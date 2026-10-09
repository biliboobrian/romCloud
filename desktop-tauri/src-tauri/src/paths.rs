// Dossiers de l'application : les mêmes que l'ancienne version Electron (%APPDATA%\RomCloud), pour reprendre
// réglages, cache, cœurs, sauvegardes et profil sans migration.
use std::path::PathBuf;

/// Dossier des données de l'utilisateur (%APPDATA%\RomCloud).
pub fn user_data() -> PathBuf {
    dirs::config_dir().unwrap_or_else(std::env::temp_dir).join("RomCloud")
}

/// Chemin sans le préfixe « \\?\ » de Windows (chemins de ressources de Tauri), pour l'affichage
/// et les programmes qui ne le comprennent pas.
pub fn plain(path: PathBuf) -> PathBuf {
    let text = path.to_string_lossy();
    match text.strip_prefix(r"\\?\") {
        Some(rest) if rest.as_bytes().get(1) == Some(&b':') => PathBuf::from(rest),
        _ => path,
    }
}

/// Dossier « Documents » de l'utilisateur.
pub fn documents() -> PathBuf {
    dirs::document_dir().unwrap_or_else(|| dirs::home_dir().unwrap_or_default().join("Documents"))
}

/// Nom du fichier (sans dossier).
pub fn file_name(path: &std::path::Path) -> String {
    path.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default()
}

/// Nom du fichier sans son extension (path.parse(file).name).
pub fn stem(path: &str) -> String {
    std::path::Path::new(path).file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default()
}

/// Extension en minuscules avec le point (« .zip »), vide sans extension.
pub fn extension(path: &str) -> String {
    std::path::Path::new(path)
        .extension()
        .map(|s| format!(".{}", s.to_string_lossy().to_lowercase()))
        .unwrap_or_default()
}

/// Dossier parent sous forme de texte.
pub fn dirname(path: &str) -> String {
    std::path::Path::new(path).parent().map(|p| p.to_string_lossy().into_owned()).unwrap_or_default()
}

/// Date de modification d'un fichier (ms depuis 1970), None s'il n'existe pas.
pub fn mtime_ms(path: &std::path::Path) -> Option<f64> {
    let modified = std::fs::metadata(path).ok()?.modified().ok()?;
    Some(modified.duration_since(std::time::UNIX_EPOCH).ok()?.as_millis() as f64)
}

/// Taille d'un fichier, None s'il n'existe pas.
pub fn size(path: &std::path::Path) -> Option<u64> {
    std::fs::metadata(path).ok().filter(|m| m.is_file()).map(|m| m.len())
}

/// Autorise le programme [pid] lancé par RomCloud (au premier plan) à passer devant lui : sans
/// cela, Windows le laisse derrière quand le jeu a été lancé à la manette (seuls le clavier et la
/// souris comptent comme une action de l'utilisateur).
pub fn allow_foreground(pid: Option<u32>) {
    #[cfg(windows)]
    {
        #[link(name = "user32")]
        extern "system" {
            fn AllowSetForegroundWindow(process_id: u32) -> i32;
        }
        if let Some(pid) = pid {
            // SAFETY : fonction de Windows sans pointeur, sans effet si RomCloud n'est pas au premier plan.
            unsafe {
                AllowSetForegroundWindow(pid);
            }
        }
    }
    #[cfg(not(windows))]
    let _ = pid;
}

/// Espace de la partition qui contient [path] (ou son premier dossier existant) : (libre, total) en octets.
pub fn disk_space(path: &std::path::Path) -> Option<(u64, u64)> {
    let mut dir = path.to_path_buf();
    while !dir.exists() {
        dir = dir.parent()?.to_path_buf();
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        #[link(name = "kernel32")]
        extern "system" {
            fn GetDiskFreeSpaceExW(directory: *const u16, free_for_caller: *mut u64, total: *mut u64, total_free: *mut u64) -> i32;
        }
        let wide: Vec<u16> = dir.as_os_str().encode_wide().chain(Some(0)).collect();
        let (mut free, mut total, mut total_free) = (0u64, 0u64, 0u64);
        // SAFETY : chaîne terminée par 0, pointeurs vers des variables locales.
        let ok = unsafe { GetDiskFreeSpaceExW(wide.as_ptr(), &mut free, &mut total, &mut total_free) };
        if ok != 0 && total > 0 {
            return Some((free, total));
        }
        None
    }
    #[cfg(not(windows))]
    None
}

/// Maintenant, en ms depuis 1970.
pub fn now_ms() -> f64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as f64).unwrap_or(0.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chemins() {
        assert_eq!(plain(PathBuf::from(r"\\?\D:\RomCloud\player.exe")), PathBuf::from(r"D:\RomCloud\player.exe"));
        assert_eq!(plain(PathBuf::from(r"\\?\UNC\nas\jeux")), PathBuf::from(r"\\?\UNC\nas\jeux"));
        assert_eq!(stem(r"D:\Jeux\Zelda (France).zip"), "Zelda (France)");
        assert_eq!(extension(r"D:\Jeux\Mario.ZIP"), ".zip");
        assert_eq!(extension("sans-extension"), "");
        assert_eq!(dirname(r"D:\Jeux\mame\sf2.zip"), r"D:\Jeux\mame");
    }

    #[test]
    fn espace_de_la_partition() {
        // Dossier absent : partition de son premier dossier existant.
        let (free, total) = disk_space(&std::env::temp_dir().join("romcloud-absent").join("roms")).unwrap();
        assert!(total > 0 && free <= total);
    }
}
