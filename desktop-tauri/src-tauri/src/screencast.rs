// Diffusion de l'écran du PC sur un Chromecast : l'écran (et le son du PC) est capturé dans la page
// (getDisplayMedia + MediaRecorder, voir bridge.js), encodé en WebM (VP8 + Opus), envoyé ici par
// morceaux, servi en HTTP sur le réseau local et lu par le lecteur multimédia par défaut du Chromecast.
use crate::cast::{CastSession, Event};
use crate::error::{AppError, Result};
use crate::events;
use serde_json::{json, Value};
use std::sync::{Arc, LazyLock};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, Mutex};

struct Cast {
    state: Value, // { status: idle | connecting | casting, device, error }
    session: Option<Arc<CastSession>>,
    server: Option<tokio::task::JoinHandle<()>>,
    /// Requête HTTP du Chromecast en cours : morceaux du flux à lui envoyer.
    response: Option<mpsc::Sender<Vec<u8>>>,
    generation: u64,
}

static CAST: LazyLock<Mutex<Cast>> = LazyLock::new(|| {
    Mutex::new(Cast { state: json!({ "status": "idle", "device": null, "error": null }), session: None, server: None, response: None, generation: 0 })
});

fn set_state(cast: &mut Cast, patch: Value) {
    if let (Value::Object(state), Value::Object(patch)) = (&mut cast.state, patch) {
        state.extend(patch);
    }
    events::emit("cast:update", cast.state.clone());
}

pub async fn state() -> Value {
    CAST.lock().await.state.clone()
}

fn error(key: &str, detail: &str) -> Value {
    json!({ "key": key, "vars": { "detail": detail } })
}

/// Serveur HTTP : un flux par connexion du Chromecast ; l'enregistreur de la page est relancé à
/// chaque fois pour que le flux commence par l'en-tête WebM.
async fn start_server(token: String, generation: u64) -> Result<(u16, tokio::task::JoinHandle<()>)> {
    let listener = tokio::net::TcpListener::bind("0.0.0.0:0").await?;
    let port = listener.local_addr()?.port();
    let task = tokio::spawn(async move {
        while let Ok((mut socket, _)) = listener.accept().await {
            let token = token.clone();
            tokio::spawn(async move {
                let mut request = Vec::new();
                let mut buf = [0u8; 2048];
                while !request.windows(4).any(|w| w == b"\r\n\r\n") && request.len() < 16384 {
                    match socket.read(&mut buf).await {
                        Ok(0) | Err(_) => return,
                        Ok(n) => request.extend_from_slice(&buf[..n]),
                    }
                }
                let line = String::from_utf8_lossy(&request).lines().next().unwrap_or("").to_string();
                let mut parts = line.split_whitespace();
                let method = parts.next().unwrap_or("");
                let path = parts.next().unwrap_or("");
                if path != format!("/{token}.webm") {
                    let _ = socket.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await;
                    return;
                }
                let _ = socket.set_nodelay(true);
                let headers = "HTTP/1.1 200 OK\r\nContent-Type: video/webm\r\nCache-Control: no-cache, no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
                if socket.write_all(headers.as_bytes()).await.is_err() || method == "HEAD" {
                    return;
                }
                let (tx, mut rx) = mpsc::channel::<Vec<u8>>(256);
                {
                    let mut cast = CAST.lock().await;
                    if cast.generation != generation {
                        return;
                    }
                    cast.response = Some(tx.clone()); // remplace (et ferme) la connexion précédente
                }
                events::emit("capture:start", ());
                while let Some(chunk) = rx.recv().await {
                    if socket.write_all(&chunk).await.is_err() {
                        break;
                    }
                }
                let mut cast = CAST.lock().await;
                if cast.response.as_ref().map(|r| r.same_channel(&tx)).unwrap_or(false) {
                    cast.response = None;
                    events::emit("capture:stop", ());
                }
            });
        }
    });
    Ok((port, task))
}

/// Morceau du flux WebM envoyé par la page.
pub async fn chunk(data: Vec<u8>) {
    let sender = CAST.lock().await.response.clone();
    if let Some(sender) = sender {
        let _ = sender.send(data).await;
    }
}

/// Capture impossible ou interrompue dans la page.
pub async fn capture_error(message: &str) {
    stop().await;
    let mut cast = CAST.lock().await;
    set_state(&mut cast, json!({ "error": error("cast.errors.capture", message) }));
}

pub async fn start(device: Value) -> Result<Value> {
    stop().await;
    let generation = {
        let mut cast = CAST.lock().await;
        cast.generation += 1;
        set_state(&mut cast, json!({ "status": "connecting", "device": device, "error": null }));
        cast.generation
    };
    let token = hex::encode(rand::random::<[u8; 12]>());
    let result: Result<(Arc<CastSession>, mpsc::UnboundedReceiver<Event>)> = async {
        let (port, server) = start_server(token.clone(), generation).await?;
        CAST.lock().await.server = Some(server);
        let (session, events) = CastSession::start(&device, |local| {
            (format!("http://{local}:{port}/{token}.webm"), "video/webm".to_string(), "RomCloud".to_string())
        })
        .await?;
        Ok((Arc::new(session), events))
    }
    .await;
    match result {
        Ok((session, mut session_events)) => {
            let mut cast = CAST.lock().await;
            if cast.generation != generation {
                drop(cast);
                session.stop().await;
                return Ok(state().await);
            }
            cast.session = Some(session);
            set_state(&mut cast, json!({ "status": "casting" }));
            let current = cast.state.clone();
            drop(cast);
            // Fin de la lecture (télévision, téléphone, autre application) ou perte de la connexion.
            tokio::spawn(async move {
                while let Some(event) = session_events.recv().await {
                    match event {
                        Event::Playing => continue,
                        Event::Ended => {
                            if CAST.lock().await.generation == generation {
                                stop().await;
                            }
                        }
                        Event::Failed(reason) => {
                            if CAST.lock().await.generation == generation {
                                stop().await;
                                let mut cast = CAST.lock().await;
                                set_state(&mut cast, json!({ "error": error("cast.errors.lost", &reason) }));
                            }
                        }
                    }
                    return;
                }
            });
            Ok(current)
        }
        Err(e) => {
            if CAST.lock().await.generation == generation {
                stop().await;
            }
            // Pas de lecture : le plus souvent le pare-feu de Windows bloque le Chromecast.
            let key = if e.message == "timeout" || e.message == "MEDIA_ERROR" { "cast.errors.timeout" } else { "cast.errors.connect" };
            Err(AppError::with_message(key, json!({ "detail": e.message }), e.message.clone()))
        }
    }
}

pub async fn stop() {
    let (session, server) = {
        let mut cast = CAST.lock().await;
        cast.generation += 1;
        cast.response = None;
        let session = cast.session.take();
        let server = cast.server.take();
        if cast.state["status"] != "idle" {
            set_state(&mut cast, json!({ "status": "idle", "device": null }));
        }
        (session, server)
    };
    events::emit("capture:stop", ());
    if let Some(server) = server {
        server.abort();
    }
    if let Some(session) = session {
        session.stop().await;
    }
}
