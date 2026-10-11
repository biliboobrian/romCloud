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

/// Taille annoncée par les en-têtes : total de Content-Range (« bytes 0-0/2933729 »), sinon
/// Content-Length ; 0 si inconnue.
fn header_size(headers: &reqwest::header::HeaderMap) -> u64 {
    let text = |name| headers.get(name).and_then(|v| v.to_str().ok()).unwrap_or("");
    let range = text(reqwest::header::CONTENT_RANGE);
    if let Some(total) = range.rsplit('/').next().filter(|_| !range.is_empty()).and_then(|t| t.trim().parse().ok()) {
        return total;
    }
    text(reqwest::header::CONTENT_LENGTH).trim().parse().unwrap_or(0)
}

/// Taille de l'installeur, 0 si inconnue. L'en-tête Content-Length est lu directement :
/// `content_length()` de reqwest donne la taille du corps reçu, vide pour une requête HEAD (0). À
/// défaut, le premier octet est demandé et la taille lue dans Content-Range.
async fn installer_size(url: &str) -> u64 {
    let client = api::client();
    if let Ok(res) = client.head(url).timeout(Duration::from_secs(10)).send().await {
        let size = if res.status().is_success() { header_size(res.headers()) } else { 0 };
        if size > 0 {
            return size;
        }
    }
    match client.get(url).header(reqwest::header::RANGE, "bytes=0-0").timeout(Duration::from_secs(10)).send().await {
        Ok(res) if res.status().is_success() => header_size(res.headers()),
        _ => 0,
    }
}

/// Comparaison de deux versions sur GitHub : commits entre leurs tags.
const COMPARE: &str = "https://api.github.com/repos/biliboobrian/romCloud/compare";

/// Modifications depuis la version installée : messages des commits entre les deux tags, par
/// version (de la plus récente à la plus ancienne), [{ version, items }] ; vide si GitHub ne répond pas.
async fn changes(installed: &str, latest: &str) -> Value {
    let url = format!("{COMPARE}/v{installed}...v{latest}");
    let res = api::client()
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .timeout(Duration::from_secs(10))
        .send()
        .await;
    let body = match res {
        Ok(res) if res.status().is_success() => res.json::<Value>().await.unwrap_or(Value::Null),
        _ => Value::Null,
    };
    changes_from_compare(&body, latest)
}

/// Commits d'une comparaison GitHub (du plus ancien au plus récent) regroupés par version : un
/// commit se termine par sa version (« … ; v1.35.6 ») ; un commit sans version appartient à la
/// version publiée après lui. Chaque message (premier paragraphe) est découpé à ses « ; ».
fn changes_from_compare(body: &Value, latest: &str) -> Value {
    let mut groups: Vec<(String, Vec<String>)> = Vec::new();
    let mut version = latest.to_string();
    let commits = body["commits"].as_array().cloned().unwrap_or_default();
    for commit in commits.iter().rev() {
        let message = commit["commit"]["message"].as_str().unwrap_or("").replace("\r\n", "\n");
        let text = message.split("\n\n").next().unwrap_or("").trim();
        if text.is_empty() || text.starts_with("Merge ") {
            continue;
        }
        let text = match text.rsplit_once("; v") {
            Some((rest, v)) if !v.is_empty() && v.chars().all(|c| c.is_ascii_digit() || c == '.') => {
                version = v.to_string();
                rest.trim_end()
            }
            _ => text,
        };
        let items = text.split(" ; ").map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
        match groups.last_mut() {
            Some((v, list)) if *v == version => list.extend(items),
            _ => groups.push((version.clone(), items.collect())),
        }
    }
    groups.into_iter().map(|(version, items)| json!({ "version": version, "items": items })).collect()
}

/// Mise à jour proposée : { version, installed, size, portable, changes }, ou null (version de
/// développement, à jour, ou hors ligne).
pub async fn check() -> Value {
    if cfg!(debug_assertions) {
        return Value::Null;
    }
    let Some(handle) = events::handle() else { return Value::Null };
    let Ok(updater) = handle.updater() else { return Value::Null };
    let Ok(Some(update)) = updater.check().await else { return Value::Null };
    let installed = account::version();
    let info = json!({
        "version": update.version,
        "size": installer_size(update.download_url.as_str()).await,
        "portable": false,
        "changes": changes(&installed, &update.version).await,
        "installed": installed,
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

#[cfg(test)]
mod tests {
    use super::*;
    use reqwest::header::{HeaderMap, HeaderValue, CONTENT_LENGTH, CONTENT_RANGE};

    #[test]
    fn taille_lue_dans_les_en_tetes() {
        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_LENGTH, HeaderValue::from_static("2933729"));
        assert_eq!(header_size(&headers), 2933729);
        // Premier octet demandé : la taille totale est dans Content-Range.
        headers.insert(CONTENT_LENGTH, HeaderValue::from_static("1"));
        headers.insert(CONTENT_RANGE, HeaderValue::from_static("bytes 0-0/2933729"));
        assert_eq!(header_size(&headers), 2933729);
        assert_eq!(header_size(&HeaderMap::new()), 0);
    }

    #[test]
    fn modifications_regroupees_par_version() {
        let body = json!({ "commits": [
            { "commit": { "message": "Couleur des systèmes ; Android : fond ; v1.35.6\r\n\r\nCo-Authored-By: x" } },
            { "commit": { "message": "Interface web sur mobile ; scraping Vita" } },
            { "commit": { "message": "Merge branch 'x'" } },
            { "commit": { "message": "Fond de l'image ; v1.35.7" } },
        ] });
        assert_eq!(
            changes_from_compare(&body, "1.35.7"),
            json!([
                { "version": "1.35.7", "items": ["Fond de l'image", "Interface web sur mobile", "scraping Vita"] },
                { "version": "1.35.6", "items": ["Couleur des systèmes", "Android : fond"] },
            ])
        );
        assert_eq!(changes_from_compare(&Value::Null, "1.35.7"), json!([]));
    }
}
