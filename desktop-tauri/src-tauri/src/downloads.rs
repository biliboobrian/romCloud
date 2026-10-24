// Téléchargements des ROMs et des BIOS : fichier « .part » et reprise (requête HTTP Range).
use crate::error::{AppError, Result};
use crate::library::{n, s};
use crate::{api, connectivity, events, library, settings};
use futures_util::StreamExt;
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
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
            // Hors ligne : rien n'est demandé au serveur.
            connectivity::ensure_online()?;
            let server = settings::get_str("serverUrl");
            let mut done = 0u64;
            for b in &bios {
                let id = b.get("id").map(key).unwrap_or_default();
                let fetched = fetch_to(&format!("{server}/api/bios/{id}/file"), &library::bios_file(b), n(b, "size"), &cancelled, |bytes| {
                    notify(&game_id, Some(running(done + bytes)), None)
                })
                .await;
                // BIOS introuvable sur le serveur (404…) : signalé, mais le jeu est téléchargé quand même
                // (un BIOS facultatif ou absent ne doit pas empêcher de jouer). Annulation : arrêt.
                if let Err(e) = fetched {
                    if cancelled.load(Ordering::SeqCst) {
                        return Err(e);
                    }
                    crate::account::report_error("download:bios", &e.message, Some(format!("{} ({id})", s(b, "path"))));
                }
                done += n(b, "size");
            }
            if include_rom && library::is_folder(&game) {
                let before = done;
                fetch_folder(&server, &system, &game, &cancelled, |bytes, folder_total| {
                    notify(&game_id, Some(json!({ "status": "running", "bytes": before + bytes, "total": before + folder_total, "title": title })), None)
                })
                .await?;
                std::fs::write(library::folder_marker(&system, &game), n(&game, "size").to_string())?;
                if let Err(e) = api::remember_downloaded(&system, &game) {
                    eprintln!("{e}"); // listé hors ligne
                }
            } else if include_rom {
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

/// Fichiers d'un jeu PC téléchargés en même temps.
const FOLDER_PARALLEL: usize = 3;
/// Archives reçues en attente de décompression au plus (au-delà, les téléchargements attendent).
const UNZIP_QUEUE: usize = 1;

/// Fichier d'un jeu PC (`GET /api/games/{id}/files`) ; compressé par le serveur : `zip_size`, taille de son archive.
#[derive(Clone)]
struct FolderFile {
    path: String,
    size: u64,
    zip_size: Option<u64>,
}

impl FolderFile {
    fn from_json(v: &Value) -> Self {
        FolderFile { path: s(v, "path").to_string(), size: n(v, "size"), zip_size: v.get("zipSize").and_then(Value::as_u64) }
    }

    /// Octets à télécharger.
    fn download_size(&self) -> u64 {
        self.zip_size.unwrap_or(self.size)
    }
}

/**
 * Place nécessaire pour installer `todo` (fichiers absents du PC) : leur taille décompressée, plus
 * les archives présentes en même temps sur le disque (en téléchargement, en attente et en
 * décompression), comptées parmi les plus grosses.
 */
fn needed_bytes(todo: &[FolderFile]) -> u64 {
    let mut zips: Vec<u64> = todo.iter().filter_map(|f| f.zip_size).collect();
    zips.sort_unstable_by(|a, b| b.cmp(a));
    todo.iter().map(|f| f.size).sum::<u64>() + zips.iter().take(FOLDER_PARALLEL + UNZIP_QUEUE + 1).sum::<u64>()
}

/// Archive d'un fichier, à côté de lui (« X.zip »).
fn zip_path(target: &Path) -> PathBuf {
    target.with_file_name(format!("{}.zip", target.file_name().unwrap_or_default().to_string_lossy()))
}

/**
 * Décompresse `zip` (archive d'un seul fichier) vers `target` (via un fichier temporaire), vérifié par
 * sa taille (et son CRC32, par la bibliothèque zip) ; l'archive est ensuite supprimée, abîmée aussi
 * (téléchargée de nouveau au prochain essai).
 */
fn unzip_file(zip: &Path, target: &Path, size: u64, cancelled: &AtomicBool) -> Result<()> {
    let temp = target.with_file_name(format!("{}.unzip", target.file_name().unwrap_or_default().to_string_lossy()));
    let result = (|| -> Result<()> {
        let mut archive = zip::ZipArchive::new(std::fs::File::open(zip)?).map_err(|e| AppError::msg(format!("zip : {e}")))?;
        let mut entry = archive.by_index(0).map_err(|e| AppError::msg(format!("zip : {e}")))?;
        let mut out = std::io::BufWriter::with_capacity(1 << 18, std::fs::File::create(&temp)?);
        let mut buf = vec![0u8; 1 << 18];
        let mut written = 0u64;
        loop {
            if cancelled.load(Ordering::SeqCst) {
                return Err(AppError::msg("cancelled"));
            }
            let read = entry.read(&mut buf)?;
            if read == 0 {
                break;
            }
            out.write_all(&buf[..read])?;
            written += read as u64;
        }
        out.flush()?;
        drop(out);
        if written != size {
            return Err(AppError::new("errors.wrongSize", json!({ "got": written, "expected": size })));
        }
        let _ = std::fs::remove_file(target);
        std::fs::rename(&temp, target)?;
        Ok(())
    })();
    let _ = std::fs::remove_file(&temp);
    if result.is_ok() || !cancelled.load(Ordering::SeqCst) {
        let _ = std::fs::remove_file(zip);
    }
    result
}

/**
 * Jeu PC (jeu en dossier) : ses fichiers, `FOLDER_PARALLEL` à la fois et les plus lourds d'abord,
 * ceux déjà complets gardés (reprise) ; jeu compressé par le serveur : archives décompressées une à
 * une dès leur réception, pendant que les suivantes se téléchargent. Refusé d'avance s'il n'y a pas
 * assez de place. `progress` reçoit les octets reçus et le total à télécharger.
 */
async fn fetch_folder(server: &str, system: &Value, game: &Value, cancelled: &Arc<AtomicBool>, progress: impl Fn(u64, u64)) -> Result<()> {
    let folder = library::file_for(system, game);
    let id = game.get("id").map(key).unwrap_or_default();
    let listing: Value = serde_json::from_str(&api::request(&format!("/api/games/{id}/files"), None).await?)?;
    let files: Vec<FolderFile> = listing["files"].as_array().map(|a| a.iter().map(FolderFile::from_json).collect()).unwrap_or_default();
    let target_of = |f: &FolderFile| f.path.split('/').fold(folder.clone(), |path, part| path.join(part));
    let complete = |f: &FolderFile| std::fs::metadata(target_of(f)).map(|m| m.is_file() && m.len() == f.size).unwrap_or(false);
    let mut todo: Vec<FolderFile> = files.iter().filter(|f| !complete(f)).cloned().collect();
    todo.sort_by(|a, b| b.download_size().cmp(&a.download_size()));
    let needed = needed_bytes(&todo);
    if let Some((free, _)) = crate::paths::disk_space(&folder) {
        if free < needed {
            return Err(AppError::new("errors.notEnoughSpace", json!({ "neededBytes": needed, "freeBytes": free })));
        }
    }
    let total: u64 = files.iter().map(FolderFile::download_size).sum();
    let received = AtomicU64::new(total - todo.iter().map(FolderFile::download_size).sum::<u64>());
    let in_flight: Mutex<HashMap<String, u64>> = Mutex::new(HashMap::new());
    let report = || progress(received.load(Ordering::SeqCst) + in_flight.lock().unwrap().values().sum::<u64>(), total);
    report();

    let (tx, mut rx) = tokio::sync::mpsc::channel::<(FolderFile, PathBuf)>(UNZIP_QUEUE);
    let downloads = async {
        let tx = tx;
        let (received, in_flight, report, target_of, id) = (&received, &in_flight, &report, &target_of, &id);
        let mut running = futures_util::stream::iter(todo.into_iter().map(|file| {
            let tx = tx.clone();
            async move {
                let target = target_of(&file);
                let zip = zip_path(&target);
                let packed = file.zip_size.is_some();
                // Archive déjà reçue (téléchargement interrompu pendant la décompression) : décompressée seulement.
                let received_zip = packed && std::fs::metadata(&zip).map(|m| m.len() == file.download_size()).unwrap_or(false);
                if !received_zip {
                    let url = format!("{server}/api/games/{id}/file?path={}{}", urlencoding::encode(&file.path), if packed { "&packed=1" } else { "" });
                    fetch_to(&url, if packed { &zip } else { &target }, file.download_size(), cancelled, |bytes| {
                        in_flight.lock().unwrap().insert(file.path.clone(), bytes);
                        report();
                    })
                    .await?;
                }
                in_flight.lock().unwrap().remove(&file.path);
                received.fetch_add(file.download_size(), Ordering::SeqCst);
                report();
                if packed {
                    tx.send((file, target)).await.map_err(|_| AppError::msg("cancelled"))?;
                }
                Ok::<(), AppError>(())
            }
        }))
        .buffer_unordered(FOLDER_PARALLEL);
        while let Some(result) = running.next().await {
            result?;
        }
        Ok::<(), AppError>(())
    };
    let unzips = async {
        while let Some((file, target)) = rx.recv().await {
            let cancelled = cancelled.clone();
            tokio::task::spawn_blocking(move || unzip_file(&zip_path(&target), &target, file.size, &cancelled))
                .await
                .map_err(|e| AppError::msg(e.to_string()))??;
        }
        Ok::<(), AppError>(())
    };
    tokio::try_join!(downloads, unzips)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(path: &str, size: u64, zip_size: Option<u64>) -> FolderFile {
        FolderFile { path: path.into(), size, zip_size }
    }

    #[test]
    fn place_pour_les_fichiers_decompresses_et_les_archives_sur_le_disque() {
        let todo = [
            file("a.dat", 100, None),
            file("big.pak", 10_000, Some(6_000)),
            file("mid.pak", 5_000, Some(4_000)),
            file("x1", 50, Some(10)),
            file("x2", 50, Some(10)),
            file("x3", 50, Some(10)),
            file("x4", 50, Some(10)),
        ];
        // 15 300 décompressés + les 5 plus grosses archives (3 en téléchargement, 1 en attente, 1 en décompression).
        assert_eq!(needed_bytes(&todo), 15_300 + 6_000 + 4_000 + 30);
        assert_eq!(needed_bytes(&todo[..1]), 100);
    }

    #[test]
    fn archive_decompressee_puis_supprimee() {
        let dir = std::env::temp_dir().join(format!("romcloud-unzip-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..300_000u32).map(|i| (i % 97) as u8).collect();
        let target = dir.join("level1.pak");
        let zip = zip_path(&target);
        assert_eq!(zip.file_name().unwrap(), "level1.pak.zip");
        {
            let mut writer = zip::ZipWriter::new(std::fs::File::create(&zip).unwrap());
            writer.start_file("level1.pak", zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated)).unwrap();
            writer.write_all(&data).unwrap();
            writer.finish().unwrap();
        }
        let cancelled = AtomicBool::new(false);
        unzip_file(&zip, &target, data.len() as u64, &cancelled).unwrap();
        assert_eq!(std::fs::read(&target).unwrap(), data);
        assert!(!zip.exists());
        // Taille différente : erreur, archive supprimée (téléchargée de nouveau).
        let bad = dir.join("bad.bin");
        {
            let mut writer = zip::ZipWriter::new(std::fs::File::create(zip_path(&bad)).unwrap());
            writer.start_file("bad.bin", zip::write::SimpleFileOptions::default()).unwrap();
            writer.write_all(b"abc").unwrap();
            writer.finish().unwrap();
        }
        assert!(unzip_file(&zip_path(&bad), &bad, 99, &cancelled).is_err());
        assert!(!bad.exists() && !zip_path(&bad).exists());
        let _ = std::fs::remove_dir_all(&dir);
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

/// Téléchargement interrompu par le réseau (délai dépassé, serveur injoignable) : hors ligne.
fn network_failure(e: reqwest::Error) -> AppError {
    connectivity::mark_offline();
    AppError::new("errors.download", json!({ "detail": api::network_detail(&e) }))
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
    let res = request.send().await.map_err(network_failure)?;
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
            let chunk = chunk.map_err(network_failure)?;
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
