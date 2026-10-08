// Mise à jour de l'application au démarrage, avec le plugin officiel de Tauri : le fichier latest.json
// de la dernière Release GitHub (voir .github/workflows/desktop-tauri.yml) donne la version et
// l'installeur, dont la signature est vérifiée avec la clé publique de tauri.conf.json. Sur
// confirmation, l'installeur est téléchargé puis lancé (mode passif : barre de progression seule) et
// RomCloud est relancé une fois à jour.
use crate::error::{AppError, Result};
use crate::{account, api, events};
use serde_json::{json, Value};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri_plugin_updater::{Update, UpdaterExt};

static AVAILABLE: Mutex<Option<Update>> = Mutex::new(None);

/// Taille de l'installeur (en-tête de la réponse), 0 si inconnue.
async fn installer_size(url: &str) -> u64 {
    match api::client().head(url).timeout(Duration::from_secs(10)).send().await {
        Ok(res) => res.content_length().unwrap_or(0),
        Err(_) => 0,
    }
}

/// Mise à jour proposée : { version, installed, size, portable }, ou null (version de
/// développement, à jour, ou hors ligne).
pub async fn check() -> Value {
    if cfg!(debug_assertions) {
        return Value::Null;
    }
    let Some(handle) = events::handle() else { return Value::Null };
    let Ok(updater) = handle.updater() else { return Value::Null };
    let Ok(Some(update)) = updater.check().await else { return Value::Null };
    let info = json!({
        "version": update.version,
        "installed": account::version(),
        "size": installer_size(update.download_url.as_str()).await,
        "portable": false,
    });
    *AVAILABLE.lock().unwrap() = Some(update);
    info
}

/// Télécharge l'installeur (progression envoyée à l'interface), vérifie sa signature, l'installe
/// puis relance RomCloud.
pub async fn install() -> Result<()> {
    let update = AVAILABLE.lock().unwrap().clone().ok_or_else(|| AppError::new("errors.updateNone", json!({})))?;
    let mut bytes = 0u64;
    let mut total = 0u64;
    let mut last = Instant::now() - Duration::from_secs(1);
    update
        .download_and_install(
            |chunk, length| {
                bytes += chunk as u64;
                total = length.unwrap_or(total);
                if last.elapsed() > Duration::from_millis(250) {
                    last = Instant::now();
                    events::emit("update:progress", json!({ "bytes": bytes, "total": total }));
                }
            },
            || events::emit("update:progress", json!({ "bytes": 1, "total": 1 })),
        )
        .await
        .map_err(|e| match e {
            tauri_plugin_updater::Error::Minisign(_) | tauri_plugin_updater::Error::SignatureUtf8(_) => AppError::new("errors.updateDigest", json!({})),
            other => AppError::new("errors.download", json!({ "detail": other.to_string() })),
        })?;
    // Windows : l'installeur a déjà fermé RomCloud ; ailleurs, relance ici.
    if let Some(handle) = events::handle() {
        handle.restart();
    }
    Ok(())
}
