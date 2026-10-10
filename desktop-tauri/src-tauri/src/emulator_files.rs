// Fichiers du dossier des BIOS (récupérés sur le serveur) installés là où les émulateurs du
// catalogue les attendent, avant leur lancement : clés de la Switch pour Eden et Ryujinx (mode
// portable reconnu à son dossier « user » ou « portable » à côté de l'exécutable).
use crate::{paths, settings};
use std::path::{Path, PathBuf};

/// Fichiers à installer pour l'émulateur : (fichier du dossier des BIOS, destination).
fn files_for(emulator: &str, exe: &Path, bios: &Path, appdata: &Path) -> Vec<(PathBuf, PathBuf)> {
    let exe_dir = exe.parent().map(Path::to_path_buf).unwrap_or_default();
    let keys_dir = match emulator {
        "eden" if exe_dir.join("user").is_dir() => exe_dir.join("user").join("keys"),
        "eden" => appdata.join("eden").join("keys"),
        "ryujinx" if exe_dir.join("portable").is_dir() => exe_dir.join("portable").join("system"),
        "ryujinx" => appdata.join("Ryujinx").join("system"),
        _ => return vec![],
    };
    ["prod.keys", "title.keys"].iter().map(|name| (bios.join("switch").join(name), keys_dir.join(name))).collect()
}

/// Copie [from] sur [to] s'il est plus récent (ou absent) ; renvoie vrai si copié.
fn copy_if_newer(from: &Path, to: &Path) -> bool {
    let modified = |p: &Path| std::fs::metadata(p).and_then(|m| m.modified()).ok();
    let Some(source) = modified(from) else { return false };
    if modified(to).is_some_and(|target| target >= source) {
        return false;
    }
    if let Some(dir) = to.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    std::fs::copy(from, to).is_ok()
}

/// Installe les clés et fichiers du dossier des BIOS pour l'émulateur [emulator] (identifiant du catalogue).
pub fn install(emulator: &str, exe: &str) {
    let appdata = PathBuf::from(std::env::var("APPDATA").unwrap_or_default());
    for (from, to) in files_for(emulator, Path::new(exe), &settings::bios_dir(), &appdata) {
        if copy_if_newer(&from, &to) {
            eprintln!("[emulator_files] {} -> {}", paths::file_name(&from), to.display());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cles_de_la_switch_pour_eden_et_ryujinx() {
        let root = std::env::temp_dir().join(format!("romcloud-emufiles-{}", std::process::id()));
        let bios = root.join("bios");
        let appdata = root.join("appdata");
        std::fs::create_dir_all(bios.join("switch")).unwrap();
        std::fs::write(bios.join("switch").join("prod.keys"), "prod").unwrap();

        // Installation classique : dossier de l'utilisateur.
        let eden = root.join("Eden").join("eden.exe");
        let files = files_for("eden", &eden, &bios, &appdata);
        assert_eq!(files[0].1, appdata.join("eden").join("keys").join("prod.keys"));
        assert!(copy_if_newer(&files[0].0, &files[0].1));
        assert!(!copy_if_newer(&files[0].0, &files[0].1)); // déjà à jour
        assert!(!copy_if_newer(&files[1].0, &files[1].1)); // title.keys absent

        // Ryujinx portable : dossier « portable » à côté de l'exécutable.
        let ryujinx = root.join("Ryujinx").join("Ryujinx.exe");
        std::fs::create_dir_all(root.join("Ryujinx").join("portable")).unwrap();
        assert_eq!(files_for("ryujinx", &ryujinx, &bios, &appdata)[0].1, root.join("Ryujinx").join("portable").join("system").join("prod.keys"));
        assert!(files_for("dolphin", &eden, &bios, &appdata).is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }
}
