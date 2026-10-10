// Fichiers du dossier des BIOS (récupérés sur le serveur) installés là où les émulateurs du
// catalogue les attendent, avant leur lancement : clés de la Switch pour Eden (et eden-cli) et
// Ryujinx (mode portable reconnu à son dossier « user » ou « portable » à côté de l'exécutable).
// eden-cli lit sa propre configuration (sdl2-config.ini), que l'interface d'Eden ne modifie pas :
// il est lancé avec une copie de celle d'eden.exe (qt-config.ini : manettes, graphismes…).
use crate::{paths, settings};
use std::path::{Path, PathBuf};

/// Dossier des données d'Eden : « user » à côté de l'exécutable (portable), sinon %APPDATA%\eden.
fn eden_dir(exe_dir: &Path, appdata: &Path) -> PathBuf {
    if exe_dir.join("user").is_dir() {
        exe_dir.join("user")
    } else {
        appdata.join("eden")
    }
}

/// Fichiers à installer pour l'émulateur : (fichier du dossier des BIOS, destination).
fn files_for(emulator: &str, exe: &Path, bios: &Path, appdata: &Path) -> Vec<(PathBuf, PathBuf)> {
    let exe_dir = exe.parent().map(Path::to_path_buf).unwrap_or_default();
    let keys_dir = match emulator {
        "eden" | "eden-cli" => eden_dir(&exe_dir, appdata).join("keys"),
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

/**
 * eden-cli : configuration d'eden.exe (qt-config.ini) recopiée dans [target] à chaque lancement
 * (eden-cli y réécrit ses réglages en quittant : celle d'eden.exe n'est jamais modifiée) ; renvoie
 * les arguments « -c <copie> » à ajouter, ou rien si Eden n'a pas encore de configuration.
 */
fn eden_cli_config(exe: &Path, appdata: &Path, target: &Path) -> Vec<String> {
    let exe_dir = exe.parent().map(Path::to_path_buf).unwrap_or_default();
    let qt = eden_dir(&exe_dir, appdata).join("config").join("qt-config.ini");
    if !qt.is_file() {
        return vec![];
    }
    if let Some(dir) = target.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if std::fs::copy(&qt, target).is_err() {
        return vec![];
    }
    vec!["-c".to_string(), target.to_string_lossy().into_owned()]
}

/**
 * Prépare le lancement de l'émulateur [emulator] (identifiant du catalogue) : clés et fichiers du
 * dossier des BIOS installés ; renvoie les arguments de la ligne de commande, complétés si besoin.
 */
pub fn prepare(emulator: &str, exe: &str, args: Vec<String>) -> Vec<String> {
    let appdata = PathBuf::from(std::env::var("APPDATA").unwrap_or_default());
    for (from, to) in files_for(emulator, Path::new(exe), &settings::bios_dir(), &appdata) {
        if copy_if_newer(&from, &to) {
            eprintln!("[emulator_files] {} -> {}", paths::file_name(&from), to.display());
        }
    }
    // eden-cli choisi comme émulateur du catalogue, ou indiqué comme exécutable d'Eden.
    let cli = emulator == "eden-cli" || (emulator == "eden" && paths::file_name(Path::new(exe)).to_lowercase().starts_with("eden-cli"));
    if cli && !args.iter().any(|a| a == "-c" || a == "--config") {
        let target = paths::user_data().join("eden").join("eden-cli-config.ini");
        return [eden_cli_config(Path::new(exe), &appdata, &target), args].concat();
    }
    args
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
        assert_eq!(files_for("eden-cli", &eden, &bios, &appdata), files);
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

    #[test]
    fn eden_cli_avec_la_configuration_d_eden() {
        let root = std::env::temp_dir().join(format!("romcloud-edencli-{}", std::process::id()));
        let appdata = root.join("appdata");
        let target = root.join("romcloud").join("eden-cli-config.ini");
        // Portable : dossier « user » à côté de l'exécutable.
        let exe = root.join("Eden").join("eden-cli.exe");
        assert!(eden_cli_config(&exe, &appdata, &target).is_empty()); // Eden jamais configuré
        let config = root.join("Eden").join("user").join("config");
        std::fs::create_dir_all(&config).unwrap();
        std::fs::write(config.join("qt-config.ini"), "[Controls]\nplayer_0_button_a=\"engine:sdl,button:0\"\n").unwrap();
        let args = eden_cli_config(&exe, &appdata, &target);
        assert_eq!(args, ["-c".to_string(), target.to_string_lossy().into_owned()]);
        assert!(std::fs::read_to_string(&target).unwrap().contains("player_0_button_a"));
        let _ = std::fs::remove_dir_all(&root);
    }
}
