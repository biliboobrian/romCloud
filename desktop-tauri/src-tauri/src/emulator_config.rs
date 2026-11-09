// Réglages des émulateurs du catalogue modifiés depuis RomCloud (fenêtre « Configurer … ») : fichiers
// INI de l'émulateur lus et mis à jour clé par clé, le reste du fichier gardé tel quel. Les champs
// proposés (sections, clés, valeurs) sont décrits par renderer/emulator-config/emulator-config.json (commun
// avec Android : EmulatorConfig.kt) ;
// ici, seulement l'emplacement des fichiers et leur format :
//  - Eden, Lichii, Orchid, Fritchii : config\qt-config.ini du dossier de données d'Eden (« user » à
//    côté de l'exécutable, sinon %APPDATA%\eden ; partagé par les forks), « clé=valeur » précédé de
//    « clé\default=false » (sinon Eden ignore la valeur et prend celle par défaut) ; eden-cli reprend
//    ces réglages au lancement (emulator_files.rs) ;
//  - Dolphin : Config\Dolphin.ini et Config\GFX.ini du dossier utilisateur (User\ à côté de
//    l'exécutable avec portable.txt, sinon Documents\Dolphin Emulator s'il existe, sinon
//    %APPDATA%\Dolphin Emulator), « Clé = Valeur » ;
//  - Vita3K : config.yml à côté de l'exécutable (portable\config.yml avec un dossier portable),
//    « clé: valeur » au premier niveau (section vide) ; relu par Vita3K à son démarrage.
use crate::error::{AppError, Result};
use crate::{emulator_files, launcher, paths};
use serde_json::{json, Map, Value};
use std::path::{Path, PathBuf};

#[derive(Clone, Copy, PartialEq, Debug)]
enum Style {
    /// « clé=valeur » avec « clé\default=false » juste avant (QSettings d'Eden).
    Eden,
    /// « Clé = Valeur » (Dolphin).
    Spaced,
    /// « clé: valeur » au premier niveau, sans section (config.yml de Vita3K).
    Yaml,
}

struct Target {
    dir: PathBuf,
    files: &'static [&'static str],
    style: Style,
}

fn appdata() -> PathBuf {
    PathBuf::from(std::env::var("APPDATA").unwrap_or_default())
}

/// Dossier utilisateur de Dolphin pour l'exécutable [exe].
fn dolphin_user_dir(exe_dir: &Path, documents: &Path, appdata: &Path) -> PathBuf {
    if exe_dir.join("portable.txt").is_file() {
        return exe_dir.join("User");
    }
    let docs = documents.join("Dolphin Emulator");
    if docs.is_dir() { docs } else { appdata.join("Dolphin Emulator") }
}

fn target_for(id: &str, exe: &Path, documents: &Path, appdata: &Path) -> Option<Target> {
    let exe_dir = exe.parent().map(Path::to_path_buf).unwrap_or_default();
    match id {
        "eden" | "lichii" | "orchid" | "fritchii" => Some(Target {
            dir: emulator_files::eden_dir(&exe_dir, appdata).join("config"),
            files: &["qt-config.ini"],
            style: Style::Eden,
        }),
        "vita3k" => Some(Target { dir: crate::vita3k::config_dir(&exe.to_string_lossy()), files: &["config.yml"], style: Style::Yaml }),
        "dolphin" => Some(Target {
            dir: dolphin_user_dir(&exe_dir, documents, appdata).join("Config"),
            files: &["Dolphin.ini", "GFX.ini"],
            style: Style::Spaced,
        }),
        _ => None,
    }
}

/// Émulateur dont RomCloud sait modifier les réglages.
pub fn supported(id: &str) -> bool {
    matches!(id, "eden" | "lichii" | "orchid" | "fritchii" | "dolphin" | "vita3k")
}

fn target(id: &str) -> Result<Target> {
    let exe = launcher::emulator_path(id).ok_or_else(|| AppError::new("errors.emulatorMissing", json!({ "id": id })))?;
    target_for(id, Path::new(&exe), &paths::documents(), &appdata()).ok_or_else(|| AppError::new("errors.unknownEmulator", json!({ "id": id })))
}

// ---------------------------------------------------------------------------
// Fichiers INI
// ---------------------------------------------------------------------------

/// Clé et valeur d'une ligne « clé=valeur » (espaces autour du « = » retirés).
fn key_value(line: &str) -> Option<(&str, &str)> {
    let trimmed = line.trim();
    if trimmed.starts_with(['#', ';', '[']) {
        return None;
    }
    let (k, v) = trimmed.split_once('=')?;
    Some((k.trim(), v.trim()))
}

/// Valeurs d'un fichier INI : { "Section/clé": "valeur" } (lignes « clé\default » ignorées).
fn parse(text: &str) -> Map<String, Value> {
    let mut out = Map::new();
    let mut section = String::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            section = trimmed[1..trimmed.len() - 1].to_string();
        } else if let Some((k, v)) = key_value(line) {
            if !k.ends_with("\\default") {
                out.insert(format!("{section}/{k}"), Value::String(v.to_string()));
            }
        }
    }
    out
}

/// [text] avec [key] de [section] à [value] : ligne remplacée, sinon ajoutée à la fin de la section
/// (avant ses lignes vides), section ajoutée à la fin du fichier si absente.
fn set_value(text: &str, section: &str, key: &str, value: &str, style: Style) -> String {
    let eol = if text.contains("\r\n") { "\r\n" } else { "\n" };
    let default_key = format!("{key}\\default");
    let new_lines = match style {
        Style::Eden => vec![format!("{default_key}=false"), format!("{key}={value}")],
        Style::Spaced | Style::Yaml => vec![format!("{key} = {value}")],
    };
    let mut lines: Vec<String> = text.lines().map(String::from).collect();
    let header = format!("[{section}]");
    let Some(start) = lines.iter().position(|l| l.trim().eq_ignore_ascii_case(&header)) else {
        if lines.last().is_some_and(|l| !l.trim().is_empty()) {
            lines.push(String::new());
        }
        lines.push(header);
        lines.extend(new_lines);
        return lines.join(eol) + eol;
    };
    let end = lines[start + 1..].iter().position(|l| l.trim().starts_with('[')).map_or(lines.len(), |i| start + 1 + i);
    let same = |l: &str, k: &str| key_value(l).is_some_and(|(lk, _)| lk.eq_ignore_ascii_case(k));
    // Anciennes lignes de la clé (et de son « \default ») retirées ; nouvelles à la place de la première.
    let mut at = None;
    let mut i = start + 1;
    let mut stop = end;
    while i < stop {
        if same(&lines[i], key) || (style == Style::Eden && same(&lines[i], &default_key)) {
            at.get_or_insert(i);
            lines.remove(i);
            stop -= 1;
        } else {
            i += 1;
        }
    }
    let at = at.unwrap_or_else(|| {
        let mut pos = stop;
        while pos > start + 1 && lines[pos - 1].trim().is_empty() {
            pos -= 1;
        }
        pos
    });
    for (n, line) in new_lines.into_iter().enumerate() {
        lines.insert(at + n, line);
    }
    lines.join(eol) + eol
}

/// Valeurs de premier niveau d'un fichier YAML : { "/clé": "valeur" } (section vide ; listes et
/// valeurs sur plusieurs lignes ignorées).
fn parse_yaml(text: &str) -> Map<String, Value> {
    let mut out = Map::new();
    for line in text.lines() {
        if line.starts_with([' ', '\t', '-', '#']) {
            continue;
        }
        if let Some((k, v)) = line.split_once(':') {
            let v = v.trim();
            if !k.trim().is_empty() && !v.is_empty() {
                out.insert(format!("/{}", k.trim()), Value::String(v.trim_matches(['"', '\'']).to_string()));
            }
        }
    }
    out
}

/// [text] avec la clé de premier niveau [key] à [value] (ligne remplacée, sinon ajoutée à la fin du document).
pub fn set_yaml(text: &str, key: &str, value: &str) -> String {
    let eol = if text.contains("\r\n") { "\r\n" } else { "\n" };
    let mut lines: Vec<String> = text.lines().map(String::from).collect();
    let line = format!("{key}: {value}");
    match lines.iter().position(|l| !l.starts_with([' ', '\t']) && l.split_once(':').is_some_and(|(k, _)| k.trim() == key)) {
        Some(i) => lines[i] = line,
        // Avant la fin du document (« ... ») s'il y en a une.
        None => match lines.iter().rposition(|l| l.trim_end() == "...") {
            Some(end) => lines.insert(end, line),
            None => lines.push(line),
        },
    }
    lines.join(eol) + eol
}

// ---------------------------------------------------------------------------
// Commandes
// ---------------------------------------------------------------------------

/// Réglages actuels : { dir, files: { "GFX.ini": { exists, values: { "Section/clé": "valeur" } } } }.
pub fn read(id: &str) -> Result<Value> {
    let t = target(id)?;
    let mut files = Map::new();
    for name in t.files {
        let text = std::fs::read_to_string(t.dir.join(name));
        let text_or_empty = text.as_deref().unwrap_or("");
        let values = if t.style == Style::Yaml { parse_yaml(text_or_empty) } else { parse(text_or_empty) };
        files.insert(name.to_string(), json!({ "exists": text.is_ok(), "values": values }));
    }
    Ok(json!({ "dir": t.dir.to_string_lossy(), "files": files }))
}

/// Enregistre [changes] : [{ file, section, key, value }] ; fichiers absents créés (l'émulateur prend
/// ses valeurs par défaut pour le reste). Renvoie les réglages relus.
pub fn write(id: &str, changes: &Value) -> Result<Value> {
    let t = target(id)?;
    let list = changes.as_array().cloned().unwrap_or_default();
    for name in t.files {
        let mine: Vec<&Value> = list.iter().filter(|c| c.get("file").and_then(Value::as_str) == Some(*name)).collect();
        if mine.is_empty() {
            continue;
        }
        let path = t.dir.join(name);
        let before = std::fs::read_to_string(&path).unwrap_or_default();
        let mut text = before.clone();
        for c in mine {
            let field = |k: &str| c.get(k).and_then(Value::as_str).unwrap_or("").to_string();
            let (section, key, value) = (field("section"), field("key"), field("value"));
            // Pas de saut de ligne ni de crochet : une valeur ne doit pas casser le fichier.
            if (section.is_empty() != (t.style == Style::Yaml)) || key.is_empty() || key.contains(':') || [&section, &key, &value].iter().any(|s| s.contains(['\n', '\r'])) || section.contains([']', '[']) {
                return Err(AppError::msg(format!("invalid setting {section}/{key}")));
            }
            text = if t.style == Style::Yaml { set_yaml(&text, &key, &value) } else { set_value(&text, &section, &key, &value, t.style) };
        }
        if text != before {
            if !before.is_empty() {
                let backup = t.dir.join(format!("{name}.romcloud.bak"));
                if !backup.exists() {
                    let _ = std::fs::write(&backup, &before);
                }
            }
            std::fs::create_dir_all(&t.dir).map_err(|e| AppError::msg(e.to_string()))?;
            std::fs::write(&path, text).map_err(|e| AppError::msg(format!("{}: {e}", path.display())))?;
        }
    }
    read(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valeur_d_eden_remplacee_avec_son_default() {
        let text = "[Renderer]\nbackend\\default=true\nbackend=1\nresolution_setup\\default=true\nresolution_setup=3\n\n[System]\nlanguage_index=1\n";
        let out = set_value(text, "Renderer", "resolution_setup", "6", Style::Eden);
        assert_eq!(out, "[Renderer]\nbackend\\default=true\nbackend=1\nresolution_setup\\default=false\nresolution_setup=6\n\n[System]\nlanguage_index=1\n");
        // Clé absente : ajoutée à la fin de la section, avant la ligne vide.
        let out = set_value(&out, "Renderer", "use_vsync", "2", Style::Eden);
        assert!(out.contains("resolution_setup=6\nuse_vsync\\default=false\nuse_vsync=2\n\n[System]"));
        // Section absente : ajoutée à la fin.
        let out = set_value(&out, "Audio", "volume", "80", Style::Eden);
        assert!(out.ends_with("language_index=1\n\n[Audio]\nvolume\\default=false\nvolume=80\n"));
        assert_eq!(parse(&out).get("Renderer/resolution_setup").and_then(Value::as_str), Some("6"));
        assert!(parse(&out).get("Renderer/backend\\default").is_none());
    }

    #[test]
    fn valeur_de_dolphin_avec_espaces() {
        let text = "[Settings]\r\nInternalResolution = 1\r\nMSAA = 0x00000001\r\n[Hardware]\r\nVSync = False\r\n";
        let out = set_value(text, "Settings", "InternalResolution", "3", Style::Spaced);
        let out = set_value(&out, "hardware", "VSync", "True", Style::Spaced);
        assert_eq!(out, "[Settings]\r\nInternalResolution = 3\r\nMSAA = 0x00000001\r\n[Hardware]\r\nVSync = True\r\n");
        let values = parse(&out);
        assert_eq!(values.get("Settings/MSAA").and_then(Value::as_str), Some("0x00000001"));
        assert_eq!(set_value("", "Display", "Fullscreen", "True", Style::Spaced), "[Display]\nFullscreen = True\n");
    }

    /// Fichier réel : `cargo test -- --ignored qt_config_installe` (copie de %APPDATA%\eden\config\qt-config.ini).
    #[test]
    #[ignore]
    fn qt_config_installe() {
        let text = std::fs::read_to_string(appdata().join("eden").join("config").join("qt-config.ini")).unwrap();
        let out = set_value(&text, "Renderer", "resolution_setup", "6", Style::Eden);
        let out = set_value(&out, "System", "language_index", "2", Style::Eden);
        let changed: Vec<_> = text.lines().zip(out.lines()).filter(|(a, b)| a != b).collect();
        println!("{changed:?}");
        assert_eq!(text.lines().count(), out.lines().count());
        assert!(changed.len() <= 3);
        assert_eq!(parse(&out).get("Renderer/resolution_setup").and_then(Value::as_str), Some("6"));
    }

    #[test]
    fn config_yml_de_vita3k() {
        let text = "---
backend-renderer: Vulkan
resolution-multiplier: 1
lle-modules:
  []
sys-lang: 1
...
";
        let out = set_yaml(text, "resolution-multiplier", "1.5");
        let out = set_yaml(&out, "show-welcome", "false");
        assert_eq!(out, "---
backend-renderer: Vulkan
resolution-multiplier: 1.5
lle-modules:
  []
sys-lang: 1
show-welcome: false
...
");
        let values = parse_yaml(&out);
        assert_eq!(values.get("/backend-renderer").and_then(Value::as_str), Some("Vulkan"));
        assert!(values.get("/lle-modules").is_none());
        assert_eq!(set_yaml("", "show-welcome", "false"), "show-welcome: false
");
    }

    #[test]
    fn emplacement_des_fichiers() {
        let root = std::env::temp_dir().join(format!("romcloud-emuconfig-{}", std::process::id()));
        let (docs, appdata) = (root.join("docs"), root.join("appdata"));
        let exe = root.join("Dolphin").join("Dolphin.exe");
        std::fs::create_dir_all(exe.parent().unwrap()).unwrap();
        assert_eq!(target_for("dolphin", &exe, &docs, &appdata).unwrap().dir, appdata.join("Dolphin Emulator").join("Config"));
        std::fs::create_dir_all(docs.join("Dolphin Emulator")).unwrap();
        assert_eq!(target_for("dolphin", &exe, &docs, &appdata).unwrap().dir, docs.join("Dolphin Emulator").join("Config"));
        std::fs::write(root.join("Dolphin").join("portable.txt"), "").unwrap();
        assert_eq!(target_for("dolphin", &exe, &docs, &appdata).unwrap().dir, root.join("Dolphin").join("User").join("Config"));

        let lichii = root.join("Lichii").join("lichii.exe");
        let t = target_for("lichii", &lichii, &docs, &appdata).unwrap();
        assert_eq!((t.dir, t.style), (appdata.join("eden").join("config"), Style::Eden));
        assert!(target_for("ryujinx", &lichii, &docs, &appdata).is_none());
        let _ = std::fs::remove_dir_all(&root);
    }
}
