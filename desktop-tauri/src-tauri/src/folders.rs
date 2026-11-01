// Dossier RomCloud choisi dans les Paramètres (par défaut Documents\RomCloud) et ses sous-dossiers :
//   roms\<dossier du système>\…    jeux (jeux PC : roms\windows\<jeu>\)
//   bios\<dossier du système>\…    BIOS et données des cœurs libretro : dossier système du cœur
//   system\<console>\<type>\…      clés, firmwares et données des émulateurs sans cœur libretro
//                                  (system\switch\keys\prod.keys, system\ps3\firmware\PS3UPDAT.PUP)
//   saves\<cœur>\, states\<cœur>\  sauvegardes et états du moteur intégré
// Emplacement d'un fichier du serveur : sa zone (« area ») et son chemin dans la zone (« devicePath »),
// donnés par le serveur. Les cœurs téléchargés, leurs options et le journal restent dans %APPDATA%\RomCloud.
//
// Ancienne organisation (dossier des ROMs avec un sous-dossier par système, dossier des BIOS commun,
// sauvegardes et états dans %APPDATA%) : déplacée une fois ([migrate]) ; les BIOS de l'ancien dossier
// sont repris à leur place quand un système les demande ([adopt_legacy_bios]).
use crate::library::s;
use crate::{paths, settings};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};

const ROMS: &str = "roms";
const BIOS: &str = "bios";
const SYSTEM: &str = "system";
const SAVES: &str = "saves";
const STATES: &str = "states";
const DRIVERS: &str = "drivers";
/// Ancien dossier des BIOS quand il était déjà « bios » dans le dossier RomCloud (renommé).
const LEGACY_BIOS: &str = "bios.old";
/// Noms réservés du dossier RomCloud : pas des dossiers de systèmes de l'ancienne organisation.
const RESERVED: [&str; 7] = [ROMS, BIOS, SYSTEM, SAVES, STATES, DRIVERS, LEGACY_BIOS];

fn default_root() -> PathBuf {
    paths::documents().join("RomCloud")
}

/// Dossier RomCloud : choisi, sinon l'ancien dossier des ROMs, sinon Documents\RomCloud.
pub fn root() -> PathBuf {
    for key in ["rootDir", "romsDir"] {
        let dir = settings::get_str(key);
        if !dir.trim().is_empty() {
            return PathBuf::from(dir.trim());
        }
    }
    default_root()
}

pub fn roms() -> PathBuf {
    root().join(ROMS)
}

/// Dossier système du cœur libretro pour ce système (BIOS et données des cœurs).
pub fn bios(system: &Value) -> PathBuf {
    root().join(BIOS).join(s(system, "folder"))
}

pub fn bios_root() -> PathBuf {
    root().join(BIOS)
}

/// Zone « system » : clés, firmwares et données des émulateurs sans cœur libretro.
pub fn system() -> PathBuf {
    root().join(SYSTEM)
}

pub fn saves() -> PathBuf {
    root().join(SAVES)
}

pub fn states() -> PathBuf {
    root().join(STATES)
}

/// nvngx_dlss.dll (filtre DLSS du moteur intégré, téléchargé par lui) : %APPDATA%\RomCloud\libretro\dlss.
pub fn dlss() -> PathBuf {
    paths::user_data().join("libretro").join("dlss")
}

/// Dossiers des systèmes connus (dernière liste du serveur gardée hors ligne) ; None sans liste.
fn known_system_folders() -> Option<Vec<String>> {
    let text = std::fs::read_to_string(paths::user_data().join("api-cache").join("systems.json")).ok()?;
    let list: Vec<Value> = serde_json::from_str(&text).ok()?;
    Some(list.iter().map(|system| s(system, "folder").to_lowercase()).filter(|f| !f.is_empty()).collect())
}

/// Chemin relatif sûr (sans « .. » ni racine) ajouté à [base].
fn under(base: PathBuf, rel: &str) -> PathBuf {
    let mut path = base;
    for part in rel.split(['/', '\\']).filter(|p| !p.is_empty() && *p != "." && *p != "..") {
        path.push(part);
    }
    path
}

/// Zone et chemin dans la zone d'un fichier du serveur ; serveur plus ancien (sans zone) : « bios »,
/// pilotes « drivers/… » dans leur zone.
fn location(bios: &Value) -> (&str, String) {
    let path = s(bios, "path");
    match s(bios, "area") {
        "" if path.starts_with("drivers/") => (DRIVERS, path.trim_start_matches("drivers/").to_string()),
        "" => (BIOS, path.to_string()),
        area => (area, s(bios, "devicePath").to_string()),
    }
}

/// Emplacement d'un fichier du serveur (BIOS, clés, firmware…) pour le système [system].
pub fn bios_file(system: &Value, bios: &Value) -> PathBuf {
    match location(bios) {
        (SYSTEM, rel) => under(self::system(), &rel),
        (DRIVERS, rel) => under(root().join(DRIVERS), &rel),
        (_, rel) => under(self::bios(system), &rel),
    }
}

/// Ancien dossier des BIOS (organisation d'avant), s'il existe encore.
fn legacy_bios_dir() -> Option<PathBuf> {
    let dir = settings::get_str("legacyBiosDir");
    (!dir.is_empty()).then(|| PathBuf::from(dir)).filter(|d| d.is_dir())
}

/**
 * Fichier [bios] absent de sa place mais présent (même taille) dans l'ancien dossier des BIOS :
 * déplacé s'il était dans le dossier RomCloud (« bios.old »), copié sinon (dossier d'un autre programme).
 */
pub fn adopt_legacy_bios(system: &Value, bios: &Value) {
    let target = bios_file(system, bios);
    let size = bios.get("size").and_then(Value::as_u64);
    if paths::size(&target) == size {
        return;
    }
    let Some(legacy) = legacy_bios_dir() else { return };
    let source = under(legacy.clone(), s(bios, "path"));
    if paths::size(&source) != size {
        return;
    }
    if let Some(dir) = target.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let moved = legacy.starts_with(root()) && std::fs::rename(&source, &target).is_ok();
    if !moved {
        let _ = std::fs::copy(&source, &target);
    }
}

/// Déplace [from] dans [to] (renommage, sinon copie puis suppression : autre disque) ; un fichier
/// déjà présent à l'arrivée est gardé. Renvoie faux si quelque chose n'a pas pu être déplacé.
fn move_tree(from: &Path, to: &Path) -> bool {
    if !from.exists() {
        return true;
    }
    if !to.exists() {
        if let Some(parent) = to.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if std::fs::rename(from, to).is_ok() {
            return true;
        }
    }
    if from.is_file() {
        let copied = to.exists() || std::fs::copy(from, to).is_ok();
        return copied && std::fs::remove_file(from).is_ok();
    }
    let _ = std::fs::create_dir_all(to);
    let mut ok = true;
    for entry in std::fs::read_dir(from).into_iter().flatten().flatten() {
        ok &= move_tree(&entry.path(), &to.join(entry.file_name()));
    }
    ok && std::fs::remove_dir(from).is_ok()
}

/**
 * Passage à l'organisation du dossier RomCloud, une fois (au démarrage) : dossiers des systèmes de
 * l'ancien dossier des ROMs déplacés dans roms\, ancien dossier des BIOS gardé pour reprendre ses
 * fichiers (« bios » du dossier RomCloud renommé « bios.old »), sauvegardes et états du moteur
 * intégré déplacés de %APPDATA%. Recommencé au démarrage suivant si un déplacement a échoué.
 */
pub fn migrate() {
    let current = settings::load();
    if current.get("foldersVersion").and_then(Value::as_u64).unwrap_or(0) >= 2 {
        return;
    }
    let root = root();
    let old_roms = PathBuf::from(settings::get_str("romsDir")).to_path_buf();
    let old_roms = if old_roms.as_os_str().is_empty() { default_root() } else { old_roms };
    let chosen_bios = settings::get_str("biosDir");
    let mut legacy_bios = if chosen_bios.trim().is_empty() { default_root().join(BIOS) } else { PathBuf::from(chosen_bios.trim()) };
    let mut ok = true;
    // Ancien dossier des BIOS à l'emplacement des nouveaux : renommé, ses fichiers repris un à un.
    if legacy_bios == root.join(BIOS) && legacy_bios.is_dir() && !root.join(LEGACY_BIOS).exists() {
        ok &= std::fs::rename(&legacy_bios, root.join(LEGACY_BIOS)).is_ok();
        legacy_bios = root.join(LEGACY_BIOS);
    } else if root.join(LEGACY_BIOS).is_dir() {
        legacy_bios = root.join(LEGACY_BIOS);
    }
    // Dossiers des systèmes seulement (liste du serveur) : pas les dossiers créés à côté par les cœurs.
    let systems = known_system_folders();
    for entry in std::fs::read_dir(&old_roms).into_iter().flatten().flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let path = entry.path();
        let lower = name.to_lowercase();
        let system = systems.as_ref().map_or(true, |list| list.contains(&lower));
        if !path.is_dir() || name.starts_with('.') || RESERVED.contains(&lower.as_str()) || path == legacy_bios || !system {
            continue;
        }
        ok &= move_tree(&path, &root.join(ROMS).join(&name));
    }
    // Filtre DLSS rangé à côté de l'ancien dossier des BIOS.
    if let Some(old_dlss) = legacy_bios.parent().map(|p| p.join("dlss")).filter(|d| d.join("nvngx_dlss.dll").is_file()) {
        ok &= move_tree(&old_dlss, &dlss());
    }
    let engine = paths::user_data().join("libretro");
    ok &= move_tree(&engine.join(SAVES), &root.join(SAVES));
    ok &= move_tree(&engine.join(STATES), &root.join(STATES));
    let legacy = if legacy_bios.is_dir() { legacy_bios.to_string_lossy().into_owned() } else { String::new() };
    let mut patch = json!({ "rootDir": root.to_string_lossy(), "legacyBiosDir": legacy });
    if ok {
        patch["foldersVersion"] = json!(2);
    }
    settings::save(patch);
    eprintln!("[folders] organisation du dossier {} : {}", root.display(), if ok { "terminée" } else { "incomplète, reprise au prochain démarrage" });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emplacement_des_fichiers_du_serveur() {
        let system = json!({ "folder": "switch" });
        let rel = |b: Value| bios_file(&system, &b).strip_prefix(root()).unwrap().to_string_lossy().replace('\\', "/");
        assert_eq!(rel(json!({ "path": "switch/prod.keys", "area": "system", "devicePath": "switch/keys/prod.keys" })), "system/switch/keys/prod.keys");
        assert_eq!(rel(json!({ "path": "dc/dc_boot.bin", "area": "bios", "devicePath": "dc/dc_boot.bin" })), "bios/switch/dc/dc_boot.bin");
        // Serveur sans zone : BIOS, pilotes dans leur dossier ; chemins sûrs seulement.
        assert_eq!(rel(json!({ "path": "drivers/turnip.zip" })), "drivers/turnip.zip");
        assert_eq!(rel(json!({ "path": "../../x/scph5501.bin" })), "bios/switch/x/scph5501.bin");
    }

    #[test]
    fn deplacement_d_un_dossier_avec_fichiers_deja_presents() {
        let base = std::env::temp_dir().join(format!("romcloud-folders-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);
        std::fs::create_dir_all(base.join("a").join("sub")).unwrap();
        std::fs::write(base.join("a").join("sub").join("f.bin"), "neuf").unwrap();
        std::fs::write(base.join("a").join("g.bin"), "g").unwrap();
        std::fs::create_dir_all(base.join("b").join("sub")).unwrap();
        std::fs::write(base.join("b").join("sub").join("f.bin"), "gardé").unwrap();
        assert!(move_tree(&base.join("a"), &base.join("b")));
        assert!(!base.join("a").exists());
        assert_eq!(std::fs::read_to_string(base.join("b").join("sub").join("f.bin")).unwrap(), "gardé");
        assert_eq!(std::fs::read_to_string(base.join("b").join("g.bin")).unwrap(), "g");
        let _ = std::fs::remove_dir_all(&base);
    }
}
