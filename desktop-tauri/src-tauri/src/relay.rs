// Jeu à plusieurs par Internet : relais du serveur RomCloud (server/src/play.js). Les deux PC ouvrent
// une connexion vers le serveur (requête HTTP « Upgrade », http ou https), qui fait passer les octets
// de l'une à l'autre : rien à ouvrir sur les box. Le moteur de jeu, lui, garde sa connexion locale
// habituelle : ce module la relie au relais (passerelles sur la boucle locale).
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;

pub const CHANNEL_PLAY: &str = "play";
pub const CHANNEL_LINK: &str = "link";

/// Connexion de relais (chiffrée ou non).
pub trait Io: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Io for T {}
pub type Stream = Box<dyn Io>;

/// Connexion à ouvrir : adresse complète et en-têtes (clé d'API, session du joueur).
#[derive(Clone)]
pub struct Request {
    pub url: String,
    pub headers: Vec<(String, String)>,
}

/// Échec : statut HTTP (0 : réseau) et détail.
#[derive(Debug)]
pub struct Refused {
    pub status: u16,
    pub detail: String,
}

/// Ouvre une connexion de relais ; renvoie le flux une fois accepté (statut 101).
pub async fn open(request: &Request) -> Result<Stream, Refused> {
    let fail = |detail: String| Refused { status: 0, detail };
    let url = reqwest::Url::parse(&request.url).map_err(|e| fail(e.to_string()))?;
    let host = url.host_str().ok_or_else(|| fail("host".into()))?.to_string();
    let secure = url.scheme() == "https";
    let port = url.port_or_known_default().unwrap_or(80);
    let tcp = tokio::time::timeout(Duration::from_secs(8), TcpStream::connect((host.as_str(), port)))
        .await
        .map_err(|_| fail("timeout".into()))?
        .map_err(|e| fail(e.to_string()))?;
    let _ = tcp.set_nodelay(true);
    let mut stream: Stream = if secure {
        let connector = tokio_native_tls::native_tls::TlsConnector::new().map_err(|e| fail(e.to_string()))?;
        let tls = tokio_native_tls::TlsConnector::from(connector).connect(&host, tcp).await.map_err(|e| fail(e.to_string()))?;
        Box::new(tls)
    } else {
        Box::new(tcp)
    };
    let path = match url.query() {
        Some(q) => format!("{}?{}", url.path(), q),
        None => url.path().to_string(),
    };
    let authority = match url.port() {
        Some(p) => format!("{host}:{p}"),
        None => host.clone(),
    };
    let mut head = format!("GET {path} HTTP/1.1\r\nHost: {authority}\r\nConnection: Upgrade\r\nUpgrade: romcloud-relay\r\n");
    for (k, v) in &request.headers {
        head.push_str(&format!("{k}: {}\r\n", v.replace(['\r', '\n'], "")));
    }
    head.push_str("\r\n");
    stream.write_all(head.as_bytes()).await.map_err(|e| fail(e.to_string()))?;
    let status = tokio::time::timeout(Duration::from_secs(15), read_status(&mut stream))
        .await
        .map_err(|_| fail("timeout".into()))?
        .map_err(|e| fail(e.to_string()))?;
    if status != 101 {
        return Err(Refused { status, detail: format!("HTTP {status}") });
    }
    Ok(stream)
}

/// En-tête de la réponse, lu octet par octet (la suite appartient à la partie) ; renvoie le statut.
async fn read_status(stream: &mut Stream) -> std::io::Result<u16> {
    let mut header = Vec::new();
    let mut byte = [0u8; 1];
    while !header.ends_with(b"\r\n\r\n") {
        if stream.read(&mut byte).await? == 0 || header.len() > 8192 {
            return Err(std::io::ErrorKind::UnexpectedEof.into());
        }
        header.push(byte[0]);
    }
    let text = String::from_utf8_lossy(&header);
    Ok(text.split_whitespace().nth(1).and_then(|s| s.parse().ok()).unwrap_or(0))
}

/// Hôte : connexion d'attente ; renvoie le flux relié à un invité (le serveur envoie 1 octet à son
/// arrivée), ou None si le relais s'est fermé avant (à rouvrir).
pub async fn await_guest(request: &Request) -> Option<Stream> {
    let mut stream = open(request).await.ok()?;
    let mut byte = [0u8; 1];
    match stream.read(&mut byte).await {
        Ok(1) if byte[0] == 1 => Some(stream),
        _ => None,
    }
}

/// Copie les octets dans les deux sens jusqu'à la fermeture de l'un des deux.
pub async fn pipe(mut a: Stream, mut b: TcpStream) {
    let _ = b.set_nodelay(true);
    let _ = tokio::io::copy_bidirectional(&mut a, &mut b).await;
}

/**
 * Hôte : tant que [stop] n'est pas levé, une connexion attend un invité sur le relais ([request] :
 * ouverte à nouveau après chaque invité ; None hors connexion) ; chaque invité est relié au port
 * local [port] (moteur de jeu, ou serveur du câble ouvert par le cœur).
 */
pub async fn host_loop(request: impl Fn() -> Option<Request>, port: u16, mut stop: watch::Receiver<bool>) {
    while !*stop.borrow() {
        let Some(req) = request() else {
            tokio::select! { _ = tokio::time::sleep(Duration::from_secs(10)) => {}, _ = stop.changed() => {} }
            continue;
        };
        let guest = tokio::select! { g = await_guest(&req) => g, _ = stop.changed() => None };
        let Some(guest) = guest else {
            tokio::time::sleep(Duration::from_secs(2)).await;
            continue;
        };
        if let Ok(local) = TcpStream::connect(("127.0.0.1", port)).await {
            tauri::async_runtime::spawn(pipe(guest, local));
        }
    }
}

/**
 * Invité : passerelle sur la boucle locale ([port], 0 : choisi) ; chaque connexion du moteur (ou
 * du cœur) est reliée à l'hôte par le relais. Renvoie le port, ou None s'il est déjà pris.
 */
pub async fn guest_bridge(request: Request, port: u16, mut stop: watch::Receiver<bool>) -> Option<u16> {
    let listener = TcpListener::bind(("127.0.0.1", port)).await.ok()?;
    let port = listener.local_addr().ok()?.port();
    tauri::async_runtime::spawn(async move {
        loop {
            let accepted = tokio::select! { a = listener.accept() => a.ok(), _ = stop.changed() => None };
            let Some((local, _)) = accepted else { break };
            match open(&request).await {
                Ok(remote) => {
                    tauri::async_runtime::spawn(pipe(remote, local));
                }
                Err(_) => drop(local),
            }
            if *stop.borrow() {
                break;
            }
        }
    });
    Some(port)
}

/// Nouvel identifiant de partie sur le relais (hôte).
pub fn new_session() -> String {
    hex::encode(rand::random::<[u8; 16]>())
}

/// Images de délai des touches par Internet (même règle que Relay.delayFrames sur Android).
pub fn delay_frames(own_rtt_ms: u64, host_rtt_ms: u64) -> u32 {
    let rtt = own_rtt_ms + host_rtt_ms + 20;
    (((rtt as f64) / 16.7).ceil() as u32 + 1).clamp(3, 15)
}

#[cfg(test)]
mod tests {
    use super::*;

    /**
     * Essai de bout en bout, à lancer à la main avec un serveur RomCloud (variable
     * ROMCLOUD_RELAY_TEST = « adresse|jeton hôte|jeton invité|partie|moteur|cœur|jeu|BIOS », partie
     * déjà annoncée par l'hôte) : deux moteurs jouent ensemble à travers le relais, l'hôte sur un port
     * relié au relais, l'invité par la passerelle locale.
     */
    #[test]
    #[ignore]
    fn partie_par_le_relais() {
        let Ok(spec) = std::env::var("ROMCLOUD_RELAY_TEST") else { return };
        let v: Vec<String> = spec.split('|').map(String::from).collect();
        let (server, host_token, guest_token, session, player, core, rom, bios) = (&v[0], &v[1], &v[2], &v[3], &v[4], &v[5], &v[6], &v[7]);
        let request = |token: &str, role: &str| Request {
            url: format!("{server}/api/play/relay?session={session}&channel=play&role={role}"),
            headers: vec![("X-RomCloud-Session".into(), token.to_string())],
        };
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let port = std::net::TcpListener::bind("0.0.0.0:0").unwrap().local_addr().unwrap().port();
            let (_tx, rx) = watch::channel(false);
            let host_request = request(host_token, "host");
            tokio::spawn(host_loop(move || Some(host_request.clone()), port, rx.clone()));
            let dir = std::env::temp_dir().join(format!("romcloud-relay-{}", std::process::id()));
            let game = format!(r#"{{"gameId":1,"systemId":"snes","title":"T","fileName":"f","size":1,"core":"{core}","session":"{session}"}}"#);
            let common = |role: &str| -> Vec<String> {
                let d = dir.join(role);
                std::fs::create_dir_all(&d).unwrap();
                let d = d.to_string_lossy().into_owned();
                vec!["--core".into(), core.clone(), "--rom".into(), rom.clone(), "--save-dir".into(), d.clone(), "--state-dir".into(), d.clone(),
                     "--options".into(), format!("{d}/o.txt"), "--system-dir".into(), bios.clone(), "--test-seconds".into(), "25".into()]
            };
            let mut host_args = common("h");
            host_args.extend(["--netplay-host".into(), "--netplay-game".into(), game.clone(), "--netplay-port".into(), port.to_string(),
                              "--device-id".into(), "H".into(), "--device-name".into(), "HOTE".into()]);
            let host = tokio::process::Command::new(player).args(&host_args).env("ROMCLOUD_NETPLAY_ACCEPT", "1")
                .stderr(std::fs::File::create(dir.join("host.log")).unwrap()).spawn().unwrap();
            tokio::time::sleep(Duration::from_secs(4)).await;
            let local = guest_bridge(request(guest_token, "guest"), 0, rx).await.unwrap();
            let mut guest_args = common("g");
            guest_args.extend(["--netplay-join".into(), format!("127.0.0.1:{local}"), "--netplay-peer".into(), "HOTE".into(),
                               "--netplay-game".into(), game, "--netplay-delay".into(), "6".into(), "--device-id".into(), "G".into(), "--device-name".into(), "INVITE".into()]);
            let guest = tokio::process::Command::new(player).args(&guest_args)
                .stderr(std::fs::File::create(dir.join("guest.log")).unwrap()).spawn().unwrap();
            let _ = guest.wait_with_output().await;
            let _ = host.wait_with_output().await;
            let log = |n: &str| std::fs::read_to_string(dir.join(n)).unwrap_or_default();
            let (h, g) = (log("host.log"), log("guest.log"));
            println!("{}", h.lines().chain(g.lines()).filter(|l| l.contains("Netplay")).collect::<Vec<_>>().join("
"));
            assert!(g.contains("partie avec HOTE") && g.contains("délai 6"), "invité : {g}");
            assert!(g.contains("(0 écart"), "écarts : {g}");
        });
    }

    #[test]
    fn delai() {
        assert_eq!(delay_frames(5, 5), 3);
        assert_eq!(delay_frames(60, 40), 9);
        assert_eq!(delay_frames(2000, 2000), 15);
    }
}
