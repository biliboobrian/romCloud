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
}
