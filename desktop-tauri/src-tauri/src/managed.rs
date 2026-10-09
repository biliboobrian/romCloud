// Émulateurs gérés par RomCloud dans le moteur intégré, pour les consoles sans cœur libretro :
// PlayStation 3 (RPCS3) et Wii U (Cemu). Dernière version Windows téléchargée depuis GitHub au
// premier lancement dans %APPDATA%\RomCloud\<émulateur> (l'émulateur y garde aussi sa configuration
// et les sauvegardes), fichiers système pris dans le dossier des BIOS, jeu lancé en plein écran.
//  - RPCS3 : firmware installé depuis PS3UPDAT.PUP, jeu = ISO, EBOOT.BIN ou dossier du disque ;
//  - Cemu : mode portable (dossier « portable »), keys.txt copié pour les jeux chiffrés (.wud, .wux),
//    jeu = .wua, .wud, .wux, .rpx ou dossier code/content/meta.
use crate::error::{AppError, Result};
use crate::{account, api, catalog, paths, settings};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

pub struct App {
    /// « Cœur » des choix d'émulateur (« builtin:rpcs3 ») et dossier dans %APPDATA%\RomCloud.
    pub core: &'static str,
    pub name: &'static str,
    systems: &'static [&'static str],
    releases: &'static str,
    /// Fins possibles du nom de l'archive Windows publiée (minuscules), par ordre de préférence.
    assets: &'static [&'static str],
    exe: &'static str,
    /// Arguments du lancement ({file} : jeu).
    args: &'static [&'static str],
    /// Journaux de l'émulateur (relatifs à son dossier).
    logs: &'static [&'static str],
}

const RPCS3: App = App {
    core: "rpcs3",
    name: "RPCS3",
    systems: &["ps3"],
    releases: "https://api.github.com/repos/RPCS3/rpcs3-binaries-win/releases/latest",
    assets: &["_win64_msvc.7z", "_win64.7z"],
    exe: "rpcs3.exe",
    args: &["--no-gui", "--fullscreen", "{file}"],
    logs: &["log/RPCS3.log", "RPCS3.log"],
};

const CEMU: App = App {
    core: "cemu",
    name: "Cemu",
    systems: &["wiiu"],
    releases: "https://api.github.com/repos/cemu-project/Cemu/releases/latest",
    assets: &["-windows-x64.zip"],
    exe: "Cemu.exe",
    args: &["-f", "-g", "{file}"],
    logs: &["portable/log.txt"],
};

pub static APPS: &[App] = &[RPCS3, CEMU];

const FIRMWARE: &str = "PS3UPDAT.PUP";
/// Code de sortie de Windows quand une DLL manque (Visual C++ Redistributable absent).
const DLL_NOT_FOUND: i32 = 0xC000_0135_u32 as i32;
/// Jeux Wii U lisibles par Cemu dans une archive décompressée.
const WIIU_FILES: &[&str] = &[".wua", ".wux", ".wud", ".iso"];

// Un téléchargement ou une installation à la fois, même si l'utilisateur relance.
static LOCK: LazyLock<tokio::sync::Mutex<()>> = LazyLock::new(|| tokio::sync::Mutex::new(()));

/// Émulateur géré pour un système (identifiant ou nom court).
pub fn for_system(system: &Value) -> Option<&'static App> {
    let keys: Vec<String> =
        ["shortname", "id"].iter().filter_map(|k| system.get(*k).and_then(Value::as_str)).map(str::to_lowercase).collect();
    APPS.iter().find(|app| app.systems.iter().any(|s| keys.iter().any(|k| k == s)))
}

pub fn by_core(core: &str) -> Option<&'static App> {
    APPS.iter().find(|app| app.core == core)
}

impl App {
    fn root(&self) -> PathBuf {
        paths::user_data().join(self.core)
    }

    pub fn exe(&self) -> PathBuf {
        self.root().join(self.exe)
    }

    pub fn installed(&self) -> bool {
        self.exe().exists()
    }

    /// Arguments de l'émulateur pour le jeu [game].
    pub fn args(&self, game: &str) -> Vec<String> {
        catalog::build_args(self.args, game)
    }

    /// Mise à jour au prochain lancement : seul l'exécutable est supprimé, configuration et sauvegardes restent.
    pub fn reset(&self) {
        let _ = std::fs::remove_file(self.exe());
    }

    /// Archive Windows de la dernière version : URL.
    fn release_asset(&self, release: &Value) -> Option<String> {
        let assets = release.get("assets")?.as_array()?;
        let name = |a: &Value| a.get("name").and_then(Value::as_str).unwrap_or("").to_lowercase();
        self.assets
            .iter()
            .find_map(|end| assets.iter().find(|a| name(a).ends_with(end)))
            .and_then(|a| a.get("browser_download_url").and_then(Value::as_str).map(String::from))
    }

    fn download_error(&self, detail: impl Into<String>) -> AppError {
        AppError::new("errors.appDownload", json!({ "name": self.name, "detail": detail.into() }))
    }

    async fn get(&self, url: &str) -> Result<reqwest::Response> {
        let res = api::client().get(url).send().await.map_err(|e| self.download_error(api::network_detail(&e)))?;
        if !res.status().is_success() {
            return Err(self.download_error(format!("HTTP {}", res.status().as_u16())));
        }
        Ok(res)
    }

    /// Émulateur installé, sinon téléchargé et décompressé dans son dossier (les fichiers déjà présents,
    /// configuration et sauvegardes, sont gardés ; l'exécutable est mis en place en dernier).
    async fn ensure_installed(&'static self) -> Result<PathBuf> {
        if self.installed() {
            return Ok(self.exe());
        }
        let release: Value = self.get(self.releases).await?.json().await.map_err(|e| self.download_error(e.to_string()))?;
        let url = self.release_asset(&release).ok_or_else(|| self.download_error("archive Windows introuvable"))?;
        let archive = self.get(&url).await?.bytes().await.map_err(|e| self.download_error(e.to_string()))?;
        tokio::task::spawn_blocking(move || -> Result<()> {
            let staging = paths::user_data().join(format!("{}-download", self.core));
            let _ = std::fs::remove_dir_all(&staging);
            let cursor = std::io::Cursor::new(archive);
            if url.to_lowercase().ends_with(".7z") {
                sevenz_rust2::decompress(cursor, &staging).map_err(|e| self.download_error(e.to_string()))?;
            } else {
                zip::ZipArchive::new(cursor)?.extract(&staging)?;
            }
            let top = single_folder(&staging);
            if !top.join(self.exe).exists() {
                return Err(self.download_error(format!("{} absent de l'archive", self.exe)));
            }
            merge(&top, &self.root(), self.exe)?;
            let _ = std::fs::remove_dir_all(&staging);
            Ok(())
        })
        .await
        .map_err(|e| AppError::msg(e.to_string()))??;
        Ok(self.exe())
    }

    /// Fichiers système de la console (firmware de la PS3, mode portable et clés de Cemu).
    async fn ensure_system(&self, exe: &Path) -> Result<()> {
        match self.core {
            "rpcs3" => ensure_firmware(&self.root(), exe).await,
            "cemu" => cemu_setup(&self.root()),
            _ => Ok(()),
        }
    }

    /// Jeu passé à l'émulateur : pour un .zip, décompressé une fois pour toutes (<émulateur>\games\<jeu>),
    /// sinon le fichier tel quel.
    fn prepare_game(&self, file: &str) -> Result<String> {
        if paths::extension(file) != ".zip" {
            return Ok(file.to_string());
        }
        let dir = self.root().join("games").join(paths::stem(file));
        let done = dir.join(".romcloud-extracted");
        if !done.exists() {
            let _ = std::fs::remove_dir_all(&dir);
            zip::ZipArchive::new(std::fs::File::open(file)?)?.extract(&dir)?;
            std::fs::write(&done, file)?;
        }
        let game = match self.core {
            "rpcs3" => disc_folder(&dir, 3).or_else(|| find_file(&dir, 2, &[".iso"])),
            _ => rpx_file(&dir, 3).or_else(|| find_file(&dir, 2, WIIU_FILES)),
        };
        let game = game.ok_or_else(|| AppError::new("errors.zipNoGame", json!({ "name": self.name, "file": file })))?;
        Ok(game.to_string_lossy().into_owned())
    }

    /// Fin du journal de l'émulateur (jointe à l'erreur signalée quand il s'arrête sur une erreur).
    fn last_log(&self) -> Option<String> {
        let text = self.logs.iter().find_map(|f| std::fs::read_to_string(self.root().join(f)).ok())?;
        let chars: Vec<char> = text.chars().collect();
        Some(chars[chars.len().saturating_sub(4000)..].iter().collect())
    }
}

/// Dossier unique d'une archive qui ne contient que lui, sinon le dossier lui-même.
fn single_folder(dir: &Path) -> PathBuf {
    let entries: Vec<_> = std::fs::read_dir(dir).map(|r| r.flatten().collect()).unwrap_or_default();
    match entries.as_slice() {
        [only] if only.path().is_dir() => only.path(),
        _ => dir.to_path_buf(),
    }
}

/// Déplace le contenu de [from] dans [to] en remplaçant les fichiers ; [last] (l'exécutable) en
/// dernier, pour qu'une installation interrompue soit reprise au lancement suivant.
fn merge(from: &Path, to: &Path, last: &str) -> Result<()> {
    std::fs::create_dir_all(to)?;
    let mut entries: Vec<_> = std::fs::read_dir(from)?.flatten().map(|e| e.path()).collect();
    entries.sort_by_key(|p| paths::file_name(p).eq_ignore_ascii_case(last));
    for source in entries {
        let target = to.join(paths::file_name(&source));
        if source.is_dir() {
            merge(&source, &target, "")?;
        } else {
            let _ = std::fs::remove_file(&target);
            std::fs::rename(&source, &target)?;
        }
    }
    Ok(())
}

/// Fichier [name] dans [dir] ou l'un de ses sous-dossiers (« ps3\PS3UPDAT.PUP »), sans tenir compte de la casse.
fn find_named(dir: &Path, name: &str, depth: u32) -> Option<PathBuf> {
    let entries: Vec<PathBuf> = std::fs::read_dir(dir).ok()?.flatten().map(|e| e.path()).collect();
    if let Some(file) = entries.iter().find(|p| p.is_file() && paths::file_name(p).eq_ignore_ascii_case(name)) {
        return Some(file.clone());
    }
    if depth == 0 {
        return None;
    }
    entries.iter().filter(|p| p.is_dir()).find_map(|p| find_named(p, name, depth - 1))
}

/// Premier fichier (ordre alphabétique) de [dir] ou de ses sous-dossiers ayant l'une des [extensions].
fn find_file(dir: &Path, depth: u32, extensions: &[&str]) -> Option<PathBuf> {
    let mut entries: Vec<PathBuf> = std::fs::read_dir(dir).ok()?.flatten().map(|e| e.path()).collect();
    entries.sort();
    if let Some(file) = entries.iter().find(|p| p.is_file() && extensions.contains(&paths::extension(&p.to_string_lossy()).as_str())) {
        return Some(file.clone());
    }
    if depth == 0 {
        return None;
    }
    entries.iter().filter(|p| p.is_dir()).find_map(|p| find_file(p, depth - 1, extensions))
}

fn firmware_installed(root: &Path) -> bool {
    root.join("dev_flash").join("vsh").join("module").join("vsh.self").exists()
}

/// Firmware de la PS3 installé dans RPCS3 (sans fenêtre, RPCS3 se ferme une fois l'installation finie).
async fn ensure_firmware(root: &Path, exe: &Path) -> Result<()> {
    if firmware_installed(root) {
        return Ok(());
    }
    let dir = settings::bios_dir();
    let pup = find_named(&dir, FIRMWARE, 2)
        .ok_or_else(|| AppError::new("errors.ps3FirmwareMissing", json!({ "dir": dir.to_string_lossy(), "file": FIRMWARE })))?;
    let status = tokio::process::Command::new(exe)
        .args(["--headless", "--installfw"])
        .arg(&pup)
        .current_dir(root)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .await
        .map_err(|e| AppError::new("errors.launchFailed", json!({ "detail": e.to_string() })))?;
    if status.code() == Some(DLL_NOT_FOUND) {
        return Err(AppError::new("errors.appRedist", json!({ "name": RPCS3.name, "url": "https://aka.ms/vs/17/release/vc_redist.x64.exe" })));
    }
    if !firmware_installed(root) {
        return Err(AppError::new("errors.ps3FirmwareInstall", json!({ "file": pup.to_string_lossy(), "code": status.code() })));
    }
    Ok(())
}

/// Cemu en mode portable (données dans son dossier « portable ») ; keys.txt du dossier des BIOS
/// (« wiiu\keys.txt », « cemu\keys.txt » ou « keys.txt ») copié quand il est plus récent.
fn cemu_setup(root: &Path) -> Result<()> {
    let portable = root.join("portable");
    std::fs::create_dir_all(&portable)?;
    let bios = settings::bios_dir();
    let source = ["wiiu", "cemu", ""].iter().map(|d| bios.join(d).join("keys.txt")).find(|f| f.is_file());
    if let Some(source) = source {
        let target = portable.join("keys.txt");
        if paths::mtime_ms(&target).unwrap_or(0.0) < paths::mtime_ms(&source).unwrap_or(0.0) {
            std::fs::copy(&source, &target)?;
        }
    }
    Ok(())
}

/// Dossier du disque PS3 : celui qui contient PS3_GAME (ou PS3_DISC.SFB).
fn disc_folder(dir: &Path, depth: u32) -> Option<PathBuf> {
    if dir.join("PS3_GAME").is_dir() || dir.join("PS3_DISC.SFB").is_file() {
        return Some(dir.to_path_buf());
    }
    if depth == 0 {
        return None;
    }
    let mut dirs: Vec<PathBuf> = std::fs::read_dir(dir).ok()?.flatten().map(|e| e.path()).filter(|p| p.is_dir()).collect();
    dirs.sort();
    dirs.iter().find_map(|d| disc_folder(d, depth - 1))
}

/// Jeu Wii U décompressé (dossiers code, content, meta) : le .rpx de son dossier code.
fn rpx_file(dir: &Path, depth: u32) -> Option<PathBuf> {
    let code = dir.join("code");
    if code.is_dir() && dir.join("content").is_dir() {
        if let Some(rpx) = find_file(&code, 0, &[".rpx"]) {
            return Some(rpx);
        }
    }
    if depth == 0 {
        return None;
    }
    let mut dirs: Vec<PathBuf> = std::fs::read_dir(dir).ok()?.flatten().map(|e| e.path()).filter(|p| p.is_dir()).collect();
    dirs.sort();
    dirs.iter().find_map(|d| rpx_file(d, depth - 1))
}

/// Lance le jeu dans l'émulateur (téléchargé et fichiers système installés avant si besoin) ; le
/// temps de jeu est compté jusqu'à sa fermeture.
pub async fn launch(app: &'static App, game: &Value, file: &str) -> Result<()> {
    let exe = {
        let _guard = LOCK.lock().await;
        let exe = app.ensure_installed().await?;
        app.ensure_system(&exe).await?;
        exe
    };
    let target = {
        let file = file.to_string();
        tokio::task::spawn_blocking(move || app.prepare_game(&file)).await.map_err(|e| AppError::msg(e.to_string()))??
    };
    let mut child = tokio::process::Command::new(&exe)
        .args(app.args(&target))
        .current_dir(app.root())
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|e| AppError::new("errors.launchFailed", json!({ "detail": e.to_string() })))?;
    paths::allow_foreground(child.id());
    let started = paths::now_ms();
    let game_id = game.get("id").cloned().unwrap_or(Value::Null);
    tauri::async_runtime::spawn(async move {
        let code = child.wait().await.ok().and_then(|s| s.code()).unwrap_or(0);
        account::add_playtime(game_id, (paths::now_ms() - started) / 1000.0).await;
        if code != 0 {
            account::report_error(&format!("player:{}", app.core), &format!("{} exit code {code}", app.core), app.last_log());
        }
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("romcloud-managed-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn emulateur_du_systeme() {
        assert_eq!(for_system(&json!({ "id": "ps3" })).map(|a| a.core), Some("rpcs3"));
        assert_eq!(for_system(&json!({ "id": "sony-ps3", "shortname": "PS3" })).map(|a| a.core), Some("rpcs3"));
        assert_eq!(for_system(&json!({ "id": "wiiu" })).map(|a| a.core), Some("cemu"));
        assert!(for_system(&json!({ "id": "ps2" })).is_none());
        assert_eq!(by_core("cemu").map(|a| a.name), Some("Cemu"));
        assert!(by_core("mgba").is_none());
    }

    #[test]
    fn archive_windows_de_la_derniere_version() {
        let release = json!({ "assets": [
            { "name": "rpcs3-v0.0.43-20251-4c7d0858_win64_msvc.7z.sha256", "browser_download_url": "https://x/sha" },
            { "name": "rpcs3-v0.0.43-20251-4c7d0858_win64_msvc.7z", "browser_download_url": "https://x/7z" },
        ]});
        assert_eq!(RPCS3.release_asset(&release).as_deref(), Some("https://x/7z"));
        let old = json!({ "assets": [{ "name": "rpcs3-v0.0.30-15000_win64.7z", "browser_download_url": "https://x/old" }] });
        assert_eq!(RPCS3.release_asset(&old).as_deref(), Some("https://x/old"));
        let cemu = json!({ "assets": [
            { "name": "cemu-2.6-ubuntu-22.04-x64.zip", "browser_download_url": "https://x/linux" },
            { "name": "cemu-2.6-windows-x64.zip", "browser_download_url": "https://x/win" },
        ]});
        assert_eq!(CEMU.release_asset(&cemu).as_deref(), Some("https://x/win"));
        assert_eq!(CEMU.release_asset(&json!({ "assets": [] })), None);
    }

    #[test]
    fn arguments_du_lancement() {
        assert_eq!(RPCS3.args(r"D:\Jeux\ps3\Demon's Souls.iso"), ["--no-gui", "--fullscreen", r"D:\Jeux\ps3\Demon's Souls.iso"]);
        assert_eq!(CEMU.args(r"D:\Jeux\wiiu\Zelda.wua"), ["-f", "-g", r"D:\Jeux\wiiu\Zelda.wua"]);
    }

    #[test]
    fn firmware_dans_un_sous_dossier_des_bios() {
        let dir = temp_dir("bios");
        std::fs::create_dir_all(dir.join("ps3")).unwrap();
        assert_eq!(find_named(&dir, FIRMWARE, 2), None);
        std::fs::write(dir.join("ps3").join("ps3updat.pup"), b"pup").unwrap();
        assert_eq!(find_named(&dir, FIRMWARE, 2), Some(dir.join("ps3").join("ps3updat.pup")));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn dossier_du_disque_ps3() {
        let dir = temp_dir("disc");
        assert_eq!(disc_folder(&dir, 3), None);
        std::fs::create_dir_all(dir.join("Demon's Souls").join("PS3_GAME").join("USRDIR")).unwrap();
        assert_eq!(disc_folder(&dir, 3), Some(dir.join("Demon's Souls")));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn jeu_wii_u_decompresse() {
        let dir = temp_dir("wiiu");
        assert_eq!(rpx_file(&dir, 3), None);
        let game = dir.join("Mario Kart 8 [AMKP01]");
        for sub in ["code", "content", "meta"] {
            std::fs::create_dir_all(game.join(sub)).unwrap();
        }
        std::fs::write(game.join("code").join("app.xml"), b"xml").unwrap();
        std::fs::write(game.join("code").join("Turbo.rpx"), b"rpx").unwrap();
        assert_eq!(rpx_file(&dir, 3), Some(game.join("code").join("Turbo.rpx")));
        let other = temp_dir("wiiu-wua");
        std::fs::write(other.join("Zelda.WUA"), b"wua").unwrap();
        assert_eq!(find_file(&other, 2, WIIU_FILES), Some(other.join("Zelda.WUA")));
        let _ = std::fs::remove_dir_all(&dir);
        let _ = std::fs::remove_dir_all(&other);
    }

    #[test]
    fn mise_a_jour_sans_perdre_les_sauvegardes() {
        let dir = temp_dir("merge");
        let (new, old) = (dir.join("new"), dir.join("old"));
        std::fs::create_dir_all(new.join("bin")).unwrap();
        std::fs::write(new.join("rpcs3.exe"), b"v2").unwrap();
        std::fs::write(new.join("bin").join("a.dll"), b"v2").unwrap();
        std::fs::create_dir_all(old.join("bin")).unwrap();
        std::fs::create_dir_all(old.join("dev_hdd0")).unwrap();
        std::fs::write(old.join("bin").join("a.dll"), b"v1").unwrap();
        std::fs::write(old.join("dev_hdd0").join("save.dat"), b"save").unwrap();
        merge(&new, &old, "rpcs3.exe").unwrap();
        assert_eq!(std::fs::read(old.join("rpcs3.exe")).unwrap(), b"v2");
        assert_eq!(std::fs::read(old.join("bin").join("a.dll")).unwrap(), b"v2");
        assert_eq!(std::fs::read(old.join("dev_hdd0").join("save.dat")).unwrap(), b"save");
        assert_eq!(single_folder(&dir), dir);
        let _ = std::fs::remove_dir_all(&dir);
    }
}

