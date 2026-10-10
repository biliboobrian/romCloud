// Historique des états du moteur intégré (plusieurs par jeu, avec miniature), même format que
// l'application Android : dossier states\<cœur>\<jeu>.history écrit par romcloud-player
// (« <id>.state », « <id>.png », « <id>.json »). Profil connecté : états envoyés en ligne après la
// partie (réglage « autoUploadStates ») ou à la demande, états des autres appareils téléchargés
// dans le sous-dossier « online » avant la partie (proposés par le menu du moteur).
use crate::account::{self, Body};
use crate::error::{AppError, Result};
use crate::{events, settings};
use base64::Engine;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// États des autres appareils téléchargés avant la partie : les plus récents, de taille raisonnable.
const PREFETCH_COUNT: usize = 5;
const PREFETCH_MAX_BYTES: u64 = 64 * 1024 * 1024;

fn online_dir(dir: &Path) -> PathBuf {
    dir.join("online")
}

fn read_json(path: &Path) -> Option<Value> {
    serde_json::from_str(&std::fs::read_to_string(path).ok()?).ok()
}

fn write_json(path: &Path, value: &Value) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, value.to_string())?;
    std::fs::rename(&tmp, path)
}

/// États enregistrés dans [dir] (ceux dont le fichier existe), du plus récent au plus ancien.
fn read_dir(dir: &Path) -> Vec<Value> {
    let mut list: Vec<Value> = std::fs::read_dir(dir)
        .map(|entries| {
            entries
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.extension().is_some_and(|e| e == "json"))
                .filter_map(|p| read_json(&p))
                .filter(|m| m["id"].as_str().is_some_and(|id| dir.join(format!("{id}.state")).is_file()))
                .collect()
        })
        .unwrap_or_default();
    list.sort_by(|a, b| b["createdAt"].as_f64().unwrap_or(0.0).total_cmp(&a["createdAt"].as_f64().unwrap_or(0.0)));
    list
}

fn valid_id(id: &str) -> Result<&str> {
    if (8..=64).contains(&id.len()) && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        Ok(id)
    } else {
        Err(AppError::msg(format!("état invalide : {id}")))
    }
}

/// États en ligne du jeu (None : sans profil ou serveur injoignable).
async fn online(game_id: &Value) -> Option<Vec<Value>> {
    account::session()?;
    let list = account::get(&format!("/api/account/states?gameId={}", account::game_key(game_id)), 8000).await.ok()?;
    Some(list.as_array().cloned().unwrap_or_default())
}

/// Réunit les états de ce PC et ceux du profil en ligne (même identifiant : un seul état), des cœurs [cores].
pub fn merge(local: &[Value], remote: &[Value], cores: &[String]) -> Vec<Value> {
    let remote: Vec<&Value> = remote
        .iter()
        .filter(|s| cores.iter().any(|c| account::same_save_core(s["core"].as_str().unwrap_or(""), c)))
        .collect();
    let mut list: Vec<Value> = local
        .iter()
        .map(|m| {
            let id = m["id"].as_str().unwrap_or("");
            let on = remote.iter().find(|s| s["id"] == id);
            json!({
                "id": id, "core": m["core"], "createdAt": m["createdAt"], "device": m["device"], "platform": m["platform"],
                "pinned": on.map(|s| s["pinned"].clone()).unwrap_or(m["pinned"].clone()).as_bool().unwrap_or(false),
                "local": true, "online": on.is_some(), "canUpload": on.is_none(),
            })
        })
        .collect();
    for s in remote {
        if local.iter().any(|m| m["id"] == s["id"]) {
            continue;
        }
        list.push(json!({
            "id": s["id"], "core": s["core"], "createdAt": account::parse_date_ms(s["createdAt"].as_str().unwrap_or("")),
            "device": s["device"], "platform": s["platform"], "pinned": s["pinned"].as_bool().unwrap_or(false),
            "local": false, "online": true, "canUpload": false, "thumbnail": s["thumbnail"],
        }));
    }
    list.sort_by(|a, b| b["createdAt"].as_f64().unwrap_or(0.0).total_cmp(&a["createdAt"].as_f64().unwrap_or(0.0)));
    list
}

/**
 * Historique affiché dans la fiche du jeu : { states, cores, online (serveur joint), signedIn } ;
 * [histories] : (cœur, dossier) de chaque cœur intégré du système (un état se charge avec le sien).
 */
pub async fn list(game_id: &Value, histories: &[(String, PathBuf)]) -> Value {
    let mut local: Vec<Value> = Vec::new();
    for (_, dir) in histories {
        for meta in read_dir(dir) {
            if !local.iter().any(|m| m["id"] == meta["id"]) {
                local.push(meta);
            }
        }
    }
    let cores: Vec<String> = histories.iter().map(|(c, _)| c.clone()).collect();
    let remote = online(game_id).await;
    json!({
        "states": merge(&local, remote.as_deref().unwrap_or(&[]), &cores),
        "cores": cores,
        "online": remote.is_some(),
        "signedIn": account::signed_in(),
    })
}

fn data_url(bytes: &[u8]) -> String {
    let mime = if bytes.starts_with(&[0xFF, 0xD8]) { "image/jpeg" } else { "image/png" };
    format!("data:{mime};base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes))
}

/// Miniature d'un état (adresse « data: »), celle du PC ou celle du serveur (gardée en cache).
pub async fn thumbnail(dir: &Path, id: &str) -> Option<String> {
    let id = valid_id(id).ok()?;
    let local = dir.join(format!("{id}.png"));
    let cached = online_dir(dir).join(format!("{id}.thumb"));
    for file in [&local, &cached] {
        if let Ok(bytes) = std::fs::read(file) {
            return Some(data_url(&bytes));
        }
    }
    account::session()?;
    let res = account::call(&format!("/api/account/states/{id}/thumbnail"), reqwest::Method::GET, Body::None, true, &[], Duration::from_secs(15)).await.ok()?;
    if res.bytes.is_empty() {
        return None;
    }
    let _ = std::fs::create_dir_all(online_dir(dir));
    let _ = std::fs::write(&cached, &res.bytes);
    Some(data_url(&res.bytes))
}

/// Télécharge l'état en ligne [state] (informations du serveur) dans le sous-dossier « online ».
async fn download(dir: &Path, state: &Value) -> Result<PathBuf> {
    let id = valid_id(state["id"].as_str().unwrap_or(""))?;
    let target = online_dir(dir).join(format!("{id}.state"));
    if !target.is_file() {
        let res = account::call(&format!("/api/account/states/{id}"), reqwest::Method::GET, Body::None, true, &[], Duration::from_secs(300)).await?;
        std::fs::create_dir_all(online_dir(dir))?;
        let part = target.with_extension("state.download");
        std::fs::write(&part, &res.bytes)?;
        std::fs::rename(&part, &target)?;
    }
    let meta = json!({
        "id": id, "core": state["core"], "createdAt": account::parse_date_ms(state["createdAt"].as_str().unwrap_or("")),
        "device": state["device"], "platform": state["platform"], "pinned": state["pinned"], "uploaded": true,
    });
    write_json(&online_dir(dir).join(format!("{id}.json")), &meta)?;
    Ok(target)
}

/// Fichier de l'état [id] : celui du PC, sinon celui du profil en ligne (téléchargé si besoin).
pub async fn file_for(game_id: &Value, dir: &Path, id: &str) -> Result<PathBuf> {
    let id = valid_id(id)?;
    for file in [dir.join(format!("{id}.state")), online_dir(dir).join(format!("{id}.state"))] {
        if file.is_file() {
            return Ok(file);
        }
    }
    let remote = online(game_id).await.ok_or_else(|| AppError::new("errors.unreachable", json!({ "detail": "" })))?;
    let state = remote.iter().find(|s| s["id"] == id).ok_or_else(|| AppError::msg(format!("état introuvable : {id}")))?;
    download(dir, state).await
}

/// Avant la partie : les états récents des autres appareils sont téléchargés (menu du moteur) ; ceux
/// qui ne sont plus en ligne sont retirés du cache.
pub async fn prefetch(game_id: &Value, core: &str, dir: &Path) {
    let Some(remote) = online(game_id).await else { return };
    let local: Vec<String> = read_dir(dir).iter().filter_map(|m| m["id"].as_str().map(String::from)).collect();
    let mut others: Vec<&Value> = remote
        .iter()
        .filter(|s| account::same_save_core(s["core"].as_str().unwrap_or(""), core))
        .filter(|s| !local.iter().any(|id| s["id"] == id.as_str()))
        .filter(|s| s["size"].as_u64().unwrap_or(0) <= PREFETCH_MAX_BYTES)
        .collect();
    others.sort_by(|a, b| {
        account::parse_date_ms(b["createdAt"].as_str().unwrap_or("")).total_cmp(&account::parse_date_ms(a["createdAt"].as_str().unwrap_or("")))
    });
    others.truncate(PREFETCH_COUNT);
    for state in &others {
        let _ = download(dir, state).await;
    }
    // Cache : seulement les états encore en ligne et retenus.
    for meta in read_dir(&online_dir(dir)) {
        let id = meta["id"].as_str().unwrap_or("");
        if !others.iter().any(|s| s["id"] == id) {
            for ext in ["state", "json", "thumb"] {
                let _ = std::fs::remove_file(online_dir(dir).join(format!("{id}.{ext}")));
            }
        }
    }
}

/// Envoie l'état [id] du PC (puis sa miniature) ; marqué « envoyé ».
pub async fn upload(game_id: &Value, dir: &Path, id: &str) -> Result<()> {
    let id = valid_id(id)?;
    let meta_path = dir.join(format!("{id}.json"));
    let mut meta = read_json(&meta_path).ok_or_else(|| AppError::msg(format!("état introuvable : {id}")))?;
    let data = std::fs::read(dir.join(format!("{id}.state")))?;
    let core = meta["core"].as_str().unwrap_or("").to_string();
    let headers = [
        ("X-Created-At", (meta["createdAt"].as_f64().unwrap_or(0.0).round() as i64).to_string()),
        ("X-Pinned", if meta["pinned"].as_bool().unwrap_or(false) { "1".into() } else { "0".into() }),
    ];
    let path = format!("/api/account/states/{}/{}/{id}", account::game_key(game_id), urlencoding::encode(&core));
    let res = account::call(&path, reqwest::Method::PUT, Body::Bytes(data), false, &headers, Duration::from_secs(300)).await?;
    // Réponse vide : plus ancien que les états gardés en ligne, aussitôt retiré par le serveur.
    if !res.json.is_null() {
        if let Ok(png) = std::fs::read(dir.join(format!("{id}.png"))) {
            let _ = account::call(&format!("/api/account/states/{id}/thumbnail"), reqwest::Method::PUT, Body::Image(png, "image/png"), false, &[], Duration::from_secs(60)).await;
        }
    }
    meta["uploaded"] = json!(true);
    write_json(&meta_path, &meta)?;
    Ok(())
}

/// Après la partie (profil connecté, envoi automatique) : les nouveaux états du PC sont envoyés ;
/// serveur injoignable : mis en attente ([flush_pending]).
pub async fn after_session(game_id: &Value, dir: &Path) {
    if !account::signed_in() || settings::load().get("autoUploadStates").and_then(Value::as_bool) == Some(false) {
        return;
    }
    let mut queued = false;
    for meta in read_dir(dir).iter().filter(|m| m["uploaded"] != json!(true)) {
        let id = meta["id"].as_str().unwrap_or("");
        if let Err(e) = upload(game_id, dir, id).await {
            if e.is("errors.unreachable") {
                let mut list = account::pending("pendingStates");
                list.retain(|p| p["id"] != id);
                list.push(json!({ "gameId": game_id, "dir": dir.to_string_lossy(), "id": id }));
                settings::save(json!({ "pendingStates": list }));
                queued = true;
            } else {
                account::report_error("states", &e.message, Some(id.to_string()));
            }
        }
    }
    if queued {
        events::emit("account:update", ());
    }
}

/// États en attente d'envoi (serveur injoignable après la partie) : renvoie le nombre envoyé.
pub async fn flush_pending() -> usize {
    let mut sent = 0;
    for entry in account::pending("pendingStates") {
        let dir = PathBuf::from(entry["dir"].as_str().unwrap_or(""));
        let id = entry["id"].as_str().unwrap_or("");
        match upload(&entry["gameId"], &dir, id).await {
            Err(e) if e.is("errors.unreachable") => break,
            Ok(()) => sent += 1,
            Err(_) => {} // état supprimé, refusé : abandonné
        }
        let mut list = account::pending("pendingStates");
        list.retain(|p| p["id"] != entry["id"]);
        settings::save(json!({ "pendingStates": list }));
    }
    sent
}

/// Épingle (jamais supprimé par la limite de l'historique) ou désépingle un état, sur le PC et en ligne.
pub async fn pin(dir: &Path, id: &str, pinned: bool, online: bool) -> Result<()> {
    let id = valid_id(id)?;
    let meta_path = dir.join(format!("{id}.json"));
    if let Some(mut meta) = read_json(&meta_path) {
        meta["pinned"] = json!(pinned);
        write_json(&meta_path, &meta)?;
    }
    if online {
        account::call(&format!("/api/account/states/{id}"), reqwest::Method::PATCH, Body::Json(json!({ "pinned": pinned })), false, &[], Duration::from_secs(15)).await?;
    }
    Ok(())
}

/// Supprime un état du PC (et son cache) et du profil en ligne.
pub async fn delete(dir: &Path, id: &str, online: bool) -> Result<()> {
    let id = valid_id(id)?;
    for file in [dir.to_path_buf(), online_dir(dir)] {
        for ext in ["state", "png", "json", "thumb"] {
            let _ = std::fs::remove_file(file.join(format!("{id}.{ext}")));
        }
    }
    if online {
        match account::call(&format!("/api/account/states/{id}"), reqwest::Method::DELETE, Body::None, false, &[], Duration::from_secs(15)).await {
            Err(e) if e.status != Some(404) => return Err(e),
            _ => {}
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn etats_du_pc_et_en_ligne_reunis() {
        let local = vec![
            json!({ "id": "aaaaaaaa", "core": "snes9x", "createdAt": 3000.0, "device": "PC", "pinned": false, "uploaded": true }),
            json!({ "id": "bbbbbbbb", "core": "snes9x", "createdAt": 1000.0, "device": "PC", "pinned": false }),
        ];
        let remote = vec![
            json!({ "id": "aaaaaaaa", "core": "snes9x", "createdAt": "1970-01-01T00:00:03Z", "device": "PC", "pinned": true }),
            json!({ "id": "cccccccc", "core": "snes9x", "createdAt": "1970-01-01T00:00:02Z", "device": "Pixel 8", "thumbnail": true }),
            json!({ "id": "dddddddd", "core": "bsnes", "createdAt": "1970-01-01T00:00:04Z", "device": "Shield" }),
        ];
        let merged = merge(&local, &remote, &["snes9x".to_string()]);
        let ids: Vec<&str> = merged.iter().map(|s| s["id"].as_str().unwrap()).collect();
        assert_eq!(ids, ["aaaaaaaa", "cccccccc", "bbbbbbbb"]);
        assert_eq!(merged[0]["pinned"], true);
        assert_eq!(merged[0]["canUpload"], false);
        assert_eq!(merged[1]["local"], false);
        assert_eq!(merged[1]["device"], "Pixel 8");
        assert_eq!(merged[2]["canUpload"], true);
    }

    #[test]
    fn identifiant_d_etat_verifie() {
        assert!(valid_id("6b823513-d898-4873-934d-069bb5474f66").is_ok());
        assert!(valid_id("..\\x").is_err());
        assert!(valid_id("court").is_err());
    }
}
