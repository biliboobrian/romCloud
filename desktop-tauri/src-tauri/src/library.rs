// ROMs sur le disque : <dossier RomCloud>\roms\<dossier du système>\<fichier>.
// Un jeu est téléchargé si ses fichiers existent avec la taille attendue : le fichier principal et
// ses parties (disques, mises à jour, DLC regroupés par le serveur, rangés à côté de lui).
use crate::{folders, paths};
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

pub fn s<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}

pub fn n(v: &Value, key: &str) -> u64 {
    v.get(key).and_then(Value::as_u64).unwrap_or(0)
}

pub fn system_dir(system: &Value) -> PathBuf {
    folders::roms().join(s(system, "folder"))
}

pub fn file_for(system: &Value, game: &Value) -> PathBuf {
    system_dir(system).join(s(game, "fileName"))
}

/// Parties du jeu (disques, mises à jour, DLC) : { id, fileName, size, kind, index }.
pub fn parts(game: &Value) -> Vec<Value> {
    game.get("parts").and_then(Value::as_array).cloned().unwrap_or_default()
}

/// Fichiers du jeu : le fichier principal puis ses parties, chacun avec son identifiant et sa taille.
pub fn game_files(game: &Value) -> Vec<Value> {
    std::iter::once(game.clone()).chain(parts(game)).collect()
}

/// Taille du jeu et de ses parties.
pub fn total_size(game: &Value) -> u64 {
    game_files(game).iter().map(|f| n(f, "size")).sum()
}

/// Jeu en dossier (PC Windows) : dossier du jeu avec tous ses fichiers, lancé par son exécutable.
pub fn is_folder(game: &Value) -> bool {
    game.get("folder").and_then(Value::as_bool).unwrap_or(false)
}

/// Jeu en dossier : fichier écrit à la fin du téléchargement, absent tant qu'il manque des fichiers.
pub fn folder_marker(system: &Value, game: &Value) -> PathBuf {
    file_for(system, game).join(MARKER)
}

const MARKER: &str = ".romcloud-complete";
const FILES_RECORD: &str = ".romcloud-files.json";

/**
 * Jeu en dossier : fichiers reçus avec la version téléchargée ({ chemin: [taille, date sur le
 * serveur] }), lus seulement à la mise à jour du jeu ; à part du marqueur, relu sans cesse (un jeu
 * compte parfois des dizaines de milliers de fichiers).
 */
pub fn folder_files_record(system: &Value, game: &Value) -> PathBuf {
    file_for(system, game).join(FILES_RECORD)
}

/// Marqueur plus gros que ceci : écrit par la version 1.89 (liste des fichiers comprise), allégé.
const MARKER_WITH_FILES: u64 = 64 * 1024;

/**
 * Fin du téléchargement d'un jeu en dossier : { size, stamp, version } (empreinte et version du jeu
 * téléchargé ; fichiers reçus : voir `folder_files_record`). Anciennes versions de l'application :
 * la taille seule, en texte ; version 1.89 : liste des fichiers comprise, rangée à part à la première
 * lecture. None : téléchargement pas terminé.
 */
pub fn read_folder_marker(system: &Value, game: &Value) -> Option<Value> {
    marker_in(&file_for(system, game))
}

/// Marqueur du jeu en dossier [dir] (voir `read_folder_marker`).
fn marker_in(dir: &Path) -> Option<Value> {
    let text = std::fs::read_to_string(dir.join(MARKER)).ok()?;
    let text = text.trim();
    if let Ok(size) = text.parse::<u64>() {
        return Some(json!({ "size": size }));
    }
    let mut value: Value = serde_json::from_str(text).ok()?;
    if text.len() as u64 > MARKER_WITH_FILES {
        if let Some(files) = value.as_object_mut().and_then(|m| m.remove("files")) {
            let _ = write_marker_in(dir, &value, &files);
        }
    }
    value.as_object_mut().map(|m| m.remove("files"));
    Some(value)
}

/// Fichiers reçus du jeu en dossier (voir `folder_files_record`) ; vide s'ils ne sont pas notés.
pub fn read_folder_files(system: &Value, game: &Value) -> Map<String, Value> {
    files_in(&file_for(system, game))
}

fn files_in(dir: &Path) -> Map<String, Value> {
    let from_record = std::fs::read(dir.join(FILES_RECORD)).ok().and_then(|b| serde_json::from_slice::<Map<String, Value>>(&b).ok());
    // Marqueur de la version 1.89, pas encore allégé : liste des fichiers comprise.
    from_record
        .or_else(|| {
            let text = std::fs::read_to_string(dir.join(MARKER)).ok()?;
            serde_json::from_str::<Value>(text.trim()).ok()?.get("files")?.as_object().cloned()
        })
        .unwrap_or_default()
}

/**
 * Note la fin du téléchargement d'un jeu en dossier : ses fichiers reçus [files], puis le marqueur
 * [marker] ({ size, stamp, version }), en dernier (sa présence : jeu complet).
 */
pub fn write_folder_marker(system: &Value, game: &Value, marker: &Value, files: &Value) -> std::io::Result<()> {
    write_marker_in(&file_for(system, game), marker, files)
}

fn write_marker_in(dir: &Path, marker: &Value, files: &Value) -> std::io::Result<()> {
    let record = dir.join(FILES_RECORD);
    let temp = record.with_extension("json.tmp");
    std::fs::write(&temp, files.to_string())?;
    std::fs::rename(&temp, &record)?;
    let path = dir.join(MARKER);
    let temp = path.with_extension("tmp");
    std::fs::write(&temp, marker.to_string())?;
    std::fs::rename(&temp, &path)
}

/// Jeu en dossier téléchargé sur le PC, dans sa version actuelle ou une plus ancienne (voir `update_available`).
fn folder_complete(system: &Value, game: &Value) -> bool {
    read_folder_marker(system, game).is_some()
}

/**
 * Jeu PC téléchargé dont les fichiers ont changé sur le serveur depuis (nouvelle version) : à mettre à
 * jour. Marqueur d'une ancienne version de l'application, ou serveur sans empreinte : par la taille.
 */
pub fn update_available(system: &Value, game: &Value) -> bool {
    if !is_folder(game) {
        return false;
    }
    read_folder_marker(system, game).is_some_and(|marker| marker_outdated(&marker, game))
}

/// Marqueur [marker] d'une version plus ancienne que celle du jeu [game] sur le serveur.
fn marker_outdated(marker: &Value, game: &Value) -> bool {
    match (marker.get("stamp").and_then(Value::as_str), game.get("contentStamp").and_then(Value::as_str)) {
        (Some(local), Some(remote)) => local != remote,
        _ => n(marker, "size") != n(game, "size"),
    }
}

/// Jeu en dossier sur le PC : { updateAvailable, localVersion } (version téléchargée, null : inconnue).
pub fn folder_status(system: &Value, game: &Value) -> Value {
    let local = read_folder_marker(system, game).and_then(|m| m.get("version").cloned()).unwrap_or(Value::Null);
    serde_json::json!({ "updateAvailable": update_available(system, game), "localVersion": local })
}

/// Exécutable d'un jeu en dossier sur le PC (None : aucun choisi sur le serveur).
pub fn folder_exe(system: &Value, game: &Value) -> Option<PathBuf> {
    folder_path(system, game, s(game, "exe"))
}

/// Désinstalleur d'un jeu en dossier sur le PC (None : aucun choisi sur le serveur).
pub fn folder_uninstaller(system: &Value, game: &Value) -> Option<PathBuf> {
    folder_path(system, game, s(game, "uninstallExe"))
}

/// Autre exécutable [rel] d'un jeu en dossier (prérequis : DirectX…), s'il est dans sa liste du serveur.
pub fn folder_extra(system: &Value, game: &Value, rel: &str) -> Option<PathBuf> {
    let listed = game["extraExes"].as_array().is_some_and(|list| list.iter().any(|e| e.as_str() == Some(rel)));
    if listed { folder_path(system, game, rel) } else { None }
}

/// Fichier [rel] (« Bin/Game.exe ») du dossier du jeu ; None si vide.
fn folder_path(system: &Value, game: &Value, rel: &str) -> Option<PathBuf> {
    (!rel.is_empty()).then(|| rel.split('/').fold(file_for(system, game), |path, part| path.join(part)))
}

pub fn is_downloaded(system: &Value, game: &Value) -> bool {
    if is_folder(game) {
        return folder_complete(system, game);
    }
    let dir = system_dir(system);
    game_files(game).iter().all(|f| paths::size(&dir.join(s(f, "fileName"))) == Some(n(f, "size")))
}

/// Identifiants des jeux présents (un seul listage du dossier par système).
pub fn downloaded_ids(systems: &[Value], games: &[Value]) -> Vec<Value> {
    let mut sizes: HashMap<String, u64> = HashMap::new();
    for system in systems {
        let Ok(entries) = std::fs::read_dir(system_dir(system)) else { continue };
        for entry in entries.flatten() {
            if let Ok(meta) = entry.metadata() {
                sizes.insert(format!("{}/{}", s(system, "id"), entry.file_name().to_string_lossy()), meta.len());
            }
        }
    }
    let by_id: HashMap<String, &Value> = systems.iter().map(|sys| (s(sys, "id").to_string(), sys)).collect();
    games
        .iter()
        .filter(|g| !is_folder(g) || by_id.get(s(g, "systemId")).is_some_and(|system| folder_complete(system, g)))
        .filter(|g| is_folder(g) || game_files(g).iter().all(|f| sizes.get(&format!("{}/{}", s(g, "systemId"), s(f, "fileName"))) == Some(&n(f, "size"))))
        .filter_map(|g| g.get("id").cloned())
        .collect()
}

/// Systèmes dont le dossier contient au moins un fichier de jeu (seuls affichés hors ligne).
pub fn systems_with_games(systems: &[Value]) -> Vec<Value> {
    systems
        .iter()
        .filter(|system| {
            std::fs::read_dir(system_dir(system))
                .map(|entries| {
                    entries.flatten().any(|e| e.file_type().map(|t| t.is_file()).unwrap_or(false) && !e.file_name().to_string_lossy().ends_with(".part"))
                })
                .unwrap_or(false)
        })
        .filter_map(|system| system.get("id").cloned())
        .collect()
}

/// Taille des fichiers d'un dossier, sous-dossiers compris (0 s'il n'existe pas).
fn dir_size(dir: &Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
    entries
        .flatten()
        .map(|e| match e.file_type() {
            Ok(t) if t.is_dir() => dir_size(&e.path()),
            Ok(t) if t.is_file() => e.metadata().map(|m| m.len()).unwrap_or(0),
            _ => 0,
        })
        .sum()
}

/// Place occupée sur le PC : { systems: { id: octets } (systèmes ayant des fichiers), disk: { free,
/// total } de la partition du dossier des ROMs, ou null }.
pub fn usage(systems: &[Value]) -> Value {
    let mut sizes = Map::new();
    for system in systems {
        let size = dir_size(&system_dir(system));
        if size > 0 {
            sizes.insert(s(system, "id").to_string(), json!(size));
        }
    }
    let disk = paths::disk_space(&folders::root()).map(|(free, total)| json!({ "free": free, "total": total }));
    json!({ "systems": sizes, "disk": disk })
}

/// Emplacement d'un fichier du système (BIOS, clés, firmware…) : voir folders.rs.
pub fn bios_file(system: &Value, bios: &Value) -> PathBuf {
    folders::bios_file(system, bios)
}

/// BIOS absents du PC (ou de taille différente) ; ceux de l'ancien dossier des BIOS repris d'abord.
pub fn missing_bios(system: &Value, files: Vec<Value>) -> Vec<Value> {
    files
        .into_iter()
        .filter(|b| {
            folders::adopt_legacy_bios(system, b);
            paths::size(&bios_file(system, b)) != Some(n(b, "size"))
        })
        .collect()
}

/// Supprime les fichiers du jeu, ses parties et sa liste de disques.
pub fn remove(system: &Value, game: &Value) {
    if is_folder(game) {
        let _ = std::fs::remove_dir_all(file_for(system, game));
        return;
    }
    let dir = system_dir(system);
    for f in game_files(game) {
        let _ = std::fs::remove_file(dir.join(s(&f, "fileName")));
    }
    let _ = std::fs::remove_file(playlist_path(&file_for(system, game)));
}

/// Liste des disques (.m3u) d'un jeu : nom du premier disque (sauvegardes et états du jeu gardent
/// leur nom), à côté de lui.
fn playlist_path(main: &Path) -> PathBuf {
    main.with_extension("m3u")
}

/// Disques du jeu, du premier au dernier : le fichier principal puis ses disques par numéro (le seul
/// fichier principal pour un jeu d'un disque).
pub fn discs(game: &Value) -> Vec<Value> {
    let mut discs: Vec<Value> = parts(game).into_iter().filter(|p| s(p, "kind") == "disc").collect();
    discs.sort_by_key(|p| p.get("index").and_then(Value::as_u64).unwrap_or(u64::MAX));
    std::iter::once(game.clone()).chain(discs).collect()
}

/**
 * Le jeu ramené à son disque [disk] (0 pour le premier) : fichier de ce disque à la place du premier,
 * sans les autres disques (émulateur qui ne lit pas la liste des disques, démarré sur ce disque).
 */
pub fn on_disc(game: &Value, disk: usize) -> Value {
    let list = discs(game);
    let Some(disc) = list.get(disk).filter(|_| disk > 0) else { return game.clone() };
    let mut single = game.clone();
    single["fileName"] = disc["fileName"].clone();
    single["size"] = disc["size"].clone();
    single["parts"] = json!(parts(game).into_iter().filter(|p| s(p, "kind") != "disc").collect::<Vec<_>>());
    single
}

/// Contenu de la liste des disques : un nom de fichier par ligne, du premier au dernier disque ;
/// None si le jeu n'a qu'un disque, ou si un disque est compressé (.zip, .7z : illisible dans une liste).
pub fn playlist_text(game: &Value) -> Option<String> {
    let discs = discs(game);
    if discs.len() < 2 {
        return None;
    }
    let names: Vec<String> = discs.iter().map(|d| s(d, "fileName").to_string()).collect();
    if names.iter().any(|name| matches!(paths::extension(name).as_str(), ".zip" | ".7z")) {
        return None;
    }
    Some(names.join("\n") + "\n")
}

/// Liste des disques écrite à côté du jeu (pour le moteur intégré, DuckStation… : changement
/// de disque en jeu) ; None pour un jeu d'un seul disque.
pub fn playlist(system: &Value, game: &Value) -> Option<PathBuf> {
    let text = playlist_text(game)?;
    let path = playlist_path(&file_for(system, game));
    if std::fs::read_to_string(&path).ok().as_deref() != Some(text.as_str()) {
        std::fs::write(&path, text).ok()?;
    }
    Some(path)
}

/// Mises à jour et DLC du jeu présents sur le PC (chemins), dans l'ordre du serveur.
pub fn extras(system: &Value, game: &Value) -> Vec<PathBuf> {
    let dir = system_dir(system);
    parts(game).iter().filter(|p| s(p, "kind") != "disc").map(|p| dir.join(s(p, "fileName"))).filter(|p| p.is_file()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn autres_executables_du_serveur_seulement() {
        let system = json!({ "id": "windows", "folder": "windows" });
        let game = json!({ "fileName": "Mr. Robot", "folder": true, "exe": "Game.exe", "extraExes": ["Redist/DXSETUP.exe"] });
        let dx = folder_extra(&system, &game, "Redist/DXSETUP.exe").unwrap();
        assert!(dx.ends_with(Path::new("Mr. Robot").join("Redist").join("DXSETUP.exe")));
        assert!(folder_extra(&system, &game, "Game.exe").is_none());
        assert!(folder_extra(&system, &json!({ "fileName": "Mr. Robot", "folder": true }), "Redist/DXSETUP.exe").is_none());
    }

    #[test]
    fn liste_des_disques() {
        let game = json!({ "fileName": "FF7 (Disc 1).chd", "size": 10, "parts": [
            { "id": 3, "fileName": "FF7 (Disc 3).chd", "size": 30, "kind": "disc", "index": 3 },
            { "id": 9, "fileName": "FF7 [DLC].chd", "size": 5, "kind": "dlc" },
            { "id": 2, "fileName": "FF7 (Disc 2).chd", "size": 20, "kind": "disc", "index": 2 },
        ]});
        assert_eq!(playlist_text(&game).as_deref(), Some("FF7 (Disc 1).chd\nFF7 (Disc 2).chd\nFF7 (Disc 3).chd\n"));
        assert_eq!(total_size(&game), 65);
        assert_eq!(game_files(&game).len(), 4);
        assert_eq!(playlist_text(&json!({ "fileName": "Tekken.chd", "size": 7 })), None);
        let zipped = json!({ "fileName": "A (Disc 1).zip", "parts": [{ "fileName": "A (Disc 2).zip", "kind": "disc", "index": 2 }] });
        assert_eq!(playlist_text(&zipped), None);
        assert_eq!(playlist_path(Path::new(r"D:\psx\FF7 (Disc 1).chd")), PathBuf::from(r"D:\psx\FF7 (Disc 1).m3u"));
        // Démarrage sur le disque 2 : son fichier seul, la DLC gardée.
        let second = on_disc(&game, 1);
        assert_eq!(s(&second, "fileName"), "FF7 (Disc 2).chd");
        assert_eq!(n(&second, "size"), 20);
        assert_eq!(parts(&second).len(), 1);
        assert_eq!(playlist_text(&second), None);
        assert_eq!(on_disc(&game, 0), game);
        assert_eq!(on_disc(&game, 7), game);
    }

    #[test]
    fn nouvelle_version_d_un_jeu_pc() {
        let game = json!({ "size": 120, "contentStamp": "120-99" });
        assert!(!marker_outdated(&json!({ "size": 120, "stamp": "120-99" }), &game));
        assert!(marker_outdated(&json!({ "size": 120, "stamp": "120-50" }), &game));
        // Ancien marqueur (taille seule) ou serveur sans empreinte : par la taille.
        assert!(!marker_outdated(&json!({ "size": 120 }), &game));
        assert!(marker_outdated(&json!({ "size": 100 }), &game));
        assert!(marker_outdated(&json!({ "size": 100, "stamp": "100-1" }), &json!({ "size": 120 })));
    }

    #[test]
    fn marqueur_d_un_jeu_pc_sans_la_liste_des_fichiers() {
        let dir = std::env::temp_dir().join(format!("romcloud-marker-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        assert_eq!(marker_in(&dir), None);
        // Version 1.89 : liste des fichiers dans le marqueur, rangée à part à la première lecture.
        let files: Map<String, Value> = (0..5000).map(|i| (format!("data/file{i}.dat"), json!([i, 1_700_000_000_000u64]))).collect();
        std::fs::write(dir.join(MARKER), json!({ "size": 9, "stamp": "9-1", "version": "1.0", "files": files }).to_string()).unwrap();
        assert_eq!(files_in(&dir).len(), 5000);
        assert_eq!(marker_in(&dir), Some(json!({ "size": 9, "stamp": "9-1", "version": "1.0" })));
        assert!(std::fs::metadata(dir.join(MARKER)).unwrap().len() < 100);
        assert_eq!(files_in(&dir), files);
        // Écrit par cette version, et anciennes versions (taille seule).
        write_marker_in(&dir, &json!({ "size": 3 }), &json!({ "a": [3, 0] })).unwrap();
        assert_eq!(marker_in(&dir), Some(json!({ "size": 3 })));
        assert_eq!(files_in(&dir).len(), 1);
        std::fs::write(dir.join(MARKER), "42").unwrap();
        assert_eq!(marker_in(&dir), Some(json!({ "size": 42 })));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn taille_d_un_dossier() {
        let dir = std::env::temp_dir().join(format!("romcloud-usage-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("sous")).unwrap();
        std::fs::write(dir.join("a.chd"), [0u8; 100]).unwrap();
        std::fs::write(dir.join("sous").join("b.chd"), [0u8; 23]).unwrap();
        assert_eq!(dir_size(&dir), 123);
        assert_eq!(dir_size(&dir.join("absent")), 0);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
