// Réglages de l'application (fichier JSON dans le dossier de données de l'utilisateur), même format
// que l'ancienne version Electron. Gardés sous forme d'objet JSON : les champs inconnus sont conservés.
use crate::paths;
use serde_json::{json, Map, Value};
use std::path::PathBuf;
use std::sync::Mutex;

const LANGUAGES: [&str; 2] = ["fr", "en"];

fn defaults() -> Map<String, Value> {
    let value = json!({
        "serverUrl": "",
        "apiKey": "",
        "romsDir": paths::documents().join("RomCloud").to_string_lossy(),
        "retroarchPath": "",
        "biosDir": "",
        "language": "system",
        "view": "carousel",
        "emulators": {},
        "emulatorPaths": {},
        "emulatorArgs": {},
        "emulatorsDetectedAt": "",
        "retroarchHelpDismissed": false,
        "hideUnidentified": false,
        "gameOrder": "name",
        "session": null,
        "pendingPlaytime": [],
        "pendingSaves": [],
        "pendingStates": [],
        // Profil connecté : états du moteur intégré envoyés en ligne après la partie.
        "autoUploadStates": true,
    });
    match value {
        Value::Object(map) => map,
        _ => Map::new(),
    }
}

fn file() -> PathBuf {
    paths::user_data().join("settings.json")
}

static CURRENT: Mutex<Option<Map<String, Value>>> = Mutex::new(None);

fn loaded(current: &mut Option<Map<String, Value>>) -> &mut Map<String, Value> {
    current.get_or_insert_with(|| {
        let mut map = defaults();
        if let Ok(text) = std::fs::read_to_string(file()) {
            if let Ok(Value::Object(saved)) = serde_json::from_str::<Value>(&text) {
                map.extend(saved);
            }
        }
        map
    })
}

/// Réglages actuels.
pub fn load() -> Value {
    let mut current = CURRENT.lock().unwrap();
    Value::Object(loaded(&mut current).clone())
}

/// Champ texte des réglages ("" s'il est absent).
pub fn get_str(key: &str) -> String {
    load().get(key).and_then(Value::as_str).unwrap_or("").to_string()
}

/// Enregistre [patch] (champs à remplacer) ; renvoie les réglages.
pub fn save(patch: Value) -> Value {
    let mut current = CURRENT.lock().unwrap();
    let map = loaded(&mut current);
    if let Value::Object(patch) = patch {
        for (key, value) in patch {
            let value = match key.as_str() {
                "serverUrl" => Value::String(normalize_url(value.as_str().unwrap_or(""))),
                "romsDir" => {
                    let dir = value.as_str().unwrap_or("").trim().to_string();
                    Value::String(if dir.is_empty() { paths::documents().join("RomCloud").to_string_lossy().into_owned() } else { dir })
                }
                "biosDir" => Value::String(value.as_str().unwrap_or("").trim().to_string()),
                _ => value,
            };
            map.insert(key, value);
        }
    }
    let _ = std::fs::create_dir_all(paths::user_data());
    if let Ok(text) = serde_json::to_string_pretty(&Value::Object(map.clone())) {
        let _ = std::fs::write(file(), text);
    }
    Value::Object(map.clone())
}

/// Dossier des BIOS : choisi, sinon le dossier « system » de RetroArch, sinon Documents\RomCloud\bios.
pub fn bios_dir() -> PathBuf {
    let s = load();
    let chosen = s.get("biosDir").and_then(Value::as_str).unwrap_or("");
    if !chosen.is_empty() {
        return PathBuf::from(chosen);
    }
    let retroarch = s.get("retroarchPath").and_then(Value::as_str).unwrap_or("");
    if !retroarch.is_empty() {
        return PathBuf::from(paths::dirname(retroarch)).join("system");
    }
    paths::documents().join("RomCloud").join("bios")
}

/// "192.168.1.10:8080" -> "http://192.168.1.10:8080"
pub fn normalize_url(input: &str) -> String {
    let trimmed = input.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return String::new();
    }
    let lower = trimmed.to_lowercase();
    if lower.starts_with("http://") || lower.starts_with("https://") {
        trimmed.to_string()
    } else {
        format!("http://{trimmed}")
    }
}

/// Langue effective (« fr » ou « en ») : choix de l'utilisateur ou langue de Windows.
pub fn language() -> String {
    let chosen = get_str("language");
    if LANGUAGES.contains(&chosen.as_str()) {
        return chosen;
    }
    let system = sys_locale::get_locale().unwrap_or_else(|| "en".into()).chars().take(2).collect::<String>().to_lowercase();
    if LANGUAGES.contains(&system.as_str()) { system } else { "en".into() }
}

/// Réglages tels que l'interface les attend (langue et dossier des BIOS effectifs en plus).
pub fn view() -> Value {
    let mut s = load();
    if let Value::Object(map) = &mut s {
        map.insert("effectiveLanguage".into(), Value::String(language()));
        map.insert("effectiveBiosDir".into(), Value::String(bios_dir().to_string_lossy().into_owned()));
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adresse_du_serveur() {
        assert_eq!(normalize_url(" 192.168.1.10:8080/ "), "http://192.168.1.10:8080");
        assert_eq!(normalize_url("https://romcloud.example"), "https://romcloud.example");
        assert_eq!(normalize_url("HTTP://Serveur//"), "HTTP://Serveur");
        assert_eq!(normalize_url(""), "");
    }
}
