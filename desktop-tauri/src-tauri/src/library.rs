// ROMs sur le disque : <dossier ROMs>\<dossier du système>\<fichier>.
// Un jeu est téléchargé si le fichier existe avec la taille attendue.
use crate::{paths, settings};
use serde_json::Value;
use std::collections::HashMap;
use std::path::PathBuf;

pub fn s<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}

pub fn n(v: &Value, key: &str) -> u64 {
    v.get(key).and_then(Value::as_u64).unwrap_or(0)
}

pub fn system_dir(system: &Value) -> PathBuf {
    PathBuf::from(settings::get_str("romsDir")).join(s(system, "folder"))
}

pub fn file_for(system: &Value, game: &Value) -> PathBuf {
    system_dir(system).join(s(game, "fileName"))
}

pub fn is_downloaded(system: &Value, game: &Value) -> bool {
    paths::size(&file_for(system, game)) == Some(n(game, "size"))
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
    games
        .iter()
        .filter(|g| sizes.get(&format!("{}/{}", s(g, "systemId"), s(g, "fileName"))) == Some(&n(g, "size")))
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

/// Emplacement d'un BIOS : <dossier BIOS>\<chemin relatif, sous-dossiers compris>.
pub fn bios_file(bios: &Value) -> PathBuf {
    let mut path = settings::bios_dir();
    for part in s(bios, "path").split('/').filter(|p| !p.is_empty() && *p != "." && *p != "..") {
        path.push(part);
    }
    path
}

/// BIOS absents du PC (ou de taille différente).
pub fn missing_bios(files: Vec<Value>) -> Vec<Value> {
    files.into_iter().filter(|b| paths::size(&bios_file(b)) != Some(n(b, "size"))).collect()
}

pub fn remove(system: &Value, game: &Value) {
    let _ = std::fs::remove_file(file_for(system, game));
}
