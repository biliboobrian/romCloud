// Vita3K : jeux PS Vita lancés directement. Un jeu doit être installé dans Vita3K avant de démarrer :
//  - .vpk / .zip : passé en argument, Vita3K l'installe puis le démarre (premier lancement) ;
//  - .pkg : chiffré, installé avec sa licence (zRIF, saisie dans la fiche du jeu) par
//    « --pkg <fichier> --zrif <licence> » (Vita3K installe puis quitte), puis démarré ;
//  - jeu déjà installé (ux0/app/<identifiant>/eboot.bin) : démarré par « -r <identifiant> ».
// L'identifiant (« PCSB00061 ») est lu dans le nom du fichier (identifiant de contenu ou numéro de
// série), sinon dans l'en-tête du .pkg ou le param.sfo du .vpk.
use crate::{paths, settings};
use serde_json::{json, Value};
use std::io::Read;
use std::path::{Path, PathBuf};

/// Préfixes des numéros de série PS Vita (PCSA…PCSH, VCS…, VLAS…).
fn is_vita_serial(id: &str) -> bool {
    id.len() == 9
        && id[4..].bytes().all(|b| b.is_ascii_digit())
        && (id.starts_with("PCS") || id.starts_with("VCS") || id.starts_with("VLAS") || id.starts_with("NPX"))
        && id[..4].bytes().all(|b| b.is_ascii_uppercase())
}

/// Identifiant dans un nom de fichier : « EP0001-PCSB00061_00-LUMINES… », « PCSB00061 », « [PCSB-00061] ».
pub fn title_id_from_name(name: &str) -> Option<String> {
    let chars: Vec<char> = name.to_ascii_uppercase().chars().collect();
    for start in 0..chars.len() {
        // Quatre lettres en début de mot, séparateur facultatif, cinq chiffres (et pas un de plus).
        if (start > 0 && chars[start - 1].is_ascii_alphanumeric()) || start + 4 > chars.len() {
            continue;
        }
        let mut i = start + 4;
        if matches!(chars.get(i), Some('-' | '_')) {
            i += 1;
        }
        let Some(digits) = chars.get(i..i + 5) else { continue };
        let id: String = chars[start..start + 4].iter().chain(digits).collect();
        if is_vita_serial(&id) && !chars.get(i + 5).is_some_and(|c| c.is_ascii_digit()) {
            return Some(id);
        }
    }
    None
}

/// Identifiant de contenu de l'en-tête d'un paquet Sony (.pkg, 36 caractères à l'octet 0x30).
fn title_id_from_pkg(file: &Path) -> Option<String> {
    let mut header = [0u8; 0x54];
    std::fs::File::open(file).ok()?.read_exact(&mut header).ok()?;
    if &header[..4] != b"\x7fPKG" {
        return None;
    }
    let content_id = String::from_utf8_lossy(&header[0x30..0x54]).to_string();
    content_id.get(7..16).filter(|id| is_vita_serial(id)).map(String::from)
}

/// TITLE_ID d'un param.sfo (format PSF : table des clés, table des valeurs).
pub fn title_id_from_sfo(sfo: &[u8]) -> Option<String> {
    let u32_at = |o: usize| sfo.get(o..o + 4).map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]) as usize);
    let u16_at = |o: usize| sfo.get(o..o + 2).map(|b| u16::from_le_bytes([b[0], b[1]]) as usize);
    if sfo.get(..4)? != b"\0PSF" {
        return None;
    }
    let (keys, data, count) = (u32_at(8)?, u32_at(12)?, u32_at(16)?);
    for i in 0..count {
        let entry = 20 + i * 16;
        let key_start = keys + u16_at(entry)?;
        let key_end = key_start + sfo.get(key_start..)?.iter().position(|b| *b == 0)?;
        if &sfo[key_start..key_end] != b"TITLE_ID" {
            continue;
        }
        let (len, offset) = (u32_at(entry + 4)?, u32_at(entry + 12)?);
        let value = sfo.get(data + offset..data + offset + len)?;
        let id = String::from_utf8_lossy(value).trim_end_matches('\0').to_string();
        return is_vita_serial(&id).then_some(id);
    }
    None
}

/// TITLE_ID du param.sfo d'une archive .vpk / .zip (sce_sys/param.sfo, éventuellement dans un dossier).
fn title_id_from_archive(file: &Path) -> Option<String> {
    let mut archive = zip::ZipArchive::new(std::fs::File::open(file).ok()?).ok()?;
    let name = archive.file_names().filter(|n| n.to_ascii_lowercase().ends_with("sce_sys/param.sfo")).min_by_key(|n| n.len())?.to_string();
    let mut sfo = Vec::new();
    archive.by_name(&name).ok()?.read_to_end(&mut sfo).ok()?;
    title_id_from_sfo(&sfo)
}

/// Identifiant du jeu (« PCSB00061 ») : nom du fichier, sinon contenu du fichier.
pub fn title_id(file: &Path) -> Option<String> {
    title_id_from_name(&paths::file_name(file)).or_else(|| match paths::extension(&file.to_string_lossy()).as_str() {
        ".pkg" => title_id_from_pkg(file),
        ".vpk" | ".zip" => title_id_from_archive(file),
        _ => None,
    })
}

/// Dossier « ux0 » des jeux de Vita3K : <dossier de Vita3K>/portable/fs s'il existe un dossier
/// portable, sinon « pref-path » de config.yml, sinon %APPDATA%\Vita3K\Vita3K.
fn vita_fs(exe: &str) -> PathBuf {
    let dir = PathBuf::from(paths::dirname(exe));
    if dir.join("portable").is_dir() {
        return dir.join("portable").join("fs");
    }
    let config = std::fs::read_to_string(dir.join("config.yml")).unwrap_or_default();
    let pref = config.lines().find_map(|l| l.trim().strip_prefix("pref-path:")).map(|v| v.trim().trim_matches(['"', '\'']).to_string());
    match pref.filter(|p| !p.is_empty()) {
        Some(p) => PathBuf::from(p),
        None => PathBuf::from(std::env::var("APPDATA").unwrap_or_default()).join("Vita3K").join("Vita3K"),
    }
}

/// Jeu [id] installé dans Vita3K.
pub fn is_installed(exe: &str, id: &str) -> bool {
    vita_fs(exe).join("ux0").join("app").join(id).join("eboot.bin").is_file()
}

/// Licence (zRIF) saisie sur ce PC dans la fiche du jeu.
fn local_license(game_id: &Value) -> Option<String> {
    let licenses = settings::load().get("vitaLicenses").cloned().unwrap_or(Value::Null);
    licenses.get(game_id.to_string().trim_matches('"')).and_then(Value::as_str).map(String::from).filter(|z| !z.is_empty())
}

/// Licence (zRIF) du jeu : celle saisie sur ce PC, sinon celle proposée par le serveur.
pub fn license(game: &Value) -> Option<String> {
    local_license(&game["id"]).or_else(|| game["zrif"].as_str().map(String::from).filter(|z| !z.is_empty()))
}

/// Enregistre (ou efface, vide) la licence d'un jeu ; zRIF : texte base64 (« KO5ifR1dQ+eHBl… »).
pub fn set_license(game_id: &Value, zrif: &str) -> Value {
    let zrif: String = zrif.chars().filter(|c| !c.is_whitespace()).collect();
    let mut licenses = settings::load().get("vitaLicenses").and_then(Value::as_object).cloned().unwrap_or_default();
    let key = game_id.to_string().trim_matches('"').to_string();
    if zrif.is_empty() {
        licenses.remove(&key);
    } else {
        licenses.insert(key, json!(zrif));
    }
    settings::save(json!({ "vitaLicenses": licenses }));
    json!(zrif)
}

/// Étape du lancement.
pub enum Step {
    /// Jeu installé : « -F -r <identifiant> ».
    Run(Vec<String>),
    /// .vpk / .zip : installé puis démarré par Vita3K lui-même.
    InstallAndRun(Vec<String>),
    /// .pkg : installation (Vita3K quitte ensuite), puis démarrage.
    InstallPkg { install: Vec<String>, run: Vec<String> },
}

/// Commande(s) pour lancer [file] ; erreur (clé d'i18n) si le jeu ne peut pas être lancé seul.
pub fn plan(exe: &str, file: &Path, game: &Value) -> Result<Step, &'static str> {
    let path = file.to_string_lossy().into_owned();
    let id = title_id(file);
    let run = |id: &str| vec!["-F".to_string(), "-r".into(), id.into()];
    if let Some(id) = id.as_deref().filter(|id| is_installed(exe, id)) {
        return Ok(Step::Run(run(id)));
    }
    match paths::extension(&path).as_str() {
        ".vpk" | ".zip" => Ok(Step::InstallAndRun(vec!["-F".into(), path])),
        ".pkg" => {
            let id = id.ok_or("errors.vitaTitleId")?;
            let zrif = license(game).ok_or("errors.vitaLicense")?;
            Ok(Step::InstallPkg { install: vec!["--pkg".into(), path, "--zrif".into(), zrif], run: run(&id) })
        }
        _ => Err("errors.vitaFormat"),
    }
}

/// État pour la fiche du jeu : { titleId, installed, format, license, licenseFromServer, appDir }.
pub fn status(exe: Option<&str>, file: &Path, game: &Value) -> Value {
    let id = file.is_file().then(|| title_id(file)).flatten().or_else(|| title_id_from_name(&paths::file_name(file)));
    let format = paths::extension(&file.to_string_lossy()).trim_start_matches('.').to_string();
    json!({
        "titleId": id,
        "installed": matches!((exe, &id), (Some(exe), Some(id)) if is_installed(exe, id)),
        "format": format,
        "license": license(game),
        // Licence proposée par le serveur, pas encore changée sur ce PC.
        "licenseFromServer": local_license(&game["id"]).is_none() && license(game).is_some(),
        "appDir": exe.map(|e| vita_fs(e).join("ux0").join("app").to_string_lossy().into_owned()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identifiant_lu_dans_le_nom() {
        assert_eq!(title_id_from_name("EP0001-PCSB00061_00-LUMINESELECTROSY.pkg").as_deref(), Some("PCSB00061"));
        assert_eq!(title_id_from_name("Lumines [PCSB-00061].vpk").as_deref(), Some("PCSB00061"));
        assert_eq!(title_id_from_name("Gravity Rush (Europe) PCSF00008.vpk").as_deref(), Some("PCSF00008"));
        assert_eq!(title_id_from_name("Lumines (Europe).vpk"), None);
        assert_eq!(title_id_from_name("PCSB000612.vpk"), None);
    }

    #[test]
    fn identifiant_lu_dans_le_param_sfo() {
        // En-tête PSF, une entrée TITLE_ID (texte, 10 octets) : clés à 0x24, valeurs à 0x30.
        let mut sfo = b"\0PSF\x01\x01\0\0".to_vec();
        sfo.extend(0x24u32.to_le_bytes());
        sfo.extend(0x30u32.to_le_bytes());
        sfo.extend(1u32.to_le_bytes());
        sfo.extend(0u16.to_le_bytes());
        sfo.extend(0x0204u16.to_le_bytes());
        sfo.extend(10u32.to_le_bytes());
        sfo.extend(12u32.to_le_bytes());
        sfo.extend(0u32.to_le_bytes());
        sfo.extend(b"TITLE_ID\0\0\0\0");
        sfo.extend(b"PCSB00061\0\0\0");
        assert_eq!(title_id_from_sfo(&sfo).as_deref(), Some("PCSB00061"));
        assert_eq!(title_id_from_sfo(b"pas un sfo"), None);
    }
}
