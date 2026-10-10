// Fichiers du dossier des BIOS (récupérés sur le serveur) installés là où les émulateurs du
// catalogue les attendent, avant leur lancement : clés de la Switch pour Eden (et eden-cli) et
// Ryujinx (mode portable reconnu à son dossier « user » ou « portable » à côté de l'exécutable).
// eden-cli lit sa propre configuration (sdl2-config.ini), que l'interface d'Eden ne modifie pas :
// les manettes d'eden.exe (section [Controls] de qt-config.ini) y sont recopiées avant le lancement
// (l'option -c d'eden-cli, qui chargerait un autre fichier, le fait planter dans Eden v0.2.1).
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
        "eden" => eden_dir(&exe_dir, appdata).join("keys"),
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

/// Position (début, fin) de la section [name] d'un fichier INI : de son en-tête à la section suivante.
fn ini_section(text: &str, name: &str) -> Option<(usize, usize)> {
    let header = format!("[{name}]");
    let mut offset = 0;
    let mut start = None;
    for line in text.split_inclusive('\n') {
        let trimmed = line.trim();
        if start.is_some() && trimmed.starts_with('[') {
            return start.map(|s| (s, offset));
        }
        if start.is_none() && trimmed == header {
            start = Some(offset);
        }
        offset += line.len();
    }
    start.map(|s| (s, text.len()))
}

/// [sdl] avec la section [Controls] de [qt] à la place de la sienne (ajoutée si absente).
fn with_qt_controls(qt: &str, sdl: &str) -> Option<String> {
    let (qs, qe) = ini_section(qt, "Controls")?;
    let mut controls = qt[qs..qe].to_string();
    if !controls.ends_with('\n') {
        controls.push_str(if qt.contains("\r\n") { "\r\n" } else { "\n" });
    }
    Some(match ini_section(sdl, "Controls") {
        Some((ss, se)) => format!("{}{}{}", &sdl[..ss], controls, &sdl[se..]),
        None if sdl.is_empty() || sdl.ends_with('\n') => format!("{sdl}{controls}"),
        None => format!("{sdl}\n{controls}"),
    })
}

/**
 * eden-cli : manettes d'eden.exe (section [Controls] de qt-config.ini) recopiées dans sa propre
 * configuration (sdl2-config.ini, dont l'original est gardé une fois en .romcloud.bak) ; renvoie
 * vrai si le fichier a changé.
 */
fn sync_eden_cli_controls(exe: &Path, appdata: &Path) -> bool {
    let exe_dir = exe.parent().map(Path::to_path_buf).unwrap_or_default();
    let config = eden_dir(&exe_dir, appdata).join("config");
    let Ok(qt) = std::fs::read_to_string(config.join("qt-config.ini")) else { return false };
    let sdl_path = config.join("sdl2-config.ini");
    let sdl = std::fs::read_to_string(&sdl_path).unwrap_or_default();
    let Some(updated) = with_qt_controls(&qt, &sdl) else { return false };
    if updated == sdl {
        return false;
    }
    let backup = config.join("sdl2-config.ini.romcloud.bak");
    if !sdl.is_empty() && !backup.exists() {
        let _ = std::fs::write(&backup, &sdl);
    }
    std::fs::write(&sdl_path, updated).is_ok()
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
    // Eden lancé avec eden-cli (jeux) : manettes d'eden.exe recopiées.
    let cli = emulator == "eden" && paths::file_name(Path::new(exe)).to_lowercase().starts_with("eden-cli");
    if cli && sync_eden_cli_controls(Path::new(exe), &appdata) {
        eprintln!("[emulator_files] manettes d'eden.exe -> sdl2-config.ini");
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

    /// Installation réelle : `ROMCLOUD_EDEN_CLI=C:\…\eden-cli.exe cargo test -- --ignored eden_cli_installe`.
    #[test]
    #[ignore]
    fn eden_cli_installe() {
        let exe = PathBuf::from(std::env::var("ROMCLOUD_EDEN_CLI").unwrap());
        let appdata = PathBuf::from(std::env::var("APPDATA").unwrap());
        println!("sdl2-config.ini modifié : {}", sync_eden_cli_controls(&exe, &appdata));
    }

    #[test]
    fn manettes_d_eden_recopiees_pour_eden_cli() {
        let qt = "[UI]\r\ntheme=dark\r\n[Controls]\r\nplayer_0_button_a=\"engine:sdl,button:1\"\r\n[Core]\r\nuse_multi_core=true\r\n";
        let sdl = "[Controls]\r\nplayer_0_button_a=\"engine:keyboard,code:4\"\r\n[Renderer]\r\nbackend=1\r\n";
        let updated = with_qt_controls(qt, sdl).unwrap();
        assert_eq!(updated, "[Controls]\r\nplayer_0_button_a=\"engine:sdl,button:1\"\r\n[Renderer]\r\nbackend=1\r\n");
        assert_eq!(with_qt_controls(qt, &updated).unwrap(), updated); // déjà à jour
        // Section absente, ou en fin de fichier.
        assert_eq!(
            with_qt_controls(qt, "[Renderer]\nbackend=1").unwrap(),
            "[Renderer]\nbackend=1\n[Controls]\r\nplayer_0_button_a=\"engine:sdl,button:1\"\r\n"
        );
        assert!(with_qt_controls("[UI]\n", sdl).is_none());

        // Fichiers réels : sauvegarde de l'original, une seule fois.
        let root = std::env::temp_dir().join(format!("romcloud-edencli-{}", std::process::id()));
        let config = root.join("Eden").join("user").join("config");
        std::fs::create_dir_all(&config).unwrap();
        std::fs::write(config.join("qt-config.ini"), qt).unwrap();
        std::fs::write(config.join("sdl2-config.ini"), sdl).unwrap();
        let exe = root.join("Eden").join("eden-cli.exe");
        assert!(sync_eden_cli_controls(&exe, &root.join("appdata")));
        assert!(!sync_eden_cli_controls(&exe, &root.join("appdata")));
        assert_eq!(std::fs::read_to_string(config.join("sdl2-config.ini.romcloud.bak")).unwrap(), sdl);
        assert!(std::fs::read_to_string(config.join("sdl2-config.ini")).unwrap().contains("engine:sdl"));
        let _ = std::fs::remove_dir_all(&root);
    }
}
