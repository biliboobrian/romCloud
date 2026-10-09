// ROMs sur le disque : <dossier ROMs>\<dossier du système>\<fichier>.
// Un jeu est téléchargé si ses fichiers existent avec la taille attendue : le fichier principal et
// ses parties (disques, mises à jour, DLC regroupés par le serveur, rangés à côté de lui).
use crate::{paths, settings};
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};

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

pub fn is_downloaded(system: &Value, game: &Value) -> bool {
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
    games
        .iter()
        .filter(|g| game_files(g).iter().all(|f| sizes.get(&format!("{}/{}", s(g, "systemId"), s(f, "fileName"))) == Some(&n(f, "size"))))
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

/// Supprime les fichiers du jeu, ses parties et sa liste de disques.
pub fn remove(system: &Value, game: &Value) {
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

/// Contenu de la liste des disques : un nom de fichier par ligne, du premier au dernier disque ;
/// None si le jeu n'a qu'un disque, ou si un disque est compressé (.zip, .7z : illisible dans une liste).
pub fn playlist_text(game: &Value) -> Option<String> {
    let mut discs: Vec<Value> = parts(game).into_iter().filter(|p| s(p, "kind") == "disc").collect();
    if discs.is_empty() {
        return None;
    }
    discs.sort_by_key(|p| p.get("index").and_then(Value::as_u64).unwrap_or(u64::MAX));
    let names: Vec<String> = std::iter::once(game.clone()).chain(discs).map(|d| s(&d, "fileName").to_string()).collect();
    if names.iter().any(|name| matches!(paths::extension(name).as_str(), ".zip" | ".7z")) {
        return None;
    }
    Some(names.join("\n") + "\n")
}

/// Liste des disques écrite à côté du jeu (pour le moteur intégré, RetroArch, DuckStation… : changement
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
    }
}
