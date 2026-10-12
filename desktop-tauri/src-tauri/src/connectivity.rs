// Connexion au serveur RomCloud : mode en ligne ou hors ligne. Le serveur est sondé (/api/ping) à
// l'ouverture de l'application et quand l'utilisateur touche la pastille « hors ligne » ; une requête
// qui n'aboutit pas (délai dépassé, serveur injoignable) passe l'application hors ligne. Hors ligne,
// aucune requête ne part vers le serveur : seuls les fichiers déjà sur le PC sont utilisés. Au retour
// en ligne, l'interface est prévenue et le travail en attente est envoyé.
use crate::error::{AppError, Result};
use crate::{account, events, settings};
use serde_json::json;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

const PING_TIMEOUT: Duration = Duration::from_secs(5);

static ONLINE: AtomicBool = AtomicBool::new(true);

pub fn is_online() -> bool {
    ONLINE.load(Ordering::SeqCst)
}

/// Hors ligne : erreur « serveur injoignable » sans requête (listes relues depuis le cache).
pub fn ensure_online() -> Result<()> {
    if is_online() {
        Ok(())
    } else {
        Err(AppError::new("errors.unreachable", json!({ "detail": "offline" })))
    }
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
    }
}

pub fn mark_online() {
    set(true);
}

/// Requête au serveur sans réponse (délai dépassé, serveur injoignable) : hors ligne.
pub fn mark_offline() {
    set(false);
}

/// Sonde le serveur (/api/ping) : toute réponse le met en ligne, son absence hors ligne. Renvoie
/// l'état obtenu (sans serveur configuré : inchangé).
pub async fn ping() -> bool {
    let server = settings::get_str("serverUrl");
    if server.is_empty() {
        return is_online();
    }
    let ok = crate::api::client().get(format!("{server}/api/ping")).timeout(PING_TIMEOUT).send().await.is_ok();
    set(ok);
    ok
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hors_ligne_aucune_requete() {
        ONLINE.store(false, Ordering::SeqCst);
        let refused = ensure_online().unwrap_err();
        // Même erreur qu'un serveur injoignable : les listes sont relues depuis le cache.
        assert!(refused.is("errors.unreachable"));
        assert!(account::relay_request("partie", "play", "host").is_none());
        ONLINE.store(true, Ordering::SeqCst);
        assert!(ensure_online().is_ok());
    }
}
