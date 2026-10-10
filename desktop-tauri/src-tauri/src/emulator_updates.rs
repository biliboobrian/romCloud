// Mises à jour des émulateurs du catalogue publiés avec des Releases : dernière version comparée à
// celle installée (lue dans l'exécutable), puis téléchargée et décompressée à la place de
// l'ancienne. Eden (et eden-cli, même archive) : Releases de git.eden-emu.dev, archive Windows de
// la même variante que celle installée (MSVC, clang PGO ou GCC ; PGO, recommandée par Eden, sinon).
use crate::error::{AppError, Result};
use crate::{api, launcher, paths, settings};
use serde_json::{json, Value};
use std::io::Read;
use std::path::{Path, PathBuf};

const EDEN_RELEASES: &str = "https://git.eden-emu.dev/api/v1/repos/eden-emu/eden/releases/latest";
/// Variantes Windows d'Eden, de la plus recommandée à la moins (PGO : « peuvent améliorer les performances »).
const EDEN_VARIANTS: &[&str] = &["clang-pgo", "msvc-standard", "gcc-standard", "clang-standard"];

/// Émulateurs dont RomCloud sait trouver et installer la dernière version.
pub fn supported(id: &str) -> bool {
    matches!(id, "eden" | "eden-cli")
}

/// Version d'Eden écrite dans son exécutable (« Eden v0.2.1 » -> « v0.2.1 »).
pub fn eden_version(exe: &[u8]) -> Option<String> {
    let marker = b"Eden v";
    let start = exe.windows(marker.len()).position(|w| w == marker)? + marker.len() - 1;
    let version: String = exe[start..]
        .iter()
        .take(32)
        .take_while(|c| c.is_ascii_alphanumeric() || **c == b'.' || **c == b'-')
        .map(|c| *c as char)
        .collect();
    (version.len() > 1 && version[1..].starts_with(|c: char| c.is_ascii_digit())).then_some(version)
}

/// Architecture et variante d'un exécutable d'Eden : « amd64-msvc-standard », « arm64-clang-pgo »…
pub fn eden_variant(exe: &[u8]) -> Option<String> {
    let pe = u32::from_le_bytes(exe.get(0x3c..0x40)?.try_into().ok()?) as usize;
    let machine = u16::from_le_bytes(exe.get(pe + 4..pe + 6)?.try_into().ok()?);
    let arch = match machine {
        0x8664 => "amd64",
        0xAA64 => "arm64",
        _ => return None,
    };
    let has = |s: &[u8]| exe.windows(s.len()).any(|w| w == s);
    // Marques des archives publiées : runtime de Visual C++ (MSVC), objets GCC liés par clang (PGO).
    let variant = if has(b"VCRUNTIME140") {
        "msvc-standard"
    } else if has(b"GCC: (") {
        "clang-pgo"
    } else if arch == "arm64" {
        "clang-standard"
    } else {
        "gcc-standard"
    };
    Some(format!("{arch}-{variant}"))
}

/// Archive Windows de la Release pour la variante voulue, sinon la plus recommandée de la même
/// architecture : (nom, adresse, variante « amd64-msvc-standard »).
pub fn pick_asset(release: &Value, wanted: &str) -> Option<(String, String, String)> {
    let assets: Vec<(String, String)> = release["assets"]
        .as_array()?
        .iter()
        .filter_map(|a| Some((a["name"].as_str()?.to_string(), a["browser_download_url"].as_str()?.to_string())))
        .filter(|(name, _)| name.starts_with("Eden-Windows-") && name.ends_with(".zip"))
        .collect();
    let arch = wanted.split('-').next().unwrap_or("amd64");
    // Ancien nommage : « …-mingw-amd64-clang-pgo.zip ».
    let matches = |name: &str, variant: &str| name.ends_with(&format!("-{arch}-{variant}.zip"));
    let wanted_variant = wanted.split_once('-').map(|(_, v)| v).unwrap_or("");
    std::iter::once(wanted_variant)
        .chain(EDEN_VARIANTS.iter().copied())
        .find_map(|variant| {
            let (name, url) = assets.iter().find(|(name, _)| !variant.is_empty() && matches(name, variant))?;
            Some((name.clone(), url.clone(), format!("{arch}-{variant}")))
        })
}

/// « v0.2.1 » < « v0.10.0 » ; une version candidate (« -rc2 ») précède la version finale.
pub fn is_newer(candidate: &str, installed: &str) -> bool {
    fn key(v: &str) -> (Vec<u64>, bool) {
        let v = v.trim().trim_start_matches('v');
        let (numbers, pre) = v.split_once('-').map(|(n, p)| (n, !p.is_empty())).unwrap_or((v, false));
        (numbers.split('.').map(|n| n.parse().unwrap_or(0)).collect(), !pre)
    }
    let (a, b) = (key(candidate), key(installed));
    let len = a.0.len().max(b.0.len());
    let pad = |v: &Vec<u64>| (0..len).map(|i| v.get(i).copied().unwrap_or(0)).collect::<Vec<_>>();
    (pad(&a.0), a.1) > (pad(&b.0), b.1)
}

fn setting_map(key: &str) -> serde_json::Map<String, Value> {
    settings::load().get(key).and_then(Value::as_object).cloned().unwrap_or_default()
}

/// Exécutable installé de l'émulateur (chemin choisi ou trouvé) ; pour Eden, l'un ou l'autre des deux.
fn installed_exe(id: &str) -> Option<PathBuf> {
    let paths = setting_map("emulatorPaths");
    let ids: &[&str] = if id.starts_with("eden") { &["eden", "eden-cli"] } else { &[id][..] };
    ids.iter().filter_map(|i| paths.get(*i).and_then(Value::as_str)).map(PathBuf::from).find(|p| p.is_file())
}

/// Dossier d'installation par défaut (première installation par RomCloud).
fn default_dir() -> PathBuf {
    PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_default()).join("Programs").join("Eden")
}

async fn latest_release() -> Result<Value> {
    let res = api::client()
        .get(EDEN_RELEASES)
        .header("Accept", "application/json")
        .header("User-Agent", "RomCloud")
        .timeout(std::time::Duration::from_secs(20))
        .send()
        .await
        .map_err(|e| AppError::new("errors.unreachable", json!({ "detail": api::network_detail(&e) })))?;
    if !res.status().is_success() {
        return Err(AppError::new("errors.http", json!({ "status": res.status().as_u16() })));
    }
    Ok(res.json().await?)
}

/// État : { installed, variant, latest, asset, updateAvailable, dir }.
pub async fn check(id: &str) -> Result<Value> {
    if !supported(id) {
        return Err(AppError::new("errors.unknownEmulator", json!({ "id": id })));
    }
    let exe = installed_exe(id);
    let (installed, detected) = match &exe {
        Some(path) => {
            let path = path.clone();
            tokio::task::spawn_blocking(move || {
                // eden.exe de préférence (version et variante identiques à celles d'eden-cli).
                let main = path.with_file_name("eden.exe");
                let data = std::fs::read(if main.is_file() { &main } else { &path }).unwrap_or_default();
                (eden_version(&data), eden_variant(&data))
            })
            .await
            .map_err(|e| AppError::msg(e.to_string()))?
        }
        None => (None, None),
    };
    // Variante installée par RomCloud, sinon reconnue dans l'exécutable, sinon la plus recommandée.
    let variant = setting_map("emulatorVariants").get("eden").and_then(Value::as_str).map(String::from).or(detected).unwrap_or_else(|| "amd64-clang-pgo".into());
    let release = latest_release().await?;
    let latest = release["tag_name"].as_str().unwrap_or("").to_string();
    let asset = pick_asset(&release, &variant);
    let update = asset.is_some() && installed.as_deref().is_none_or(|v| is_newer(&latest, v));
    Ok(json!({
        "installed": installed,
        "variant": variant,
        "latest": latest,
        "asset": asset.map(|(name, _, _)| name),
        "updateAvailable": update,
        "dir": exe.and_then(|p| p.parent().map(|d| d.to_string_lossy().into_owned())).unwrap_or_else(|| default_dir().to_string_lossy().into_owned()),
    }))
}

/// Extrait l'archive dans [dir] (fichiers remplacés) ; refusé si Eden est ouvert (fichier utilisé).
fn extract(zip: &[u8], dir: &Path) -> Result<()> {
    let mut archive = zip::ZipArchive::new(std::io::Cursor::new(zip)).map_err(|e| AppError::msg(e.to_string()))?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| AppError::msg(e.to_string()))?;
        let Some(relative) = entry.enclosed_name() else { continue };
        let target = dir.join(relative);
        if entry.is_dir() {
            std::fs::create_dir_all(&target)?;
            continue;
        }
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut data = Vec::with_capacity(entry.size() as usize);
        entry.read_to_end(&mut data)?;
        std::fs::write(&target, &data).map_err(|e| {
            if e.raw_os_error() == Some(32) {
                AppError::new("errors.emulatorRunning", json!({ "name": "Eden" }))
            } else {
                e.into()
            }
        })?;
    }
    Ok(())
}

/// Télécharge la dernière version et l'installe à la place de l'actuelle (ou dans le dossier par
/// défaut) ; chemins d'Eden et d'eden-cli enregistrés ; renvoie le nouvel état ([check]).
pub async fn install(id: &str) -> Result<Value> {
    let state = check(id).await?;
    let release = latest_release().await?;
    let variant = state["variant"].as_str().unwrap_or("amd64-clang-pgo").to_string();
    let (name, url, picked) = pick_asset(&release, &variant).ok_or_else(|| AppError::msg("archive Windows introuvable".to_string()))?;
    let res = api::client()
        .get(&url)
        .header("User-Agent", "RomCloud")
        .timeout(std::time::Duration::from_secs(1800))
        .send()
        .await
        .map_err(|e| AppError::new("errors.unreachable", json!({ "detail": api::network_detail(&e) })))?;
    if !res.status().is_success() {
        return Err(AppError::new("errors.http", json!({ "status": res.status().as_u16() })));
    }
    let zip = res.bytes().await?.to_vec();
    let dir = PathBuf::from(state["dir"].as_str().unwrap_or(""));
    {
        let dir = dir.clone();
        tokio::task::spawn_blocking(move || extract(&zip, &dir)).await.map_err(|e| AppError::msg(e.to_string()))??;
    }
    // Variante gardée pour les prochaines mises à jour (celle de l'archive installée).
    let mut variants = setting_map("emulatorVariants");
    variants.insert("eden".into(), json!(picked));
    settings::save(json!({ "emulatorVariants": variants }));
    for (emulator, exe) in [("eden", "eden.exe"), ("eden-cli", "eden-cli.exe")] {
        let path = dir.join(exe);
        if path.is_file() {
            launcher::set_emulator_path(emulator, &path.to_string_lossy())?;
        }
    }
    eprintln!("[emulator_updates] {name} -> {}", paths::file_name(&dir));
    check(id).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn release() -> Value {
        let names = [
            "Eden-Android-v0.2.1-standard.apk",
            "Eden-Windows-v0.2.1-amd64-clang-pgo.zip",
            "Eden-Windows-v0.2.1-amd64-gcc-standard.zip",
            "Eden-Windows-v0.2.1-amd64-msvc-standard.zip",
            "Eden-Windows-v0.2.1-arm64-clang-pgo.zip",
            "Eden-Windows-v0.2.1-arm64-clang-standard.zip",
            "Eden-Windows-v0.2.1-rog-ally-clang-pgo.zip",
        ];
        json!({ "tag_name": "v0.2.1", "assets": names.iter().map(|n| json!({ "name": n, "browser_download_url": format!("https://x/{n}") })).collect::<Vec<_>>() })
    }

    #[test]
    fn archive_de_la_variante_installee() {
        let r = release();
        assert_eq!(pick_asset(&r, "amd64-msvc-standard").unwrap().0, "Eden-Windows-v0.2.1-amd64-msvc-standard.zip");
        assert_eq!(pick_asset(&r, "amd64-clang-pgo").unwrap().0, "Eden-Windows-v0.2.1-amd64-clang-pgo.zip");
        let fallback = pick_asset(&r, "arm64-gcc-standard").unwrap(); // absente : la PGO
        assert_eq!((fallback.0.as_str(), fallback.2.as_str()), ("Eden-Windows-v0.2.1-arm64-clang-pgo.zip", "arm64-clang-pgo"));
        assert_eq!(pick_asset(&r, "amd64-msvc-standard").unwrap().2, "amd64-msvc-standard");
        // Ancien nommage (v0.2.0-rc2).
        let old = json!({ "assets": [{ "name": "Eden-Windows-v0.2.0-rc2-mingw-amd64-clang-pgo.zip", "browser_download_url": "u" }] });
        assert_eq!(pick_asset(&old, "amd64-clang-pgo").unwrap().0, "Eden-Windows-v0.2.0-rc2-mingw-amd64-clang-pgo.zip");
    }

    #[test]
    fn version_et_variante_lues_dans_l_executable() {
        let mut exe = vec![0u8; 0x200];
        exe[0x3c..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        exe[0x80..0x84].copy_from_slice(b"PE\0\0");
        exe[0x84..0x86].copy_from_slice(&0x8664u16.to_le_bytes());
        exe.extend_from_slice(b"..Eden v0.2.1\0..VCRUNTIME140.dll\0");
        assert_eq!(eden_version(&exe).as_deref(), Some("v0.2.1"));
        assert_eq!(eden_variant(&exe).as_deref(), Some("amd64-msvc-standard"));
        assert_eq!(eden_version(b"rien"), None);
    }

    /// Exécutable réel : `ROMCLOUD_EDEN_EXE=C:\…\eden.exe cargo test -- --ignored eden_installe`.
    #[test]
    #[ignore]
    fn eden_installe() {
        let data = std::fs::read(std::env::var("ROMCLOUD_EDEN_EXE").unwrap()).unwrap();
        println!("version {:?}, variante {:?}", eden_version(&data), eden_variant(&data));
        assert!(eden_version(&data).is_some() && eden_variant(&data).is_some());
    }

    #[test]
    fn archive_extraite_par_dessus_l_ancienne_version() {
        use std::io::Write;
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default();
        for (name, data) in [("eden.exe", "v0.3.0"), ("eden-cli.exe", "cli"), ("LICENSES/GPL.txt", "gpl")] {
            zip.start_file(name, options).unwrap();
            zip.write_all(data.as_bytes()).unwrap();
        }
        let bytes = zip.finish().unwrap().into_inner();
        let dir = std::env::temp_dir().join(format!("romcloud-edenzip-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("eden.exe"), "v0.2.1").unwrap();
        extract(&bytes, &dir).unwrap();
        assert_eq!(std::fs::read_to_string(dir.join("eden.exe")).unwrap(), "v0.3.0");
        assert_eq!(std::fs::read_to_string(dir.join("LICENSES").join("GPL.txt")).unwrap(), "gpl");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn comparaison_des_versions() {
        assert!(is_newer("v0.3.0", "v0.2.1"));
        assert!(is_newer("v0.10.0", "v0.9.9"));
        assert!(is_newer("v0.2.0", "v0.2.0-rc2"));
        assert!(!is_newer("v0.2.1", "v0.2.1"));
        assert!(!is_newer("v0.2.0-rc2", "v0.2.0"));
    }
}
