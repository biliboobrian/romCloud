// Jeu à plusieurs en réseau local, côté application (même protocole que l'application Android et que
// le moteur intégré, desktop/native/player/src/netplay.h) : annonce de ce PC, appareils RomCloud du
// réseau et parties qu'ils proposent ; règles des jeux jouables à plusieurs ; arguments du moteur
// intégré (partie proposée ou rejointe). La partie elle-même (touches image par image) est dans le moteur.
use crate::library::s;
use crate::{events, launcher, managed, settings};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddrV4, UdpSocket};
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

/// Cœurs trop lourds, ou dont l'état est trop gros pour être copié en cours de partie.
const EXCLUDED_CORES: &[&str] = &[
    "dolphin", "pcsx2", "play", "lrps2", "pcee2", "armsx2", "flycast", "citra", "azahar", "panda3ds", "ppsspp", "melonds",
    "melondsds", "desmume", "desmume2015",
];

struct Entry {
    peer: Value,
    seen: Instant,
    hosting_seen: Option<Instant>,
}

static PEERS: LazyLock<Mutex<HashMap<String, Entry>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

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
}

/// Appareils RomCloud du réseau local : { id, name, platform, address, port, hosting }.
pub fn peers() -> Value {
    let peers = PEERS.lock().unwrap();
    let mut list: Vec<Value> = peers.values().map(|e| e.peer.clone()).collect();
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
        if s(&beacon, "app") != "romcloud" || beacon["v"].as_i64() != Some(VERSION) || s(&beacon, "id") == me || s(&beacon, "id").is_empty() {
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
    let peer = json!({
        "id": id, "name": beacon["name"], "platform": beacon["platform"], "address": address,
        "port": port, "hosting": hosting,
    });
    peers.insert(id, Entry { peer, seen: now, hosting_seen });
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

/// Arguments du moteur intégré : partie proposée ([core] : cœur choisi) ou rejointe ([join] : adresse,
/// port et nom de l'hôte, jeu annoncé).
pub fn host_args(system: &Value, game: &Value, core: &str) -> Vec<String> {
    let hosted = json!({
        "gameId": game["id"], "systemId": s(system, "id"), "title": s(game, "title"),
        "fileName": s(game, "fileName"), "size": game["size"], "core": core,
    });
    let mut args = vec!["--netplay-host".to_string(), "--netplay-game".into(), hosted.to_string()];
    args.extend(identity());
    args
}

pub fn join_args(join: &Value) -> Vec<String> {
    let mut args = join_game_args(join);
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
    fn arguments_du_moteur() {
        let args = join_game_args(&json!({ "address": "192.168.1.5", "port": 4000, "peerName": "Pixel", "game": { "core": "snes9x" } }));
        assert_eq!(&args[..4], ["--netplay-join", "192.168.1.5:4000", "--netplay-peer", "Pixel"]);
        assert_eq!(args[5], r#"{"core":"snes9x"}"#);
    }
}
