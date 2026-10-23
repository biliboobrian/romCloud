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
use std::collections::HashMap;
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

/**
 * Toute sauvegarde de la partie est dans l'historique : l'état de reprise [quick] (écrit en quittant,
 * ou téléchargé depuis le profil : sauvegarde d'un autre appareil, ou d'une version précédente) y est
 * ajouté s'il n'y est pas déjà (même taille, date à 2 s près), daté de son fichier, avec l'appareil
 * [device] qui l'a fait. Renvoie vrai s'il a été ajouté.
 */
pub fn record_quick(dir: &Path, quick: &Path, core: &str, device: &str, platform: &str) -> bool {
    if crate::paths::size(quick).unwrap_or(0) == 0 || same_in_history(dir, quick).is_some() {
        return false;
    }
    let Some(date) = crate::paths::mtime_ms(quick) else { return false };
    let b: [u8; 16] = rand::random();
    let h = hex::encode(b);
    let id = format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32]);
    let ok = std::fs::create_dir_all(dir).is_ok()
        && std::fs::copy(quick, dir.join(format!("{id}.state"))).is_ok()
        && write_json(
            &dir.join(format!("{id}.json")),
            &json!({ "id": id, "core": core, "createdAt": date, "device": device, "platform": platform, "pinned": false, "uploaded": false }),
        )
        .is_ok();
    if !ok {
        for ext in ["state", "json"] {
            let _ = std::fs::remove_file(dir.join(format!("{id}.{ext}")));
        }
    }
    ok
}

/// État de l'historique ([dir] ou « online ») identique à l'état de reprise [quick] (même taille, date à 2 s près).
fn same_in_history(dir: &Path, quick: &Path) -> Option<Value> {
    let (Some(size), Some(date)) = (crate::paths::size(quick), crate::paths::mtime_ms(quick)) else { return None };
    [dir.to_path_buf(), online_dir(dir)].iter().find_map(|d| {
        read_dir(d).into_iter().find(|m| {
            let file = d.join(format!("{}.state", m["id"].as_str().unwrap_or("")));
            (m["createdAt"].as_f64().unwrap_or(0.0) - date).abs() <= 2000.0 && crate::paths::size(&file) == Some(size)
        })
    })
}

/// Disque d'un état (jeu en plusieurs disques, 0 pour le premier), noté par le moteur intégré ; None
/// s'il ne l'est pas (état d'une version précédente ou d'un autre appareil).
fn meta_disk(meta: &Value) -> Option<usize> {
    meta["disk"].as_u64().map(|d| d as usize)
}

/**
 * Disque inséré quand l'état [file] a été fait : celui de ses informations (état de l'historique de
 * [history]) ou de l'état de l'historique identique (état de reprise) ; None s'il n'est pas connu.
 */
pub fn disk_of(file: &Path, history: &Path) -> Option<usize> {
    match read_json(&file.with_extension("json")) {
        Some(meta) => meta_disk(&meta),
        None => same_in_history(history, file).and_then(|m| meta_disk(&m)),
    }
}

/**
 * État le plus récent de l'historique du jeu sur ce PC ([dir] et états des autres appareils déjà
 * téléchargés) : fichier et date (ms) ; None sans état. [disk] : parmi les états faits sur ce
 * disque (disque inconnu : le premier).
 */
fn latest_on(dir: &Path, disk: Option<usize>) -> Option<(PathBuf, f64)> {
    [dir.to_path_buf(), online_dir(dir)]
        .into_iter()
        .flat_map(|d| {
            read_dir(&d).into_iter().filter(|m| disk.map_or(true, |k| meta_disk(m).unwrap_or(0) == k)).filter_map(move |m| {
                let id = m["id"].as_str()?;
                Some((d.join(format!("{id}.state")), m["createdAt"].as_f64().unwrap_or(0.0)))
            })
        })
        .max_by(|a, b| a.1.total_cmp(&b.1))
}

/**
 * État à charger pour reprendre la partie, avec sa date (ms, comparaison entre les cœurs du système) :
 * l'état de reprise ([quick], écrit en quittant) ou l'état de l'historique s'il est plus récent ou que
 * l'état de reprise manque ; None sans aucun état. Parmi les états faits sur le disque [disk] (jeu en
 * plusieurs disques ; disque inconnu : le premier) ; None : tous les états.
 */
pub fn resume_point_on(quick: &Path, history: &Path, disk: Option<usize>) -> Option<(PathBuf, f64)> {
    let on_disk = |k: usize| disk_of(quick, history).unwrap_or(0) == k;
    let quick_date = (quick.is_file() && disk.map_or(true, on_disk)).then(|| crate::paths::mtime_ms(quick).unwrap_or(0.0));
    match (quick_date, latest_on(history, disk)) {
        (Some(q), Some((file, date))) if date > q => Some((file, date)),
        (Some(q), _) => Some((quick.to_path_buf(), q)),
        (None, Some(point)) => Some(point),
        (None, None) => None,
    }
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
                "id": id, "core": m["core"], "createdAt": m["createdAt"], "device": m["device"], "platform": m["platform"], "disk": m["disk"],
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
pub async fn flush_pending() -> (usize, usize) {
    let (mut sent, mut skipped) = (0, 0);
    // États en ligne de chaque jeu, relus une fois.
    let mut remote: HashMap<String, Option<Vec<Value>>> = HashMap::new();
    for entry in account::pending("pendingStates") {
        let dir = PathBuf::from(entry["dir"].as_str().unwrap_or(""));
        let id = entry["id"].as_str().unwrap_or("");
        let key = account::game_key(&entry["gameId"]);
        if !remote.contains_key(&key) {
            remote.insert(key.clone(), online(&entry["gameId"]).await);
        }
        let created_at = valid_id(id).ok().and_then(|id| read_json(&dir.join(format!("{id}.json")))).and_then(|m| m["createdAt"].as_f64());
        // Assez d'états plus récents en ligne pour le jeu : il ne serait pas gardé, il n'est pas envoyé.
        if let (Some(list), Some(created_at)) = (remote[&key].as_ref(), created_at) {
            if too_many_newer(list, created_at) {
                skipped += 1;
                forget_pending(id);
                continue;
            }
        }
        match upload(&entry["gameId"], &dir, id).await {
            Err(e) if e.is("errors.unreachable") => break,
            Ok(()) => sent += 1,
            Err(_) => {} // état supprimé, refusé : abandonné
        }
        forget_pending(id);
    }
    (sent, skipped)
}

fn forget_pending(id: &str) {
    let mut list = account::pending("pendingStates");
    list.retain(|p| p["id"] != id);
    settings::save(json!({ "pendingStates": list }));
}

/// États gardés en ligne par jeu et par appareil (hors épinglés), comme sur le serveur.
pub const HISTORY_LIMIT: usize = 10;

/**
 * État fait hors ligne à [created_at] (ms) : le jeu a-t-il déjà [HISTORY_LIMIT] états plus récents
 * en ligne (hors épinglés) ? Envoyé, il ne serait pas gardé.
 */
pub fn too_many_newer(remote: &[Value], created_at: f64) -> bool {
    remote
        .iter()
        .filter(|s| s["pinned"] != json!(true))
        .filter(|s| account::parse_date_ms(s["createdAt"].as_str().unwrap_or("")) > created_at)
        .count()
        >= HISTORY_LIMIT
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

    /// État à charger pour reprendre la partie, tous disques confondus.
    fn resume_file(quick: &Path, history: &Path) -> Option<PathBuf> {
        resume_point_on(quick, history, None).map(|(file, _)| file)
    }

    #[test]
    fn etat_hors_ligne_trop_ancien_pas_envoye() {
        let at = |day: u32, pinned: bool| json!({ "createdAt": format!("2026-10-{day:02}T12:00:00.000Z"), "pinned": pinned });
        let offline = account::parse_date_ms("2026-10-05T12:00:00.000Z");
        // 9 états plus récents (et un épinglé, qui ne compte pas) : encore gardé, envoyé.
        let mut remote: Vec<Value> = (6..15).map(|d| at(d, false)).collect();
        remote.push(at(20, true));
        remote.push(at(1, false));
        assert!(!too_many_newer(&remote, offline));
        // 10 plus récents : il ne serait pas gardé.
        remote.push(at(16, false));
        assert!(too_many_newer(&remote, offline));
    }

    #[test]
    fn etat_de_reprise_ajoute_a_l_historique() {
        let dir = std::env::temp_dir().join(format!("romcloud-quick-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let history = dir.join("Jeu.history");
        let quick = dir.join("Jeu.state");
        assert!(!record_quick(&history, &quick, "snes9x", "PC", "windows"));
        // État de reprise téléchargé du profil (fait sur un autre appareil) : ajouté une seule fois.
        std::fs::write(&quick, b"etat du telephone").unwrap();
        assert!(record_quick(&history, &quick, "snes9x", "Pixel 8", "android"));
        assert!(!record_quick(&history, &quick, "snes9x", "Pixel 8", "android"));
        let list = read_dir(&history);
        assert_eq!(list.len(), 1);
        assert_eq!(list[0]["device"], "Pixel 8");
        assert_eq!(std::fs::read(history.join(format!("{}.state", list[0]["id"].as_str().unwrap()))).unwrap(), b"etat du telephone");
        assert!(valid_id(list[0]["id"].as_str().unwrap()).is_ok());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn reprise_depuis_l_etat_le_plus_recent() {
        let dir = std::env::temp_dir().join(format!("romcloud-resume-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let history = dir.join("Jeu.history");
        let quick = dir.join("Jeu.state");
        assert_eq!(resume_file(&quick, &history), None);
        // Historique seul (états faits depuis le menu, ou d'un autre appareil) : le plus récent.
        let add = |d: &Path, id: &str, at: f64| {
            write_json(&d.join(format!("{id}.json")), &json!({ "id": id, "createdAt": at })).unwrap();
            std::fs::write(d.join(format!("{id}.state")), b"s").unwrap();
        };
        add(&history, "aaaaaaaa", 1000.0);
        add(&online_dir(&history), "bbbbbbbb", 2000.0);
        assert_eq!(resume_file(&quick, &history), Some(online_dir(&history).join("bbbbbbbb.state")));
        // État de reprise plus récent (écrit maintenant) : choisi.
        std::fs::write(&quick, b"q").unwrap();
        assert_eq!(resume_file(&quick, &history), Some(quick.clone()));
        // État de l'historique plus récent que l'état de reprise : choisi.
        add(&history, "cccccccc", crate::paths::mtime_ms(&quick).unwrap() + 60_000.0);
        assert_eq!(resume_file(&quick, &history), Some(history.join("cccccccc.state")));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn reprise_par_disque() {
        let dir = std::env::temp_dir().join(format!("romcloud-disk-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let history = dir.join("Jeu.history");
        let quick = dir.join("Jeu.state");
        let add = |id: &str, at: f64, disk: Option<usize>, bytes: &[u8]| {
            let mut meta = json!({ "id": id, "createdAt": at });
            if let Some(d) = disk {
                meta["disk"] = json!(d);
            }
            write_json(&history.join(format!("{id}.json")), &meta).unwrap();
            std::fs::write(history.join(format!("{id}.state")), bytes).unwrap();
        };
        // Ancien état sans disque (le premier), puis un état du disque 2.
        add("aaaaaaaa", 1000.0, None, b"a");
        add("bbbbbbbb", 2000.0, Some(1), b"b");
        assert_eq!(resume_point_on(&quick, &history, Some(0)).map(|p| p.0), Some(history.join("aaaaaaaa.state")));
        assert_eq!(resume_point_on(&quick, &history, Some(1)).map(|p| p.0), Some(history.join("bbbbbbbb.state")));
        assert_eq!(resume_point_on(&quick, &history, Some(2)), None);
        assert_eq!(disk_of(&history.join("aaaaaaaa.state"), &history), None);
        assert_eq!(disk_of(&history.join("bbbbbbbb.state"), &history), Some(1));
        // État de reprise écrit avec un état du disque 3 (même taille, même date) : reprise du disque 3.
        std::fs::write(&quick, b"qq").unwrap();
        add("cccccccc", crate::paths::mtime_ms(&quick).unwrap(), Some(2), b"qq");
        assert_eq!(disk_of(&quick, &history), Some(2));
        assert_eq!(resume_point_on(&quick, &history, Some(2)).map(|p| p.0), Some(quick.clone()));
        assert_eq!(resume_point_on(&quick, &history, Some(1)).map(|p| p.0), Some(history.join("bbbbbbbb.state")));
        let _ = std::fs::remove_dir_all(&dir);
    }

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
