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
use crate::{account, builtin, cores, library, managed, netplay, paths, settings};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};

// ---------------------------------------------------------------------------
// Émulateurs du catalogue : emplacement sur le PC et ligne de commande
// ---------------------------------------------------------------------------

fn setting_map(key: &str) -> serde_json::Map<String, Value> {
    settings::load().get(key).and_then(Value::as_object).cloned().unwrap_or_default()
}

/// Chemin de l'exécutable : choisi par l'utilisateur ou trouvé par la dernière recherche.
fn emulator_path(id: &str) -> Option<String> {
    setting_map("emulatorPaths").get(id).and_then(Value::as_str).filter(|f| !f.is_empty() && Path::new(f).exists()).map(String::from)
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
            paths_map.insert(id, Value::String(file));
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
                json!({
                    "id": emu.id,
                    "name": emu.name,
                    "systems": emu.systems,
                    "url": emu.url,
                    "path": exe,
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
    if catalog::by_id(id).is_none() {
        return Err(AppError::new("errors.unknownEmulator", json!({ "id": id })));
    }
    let mut map = setting_map("emulatorPaths");
    map.insert(id.into(), Value::String(file.into()));
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
fn spawn_detached(exe: &str, args: &[String], game: Option<Value>) -> Result<()> {
    let dir = paths::dirname(exe);
    let mut command = tokio::process::Command::new(exe);
    command.args(args).stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
    if Path::new(&dir).exists() {
        command.current_dir(&dir);
    }
    let mut child = command.spawn().map_err(|e| AppError::new("errors.launchFailed", json!({ "detail": e.to_string() })))?;
    paths::allow_foreground(child.id());
    let started = paths::now_ms();
    tauri::async_runtime::spawn(async move {
        let _ = child.wait().await;
        if let Some(game) = game {
            account::add_playtime(game["id"].clone(), (paths::now_ms() - started) / 1000.0).await;
        }
    });
    Ok(())
}

/// Ouvre l'émulateur seul (sans jeu).
pub fn launch_emulator(id: &str) -> Result<()> {
    let emu = catalog::by_id(id).ok_or_else(|| AppError::new("errors.unknownEmulator", json!({ "id": id })))?;
    let exe = emulator_path(id).ok_or_else(|| AppError::new("errors.emulatorMissing", json!({ "name": emu.name, "id": id })))?;
    crate::emulator_files::install(emu.id, &exe);
    spawn_detached(&exe, &[], None)
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
    for core in &system_cores {
        list.push(json!({ "id": format!("retroarch:{core}"), "kind": "retroarch", "core": core }));
    }
    for emu in catalog::for_system(system) {
        list.push(json!({ "id": format!("emu:{}", emu.id), "kind": "emulator", "emuId": emu.id, "name": emu.name, "installed": emulator_path(emu.id).is_some() }));
    }
    list.push(json!({ "id": "custom", "kind": "custom" }));
    list.push(json!({ "id": "default", "kind": "default" }));
    let saved = settings::load().get("emulators").and_then(|e| e.get(s(system, "id"))).cloned().unwrap_or(Value::Null);
    let kind = |o: &Value, k: &str| o["kind"] == k;
    let fallback = list
        .iter()
        .find(|o| kind(o, "builtin"))
        .or_else(|| if retroarch_ok() { list.iter().find(|o| kind(o, "retroarch")) } else { None })
        .or_else(|| list.iter().find(|o| kind(o, "emulator") && o["installed"] == true))
        .or_else(|| list.iter().find(|o| kind(o, "retroarch") || kind(o, "emulator")))
        .or_else(|| list.iter().find(|o| kind(o, "default")))
        .map(|o| o["id"].clone())
        .unwrap_or(Value::Null);
    let selected = if list.iter().any(|o| o["id"] == saved["option"] && !saved["option"].is_null()) { saved["option"].clone() } else { fallback };
    json!({ "options": list, "selected": selected, "command": saved["command"].as_str().unwrap_or("") })
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
    Open(String),
}

/// Émulateurs du catalogue qui lisent une liste de disques (.m3u).
const PLAYLIST_EMULATORS: &[&str] = &["duckstation", "mednafen"];

/// Vérifications avant lancement (émulateur installé, cœur présent…).
fn prepare(system: &Value, game: &Value) -> Result<Plan> {
    let file = library::file_for(system, game).to_string_lossy().into_owned();
    if !library::is_downloaded(system, game) {
        return Err(AppError::new("errors.notDownloaded", json!({ "file": file })));
    }
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

/// Partie à reprendre : moteur intégré choisi et état sauvegardé pour ce jeu et ce cœur.
pub fn resumable(system: &Value, game: &Value) -> bool {
    match prepare(system, game) {
        Ok(Plan::Builtin { core, file, .. }) => builtin::state_path(&core, &file).exists(),
        _ => false,
    }
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

/// Historique des états du jeu : { states, online, signedIn } (null : pas le moteur intégré).
pub async fn states(system: &Value, game: &Value) -> Value {
    match history(system, game) {
        Some((dir, core)) => crate::states::list(&game["id"], &core, &dir).await,
        None => Value::Null,
    }
}

/// Opération sur un état de l'historique : « thumbnail », « pin », « unpin », « upload », « delete ».
pub async fn state_action(system: &Value, game: &Value, action: &str, state: &Value) -> Result<Value> {
    let (dir, _) = history(system, game).ok_or_else(|| AppError::msg("historique indisponible".to_string()))?;
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
    match saves.iter().find(|s| account::same_save_core(s["core"].as_str().unwrap_or(""), &core) && s["kind"] == "state") {
        Some(save) => json!({ "device": save["device"], "savedAt": save["savedAt"] }),
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
    if let Some(link) = netplay::link_for(system).filter(|_| !netplay::system_allows(system)) {
        return play_link(system, game, link, None).await;
    }
    match prepare(system, game)? {
        Plan::Builtin { core, file, .. } if netplay::core_allows(&core) => {
            builtin::launch(system, game, &file, &core, false, netplay::host_args(system, game, &core), None).await?;
            Ok(json!({ "manual": false }))
        }
        _ => Err(needs_builtin()),
    }
}

/// Lance le jeu (`resume` : reprend la partie sauvegardée, moteur intégré ; `netplay` : jeu à plusieurs).
pub async fn play(system: &Value, game: &Value, options: &Value) -> Result<Value> {
    let resume = options.get("resume").and_then(Value::as_bool).unwrap_or(false);
    if let Some(together) = options.get("netplay").filter(|n| n.is_object()) {
        return play_together(system, game, together).await;
    }
    match prepare(system, game)? {
        Plan::Builtin { core, file, .. } if managed::by_core(&core).is_some() => {
            managed::launch(managed::by_core(&core).unwrap(), game, &file, &library::extras(system, game)).await?;
            Ok(json!({ "manual": false }))
        }
        Plan::Builtin { core, file, .. } => {
            // État de l'historique choisi dans la fiche du jeu (téléchargé s'il n'est qu'en ligne).
            let mut extra = Vec::new();
            if let Some(id) = options.get("state").and_then(Value::as_str) {
                let path = crate::states::file_for(&game["id"], &builtin::history_dir(&core, &file), id).await?;
                extra = vec!["--state-file".to_string(), path.to_string_lossy().into_owned()];
            }
            builtin::launch(system, game, &file, &core, resume, extra, None).await?;
            Ok(json!({ "manual": false }))
        }
        Plan::Open(file) => {
            tauri_plugin_opener::open_path(&file, None::<&str>).map_err(|e| AppError::new("errors.noDefaultApp", json!({ "detail": e.to_string() })))?;
            Ok(json!({ "manual": false }))
        }
        Plan::Run { exe, args, emulator } => {
            // Clés de la Switch (Eden, Ryujinx)… copiées depuis le dossier des BIOS.
            if let Some(id) = emulator {
                crate::emulator_files::install(id, &exe);
            }
            spawn_detached(&exe, &args, Some(game.clone()))?;
            Ok(json!({ "manual": false }))
        }
        Plan::Manual { exe, emulator, file } => {
            spawn_detached(&exe, &[], None)?;
            Ok(json!({ "manual": true, "emulator": emulator, "file": file }))
        }
    }
}
