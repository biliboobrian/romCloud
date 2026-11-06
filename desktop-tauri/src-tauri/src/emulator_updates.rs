// Mises à jour des émulateurs du catalogue publiés avec des Releases : dernière version comparée à
// celle installée (lue dans l'exécutable), puis téléchargée et décompressée à la place de
// l'ancienne. Versions et fichiers demandés au serveur RomCloud (mirror.rs), qui les récupère sur
// Internet et en garde une copie : une mise à jour n'est proposée qu'une fois sur le serveur. Eden (et eden-cli, même archive) : Releases de git.eden-emu.dev, archive Windows de
// la même variante que celle installée (MSVC, clang PGO ou GCC ; PGO, recommandée par Eden, sinon).
// Forks d'Eden pour la Switch 2 (Lichii, Orchid) : Releases GitHub (préversions comprises : Lichii
// ne publie que des « build-75 » en préversion), la plus récente qui a une archive Windows.
use crate::error::{AppError, Result};
use crate::{launcher, mirror, paths, settings};
use serde_json::{json, Value};
use std::io::Read;
use std::path::{Path, PathBuf};

/// Variantes Windows d'Eden, de la plus recommandée à la moins (PGO : « peuvent améliorer les performances »).
const EDEN_VARIANTS: &[&str] = &["clang-pgo", "msvc-standard", "gcc-standard", "clang-standard"];

/// Fork d'Eden pour la Switch 2 publié sur GitHub.
pub struct Nx2Fork {
    pub id: &'static str,
    pub name: &'static str,
    /// Début du nom des archives (« lichii-windows-x86_64.zip », « orchid-v0.7.4-windows-x86_64.zip »).
    prefix: &'static str,
    /// Exécutable de l'interface (jeux lancés avec lui : il lit nx2_switches.txt).
    pub exe: &'static str,
    /// Version écrite dans l'exécutable : « Lichii build-75 », « Orchid v0.7.4 ».
    marker: &'static [u8],
}

pub const NX2_FORKS: &[Nx2Fork] = &[
    // Releases des dépôts MythosOfs/Orchid et zortos293/Lichii (préversions comprises), via le serveur.
    Nx2Fork { id: "orchid", name: "Orchid", prefix: "orchid", exe: "orchid.exe", marker: b"Orchid " },
    Nx2Fork { id: "lichii", name: "Lichii", prefix: "lichii", exe: "lichii.exe", marker: b"Lichii " },
];

/// Archives Windows des forks Switch 2, de la plus recommandée à la moins : la standard d'abord,
/// seule à contenir le décodeur AV1 (dav1d) des vidéos des jeux de la Switch 2 (sans lui, écran
/// noir pendant la vidéo d'ouverture : Ocarina of Time), la PGO à défaut.
const NX2_VARIANTS: &[&str] = &["x86_64", "x86_64-pgo"];

pub fn nx2_fork(id: &str) -> Option<&'static Nx2Fork> {
    NX2_FORKS.iter().find(|f| f.id == id)
}

/// Émulateurs dont RomCloud sait trouver et installer la dernière version.
pub fn supported(id: &str) -> bool {
    id == "eden" || nx2_fork(id).is_some()
}

fn display_name(id: &str) -> &'static str {
    nx2_fork(id).map(|f| f.name).unwrap_or("Eden")
}

/// Version d'un fork écrite dans son exécutable (« Lichii build-75 » -> « build-75 », « Orchid v0.7.4 » -> « v0.7.4 »).
pub fn nx2_version(fork: &Nx2Fork, exe: &[u8]) -> Option<String> {
    let marker = fork.marker;
    exe.windows(marker.len()).enumerate().filter(|(_, w)| *w == marker).find_map(|(i, _)| {
        let version: String = exe[i + marker.len()..]
            .iter()
            .take(32)
            .take_while(|c| c.is_ascii_alphanumeric() || **c == b'.' || **c == b'-')
            .map(|c| *c as char)
            .collect();
        let valid = version.strip_prefix("build-").or_else(|| version.strip_prefix('v')).is_some_and(|n| n.starts_with(|c: char| c.is_ascii_digit()));
        valid.then_some(version)
    })
}

/// Variante d'un exécutable de fork : standard avec le décodeur AV1 (dav1d), sinon PGO.
pub fn nx2_variant(exe: &[u8]) -> &'static str {
    let marker = b"dav1d";
    if exe.windows(marker.len()).any(|w| w == marker) { NX2_VARIANTS[0] } else { NX2_VARIANTS[1] }
}

/// Archive Windows du fork pour la variante voulue, sinon la plus recommandée : (nom, adresse, variante).
pub fn nx2_asset(fork: &Nx2Fork, release: &Value, wanted: &str) -> Option<(String, String, String)> {
    let assets = release["assets"].as_array()?;
    let tag = release["tag_name"].as_str().unwrap_or("");
    std::iter::once(wanted).chain(NX2_VARIANTS.iter().copied()).filter(|v| !v.is_empty()).find_map(|variant| {
        // Avec ou sans la version dans le nom (« orchid-v0.7.4-windows-x86_64.zip »).
        let names = [format!("{}-windows-{variant}.zip", fork.prefix), format!("{}-{tag}-windows-{variant}.zip", fork.prefix)];
        let asset = assets.iter().find(|a| a["name"].as_str().is_some_and(|n| names.iter().any(|x| x == n)))?;
        Some((asset["name"].as_str()?.to_string(), asset["browser_download_url"].as_str()?.to_string(), variant.to_string()))
    })
}

/// Releases du fork (les plus récentes d'abord) : la première qui a une archive Windows.
pub fn nx2_release(fork: &Nx2Fork, releases: &Value) -> Value {
    releases
        .as_array()
        .and_then(|list| list.iter().find(|r| !r["draft"].as_bool().unwrap_or(false) && nx2_asset(fork, r, "").is_some()))
        .cloned()
        .unwrap_or_else(|| json!({}))
}

/// Numéro d'une version « build-75 » (-> 75).
fn build_number(version: &str) -> u64 {
    version.rsplit('-').next().and_then(|n| n.parse().ok()).unwrap_or(0)
}

/// Archive Windows de la Release pour l'émulateur [id] et la variante voulue.
fn asset_for(id: &str, release: &Value, wanted: &str) -> Option<(String, String, String)> {
    match nx2_fork(id) {
        Some(fork) => nx2_asset(fork, release, wanted),
        None => pick_asset(release, wanted),
    }
}

/// Version [candidate] plus récente que celle installée : numéros de build (Lichii), sinon « v0.7.4 ».
fn newer(_id: &str, candidate: &str, installed: &str) -> bool {
    if candidate.starts_with("build-") && installed.starts_with("build-") {
        build_number(candidate) > build_number(installed)
    } else {
        is_newer(candidate, installed)
    }
}

/// Variante à installer : celle installée (Eden), la plus recommandée (forks : la standard).
fn wanted_variant(id: &str, installed: &str) -> String {
    if nx2_fork(id).is_some() { default_variant(id).into() } else { installed.into() }
}

fn default_variant(id: &str) -> &'static str {
    if nx2_fork(id).is_some() { NX2_VARIANTS[0] } else { "amd64-clang-pgo" }
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

/// Exécutable installé : Eden (eden.exe, sinon eden-cli.exe) dans le dossier enregistré, forks (lichii.exe, orchid.exe).
fn installed_exe(id: &str) -> Option<PathBuf> {
    if nx2_fork(id).is_some() {
        return launcher::emulator_path(id).map(PathBuf::from);
    }
    crate::catalog::exe_in_folder(&launcher::emulator_folder(id)?, &["eden.exe", "eden-cli.exe"])
}

/// Dossier d'installation par défaut (première installation par RomCloud).
fn default_dir(id: &str) -> PathBuf {
    PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_default()).join("Programs").join(display_name(id))
}

/// Dernière version (liste du serveur, la plus récente d'abord) qui a une archive Windows pour l'émulateur [id].
pub fn latest_in(id: &str, releases: &Value, variant: &str) -> Value {
    if let Some(fork) = nx2_fork(id) {
        return nx2_release(fork, releases);
    }
    releases
        .as_array()
        .and_then(|list| list.iter().find(|r| pick_asset(r, variant).is_some()))
        .cloned()
        .unwrap_or_else(|| json!({}))
}

async fn latest_release(id: &str, variant: &str) -> Result<Value> {
    Ok(latest_in(id, &mirror::releases(id).await?, variant))
}

/// État : { installed, variant, latest, asset, updateAvailable, dir, server, preparing, serverReceived,
/// serverTotal, serverError } ; [server] : état de l'archive sur le serveur (stored, fetching, remote,
/// error). Version plus récente pas encore sur le serveur : récupération demandée au serveur
/// (preparing), mise à jour proposée (updateAvailable) une fois qu'il l'a.
pub async fn check(id: &str) -> Result<Value> {
    if !supported(id) {
        return Err(AppError::new("errors.unknownEmulator", json!({ "id": id })));
    }
    let exe = installed_exe(id);
    let fork = nx2_fork(id);
    let (installed, detected) = match &exe {
        Some(path) => {
            let path = path.clone();
            tokio::task::spawn_blocking(move || {
                // eden.exe de préférence (version et variante identiques à celles d'eden-cli).
                let data = std::fs::read(&path).unwrap_or_default();
                if let Some(fork) = fork {
                    (nx2_version(fork, &data), Some(nx2_variant(&data).to_string()))
                } else {
                    (eden_version(&data), eden_variant(&data))
                }
            })
            .await
            .map_err(|e| AppError::msg(e.to_string()))?
        }
        None => (None, None),
    };
    // Variante installée par RomCloud, sinon reconnue dans l'exécutable, sinon la plus recommandée.
    // Forks Switch 2 : variante reconnue dans l'exécutable ; la standard (vidéos AV1) remplace la PGO.
    let variant = if fork.is_some() { detected.clone() } else { None }
        .or_else(|| setting_map("emulatorVariants").get(id).and_then(Value::as_str).map(String::from))
        .or(detected)
        .unwrap_or_else(|| default_variant(id).into());
    let release = latest_release(id, &wanted_variant(id, &variant)).await?;
    let latest = release["tag_name"].as_str().unwrap_or("").to_string();
    let asset = asset_for(id, &release, &wanted_variant(id, &variant));
    let replace_variant = fork.is_some() && installed.is_some() && asset.as_ref().is_some_and(|(_, _, v)| *v != variant);
    let newer_build = asset.is_some() && (replace_variant || installed.as_deref().is_none_or(|v| newer(id, &latest, v)));
    // Archive sur le serveur : sinon demandée (le serveur la récupère, l'interface suit la progression).
    let mut server = asset.as_ref().and_then(|(name, _, _)| mirror::asset(&release, name)).cloned().unwrap_or_else(|| json!({}));
    if let (true, Some((_, url, _))) = (newer_build && !mirror::is_stored(&server), &asset) {
        server = mirror::prepare(url).await?;
    }
    let stored = mirror::is_stored(&server);
    let server_state = server["state"].as_str().unwrap_or("remote");
    Ok(json!({
        "server": server_state,
        "preparing": newer_build && server_state == "fetching",
        "serverReceived": server["received"].as_u64().unwrap_or(0),
        "serverTotal": server["total"].as_u64().unwrap_or(0),
        "serverError": server["error"].as_str(),
        "installed": installed,
        "variant": variant,
        "latest": latest,
        "asset": asset.map(|(name, _, _)| name),
        "updateAvailable": newer_build && stored,
        "dir": exe.and_then(|p| p.parent().map(|d| d.to_string_lossy().into_owned())).unwrap_or_else(|| default_dir(id).to_string_lossy().into_owned()),
    }))
}

/// Extrait l'archive dans [dir] (fichiers remplacés) ; refusé si l'émulateur [name] est ouvert (fichier utilisé).
fn extract(zip: &[u8], dir: &Path, name: &str) -> Result<()> {
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
                AppError::new("errors.emulatorRunning", json!({ "name": name }))
            } else {
                e.into()
            }
        })?;
    }
    Ok(())
}

/// Télécharge la dernière version et l'installe dans [dir] (choisi à la première installation,
/// créé s'il n'existe pas), sinon à la place de l'actuelle (ou dans le dossier par défaut) ;
/// emplacement enregistré (dossier d'Eden, exécutable d'un fork : lichii.exe, orchid.exe) ; renvoie le nouvel état ([check]).
pub async fn install(id: &str, dir: Option<&str>) -> Result<Value> {
    let state = check(id).await?;
    let variant = wanted_variant(id, state["variant"].as_str().unwrap_or(default_variant(id)));
    let release = latest_release(id, &variant).await?;
    let (name, url, picked) = asset_for(id, &release, &variant).ok_or_else(|| AppError::msg("archive Windows introuvable".to_string()))?;
    // Archive récupérée par le serveur (si ce n'est pas déjà fait), puis téléchargée depuis lui.
    mirror::wait_stored(&url, &name, |_, _| {}).await?;
    let zip = mirror::download(&url).await?;
    let dir = PathBuf::from(dir.map(str::trim).filter(|d| !d.is_empty()).unwrap_or(state["dir"].as_str().unwrap_or("")));
    {
        let dir = dir.clone();
        let emulator = display_name(id);
        tokio::task::spawn_blocking(move || {
            std::fs::create_dir_all(&dir)?;
            extract(&zip, &dir, emulator)
        }).await.map_err(|e| AppError::msg(e.to_string()))??;
    }
    // Variante gardée pour les prochaines mises à jour (celle de l'archive installée).
    let mut variants = setting_map("emulatorVariants");
    variants.insert(id.into(), json!(picked));
    settings::save(json!({ "emulatorVariants": variants }));
    // Dossier d'Eden enregistré (eden.exe et eden-cli.exe) ; fork : son exécutable.
    let location = match nx2_fork(id) {
        Some(fork) => dir.join(fork.exe),
        None => dir.clone(),
    };
    launcher::set_emulator_path(id, &location.to_string_lossy())?;
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
        extract(&bytes, &dir, "Eden").unwrap();
        assert_eq!(std::fs::read_to_string(dir.join("eden.exe")).unwrap(), "v0.3.0");
        assert_eq!(std::fs::read_to_string(dir.join("LICENSES").join("GPL.txt")).unwrap(), "gpl");
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn github_release(tag: &str, names: &[&str]) -> Value {
        json!({ "tag_name": tag, "prerelease": true, "draft": false,
            "assets": names.iter().map(|n| json!({ "name": n, "browser_download_url": format!("https://x/{tag}/{n}") })).collect::<Vec<_>>() })
    }

    #[test]
    fn releases_de_lichii() {
        let lichii = nx2_fork("lichii").unwrap();
        // Préversions, les plus récentes d'abord.
        let releases = json!([
            github_release("build-76", &["lichii-linux-x86_64.AppImage"]), // pas encore d'archive Windows
            github_release("build-75", &["lichii-linux-x86_64-pgo.tar.gz", "lichii-macos-arm64.zip", "lichii-windows-x86_64-pgo.zip", "lichii-windows-x86_64.zip"]),
            github_release("build-70", &["lichii-windows-x86_64-pgo.zip", "lichii-windows-x86_64.zip"]),
        ]);
        let latest = nx2_release(lichii, &releases);
        assert_eq!(latest["tag_name"], "build-75");
        assert_eq!(nx2_asset(lichii, &latest, "").unwrap().0, "lichii-windows-x86_64.zip"); // standard d'abord (AV1)
        assert_eq!(wanted_variant("lichii", "x86_64-pgo"), "x86_64");
        assert_eq!(nx2_variant(b"..libdav1d 1.5.1.."), "x86_64");
        assert_eq!(nx2_variant(b"..h264_.."), "x86_64-pgo");
        let pgo = nx2_asset(lichii, &latest, "x86_64-pgo").unwrap();
        assert_eq!((pgo.0.as_str(), pgo.1.as_str(), pgo.2.as_str()), ("lichii-windows-x86_64-pgo.zip", "https://x/build-75/lichii-windows-x86_64-pgo.zip", "x86_64-pgo"));
        assert_eq!(nx2_asset(lichii, &latest, "arm64").unwrap().2, "x86_64"); // absente : la standard
        assert_eq!(nx2_release(lichii, &json!([])), json!({}));
        assert!(asset_for("lichii", &json!({}), "x86_64-pgo").is_none());

        let mut exe = b"..Lichii Version: {}\0Lichii build-75 \0".to_vec();
        assert_eq!(nx2_version(lichii, &exe).as_deref(), Some("build-75"));
        exe.truncate(4);
        assert_eq!(nx2_version(lichii, &exe), None);
        assert!(newer("lichii", "build-100", "build-75"));
        assert!(!newer("lichii", "build-75", "build-75"));
        assert!(supported("lichii") && supported("orchid") && supported("eden") && !supported("ryujinx"));
    }

    #[test]
    fn releases_d_orchid() {
        let orchid = nx2_fork("orchid").unwrap();
        // Versions publiées (pas des préversions), la version dans le nom des archives.
        let releases = json!([
            github_release("v0.7.4", &["orchid-v0.7.4-macos-arm64.zip", "orchid-v0.7.4-windows-x86_64-pgo.zip", "orchid-v0.7.4-windows-x86_64.zip"]),
            github_release("v0.7.3", &["orchid-v0.7.3-windows-x86_64.zip"]),
        ]);
        let latest = nx2_release(orchid, &releases);
        assert_eq!(latest["tag_name"], "v0.7.4");
        let std = asset_for("orchid", &latest, &wanted_variant("orchid", "x86_64-pgo")).unwrap();
        assert_eq!((std.0.as_str(), std.2.as_str()), ("orchid-v0.7.4-windows-x86_64.zip", "x86_64"));
        assert_eq!(nx2_asset(orchid, &latest, "x86_64-pgo").unwrap().0, "orchid-v0.7.4-windows-x86_64-pgo.zip");
        // « Orchid Version: {} » ignoré, « Orchid v0.7.4 » lu.
        let exe = b"..Orchid Version: {}\0Orchid v0.7.4   v0.7.4  main\0".to_vec();
        assert_eq!(nx2_version(orchid, &exe).as_deref(), Some("v0.7.4"));
        assert!(newer("orchid", "v0.7.10", "v0.7.4"));
        assert!(!newer("orchid", "v0.7.4", "v0.7.4"));
        assert_eq!(orchid.exe, "orchid.exe");
    }

    #[test]
    fn derniere_version_du_serveur_avec_une_archive_windows() {
        // Liste du serveur : Release Android seule d'abord, puis la dernière avec archives Windows.
        let list = json!([
            { "tag_name": "v0.3.0-android", "assets": [{ "name": "Eden-Android-v0.3.0-standard.apk", "browser_download_url": "u" }] },
            release(),
        ]);
        assert_eq!(latest_in("eden", &list, "amd64-clang-pgo")["tag_name"], "v0.2.1");
        assert_eq!(latest_in("eden", &json!([]), "amd64-clang-pgo"), json!({}));
        let forks = json!([github_release("build-75", &["lichii-windows-x86_64.zip"])]);
        assert_eq!(latest_in("lichii", &forks, "x86_64")["tag_name"], "build-75");
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
