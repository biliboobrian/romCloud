// Connexion au serveur RomCloud : hors ligne dès qu'une requête n'aboutit pas, de nouveau en ligne
// à la première réponse (quel que soit son code). Hors ligne, le serveur est sondé régulièrement ;
// au retour de la connexion, l'interface est prévenue et le travail en attente est envoyé.
use crate::{account, events, settings};
use serde_json::json;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

const PROBE_INTERVAL: Duration = Duration::from_secs(15);

static ONLINE: AtomicBool = AtomicBool::new(true);
static PROBING: AtomicBool = AtomicBool::new(false);

pub fn is_online() -> bool {
    ONLINE.load(Ordering::SeqCst)
}

/// État affiché par l'interface : { online, pending }.
pub fn state() -> serde_json::Value {
    json!({ "online": is_online(), "pending": account::pending_count() })
}

fn set(value: bool) {
    if ONLINE.swap(value, Ordering::SeqCst) == value {
        return;
    }
    events::emit("connectivity:update", state());
    if value {
        // Retour de la connexion : sauvegardes et durées de jeu en attente envoyées.
        tauri::async_runtime::spawn(async {
            let synced = account::flush().await;
            let mut s = state();
            s["synced"] = json!(synced);
            events::emit("connectivity:update", s);
        });
    } else if !PROBING.swap(true, Ordering::SeqCst) {
        tauri::async_runtime::spawn(async {
            while !is_online() {
                tokio::time::sleep(PROBE_INTERVAL).await;
                probe().await;
            }
            PROBING.store(false, Ordering::SeqCst);
        });
    }
}

pub fn mark_online() {
    set(true);
}

pub fn mark_offline() {
    set(false);
}

/// Le serveur répond-il ? Met l'état à jour et le renvoie (sans serveur configuré : inchangé).
pub async fn probe() -> bool {
    let server = settings::get_str("serverUrl");
    if server.is_empty() {
        return is_online();
    }
    let ok = crate::api::client()
        .get(format!("{server}/api/info"))
        .timeout(Duration::from_secs(5))
        .send()
        .await
        .is_ok();
    if ok { mark_online() } else { mark_offline() }
    is_online()
}
