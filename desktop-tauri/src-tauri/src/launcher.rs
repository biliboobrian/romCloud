// Lancement des jeux sous Windows.
//  - moteur intégré (romcloud-player.exe) : mêmes cœurs que RetroArch, téléchargés à la demande ;
//    PlayStation 3 et Wii U : RPCS3 et Cemu, téléchargés et gérés par RomCloud (pas de cœur libretro) ;
//  - RetroArch : « retroarch.exe -L <cœur> <jeu> », le cœur étant repris des modèles d'émulateurs ;
//  - émulateurs du catalogue (Dolphin, PCSX2, DuckStation…) : ligne de commande connue, ou
//    ouverture de l'émulateur seul quand il ne sait pas lancer un jeu directement ;
//  - commande personnalisée par système (« {file} » = chemin du jeu) ;
//  - programme associé au type de fichier dans Windows.
use crate::catalog::{self, Emulator};
use crate::error::{AppError, Result};
use crate::library::s;
use crate::{account, builtin, cores, library, managed, netplay, paths, settings, vita3k};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};

// ---------------------------------------------------------------------------
// Émulateurs du catalogue : emplacement sur le PC et ligne de commande
// ---------------------------------------------------------------------------

fn setting_map(key: &str) -> serde_json::Map<String, Value> {
    settings::load().get(key).and_then(Value::as_object).cloned().unwrap_or_default()
}

/// Emplacement enregistré : exécutable, ou dossier (émulateur indiqué par son dossier, voir
/// catalog::folder_exes) ; un ancien chemin d'« eden-cli » (entrée séparée autrefois) vaut pour Eden.
fn stored_location(id: &str) -> Option<String> {
    let map = setting_map("emulatorPaths");
    let get = |key: &str| map.get(key).and_then(Value::as_str).filter(|f| !f.is_empty() && Path::new(f).exists()).map(String::from);
    get(id).or_else(|| if id == "eden" { get("eden-cli") } else { None })
}

/// Exécutable qui lance les jeux : choisi par l'utilisateur ou trouvé par la dernière recherche.
fn emulator_path(id: &str) -> Option<String> {
    let stored = stored_location(id)?;
    match catalog::folder_exes(id) {
        Some(exes) => catalog::exe_in_folder(Path::new(&stored), exes.game).map(|p| p.to_string_lossy().into_owned()),
        None => Some(stored),
    }
}

/// Exécutable qui ouvre l'émulateur seul (eden.exe plutôt qu'eden-cli).
fn emulator_open_path(id: &str) -> Option<String> {
    let stored = stored_location(id)?;
    match catalog::folder_exes(id) {
        Some(exes) => catalog::exe_in_folder(Path::new(&stored), exes.open).map(|p| p.to_string_lossy().into_owned()),
        None => Some(stored),
    }
}

/// Dossier de l'émulateur (installé), pour les émulateurs indiqués par leur dossier.
pub fn emulator_folder(id: &str) -> Option<PathBuf> {
    catalog::folder_exes(id)?;
    let stored = PathBuf::from(stored_location(id)?);
    if stored.is_dir() { Some(stored) } else { stored.parent().map(Path::to_path_buf) }
}

/// Arguments du lancement : ceux saisis par l'utilisateur, sinon ceux du catalogue.
fn emulator_args(emu: &Emulator) -> Option<Vec<String>> {
    let custom = setting_map("emulatorArgs").get(emu.id).and_then(Value::as_str).unwrap_or("").to_string();
    if !custom.is_empty() {
        return Some(cores::tokenize(&custom));
    }
    emu.args.map(|a| a.iter().map(|s| s.to_string()).collect())
}

/// Cherche les émulateurs sur le PC ; les chemins déjà choisis par l'utilisateur sont conservés.
pub fn detect_emulators() -> Value {
    let found = catalog::detect();
    let mut paths_map = setting_map("emulatorPaths");
    for (id, file) in found {
        let keep = paths_map.get(&id).and_then(Value::as_str).map(|p| !p.is_empty() && Path::new(p).exists()).unwrap_or(false);
        if !keep {
            // Émulateur indiqué par son dossier : celui de l'exécutable trouvé.
            let location = if catalog::folder_exes(&id).is_some() { paths::dirname(&file) } else { file };
            paths_map.insert(id, Value::String(location));
        }
    }
    settings::save(json!({ "emulatorPaths": paths_map, "emulatorsDetectedAt": chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true) }));
    list_emulators()
}

/// Recherche automatique au premier affichage (puis à la demande).
fn ensure_detected() {
    if settings::get_str("emulatorsDetectedAt").is_empty() {
        detect_emulators();
    }
}

/// Catalogue avec l'état de chaque émulateur sur ce PC.
pub fn list_emulators() -> Value {
    let custom_args = setting_map("emulatorArgs");
    Value::Array(
        catalog::EMULATORS
            .iter()
            .map(|emu| {
                let exe = emulator_path(emu.id);
                let args = emulator_args(emu);
                let folder = emulator_folder(emu.id).map(|d| d.to_string_lossy().into_owned());
                json!({
                    "id": emu.id,
                    "name": emu.name,
                    "systems": emu.systems,
                    "url": emu.url,
                    // Dossier pour un émulateur indiqué par son dossier (Eden), sinon l'exécutable.
                    "path": if catalog::folder_exes(emu.id).is_some() { exe.as_ref().and(folder) } else { exe.clone() },
                    "folder": catalog::folder_exes(emu.id).is_some(),
                    // RomCloud sait vérifier et installer sa dernière version (emulator_updates.rs).
                    "updatable": crate::emulator_updates::supported(emu.id),
                    "defaultLocation": catalog::default_location(emu).to_string_lossy(),
                    "direct": args.is_some(),
                    "defaultArgs": catalog::args_line(emu.args),
                    "systemNames": emu.systems.iter().map(|s| catalog::system_name(s)).collect::<Vec<_>>(),
                    "customArgs": custom_args.get(emu.id).and_then(Value::as_str).unwrap_or(""),
                    "command": args.map(|a| catalog::command_line(exe.as_deref().unwrap_or(emu.exe[0]), &a)),
                })
            })
            .collect(),
    )
}

pub fn set_emulator_path(id: &str, file: &str) -> Result<Value> {
    let Some(emu) = catalog::by_id(id) else {
        return Err(AppError::new("errors.unknownEmulator", json!({ "id": id })));
    };
    // Émulateur indiqué par son dossier : il doit contenir l'un de ses exécutables.
    let location = match catalog::folder_exes(id) {
        Some(exes) => {
            let found = catalog::exe_in_folder(Path::new(file), exes.game)
                .ok_or_else(|| AppError::new("errors.emulatorFolderInvalid", json!({ "name": emu.name, "files": exes.game.join(", ") })))?;
            found.parent().map(|d| d.to_string_lossy().into_owned()).unwrap_or_else(|| file.into())
        }
        None => file.into(),
    };
    let mut map = setting_map("emulatorPaths");
    if id == "eden" {
        map.remove("eden-cli");
    }
    map.insert(id.into(), Value::String(location));
    settings::save(json!({ "emulatorPaths": map }));
    Ok(list_emulators())
}

pub fn set_emulator_args(id: &str, args: &str) -> Result<Value> {
    if catalog::by_id(id).is_none() {
        return Err(AppError::new("errors.unknownEmulator", json!({ "id": id })));
    }
    let mut map = setting_map("emulatorArgs");
    map.insert(id.into(), Value::String(args.trim().into()));
    settings::save(json!({ "emulatorArgs": map }));
    Ok(list_emulators())
}

/// Lance un programme ; [game] : jeu lancé, dont le temps de jeu est compté jusqu'à sa fermeture.
/// CreateProcess refusé : le programme demande les droits d'administrateur.
const ERROR_ELEVATION_REQUIRED: i32 = 740;

fn spawn_detached(exe: &str, args: &[String], game: Option<Value>) -> Result<()> {
    let dir = paths::dirname(exe);
    let mut command = tokio::process::Command::new(exe);
    command.args(args).stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
    if Path::new(&dir).exists() {
        command.current_dir(&dir);
    }
    let mut child = match command.spawn() {
        Ok(child) => child,
        // Programme qui demande les droits d'administrateur (installation, certains jeux PC) :
        // lancé par le shell de Windows, qui affiche la demande ; temps de jeu non compté.
        Err(e) if e.raw_os_error() == Some(ERROR_ELEVATION_REQUIRED) => {
            return paths::run_elevated(exe, args, &dir).map_err(|e| AppError::new("errors.launchFailed", json!({ "detail": e })));
        }
        Err(e) => return Err(AppError::new("errors.launchFailed", json!({ "detail": e.to_string() }))),
    };
    paths::allow_foreground(child.id());
    let started = paths::now_ms();
    game_started();
    tauri::async_runtime::spawn(async move {
        let _ = child.wait().await;
        game_exited(&game.as_ref().map(|g| g["id"].clone()).unwrap_or(Value::Null));
        if let Some(game) = game {
            account::add_playtime(game["id"].clone(), (paths::now_ms() - started) / 1000.0).await;
        }
    });
    Ok(())
}

/**
 * Jeu en dossier supprimé du PC par son désinstalleur (choisi sur le serveur) : marque de fin de
 * téléchargement retirée (le jeu n'est plus « sur ce PC »), désinstalleur lancé depuis son dossier
 * (droits d'administrateur demandés si besoin), puis ce qui reste du dossier effacé quand le
 * désinstalleur a disparu (Inno Setup, NSIS : copiés ailleurs, ils s'effacent en dernier). Au bout
 * d'une heure sans cela (désinstallation annulée), le dossier est laissé. Fin annoncée par
 * « downloads:update » (événement « uninstalled »). Faux : pas de désinstalleur sur le PC.
 */
pub fn uninstall(system: &Value, game: &Value) -> Result<bool> {
    let Some(exe) = library::folder_uninstaller(system, game).filter(|p| p.is_file()) else { return Ok(false) };
    let dir = exe.parent().map(Path::to_path_buf).unwrap_or_default();
    let _ = std::fs::remove_file(library::folder_marker(system, game));
    match std::process::Command::new(&exe).current_dir(&dir).spawn() {
        Ok(child) => paths::allow_foreground(Some(child.id())),
        Err(e) if e.raw_os_error() == Some(ERROR_ELEVATION_REQUIRED) => {
            paths::run_elevated(&exe.to_string_lossy(), &[], &dir.to_string_lossy())
                .map_err(|e| AppError::new("errors.launchFailed", json!({ "detail": e })))?;
        }
        Err(e) => return Err(AppError::new("errors.launchFailed", json!({ "detail": e.to_string() }))),
    }
    let folder = library::file_for(system, game);
    let (game_id, title) = (game["id"].clone(), s(game, "title").to_string());
    tauri::async_runtime::spawn(async move {
        let start = std::time::Instant::now();
        while exe.exists() && start.elapsed() < std::time::Duration::from_secs(3600) {
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        }
        let complete = !exe.exists();
        if complete {
            let _ = std::fs::remove_dir_all(&folder);
        }
        crate::events::emit(
            "downloads:update",
            json!({ "gameId": game_id, "state": null, "event": { "type": "uninstalled", "gameId": game_id, "title": title, "complete": complete } }),
        );
    });
    Ok(true)
}

/// Ouvre l'émulateur seul (sans jeu).
pub fn launch_emulator(id: &str) -> Result<()> {
    let emu = catalog::by_id(id).ok_or_else(|| AppError::new("errors.unknownEmulator", json!({ "id": id })))?;
    let exe = emulator_open_path(id).ok_or_else(|| AppError::new("errors.emulatorMissing", json!({ "name": emu.name, "id": id })))?;
    let args = crate::emulator_files::prepare(emu.id, &exe, Vec::new());
    spawn_detached(&exe, &args, None)
}

// ---------------------------------------------------------------------------
// Choix de l'émulateur d'un système
// ---------------------------------------------------------------------------

fn retroarch_ok() -> bool {
    let exe = settings::get_str("retroarchPath");
    !exe.is_empty() && Path::new(&exe).exists()
}

/// Choix proposés pour un système, et choix courant.
pub fn options(system: &Value) -> Value {
    ensure_detected();
    let system_cores = cores::cores(system);
    let mut list: Vec<Value> = Vec::new();
    if let Some(app) = managed::for_system(system) {
        list.push(json!({ "id": format!("builtin:{}", app.core), "kind": "builtin", "core": app.core, "name": app.name, "installed": app.installed() }));
    }
    // Moteur intégré en premier : mêmes cœurs que RetroArch, rien à installer.
    if builtin::available() {
        for core in &system_cores {
            list.push(json!({ "id": format!("builtin:{core}"), "kind": "builtin", "core": core, "installed": builtin::core_installed(core) }));
        }
    }
    // RetroArch : seulement pour un cœur que le moteur intégré n'a pas (absent du buildbot pour
    // Windows), ou sans moteur intégré (développement).
    for core in system_cores.iter().filter(|c| !builtin::available() || !cores::core_available(c)) {
        list.push(json!({ "id": format!("retroarch:{core}"), "kind": "retroarch", "core": core }));
    }
    for emu in catalog::for_system(system) {
        list.push(json!({ "id": format!("emu:{}", emu.id), "kind": "emulator", "emuId": emu.id, "name": emu.name, "installed": emulator_path(emu.id).is_some() }));
    }
    let order = server_order(system);
    sort_by_order(&mut list, &order);
    list.push(json!({ "id": "custom", "kind": "custom" }));
    list.push(json!({ "id": "default", "kind": "default" }));
    let saved = settings::load().get("emulators").and_then(|e| e.get(s(system, "id"))).cloned().unwrap_or(Value::Null);
    let kind = |o: &Value, k: &str| o["kind"] == k;
    // Ordre choisi sur le serveur : le premier utilisable de cet ordre ; sinon le moteur intégré d'abord.
    let usable = |o: &Value| match o["kind"].as_str().unwrap_or("") {
        "builtin" => managed::by_core(s(o, "core")).is_some() || cores::core_available(s(o, "core")),
        "retroarch" => retroarch_ok(),
        "emulator" => o["installed"] == true,
        _ => false,
    };
    let fallback = list
        .iter()
        .find(|o| order.contains(&s(o, "id").to_string()) && usable(o))
        .or_else(|| list.iter().find(|o| kind(o, "builtin")))
        .or_else(|| if retroarch_ok() { list.iter().find(|o| kind(o, "retroarch")) } else { None })
        .or_else(|| list.iter().find(|o| kind(o, "emulator") && o["installed"] == true))
        .or_else(|| list.iter().find(|o| kind(o, "retroarch") || kind(o, "emulator")))
        .or_else(|| list.iter().find(|o| kind(o, "default")))
        .map(|o| o["id"].clone())
        .unwrap_or(Value::Null);
    let selected = if list.iter().any(|o| o["id"] == saved["option"] && !saved["option"].is_null()) { saved["option"].clone() } else { fallback };
    json!({ "options": list, "selected": selected, "command": saved["command"].as_str().unwrap_or("") })
}

/// Ordre des émulateurs choisi sur le serveur pour Windows (identifiants des choix) ; vide : ordre de l'application.
fn server_order(system: &Value) -> Vec<String> {
    system["emulatorOrder"]["windows"].as_array().map(|ids| ids.iter().filter_map(Value::as_str).map(String::from).collect()).unwrap_or_default()
}

/// Choix de l'ordre [order] d'abord, dans cet ordre ; les autres après, dans leur ordre. « retroarch:<cœur> »
/// suit « builtin:<cœur> » s'il n'est pas lui-même dans l'ordre.
fn sort_by_order(list: &mut [Value], order: &[String]) {
    if order.is_empty() {
        return;
    }
    let rank = |o: &Value| {
        let id = s(o, "id");
        let builtin = id.strip_prefix("retroarch:").map(|core| format!("builtin:{core}"));
        order.iter().position(|x| x == id).or_else(|| builtin.and_then(|b| order.iter().position(|x| *x == b))).unwrap_or(order.len())
    };
    list.sort_by_key(|o| rank(o));
}

pub fn choose(system_id: &str, option: &Value, command: &Value) {
    let mut emulators = setting_map("emulators");
    let previous = emulators.get(system_id).and_then(|e| e.get("command")).and_then(Value::as_str).unwrap_or("").to_string();
    let command = command.as_str().map(String::from).unwrap_or(previous);
    emulators.insert(system_id.into(), json!({ "option": option, "command": command }));
    settings::save(json!({ "emulators": emulators }));
}

/// Dossier des cœurs de RetroArch (à côté de retroarch.exe).
fn core_dll(retroarch_path: &str, core: &str) -> PathBuf {
    PathBuf::from(paths::dirname(retroarch_path)).join("cores").join(format!("{core}_libretro.dll"))
}

/// Préparation du lancement.
enum Plan {
    Builtin { core: String, file: String, exe: String, args: Vec<String> },
    /// [emulator] : identifiant du catalogue (fichiers du dossier des BIOS à installer avant le lancement).
    Run { exe: String, args: Vec<String>, emulator: Option<&'static str> },
    Manual { exe: String, emulator: String, file: String },
    /// Vita3K : jeu démarré, ou installé d'abord (voir vita3k.rs).
    Vita { exe: String, step: vita3k::Step },
    Open(String),
}

/// Émulateurs du catalogue qui lisent une liste de disques (.m3u).
const PLAYLIST_EMULATORS: &[&str] = &["mednafen"];

/// Vérifications avant lancement (jeu téléchargé, émulateur installé, cœur présent…).
fn prepare(system: &Value, game: &Value) -> Result<Plan> {
    if !library::is_downloaded(system, game) {
        let file = library::file_for(system, game).to_string_lossy().into_owned();
        return Err(AppError::new("errors.notDownloaded", json!({ "file": file })));
    }
    plan(system, game)
}

/**
 * Problèmes qui empêchent de jouer avec l'émulateur externe choisi, jeu téléchargé ou non :
 * [{ key, vars }] (clés d'erreur traduites par l'interface) ; vide pour le moteur intégré, qui
 * télécharge lui-même ce qui lui manque.
 */
pub fn issues(system: &Value, game: &Value) -> Value {
    let downloaded = library::is_downloaded(system, game);
    let found = match plan(system, game) {
        // Identifiant d'un .pkg lu dans son en-tête : seulement une fois le jeu téléchargé.
        Err(e) if e.is("errors.vitaTitleId") && !downloaded => vec![],
        Err(e) => vec![json!({ "key": e.key, "vars": e.vars })],
        Ok(Plan::Run { exe, emulator: Some(id), .. }) => crate::emulator_files::missing_required(id, &exe)
            .into_iter()
            .map(|file| json!({ "key": "errors.emulatorKeysMissing", "vars": { "file": file, "name": catalog::by_id(id).map(|e| e.name).unwrap_or(id) } }))
            .collect(),
        Ok(_) => vec![],
    };
    json!(found)
}

/// Commande de lancement selon l'émulateur choisi (le jeu n'a pas besoin d'être téléchargé).
fn plan(system: &Value, game: &Value) -> Result<Plan> {
    // Jeu en dossier (PC Windows) : son exécutable, lancé directement depuis son dossier.
    if library::is_folder(game) {
        let exe = library::folder_exe(system, game).ok_or_else(|| AppError::new("errors.noExe", json!({ "title": s(game, "title") })))?;
        return Ok(Plan::Run { exe: exe.to_string_lossy().into_owned(), args: Vec::new(), emulator: None });
    }
    let file = library::file_for(system, game).to_string_lossy().into_owned();
    // Jeu en plusieurs disques : liste des disques (.m3u) pour les émulateurs qui la lisent
    // (changement de disque en jeu), sinon le premier disque.
    let main = file.clone();
    let playlist = || library::playlist(system, game).map(|p| p.to_string_lossy().into_owned()).unwrap_or_else(|| main.clone());
    let opts = options(system);
    let selected = opts["selected"].clone();
    let option = opts["options"].as_array().and_then(|l| l.iter().find(|o| o["id"] == selected).cloned()).unwrap_or(Value::Null);
    let command = opts["command"].as_str().unwrap_or("").to_string();
    match option["kind"].as_str().unwrap_or("default") {
        "builtin" if managed::by_core(s(&option, "core")).is_some() => {
            let app = managed::by_core(s(&option, "core")).unwrap();
            Ok(Plan::Builtin { exe: app.exe().to_string_lossy().into_owned(), args: app.args(&file), core: app.core.into(), file })
        }
        "builtin" => {
            let core = s(&option, "core").to_string();
            let file = playlist();
            Ok(Plan::Builtin {
                exe: builtin::player_path().to_string_lossy().into_owned(),
                args: vec!["--core".into(), format!("{core}_libretro.dll"), "--rom".into(), file.clone()],
                core,
                file,
            })
        }
        "retroarch" => {
            let exe = settings::get_str("retroarchPath");
            if exe.is_empty() || !Path::new(&exe).exists() {
                return Err(AppError::new("errors.retroarchMissing", json!({})));
            }
            let core = s(&option, "core");
            let dll = core_dll(&exe, core);
            if !dll.exists() {
                return Err(AppError::new("errors.coreMissing", json!({ "core": core, "dir": dll.parent().map(|p| p.to_string_lossy().into_owned()) })));
            }
            Ok(Plan::Run { exe, args: vec!["-L".into(), dll.to_string_lossy().into_owned(), playlist()], emulator: None })
        }
        "emulator" => {
            let emu = catalog::by_id(s(&option, "emuId")).ok_or_else(|| AppError::new("errors.unknownEmulator", json!({ "id": option["emuId"] })))?;
            let exe = emulator_path(emu.id).ok_or_else(|| AppError::new("errors.emulatorMissing", json!({ "name": emu.name, "id": emu.id })))?;
            let file = if PLAYLIST_EMULATORS.contains(&emu.id) { playlist() } else { file };
            // Vita3K sans ligne de commande personnalisée : installation du jeu puis démarrage.
            if emu.id == "vita3k" && emulator_args(emu).is_none() {
                return vita3k::plan(&exe, Path::new(&file), game)
                    .map(|step| Plan::Vita { exe, step })
                    .map_err(|key| AppError::new(key, json!({ "file": file })));
            }
            match emulator_args(emu) {
                None => Ok(Plan::Manual { exe, emulator: emu.name.into(), file }),
                Some(args) => Ok(Plan::Run { exe, args: catalog::build_args(&args, &file), emulator: Some(emu.id) }),
            }
        }
        "custom" => {
            if command.trim().is_empty() {
                return Err(AppError::new("errors.commandMissing", json!({})));
            }
            let mut tokens: Vec<String> = catalog::build_args(&cores::tokenize(&command), &file);
            if !command.contains("{file}") {
                tokens.push(file);
            }
            let exe = tokens.remove(0);
            Ok(Plan::Run { exe, args: tokens, emulator: None })
        }
        _ => Ok(Plan::Open(file)),
    }
}

/// Ligne de commande qui sera utilisée pour ce jeu (affichée dans la fiche), ou null.
pub fn describe(system: &Value, game: &Value) -> Value {
    match prepare(system, game) {
        Ok(Plan::Builtin { exe, args, .. }) | Ok(Plan::Run { exe, args, .. }) => json!(catalog::command_line(&exe, &args)),
        Ok(Plan::Vita { exe, step: vita3k::Step::Run(args) | vita3k::Step::InstallAndRun(args) }) => json!(catalog::command_line(&exe, &args)),
        Ok(Plan::Vita { exe, step: vita3k::Step::InstallPkg { run, .. } }) => json!(catalog::command_line(&exe, &run)),
        _ => Value::Null,
    }
}

/// État de RetroArch pour le guide : exécutable trouvé, cœur installé.
pub fn check(system: &Value) -> Value {
    let opts = options(system);
    let option = opts["options"].as_array().and_then(|l| l.iter().find(|o| o["id"] == opts["selected"]).cloned()).unwrap_or(Value::Null);
    let exe = settings::get_str("retroarchPath");
    let ok = retroarch_ok();
    let core = if option["kind"] == "retroarch" { Some(s(&option, "core").to_string()) } else { None };
    let dll = core.as_ref().filter(|_| !exe.is_empty()).map(|c| core_dll(&exe, c));
    json!({
        "usesRetroArch": option["kind"] == "retroarch",
        "retroarchPath": exe,
        "retroarchOk": ok,
        "core": core,
        "coreDir": dll.as_ref().and_then(|d| d.parent()).map(|p| p.to_string_lossy().into_owned()),
        "coreOk": dll.map(|d| ok && d.exists()).unwrap_or(false),
    })
}

/// Jeux lancés par l'application encore ouverts (moteur intégré, émulateurs gérés ou externes).
static RUNNING: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// Jeu lancé : compté jusqu'à sa fermeture ([game_exited]).
pub fn game_started() {
    RUNNING.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
}

/// Jeu fermé : l'interface en est prévenue (« game:exited », statut du lobby de nouveau « en attente »).
pub fn game_exited(game_id: &Value) {
    let _ = RUNNING.try_update(std::sync::atomic::Ordering::SeqCst, std::sync::atomic::Ordering::SeqCst, |n| Some(n.saturating_sub(1)));
    crate::events::emit("game:exited", json!({ "gameId": game_id, "running": RUNNING.load(std::sync::atomic::Ordering::SeqCst) }));
}

/// Nombre de jeux lancés par l'application encore ouverts.
pub fn games_running() -> usize {
    RUNNING.load(std::sync::atomic::Ordering::SeqCst)
}

/// Partie à reprendre : moteur intégré choisi et état de reprise ou de l'historique pour ce jeu, fait
/// avec l'un des cœurs intégrés du système (pas forcément celui choisi).
pub fn resumable(system: &Value, game: &Value) -> bool {
    latest_resume(system, game, None).is_some()
}

/// Cœur de la partie que « Reprendre » relance (null : aucune) ; un autre que celui choisi : l'interface
/// propose d'abord d'en faire l'émulateur du système.
pub fn resume_core(system: &Value, game: &Value) -> Option<String> {
    latest_resume(system, game, None).map(|(core, ..)| core)
}

/**
 * Jeu en plusieurs disques : { count, resume: partie à reprendre sur chaque disque (moteur intégré),
 * latest: disque de la partie la plus récente (null : aucune) } ; null pour un jeu d'un disque.
 */
pub fn resume_discs(system: &Value, game: &Value) -> Value {
    let count = library::discs(game).len();
    if count < 2 {
        return Value::Null;
    }
    let resume: Vec<bool> = (0..count).map(|disk| latest_resume(system, game, Some(disk)).is_some()).collect();
    let latest = latest_resume(system, game, None).map(|(.., disk)| disk.unwrap_or(0).min(count - 1));
    json!({ "count": count, "resume": resume, "latest": latest })
}

/**
 * Partie à reprendre la plus récente parmi les cœurs intégrés du système (état de reprise ou de
 * l'historique de chacun) : cœur, fichier de l'état et disque inséré (jeu en plusieurs disques,
 * None s'il n'est pas connu). Un état fait avec Yabause se reprend avec lui même si Beetle Saturn
 * est choisi. [disk] : parmi les états faits sur ce disque (disque inconnu : le premier).
 */
fn latest_resume(system: &Value, game: &Value, disk: Option<usize>) -> Option<(String, PathBuf, Option<usize>)> {
    let (list, file) = system_cores(system, game)?;
    list.into_iter()
        .filter_map(|core| {
            let history = builtin::history_dir(&core, &file);
            let (path, date) = crate::states::resume_point_on(&builtin::state_path(&core, &file), &history, disk)?;
            let found = crate::states::disk_of(&path, &history);
            Some((core, path, found, date))
        })
        // Égalité : le cœur choisi (premier de la liste).
        .fold(None::<(String, PathBuf, Option<usize>, f64)>, |best, next| match best {
            Some(b) if b.3 >= next.3 => Some(b),
            _ => Some(next),
        })
        .map(|(core, path, found, _)| (core, path, found))
}

/// Jeux de [games] (même système) avec une partie à reprendre : identifiants.
pub fn resumable_ids(system: &Value, games: &[Value]) -> Vec<Value> {
    games.iter().filter(|g| resumable(system, g)).map(|g| g["id"].clone()).collect()
}

/// Historique des états du jeu (moteur intégré) : dossier et cœur, ou None pour un autre émulateur.
fn history(system: &Value, game: &Value) -> Option<(std::path::PathBuf, String)> {
    match prepare(system, game) {
        Ok(Plan::Builtin { core, file, .. }) if managed::by_core(&core).is_none() => Some((builtin::history_dir(&core, &file), core)),
        _ => None,
    }
}

/**
 * Cœurs intégrés du système (celui choisi en premier) et fichier du jeu : un état ou une partie en
 * ligne faits avec un autre cœur (sur un autre appareil) se reprennent avec lui.
 */
fn system_cores(system: &Value, game: &Value) -> Option<(Vec<String>, String)> {
    let Ok(Plan::Builtin { core, file, .. }) = prepare(system, game) else { return None };
    if managed::by_core(&core).is_some() {
        return None;
    }
    let mut list = vec![core.clone()];
    list.extend(cores::cores(system).into_iter().filter(|c| *c != core && managed::by_core(c).is_none()));
    Some((list, file))
}

/// Historique des états du jeu (tous les cœurs intégrés du système) : { states, cores, online, signedIn }.
pub async fn states(system: &Value, game: &Value) -> Value {
    let Some((list, file)) = system_cores(system, game) else { return Value::Null };
    let histories: Vec<(String, std::path::PathBuf)> = list.iter().map(|c| (c.clone(), builtin::history_dir(c, &file))).collect();
    crate::states::list(&game["id"], &histories).await
}

/// Cœur demandé ([wanted], état ou partie en ligne) s'il est l'un des cœurs intégrés du système.
fn chosen_core(system: &Value, game: &Value, wanted: Option<&str>) -> Option<String> {
    let wanted = wanted.filter(|w| !w.is_empty())?;
    let (list, _) = system_cores(system, game)?;
    list.into_iter().find(|c| account::same_save_core(c, wanted))
}

/// Opération sur un état de l'historique : « thumbnail », « pin », « unpin », « upload », « delete ».
pub async fn state_action(system: &Value, game: &Value, action: &str, state: &Value) -> Result<Value> {
    let (default_dir, _) = history(system, game).ok_or_else(|| AppError::msg("historique indisponible".to_string()))?;
    // Historique du cœur de l'état (autre cœur intégré du système).
    let dir = match (chosen_core(system, game, state["core"].as_str()), system_cores(system, game)) {
        (Some(core), Some((_, file))) => builtin::history_dir(&core, &file),
        _ => default_dir,
    };
    let id = state["id"].as_str().unwrap_or("");
    let online = state["online"].as_bool().unwrap_or(false);
    Ok(match action {
        "thumbnail" => crate::states::thumbnail(&dir, id).await.map(Value::String).unwrap_or(Value::Null),
        "pin" | "unpin" => {
            crate::states::pin(&dir, id, action == "pin", online).await?;
            Value::Null
        }
        "upload" => {
            crate::states::upload(&game["id"], &dir, id).await?;
            Value::Null
        }
        // État du profil en ligne : téléchargé sur le PC (avant de jouer à partir de lui).
        "download" => {
            crate::states::file_for(&game["id"], &dir, id).await?;
            Value::Null
        }
        "delete" => {
            crate::states::delete(&dir, id, online).await?;
            Value::Null
        }
        other => return Err(AppError::msg(format!("action inconnue : {other}"))),
    })
}

/// Partie à reprendre depuis une sauvegarde en ligne (moteur intégré, profil connecté).
pub async fn resumable_online(system: &Value, game: &Value) -> Value {
    let Ok(Plan::Builtin { core, .. }) = prepare(system, game) else { return Value::Null };
    let saves = account::saves(&game["id"]).await;
    let states: Vec<&Value> = saves.iter().filter(|s| s["kind"] == "state").collect();
    // Partie du cœur choisi, sinon la plus récente d'un autre cœur intégré du système (reprise avec lui).
    let same = states.iter().find(|s| account::same_save_core(s["core"].as_str().unwrap_or(""), &core));
    let other = || {
        let list = system_cores(system, game).map(|(l, _)| l).unwrap_or_default();
        states
            .iter()
            .filter(|s| list.iter().any(|c| account::same_save_core(s["core"].as_str().unwrap_or(""), c)))
            .max_by(|a, b| account::parse_date_ms(a["savedAt"].as_str().unwrap_or("")).total_cmp(&account::parse_date_ms(b["savedAt"].as_str().unwrap_or(""))))
    };
    match same.or_else(other) {
        Some(save) => json!({ "device": save["device"], "savedAt": save["savedAt"], "core": save["core"] }),
        None => Value::Null,
    }
}

/// Liaison entre consoles (netplay::Link) : ce jeu, avec le cœur de la liaison ; proposée (`join`
/// absent) ou reliée à l'hôte. Invité d'une liaison ouverte par le cœur : l'hôte accepte avant le lancement.
async fn play_link(system: &Value, game: &Value, link: &'static netplay::Link, join: Option<&Value>) -> Result<Value> {
    let main = library::file_for(system, game).to_string_lossy().into_owned();
    if !library::is_downloaded(system, game) {
        return Err(AppError::new("errors.notDownloaded", json!({ "file": main })));
    }
    let file = library::playlist(system, game).map(|p| p.to_string_lossy().into_owned()).unwrap_or(main);
    // Émulateur choisi (moteur intégré) : sa sauvegarde est reprise par le cœur de la liaison.
    let chosen = match prepare(system, game) {
        Ok(Plan::Builtin { core, .. }) => Some(core),
        _ => None,
    };
    let args = match join {
        None => netplay::link_host_args(system, game, link),
        Some(join) if link.packets => netplay::guest_link_join_args(join, system, game, link).await?,
        Some(join) => {
            let local = netplay::link_handshake(join, game, link).await?;
            netplay::linked_args(&local, link)
        }
    };
    builtin::launch(system, game, &file, link.core, false, args, chosen.as_deref()).await?;
    Ok(json!({ "manual": false }))
}

/// Jeu à plusieurs en réseau local : `{ host: true }` (partie proposée, émulateur choisi) ou
/// `{ join: { address, port, peerName, game } }` (partie rejointe, avec le cœur de l'hôte). Console
/// qui se relie (câble, ad hoc) : liaison proposée, ou `{ join: { …, link } }` (reliée avec ce jeu).
async fn play_together(system: &Value, game: &Value, together: &Value) -> Result<Value> {
    let needs_builtin = || AppError::new("errors.netplayBuiltin", json!({}));
    if let Some(join) = together.get("join").filter(|j| j.is_object()) {
        let link_id = join.get("link").and_then(Value::as_str).or_else(|| join["game"].get("link").and_then(Value::as_str));
        if let Some(link) = link_id.and_then(netplay::link_by_id) {
            return play_link(system, game, link, Some(join)).await;
        }
        let core = s(&join["game"], "core");
        if !netplay::core_allows(core) || !cores::valid_core(core) {
            return Err(needs_builtin());
        }
        let main = library::file_for(system, game).to_string_lossy().into_owned();
        if !library::is_downloaded(system, game) {
            return Err(AppError::new("errors.notDownloaded", json!({ "file": main })));
        }
        let file = library::playlist(system, game).map(|p| p.to_string_lossy().into_owned()).unwrap_or(main);
        builtin::launch(system, game, &file, core, false, netplay::guest_join_args(join).await?, None).await?;
        return Ok(json!({ "manual": false }));
    }
    let Some(core) = netplay::netplay_core(system) else {
        if let Some(link) = netplay::link_for(system) {
            return play_link(system, game, link, None).await;
        }
        return Err(needs_builtin());
    };
    match prepare(system, game)? {
        Plan::Builtin { core: chosen, file, .. } if chosen == core => {
            builtin::launch(system, game, &file, &core, false, netplay::host_args(system, game, &core), None).await?;
        }
        // Émulateur externe choisi : la partie à plusieurs se joue avec le moteur intégré disponible.
        _ => {
            let main = library::file_for(system, game).to_string_lossy().into_owned();
            let file = library::playlist(system, game).map(|p| p.to_string_lossy().into_owned()).unwrap_or(main);
            builtin::launch(system, game, &file, &core, false, netplay::host_args(system, game, &core), None).await?;
        }
    }
    Ok(json!({ "manual": false }))
}

/**
 * Lance le jeu (`resume` : reprend la partie sauvegardée, moteur intégré ; `netplay` : jeu à plusieurs ;
 * `disk` : jeu en plusieurs disques démarré sur ce disque, 0 pour le premier, et repris depuis la
 * dernière partie faite sur lui).
 */
pub async fn play(system: &Value, game: &Value, options: &Value) -> Result<Value> {
    let resume = options.get("resume").and_then(Value::as_bool).unwrap_or(false);
    if let Some(together) = options.get("netplay").filter(|n| n.is_object()) {
        return play_together(system, game, together).await;
    }
    let disk = options.get("disk").and_then(Value::as_u64).map(|d| d as usize).filter(|d| *d < library::discs(game).len());
    let plan = prepare(system, game)?;
    // Moteur intégré avec la liste des disques : disque inséré au démarrage (--disk). Sinon (autre
    // émulateur, disques compressés) : fichier du disque choisi à la place du premier.
    let player_discs = matches!(&plan, Plan::Builtin { core, file, .. } if managed::by_core(core).is_none() && paths::extension(file) == ".m3u");
    let single = disk.filter(|d| *d > 0 && !player_discs).map(|d| library::on_disc(game, d));
    let game = single.as_ref().unwrap_or(game);
    let plan = if single.is_some() { prepare(system, game)? } else { plan };
    let disk = disk.filter(|_| player_discs);
    match plan {
        Plan::Builtin { core, file, .. } if managed::by_core(&core).is_some() => {
            managed::launch(managed::by_core(&core).unwrap(), game, &file, &library::extras(system, game)).await?;
            Ok(json!({ "manual": false }))
        }
        Plan::Builtin { core, file, .. } => {
            // État de l'historique choisi dans la fiche du jeu (téléchargé s'il n'est qu'en ligne).
            let mut extra = Vec::new();
            // Cœur de l'état choisi, ou de la partie en ligne reprise (autre cœur intégré du système).
            let wanted = options.get("stateCore").or_else(|| options.get("resumeCore")).and_then(Value::as_str);
            // Reprise sans cœur demandé : celui de la partie la plus récente (autre cœur intégré possible),
            // sur le disque choisi.
            let latest = if resume && wanted.is_none() && options.get("state").is_none() { latest_resume(system, game, disk) } else { None };
            let core = match &latest {
                Some((latest_core, ..)) => latest_core.clone(),
                None => chosen_core(system, game, wanted).unwrap_or(core),
            };
            let history = builtin::history_dir(&core, &file);
            // Disque inséré au démarrage : celui de l'état chargé s'il est connu, sinon celui choisi.
            let mut start_disk = disk;
            if let Some(id) = options.get("state").and_then(Value::as_str) {
                let path = crate::states::file_for(&game["id"], &history, id).await?;
                start_disk = crate::states::disk_of(&path, &history);
                extra = vec!["--state-file".to_string(), path.to_string_lossy().into_owned()];
            } else if resume {
                // Reprise : état de l'historique s'il est plus récent que l'état de reprise (ou seul).
                let quick = builtin::state_path(&core, &file);
                let path = match latest {
                    Some((_, path, found)) => {
                        start_disk = found.or(disk);
                        Some(path)
                    }
                    None => {
                        let path = crate::states::resume_point_on(&quick, &history, disk).map(|p| p.0);
                        start_disk = path.as_ref().and_then(|p| crate::states::disk_of(p, &history)).or(disk);
                        path
                    }
                };
                if let Some(path) = path.filter(|p| *p != quick) {
                    extra = vec!["--state-file".to_string(), path.to_string_lossy().into_owned()];
                }
            }
            if let Some(d) = start_disk.filter(|d| *d > 0) {
                extra.extend(["--disk".to_string(), d.to_string()]);
            }
            builtin::launch(system, game, &file, &core, resume, extra, None).await?;
            Ok(json!({ "manual": false }))
        }
        Plan::Open(file) => {
            tauri_plugin_opener::open_path(&file, None::<&str>).map_err(|e| AppError::new("errors.noDefaultApp", json!({ "detail": e.to_string() })))?;
            Ok(json!({ "manual": false }))
        }
        Plan::Run { exe, args, emulator } => {
            // Clés de la Switch (Eden, Ryujinx)… copiées depuis le dossier des BIOS ; eden-cli :
            // lancé avec la configuration d'eden.exe (manettes).
            let args = match emulator {
                Some(id) => crate::emulator_files::prepare(id, &exe, args),
                None => args,
            };
            spawn_detached(&exe, &args, Some(game.clone()))?;
            Ok(json!({ "manual": false }))
        }
        Plan::Manual { exe, emulator, file } => {
            spawn_detached(&exe, &[], None)?;
            Ok(json!({ "manual": true, "emulator": emulator, "file": file }))
        }
        Plan::Vita { exe, step: vita3k::Step::Run(args) | vita3k::Step::InstallAndRun(args) } => {
            spawn_detached(&exe, &args, Some(game.clone()))?;
            Ok(json!({ "manual": false }))
        }
        Plan::Vita { exe, step: vita3k::Step::InstallPkg { install, run } } => {
            // Vita3K installe le .pkg puis quitte : le jeu est ensuite démarré.
            let mut command = tokio::process::Command::new(&exe);
            command.args(&install).current_dir(paths::dirname(&exe)).stdin(std::process::Stdio::null());
            let status = command.status().await.map_err(|e| AppError::new("errors.launchFailed", json!({ "detail": e.to_string() })))?;
            let id = run.last().cloned().unwrap_or_default();
            if !vita3k::is_installed(&exe, &id) {
                return Err(AppError::new("errors.vitaInstall", json!({ "code": status.code() })));
            }
            spawn_detached(&exe, &run, Some(game.clone()))?;
            Ok(json!({ "manual": false }))
        }
    }
}

/// Vita3K et ce jeu, pour la procédure de la fiche : { titleId, installed, format, license, appDir, exe }.
pub fn vita_status(system: &Value, game: &Value) -> Value {
    let exe = emulator_path("vita3k");
    let mut status = vita3k::status(exe.as_deref(), &library::file_for(system, game), game);
    status["exe"] = json!(exe);
    status
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ordre_des_emulateurs_choisi_sur_le_serveur() {
        let ids = |list: &[Value]| list.iter().map(|o| s(o, "id").to_string()).collect::<Vec<_>>();
        let options = |names: &[&str]| names.iter().map(|id| json!({ "id": id })).collect::<Vec<_>>();
        let system = json!({ "emulatorOrder": { "windows": ["emu:ryujinx", "builtin:duckstation", "builtin:pcsx_rearmed"], "android": ["x"] } });
        let order = server_order(&system);
        let mut list = options(&["builtin:mednafen_psx_hw", "builtin:pcsx_rearmed", "builtin:duckstation", "retroarch:duckstation", "emu:ryujinx"]);
        sort_by_order(&mut list, &order);
        assert_eq!(
            ids(&list),
            ["emu:ryujinx", "builtin:duckstation", "retroarch:duckstation", "builtin:pcsx_rearmed", "builtin:mednafen_psx_hw"]
        );
        // Sans ordre : celui de l'application.
        let mut list = options(&["b", "a"]);
        sort_by_order(&mut list, &server_order(&json!({ "id": "snes" })));
        assert_eq!(ids(&list), ["b", "a"]);
    }
}
