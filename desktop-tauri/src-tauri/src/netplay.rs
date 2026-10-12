// Jeu à plusieurs en réseau local, côté application (même protocole que l'application Android et que
// le moteur intégré, desktop/native/player/src/netplay.h) : annonce de ce PC, appareils RomCloud du
// réseau et parties qu'ils proposent ; règles des jeux jouables à plusieurs ; arguments du moteur
// intégré (partie proposée ou rejointe). La partie elle-même (touches image par image) est dans le moteur.
use crate::error::{AppError, Result};
use crate::library::s;
use crate::{account, events, launcher, managed, relay, settings};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddrV4, UdpSocket};
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

const PORT: u16 = 47321;
const VERSION: i64 = 1;
const BEACON: Duration = Duration::from_secs(2);
/// Appareil muet depuis ce délai : retiré de la liste.
const TTL: Duration = Duration::from_secs(7);

/// Consoles de salon et bornes d'arcade (plusieurs manettes sur la même console) ; les consoles
/// portables se relient par câble ou réseau émulé, à part.
const SYSTEMS: &[&str] = &[
    "nes", "fds", "snes", "snesmsu1", "satellaview", "n64", "psx", "genesis", "genesismsu", "segacd", "sega32x", "pico",
    "master", "sg1000", "saturn", "tg16", "tgcd", "supergrafx", "pcfx", "neogeo", "neogeocd", "cps1", "cps2", "cps3",
    "fbneo", "mame", "atari2600", "atari5200", "atari7800", "jaguar", "coleco", "colecovision", "intellivision", "msx",
    "msx2", "3do",
];

/// Cœurs trop lourds, dont l'état est trop gros pour être copié en cours de partie, ou qui ne tiennent
/// pas une partie synchronisée (vérifié entre deux moteurs ; même liste que NetplayRules.kt) :
/// parallel_n64 s'arrête net après le départ ; Yabause, YabaSanshiro, Kronos et Ymir (Saturn,
/// émulation répartie sur plusieurs fils) et SMS Plus divergent à chaque contrôle.
const EXCLUDED_CORES: &[&str] = &[
    "dolphin", "pcsx2", "play", "lrps2", "pcee2", "armsx2", "flycast", "citra", "azahar", "panda3ds", "ppsspp", "melonds",
    "melondsds", "desmume", "desmume2015", "parallel_n64", "yabause", "yabasanshiro", "kronos", "ymir", "smsplus",
];

/// Liaison propre à un cœur (même règles que LinkRules.kt) : chaque appareil émule sa console, avec
/// son propre jeu. `packets` : paquets du cœur transmis par le moteur (interface netpacket, gpSP),
/// sinon connexion ouverte par le cœur vers l'hôte (options imposées) ; `multi` : plus de deux consoles.
pub struct Link {
    pub id: &'static str,
    pub core: &'static str,
    pub packets: bool,
    pub multi: bool,
}

/// Câble Game Link (Gambatte), câble et adaptateur sans fil de la GBA (gpSP), ad hoc de la PSP (PPSSPP).
const LINKS: &[Link] = &[
    Link { id: "gb", core: "gambatte", packets: false, multi: false },
    Link { id: "gba", core: "gpsp", packets: true, multi: false },
    Link { id: "psp", core: "ppsspp", packets: false, multi: true },
];
const LINK_SYSTEMS: &[(&str, &str)] = &[("gb", "gb"), ("gbc", "gb"), ("gbcolor", "gb"), ("gba", "gba"), ("psp", "psp")];
const GAMBATTE_PORT: &str = "56400";
/// Décalage des ports des jeux PSP (valeur par défaut de PPSSPP hors libretro, comme LinkRules.kt).
const PSP_PORT_OFFSET: &str = "10000";

struct Entry {
    peer: Value,
    seen: Instant,
    hosting_seen: Option<Instant>,
    /// Dernière annonce « en partie » (du jeu) : gardée entre les annonces de l'application.
    busy_seen: Option<Instant>,
}

static PEERS: LazyLock<Mutex<HashMap<String, Entry>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// Annonce de ce PC par son moteur (même identifiant) : partie proposée, « en partie », heure.
static OWN: Mutex<Option<(Value, bool, Instant)>> = Mutex::new(None);
/// Port d'envoi des annonces de l'application : reçues aussi par ce PC, elles ne viennent pas du moteur.
static APP_BEACON_PORT: AtomicU16 = AtomicU16::new(0);
/// Appareils vus par Internet (profils connectés au même serveur).
static INTERNET: Mutex<Vec<Value>> = Mutex::new(Vec::new());
/// Dernier aller-retour mesuré jusqu'au serveur (ms).
static RTT: AtomicU64 = AtomicU64::new(0);
/// Dernière activité de l'utilisateur dans l'application (ms depuis 1970) : pastille du multijoueur.
static LAST_ACTIVITY: AtomicU64 = AtomicU64::new(0);
/// Annonce au serveur à refaire tout de suite (partie proposée ou état changé).
static WAKE: LazyLock<tokio::sync::Notify> = LazyLock::new(tokio::sync::Notify::new);
/// Relais de la partie en cours (moteur ouvert) : arrêtés quand il se ferme.
static STOP: Mutex<Option<tokio::sync::watch::Sender<bool>>> = Mutex::new(None);
const INTERNET_ANNOUNCE: Duration = Duration::from_secs(10);

/// Identifiant de ce PC (le même pour l'application et le moteur : un appareil ne se voit pas lui-même).
pub fn device_id() -> String {
    let saved = settings::get_str("deviceId");
    if !saved.is_empty() {
        return saved;
    }
    let id = hex::encode(rand::random::<[u8; 16]>());
    settings::save(json!({ "deviceId": id }));
    id
}

pub fn device_name() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".into())
}

/// Annonce et écoute, jusqu'à la fermeture de l'application.
pub fn start() {
    std::thread::spawn(listen);
    std::thread::spawn(announce);
    // Par Internet (profil connecté) : annonce au serveur toutes les 10 s, tout de suite si la partie change.
    tauri::async_runtime::spawn(async {
        loop {
            announce_internet().await;
            tokio::select! { _ = tokio::time::sleep(INTERNET_ANNOUNCE) => {}, _ = WAKE.notified() => {} }
        }
    });
}

/// Activité de l'utilisateur (touche, souris, manette) : joueur « présent » pour les autres.
pub fn touch_activity() {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
    LAST_ACTIVITY.store(now, Ordering::Relaxed);
}

/// Moteur de ce PC ouvert (il s'annonce) : partie proposée et « en partie ».
pub fn own_state() -> Value {
    match OWN.lock().unwrap().clone() {
        Some((hosting, busy, at)) if at.elapsed() < TTL => json!({ "running": true, "hosting": !hosting.is_null(), "busy": busy }),
        _ => json!({ "running": false, "hosting": false, "busy": false }),
    }
}

/// Annonce au serveur tout de suite (avant de créer ou rejoindre un lobby).
pub async fn announce_now() {
    announce_internet().await;
}

/// Annonce de ce PC au serveur (partie proposée par son moteur, si elle passe par le relais) ;
/// appareils connectés en retour.
async fn announce_internet() {
    if !account::signed_in() {
        if !INTERNET.lock().unwrap().is_empty() {
            INTERNET.lock().unwrap().clear();
            publish();
        }
        return;
    }
    let (hosting, busy) = match OWN.lock().unwrap().clone() {
        Some((h, b, at)) if at.elapsed() < TTL => (h.get("session").filter(|s| s.is_string()).map(|_| h.clone()).unwrap_or(Value::Null), b),
        _ => (Value::Null, false),
    };
    let body = json!({
        "deviceId": device_id(), "name": device_name(), "platform": "windows", "busy": busy,
        "rtt": RTT.load(Ordering::Relaxed), "hosting": hosting,
        "activeAt": match LAST_ACTIVITY.load(Ordering::Relaxed) { 0 => Value::Null, at => json!(at) },
    });
    let started = Instant::now();
    let peers: Vec<Value> = match account::play_presence(body).await {
        Ok(r) => {
            RTT.store(started.elapsed().as_millis() as u64, Ordering::Relaxed);
            r["peers"].as_array().cloned().unwrap_or_default().into_iter().map(|mut p| {
                p["internet"] = json!(true);
                p["address"] = json!("");
                p["port"] = json!(0);
                p
            }).collect()
        }
        Err(_) => Vec::new(),
    };
    *INTERNET.lock().unwrap() = peers;
    publish();
}

/// Appareils RomCloud joignables : { id, name, platform, address, port, hosting, busy } sur le réseau
/// local, plus ceux vus par Internet ({ …, internet, user, rtt }) ; un appareil vu des deux façons
/// est gardé en local.
pub fn peers() -> Value {
    let peers = PEERS.lock().unwrap();
    let mut list: Vec<Value> = peers.values().map(|e| e.peer.clone()).collect();
    for p in INTERNET.lock().unwrap().iter() {
        match list.iter_mut().find(|l| l["id"] == p["id"]) {
            None => list.push(p.clone()),
            // Vu en local sans partie (annonces du moteur pas reçues ici), proposée par Internet : relais.
            Some(local) if local["hosting"].is_null() && p["hosting"].is_object() => *local = p.clone(),
            Some(_) => {}
        }
    }
    list.sort_by_key(|p| s(p, "name").to_lowercase());
    Value::Array(list)
}

fn publish() {
    events::emit("netplay:peers", peers());
}

fn broadcast_addresses() -> Vec<Ipv4Addr> {
    let mut out = vec![Ipv4Addr::BROADCAST];
    for iface in if_addrs::get_if_addrs().unwrap_or_default() {
        if iface.is_loopback() {
            continue;
        }
        if let if_addrs::IfAddr::V4(v4) = iface.addr {
            if let Some(broadcast) = v4.broadcast {
                if !out.contains(&broadcast) {
                    out.push(broadcast);
                }
            }
        }
    }
    out
}

fn announce() {
    let Ok(socket) = UdpSocket::bind("0.0.0.0:0") else { return };
    let _ = socket.set_broadcast(true);
    if let Ok(address) = socket.local_addr() {
        APP_BEACON_PORT.store(address.port(), Ordering::Relaxed);
    }
    loop {
        let beacon = json!({ "app": "romcloud", "v": VERSION, "id": device_id(), "name": device_name(), "platform": "windows", "port": 0 });
        let bytes = beacon.to_string();
        for address in broadcast_addresses() {
            let _ = socket.send_to(bytes.as_bytes(), SocketAddrV4::new(address, PORT));
        }
        expire();
        std::thread::sleep(BEACON);
    }
}

fn listen() {
    use socket2::{Domain, Protocol, Socket, Type};
    let socket = (|| -> std::io::Result<UdpSocket> {
        let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
        socket.set_reuse_address(true)?;
        socket.set_broadcast(true)?;
        socket.bind(&SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, PORT).into())?;
        Ok(socket.into())
    })();
    let Ok(socket) = socket else { return };
    let _ = socket.set_read_timeout(Some(Duration::from_secs(1)));
    let me = device_id();
    let mut buffer = [0u8; 4096];
    loop {
        let Ok((length, from)) = socket.recv_from(&mut buffer) else { continue };
        let Ok(beacon) = serde_json::from_slice::<Value>(&buffer[..length]) else { continue };
        if s(&beacon, "app") != "romcloud" || beacon["v"].as_i64() != Some(VERSION) || s(&beacon, "id").is_empty() {
            continue;
        }
        // Annonce du moteur de ce PC : partie proposée et « en partie », reprises par l'annonce au serveur.
        if s(&beacon, "id") == me {
            // Annonce de l'application elle-même (sans partie) : elle effacerait celle du moteur.
            if from.port() == APP_BEACON_PORT.load(Ordering::Relaxed) {
                continue;
            }
            let state = (beacon.get("hosting").filter(|h| h.is_object()).cloned().unwrap_or(Value::Null), beacon["busy"] == true);
            let mut own = OWN.lock().unwrap();
            let changed = own.as_ref().map(|(h, b, _)| (h, *b)) != Some((&state.0, state.1));
            *own = Some((state.0, state.1, Instant::now()));
            drop(own);
            if changed {
                WAKE.notify_one();
            }
            continue;
        }
        merge(&beacon, &from.ip().to_string(), Instant::now());
        publish();
    }
}

/// Annonce reçue de [address] : appareil mis à jour. Le même appareil est annoncé par l'application
/// et par son jeu : la partie proposée reste tant que le jeu l'annonce.
fn merge(beacon: &Value, address: &str, now: Instant) {
    let id = s(beacon, "id").to_string();
    let mut peers = PEERS.lock().unwrap();
    let previous = peers.get(&id);
    let hosting = beacon.get("hosting").filter(|h| h.is_object());
    let (port, hosting, hosting_seen) = match (hosting, previous) {
        (Some(h), _) => (beacon["port"].clone(), h.clone(), Some(now)),
        (None, Some(p)) if p.hosting_seen.is_some_and(|t| now.duration_since(t) < TTL) => (p.peer["port"].clone(), p.peer["hosting"].clone(), p.hosting_seen),
        _ => (json!(0), Value::Null, None),
    };
    let busy_seen = if beacon["busy"] == true {
        Some(now)
    } else {
        previous.and_then(|p| p.busy_seen).filter(|t| now.duration_since(*t) < TTL)
    };
    let peer = json!({
        "id": id, "name": beacon["name"], "platform": beacon["platform"], "address": address,
        "port": port, "hosting": hosting, "busy": busy_seen.is_some(),
    });
    peers.insert(id, Entry { peer, seen: now, hosting_seen, busy_seen });
}

fn expire() {
    let now = Instant::now();
    let mut changed = false;
    {
        let mut peers = PEERS.lock().unwrap();
        let before = peers.len();
        peers.retain(|_, e| now.duration_since(e.seen) < TTL);
        changed |= peers.len() != before;
        for entry in peers.values_mut() {
            if entry.hosting_seen.is_some_and(|t| now.duration_since(t) >= TTL) {
                entry.hosting_seen = None;
                entry.peer["hosting"] = Value::Null;
                entry.peer["port"] = json!(0);
                changed = true;
            }
            if entry.busy_seen.is_some_and(|t| now.duration_since(t) >= TTL) {
                entry.busy_seen = None;
                entry.peer["busy"] = json!(false);
                changed = true;
            }
        }
    }
    if changed {
        publish();
    }
}

/// Nombre de joueurs maximal d'après le champ « joueurs » du scraping (« 1-2 », « 4 »…), 0 si inconnu.
pub fn max_players(players: &str) -> u32 {
    players.split(|c: char| !c.is_ascii_digit()).filter_map(|n| n.parse().ok()).max().unwrap_or(0)
}

pub fn core_allows(core: &str) -> bool {
    !core.is_empty() && !EXCLUDED_CORES.contains(&core) && managed::by_core(core).is_none()
}

/// Système jouable à plusieurs avec l'émulateur choisi (moteur intégré, cœur compatible).
pub fn system_allows(system: &Value) -> bool {
    let known = [s(system, "id"), s(system, "shortname")].iter().any(|id| SYSTEMS.contains(&id.to_lowercase().as_str()));
    if !known {
        return false;
    }
    let opts = launcher::options(system);
    let selected = opts["options"].as_array().and_then(|l| l.iter().find(|o| o["id"] == opts["selected"]).cloned()).unwrap_or(Value::Null);
    selected["kind"] == "builtin" && core_allows(s(&selected, "core"))
}

pub fn link_by_id(id: &str) -> Option<&'static Link> {
    LINKS.iter().find(|l| l.id == id)
}

/// Liaison de la console du système, s'il se relie à une autre.
pub fn link_for(system: &Value) -> Option<&'static Link> {
    let ids = [s(system, "id").to_lowercase(), s(system, "shortname").to_lowercase()];
    LINK_SYSTEMS.iter().find(|(id, _)| ids.iter().any(|i| i == id)).and_then(|(_, link)| link_by_id(link))
}

/// Façon de jouer à plusieurs avec ce système : "netplay" (jeu synchronisé), "link" (consoles reliées) ou null.
/**
 * Cœur du moteur intégré pour jouer ce système en réseau : celui de l'émulateur choisi s'il convient,
 * sinon le premier cœur intégré compatible proposé pour le système (émulateur externe choisi).
 */
pub fn netplay_core(system: &Value) -> Option<String> {
    let known = [s(system, "id"), s(system, "shortname")].iter().any(|id| SYSTEMS.contains(&id.to_lowercase().as_str()));
    if !known {
        return None;
    }
    let opts = launcher::options(system);
    let list = opts["options"].as_array().cloned().unwrap_or_default();
    let allowed = |o: &&Value| o["kind"] == "builtin" && core_allows(s(o, "core"));
    list.iter().find(|o| o["id"] == opts["selected"]).filter(allowed).or_else(|| list.iter().find(allowed)).map(|o| s(o, "core").to_string())
}

/**
 * Systèmes que ce PC sait jouer à plusieurs avec un émulateur intégré disponible (même s'il n'est pas
 * choisi), ou relier à une autre console : envoyés en créant ou en rejoignant un lobby.
 */
pub fn capable_systems(systems: &[Value]) -> Vec<Value> {
    systems.iter().filter(|sys| !system_together(sys).is_null()).map(|sys| json!(s(sys, "id"))).collect()
}

pub fn system_together(system: &Value) -> Value {
    if netplay_core(system).is_some() {
        json!("netplay")
    } else if link_for(system).is_some() {
        json!("link")
    } else {
        Value::Null
    }
}

/// Adresse IPv4 en 12 chiffres, comme les options des cœurs (« 192.168.1.5 » -> « 192168001005 »).
fn ip_digits(address: &str) -> Option<String> {
    let parts: Vec<u8> = address.trim().split('.').map(|p| p.parse().ok()).collect::<Option<_>>()?;
    (parts.len() == 4).then(|| parts.iter().map(|n| format!("{n:03}")).collect())
}

/// Adresse IPv4 de ce PC sur le réseau local : celle de la route par défaut (socket UDP « connectée »,
/// rien n'est envoyé), sinon la première adresse privée d'une interface active.
fn local_address() -> Option<String> {
    let routed = UdpSocket::bind("0.0.0.0:0").and_then(|s| s.connect("8.8.8.8:53").and_then(|_| s.local_addr())).ok();
    if let Some(std::net::SocketAddr::V4(a)) = routed {
        if !a.ip().is_unspecified() && !a.ip().is_loopback() {
            return Some(a.ip().to_string());
        }
    }
    if_addrs::get_if_addrs().ok()?.into_iter().filter(|iface| !iface.is_loopback()).find_map(|iface| match iface.addr {
        if_addrs::IfAddr::V4(v4) if v4.ip.is_private() => Some(v4.ip.to_string()),
        _ => None,
    })
}

/// Adresse MAC (12 chiffres hexadécimaux) tirée de l'identifiant de l'appareil, administrée localement.
fn mac(device_id: &str) -> String {
    let mut hash: u64 = 1469598103934665603;
    for c in device_id.chars() {
        hash = (hash ^ c as u64).wrapping_mul(1099511628211);
    }
    let mut bytes: Vec<u8> = (0..6).map(|i| (hash >> (i * 8)) as u8).collect();
    bytes[0] = (bytes[0] & 0xfc) | 0x02;
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Options du cœur imposées pour la liaison (« --option clé=valeur ») : hôte (serveur) ou invité relié à [host_address] ;
/// [psp_server] : PSP par le serveur RomCloud (ports locaux de PPSSPP reliés au serveur, relay::psp_bridges).
fn link_options(link: &Link, host: bool, host_address: &str, psp_server: bool) -> Vec<String> {
    let mut options: Vec<(String, String)> = Vec::new();
    let digits = ip_digits(host_address).unwrap_or_default();
    match link.id {
        "gb" => {
            options.push(("gambatte_gb_link_mode".into(), if host { "Network Server" } else { "Network Client" }.into()));
            options.push(("gambatte_gb_link_network_port".into(), GAMBATTE_PORT.into()));
            if !host {
                for (i, d) in digits.chars().enumerate() {
                    options.push((format!("gambatte_gb_link_network_server_ip_{}", i + 1), d.to_string()));
                }
            }
        }
        "psp" => {
            options.push(("ppsspp_enable_wlan".into(), "enabled".into()));
            // Ports des jeux décalés (PSP : souvent sous 1024, interdits aux applications Android) ;
            // le même décalage sur toutes les consoles reliées (port de l'autre calculé avec).
            options.push(("ppsspp_port_offset".into(), PSP_PORT_OFFSET.into()));
            // Par le serveur RomCloud : pas de serveur intégré, celui de la boucle locale (relié au serveur),
            // en mode relais (toutes les données passent par le serveur, voir psp_relay_server_entry).
            options.push(("ppsspp_enable_builtin_pro_ad_hoc_server".into(), if host && !psp_server { "enabled" } else { "disabled" }.into()));
            // Hôte sur le réseau local : relié à son propre serveur par son adresse sur le réseau local, pas
            // par « localhost » : le serveur annonce chaque console aux autres avec l'adresse de sa
            // connexion (127.0.0.1 sinon, injoignable pour les invités).
            let server = if psp_server {
                ip_digits(PSP_LOOPBACK)
            } else if host {
                local_address().and_then(|a| ip_digits(&a))
            } else {
                Some(digits.clone())
            };
            match server.filter(|d| !d.is_empty()) {
                Some(server) => {
                    options.push(("ppsspp_change_pro_ad_hoc_server_address".into(), "IP address".into()));
                    for (i, d) in server.chars().enumerate() {
                        options.push((format!("ppsspp_pro_ad_hoc_server_address{:02}", i + 1), d.to_string()));
                    }
                }
                None => options.push(("ppsspp_change_pro_ad_hoc_server_address".into(), "localhost".into())),
            }
            for (i, d) in mac(&device_id()).chars().enumerate() {
                options.push((format!("ppsspp_change_mac_address{:02}", i + 1), d.to_string()));
            }
        }
        _ => {}  // gpSP : câble choisi d'après le jeu (option « Automatic »)
    }
    options.into_iter().flat_map(|(k, v)| ["--option".to_string(), format!("{k}={v}")]).collect()
}

/// Ce jeu, annoncé pour une liaison (cœur de la liaison).
fn link_game(system: &Value, game: &Value, link: &Link) -> Value {
    json!({
        "gameId": game["id"], "systemId": s(system, "id"), "title": s(game, "title"),
        "fileName": s(game, "fileName"), "size": game["size"], "core": link.core, "link": link.id,
    })
}

fn link_flags(link: &Link) -> Vec<String> {
    let mut args = vec!["--netplay-link".to_string(), link.id.to_string()];
    if link.packets {
        args.push("--netplay-packets".into());
    }
    if link.multi {
        args.push("--netplay-multi".into());
    }
    args
}

/**
 * Partie proposée aussi par Internet (profil connecté) : identifiant de relais ([session], sinon
 * nouveau) ajouté au jeu annoncé, port d'écoute fixe du moteur, relié au relais tant que le moteur
 * est ouvert ([link_port] : serveur du câble ouvert par le cœur, relié aussi).
 */
fn internet_host(game: &mut Value, link_port: Option<u16>, session: Option<String>) -> Vec<String> {
    if !account::signed_in() {
        return Vec::new();
    }
    let Some(port) = std::net::TcpListener::bind("0.0.0.0:0").ok().and_then(|l| l.local_addr().ok()).map(|a| a.port()) else {
        return Vec::new();
    };
    let session = session.unwrap_or_else(relay::new_session);
    game["session"] = json!(session);
    let stop = new_stop();
    let s1 = session.clone();
    tauri::async_runtime::spawn(relay::host_loop(move || account::relay_request(&s1, relay::CHANNEL_PLAY, "host"), port, stop.clone()));
    if let Some(link_port) = link_port {
        let s2 = session.clone();
        tauri::async_runtime::spawn(relay::host_loop(move || account::relay_request(&s2, relay::CHANNEL_LINK, "host"), link_port, stop));
    }
    vec!["--netplay-port".into(), port.to_string()]
}

/// Adresse du serveur ad hoc donnée à PPSSPP pour la PSP par le serveur RomCloud (passerelles locales).
const PSP_LOOPBACK: &str = "127.0.0.1";

/**
 * PSP proposée (profil connecté, serveur joignable) : réseau ad hoc de la partie sur le serveur, pour
 * les invités par Internet comme sur le réseau local ; ports locaux de PPSSPP reliés à lui. Renvoie
 * les arguments de la partie par Internet et vrai dans ce cas ; sinon (sans profil, ports pris) :
 * rien, jeu direct sur le réseau local (serveur intégré de PPSSPP).
 */
fn psp_host(hosted: &mut Value) -> (Vec<String>, bool) {
    if !account::signed_in() || !crate::connectivity::is_online() {
        return (Vec::new(), false);
    }
    let session = relay::new_session();
    let args = internet_host(hosted, None, Some(session.clone()));
    if args.is_empty() {
        return (Vec::new(), false);
    }
    // Passerelles arrêtées avec les relais de la partie (fermeture du moteur).
    let Some(stop) = STOP.lock().unwrap().as_ref().map(|tx| tx.subscribe()) else { return (args, false) };
    if !relay::psp_bridges(move |kind| account::psp_request(&session, kind), stop) {
        // Ports de PPSSPP pris (autre émulateur ouvert) : réseau local seulement.
        player_exited();
        if let Some(game) = hosted.as_object_mut() {
            game.remove("session");
        }
        return (Vec::new(), false);
    }
    psp_relay_server_entry();
    (args, true)
}

/// Invité d'une partie PSP acceptée : réseau ad hoc sur le serveur si l'hôte l'y a ouvert (session de la partie).
fn psp_guest(join: &Value) -> Result<Value> {
    let session = s(&join["game"], "session").to_string();
    if session.is_empty() {
        return Ok(join.clone());
    }
    if account::psp_request(&session, "adhocctl").is_none() {
        return Err(AppError::new("errors.signedOut", json!({})));
    }
    if !relay::psp_bridges(move |kind| account::psp_request(&session, kind), new_stop()) {
        return Err(AppError::new("errors.linkUnreachable", json!({ "name": s(join, "peerName"), "detail": "port 27312" })));
    }
    psp_relay_server_entry();
    let mut local = join.clone();
    local["pspServer"] = json!(true);
    Ok(local)
}

/**
 * PPSSPP : le serveur ad hoc de la boucle locale déclaré « avec relais » (mode AEMU postoffice :
 * toutes les données par le serveur) dans la liste des serveurs qu'il lit hors connexion,
 * <BIOS>/PPSSPP/adhoc-servers.json ; les autres serveurs de la liste sont gardés.
 */
fn psp_relay_server_entry() {
    let path = settings::bios_dir().join("PPSSPP").join("adhoc-servers.json");
    let _ = std::fs::create_dir_all(path.parent().unwrap_or(&path));
    let current = std::fs::read_to_string(&path).unwrap_or_default();
    if let Some(text) = with_psp_relay_entry(&current) {
        let _ = std::fs::write(&path, text);
    }
}

/// Liste des serveurs ad hoc de PPSSPP ([current], vide ou invalide : nouvelle) avec l'entrée du
/// serveur de la boucle locale en mode relais ; None si elle y est déjà.
fn with_psp_relay_entry(current: &str) -> Option<String> {
    let mut root: Value = serde_json::from_str(current).ok().filter(Value::is_object).unwrap_or_else(|| json!({}));
    let mut servers: Vec<Value> = root["servers"].as_array().cloned().unwrap_or_default();
    if servers.iter().any(|e| e["host"] == PSP_LOOPBACK && e["data_mode"] == "AemuPostoffice") {
        return None;
    }
    servers.retain(|e| e["host"] != PSP_LOOPBACK);
    servers.push(json!({ "name": "RomCloud", "host": PSP_LOOPBACK, "data_mode": "AemuPostoffice", "hidden": true }));
    root["servers"] = Value::Array(servers);
    serde_json::to_string_pretty(&root).ok()
}

/// Nouveaux relais de partie : ceux d'avant arrêtés ; arrêtés aussi à la fermeture du moteur ([player_exited]).
fn new_stop() -> tokio::sync::watch::Receiver<bool> {
    let (tx, rx) = tokio::sync::watch::channel(false);
    if let Some(old) = STOP.lock().unwrap().replace(tx) {
        let _ = old.send(true);
    }
    rx
}

/// Moteur fermé : relais de sa partie arrêtés.
pub fn player_exited() {
    if let Some(stop) = STOP.lock().unwrap().take() {
        let _ = stop.send(true);
    }
}

/// Invité par Internet ({ internet: true }) : passerelle locale vers l'hôte par le relais ; renvoie
/// l'invitation modifiée (adresse et port locaux) et le délai des touches à demander.
async fn internet_guest(join: &Value, channel: &str, port: u16) -> Result<Option<(Value, u32)>> {
    if join["internet"] != true {
        return Ok(None);
    }
    let session = s(&join["game"], "session").to_string();
    let request = account::relay_request(&session, channel, "guest").ok_or_else(|| AppError::new("errors.signedOut", json!({})))?;
    let local = relay::guest_bridge(request, port, new_stop())
        .await
        .ok_or_else(|| AppError::new("errors.linkUnreachable", json!({ "name": s(join, "peerName"), "detail": "port" })))?;
    let mut local_join = join.clone();
    local_join["address"] = json!("127.0.0.1");
    local_join["port"] = json!(local);
    let delay = relay::delay_frames(RTT.load(Ordering::Relaxed), join["rtt"].as_u64().unwrap_or(0));
    Ok(Some((local_join, delay)))
}

/// Partie rejointe : par le relais pour un appareil vu par Internet, sinon directement.
pub async fn guest_join_args(join: &Value) -> Result<Vec<String>> {
    match internet_guest(join, relay::CHANNEL_PLAY, 0).await? {
        Some((local, delay)) => {
            let mut args = join_args(&local);
            args.extend(["--netplay-delay".to_string(), delay.to_string()]);
            Ok(args)
        }
        None => Ok(join_args(join)),
    }
}

/// Liaison par paquets rejointe (gpSP) : par le relais pour un appareil vu par Internet.
pub async fn guest_link_join_args(join: &Value, system: &Value, game: &Value, link: &Link) -> Result<Vec<String>> {
    match internet_guest(join, relay::CHANNEL_PLAY, 0).await? {
        Some((local, delay)) => {
            let mut args = link_join_args(&local, system, game, link);
            args.extend(["--netplay-delay".to_string(), delay.to_string()]);
            Ok(args)
        }
        None => Ok(link_join_args(join, system, game, link)),
    }
}

/// Liaison proposée : annonce et demandes gérées par le moteur, options du cœur côté serveur.
pub fn link_host_args(system: &Value, game: &Value, link: &Link) -> Vec<String> {
    let mut hosted = link_game(system, game, link);
    let (internet, psp_server) = if link.id == "psp" {
        psp_host(&mut hosted)
    } else {
        (internet_host(&mut hosted, (link.id == "gb").then(|| GAMBATTE_PORT.parse().unwrap_or(56400)), None), false)
    };
    let mut args = vec!["--netplay-host".to_string(), "--netplay-game".into(), hosted.to_string()];
    args.extend(internet);
    args.extend(link_flags(link));
    args.extend(link_options(link, true, "", psp_server));
    args.extend(identity());
    args
}

/// Liaison par paquets (gpSP) : le moteur se connecte à l'hôte, avec ce jeu.
pub fn link_join_args(join: &Value, system: &Value, game: &Value, link: &Link) -> Vec<String> {
    let mut args = vec![
        "--netplay-join".to_string(),
        format!("{}:{}", s(join, "address"), join["port"].as_u64().unwrap_or(0)),
        "--netplay-peer".into(),
        s(join, "peerName").to_string(),
        "--netplay-game".into(),
        link_game(system, game, link).to_string(),
    ];
    args.extend(link_flags(link));
    args.extend(identity());
    args
}

/// Liaison ouverte par le cœur, déjà acceptée par l'hôte : options du cœur côté client.
pub fn linked_args(join: &Value, link: &Link) -> Vec<String> {
    let mut args = vec!["--linked".to_string(), s(join, "peerName").to_string()];
    args.extend(link_options(link, false, s(join, "address"), join["pspServer"] == true));
    args.extend(identity());
    args
}

/**
 * Invité d'une liaison ouverte par le cœur : demande à l'hôte (même échange que le moteur et
 * l'application Android : writeUTF / readUTF de Java), avant le lancement. L'hôte a 90 s pour accepter.
 */
pub async fn link_handshake(join: &Value, game: &Value, link: &Link) -> Result<Value> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let peer = s(join, "peerName").to_string();
    let unreachable = |detail: String| AppError::new("errors.linkUnreachable", json!({ "name": peer, "detail": detail }));
    // Par Internet : demande par le relais, puis câble du cœur relié à l'hôte par le relais lui aussi
    // (le cœur se connecte à ce PC).
    let internet = join["internet"] == true;
    let mut stream: relay::Stream = if internet {
        let session = s(&join["game"], "session").to_string();
        let request = account::relay_request(&session, relay::CHANNEL_PLAY, "guest").ok_or_else(|| AppError::new("errors.signedOut", json!({})))?;
        relay::open(&request).await.map_err(|e| if e.status == 404 { AppError::new("errors.linkBusy", json!({ "name": peer })) } else { unreachable(e.detail) })?
    } else {
        let address = format!("{}:{}", s(join, "address"), join["port"].as_u64().unwrap_or(0));
        Box::new(
            tokio::time::timeout(Duration::from_secs(5), tokio::net::TcpStream::connect(&address))
                .await
                .map_err(|_| unreachable("timeout".into()))?
                .map_err(|e| unreachable(e.to_string()))?,
        )
    };
    let request = json!({
        "v": VERSION, "name": device_name(), "platform": "windows", "fileName": s(game, "fileName"),
        "size": game["size"].as_i64().unwrap_or(0), "core": link.core, "coreSize": 0, "link": link.id,
    })
    .to_string();
    let bytes = request.as_bytes();
    let mut message = (bytes.len() as u16).to_be_bytes().to_vec();
    message.extend_from_slice(bytes);
    stream.write_all(&message).await.map_err(|e| unreachable(e.to_string()))?;
    let answer = tokio::time::timeout(Duration::from_secs(90), async {
        let mut head = [0u8; 2];
        stream.read_exact(&mut head).await?;
        let mut body = vec![0u8; u16::from_be_bytes(head) as usize];
        stream.read_exact(&mut body).await?;
        Ok::<_, std::io::Error>(body)
    })
    .await
    .map_err(|_| AppError::new("errors.linkRefused", json!({ "name": peer })))?
    .map_err(|e| unreachable(e.to_string()))?;
    let answer: Value = serde_json::from_slice(&answer).unwrap_or(Value::Null);
    if answer["ok"] == true {
        // PSP : réseau ad hoc de la partie sur le serveur (hôte connecté à un profil), sinon direct.
        if link.id == "psp" {
            return psp_guest(join);
        }
        if !internet {
            return Ok(join.clone());
        }
        let port = GAMBATTE_PORT.parse().unwrap_or(56400);
        internet_guest(join, relay::CHANNEL_LINK, port).await?;
        let mut local = join.clone();
        local["address"] = json!("127.0.0.1");
        return Ok(local);
    }
    Err(match s(&answer, "reason") {
        "busy" => AppError::new("errors.linkBusy", json!({ "name": peer })),
        "version" => AppError::new("errors.linkVersion", json!({})),
        "game" | "core" => AppError::new("errors.linkOther", json!({})),
        _ => AppError::new("errors.linkRefused", json!({ "name": peer })),
    })
}

/// Arguments du moteur intégré : partie proposée ([core] : cœur choisi) ou rejointe ([join] : adresse,
/// port et nom de l'hôte, jeu annoncé).
pub fn host_args(system: &Value, game: &Value, core: &str) -> Vec<String> {
    let mut hosted = json!({
        "gameId": game["id"], "systemId": s(system, "id"), "title": s(game, "title"),
        "fileName": s(game, "fileName"), "size": game["size"], "core": core,
    });
    let internet = internet_host(&mut hosted, None, None);
    let mut args = vec!["--netplay-host".to_string(), "--netplay-game".into(), hosted.to_string()];
    args.extend(internet);
    args.extend(netplay_options());
    args.extend(identity());
    args
}

pub fn join_args(join: &Value) -> Vec<String> {
    let mut args = join_game_args(join);
    args.extend(netplay_options());
    args.extend(identity());
    args
}

fn join_game_args(join: &Value) -> Vec<String> {
    vec![
        "--netplay-join".to_string(),
        format!("{}:{}", s(join, "address"), join["port"].as_u64().unwrap_or(0)),
        "--netplay-peer".into(),
        s(join, "peerName").to_string(),
        "--netplay-game".into(),
        join["game"].to_string(),
    ]
}

/// Options des cœurs imposées en jeu synchronisé (même règle que NetplayRules.CORE_OPTIONS) : ce
/// qui dépend de l'appareil et changerait la partie (meilleurs scores enregistrés de FBNeo).
const NETPLAY_OPTIONS: &[(&str, &str)] = &[("fbneo-hiscores", "disabled")];

fn netplay_options() -> Vec<String> {
    NETPLAY_OPTIONS.iter().flat_map(|(k, v)| ["--option".to_string(), format!("{k}={v}")]).collect()
}

fn identity() -> Vec<String> {
    vec!["--device-id".into(), device_id(), "--device-name".into(), device_name()]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nombre_de_joueurs() {
        assert_eq!(["1-2", "1 - 4", "1", "", "2 simultanés"].map(max_players), [2, 4, 1, 0, 2]);
    }

    #[test]
    fn coeurs_compatibles() {
        assert!(core_allows("snes9x"));
        assert!(!core_allows("dolphin"));
        assert!(!core_allows("yabause"));
        assert!(core_allows("mednafen_saturn"));
        assert!(!core_allows("rpcs3")); // émulateur géré par RomCloud, pas le moteur intégré
        assert!(!core_allows(""));
    }

    #[test]
    fn partie_annoncee_gardee_entre_les_annonces_de_l_application() {
        let now = Instant::now();
        let game = json!({ "gameId": 1, "systemId": "snes", "title": "SSF2", "fileName": "a.sfc", "size": 4, "core": "snes9x" });
        merge(&json!({ "id": "pc-test", "name": "PC", "platform": "windows", "port": 4000, "hosting": game }), "192.168.1.5", now);
        merge(&json!({ "id": "pc-test", "name": "PC", "platform": "windows", "port": 0 }), "192.168.1.5", now + Duration::from_secs(1));
        let peers = peers();
        let peer = peers.as_array().unwrap().iter().find(|p| p["id"] == "pc-test").unwrap();
        assert_eq!(peer["port"], 4000);
        assert_eq!(peer["hosting"]["core"], "snes9x");
        assert_eq!(peer["address"], "192.168.1.5");
    }

    #[test]
    fn appareil_en_partie_garde_entre_les_annonces_de_l_application() {
        let now = Instant::now();
        merge(&json!({ "id": "pc-busy", "name": "PC", "platform": "windows", "port": 0, "busy": true }), "192.168.1.6", now);
        merge(&json!({ "id": "pc-busy", "name": "PC", "platform": "windows", "port": 0 }), "192.168.1.6", now + Duration::from_secs(1));
        let peers = peers();
        assert_eq!(peers.as_array().unwrap().iter().find(|p| p["id"] == "pc-busy").unwrap()["busy"], true);
    }

    #[test]
    fn liaisons() {
        assert_eq!(link_for(&json!({ "id": "gbc", "shortname": "gbc" })).map(|l| l.core), Some("gambatte"));
        assert_eq!(system_together(&json!({ "id": "gba", "shortname": "gba" })), json!("link"));
        assert_eq!(ip_digits("192.168.1.5").as_deref(), Some("192168001005"));
        assert_eq!(ip_digits("fe80::1"), None);
        let options = link_options(link_by_id("gb").unwrap(), false, "10.0.0.42", false);
        assert!(options.contains(&"gambatte_gb_link_mode=Network Client".to_string()));
        assert!(options.contains(&"gambatte_gb_link_network_server_ip_2=1".to_string()));
        assert!(options.contains(&"gambatte_gb_link_network_server_ip_12=2".to_string()));
        // Hôte de la PSP : relié à son serveur par son adresse sur le réseau, pas par 127.0.0.1.
        let host = link_options(link_by_id("psp").unwrap(), true, "", false);
        assert!(host.contains(&"ppsspp_port_offset=10000".to_string()));
        assert!(host.contains(&"ppsspp_enable_builtin_pro_ad_hoc_server=enabled".to_string()));
        if local_address().is_some() {
            assert!(host.contains(&"ppsspp_change_pro_ad_hoc_server_address=IP address".to_string()));
        }
        // PSP par le serveur RomCloud : ni hôte ni invité n'ouvrent de serveur, tous reliés à 127.0.0.1.
        for host in [true, false] {
            let options = link_options(link_by_id("psp").unwrap(), host, "192.168.1.5", true);
            assert!(options.contains(&"ppsspp_enable_builtin_pro_ad_hoc_server=disabled".to_string()));
            assert!(options.contains(&"ppsspp_pro_ad_hoc_server_address01=1".to_string()));
            assert!(options.contains(&"ppsspp_pro_ad_hoc_server_address04=0".to_string()));
            assert!(options.contains(&"ppsspp_pro_ad_hoc_server_address12=1".to_string()));
        }
        assert_eq!(mac("a").len(), 12);
        assert_ne!(mac("a"), mac("b"));
        assert_eq!(u8::from_str_radix(&mac("a")[..2], 16).unwrap() & 3, 2);
    }

    #[test]
    fn serveur_ad_hoc_en_mode_relais_pour_ppsspp() {
        // Liste absente ou invalide : créée avec l'entrée de la boucle locale en mode relais.
        let created: Value = serde_json::from_str(&with_psp_relay_entry("").unwrap()).unwrap();
        assert_eq!(created["servers"][0]["host"], "127.0.0.1");
        assert_eq!(created["servers"][0]["data_mode"], "AemuPostoffice");
        // Liste de PPSSPP gardée ; ancienne entrée 127.0.0.1 sans relais remplacée ; rien à changer ensuite.
        let existing = r#"{"servers":[{"name":"Socom","host":"socom.cc"},{"name":"Local","host":"127.0.0.1"}]}"#;
        let merged_text = with_psp_relay_entry(existing).unwrap();
        let merged: Value = serde_json::from_str(&merged_text).unwrap();
        let servers = merged["servers"].as_array().unwrap();
        assert_eq!(servers.len(), 2);
        assert_eq!(servers[0]["host"], "socom.cc");
        assert_eq!(servers[1]["data_mode"], "AemuPostoffice");
        assert_eq!(with_psp_relay_entry(&merged_text), None);
    }

    #[test]
    fn arguments_du_moteur() {
        let args = join_game_args(&json!({ "address": "192.168.1.5", "port": 4000, "peerName": "Pixel", "game": { "core": "snes9x" } }));
        assert_eq!(&args[..4], ["--netplay-join", "192.168.1.5:4000", "--netplay-peer", "Pixel"]);
        assert_eq!(args[5], r#"{"core":"snes9x"}"#);
    }
}
