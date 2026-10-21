// Moteur d'émulation intégré (romcloud-player.exe, frontend libretro natif) : téléchargement des
// cœurs à la demande depuis le buildbot libretro, préparation de la ROM et lancement du jeu.
// Données dans le dossier de l'application : libretro\cores, saves\<cœur>, states\<cœur>,
// options\<système>.cfg, keyboard.cfg (touches du clavier), player.log (journal).
use crate::error::{AppError, Result};
use crate::library::s;
use crate::{account, api, cores, events, keyboard, managed, paths, settings, states};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock, Mutex};
use tauri::Manager;

fn root() -> PathBuf {
    paths::user_data().join("libretro")
}

fn safe(s: &str) -> String {
    s.chars().map(|c| if c.is_ascii_alphanumeric() || "._-".contains(c) { c } else { '_' }).collect()
}

/// Exécutable du moteur : à côté de l'application installée, sinon build local (développement).
pub fn player_path() -> PathBuf {
    if let Some(dir) = events::handle().and_then(|h| h.path().resource_dir().ok()) {
        let bundled = paths::plain(dir.join("player").join("romcloud-player.exe"));
        if bundled.exists() {
            return bundled;
        }
    }
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../desktop/native/player/build/romcloud-player.exe")
}

pub fn available() -> bool {
    player_path().exists()
}

fn core_file(core: &str) -> PathBuf {
    root().join("cores").join(cores::core_dll_name(core))
}

pub fn core_installed(core: &str) -> bool {
    cores::valid_core(core) && core_file(core).exists()
}

// Un téléchargement à la fois par cœur, même si l'utilisateur relance.
static CORE_LOCKS: LazyLock<Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// Cœur installé ou téléchargé (et décompressé) depuis le buildbot libretro ; chemin de la DLL.
pub async fn ensure_core(core: &str) -> Result<PathBuf> {
    if !cores::valid_core(core) {
        return Err(AppError::new("errors.coreInvalid", json!({ "core": core })));
    }
    let lock = CORE_LOCKS.lock().unwrap().entry(core.to_string()).or_default().clone();
    let _guard = lock.lock().await;
    if core_installed(core) {
        return Ok(core_file(core));
    }
    let res = api::client()
        .get(cores::core_url(core))
        .send()
        .await
        .map_err(|e| AppError::new("errors.coreDownload", json!({ "core": core, "detail": api::network_detail(&e) })))?;
    if res.status().as_u16() == 404 {
        return Err(AppError::new("errors.coreNotOnBuildbot", json!({ "core": core })));
    }
    if !res.status().is_success() {
        return Err(AppError::new("errors.coreDownload", json!({ "core": core, "detail": format!("HTTP {}", res.status().as_u16()) })));
    }
    let archive = res.bytes().await.map_err(|e| AppError::new("errors.coreDownload", json!({ "core": core, "detail": e.to_string() })))?;
    let core_name = core.to_string();
    tokio::task::spawn_blocking(move || -> Result<PathBuf> {
        let data = extract_dll(&archive)
            .ok_or_else(|| AppError::new("errors.coreDownload", json!({ "core": core_name, "detail": "archive sans .dll" })))??;
        let target = core_file(&core_name);
        std::fs::create_dir_all(target.parent().unwrap())?;
        let part = PathBuf::from(format!("{}.part", target.display()));
        std::fs::write(&part, data)?;
        std::fs::rename(&part, &target)?;
        Ok(target)
    })
    .await
    .map_err(|e| AppError::msg(e.to_string()))?
}

/// Fichiers système des cœurs (« Core System Files » de RetroArch) : cœur -> archive du buildbot et
/// fichier témoin de leur présence dans le dossier des BIOS, le dernier de l'archive (installation
/// interrompue : refaite). Dolphin : polices de la console, shaders, ressources, gestionnaire des
/// codes ; sans eux, des jeux GameCube plantent ou n'affichent pas leurs textes.
const SYSTEM_FILES: &[(&str, &str, &str)] = &[("dolphin", "Dolphin.zip", "dolphin-emu/license.txt")];
const SYSTEM_FILES_URL: &str = "https://buildbot.libretro.com/assets/system";

/// Archive des fichiers système à installer pour ce cœur, s'ils manquent dans [bios].
fn missing_system_files(core: &str, bios: &Path) -> Option<&'static str> {
    let (_, archive, marker) = SYSTEM_FILES.iter().find(|(c, _, _)| *c == core)?;
    (!cores::safe_entry_path(bios, marker).is_file()).then_some(*archive)
}

/// Fichiers système du cœur téléchargés et décompressés dans le dossier des BIOS s'ils manquent
/// (le jeu est lancé même en cas d'échec : le cœur signale ce qui lui manque).
async fn ensure_system_files(core: &str) {
    let bios = settings::bios_dir();
    let Some(archive) = missing_system_files(core, &bios) else { return };
    let lock = CORE_LOCKS.lock().unwrap().entry(format!("system:{core}")).or_default().clone();
    let _guard = lock.lock().await;
    if missing_system_files(core, &bios).is_none() {
        return;
    }
    let data = match api::client().get(format!("{SYSTEM_FILES_URL}/{archive}")).send().await {
        Ok(res) if res.status().is_success() => res.bytes().await.ok(),
        _ => None,
    };
    let Some(data) = data else {
        eprintln!("[builtin] fichiers système {archive} impossibles à télécharger");
        return;
    };
    let result = tokio::task::spawn_blocking(move || extract_into(&data, &bios)).await;
    match result {
        Ok(Ok(count)) => eprintln!("[builtin] fichiers système {archive} installés ({count} fichiers)"),
        _ => eprintln!("[builtin] fichiers système {archive} impossibles à décompresser"),
    }
}

/// Décompresse [archive] dans [dir] (chemins hors du dossier ignorés) ; nombre de fichiers.
fn extract_into(archive: &[u8], dir: &Path) -> Result<usize> {
    let mut zip = zip::ZipArchive::new(std::io::Cursor::new(archive))?;
    let mut count = 0;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        let Some(name) = entry.name().ok().map(|n| n.to_string()) else { continue };
        let target = cores::safe_entry_path(dir, &name);
        if name.ends_with('/') {
            std::fs::create_dir_all(&target)?;
            continue;
        }
        // Déjà présent à la bonne taille (installation interrompue reprise) : gardé.
        if std::fs::metadata(&target).is_ok_and(|m| m.is_file() && m.len() == entry.size()) {
            continue;
        }
        std::fs::create_dir_all(target.parent().unwrap_or(dir))?;
        std::io::copy(&mut entry, &mut std::fs::File::create(&target)?)?;
        count += 1;
    }
    Ok(count)
}

/// DLL contenue dans l'archive d'un cœur du buildbot ; None si l'archive n'en contient pas.
fn extract_dll(archive: &[u8]) -> Option<Result<Vec<u8>>> {
    let mut zip = match zip::ZipArchive::new(std::io::Cursor::new(archive)) {
        Ok(zip) => zip,
        Err(e) => return Some(Err(e.into())),
    };
    let index = (0..zip.len()).find(|&i| zip.by_index(i).is_ok_and(|f| f.name().is_ok_and(|n| n.to_lowercase().ends_with(".dll"))))?;
    let mut data = Vec::new();
    Some(zip.by_index(index).map_err(AppError::from).and_then(|mut f| f.read_to_end(&mut data).map_err(AppError::from)).map(|_| data))
}

/// ROM passée au moteur : .zip décompressé pour les cœurs qui ne lisent pas les archives.
fn prepare_rom(core: &str, file: &str) -> Result<String> {
    if !cores::needs_extraction(core, file) {
        return Ok(file.to_string());
    }
    let mut zip = zip::ZipArchive::new(std::fs::File::open(file)?)?;
    let entries: Vec<(String, u64)> = (0..zip.len()).filter_map(|i| zip.by_index(i).ok().and_then(|f| Some((f.name().ok()?.to_string(), f.size())))).collect();
    let dir = root().join("rom-cache");
    let _ = std::fs::remove_dir_all(&dir);
    let chosen = cores::main_entry(&entries);
    let mut main = None;
    for (i, (name, _)) in entries.iter().enumerate() {
        if name.ends_with('/') {
            continue;
        }
        let target = cores::safe_entry_path(&dir, name);
        std::fs::create_dir_all(target.parent().unwrap_or(&dir))?;
        let mut out = std::fs::File::create(&target)?;
        std::io::copy(&mut zip.by_index(i)?, &mut out)?;
        if Some(i) == chosen {
            main = Some(target.to_string_lossy().into_owned());
        }
    }
    Ok(main.unwrap_or_else(|| file.to_string()))
}

fn keys_file() -> PathBuf {
    root().join("keyboard.cfg")
}

/// Touches du clavier du moteur ; sans fichier, celles des paramètres de la version 1.16.0.
pub fn load_keys() -> Value {
    match std::fs::read_to_string(keys_file()) {
        Ok(text) => keyboard::parse_file(&text),
        Err(_) => keyboard::resolve(settings::load().get("keyboard").unwrap_or(&json!({}))),
    }
}

pub fn save_keys(keys: &Value) -> Result<Value> {
    std::fs::create_dir_all(root())?;
    std::fs::write(keys_file(), keyboard::format_file(keys))?;
    Ok(load_keys())
}

/// État de sauvegarde du jeu dans le moteur (« Sauvegarder et quitter », F2…).
pub fn state_path(core: &str, file: &str) -> PathBuf {
    root().join("states").join(core).join(format!("{}.state", paths::stem(file)))
}

/// Historique des états du jeu (plusieurs états avec miniature, voir states.rs).
pub fn history_dir(core: &str, file: &str) -> PathBuf {
    root().join("states").join(core).join(format!("{}.history", paths::stem(file)))
}

fn options_file(system_id: &str) -> PathBuf {
    root().join("options").join(format!("{}.cfg", safe(system_id)))
}

/**
 * Arguments du moteur sans fenêtre pour les manettes du système (fiche du jeu, pads.rs) : mêmes
 * fichiers que le jeu (options du système, touches du clavier et joueurs) et boutons de sa console.
 */
pub fn pad_service_args(system: &Value, core: &str) -> Vec<String> {
    let system_key = if s(system, "shortname").is_empty() { s(system, "id") } else { s(system, "shortname") };
    let language = settings::language();
    let _ = std::fs::create_dir_all(root().join("options"));
    vec![
        "--pad-service".into(),
        "--options".into(),
        options_file(s(system, "id")).to_string_lossy().into_owned(),
        "--keys-file".into(),
        keys_file().to_string_lossy().into_owned(),
        "--buttons".into(),
        keyboard::console_buttons(system_key, core, &language),
        "--lang".into(),
        language,
    ]
}

/// Réinitialise un cœur après un plantage : DLL supprimée, options du cœur du système effacées.
pub fn reset_core(system_id: &str, core: &str) -> Result<()> {
    // RPCS3, Cemu : nouvelle version au prochain lancement, configuration et sauvegardes gardées.
    if let Some(app) = managed::by_core(core) {
        app.reset();
        return Ok(());
    }
    if !cores::valid_core(core) {
        return Err(AppError::new("errors.coreInvalid", json!({ "core": core })));
    }
    let _ = std::fs::remove_file(core_file(core));
    if !system_id.is_empty() {
        let _ = std::fs::remove_file(options_file(system_id));
    }
    Ok(())
}

/// Sauvegarde du jeu (mémoire de la cartouche) écrite par le moteur : nom de la ROM lancée.
fn sram_path(core: &str, rom: &str) -> PathBuf {
    root().join("saves").join(core).join(format!("{}.srm", paths::stem(rom)))
}

/**
 * Lance le jeu dans le moteur intégré (le cœur est téléchargé avant si besoin) ; `resume` :
 * reprend la partie à son état de sauvegarde ; `extra` : arguments du moteur en plus (jeu à plusieurs).
 * Profil connecté : les sauvegardes en ligne plus
 * récentes sont récupérées avant, et à la fermeture du moteur, le temps de jeu est compté et les
 * sauvegardes modifiées sont envoyées au serveur.
 */
pub async fn launch(system: &Value, game: &Value, file: &str, core: &str, resume: bool, extra: Vec<String>, save_core: Option<&str>) -> Result<()> {
    if !available() {
        return Err(AppError::new("errors.playerMissing", json!({ "path": player_path().to_string_lossy() })));
    }
    let dll = ensure_core(core).await?;
    ensure_system_files(core).await;
    let rom = {
        let (core, file) = (core.to_string(), file.to_string());
        tokio::task::spawn_blocking(move || prepare_rom(&core, &file)).await.map_err(|e| AppError::msg(e.to_string()))??
    };
    let save_files: account::SaveFiles = vec![("state", state_path(core, file)), ("sram", sram_path(core, &rom))];
    let game_id = game.get("id").cloned().unwrap_or(Value::Null);
    let downloaded = account::download_newer(&game_id, core, &save_files).await;
    // Toute sauvegarde est dans l'historique : état de reprise téléchargé du profil (autre appareil)
    // ou d'une version précédente, ajouté s'il n'y est pas.
    let online_state = downloaded.iter().find(|s| s["kind"] == "state");
    let (device, platform) = match online_state {
        Some(s) => (s["device"].as_str().unwrap_or("").to_string(), s["platform"].as_str().unwrap_or("").to_string()),
        None => (account::hostname(), "windows".to_string()),
    };
    crate::states::record_quick(&history_dir(core, file), &state_path(core, file), core, &device, &platform);
    // Historique des états : ceux des autres appareils du profil, proposés par le menu du moteur.
    let history = history_dir(core, file);
    states::prefetch(&game_id, core, &history).await;
    // Liaison entre consoles avec un autre cœur que l'émulateur choisi : sauvegarde de celui-ci
    // reprise si elle est plus récente, recopiée pour lui en quittant.
    let linked_sram = save_core.filter(|other| *other != core).map(|other| {
        let other_rom = if cores::needs_extraction(other, file) { rom.clone() } else { file.to_string() };
        (other.to_string(), sram_path(other, &other_rom))
    });
    if let Some((_, other)) = &linked_sram {
        copy_if_newer(other, &sram_path(core, &rom));
    }

    let base = root();
    let system_key = if s(system, "shortname").is_empty() { s(system, "id") } else { s(system, "shortname") };
    let language = settings::language();
    let title = if s(game, "title").is_empty() { paths::stem(file) } else { s(game, "title").to_string() };
    let text = |p: PathBuf| p.to_string_lossy().into_owned();
    let mut args = cores::player_args(&cores::PlayerArgs {
        dll: text(dll),
        rom: rom.clone(),
        system_dir: text(settings::bios_dir()),
        save_dir: text(base.join("saves").join(core)),
        state_dir: text(base.join("states").join(core)),
        state_name: Some(paths::stem(file)),
        options_file: text(options_file(s(system, "id"))),
        title,
        language: language.clone(),
        windowed: false,
        resume,
        keys_file: Some(text(keys_file())),
        buttons: Some(keyboard::console_buttons(system_key, core, &language)),
        pad_style: Some(keyboard::console_pad(system_key, core).to_string()),
        option_defaults: cores::game_option_defaults(core, &rom),
    });
    args.extend(["--history-dir".to_string(), text(history.clone()), "--history-device".to_string(), account::hostname()]);
    // Jeu à plusieurs en réseau local (netplay.rs) : partie proposée ou rejointe ; état choisi.
    args.extend(extra);
    std::fs::create_dir_all(&base)?;
    if !keys_file().exists() {
        save_keys(&load_keys())?;
    }
    // Manettes de la fiche du jeu : service arrêté (le jeu reprend leurs réglages).
    crate::pads::stop();
    let log = std::fs::File::create(base.join("player.log"))?;
    let mut child = tokio::process::Command::new(player_path())
        .args(&args)
        .stdin(std::process::Stdio::null())
        .stdout(log.try_clone()?)
        .stderr(log)
        .spawn()
        .map_err(|e| AppError::new("errors.launchFailed", json!({ "detail": e.to_string() })))?;
    paths::allow_foreground(child.id());
    let started = paths::now_ms();
    let (system, game, core) = (system.clone(), game.clone(), core.to_string());
    crate::launcher::game_started();
    tauri::async_runtime::spawn(async move {
        let code = child.wait().await.ok().and_then(|s| s.code()).unwrap_or(0);
        crate::netplay::player_exited();
        crate::launcher::game_exited(&game_id);
        account::add_playtime(game_id.clone(), (paths::now_ms() - started) / 1000.0).await;
        account::upload_changed(&game_id, &core, &save_files, started - 2000.0).await;
        states::after_session(&game_id, &history).await;
        if let Some((other, path)) = linked_sram {
            if let Some((_, sram)) = save_files.iter().find(|(kind, _)| *kind == "sram") {
                copy_if_newer(sram, &path);
            }
            account::upload_changed(&game_id, &other, &vec![("sram", path)], started - 2000.0).await;
        }
        if code != 0 {
            let log = last_log(&base);
            account::report_error(&format!("player:{core}"), &format!("romcloud-player exit code {code}"), log.clone());
            events::emit(
                "player:crashed",
                json!({
                    "system": { "id": system["id"], "name": system["name"] },
                    "game": { "id": game["id"], "title": game["title"] },
                    "core": core, "code": code, "log": log,
                }),
            );
        }
    });
    Ok(())
}

/// Copie [from] sur [to] s'il est plus récent (ou si [to] n'existe pas).
fn copy_if_newer(from: &Path, to: &Path) {
    let modified = |p: &Path| std::fs::metadata(p).and_then(|m| m.modified()).ok();
    let Some(source) = modified(from) else { return };
    if modified(to).is_some_and(|target| target >= source) {
        return;
    }
    if let Some(dir) = to.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let _ = std::fs::copy(from, to);
}

/// Fin du journal du moteur (jointe à l'erreur signalée quand il s'arrête sur une erreur).
fn last_log(base: &Path) -> Option<String> {
    let text = std::fs::read_to_string(base.join("player.log")).ok()?;
    let chars: Vec<char> = text.chars().collect();
    Some(chars[chars.len().saturating_sub(4000)..].iter().collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn archive(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        for (name, data) in files {
            zip.start_file(*name, options).unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap().into_inner()
    }

    #[test]
    fn fichiers_systeme_de_dolphin() {
        let dir = std::env::temp_dir().join(format!("romcloud-sysfiles-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        assert_eq!(missing_system_files("dolphin", &dir), Some("Dolphin.zip"));
        assert_eq!(missing_system_files("snes9x", &dir), None);
        // Archive : dossiers et fichiers, chemin hors du dossier ignoré.
        let mut buf = Vec::new();
        {
            let mut zip = zip::ZipWriter::new(std::io::Cursor::new(&mut buf));
            let options = zip::write::SimpleFileOptions::default();
            zip.add_directory("dolphin-emu/Sys/GC/", options).unwrap();
            zip.start_file("dolphin-emu/Sys/GC/dsp_rom.bin", options).unwrap();
            std::io::Write::write_all(&mut zip, b"rom").unwrap();
            zip.start_file("dolphin-emu/license.txt", options).unwrap();
            std::io::Write::write_all(&mut zip, b"gpl").unwrap();
            zip.start_file("../evil.txt", options).unwrap();
            std::io::Write::write_all(&mut zip, b"x").unwrap();
            zip.finish().unwrap();
        }
        // Installation interrompue (témoin absent) : refaite, fichiers déjà présents gardés.
        std::fs::create_dir_all(dir.join("dolphin-emu/Sys/GC")).unwrap();
        std::fs::write(dir.join("dolphin-emu/Sys/GC/dsp_rom.bin"), b"rom").unwrap();
        assert_eq!(missing_system_files("dolphin", &dir), Some("Dolphin.zip"));
        assert_eq!(extract_into(&buf, &dir).unwrap(), 2);
        assert_eq!(missing_system_files("dolphin", &dir), None);
        assert!(dir.join("evil.txt").is_file() && !dir.parent().unwrap().join("evil.txt").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn dll_d_un_coeur_du_buildbot() {
        let dll = vec![7u8; 5000];
        let zip = archive(&[("readme.txt", b"bonjour"), ("fceumm_libretro.dll", &dll)]);
        assert_eq!(extract_dll(&zip).unwrap().unwrap(), dll);
        assert!(extract_dll(&archive(&[("readme.txt", b"bonjour")])).is_none());
        assert!(extract_dll(b"pas une archive").unwrap().is_err());
    }

    #[test]
    fn nom_de_fichier_sur() {
        assert_eq!(safe("gb/Game Boy:2"), "gb_Game_Boy_2");
    }
}
