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
    speed: Speed,
}

/**
 * Vitesse d'un téléchargement (octets par seconde) : mesurée sur des fenêtres d'au moins une
 * seconde, lissée (une requête lente ou un fichier tout juste reçu ne la font pas bondir).
 */
#[derive(Default)]
struct Speed {
    since: Option<(Instant, u64)>,
    bytes_per_second: f64,
}

/// Jeu téléchargé : état « finishing » gardé ce temps (voir `start`).
const FINISHING_SHOWN: Duration = Duration::from_secs(15);

/// Fenêtre de mesure de la vitesse.
const SPEED_WINDOW: Duration = Duration::from_secs(1);

impl Speed {
    /// Octets reçus depuis le début, `bytes`, à l'instant `now` ; renvoie la vitesse lissée.
    fn update(&mut self, bytes: u64, now: Instant) -> u64 {
        match self.since {
            Some((at, from)) if bytes >= from => {
                let elapsed = now.saturating_duration_since(at);
                if elapsed >= SPEED_WINDOW {
                    let measured = (bytes - from) as f64 / elapsed.as_secs_f64();
                    self.bytes_per_second = if self.bytes_per_second == 0.0 { measured } else { 0.5 * measured + 0.5 * self.bytes_per_second };
                    self.since = Some((now, bytes));
                }
            }
            // Premier relevé, ou compteur revenu en arrière : nouvelle fenêtre.
            _ => self.since = Some((now, bytes)),
        }
        self.bytes_per_second.round() as u64
    }
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

/// Publie l'état du téléchargement ; en cours : avec sa vitesse (`speed`, octets par seconde).
fn notify(game_id: &Value, mut state: Option<Value>, event: Option<Value>) {
    if let Some(state) = &mut state {
        if let Some(d) = ACTIVE.lock().unwrap().get_mut(&key(game_id)) {
            if state["status"] == "running" {
                state["speed"] = json!(d.speed.update(n(state, "bytes"), Instant::now()));
            }
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
    // Jeu en plusieurs parties (disques, mises à jour, DLC) : fichiers restant à recevoir (`filesLeft`).
    let parts = if include_rom && !library::is_folder(&game) { library::game_files(&game).len() } else { 0 };
    let running = {
        let title = title.clone();
        move |bytes: u64, files_left: usize| {
            let mut state = json!({ "status": "running", "bytes": bytes, "total": total, "title": title });
            if parts > 1 {
                state["files"] = json!(parts);
                state["filesLeft"] = json!(files_left);
            }
            state
        }
    };
    let cancelled = Arc::new(AtomicBool::new(false));
    {
        let mut active = ACTIVE.lock().unwrap();
        if active.get(&key(&game_id)).map(|d| d.state["status"] == "running").unwrap_or(false) {
            return;
        }
        active.insert(key(&game_id), Download { cancelled: cancelled.clone(), state: running(0, parts), speed: Speed::default() });
    }
    notify(&game_id, Some(running(0, parts)), None);
    tauri::async_runtime::spawn(async move {
        let result: Result<()> = async {
            // Hors ligne : rien n'est demandé au serveur.
            connectivity::ensure_online()?;
            let server = settings::get_str("serverUrl");
            let mut done = 0u64;
            for b in &bios {
                let id = b.get("id").map(key).unwrap_or_default();
                let fetched = fetch_to(&format!("{server}/api/bios/{id}/file"), &library::bios_file(&system, b), n(b, "size"), &cancelled, |bytes| {
                    notify(&game_id, Some(running(done + bytes, parts)), None)
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
                fetch_folder(&server, &system, &game, &cancelled, |p| {
                    let state = json!({
                        "status": "running",
                        "bytes": before + p.bytes,
                        "total": before + p.total,
                        "unpackBytes": p.unpack_bytes,
                        "unpackTotal": p.unpack_total,
                        "files": p.files,
                        "filesLeft": p.files_left,
                        "title": title,
                    });
                    notify(&game_id, Some(state), None)
                })
                .await?;
                if let Err(e) = api::remember_downloaded(&system, &game) {
                    eprintln!("{e}"); // listé hors ligne
                }
            } else if include_rom {
                // Le jeu puis ses parties (disques, mises à jour, DLC), rangés à côté de lui.
                let dir = library::system_dir(&system);
                for (index, file) in library::game_files(&game).into_iter().enumerate() {
                    let id = file.get("id").map(key).unwrap_or_default();
                    fetch_to(&format!("{server}/api/games/{id}/file"), &dir.join(s(&file, "fileName")), n(&file, "size"), &cancelled, |bytes| {
                        notify(&game_id, Some(running(done + bytes, parts - index)), None)
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
                // « Veuillez patienter » le temps que l'interface relise les jeux présents (sinon
                // « Télécharger » réapparaît un instant) ; retiré ensuite.
                let finishing = json!({ "status": "finishing", "title": title });
                notify(
                    &game_id,
                    Some(finishing),
                    Some(json!({ "type": "completed", "systemId": s(&system, "id"), "gameId": game_id, "title": title, "romIncluded": include_rom })),
                );
                tokio::time::sleep(FINISHING_SHOWN).await;
                let done = {
                    let mut active = ACTIVE.lock().unwrap();
                    let finishing = active.get(&key(&game_id)).is_some_and(|d| d.state["status"] == "finishing");
                    finishing && active.remove(&key(&game_id)).is_some()
                };
                if done {
                    notify(&game_id, None, None);
                }
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

/// Jeu supprimé du PC tout juste après son téléchargement : plus de « Veuillez patienter ».
pub fn forget_finished(game_id: &Value) {
    let removed = {
        let mut active = ACTIVE.lock().unwrap();
        let finishing = active.get(&key(game_id)).is_some_and(|d| d.state["status"] == "finishing");
        finishing && active.remove(&key(game_id)).is_some()
    };
    if removed {
        notify(game_id, None, None);
    }
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

/// Marge gardée libre sur le disque pendant l'installation d'un jeu PC.
const SPACE_MARGIN: u64 = 64 * 1024 * 1024;

/// Jeu PC : fichiers plus petits (à télécharger) reçus plusieurs à la fois, `SMALL_FILES_AT_ONCE` au plus.
const SMALL_FILE: u64 = 1024 * 1024;
const SMALL_FILES_AT_ONCE: usize = 8;

/**
 * Fichier d'un jeu PC (`GET /api/games/{id}/files`) ; compressé par le serveur : `format` de son
 * archive (« zst », ou « xz » et « zip » pour les compressions d'avant le zstd) et sa taille
 * `archive_size` ; regroupé avec d'autres dans une archive zstd : `bundle` (son archive) et
 * `offset` (sa place dans le contenu décompressé de l'archive, fichiers mis bout à bout).
 */
#[derive(Clone)]
struct FolderFile {
    path: String,
    size: u64,
    archive_size: Option<u64>,
    format: &'static str,
    /// Date du fichier sur le serveur (ms) : avec la taille, repère un fichier changé par une nouvelle version.
    mtime: Option<u64>,
    bundle: Option<String>,
    offset: u64,
    crc32: Option<String>,
}

/// Dossier des archives regroupant plusieurs fichiers, dans le dossier du jeu (comme sur le serveur).
const BUNDLES_DIR: &str = ".romcloud-bundles";

impl FolderFile {
    fn from_json(v: &Value) -> Self {
        let archive_size = v.get("packedSize").or_else(|| v.get("zipSize")).and_then(Value::as_u64);
        let format = match v.get("format").and_then(Value::as_str) {
            Some("zst") => "zst",
            Some("xz") => "xz",
            _ => "zip",
        };
        FolderFile {
            path: s(v, "path").to_string(),
            size: n(v, "size"),
            archive_size,
            format,
            mtime: v.get("mtime").and_then(Value::as_u64),
            bundle: v.get("bundle").and_then(Value::as_str).map(str::to_string),
            offset: n(v, "offset"),
            crc32: v.get("crc32").and_then(Value::as_str).map(str::to_string),
        }
    }

    fn packed(&self) -> bool {
        self.archive_size.is_some()
    }

    /// Octets à télécharger.
    fn download_size(&self) -> u64 {
        self.archive_size.unwrap_or(self.size)
    }
}

/**
 * Téléchargements pour recevoir `todo` (fichiers absents du PC, parmi tous ceux du jeu `files`),
 * les plus lourds d'abord : chaque fichier, ou l'archive regroupée de ceux qui en ont une
 * (`bundles` de la liste du serveur), téléchargée une fois pour tous ses fichiers (tous réécrits) et
 * vue comme un fichier « .romcloud-bundles/<id> » au format zst (son archive :
 * « .romcloud-bundles/<id>.zst ») ; renvoie aussi les fichiers de chaque archive regroupée.
 */
fn downloads(files: &[FolderFile], todo: Vec<FolderFile>, bundles: &Value) -> Result<(Vec<FolderFile>, HashMap<String, Vec<FolderFile>>)> {
    let mut members: HashMap<String, Vec<FolderFile>> = HashMap::new();
    for f in files {
        if let Some(id) = &f.bundle {
            members.entry(id.clone()).or_default().push(f.clone());
        }
    }
    let mut needed: Vec<String> = todo.iter().filter_map(|f| f.bundle.clone()).collect();
    needed.sort();
    needed.dedup();
    let mut units: Vec<FolderFile> = todo.into_iter().filter(|f| f.bundle.is_none()).collect();
    for id in needed {
        let packed = bundles
            .get(&id)
            .and_then(|b| b.get("packedSize"))
            .and_then(Value::as_u64)
            .ok_or_else(|| AppError::msg(format!("zstd : archive regroupée {id} absente de la liste")))?;
        units.push(FolderFile {
            path: format!("{BUNDLES_DIR}/{id}"),
            size: members[&id].iter().map(|f| f.size).sum(),
            archive_size: Some(packed),
            format: "zst",
            mtime: None,
            bundle: Some(id),
            offset: 0,
            crc32: None,
        });
    }
    units.sort_by(|a, b| b.download_size().cmp(&a.download_size()));
    Ok((units, members))
}

/**
 * Place nécessaire pour installer `todo` (fichiers absents du PC) : leur taille décompressée et la
 * plus grosse archive (téléchargée pendant qu'une autre se décompresse ; si la décompression prend
 * du retard, les téléchargements attendent la place libérée, voir `must_wait`).
 */
fn needed_bytes(todo: &[FolderFile]) -> u64 {
    todo.iter().map(|f| f.size).sum::<u64>() + todo.iter().filter_map(|f| f.archive_size).max().unwrap_or(0) + SPACE_MARGIN
}

/**
 * Téléchargement de `next` à retarder : la place libre ne suffit pas pour lui (son archive, ou le
 * fichier lui-même) et pour les fichiers pas encore écrits (`pending` : en attente de décompression,
 * en décompression, à télécharger).
 */
fn must_wait(next: &FolderFile, pending: u64, free: u64) -> bool {
    free < pending + next.archive_size.unwrap_or(0) + SPACE_MARGIN
}

/// Archive d'un fichier, à côté de lui (« X.zst », « X.xz » ou « X.zip »).
fn archive_path(target: &Path, format: &str) -> PathBuf {
    target.with_file_name(format!("{}.{format}", target.file_name().unwrap_or_default().to_string_lossy()))
}

/**
 * Fil d'exécution courant en arrière-plan (Windows : priorité du processeur, du disque et de la
 * mémoire très basse, THREAD_MODE_BACKGROUND) ou de nouveau normal ; remis normal à la fin.
 */
struct Background(bool);

impl Background {
    fn set(&mut self, on: bool) {
        if self.0 == on {
            return;
        }
        self.0 = on;
        #[cfg(windows)]
        {
            #[link(name = "kernel32")]
            extern "system" {
                fn GetCurrentThread() -> isize;
                fn SetThreadPriority(thread: isize, priority: i32) -> i32;
            }
            const THREAD_MODE_BACKGROUND_BEGIN: i32 = 0x0001_0000;
            const THREAD_MODE_BACKGROUND_END: i32 = 0x0002_0000;
            // SAFETY : pseudo-handle du fil courant, toujours valide.
            unsafe { SetThreadPriority(GetCurrentThread(), if on { THREAD_MODE_BACKGROUND_BEGIN } else { THREAD_MODE_BACKGROUND_END }) };
        }
    }
}

impl Drop for Background {
    fn drop(&mut self) {
        self.set(false);
    }
}

/**
 * Décompresse `archive` (« zst » ; « xz » : blocs décompressés en parallèle ; « zip » : un seul
 * fichier) vers `target` (via un fichier temporaire), vérifié par sa taille (et l'empreinte de
 * l'archive : XXH64 du zstd, CRC64 du xz, CRC32 du zip) ; l'archive est ensuite supprimée, abîmée aussi (téléchargée de nouveau au
 * prochain essai). `on_bytes` reçoit les octets décompressés depuis le début. Tant que `downloading`
 * (téléchargement du jeu en cours) : priorité très basse et un seul fil, pour ne pas ralentir le
 * téléchargement ; ensuite, priorité normale.
 */
fn unpack_file(archive: &Path, format: &str, target: &Path, size: u64, cancelled: &AtomicBool, downloading: &AtomicBool, on_bytes: impl Fn(u64)) -> Result<()> {
    let temp = target.with_file_name(format!("{}.unpack", target.file_name().unwrap_or_default().to_string_lossy()));
    let mut background = Background(false);
    background.set(downloading.load(Ordering::SeqCst));
    let result = (|| -> Result<()> {
        let file = std::fs::File::open(archive)?;
        let mut out = std::io::BufWriter::with_capacity(1 << 20, std::fs::File::create(&temp)?);
        let mut buf = vec![0u8; 1 << 20];
        let mut written = 0u64;
        let mut copy = |reader: &mut dyn Read| -> Result<()> {
            let mut last = Instant::now();
            loop {
                if cancelled.load(Ordering::SeqCst) {
                    return Err(AppError::msg("cancelled"));
                }
                // Téléchargement terminé pendant la décompression : priorité normale.
                background.set(downloading.load(Ordering::SeqCst));
                let read = reader.read(&mut buf)?;
                if read == 0 {
                    return Ok(());
                }
                out.write_all(&buf[..read])?;
                written += read as u64;
                if last.elapsed() > Duration::from_millis(200) {
                    last = Instant::now();
                    on_bytes(written);
                }
            }
        };
        if format == "zst" {
            // Fenêtre des archives du serveur : 128 Mo (zstd -19 --long=27).
            let reader = std::io::BufReader::with_capacity(1 << 20, file);
            let mut decoder = ruzstd::decoding::StreamingDecoder::new_with_max_window_size(reader, 1 << 27)
                .map_err(|e| AppError::msg(format!("zstd : {e}")))?;
            copy(&mut decoder)?;
            let frame = &decoder.decoder;
            if let (Some(expected), Some(got)) = (frame.get_checksum_from_data(), frame.get_calculated_checksum()) {
                if expected != got {
                    return Err(AppError::msg("zstd : somme de contrôle incorrecte"));
                }
            }
        } else if format == "xz" && downloading.load(Ordering::SeqCst) {
            // Pendant le téléchargement : un seul fil, en arrière-plan.
            let mut reader = lzma_rust2::XzReader::new(std::io::BufReader::with_capacity(1 << 20, file), false);
            copy(&mut reader)?;
        } else if format == "xz" {
            let workers = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2).min(8) as u32;
            let mut reader = lzma_rust2::XzReaderMt::new(file, false, workers).map_err(|e| AppError::msg(format!("xz : {e}")))?;
            copy(&mut reader)?;
        } else {
            let mut zip = zip::ZipArchive::new(file).map_err(|e| AppError::msg(format!("zip : {e}")))?;
            let mut entry = zip.by_index(0).map_err(|e| AppError::msg(format!("zip : {e}")))?;
            copy(&mut entry)?;
        }
        out.flush()?;
        drop(out);
        on_bytes(written);
        if written != size {
            return Err(AppError::new("errors.wrongSize", json!({ "got": written, "expected": size })));
        }
        let _ = std::fs::remove_file(target);
        std::fs::rename(&temp, target)?;
        Ok(())
    })();
    let _ = std::fs::remove_file(&temp);
    if result.is_ok() || !cancelled.load(Ordering::SeqCst) {
        let _ = std::fs::remove_file(archive);
    }
    result
}

/**
 * Décompresse l'archive regroupée `archive` (zstd, fichiers mis bout à bout) : chaque fichier de
 * `members` (fichier du jeu et sa place sur le PC) écrit à sa place (via un fichier temporaire),
 * vérifié par sa taille et son CRC32 (l'archive entière aussi, par son empreinte zstd) ; l'archive
 * est ensuite supprimée (abîmée aussi). `on_bytes` reçoit les octets décompressés depuis le début ;
 * priorité comme pour `unpack_file`.
 */
fn unpack_bundle(archive: &Path, members: &[(FolderFile, PathBuf)], cancelled: &AtomicBool, downloading: &AtomicBool, on_bytes: impl Fn(u64)) -> Result<()> {
    let mut background = Background(false);
    // Par place ; fichier vide avant celui qui commence au même endroit.
    let mut sorted: Vec<&(FolderFile, PathBuf)> = members.iter().collect();
    sorted.sort_by_key(|(f, _)| (f.offset, f.size));
    let mut temp_file: Option<PathBuf> = None;
    let result = (|| -> Result<()> {
        let reader = std::io::BufReader::with_capacity(1 << 20, std::fs::File::open(archive)?);
        // Fenêtre des archives du serveur : 128 Mo (zstd -19 --long=27).
        let mut decoder =
            ruzstd::decoding::StreamingDecoder::new_with_max_window_size(reader, 1 << 27).map_err(|e| AppError::msg(format!("zstd : {e}")))?;
        let mut buf = vec![0u8; 1 << 20];
        let mut position = 0u64;
        let mut last = Instant::now();
        // Octets à passer ou à copier (`out` : fichier et son CRC32 ; aucun : passés).
        let mut copy = |count: u64, mut out: Option<(&mut dyn Write, &mut crc32fast::Hasher)>, position: &mut u64| -> Result<()> {
            let mut left = count;
            while left > 0 {
                if cancelled.load(Ordering::SeqCst) {
                    return Err(AppError::msg("cancelled"));
                }
                background.set(downloading.load(Ordering::SeqCst));
                let read = decoder.read(&mut buf[..left.min(1 << 20) as usize])?;
                if read == 0 {
                    return Err(AppError::msg("zstd : archive regroupée tronquée"));
                }
                if let Some((file, crc)) = out.as_mut() {
                    file.write_all(&buf[..read])?;
                    crc.update(&buf[..read]);
                }
                left -= read as u64;
                *position += read as u64;
                if last.elapsed() > Duration::from_millis(200) {
                    last = Instant::now();
                    on_bytes(*position);
                }
            }
            Ok(())
        };
        for (file, target) in &sorted {
            if file.offset < position {
                return Err(AppError::msg("zstd : fichiers mal placés dans l'archive regroupée"));
            }
            copy(file.offset - position, None, &mut position)?;
            make_parent_dirs(target)?;
            let temp = target.with_file_name(format!("{}.unpack", target.file_name().unwrap_or_default().to_string_lossy()));
            temp_file = Some(temp.clone());
            let mut out = std::io::BufWriter::with_capacity(1 << 20, std::fs::File::create(&temp)?);
            let mut crc = crc32fast::Hasher::new();
            copy(file.size, Some((&mut out, &mut crc)), &mut position)?;
            out.flush()?;
            drop(out);
            if let Some(expected) = &file.crc32 {
                if !format!("{:08x}", crc.finalize()).eq_ignore_ascii_case(expected) {
                    return Err(AppError::msg(format!("zstd : contenu incorrect ({})", file.path)));
                }
            }
            let _ = std::fs::remove_file(target);
            std::fs::rename(&temp, target)?;
            temp_file = None;
        }
        drop(copy);
        // Reste de l'archive lu : empreinte zstd vérifiée.
        while decoder.read(&mut buf)? > 0 {}
        let frame = &decoder.decoder;
        if let (Some(expected), Some(got)) = (frame.get_checksum_from_data(), frame.get_calculated_checksum()) {
            if expected != got {
                return Err(AppError::msg("zstd : somme de contrôle incorrecte"));
            }
        }
        on_bytes(position);
        Ok(())
    })();
    if let Some(temp) = temp_file {
        let _ = std::fs::remove_file(temp);
    }
    if result.is_ok() || !cancelled.load(Ordering::SeqCst) {
        let _ = std::fs::remove_file(archive);
    }
    result
}

/**
 * Progression d'un jeu PC : octets reçus, total à télécharger, octets décompressés, total à
 * décompresser ; fichiers à recevoir (absents du PC) et ceux qui restent.
 */
#[derive(Clone, Copy, Default)]
struct FolderProgress {
    bytes: u64,
    total: u64,
    unpack_bytes: u64,
    unpack_total: u64,
    files: usize,
    files_left: usize,
}

/**
 * Jeu PC (jeu en dossier) : ses fichiers un par un, les plus lourds d'abord (les petits plusieurs à
 * la fois), ceux déjà complets gardés (reprise) ; jeu compressé : chaque archive reçue part dans une file de décompression (une à
 * la fois) qui avance pendant les téléchargements suivants ; le jeu est jouable une fois la file
 * vide. Refusé d'avance s'il n'y a pas assez de place ; si la décompression prend du retard au point
 * de manquer de place, le téléchargement suivant l'attend.
 */
async fn fetch_folder(server: &str, system: &Value, game: &Value, cancelled: &Arc<AtomicBool>, progress: impl Fn(FolderProgress)) -> Result<()> {
    let folder = library::file_for(system, game);
    let id = game.get("id").map(key).unwrap_or_default();
    // ?zst=1 : archives zstd lues par l'application (sinon listées en clair par le serveur) ;
    // ?bundles=1 : archives regroupant plusieurs fichiers aussi.
    let listing: Value = serde_json::from_str(&api::request(&format!("/api/games/{id}/files?zst=1&bundles=1"), None).await?)?;
    let files: Vec<FolderFile> = listing["files"].as_array().map(|a| a.iter().map(FolderFile::from_json).collect()).unwrap_or_default();
    let target_of = |f: &FolderFile| f.path.split('/').fold(folder.clone(), |path, part| path.join(part));
    // Mise à jour : fichiers reçus avec la version déjà téléchargée (chemin -> [taille, date sur le
    // serveur]) ; ceux qui ont changé sont téléchargés de nouveau.
    let previous = if library::read_folder_marker(system, game).is_some() { library::read_folder_files(system, game) } else { Map::new() };
    let complete = |f: &FolderFile| {
        std::fs::metadata(target_of(f)).map(|m| m.is_file() && m.len() == f.size).unwrap_or(false)
            && match (f.mtime, previous.get(&f.path).and_then(|e| e.get(1)).and_then(Value::as_u64)) {
                (Some(remote), Some(local)) => remote == local,
                _ => true,
            }
    };
    // Fichiers regroupés dans une archive : l'archive, téléchargée une fois pour tous ses fichiers.
    let (todo, bundled) = downloads(&files, files.iter().filter(|f| !complete(f)).cloned().collect(), &listing["bundles"])?;
    let free = || crate::paths::disk_space(&folder).map(|(free, _)| free);
    let needed = needed_bytes(&todo);
    if let Some(free) = free() {
        if free < needed {
            return Err(AppError::new("errors.notEnoughSpace", json!({ "neededBytes": needed, "freeBytes": free })));
        }
    }

    let total: u64 = files.iter().map(FolderFile::download_size).sum();
    let received = AtomicU64::new(total - todo.iter().map(FolderFile::download_size).sum::<u64>());
    let current = AtomicU64::new(0);
    let unpack_total: u64 = todo.iter().filter(|f| f.packed()).map(|f| f.size).sum();
    let unpacked = Arc::new(AtomicU64::new(0));
    let unpacking = Arc::new(AtomicU64::new(0));
    // Octets encore à écrire sur le disque (fichiers en clair) ; archives en attente ou en décompression.
    let pending = Arc::new(AtomicU64::new(todo.iter().map(|f| f.size).sum()));
    let queued = Arc::new(AtomicU64::new(0));
    let files_count = todo.len();
    let files_left = AtomicU64::new(files_count as u64);
    let report = || {
        progress(FolderProgress {
            bytes: received.load(Ordering::SeqCst) + current.load(Ordering::SeqCst),
            total,
            unpack_bytes: unpacked.load(Ordering::SeqCst) + unpacking.load(Ordering::SeqCst),
            unpack_total,
            files: files_count,
            files_left: files_left.load(Ordering::SeqCst) as usize,
        })
    };
    report();

    // Téléchargement du jeu en cours : décompression en arrière-plan (voir unpack_file).
    let downloading = Arc::new(AtomicBool::new(true));
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(FolderFile, PathBuf)>();
    let downloads = async {
        let tx = tx;
        // Un fichier : attente de la place, téléchargement, archive confiée à la décompression ;
        // `track` : progression octet par octet (gros fichiers, reçus un à la fois).
        let fetch_one = |file: FolderFile, track: bool| {
            let (tx, id, pending, unpacking, queued, received, current, files_left, report, free, target_of) =
                (&tx, &id, &pending, &unpacking, &queued, &received, &current, &files_left, &report, &free, &target_of);
            async move {
                // Décompression en retard et disque presque plein : attente de la place libérée.
                while let Some(free) = free() {
                    let waiting = pending.load(Ordering::SeqCst).saturating_sub(unpacking.load(Ordering::SeqCst));
                    if !must_wait(&file, waiting, free) {
                        break;
                    }
                    if queued.load(Ordering::SeqCst) == 0 {
                        return Err(AppError::new("errors.notEnoughSpace", json!({ "neededBytes": waiting + file.archive_size.unwrap_or(0), "freeBytes": free })));
                    }
                    if cancelled.load(Ordering::SeqCst) {
                        return Err(AppError::msg("cancelled"));
                    }
                    tokio::time::sleep(Duration::from_millis(500)).await;
                }
                let target = target_of(&file);
                let archive = archive_path(&target, file.format);
                // Archive déjà reçue (téléchargement interrompu pendant la décompression) : décompressée seulement.
                let received_archive = file.packed() && std::fs::metadata(&archive).map(|m| m.len() == file.download_size()).unwrap_or(false);
                if !received_archive {
                    let url = match &file.bundle {
                        Some(bundle) => format!("{server}/api/games/{id}/bundle?id={}", urlencoding::encode(bundle)),
                        None => format!("{server}/api/games/{id}/file?path={}{}", urlencoding::encode(&file.path), if file.packed() { "&packed=1&zst=1" } else { "" }),
                    };
                    fetch_to(&url, if file.packed() { &archive } else { &target }, file.download_size(), cancelled, |bytes| {
                        if track {
                            current.store(bytes, Ordering::SeqCst);
                            report();
                        }
                    })
                    .await?;
                }
                if track {
                    current.store(0, Ordering::SeqCst);
                }
                received.fetch_add(file.download_size(), Ordering::SeqCst);
                files_left.fetch_sub(1, Ordering::SeqCst);
                if file.packed() {
                    queued.fetch_add(1, Ordering::SeqCst);
                    tx.send((file, target)).map_err(|_| AppError::msg("cancelled"))?;
                } else {
                    pending.fetch_sub(file.size, Ordering::SeqCst);
                }
                report();
                Ok::<(), AppError>(())
            }
        };
        // Gros fichiers un à la fois (les plus lourds d'abord) ; petits fichiers (un jeu en compte
        // parfois des dizaines de milliers) plusieurs à la fois : sinon, une requête après l'autre,
        // l'attente du serveur l'emporte sur le transfert.
        let (big, small): (Vec<FolderFile>, Vec<FolderFile>) = todo.into_iter().partition(|f| f.download_size() >= SMALL_FILE);
        for file in big {
            fetch_one(file, true).await?;
        }
        let mut small = futures_util::stream::iter(small).map(|file| fetch_one(file, false)).buffer_unordered(SMALL_FILES_AT_ONCE);
        while let Some(result) = small.next().await {
            result?;
        }
        // Tout est reçu : la décompression repasse en priorité normale.
        downloading.store(false, Ordering::SeqCst);
        Ok::<(), AppError>(())
    };
    let unpacks = async {
        while let Some((file, target)) = rx.recv().await {
            let (cancelled, downloading, unpacking_bytes) = (cancelled.clone(), downloading.clone(), unpacking.clone());
            let size = file.size;
            // Archive regroupée : ses fichiers et leur place sur le PC.
            let members: Option<Vec<(FolderFile, PathBuf)>> = file.bundle.as_ref().map(|b| bundled[b].iter().map(|f| (f.clone(), target_of(f))).collect());
            tokio::task::spawn_blocking(move || {
                let archive = archive_path(&target, file.format);
                let on_bytes = |bytes| unpacking_bytes.store(bytes, Ordering::SeqCst);
                match members {
                    Some(members) => unpack_bundle(&archive, &members, &cancelled, &downloading, on_bytes),
                    None => unpack_file(&archive, file.format, &target, file.size, &cancelled, &downloading, on_bytes),
                }
            })
            .await
            .map_err(|e| AppError::msg(e.to_string()))??;
            unpacking.store(0, Ordering::SeqCst);
            unpacked.fetch_add(size, Ordering::SeqCst);
            pending.fetch_sub(size, Ordering::SeqCst);
            queued.fetch_sub(1, Ordering::SeqCst);
            report();
        }
        Ok::<(), AppError>(())
    };
    // Progression de la décompression (elle avance dans son propre fil) : affichée régulièrement.
    let ticker = async {
        loop {
            tokio::time::sleep(Duration::from_millis(250)).await;
            if unpacking.load(Ordering::SeqCst) > 0 {
                report();
            }
        }
    };
    let listed = files.clone();
    tokio::select! {
        result = async { tokio::try_join!(downloads, unpacks) } => { result?; }
        _ = ticker => {}
    }
    // Dossier des archives regroupées : vide une fois toutes décompressées.
    let _ = std::fs::remove_dir(folder.join(BUNDLES_DIR));
    // Fichiers de la version précédente absents de la nouvelle : supprimés.
    let paths: std::collections::HashSet<&str> = listed.iter().map(|f| f.path.as_str()).collect();
    for old in previous.keys().filter(|p| !paths.contains(p.as_str())) {
        let _ = std::fs::remove_file(old.split('/').fold(folder.clone(), |path, part| path.join(part)));
    }
    let marker = json!({
        "size": listed.iter().map(|f| f.size).sum::<u64>(),
        "stamp": listing.get("contentStamp").cloned().unwrap_or(Value::Null),
        "version": listing.get("version").cloned().unwrap_or(Value::Null),
    });
    let received: Map<String, Value> = listed.iter().map(|f| (f.path.clone(), json!([f.size, f.mtime.unwrap_or(0)]))).collect();
    library::write_folder_marker(system, game, &marker, &Value::Object(received))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(path: &str, size: u64, archive_size: Option<u64>) -> FolderFile {
        FolderFile { path: path.into(), size, archive_size, format: "xz", mtime: None, bundle: None, offset: 0, crc32: None }
    }

    #[test]
    fn vitesse_mesuree_par_seconde_et_lissee() {
        let start = Instant::now();
        let at = |ms: u64| start + Duration::from_millis(ms);
        let mut speed = Speed::default();
        assert_eq!(speed.update(0, at(0)), 0);
        assert_eq!(speed.update(500_000, at(500)), 0); // fenêtre pas encore écoulée
        assert_eq!(speed.update(1_000_000, at(1000)), 1_000_000);
        assert_eq!(speed.update(4_000_000, at(2000)), 2_000_000); // 3 Mo/s lissés avec 1 Mo/s
        assert_eq!(speed.update(4_000_000, at(3000)), 1_000_000); // plus rien reçu : ralentit
        assert_eq!(speed.update(0, at(3500)), 1_000_000); // compteur revenu en arrière : nouvelle fenêtre
        assert_eq!(speed.update(2_000_000, at(4500)), 1_500_000);
    }

    #[test]
    fn place_pour_les_fichiers_decompresses_et_la_plus_grosse_archive() {
        let todo = [file("a.dat", 100, None), file("big.pak", 10_000, Some(6_000)), file("mid.pak", 5_000, Some(4_000))];
        assert_eq!(needed_bytes(&todo), 15_100 + 6_000 + SPACE_MARGIN);
        let mb = 1024 * 1024;
        assert!(must_wait(&todo[1], 100 * mb, 100 * mb));
        assert!(!must_wait(&todo[1], 100 * mb, 200 * mb));
        // Serveur d'avant le xz : zipSize, format zip.
        let old = FolderFile::from_json(&json!({ "path": "x", "size": 10, "zipSize": 4 }));
        assert_eq!((old.archive_size, old.format), (Some(4), "zip"));
        let xz = FolderFile::from_json(&json!({ "path": "x", "size": 10, "packedSize": 3, "format": "xz" }));
        assert_eq!((xz.archive_size, xz.format), (Some(3), "xz"));
        let zst = FolderFile::from_json(&json!({ "path": "x", "size": 10, "packedSize": 3, "format": "zst" }));
        assert_eq!((zst.archive_size, zst.format), (Some(3), "zst"));
    }

    #[test]
    fn fichiers_regroupes_telecharges_en_une_archive_puis_ecrits_chacun_a_sa_place() {
        let listed = |v: Value| FolderFile::from_json(&v);
        let files = [
            listed(json!({ "path": "big.pak", "size": 500, "packedSize": 300, "format": "zst" })),
            listed(json!({ "path": "data/a.dat", "size": 0, "format": "zst", "bundle": "1", "offset": 0 })),
            listed(json!({ "path": "b.dat", "size": 0, "format": "zst", "bundle": "1", "offset": 0 })),
            listed(json!({ "path": "c.dat", "size": 30, "format": "zst", "bundle": "2", "offset": 0 })),
        ];
        // a.dat absent : l'archive 1 entière ; c.dat déjà là : archive 2 pas téléchargée.
        let bundles = json!({ "1": { "packedSize": 12, "size": 0 }, "2": { "packedSize": 9, "size": 30 } });
        let (todo, members) = downloads(&files, vec![files[0].clone(), files[1].clone()], &bundles).unwrap();
        assert_eq!(todo.iter().map(|f| f.path.as_str()).collect::<Vec<_>>(), ["big.pak", ".romcloud-bundles/1"]);
        assert_eq!((todo[1].download_size(), todo[1].format, todo[1].bundle.as_deref()), (12, "zst", Some("1")));
        assert_eq!(archive_path(Path::new(&todo[1].path), "zst").file_name().unwrap(), "1.zst");
        assert_eq!(members["1"].len(), 2);
        assert!(downloads(&files, vec![files[3].clone()], &json!({})).is_err());

        let dir = std::env::temp_dir().join(format!("romcloud-bundle-{}", std::process::id()));
        std::fs::create_dir_all(dir.join(BUNDLES_DIR)).unwrap();
        let a: Vec<u8> = (0..300_000u32).map(|i| (i % 97) as u8).collect();
        let c: Vec<u8> = (0..200_000u32).map(|i| (i % 13) as u8).collect();
        let crc = |d: &[u8]| format!("{:08x}", crc32fast::hash(d));
        let members = [
            ("data/a.dat", &a, 0u64),
            ("c.dat", &c, a.len() as u64),
            ("empty.txt", &Vec::new(), a.len() as u64), // vide, au même endroit que c.dat
        ]
        .map(|(path, data, offset)| {
            let file = listed(json!({ "path": path, "size": data.len(), "crc32": crc(data), "bundle": "1", "offset": offset, "format": "zst" }));
            (file, path.split('/').fold(dir.clone(), |p, part| p.join(part)))
        });
        let archive = dir.join(BUNDLES_DIR).join("1.zst");
        let write = |content: &[u8]| {
            let mut zst = Vec::new();
            ruzstd::encoding::compress(content, &mut zst, ruzstd::encoding::CompressionLevel::Fastest);
            std::fs::write(&archive, zst).unwrap();
        };
        let (cancelled, downloading) = (AtomicBool::new(false), AtomicBool::new(false));
        write(&[a.clone(), c.clone()].concat());
        unpack_bundle(&archive, &members, &cancelled, &downloading, |_| {}).unwrap();
        assert_eq!(std::fs::read(dir.join("data").join("a.dat")).unwrap(), a);
        assert_eq!(std::fs::read(dir.join("c.dat")).unwrap(), c);
        assert!(dir.join("empty.txt").is_file());
        assert!(!archive.exists());
        // Contenu qui ne correspond pas au CRC32 d'un fichier : refusé, archive supprimée.
        std::fs::remove_file(dir.join("c.dat")).unwrap();
        let mut wrong = c.clone();
        wrong.reverse();
        write(&[a.clone(), wrong].concat());
        assert!(unpack_bundle(&archive, &members, &cancelled, &downloading, |_| {}).is_err());
        assert!(!dir.join("c.dat").exists() && !dir.join("c.dat.unpack").exists() && !archive.exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn archives_zstd_xz_et_zip_decompressees_puis_supprimees() {
        let dir = std::env::temp_dir().join(format!("romcloud-unpack-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..3_000_000u32).map(|i| (i % 251) as u8).collect();
        let cancelled = AtomicBool::new(false);
        let downloading = AtomicBool::new(false);

        // zstd (somme de contrôle comprise, comme celles du serveur).
        let new = dir.join("new.pak");
        let mut zst = Vec::new();
        ruzstd::encoding::compress(&data[..], &mut zst, ruzstd::encoding::CompressionLevel::Fastest);
        std::fs::write(archive_path(&new, "zst"), &zst).unwrap();
        assert_eq!(archive_path(&new, "zst").file_name().unwrap(), "new.pak.zst");
        unpack_file(&archive_path(&new, "zst"), "zst", &new, data.len() as u64, &cancelled, &downloading, |_| {}).unwrap();
        assert_eq!(std::fs::read(&new).unwrap(), data);
        assert!(!archive_path(&new, "zst").exists());
        // Archive abîmée : erreur, archive supprimée.
        let broken = dir.join("broken.pak");
        let middle = zst.len() / 2;
        zst[middle] ^= 0x55;
        std::fs::write(archive_path(&broken, "zst"), &zst).unwrap();
        assert!(unpack_file(&archive_path(&broken, "zst"), "zst", &broken, data.len() as u64, &cancelled, &downloading, |_| {}).is_err());
        assert!(!broken.exists() && !archive_path(&broken, "zst").exists());

        // xz en plusieurs blocs (comme xz -T du serveur).
        let target = dir.join("level1.pak");
        let archive = archive_path(&target, "xz");
        assert_eq!(archive.file_name().unwrap(), "level1.pak.xz");
        {
            let mut options = lzma_rust2::XzOptions::with_preset(6);
            options.set_block_size(std::num::NonZeroU64::new(1_000_000));
            let mut writer = lzma_rust2::XzWriter::new(std::fs::File::create(&archive).unwrap(), options).unwrap();
            writer.write_all(&data).unwrap();
            writer.finish().unwrap();
        }
        unpack_file(&archive, "xz", &target, data.len() as u64, &cancelled, &downloading, |_| {}).unwrap();
        assert_eq!(std::fs::read(&target).unwrap(), data);
        assert!(!archive.exists());

        // Pendant le téléchargement : un seul fil, en arrière-plan ; même résultat.
        let low = dir.join("low.pak");
        {
            let mut options = lzma_rust2::XzOptions::with_preset(6);
            options.set_block_size(std::num::NonZeroU64::new(1_000_000));
            let mut writer = lzma_rust2::XzWriter::new(std::fs::File::create(archive_path(&low, "xz")).unwrap(), options).unwrap();
            writer.write_all(&data).unwrap();
            writer.finish().unwrap();
        }
        unpack_file(&archive_path(&low, "xz"), "xz", &low, data.len() as u64, &cancelled, &AtomicBool::new(true), |_| {}).unwrap();
        assert_eq!(std::fs::read(&low).unwrap(), data);

        // zip des compressions d'avant le xz.
        let old = dir.join("old.dat");
        {
            let mut writer = zip::ZipWriter::new(std::fs::File::create(archive_path(&old, "zip")).unwrap());
            writer.start_file("old.dat", zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated)).unwrap();
            writer.write_all(&data).unwrap();
            writer.finish().unwrap();
        }
        unpack_file(&archive_path(&old, "zip"), "zip", &old, data.len() as u64, &cancelled, &downloading, |_| {}).unwrap();
        assert_eq!(std::fs::read(&old).unwrap(), data);

        // Taille différente : erreur, archive supprimée (téléchargée de nouveau).
        let bad = dir.join("bad.bin");
        {
            let mut writer = lzma_rust2::XzWriter::new(std::fs::File::create(archive_path(&bad, "xz")).unwrap(), lzma_rust2::XzOptions::with_preset(1)).unwrap();
            writer.write_all(b"abc").unwrap();
            writer.finish().unwrap();
        }
        assert!(unpack_file(&archive_path(&bad, "xz"), "xz", &bad, 99, &cancelled, &downloading, |_| {}).is_err());
        assert!(!bad.exists() && !archive_path(&bad, "xz").exists());
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
