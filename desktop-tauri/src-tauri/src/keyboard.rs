// Touches du clavier du joueur 1 dans le moteur intégré, et boutons de chaque console (comme
// keyboard.js de la version Electron). Une touche est notée par son code physique
// (KeyboardEvent.code : « KeyZ », « ArrowUp »…) ; fichier partagé avec le moteur : une ligne
// « bouton=scancode SDL » par bouton, valeur vide = aucune touche.
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::sync::LazyLock;

/// Boutons de la manette libretro (RetroPad), dans l'ordre d'affichage.
pub const BUTTONS: [&str; 16] = ["up", "down", "left", "right", "a", "b", "x", "y", "l", "r", "l2", "r2", "l3", "r3", "start", "select"];

/// Disposition par défaut (proche de RetroArch), identique à celle du moteur sans réglage.
pub const DEFAULTS: [(&str, &str); 16] = [
    ("up", "ArrowUp"), ("down", "ArrowDown"), ("left", "ArrowLeft"), ("right", "ArrowRight"),
    ("a", "KeyX"), ("b", "KeyZ"), ("x", "KeyS"), ("y", "KeyA"),
    ("l", "KeyQ"), ("r", "KeyW"), ("l2", "KeyE"), ("r2", "KeyR"), ("l3", ""), ("r3", ""),
    ("start", "Enter"), ("select", "ShiftRight"),
];

/// Touches du moteur lui-même (menu, états de sauvegarde, plein écran) : non attribuables.
pub const RESERVED: [&str; 5] = ["Escape", "F1", "F2", "F4", "F11"];

/// KeyboardEvent.code -> SDL_Scancode (ordre d'insertion conservé pour la liste des codes).
pub static SCANCODES: LazyLock<Vec<(String, u32)>> = LazyLock::new(|| {
    let mut map: Vec<(String, u32)> = Vec::new();
    for i in 0..26u32 {
        map.push((format!("Key{}", (b'A' + i as u8) as char), 4 + i));
    }
    for i in 1..=9u32 {
        map.push((format!("Digit{i}"), 29 + i));
    }
    map.push(("Digit0".into(), 39));
    for i in 1..=12u32 {
        map.push((format!("F{i}"), 57 + i));
    }
    for i in 1..=9u32 {
        map.push((format!("Numpad{i}"), 88 + i));
    }
    let named: [(&str, u32); 48] = [
        ("Enter", 40), ("Escape", 41), ("Backspace", 42), ("Tab", 43), ("Space", 44), ("Minus", 45), ("Equal", 46),
        ("BracketLeft", 47), ("BracketRight", 48), ("Backslash", 49), ("Semicolon", 51), ("Quote", 52), ("Backquote", 53),
        ("Comma", 54), ("Period", 55), ("Slash", 56), ("CapsLock", 57), ("PrintScreen", 70), ("ScrollLock", 71), ("Pause", 72),
        ("Insert", 73), ("Home", 74), ("PageUp", 75), ("Delete", 76), ("End", 77), ("PageDown", 78),
        ("ArrowRight", 79), ("ArrowLeft", 80), ("ArrowDown", 81), ("ArrowUp", 82),
        ("NumLock", 83), ("NumpadDivide", 84), ("NumpadMultiply", 85), ("NumpadSubtract", 86), ("NumpadAdd", 87),
        ("NumpadEnter", 88), ("Numpad0", 98), ("NumpadDecimal", 99), ("IntlBackslash", 100), ("ContextMenu", 101),
        ("ControlLeft", 224), ("ShiftLeft", 225), ("AltLeft", 226), ("MetaLeft", 227),
        ("ControlRight", 228), ("ShiftRight", 229), ("AltRight", 230), ("MetaRight", 231),
    ];
    for (code, sc) in named {
        map.push((code.to_string(), sc));
    }
    map
});

fn scancode(code: &str) -> Option<u32> {
    SCANCODES.iter().find(|(c, _)| c == code).map(|(_, sc)| *sc)
}

fn default_key(name: &str) -> &'static str {
    DEFAULTS.iter().find(|(n, _)| *n == name).map(|(_, c)| *c).unwrap_or("")
}

/// Touche utilisable pour un bouton (connue du moteur et non réservée).
pub fn assignable(code: &str) -> bool {
    scancode(code).is_some() && !RESERVED.contains(&code)
}

/// Disposition complète : réglage de l'utilisateur, touches par défaut pour le reste ; "" = aucune.
pub fn resolve(saved: &Value) -> Value {
    let mut keys = Map::new();
    for name in BUTTONS {
        let code = match saved.get(name).and_then(Value::as_str) {
            Some(c) => c,
            None => default_key(name),
        };
        let code = if code.is_empty() || assignable(code) { code } else { default_key(name) };
        keys.insert(name.into(), Value::String(code.into()));
    }
    Value::Object(keys)
}

/// Contenu du fichier des touches -> { bouton: code } (touches inconnues ignorées).
pub fn parse_file(text: &str) -> Value {
    let mut saved = Map::new();
    for line in text.lines() {
        let line = line.trim();
        let Some((name, value)) = line.split_once('=') else { continue };
        if !BUTTONS.contains(&name) || !value.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        if value.is_empty() {
            saved.insert(name.into(), Value::String(String::new()));
        } else if let Ok(sc) = value.parse::<u32>() {
            if let Some((code, _)) = SCANCODES.iter().find(|(_, s)| *s == sc) {
                saved.insert(name.into(), Value::String(code.clone()));
            }
        }
    }
    resolve(&Value::Object(saved))
}

/// Fichier des touches pour le moteur.
pub fn format_file(saved: &Value) -> String {
    let keys = resolve(saved);
    BUTTONS
        .iter()
        .map(|name| {
            let code = keys.get(*name).and_then(Value::as_str).unwrap_or("");
            format!("{name}={}\n", if code.is_empty() { String::new() } else { scancode(code).map(|s| s.to_string()).unwrap_or_default() })
        })
        .collect()
}

/// Disposition décrite à l'interface (écran des touches).
pub fn layout(keys: Value) -> Value {
    json!({
        "buttons": BUTTONS,
        "defaults": Value::Object(DEFAULTS.iter().map(|(n, c)| (n.to_string(), Value::String(c.to_string()))).collect()),
        "reserved": RESERVED,
        "codes": SCANCODES.iter().map(|(c, _)| c.clone()).collect::<Vec<_>>(),
        "keys": keys,
    })
}

// Boutons de chaque console (comme PadLayouts de l'application Android) : bouton RetroPad -> nom
// sur la console (français, anglais), dans l'ordre d'affichage ; les directions sont toujours
// proposées. rx / ry : axes du stick droit, pour la configuration des manettes du moteur.
type Pad = Vec<(&'static str, &'static str, &'static str)>;

fn same(name: &'static str, label: &'static str) -> (&'static str, &'static str, &'static str) {
    (name, label, label)
}

static PADS: LazyLock<HashMap<&'static str, Pad>> = LazyLock::new(|| {
    let start_select = || vec![same("start", "Start"), same("select", "Select")];
    let right_stick = || vec![("rx", "Stick droit horizontal", "Right stick horizontal"), ("ry", "Stick droit vertical", "Right stick vertical")];
    let mut pads: HashMap<&'static str, Pad> = HashMap::new();
    let with = |mut base: Pad, extra: Vec<Pad>| {
        for e in extra {
            base.extend(e);
        }
        base
    };
    pads.insert("default", with(vec![same("a", "A"), same("b", "B"), same("x", "X"), same("y", "Y"), same("l", "L"), same("r", "R"), same("l2", "L2"), same("r2", "R2"), same("l3", "L3"), same("r3", "R3")], vec![start_select(), right_stick()]));
    pads.insert("nes", with(vec![same("b", "B"), same("a", "A")], vec![start_select()]));
    pads.insert("fds", with(vec![same("b", "B"), same("a", "A"), ("l", "Face du disque", "Disk side"), ("r", "Éjecter / insérer", "Eject / insert")], vec![start_select()]));
    pads.insert("gameBoy", with(vec![same("b", "B"), same("a", "A")], vec![start_select()]));
    pads.insert("gba", with(vec![same("b", "B"), same("a", "A"), same("l", "L"), same("r", "R")], vec![start_select()]));
    pads.insert("snes", with(vec![same("b", "B"), same("a", "A"), same("y", "Y"), same("x", "X"), same("l", "L"), same("r", "R")], vec![start_select()]));
    pads.insert("n64", vec![same("b", "A"), same("y", "B"), same("l2", "Z"), same("l", "L"), same("r", "R"), same("start", "Start"), ("rx", "C gauche / droite", "C left / right"), ("ry", "C haut / bas", "C up / down")]);
    pads.insert("psx", with(vec![("b", "Croix", "Cross"), ("a", "Rond", "Circle"), ("y", "Carré", "Square"), same("x", "Triangle"), same("l", "L1"), same("r", "R1"), same("l2", "L2"), same("r2", "R2"), same("l3", "L3"), same("r3", "R3")], vec![start_select(), right_stick()]));
    pads.insert("psp", with(vec![("b", "Croix", "Cross"), ("a", "Rond", "Circle"), ("y", "Carré", "Square"), same("x", "Triangle"), same("l", "L"), same("r", "R")], vec![start_select()]));
    pads.insert("genesis", vec![same("y", "A"), same("b", "B"), same("a", "C"), same("l", "X"), same("x", "Y"), same("r", "Z"), same("start", "Start")]);
    pads.insert("saturn", vec![same("b", "A"), same("a", "B"), same("r", "C"), same("y", "X"), same("x", "Y"), same("l", "Z"), same("l2", "L"), same("r2", "R"), same("start", "Start")]);
    pads.insert("master", vec![same("b", "1"), same("a", "2"), same("start", "Start")]);
    pads.insert("dreamcast", vec![same("b", "A"), same("a", "B"), same("y", "X"), same("x", "Y"), same("l2", "L"), same("r2", "R"), same("start", "Start")]);
    pads.insert("pcEngine", vec![same("b", "II"), same("a", "I"), same("select", "Select"), same("start", "Run")]);
    pads.insert("neoGeo", vec![same("b", "A"), same("a", "B"), same("y", "C"), same("x", "D"), ("select", "Pièce", "Coin"), same("start", "Start")]);
    pads.insert("ngp", vec![same("b", "A"), same("a", "B"), same("start", "Option")]);
    pads.insert("cps", vec![same("y", "LP"), same("x", "MP"), same("l", "HP"), same("b", "LK"), same("a", "MK"), same("r", "HK"), ("select", "Pièce", "Coin"), same("start", "Start")]);
    pads.insert("arcade", vec![same("b", "1"), same("a", "2"), same("y", "3"), same("x", "4"), same("l", "5"), same("r", "6"), ("select", "Pièce", "Coin"), same("start", "Start")]);
    pads.insert("gx4000", vec![("b", "Feu 1", "Fire 1"), ("a", "Feu 2", "Fire 2")]);
    pads.insert("atari2600", vec![("b", "Feu", "Fire"), same("select", "Select"), same("start", "Reset")]);
    pads.insert("atari7800", vec![same("b", "1"), same("a", "2"), same("select", "Select"), same("start", "Pause")]);
    pads.insert("lynx", vec![same("b", "B"), same("a", "A"), same("l", "Option 1"), same("r", "Option 2"), same("start", "Pause")]);
    pads
});

fn pad_by_system(system: &str) -> Option<&'static str> {
    Some(match system {
        "nes" => "nes",
        "fds" => "fds",
        "gba" => "gba",
        "n64" => "n64",
        "psx" => "psx",
        "gx4000" => "gx4000",
        "atari2600" => "atari2600",
        "atari7800" => "atari7800",
        "lynx" => "lynx",
        "gb" | "gbc" | "megaduck" | "gw" | "pokemini" | "supervision" => "gameBoy",
        "snes" | "snesmsu1" | "satellaview" => "snes",
        "psp" | "pspminis" => "psp",
        "genesis" | "genesismsu" | "segacd" | "sega32x" | "pico" => "genesis",
        "saturn" | "stv" => "saturn",
        "master" | "gamegear" | "sg1000" => "master",
        "dreamcast" | "naomi" | "atomiswave" => "dreamcast",
        "tg16" | "tgcd" | "supergrafx" => "pcEngine",
        "neogeo" | "neogeocd" => "neoGeo",
        "ngp" | "ngpc" => "ngp",
        "cps1" | "cps2" | "cps3" => "cps",
        "fbneo" | "mame" => "arcade",
        _ => return None,
    })
}

/// Système inconnu (ajouté à la main sur le serveur) : d'après le cœur ; préfixes longs d'abord.
const PAD_BY_CORE: &[(&str, &str)] = &[
    ("mesen-s", "snes"),
    ("fceumm", "nes"), ("nestopia", "nes"), ("mesen", "nes"), ("quicknes", "nes"),
    ("gambatte", "gameBoy"), ("sameboy", "gameBoy"), ("gearboy", "gameBoy"), ("tgbdual", "gameBoy"),
    ("mgba", "gba"), ("gpsp", "gba"), ("vba", "gba"), ("mednafen_gba", "gba"),
    ("snes9x", "snes"), ("bsnes", "snes"),
    ("mupen64plus", "n64"), ("parallel_n64", "n64"),
    ("pcsx_rearmed", "psx"), ("swanstation", "psx"), ("duckstation", "psx"), ("mednafen_psx", "psx"),
    ("ppsspp", "psp"),
    ("genesis_plus_gx", "genesis"), ("picodrive", "genesis"),
    ("mednafen_saturn", "saturn"), ("yabause", "saturn"), ("yabasanshiro", "saturn"), ("kronos", "saturn"),
    ("gearsystem", "master"), ("smsplus", "master"),
    ("flycast", "dreamcast"),
    ("mednafen_pce", "pcEngine"), ("mednafen_supergrafx", "pcEngine"),
    ("neocd", "neoGeo"), ("geolith", "neoGeo"),
    ("mednafen_ngp", "ngp"), ("race", "ngp"),
    ("fbalpha2012_cps", "cps"),
    ("fbneo", "arcade"), ("fbalpha", "arcade"), ("mame", "arcade"),
    ("stella", "atari2600"),
    ("prosystem", "atari7800"),
    ("handy", "lynx"), ("mednafen_lynx", "lynx"),
];

/// Manette de la console (« snes », « psx »… ; « default » : RetroPad) : système, puis cœur.
pub fn console_pad(system_id: &str, core: &str) -> &'static str {
    let system = system_id.to_lowercase();
    pad_by_system(&system)
        .or_else(|| PAD_BY_CORE.iter().find(|(prefix, _)| core.starts_with(prefix)).map(|(_, pad)| *pad))
        .unwrap_or("default")
}

/// Boutons de la console proposés par le moteur : argument --buttons.
pub fn console_buttons(system_id: &str, core: &str, language: &str) -> String {
    let pad = &PADS[console_pad(system_id, core)];
    let clean = |s: &str| s.replace([',', '='], " ");
    let mut parts: Vec<String> = ["up", "down", "left", "right"].iter().map(|s| s.to_string()).collect();
    for (name, fr, en) in pad {
        parts.push(format!("{name}={}", clean(if language == "fr" { fr } else { en })));
    }
    parts.join(",")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key<'a>(keys: &'a Value, name: &str) -> &'a str {
        keys[name].as_str().unwrap()
    }

    #[test]
    fn touches_par_defaut() {
        let file = format_file(&json!({}));
        let lines: Vec<&str> = file.trim().split('\n').collect();
        assert_eq!(lines.len(), BUTTONS.len());
        for line in ["up=82", "b=29", "start=40", "l3="] {
            assert!(lines.contains(&line), "{line}");
        }
    }

    #[test]
    fn touches_choisies() {
        let keys = resolve(&json!({ "b": "Space", "a": "", "up": "Escape", "down": "Nope" }));
        assert_eq!(key(&keys, "b"), "Space");
        assert_eq!(key(&keys, "a"), "");
        assert_eq!(key(&keys, "up"), "ArrowUp"); // réservée au moteur
        assert_eq!(key(&keys, "down"), "ArrowDown"); // inconnue
    }

    #[test]
    fn fichier_des_touches_relu() {
        let keys = parse_file("b=44\r\na=\r\nup=41\r\nr3=4\r\nautre=5\r\n");
        assert_eq!(key(&keys, "b"), "Space");
        assert_eq!(key(&keys, "a"), "");
        assert_eq!(key(&keys, "up"), "ArrowUp"); // Échap (41) : réservée
        assert_eq!(key(&keys, "r3"), "KeyA");
        assert_eq!(key(&keys, "start"), "Enter"); // absent : par défaut
        assert_eq!(parse_file(&format_file(&keys)), keys);
    }

    #[test]
    fn scancodes_sdl() {
        for (code, sc) in [("KeyA", 4), ("KeyZ", 29), ("Digit0", 39), ("F12", 69), ("Numpad0", 98), ("ShiftRight", 229)] {
            assert_eq!(scancode(code), Some(sc), "{code}");
        }
        assert!(!assignable("F1"));
    }

    #[test]
    fn boutons_de_la_console() {
        assert_eq!(console_buttons("gba", "vbam", "fr"), "up,down,left,right,b=B,a=A,l=L,r=R,start=Start,select=Select");
        // Mega Drive : A B C sur Y B A, X Y Z sur L X R, pas de Select.
        assert_eq!(console_buttons("genesis", "genesis_plus_gx", "fr"), "up,down,left,right,y=A,b=B,a=C,l=X,x=Y,r=Z,start=Start");
        assert!(console_buttons("psx", "pcsx_rearmed", "en").contains("b=Cross"));
        assert!(console_buttons("psx", "pcsx_rearmed", "fr").contains("b=Croix"));
        // Système inconnu : d'après le cœur, sinon tous les boutons.
        assert!(console_buttons("perso", "snes9x", "fr").contains("y=Y"));
        assert!(console_buttons("perso", "inconnu", "fr").contains("r3=R3"));
        assert!(!console_buttons("gx4000", "cap32", "fr").contains("start"));
        assert_eq!(console_pad("n64", "mupen64plus_next"), "n64");
        assert_eq!(console_pad("perso", "mesen-s"), "snes");
    }
}
