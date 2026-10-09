// Accès au serveur RomCloud, avec cache disque des listes (utilisation hors ligne).
use crate::error::{AppError, Result};
use crate::{connectivity, paths, settings};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::LazyLock;
use std::time::Duration;

static CLIENT: LazyLock<reqwest::Client> =
    LazyLock::new(|| reqwest::Client::builder().user_agent("RomCloud").build().expect("client HTTP"));

/// Client HTTP partagé.
pub fn client() -> &'static reqwest::Client {
    &CLIENT
}

fn cache_dir() -> PathBuf {
    paths::user_data().join("api-cache")
}

fn safe_name(s: &str) -> String {
    s.chars().map(|c| if c.is_ascii_alphanumeric() || "._-".contains(c) { c } else { '_' }).collect()
}

/// En-têtes communs : langue de l'interface, clé d'API, plateforme (le serveur ne liste que les
/// systèmes proposés sous Windows).
pub fn headers(api_key: &str) -> reqwest::header::HeaderMap {
    let mut h = reqwest::header::HeaderMap::new();
    h.insert("X-RomCloud-Platform", reqwest::header::HeaderValue::from_static("windows"));
    if let Ok(v) = settings::language().parse() {
        h.insert(reqwest::header::ACCEPT_LANGUAGE, v);
    }
    if !api_key.is_empty() {
        if let Ok(v) = format!("Bearer {api_key}").parse() {
            h.insert(reqwest::header::AUTHORIZATION, v);
        }
    }
    h
}

/// Détail d'une erreur réseau (code court quand il existe).
pub fn network_detail(e: &reqwest::Error) -> String {
    let mut source: Option<&dyn std::error::Error> = std::error::Error::source(e);
    let mut detail = e.to_string();
    while let Some(s) = source {
        detail = s.to_string();
        source = s.source();
    }
    detail
}

/// Requête GET ; renvoie le corps. [server]/[key] : ceux des réglages, ou ceux en cours de test.
pub async fn request(api_path: &str, target: Option<(&str, &str)>) -> Result<String> {
    let (server, key) = match target {
        Some((s, k)) => (s.to_string(), k.to_string()),
        None => (settings::get_str("serverUrl"), settings::get_str("apiKey")),
    };
    if server.is_empty() {
        return Err(AppError::new("errors.noServer", json!({})));
    }
    // Seul le serveur configuré compte pour l'état de connexion (pas une adresse en cours de test).
    let tracked = server == settings::get_str("serverUrl");
    let res = match client().get(format!("{server}{api_path}")).headers(headers(&key)).timeout(Duration::from_secs(10)).send().await {
        Ok(res) => res,
        Err(e) => {
            if tracked {
                connectivity::mark_offline();
            }
            return Err(AppError::new("errors.unreachable", json!({ "detail": network_detail(&e) })));
        }
    };
    if tracked {
        connectivity::mark_online();
    }
    let status = res.status();
    let text = res.text().await.unwrap_or_default();
    if !status.is_success() {
        let server_message = serde_json::from_str::<Value>(&text).ok().and_then(|v| v.get("error").and_then(Value::as_str).map(String::from));
        if let Some(message) = server_message {
            return Err(AppError::with_message("errors.server", json!({ "message": message }), message));
        }
        let key = if status.as_u16() == 401 { "errors.apiKey" } else { "errors.http" };
        return Err(AppError::new(key, json!({ "status": status.as_u16() })));
    }
    Ok(text)
}

fn is_unreachable(e: &AppError) -> bool {
    e.is("errors.unreachable") || e.is("errors.noServer")
}

fn read_json(file: &PathBuf) -> Option<Value> {
    serde_json::from_str(&std::fs::read_to_string(file).ok()?).ok()
}

/// Charge une liste et la met en cache ; hors ligne, renvoie la dernière version en cache.
async fn load_with_cache(name: &str, api_path: &str) -> Result<(Value, bool)> {
    match request(api_path, None).await {
        Ok(text) => {
            let _ = std::fs::create_dir_all(cache_dir());
            let _ = std::fs::write(cache_dir().join(name), &text);
            Ok((serde_json::from_str(&text)?, false))
        }
        Err(e) if is_unreachable(&e) => match read_json(&cache_dir().join(name)) {
            Some(data) => Ok((data, true)),
            None => Err(e),
        },
        Err(e) => Err(e),
    }
}

// Jeux téléchargés (et leur système) : gardés à part pour être listés hors ligne, même si la liste
// de leur système n'a jamais été mise en cache (jeu téléchargé depuis la recherche globale).
fn downloaded_index() -> Value {
    read_json(&cache_dir().join("downloaded.json")).unwrap_or_else(|| json!({ "systems": {}, "games": {} }))
}

fn id_key(v: &Value) -> String {
    match v.get("id") {
        Some(Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
        None => String::new(),
    }
}

pub fn remember_downloaded(system: &Value, game: &Value) -> Result<()> {
    let mut index = downloaded_index();
    index["systems"][id_key(system)] = system.clone();
    index["games"][id_key(game)] = game.clone();
    std::fs::create_dir_all(cache_dir())?;
    std::fs::write(cache_dir().join("downloaded.json"), serde_json::to_string(&index)?)?;
    Ok(())
}

fn index_values(kind: &str) -> Vec<Value> {
    downloaded_index().get(kind).and_then(Value::as_object).map(|m| m.values().cloned().collect()).unwrap_or_default()
}

/// Hors ligne : la liste en cache (ou rien) est complétée par les éléments [extra] des jeux téléchargés.
async fn with_downloaded(load: Result<(Value, bool)>, extra: Vec<Value>) -> Result<Value> {
    let (data, offline, error) = match load {
        Ok((data, offline)) => (data, offline, None),
        Err(e) if is_unreachable(&e) => (json!([]), true, Some(e)),
        Err(e) => return Err(e),
    };
    if !offline {
        return Ok(json!({ "data": data, "offline": false }));
    }
    let mut list = data.as_array().cloned().unwrap_or_default();
    let known: HashSet<String> = list.iter().map(id_key).collect();
    list.extend(extra.into_iter().filter(|x| !known.contains(&id_key(x))));
    if list.is_empty() {
        if let Some(e) = error {
            return Err(e);
        }
    }
    Ok(json!({ "data": list, "offline": true }))
}

pub async fn systems() -> Result<Value> {
    with_downloaded(load_with_cache("systems.json", "/api/systems").await, index_values("systems")).await
}

/// Jeu identifié par le scraping du serveur (statut « ok ») ; sans statut (ancien cache), identifié.
fn identified(game: &Value) -> bool {
    game.get("scrapeStatus").and_then(Value::as_str).is_none_or(|s| s == "ok")
}

/// Liste { data, offline } sans les jeux non identifiés (introuvables ou pas encore scrapés) quand [hide].
fn without_unidentified(mut result: Value, hide: bool) -> Value {
    if hide {
        if let Some(list) = result.get_mut("data").and_then(Value::as_array_mut) {
            list.retain(identified);
        }
    }
    result
}

/// Réglage « Masquer les jeux non identifiés » appliqué à une liste de jeux.
fn visible_games(result: Result<Value>) -> Result<Value> {
    let hide = settings::load().get("hideUnidentified").and_then(Value::as_bool).unwrap_or(false);
    result.map(|r| without_unidentified(r, hide))
}

pub async fn games(system_id: &str) -> Result<Value> {
    let load = load_with_cache(
        &format!("games-{}.json", safe_name(system_id)),
        &format!("/api/systems/{}/games", urlencoding::encode(system_id)),
    )
    .await;
    let extra = index_values("games").into_iter().filter(|g| g.get("systemId").and_then(Value::as_str) == Some(system_id)).collect();
    visible_games(with_downloaded(load, extra).await)
}

/// BIOS du système sur le serveur (liste vide si le système n'en a pas ou si le serveur est injoignable).
pub async fn bios(system: &Value) -> Vec<Value> {
    if system.get("biosCount").and_then(Value::as_u64).unwrap_or(0) == 0 {
        return vec![];
    }
    let id = system.get("id").and_then(Value::as_str).unwrap_or("");
    match load_with_cache(&format!("bios-{}.json", safe_name(id)), &format!("/api/systems/{}/bios?catalog=0", urlencoding::encode(id))).await {
        Ok((data, _)) => data.get("files").and_then(Value::as_array).cloned().unwrap_or_default(),
        Err(_) => vec![],
    }
}

/// Recherche dans tous les systèmes ; hors ligne, dans les listes déjà en cache.
pub async fn search(query: &str) -> Result<Value> {
    visible_games(search_all(query).await)
}

async fn search_all(query: &str) -> Result<Value> {
    match request(&format!("/api/search?q={}", urlencoding::encode(query)), None).await {
        Ok(text) => Ok(json!({ "data": serde_json::from_str::<Value>(&text)?, "offline": false })),
        Err(e) if e.is("errors.unreachable") => {
            let words: Vec<String> = query.to_lowercase().split_whitespace().map(String::from).collect();
            let mut by_id: HashMap<String, Value> = HashMap::new();
            for game in index_values("games") {
                by_id.insert(id_key(&game), game);
            }
            if let Ok(entries) = std::fs::read_dir(cache_dir()) {
                for entry in entries.flatten() {
                    if entry.file_name().to_string_lossy().starts_with("games-") {
                        if let Some(Value::Array(list)) = read_json(&entry.path()) {
                            for game in list {
                                by_id.insert(id_key(&game), game);
                            }
                        }
                    }
                }
            }
            let text = |g: &Value, k: &str| g.get(k).and_then(Value::as_str).unwrap_or("").to_lowercase();
            let mut data: Vec<Value> = by_id
                .into_values()
                .filter(|g| words.iter().all(|w| text(g, "title").contains(w) || text(g, "fileName").contains(w)))
                .collect();
            data.sort_by_key(|g| text(g, "title"));
            Ok(json!({ "data": data, "offline": true }))
        }
        Err(e) => Err(e),
    }
}

/// Teste une adresse et une clé sans modifier les réglages.
pub async fn test(server_url: &str, api_key: &str) -> Result<Value> {
    let server = settings::normalize_url(server_url);
    let key = api_key.trim().to_string();
    let info: Value = serde_json::from_str(&request("/api/info", Some((&server, &key))).await?)?;
    request("/api/status", Some((&server, &key))).await?;
    Ok(info)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jeux_non_identifies_masques() {
        let list = json!({ "data": [
            { "id": 1, "scrapeStatus": "ok" },
            { "id": 2, "scrapeStatus": "notfound" },
            { "id": 3, "scrapeStatus": "none" },
            { "id": 4, "scrapeStatus": "error" },
            { "id": 5 },
        ], "offline": false });
        let ids = |v: Value| v["data"].as_array().unwrap().iter().map(|g| g["id"].as_i64().unwrap()).collect::<Vec<_>>();
        assert_eq!(ids(without_unidentified(list.clone(), true)), [1, 5]);
        assert_eq!(ids(without_unidentified(list, false)), [1, 2, 3, 4, 5]);
    }
}
