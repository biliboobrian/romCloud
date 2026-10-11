// Lobbies du multijoueur (server/src/lobbies.js) : appels au serveur pour ce PC (son identifiant
// d'appareil, celui de la présence). L'interface affiche les lobbies et les joueurs, ce module ne
// garde rien : le serveur fait foi.
use crate::account::{self, Body};
use crate::error::{AppError, Result};
use crate::netplay;
use serde_json::{json, Value};
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(8);

/// Identifiant de lobby (hexadécimal, donné par le serveur).
fn lobby_path(id: &str, suffix: &str) -> Result<String> {
    if id.is_empty() || id.len() > 32 || !id.chars().all(|c| c.is_ascii_alphanumeric()) {
        return Err(AppError::msg(format!("lobby invalide : {id}")));
    }
    Ok(format!("/api/account/play/lobbies/{id}{suffix}"))
}

/// Corps de la requête avec l'identifiant de ce PC.
fn with_device(mut body: Value) -> Value {
    if !body.is_object() {
        body = json!({});
    }
    body["deviceId"] = json!(netplay::device_id());
    body
}

/// Requête au serveur ; PC pas encore annoncé (application ouverte à l'instant) : annonce puis nouvel essai.
async fn send(path: &str, method: reqwest::Method, body: Option<Value>) -> Result<Value> {
    let attempt = || {
        let body = match &body {
            Some(b) => Body::Json(b.clone()),
            None => Body::None,
        };
        account::call(path, method.clone(), body, false, &[], TIMEOUT)
    };
    match attempt().await {
        Err(e) if e.status == Some(409) => {
            netplay::announce_now().await;
            Ok(attempt().await?.json)
        }
        other => Ok(other?.json),
    }
}

/// Joueurs connectés ({ id, name, user, platform, busy, active }) et lobbies ouverts ; [me] : ce PC.
pub async fn list() -> Result<Value> {
    let mut data = account::get("/api/account/play/lobbies", 8000).await?;
    data["me"] = json!(netplay::device_id());
    Ok(data)
}

/// Nouveau lobby (ce PC en est l'hôte) ; [options] : { game, modes, mode } facultatifs.
pub async fn create(options: &Value) -> Result<Value> {
    send("/api/account/play/lobbies", reqwest::Method::POST, Some(with_device(options.clone()))).await
}

/// Rejoint le lobby ; [systems] : systèmes que ce PC sait jouer en réseau (émulateur intégré).
pub async fn join(id: &str, systems: &Value) -> Result<Value> {
    send(&lobby_path(id, "/join")?, reqwest::Method::POST, Some(with_device(json!({ "systems": systems })))).await
}

pub async fn leave(id: &str) -> Result<Value> {
    send(&lobby_path(id, "/leave")?, reqwest::Method::POST, Some(with_device(json!({})))).await
}

/// Hôte : jeu ({ game, modes, mode }) ou façon de jouer ({ mode }).
pub async fn update(id: &str, changes: &Value) -> Result<Value> {
    send(&lobby_path(id, "")?, reqwest::Method::PUT, Some(with_device(changes.clone()))).await
}

/// Statut de ce PC dans le lobby : « waiting » ou « playing ».
pub async fn status(id: &str, status: &str) -> Result<Value> {
    send(&lobby_path(id, "/status")?, reqwest::Method::PUT, Some(with_device(json!({ "status": status })))).await
}

/// Hôte : dissout le lobby.
pub async fn dissolve(id: &str) -> Result<Value> {
    let path = format!("{}?deviceId={}", lobby_path(id, "")?, urlencoding::encode(&netplay::device_id()));
    send(&path, reqwest::Method::DELETE, None).await
}
