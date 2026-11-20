// Page du stockage : place occupée sur le PC par chaque système, décomposée par catégorie, et
// suppression des données d'un système (catégories choisies).
//  - games     : roms\<dossier du système> ;
//  - emulators : cœurs libretro du système (libretro\cores, %APPDATA%), émulateur géré par RomCloud
//                (RPCS3, shadPS4, Cemu… : programme, configuration et données dans %APPDATA%\RomCloud\<émulateur>) ;
//                les émulateurs installés par l'utilisateur (Program Files…) ne sont pas comptés ;
//  - bios      : bios\<dossier du système>, system\<console> et fichiers « system » de la liste du serveur ;
//  - drivers   : pilotes de la liste du serveur (zone « drivers ») ;
//  - saves     : saves\<cœur>, states\<cœur> des cœurs du système : tout le dossier si le cœur n'est
//                utilisé par aucun autre système, sinon les fichiers nommés comme un jeu du système.
// Un cœur ou un pilote commun à plusieurs systèmes est compté pour chacun (re-téléchargé à la demande).
use crate::library::{s, system_dir};
use crate::{api, builtin, cores, folders, managed, paths};
use serde_json::{json, Map, Value};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

pub const KINDS: [&str; 5] = ["games", "emulators", "bios", "drivers", "saves"];

/// Taille d'un fichier ou d'un dossier (sous-dossiers compris) ; 0 s'il n'existe pas.
fn size_of(path: &Path) -> u64 {
    let Ok(meta) = std::fs::symlink_metadata(path) else { return 0 };
    if meta.is_file() {
        return meta.len();
    }
    if !meta.is_dir() {
        return 0;
    }
    std::fs::read_dir(path).into_iter().flatten().flatten().map(|e| size_of(&e.path())).sum()
}

/// Nom sans extension (« Zelda (France).srm » → « Zelda (France) »), en minuscules.
fn stem_of(name: &str) -> String {
    paths::stem(name).to_lowercase()
}

/// Dossier de la console dans la zone « system » (system\switch…), d'après l'identifiant du système.
fn system_area(system: &Value) -> Option<PathBuf> {
    [s(system, "id"), s(system, "shortname")].into_iter().filter(|k| !k.is_empty()).map(|k| folders::system().join(k)).find(|d| d.is_dir())
}

/// Chemins de chaque catégorie pour le système (sans doublon, présents ou non).
fn paths_of(system: &Value, systems: &[Value]) -> Vec<(&'static str, Vec<PathBuf>)> {
    let system_cores = cores::cores(system);
    let mut emulators: Vec<PathBuf> = system_cores.iter().flat_map(|c| builtin::core_files(c)).collect();
    if let Some(app) = managed::for_system(system) {
        emulators.push(app.dir());
    }

    let mut bios = vec![folders::bios(system)];
    let area = system_area(system);
    bios.extend(area.clone());
    let mut drivers = Vec::new();
    for file in api::cached_bios(system) {
        let path = folders::bios_file(system, &file);
        match folders::zone(&file) {
            "drivers" => drivers.push(path),
            "system" if !area.as_ref().is_some_and(|a| path.starts_with(a)) => bios.push(path),
            _ => {}
        }
    }

    // Sauvegardes : noms des jeux du système (fichiers et dossiers de roms\<dossier>).
    let games_dir = system_dir(system);
    let stems: HashSet<String> = std::fs::read_dir(&games_dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| stem_of(&e.file_name().to_string_lossy()))
        .collect();
    let others: Vec<&Value> = systems.iter().filter(|o| s(o, "id") != s(system, "id")).collect();
    let mut saves = Vec::new();
    for core in &system_cores {
        let shared = others.iter().any(|o| cores::cores(o).contains(core));
        for dir in [folders::saves().join(core), folders::states().join(core)] {
            if !shared {
                saves.push(dir);
                continue;
            }
            for entry in std::fs::read_dir(&dir).into_iter().flatten().flatten() {
                if stems.contains(&stem_of(&entry.file_name().to_string_lossy())) {
                    saves.push(entry.path());
                }
            }
        }
    }

    let dedup = |list: Vec<PathBuf>| {
        let mut seen = HashSet::new();
        list.into_iter().filter(|p| seen.insert(p.clone())).collect::<Vec<_>>()
    };
    vec![
        ("games", vec![games_dir]),
        ("emulators", dedup(emulators)),
        ("bios", dedup(bios)),
        ("drivers", dedup(drivers)),
        ("saves", dedup(saves)),
    ]
}

/**
 * Place occupée par système et par catégorie : { systems: { id: { games, emulators, bios, drivers,
 * saves } } (systèmes ayant des données), disk: { free, total } de la partition du dossier RomCloud }.
 */
pub fn details(systems: &[Value]) -> Value {
    let mut out = Map::new();
    for system in systems {
        let sizes: Map<String, Value> = paths_of(system, systems)
            .into_iter()
            .map(|(kind, list)| (kind.to_string(), json!(list.iter().map(|p| size_of(p)).sum::<u64>())))
            .collect();
        if sizes.values().any(|v| v.as_u64().unwrap_or(0) > 0) {
            out.insert(s(system, "id").to_string(), Value::Object(sizes));
        }
    }
    let disk = paths::disk_space(&folders::root()).map(|(free, total)| json!({ "free": free, "total": total }));
    json!({ "systems": out, "disk": disk })
}

/// Supprime les données du système pour les catégories [kinds] ; renvoie le nombre d'octets libérés.
pub fn clear(system: &Value, kinds: &[Value], systems: &[Value]) -> u64 {
    let wanted: Vec<&str> = kinds.iter().filter_map(Value::as_str).filter(|k| KINDS.contains(k)).collect();
    let mut freed = 0;
    for (kind, list) in paths_of(system, systems) {
        if !wanted.contains(&kind) {
            continue;
        }
        for path in list {
            let size = size_of(&path);
            let removed = if path.is_dir() { std::fs::remove_dir_all(&path) } else { std::fs::remove_file(&path) };
            if removed.is_ok() {
                freed += size;
            }
        }
    }
    freed
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn taille_d_un_dossier() {
        let dir = std::env::temp_dir().join(format!("romcloud-storage-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("sub")).unwrap();
        std::fs::write(dir.join("a.bin"), [0u8; 10]).unwrap();
        std::fs::write(dir.join("sub").join("b.bin"), [0u8; 5]).unwrap();
        assert_eq!(size_of(&dir), 15);
        assert_eq!(size_of(&dir.join("a.bin")), 10);
        assert_eq!(size_of(&dir.join("absent")), 0);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn nom_des_sauvegardes() {
        assert_eq!(stem_of("Super Mario Bros. 3.srm"), stem_of("Super Mario Bros. 3.nes"));
        assert_eq!(stem_of("Zelda (France).history"), "zelda (france)");
    }
}
