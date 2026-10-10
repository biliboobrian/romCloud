// Téléchargements des ROMs et des BIOS : fichier « .part » et reprise (requête HTTP Range).
use crate::error::{AppError, Result};
use crate::library::{n, s};
use crate::{api, events, library, settings};
use futures_util::StreamExt;
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};

struct Download {
    cancelled: Arc<AtomicBool>,
    state: Value,
}

static ACTIVE: LazyLock<Mutex<HashMap<String, Download>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

fn key(game_id: &Value) -> String {
    match game_id {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

/// État des téléchargements : { [gameId]: état }.
pub fn states() -> Value {
    let active = ACTIVE.lock().unwrap();
    Value::Object(active.iter().map(|(id, d)| (id.clone(), d.state.clone())).collect::<Map<_, _>>())
}

fn notify(game_id: &Value, state: Option<Value>, event: Option<Value>) {
    if let Some(state) = &state {
        if let Some(d) = ACTIVE.lock().unwrap().get_mut(&key(game_id)) {
            d.state = state.clone();
        }
    }
    events::emit("downloads:update", json!({ "gameId": game_id, "state": state, "event": event }));
}

/**
 * Télécharge d'abord les `bios` indiqués (BIOS manquants du système), puis le jeu et ses parties
 * (disques, mises à jour, DLC) sauf si `includeRom` vaut false. La progression couvre l'ensemble.
 * Tâche de fond.
 */
pub fn start(system: Value, game: Value, options: Value) {
    let game_id = game.get("id").cloned().unwrap_or(Value::Null);
    let bios: Vec<Value> = options.get("bios").and_then(Value::as_array).cloned().unwrap_or_default();
    let include_rom = options.get("includeRom").and_then(Value::as_bool).unwrap_or(true);
    if !include_rom && bios.is_empty() {
        return;
    }
    let title = s(&game, "title").to_string();
    let total = bios.iter().map(|b| n(b, "size")).sum::<u64>() + if include_rom { library::total_size(&game) } else { 0 };
    let running = {
        let title = title.clone();
        move |bytes: u64| json!({ "status": "running", "bytes": bytes, "total": total, "title": title })
    };
    let cancelled = Arc::new(AtomicBool::new(false));
    {
        let mut active = ACTIVE.lock().unwrap();
        if active.get(&key(&game_id)).map(|d| d.state["status"] == "running").unwrap_or(false) {
            return;
        }
        active.insert(key(&game_id), Download { cancelled: cancelled.clone(), state: running(0) });
    }
    notify(&game_id, Some(running(0)), None);
    tauri::async_runtime::spawn(async move {
        let result: Result<()> = async {
            let server = settings::get_str("serverUrl");
            let mut done = 0u64;
            for b in &bios {
                let id = b.get("id").map(key).unwrap_or_default();
                fetch_to(&format!("{server}/api/bios/{id}/file"), &library::bios_file(b), n(b, "size"), &cancelled, |bytes| {
                    notify(&game_id, Some(running(done + bytes)), None)
                })
                .await?;
                done += n(b, "size");
            }
            if include_rom {
                // Le jeu puis ses parties (disques, mises à jour, DLC), rangés à côté de lui.
                let dir = library::system_dir(&system);
                for file in library::game_files(&game) {
                    let id = file.get("id").map(key).unwrap_or_default();
                    fetch_to(&format!("{server}/api/games/{id}/file"), &dir.join(s(&file, "fileName")), n(&file, "size"), &cancelled, |bytes| {
                        notify(&game_id, Some(running(done + bytes)), None)
                    })
                    .await?;
                    done += n(&file, "size");
                }
                if let Err(e) = api::remember_downloaded(&system, &game) {
                    eprintln!("{e}"); // listé hors ligne
                }
            }
            Ok(())
        }
        .await;
        if cancelled.load(Ordering::SeqCst) {
            ACTIVE.lock().unwrap().remove(&key(&game_id));
            notify(&game_id, None, None);
            return;
        }
        match result {
            Ok(()) => {
                ACTIVE.lock().unwrap().remove(&key(&game_id));
                notify(
                    &game_id,
                    None,
                    Some(json!({ "type": "completed", "systemId": s(&system, "id"), "gameId": game_id, "title": title, "romIncluded": include_rom })),
                );
            }
            Err(e) => {
                let error = json!({
                    "key": e.key.clone().unwrap_or_else(|| "errors.download".into()),
                    "vars": if e.key.is_some() { Value::Object(e.vars.clone()) } else { json!({ "detail": e.message }) },
                    "message": e.message,
                });
                let state = json!({ "status": "failed", "title": title, "error": error });
                if let Some(d) = ACTIVE.lock().unwrap().get_mut(&key(&game_id)) {
                    d.state = state.clone();
                }
                notify(&game_id, Some(state), Some(json!({ "type": "failed", "gameId": game_id, "title": title, "error": error })));
            }
        }
    });
}

pub fn cancel(game_id: &Value) {
    if let Some(d) = ACTIVE.lock().unwrap().remove(&key(game_id)) {
        d.cancelled.store(true, Ordering::SeqCst);
    }
    notify(game_id, None, None);
}

pub fn dismiss_error(game_id: &Value) {
    let removed = {
        let mut active = ACTIVE.lock().unwrap();
        let failed = active.get(&key(game_id)).map(|d| d.state["status"] == "failed").unwrap_or(false);
        failed && active.remove(&key(game_id)).is_some()
    };
    if removed {
        notify(game_id, None, None);
    }
}

/**
 * Crée le dossier de `target`. Un fichier qui porte le nom d'un de ses dossiers (ancien BIOS
 * « pcsx2/bios » envoyé sous forme de .zip, devenu un dossier) est d'abord supprimé.
 */
fn make_parent_dirs(target: &Path) -> Result<()> {
    let mut dir = target.parent().map(Path::to_path_buf).unwrap_or_default();
    while !dir.exists() {
        match dir.parent() {
            Some(parent) if parent != dir => dir = parent.to_path_buf(),
            _ => break,
        }
    }
    if dir.is_file() {
        std::fs::remove_file(&dir)?;
    }
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)?;
    }
    Ok(())
}

/// Télécharge `url` vers `target` (via « .part », avec reprise) ; `on_bytes` reçoit les octets reçus.
async fn fetch_to(url: &str, target: &Path, expected: u64, cancelled: &AtomicBool, on_bytes: impl Fn(u64)) -> Result<()> {
    make_parent_dirs(target)?;
    let part = target.with_file_name(format!("{}.part", target.file_name().unwrap_or_default().to_string_lossy()));
    let mut offset = std::fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
    if offset > expected {
        std::fs::remove_file(&part)?;
        offset = 0;
    }
    let mut request = api::client().get(url).headers(api::headers(&settings::get_str("apiKey"))).headers(crate::account::client_headers());
    if offset > 0 {
        request = request.header(reqwest::header::RANGE, format!("bytes={offset}-"));
    }
    let res = request.send().await.map_err(|e| AppError::new("errors.download", json!({ "detail": api::network_detail(&e) })))?;
    let status = res.status().as_u16();
    if status != 416 {
        if !res.status().is_success() {
            return Err(AppError::new(if status == 401 { "errors.apiKey" } else { "errors.http" }, json!({ "status": status })));
        }
        let append = status == 206 && offset > 0;
        let mut file = std::fs::OpenOptions::new().create(true).write(true).append(append).truncate(!append).open(&part)?;
        let mut bytes = if append { offset } else { 0 };
        let mut last = Instant::now() - Duration::from_secs(1);
        let mut stream = res.bytes_stream();
        while let Some(chunk) = stream.next().await {
            if cancelled.load(Ordering::SeqCst) {
                return Err(AppError::msg("cancelled"));
            }
            let chunk = chunk.map_err(|e| AppError::new("errors.download", json!({ "detail": api::network_detail(&e) })))?;
            file.write_all(&chunk)?;
            bytes += chunk.len() as u64;
            if last.elapsed() > Duration::from_millis(250) {
                last = Instant::now();
                on_bytes(bytes);
            }
        }
        file.flush()?;
    }
    let size = std::fs::metadata(&part)?.len();
    if size != expected {
        return Err(AppError::new("errors.wrongSize", json!({ "got": size, "expected": expected })));
    }
    let _ = std::fs::remove_file(target);
    std::fs::rename(&part, target)?;
    Ok(())
}
