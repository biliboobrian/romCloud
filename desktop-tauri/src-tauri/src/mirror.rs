// Émulateurs téléchargés depuis le serveur RomCloud plutôt que sur GitHub ou Forgejo : le serveur
// garde une copie de chaque version demandée (emulator-mirror.js), si bien qu'un émulateur retiré
// d'Internet reste installable.
//  1. Liste des versions de l'émulateur demandée au serveur (format des Releases GitHub, chaque
//     fichier avec son adresse sur le serveur et son état : stored, fetching, remote, error) ;
//  2. fichier choisi demandé au serveur (« prepare ») : il le récupère sur Internet s'il ne l'a pas ;
//  3. une fois sur le serveur (stored), il est téléchargé depuis le serveur.
use crate::error::{AppError, Result};
use crate::{api, settings};
use serde_json::{json, Value};
use std::time::{Duration, Instant};

/// Versions de l'émulateur [source] sur le serveur, les plus récentes d'abord ; adresse de
/// téléchargement de chaque fichier (browser_download_url) : celle du serveur.
pub async fn releases(source: &str) -> Result<Value> {
    let text = api::request(&format!("/api/emulators/{source}/releases"), None).await?;
    let mut list: Value = serde_json::from_str(&text)?;
    with_server_urls(&mut list, &settings::get_str("serverUrl"));
    Ok(list)
}

/// Adresse complète des fichiers ([server] + leur chemin sur le serveur).
pub fn with_server_urls(list: &mut Value, server: &str) {
    let server = server.trim_end_matches('/');
    for release in list.as_array_mut().into_iter().flatten() {
        for asset in release.get_mut("assets").and_then(Value::as_array_mut).into_iter().flatten() {
            if let Some(path) = asset.get("path").and_then(Value::as_str).map(String::from) {
                asset["browser_download_url"] = json!(format!("{server}{path}"));
            }
        }
    }
}

/// Fichier [name] d'une version de la liste.
pub fn asset<'a>(release: &'a Value, name: &str) -> Option<&'a Value> {
    release.get("assets")?.as_array()?.iter().find(|a| a.get("name").and_then(Value::as_str) == Some(name))
}

/// Chemin sur le serveur d'un fichier téléchargeable ([url] : browser_download_url).
fn server_path(url: &str) -> String {
    let server = settings::get_str("serverUrl");
    url.strip_prefix(server.trim_end_matches('/')).unwrap_or(url).to_string()
}

/// Demande au serveur le fichier d'adresse [url] : { state, received, total, error }.
pub async fn prepare(url: &str) -> Result<Value> {
    let text = api::post(&format!("{}/prepare", server_path(url))).await?;
    Ok(serde_json::from_str(&text)?)
}

pub fn is_stored(state: &Value) -> bool {
    state.get("state").and_then(Value::as_str) == Some("stored")
}

/// Fichier demandé au serveur puis attendu jusqu'à ce qu'il l'ait ([on_progress] : octets reçus
/// par le serveur, taille) ; erreur si le serveur n'a pas pu le récupérer.
pub async fn wait_stored(url: &str, name: &str, on_progress: impl Fn(u64, u64)) -> Result<()> {
    let started = Instant::now();
    loop {
        let state = prepare(url).await?;
        if is_stored(&state) {
            return Ok(());
        }
        if state.get("state").and_then(Value::as_str) == Some("error") {
            let detail = state.get("error").and_then(Value::as_str).unwrap_or("").to_string();
            return Err(AppError::new("errors.mirrorFetch", json!({ "name": name, "detail": detail })));
        }
        on_progress(state["received"].as_u64().unwrap_or(0), state["total"].as_u64().unwrap_or(0));
        if started.elapsed() > Duration::from_secs(3 * 3600) {
            return Err(AppError::new("errors.mirrorFetch", json!({ "name": name, "detail": "timeout" })));
        }
        tokio::time::sleep(Duration::from_millis(1500)).await;
    }
}

/// Fichier téléchargé depuis le serveur (clé d'API des réglages).
pub async fn download(url: &str) -> Result<Vec<u8>> {
    let res = api::client()
        .get(url)
        .headers(api::headers(&settings::get_str("apiKey")))
        .timeout(Duration::from_secs(3600))
        .send()
        .await
        .map_err(|e| AppError::new("errors.unreachable", json!({ "detail": api::network_detail(&e) })))?;
    if !res.status().is_success() {
        return Err(AppError::new("errors.http", json!({ "status": res.status().as_u16() })));
    }
    Ok(res.bytes().await?.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adresses_du_serveur() {
        let mut list = json!([{ "tag_name": "v1", "assets": [{ "name": "a.zip", "path": "/api/emulators/cemu/files/v1/a.zip", "state": "stored" }] }]);
        with_server_urls(&mut list, "http://nas:8080/");
        assert_eq!(list[0]["assets"][0]["browser_download_url"], "http://nas:8080/api/emulators/cemu/files/v1/a.zip");
        assert!(asset(&list[0], "a.zip").is_some_and(is_stored));
        assert!(asset(&list[0], "b.zip").is_none());
    }
}
