// Profil du joueur sur le serveur : connexion, temps de jeu par jeu, sauvegardes en ligne du moteur
// intégré (reprise sur un autre appareil) et erreurs signalées à l'administration. La session (jeton)
// est gardée dans les réglages ; les durées de jeu et les sauvegardes non envoyées (serveur
// injoignable) attendent dans les réglages et partent dès le retour de la connexion ([flush]).
use crate::error::{AppError, Result};
use crate::{api, connectivity, events, paths, settings};
use serde_json::{json, Value};
use std::path::Path;
use std::time::Duration;

/// Parties plus courtes ignorées (jeu quitté aussitôt, lancement raté).
pub const MIN_PLAY_SECONDS: f64 = 10.0;
const KINDS: [&str; 2] = ["state", "sram"];

fn changed() {
    events::emit("account:update", ());
}

pub(crate) fn session() -> Option<Value> {
    settings::load().get("session").filter(|s| s.is_object()).cloned()
}

/// Nom de ce PC (appareil d'origine des sauvegardes et des états envoyés).
pub(crate) fn hostname() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".into())
}

pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

pub(crate) enum Body {
    None,
    Json(Value),
    /// Fichier de sauvegarde ou d'état : compressé en gzip quand c'est plus petit ([gzip_if_smaller]).
    Bytes(Vec<u8>),
    /// Image (miniature d'un état) : contenu et type (« image/png »).
    Image(Vec<u8>, &'static str),
}

pub(crate) struct Response {
    pub json: Value,
    pub bytes: Vec<u8>,
}

/// Compression gardée seulement si elle fait gagner au moins 5 % (états déjà compressés : PPSSPP…).
const MIN_GAIN: f64 = 0.95;

/// Données compressées en gzip, ou None si la compression n'y gagne rien.
pub(crate) fn gzip_if_smaller(data: &[u8]) -> Option<Vec<u8>> {
    use std::io::Write;
    let mut encoder = flate2::write::GzEncoder::new(Vec::with_capacity(data.len() / 4), flate2::Compression::new(6));
    encoder.write_all(data).ok()?;
    let packed = encoder.finish().ok()?;
    ((packed.len() as f64) < data.len() as f64 * MIN_GAIN).then_some(packed)
}

fn gunzip(data: &[u8]) -> std::io::Result<Vec<u8>> {
    use std::io::Read;
    let mut out = Vec::with_capacity(data.len() * 4);
    flate2::read::GzDecoder::new(data).read_to_end(&mut out)?;
    Ok(out)
}

/// Requête au serveur ; `raw` : corps binaire reçu ([Response::bytes], décompressé s'il arrive en gzip).
pub(crate) async fn call(api_path: &str, method: reqwest::Method, body: Body, raw: bool, extra: &[(&str, String)], timeout: Duration) -> Result<Response> {
    let server = settings::get_str("serverUrl");
    if server.is_empty() {
        return Err(AppError::new("errors.noServer", json!({})));
    }
    let mut request = api::client()
        .request(method, format!("{server}{api_path}"))
        .headers(api::headers(&settings::get_str("apiKey")))
        .header("X-RomCloud-Device", hostname())
        .header("X-RomCloud-Version", version())
        .timeout(timeout);
    if let Some(token) = session().and_then(|s| s.get("token").and_then(Value::as_str).map(String::from)) {
        request = request.header("X-RomCloud-Session", token);
    }
    for (k, v) in extra {
        request = request.header(*k, v);
    }
    // Fichiers reçus : le serveur les renvoie compressés tels qu'ils ont été envoyés.
    if raw {
        request = request.header("Accept-Encoding", "gzip");
    }
    request = match body {
        Body::None => request,
        Body::Json(v) => request.json(&v),
        Body::Bytes(b) => {
            // Sauvegardes et états : compressés ici (moins de place sur le serveur, moins à envoyer).
            let size = b.len();
            let packed = tokio::task::spawn_blocking(move || gzip_if_smaller(&b).ok_or(b)).await.map_err(|e| AppError::msg(e.to_string()))?;
            let request = request.header("Content-Type", "application/octet-stream");
            match packed {
                Ok(gz) => request.header("Content-Encoding", "gzip").header("X-Uncompressed-Size", size.to_string()).body(gz),
                Err(b) => request.body(b),
            }
        }
        Body::Image(b, mime) => request.header("Content-Type", mime).body(b),
    };
    let res = match request.send().await {
        Ok(res) => res,
        Err(e) => {
            connectivity::mark_offline();
            return Err(AppError::new("errors.unreachable", json!({ "detail": api::network_detail(&e) })));
        }
    };
    connectivity::mark_online();
    let status = res.status();
    if status.as_u16() == 204 {
        return Ok(Response { json: Value::Null, bytes: vec![] });
    }
    if !status.is_success() {
        let data: Value = res.json().await.unwrap_or(json!({}));
        // Session refusée (déconnectée depuis l'administration, mot de passe changé) : oubliée ici aussi.
        if data.get("code").and_then(Value::as_str) == Some("errors.signedOut") && session().is_some() {
            settings::save(json!({ "session": null }));
            changed();
        }
        let mut err = match data.get("error").and_then(Value::as_str) {
            Some(message) => AppError::with_message("errors.server", json!({ "message": message }), message),
            None => AppError::new("errors.http", json!({ "status": status.as_u16() })),
        };
        err.status = Some(status.as_u16());
        return Err(err);
    }
    if raw {
        let gzipped = res.headers().get("content-encoding").and_then(|v| v.to_str().ok()).is_some_and(|v| v.eq_ignore_ascii_case("gzip"));
        let bytes = res.bytes().await?.to_vec();
        let bytes = if gzipped {
            tokio::task::spawn_blocking(move || gunzip(&bytes)).await.map_err(|e| AppError::msg(e.to_string()))??
        } else {
            bytes
        };
        return Ok(Response { json: Value::Null, bytes });
    }
    Ok(Response { json: res.json().await.unwrap_or(Value::Null), bytes: vec![] })
}

/// Profil connecté sur ce PC.
pub fn signed_in() -> bool {
    session().is_some()
}

/// Jeu par Internet : annonce de ce PC ; renvoie la réponse du serveur (autres appareils connectés).
pub async fn play_presence(body: Value) -> Result<Value> {
    Ok(call("/api/account/play/presence", reqwest::Method::PUT, Body::Json(body), false, &[], Duration::from_secs(8)).await?.json)
}

/// Connexion de relais à ouvrir (partie [session], [channel], rôle [role]), ou None sans profil.
pub fn relay_request(session: &str, channel: &str, role: &str) -> Option<crate::relay::Request> {
    let token = self::session()?.get("token").and_then(Value::as_str)?.to_string();
    let server = settings::get_str("serverUrl");
    if server.is_empty() {
        return None;
    }
    let mut headers: Vec<(String, String)> = api::headers(&settings::get_str("apiKey"))
        .iter()
        .filter_map(|(k, v)| v.to_str().ok().map(|v| (k.as_str().to_string(), v.to_string())))
        .collect();
    headers.push(("X-RomCloud-Session".into(), token));
    headers.push(("X-RomCloud-Device".into(), hostname()));
    headers.push(("X-RomCloud-Version".into(), version()));
    Some(crate::relay::Request { url: format!("{server}/api/play/relay?session={session}&channel={channel}&role={role}"), headers })
}

pub(crate) async fn get(api_path: &str, timeout_ms: u64) -> Result<Value> {
    Ok(call(api_path, reqwest::Method::GET, Body::None, false, &[], Duration::from_millis(timeout_ms)).await?.json)
}

async fn post(api_path: &str, body: Value) -> Result<Value> {
    Ok(call(api_path, reqwest::Method::POST, Body::Json(body), false, &[], Duration::from_secs(15)).await?.json)
}

// ---------------------------------------------------------------------------
// Connexion
// ---------------------------------------------------------------------------

async fn remember(data: Value) -> Result<Value> {
    let user = data.get("user").cloned().unwrap_or(json!({}));
    settings::save(json!({ "session": { "token": data["token"], "username": user["username"], "userId": user["id"] } }));
    tauri::async_runtime::spawn(async {
        flush().await;
    });
    changed();
    Ok(state().await)
}

pub async fn register(username: &str, password: &str) -> Result<Value> {
    remember(post("/api/account/register", json!({ "username": username, "password": password })).await?).await
}

pub async fn login(username: &str, password: &str) -> Result<Value> {
    remember(post("/api/account/login", json!({ "username": username, "password": password })).await?).await
}

pub async fn logout() -> Result<Value> {
    if session().is_some() {
        let _ = post("/api/account/logout", Value::Null).await;
    }
    settings::save(json!({ "session": null }));
    changed();
    Ok(state().await)
}

/// Profil affiché : { signedIn, username, playSeconds, gamesPlayed, offline }.
pub async fn state() -> Value {
    let Some(s) = session() else { return json!({ "signedIn": false }) };
    match get("/api/account/me", 15000).await {
        Ok(me) => json!({ "signedIn": true, "username": me["user"]["username"], "playSeconds": me["playSeconds"], "gamesPlayed": me["gamesPlayed"] }),
        Err(e) => {
            if session().is_none() {
                return json!({ "signedIn": false });
            }
            json!({ "signedIn": true, "username": s["username"], "offline": true, "error": e.message })
        }
    }
}

// ---------------------------------------------------------------------------
// Temps de jeu
// ---------------------------------------------------------------------------

pub(crate) fn pending(key: &str) -> Vec<Value> {
    settings::load().get(key).and_then(Value::as_array).cloned().unwrap_or_default()
}

pub(crate) fn game_key(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

/// Temps de jeu par jeu : { [gameId]: secondes } (vide sans profil ou hors ligne).
pub async fn playtime() -> Value {
    if session().is_none() {
        return json!({});
    }
    let Ok(list) = get("/api/account/playtime", 15000).await else { return json!({}) };
    let mut map = serde_json::Map::new();
    for p in list.as_array().cloned().unwrap_or_default() {
        map.insert(game_key(&p["gameId"]), p["seconds"].clone());
    }
    for p in pending("pendingPlaytime") {
        let key = game_key(&p["gameId"]);
        let total = map.get(&key).and_then(Value::as_f64).unwrap_or(0.0) + p["seconds"].as_f64().unwrap_or(0.0);
        map.insert(key, json!(total));
    }
    Value::Object(map)
}

/// Durées gardées faute de serveur : envoyées dès que possible.
async fn flush_playtime() {
    let list = pending("pendingPlaytime");
    if list.is_empty() || session().is_none() {
        return;
    }
    let mut left = Vec::new();
    for p in list {
        if let Err(e) = post("/api/account/playtime", p.clone()).await {
            if e.is("errors.unreachable") {
                left.push(p);
            }
        }
    }
    settings::save(json!({ "pendingPlaytime": left }));
}

/// Ajoute une partie au temps de jeu du jeu (profil connecté).
pub async fn add_playtime(game_id: Value, seconds: f64) {
    if session().is_none() || seconds < MIN_PLAY_SECONDS {
        return;
    }
    let entry = json!({ "gameId": game_id, "seconds": seconds.round() as i64 });
    flush_playtime().await;
    if let Err(e) = post("/api/account/playtime", entry.clone()).await {
        if e.is("errors.unreachable") {
            let mut list = pending("pendingPlaytime");
            list.push(entry);
            settings::save(json!({ "pendingPlaytime": list }));
        }
    }
    changed();
}

// ---------------------------------------------------------------------------
// Sauvegardes en ligne (moteur intégré)
// ---------------------------------------------------------------------------

/// Cœurs dont les sauvegardes sont interchangeables (mupen64plus_next sous Windows, _gles3 sous Android).
fn save_core_family(core: &str) -> &str {
    core.strip_suffix("_gles3").or_else(|| core.strip_suffix("_gles2")).unwrap_or(core)
}

pub fn same_save_core(a: &str, b: &str) -> bool {
    save_core_family(a) == save_core_family(b)
}

fn save_path(game_id: &Value, core: &str, kind: &str) -> String {
    format!("/api/account/saves/{}/{}/{kind}", game_key(game_id), urlencoding::encode(core))
}

pub(crate) fn parse_date_ms(s: &str) -> f64 {
    chrono::DateTime::parse_from_rfc3339(s).map(|d| d.timestamp_millis() as f64).unwrap_or(0.0)
}

/// Fichiers de sauvegarde d'une partie : (type, chemin).
pub type SaveFiles = Vec<(&'static str, std::path::PathBuf)>;

/// Avant de jouer : les sauvegardes en ligne plus récentes que celles du PC remplacent les fichiers locaux.
pub async fn download_newer(game_id: &Value, core: &str, files: &SaveFiles) -> Vec<String> {
    if session().is_none() {
        return vec![];
    }
    let Ok(remote) = get(&format!("/api/account/saves?gameId={}", game_key(game_id)), 8000).await else { return vec![] };
    let remote = remote.as_array().cloned().unwrap_or_default();
    let mut updated = Vec::new();
    for kind in KINDS {
        // Variantes du même cœur (sauvegardes envoyées par Android) : la plus récente.
        let save = remote
            .iter()
            .filter(|s| same_save_core(s["core"].as_str().unwrap_or(""), core) && s["kind"] == kind)
            .max_by(|a, b| parse_date_ms(a["savedAt"].as_str().unwrap_or("")).total_cmp(&parse_date_ms(b["savedAt"].as_str().unwrap_or(""))));
        let (Some(save), Some((_, file))) = (save, files.iter().find(|(k, _)| *k == kind)) else { continue };
        let saved_at = parse_date_ms(save["savedAt"].as_str().unwrap_or(""));
        let local = paths::mtime_ms(file).unwrap_or(0.0);
        if saved_at <= local + 1000.0 {
            continue;
        }
        let path = save_path(game_id, save["core"].as_str().unwrap_or(core), kind);
        let Ok(res) = call(&path, reqwest::Method::GET, Body::None, true, &[], Duration::from_secs(120)).await else { continue };
        let part = std::path::PathBuf::from(format!("{}.download", file.display()));
        let ok = (|| -> std::io::Result<()> {
            if let Some(dir) = file.parent() {
                std::fs::create_dir_all(dir)?;
            }
            std::fs::write(&part, &res.bytes)?;
            std::fs::rename(&part, file)?;
            let time = filetime::FileTime::from_unix_time((saved_at / 1000.0) as i64, ((saved_at % 1000.0) * 1_000_000.0) as u32);
            filetime::set_file_times(file, time, time)?;
            Ok(())
        })();
        if ok.is_ok() {
            updated.push(kind.to_string());
        }
    }
    updated
}

/// Envoie une sauvegarde ; renvoie false si le serveur est injoignable (à réessayer plus tard).
async fn send_save(entry: &Value) -> bool {
    let file = Path::new(entry["file"].as_str().unwrap_or(""));
    let Some(size) = paths::size(file) else { return true };
    if size == 0 {
        return true;
    }
    let Ok(data) = std::fs::read(file) else { return true };
    let mtime = paths::mtime_ms(file).unwrap_or(0.0).round() as i64;
    let path = save_path(&entry["gameId"], entry["core"].as_str().unwrap_or(""), entry["kind"].as_str().unwrap_or(""));
    match call(&path, reqwest::Method::PUT, Body::Bytes(data), false, &[("X-Saved-At", mtime.to_string())], Duration::from_secs(300)).await {
        Ok(_) => true,
        Err(e) if e.is("errors.unreachable") => false,
        Err(e) => {
            report_error(&format!("saves:{}", entry["kind"].as_str().unwrap_or("")), &e.message, None);
            true
        }
    }
}

fn other_saves(entry: &Value) -> Vec<Value> {
    pending("pendingSaves")
        .into_iter()
        .filter(|p| p["gameId"] != entry["gameId"] || p["core"] != entry["core"] || p["kind"] != entry["kind"])
        .collect()
}

/// Après la partie : les fichiers modifiés depuis [since] (ms) sont envoyés au serveur (ou mis en attente).
pub async fn upload_changed(game_id: &Value, core: &str, files: &SaveFiles, since: f64) {
    if session().is_none() {
        return;
    }
    let mut queued = false;
    for kind in KINDS {
        let Some((_, file)) = files.iter().find(|(k, _)| *k == kind) else { continue };
        let (Some(mtime), Some(size)) = (paths::mtime_ms(file), paths::size(file)) else { continue };
        if mtime < since || size == 0 {
            continue;
        }
        let entry = json!({ "gameId": game_id, "core": core, "kind": kind, "file": file.to_string_lossy() });
        if send_save(&entry).await {
            let others = other_saves(&entry);
            if others.len() != pending("pendingSaves").len() {
                settings::save(json!({ "pendingSaves": others }));
            }
        } else {
            let mut list = other_saves(&entry);
            list.push(entry);
            settings::save(json!({ "pendingSaves": list }));
            queued = true;
        }
    }
    if queued {
        changed();
    }
}

static FLUSHING: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// Envoie le travail en attente (temps de jeu, sauvegardes faites hors ligne) ; renvoie le nombre de
/// sauvegardes envoyées. Un seul envoi à la fois.
pub async fn flush() -> usize {
    let Ok(_guard) = FLUSHING.try_lock() else { return 0 };
    if session().is_none() {
        return 0;
    }
    flush_playtime().await;
    let mut sent = crate::states::flush_pending().await;
    for entry in pending("pendingSaves") {
        if !send_save(&entry).await {
            break; // serveur de nouveau injoignable : le reste attend
        }
        sent += 1;
        settings::save(json!({ "pendingSaves": other_saves(&entry) }));
    }
    if sent > 0 {
        changed();
    }
    sent
}

/// Nombre d'envois en attente (sauvegardes et durées de jeu).
pub fn pending_count() -> usize {
    pending("pendingSaves").len() + pending("pendingPlaytime").len() + pending("pendingStates").len()
}

/// Sauvegardes en ligne du jeu (fiche du jeu) : [{ core, kind, savedAt, device, … }].
pub async fn saves(game_id: &Value) -> Vec<Value> {
    if session().is_none() {
        return vec![];
    }
    get(&format!("/api/account/saves?gameId={}", game_key(game_id)), 8000).await.ok().and_then(|v| v.as_array().cloned()).unwrap_or_default()
}

// ---------------------------------------------------------------------------
// Erreurs
// ---------------------------------------------------------------------------

/// Signale une erreur à l'administration (sans effet si le serveur est injoignable).
pub fn report_error(context: &str, message: &str, details: Option<String>) {
    if message.is_empty() || settings::get_str("serverUrl").is_empty() {
        return;
    }
    let body = json!({ "context": context, "message": message, "details": details });
    tauri::async_runtime::spawn(async move {
        let _ = call("/api/account/errors", reqwest::Method::POST, Body::Json(body), false, &[], Duration::from_secs(8)).await;
    });
}

#[cfg(test)]
mod compression_tests {
    use super::*;

    #[test]
    fn sauvegarde_compressee_seulement_si_plus_petite() {
        let state: Vec<u8> = (0..200_000u32).map(|i| if i % 97 == 0 { (i % 251) as u8 } else { 0 }).collect();
        let packed = gzip_if_smaller(&state).expect("état répétitif compressé");
        assert!(packed.len() < state.len() / 5);
        assert_eq!(gunzip(&packed).unwrap(), state);
        // Données sans répétition (état déjà compressé) : envoyées telles quelles.
        let mut seed = 12345u32;
        let noise: Vec<u8> = (0..50_000).map(|_| {
            seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12_345);
            (seed >> 16) as u8
        }).collect();
        assert!(gzip_if_smaller(&noise).is_none());
    }
}
