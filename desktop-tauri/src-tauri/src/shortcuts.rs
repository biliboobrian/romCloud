// Raccourci d'un jeu sur le bureau (fenêtre de téléchargement) : « RomCloud.exe --play <système>
// <jeu> » ouvre RomCloud (ou ramène sa fenêtre) et lance le jeu, ou ouvre sa fiche s'il n'est pas
// sur le PC. Icône : la jaquette du jeu (version « icon » du serveur : carré PNG, glissé dans un
// fichier .ico), sinon celle de RomCloud.
use crate::error::{AppError, Result};
use crate::{api, events, paths, settings};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// Argument de la ligne de commande d'un raccourci.
const PLAY_ARG: &str = "--play";

/// Jeu demandé au lancement de RomCloud (premier lancement), lu par l'interface une fois prête.
static PENDING: Mutex<Option<Value>> = Mutex::new(None);

/// Jeu demandé par les arguments [args] d'un raccourci : { systemId, gameId }, ou None.
pub fn play_request(args: &[String]) -> Option<Value> {
    let at = args.iter().position(|a| a == PLAY_ARG)?;
    let system = args.get(at + 1).filter(|s| !s.is_empty() && !s.starts_with("--"))?;
    let game: i64 = args.get(at + 2)?.parse().ok().filter(|id| *id > 0)?;
    Some(json!({ "systemId": system, "gameId": game }))
}

/// Premier lancement : jeu du raccourci gardé jusqu'à ce que l'interface le demande ([take_pending]).
pub fn remember_launch(args: &[String]) {
    *PENDING.lock().unwrap() = play_request(args);
}

/// RomCloud déjà ouvert : le jeu du raccourci est envoyé à l'interface.
pub fn forward(args: &[String]) {
    if let Some(request) = play_request(args) {
        events::emit("shortcut:play", request);
    }
}

/// Jeu du raccourci qui a ouvert RomCloud (une seule fois), ou null.
pub fn take_pending() -> Value {
    PENDING.lock().unwrap().take().unwrap_or(Value::Null)
}

/// Nom de fichier lisible pour Windows (caractères interdits retirés).
fn file_name(title: &str) -> String {
    let clean: String = title.chars().map(|c| if "<>:\"/\\|?*".contains(c) || c.is_control() { ' ' } else { c }).collect();
    let clean = clean.split_whitespace().collect::<Vec<_>>().join(" ");
    let clean = clean.trim_end_matches('.').trim();
    if clean.is_empty() { "RomCloud".into() } else { clean.chars().take(80).collect() }
}

/// Fichier .ico contenant l'image PNG [png] (format reconnu par Windows depuis Vista).
pub fn ico_from_png(png: &[u8]) -> Vec<u8> {
    let mut ico = Vec::with_capacity(22 + png.len());
    ico.extend_from_slice(&[0, 0, 1, 0, 1, 0]); // en-tête : icône, une image
    ico.extend_from_slice(&[0, 0, 0, 0]); // 256 x 256 (0), pas de palette
    ico.extend_from_slice(&1u16.to_le_bytes()); // plans
    ico.extend_from_slice(&32u16.to_le_bytes()); // bits par pixel
    ico.extend_from_slice(&(png.len() as u32).to_le_bytes());
    ico.extend_from_slice(&22u32.to_le_bytes()); // position de l'image
    ico.extend_from_slice(png);
    ico
}

/// Icône du jeu (jaquette) dans le dossier des raccourcis ; None sans jaquette ou serveur trop ancien.
async fn game_icon(game: &Value) -> Option<PathBuf> {
    if game["hasBoxart"] != json!(true) {
        return None;
    }
    let server = settings::get_str("serverUrl");
    let url = format!("{server}/api/games/{}/media/boxart?size=icon&v={}", game["id"], game["updatedAt"].as_str().unwrap_or(""));
    let res = api::client().get(url).headers(api::headers(&settings::get_str("apiKey"))).send().await.ok()?;
    let png = res.error_for_status().ok()?.bytes().await.ok()?;
    // Serveur sans la version « icon » : WebP ou original, pas une image PNG.
    if !png.starts_with(b"\x89PNG") {
        return None;
    }
    let file = paths::user_data().join("shortcuts").join(format!("{}.ico", game["id"]));
    std::fs::create_dir_all(file.parent()?).ok()?;
    std::fs::write(&file, ico_from_png(&png)).ok()?;
    Some(file)
}

/// Écrit le raccourci [lnk] vers [exe] avec [args] (objet WScript.Shell de Windows).
fn write_lnk(lnk: &Path, exe: &Path, args: &str, icon: &Path, description: &str) -> Result<()> {
    use std::os::windows::process::CommandExt;
    let script = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($env:RC_LNK); \
                  $s.TargetPath = $env:RC_EXE; $s.Arguments = $env:RC_ARGS; $s.IconLocation = $env:RC_ICON; \
                  $s.WorkingDirectory = $env:RC_DIR; $s.Description = $env:RC_DESC; $s.Save()";
    // Valeurs passées par l'environnement : aucun guillemet à échapper dans le script.
    let status = std::process::Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script])
        .env("RC_LNK", lnk)
        .env("RC_EXE", exe)
        .env("RC_ARGS", args)
        .env("RC_ICON", format!("{},0", icon.display()))
        .env("RC_DIR", exe.parent().unwrap_or(exe))
        .env("RC_DESC", description)
        .creation_flags(0x0800_0000) // CREATE_NO_WINDOW
        .status()?;
    if !status.success() || !lnk.is_file() {
        return Err(AppError::msg(format!("raccourci non créé : {}", lnk.display())));
    }
    Ok(())
}

/// Crée le raccourci du jeu [game] sur le bureau ; renvoie son chemin.
pub async fn create(game: &Value) -> Result<Value> {
    let system = game["systemId"].as_str().unwrap_or("");
    let id = game["id"].as_i64().unwrap_or(0);
    if system.is_empty() || id <= 0 {
        return Err(AppError::msg("jeu inconnu".to_string()));
    }
    let desktop = dirs::desktop_dir().ok_or_else(|| AppError::msg("bureau introuvable".to_string()))?;
    let exe = paths::plain(std::env::current_exe()?);
    let title = game["title"].as_str().filter(|t| !t.is_empty()).or_else(|| game["fileName"].as_str()).unwrap_or("RomCloud");
    let icon = game_icon(game).await.unwrap_or_else(|| exe.clone());
    let lnk = desktop.join(format!("{}.lnk", file_name(title)));
    let args = format!("{PLAY_ARG} {system} {id}");
    let description = format!("{title} (RomCloud)");
    tokio::task::spawn_blocking(move || write_lnk(&lnk, &exe, &args, &icon, &description).map(|_| lnk))
        .await
        .map_err(|e| AppError::msg(e.to_string()))?
        .map(|lnk| json!(lnk.to_string_lossy()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jeu_demande_par_un_raccourci() {
        let args = |list: &[&str]| list.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert_eq!(play_request(&args(&["RomCloud.exe", "--play", "psp", "14648"])), Some(json!({ "systemId": "psp", "gameId": 14648 })));
        assert_eq!(play_request(&args(&["RomCloud.exe"])), None);
        assert_eq!(play_request(&args(&["RomCloud.exe", "--play", "psp"])), None);
        assert_eq!(play_request(&args(&["RomCloud.exe", "--play", "psp", "x"])), None);
    }

    #[test]
    fn nom_du_raccourci_et_icone() {
        assert_eq!(file_name("Ridge Racer 2"), "Ridge Racer 2");
        assert_eq!(file_name("Final Fantasy: Tactics / PSP?"), "Final Fantasy Tactics PSP");
        assert_eq!(file_name("..."), "RomCloud");
        let ico = ico_from_png(b"\x89PNGdata");
        assert_eq!(&ico[..6], &[0, 0, 1, 0, 1, 0]);
        assert_eq!(u32::from_le_bytes(ico[14..18].try_into().unwrap()), 8);
        assert_eq!(&ico[22..], b"\x89PNGdata");
    }
}
