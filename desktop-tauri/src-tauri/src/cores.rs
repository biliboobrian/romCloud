// Cœurs libretro : ordre de chaque console (même table que CoreRanking.kt de l'application Android), lecture
// des modèles d'émulateurs Daijishou du serveur, et fonctions du moteur intégré (buildbot libretro,
// ROMs compressées, arguments de romcloud-player.exe).
use crate::paths;
use serde_json::Value;
use std::path::{Path, PathBuf};

const GAME_BOY: &[&str] = &["sameboy", "gambatte", "mgba", "mesen-s", "gearboy", "tgbdual", "vbam", "DoubleCherryGB", "skyemu"];
const SEGA_8BIT: &[&str] = &["genesis_plus_gx", "gearsystem", "smsplus", "picodrive", "genesis_plus_gx_wide"];
const NES: &[&str] = &["mesen", "nestopia", "fceumm", "bnes", "quicknes"];

fn ranking(system: &str) -> &'static [&'static str] {
    match system {
        "nes" | "fds" => NES,
        "snes" => &[
            "bsnes", "snes9x", "mesen-s", "bsnes_hd_beta", "higan_sfc", "higan_sfc_balanced",
            "bsnes_mercury_accuracy", "bsnes_mercury_balanced", "bsnes2014_accuracy", "bsnes2014_balanced",
            "mednafen_supafaust", "snes9x2010", "bsnes_mercury_performance", "bsnes2014_performance",
            "mednafen_snes", "bsnes_cplusplus98", "snes9x2005_plus", "snes9x2005", "snes9x2002",
        ],
        "gb" | "gbc" => GAME_BOY,
        "gba" => &["mgba", "vbam", "mednafen_gba", "vba_next", "gpsp", "skyemu", "noods", "meteor"],
        "n64" => &["mupen64plus_next_gles3", "mupen64plus_next", "mupen64plus_next_gles2", "parallel_n64"],
        "nds" => &["melondsds", "desmume", "melonds", "desmume2015", "noods", "skyemu"],
        "ndsi" => &["melondsds", "melonds", "desmume", "desmume2015"],
        "3ds" => &["azahar", "citra", "panda3ds"],
        "gc" | "wii" => &["dolphin"],
        "virtualboy" => &["mednafen_vb"],
        "master" | "gamegear" => SEGA_8BIT,
        "sg1000" => &["genesis_plus_gx", "gearsystem", "smsplus", "picodrive"],
        "genesis" | "segacd" => &["genesis_plus_gx", "picodrive", "genesis_plus_gx_wide"],
        "sega32x" => &["picodrive"],
        "saturn" => &["mednafen_saturn", "ymir", "yabasanshiro", "yabause", "kronos"],
        "dreamcast" => &["flycast"],
        "psx" => &["mednafen_psx_hw", "swanstation", "mednafen_psx", "pcsx_rearmed", "duckstation", "goosestation"],
        "ps2" => &["pcsx2", "pcee2", "armsx2", "play"],
        "psp" => &["ppsspp"],
        "tg16" | "tgcd" => &["mednafen_pce", "mednafen_pce_fast", "mednafen_supergrafx"],
        "supergrafx" => &["mednafen_supergrafx", "mednafen_pce"],
        "ngp" | "ngpc" => &["mednafen_ngp", "race"],
        "ws" | "wsc" => &["mednafen_wswan"],
        "atari2600" => &["stella", "stella2014"],
        "atari7800" => &["prosystem"],
        "lynx" => &["handy", "mednafen_lynx"],
        "cpc" => &["cap32", "crocods"],
        "gx4000" => &["cap32"],
        "neogeo" => &["fbneo", "geolith", "fbalpha2012"],
        "fbneo" => &["fbneo", "fbalpha2012"],
        "mame" => &["mame", "mamearcade", "mame2010", "mame2003_plus", "mame2003", "mame2003_midway", "mame2000"],
        _ => &[],
    }
}

/// Système émulé par le moteur intégré (cœurs connus pour lui, sous Windows comme sous Android).
pub fn has_builtin_cores(system: &str) -> bool {
    !ranking(&system.to_lowercase()).is_empty()
}

/// Cœurs absents du buildbot libretro pour Windows (vérifié en octobre 2026) : proposés en dernier.
const UNAVAILABLE: &[&str] = &["bnes", "duckstation", "goosestation", "mamearcade", "armsx2"];

/// Cœur téléchargeable pour le moteur intégré (présent sur le buildbot libretro pour Windows).
pub fn core_available(core: &str) -> bool {
    !UNAVAILABLE.contains(&core)
}

/// Cœurs [cores] du système [system_id] triés selon la table ; tri stable pour les autres.
pub fn rank_cores(system_id: &str, cores: Vec<String>) -> Vec<String> {
    let order = ranking(&system_id.to_lowercase());
    let rank = |core: &str| {
        if UNAVAILABLE.contains(&core) {
            return order.len() + 1;
        }
        order.iter().position(|c| *c == core).unwrap_or(order.len())
    };
    let mut indexed: Vec<(usize, String)> = cores.into_iter().enumerate().collect();
    indexed.sort_by(|(ia, a), (ib, b)| rank(a).cmp(&rank(b)).then(ia.cmp(ib)));
    indexed.into_iter().map(|(_, c)| c).collect()
}

/// Découpe une ligne de commande en arguments (guillemets gérés).
pub fn tokenize(input: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    let mut quote: Option<char> = None;
    let mut has = false;
    for c in input.chars() {
        if let Some(q) = quote {
            if c == q { quote = None } else { current.push(c) }
        } else if c == '"' || c == '\'' {
            quote = Some(c);
            has = true;
        } else if c.is_whitespace() {
            if has {
                tokens.push(std::mem::take(&mut current));
            }
            current.clear();
            has = false;
        } else {
            current.push(c);
            has = true;
        }
    }
    if has {
        tokens.push(current);
    }
    tokens
}

/// Nom du cœur Libretro d'un modèle Android (« mupen64plus_next_gles3 », ou chemin .so).
pub fn core_of(player: &Value) -> Option<String> {
    let tokens = tokenize(player.get("amStartArguments").and_then(Value::as_str).unwrap_or(""));
    for i in 0..tokens.len().saturating_sub(2) {
        if (tokens[i] == "-e" || tokens[i] == "--es") && tokens[i + 1] == "LIBRETRO" {
            let base = paths::file_name(Path::new(&tokens[i + 2]));
            let name = base.strip_suffix(".so").unwrap_or(&base);
            let name = name.strip_suffix("_android").unwrap_or(name);
            let name = name.strip_suffix("_libretro").unwrap_or(name);
            return Some(name.to_string());
        }
    }
    None
}

/// Cœurs RetroArch utilisables pour un système (sans doublon), du plus abouti au moins abouti.
pub fn cores(system: &Value) -> Vec<String> {
    let mut names: Vec<String> = Vec::new();
    for player in system.get("players").and_then(Value::as_array).cloned().unwrap_or_default() {
        if let Some(core) = core_of(&player) {
            // Les cœurs GLES (Android) ont un équivalent sans suffixe sous Windows, MAME (arcade) s'y appelle « mame ».
            let core = if core == "mamearcade" {
                "mame".to_string()
            } else {
                core.strip_suffix("_gles3").or_else(|| core.strip_suffix("_gles2")).unwrap_or(&core).to_string()
            };
            if !names.contains(&core) {
                names.push(core);
            }
        }
    }
    let id = system.get("shortname").and_then(Value::as_str).filter(|s| !s.is_empty()).or_else(|| system.get("id").and_then(Value::as_str)).unwrap_or("");
    rank_cores(id, names)
}

pub const BUILDBOT: &str = "https://buildbot.libretro.com/nightly/windows/x86_64/latest";

/// Nom de cœur utilisable dans une URL ou un nom de fichier (« mupen64plus_next »).
pub fn valid_core(core: &str) -> bool {
    !core.is_empty() && core.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub fn core_dll_name(core: &str) -> String {
    format!("{core}_libretro.dll")
}

pub fn core_url(core: &str) -> String {
    format!("{BUILDBOT}/{}.zip", core_dll_name(core))
}

/// Cœurs qui lisent eux-mêmes les archives (arcade : jeux = ensembles de fichiers .zip, DOS).
const ARCHIVE_CORES: &[&str] = &["fbneo", "fbalpha", "mame", "dosbox", "neocd"];

/// RetroArch décompresse les .zip avant de les passer au cœur, sauf pour ces cœurs : idem ici.
pub fn needs_extraction(core: &str, file: &str) -> bool {
    paths::extension(file) == ".zip" && !ARCHIVE_CORES.iter().any(|p| core.starts_with(p))
}

/// Options du cœur imposées par le jeu : cartouche GX4000 / CPC+ (.cpr) avec cap32 -> CPC 6128+.
pub fn game_option_defaults(core: &str, file: &str) -> Vec<(String, String)> {
    if core == "cap32" && paths::extension(file) == ".cpr" {
        return vec![("cap32_model".into(), "6128+ (experimental)".into())];
    }
    vec![]
}

/// Paramètres de romcloud-player.exe. `state_name` : nom de l'état de sauvegarde (celui du jeu,
/// même si la ROM passée est extraite d'un .zip) ; `resume` : reprend la partie à cet état ;
/// `keys_file` : touches du clavier ; `buttons` : boutons de la console ; `pad_style` : manette
/// dessinée dans la configuration des manettes ; `option_defaults` : options imposées par le jeu.
#[derive(Default)]
pub struct PlayerArgs {
    pub dll: String,
    pub rom: String,
    pub system_dir: String,
    pub save_dir: String,
    pub state_dir: String,
    pub state_name: Option<String>,
    pub options_file: String,
    pub title: String,
    pub language: String,
    pub windowed: bool,
    pub resume: bool,
    pub keys_file: Option<String>,
    pub buttons: Option<String>,
    pub pad_style: Option<String>,
    pub option_defaults: Vec<(String, String)>,
}

/// Arguments de romcloud-player.exe.
pub fn player_args(a: &PlayerArgs) -> Vec<String> {
    let mut args: Vec<String> = [
        ("--core", &a.dll), ("--rom", &a.rom), ("--system-dir", &a.system_dir), ("--save-dir", &a.save_dir),
        ("--state-dir", &a.state_dir), ("--options", &a.options_file), ("--title", &a.title), ("--lang", &a.language),
    ]
    .iter()
    .flat_map(|(k, v)| [k.to_string(), v.to_string()])
    .collect();
    for (key, value) in [("--state-name", &a.state_name), ("--keys-file", &a.keys_file), ("--buttons", &a.buttons), ("--pad-style", &a.pad_style)] {
        if let Some(value) = value.as_ref().filter(|v| !v.is_empty()) {
            args.push(key.into());
            args.push(value.clone());
        }
    }
    for (key, value) in &a.option_defaults {
        args.push("--option-default".into());
        args.push(format!("{key}={value}"));
    }
    if a.windowed {
        args.push("--windowed".into());
    }
    if a.resume {
        args.push("--resume".into());
    }
    args
}

/// Fichier principal d'un jeu à plusieurs fichiers (liste de disques, image CD), sinon le plus gros.
const MAIN_EXTENSIONS: &[&str] = &[".m3u", ".cue", ".gdi", ".ccd", ".chd", ".iso"];

pub fn main_entry(entries: &[(String, u64)]) -> Option<usize> {
    let files: Vec<usize> = (0..entries.len()).filter(|&i| !entries[i].0.ends_with('/')).collect();
    for ext in MAIN_EXTENSIONS {
        if let Some(&i) = files.iter().find(|&&i| entries[i].0.to_lowercase().ends_with(ext)) {
            return Some(i);
        }
    }
    files.into_iter().fold(None, |best: Option<usize>, i| match best {
        Some(b) if entries[b].1 >= entries[i].1 => Some(b),
        _ => Some(i),
    })
}

/// Chemin d'extraction sûr (pas de « .. » ni de chemin absolu dans l'archive).
pub fn safe_entry_path(dir: &Path, name: &str) -> PathBuf {
    let mut path = dir.to_path_buf();
    for part in name.split(['\\', '/']) {
        let is_drive = part.len() == 2 && part.ends_with(':') && part.chars().next().map(|c| c.is_ascii_alphabetic()).unwrap_or(false);
        if !part.is_empty() && part != "." && part != ".." && !is_drive {
            path.push(part);
        }
    }
    path
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn retroarch(core: &str) -> Value {
        json!({ "amStartArguments": format!("-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n -e ROM {{file.path}}\n -e LIBRETRO {core}\n -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg") })
    }

    fn system(id: &str, cores: &[&str]) -> Value {
        json!({ "id": id, "players": cores.iter().map(|c| retroarch(c)).collect::<Vec<_>>() })
    }

    #[test]
    fn decoupage_d_une_commande() {
        assert_eq!(tokenize(r#""C:\Emulateurs\Dolphin 5\Dolphin.exe" -b -e "{file}""#), [r"C:\Emulateurs\Dolphin 5\Dolphin.exe", "-b", "-e", "{file}"]);
        assert_eq!(tokenize(r"C:\RetroArch\retroarch.exe -L snes9x"), [r"C:\RetroArch\retroarch.exe", "-L", "snes9x"]);
        assert_eq!(tokenize("a '' b"), ["a", "", "b"]);
    }

    #[test]
    fn coeur_d_un_modele_android() {
        assert_eq!(core_of(&retroarch("mupen64plus_next_gles3")).as_deref(), Some("mupen64plus_next_gles3"));
        assert_eq!(core_of(&retroarch("/data/data/com.retroarch/cores/snes9x_libretro_android.so")).as_deref(), Some("snes9x"));
        assert_eq!(core_of(&json!({ "amStartArguments": "-n org.ppsspp.ppsspp/.PpssppActivity -d {file.uri}" })), None);
    }

    #[test]
    fn coeurs_windows_sans_doublon() {
        let mut n64 = system("x", &["mupen64plus_next_gles3", "mupen64plus_next_gles2", "parallel_n64"]);
        n64["players"].as_array_mut().unwrap().push(json!({ "amStartArguments": "-n x/.Y" }));
        assert_eq!(cores(&n64), ["mupen64plus_next", "parallel_n64"]);
        assert!(cores(&json!({ "players": [] })).is_empty());
    }

    #[test]
    fn coeurs_classes() {
        assert_eq!(cores(&system("gba", &["vba_next", "vbam", "mgba", "inconnu", "gpsp", "autre"])), ["mgba", "vbam", "vba_next", "gpsp", "inconnu", "autre"]);
        assert_eq!(cores(&system("ps2", &["play", "pcee2", "armsx2", "pcsx2"])), ["pcsx2", "pcee2", "play", "armsx2"]); // armsx2 : absent sous Windows
        assert_eq!(cores(&system("mame", &["mame2003_plus", "mamearcade", "mame2010"])), ["mame", "mame2010", "mame2003_plus"]);
        assert_eq!(cores(&system("psx", &["duckstation", "pcsx_rearmed"])), ["pcsx_rearmed", "duckstation"]);
        assert_eq!(cores(&system("perso", &["b", "a"])), ["b", "a"]);
    }

    #[test]
    fn coeurs_du_buildbot() {
        assert!(valid_core("mupen64plus_next"));
        assert!(!valid_core("../evil"));
        assert!(!valid_core(""));
        assert_eq!(core_url("snes9x"), "https://buildbot.libretro.com/nightly/windows/x86_64/latest/snes9x_libretro.dll.zip");
    }

    #[test]
    fn rom_zip_decompressee_sauf_arcade() {
        assert!(needs_extraction("snes9x", r"D:\Jeux\Mario.ZIP"));
        assert!(!needs_extraction("fbneo", r"D:\Jeux\sf2.zip"));
        assert!(!needs_extraction("mame2003_plus", r"D:\Jeux\pacman.zip"));
        assert!(!needs_extraction("snes9x", r"D:\Jeux\Mario.sfc"));
    }

    #[test]
    fn fichier_principal_d_une_archive() {
        let pick = |entries: &[(&str, u64)]| {
            let list: Vec<(String, u64)> = entries.iter().map(|(n, s)| (n.to_string(), *s)).collect();
            list[main_entry(&list).unwrap()].0.clone()
        };
        assert_eq!(pick(&[("Jeu (Track 1).bin", 900), ("Jeu.cue", 1)]), "Jeu.cue");
        assert_eq!(pick(&[("a.txt", 3), ("jeu.sfc", 900), ("dir/", 0)]), "jeu.sfc");
    }

    #[test]
    fn chemin_d_extraction_sur() {
        let dir = Path::new(r"C:\cache");
        assert_eq!(safe_entry_path(dir, "../../Windows/evil.dll"), dir.join("Windows").join("evil.dll"));
        assert_eq!(safe_entry_path(dir, "C:/abs/jeu.bin"), dir.join("abs").join("jeu.bin"));
    }

    fn base() -> PlayerArgs {
        PlayerArgs {
            dll: r"C:\c\snes9x_libretro.dll".into(),
            rom: r"D:\Jeux\Mario.sfc".into(),
            system_dir: "B".into(),
            save_dir: "S".into(),
            state_dir: "T".into(),
            options_file: "O.cfg".into(),
            title: "Super Mario World".into(),
            language: "fr".into(),
            ..Default::default()
        }
    }

    fn value<'a>(args: &'a [String], key: &str) -> &'a str {
        &args[args.iter().position(|a| a == key).unwrap() + 1]
    }

    #[test]
    fn arguments_du_moteur() {
        let args = player_args(&base());
        assert_eq!(&args[..4], [r"C:\c\snes9x_libretro.dll", r"D:\Jeux\Mario.sfc"].iter().zip(["--core", "--rom"]).flat_map(|(v, k)| [k.to_string(), v.to_string()]).collect::<Vec<_>>());
        assert_eq!(value(&args, "--title"), "Super Mario World");
        assert!(!args.contains(&"--windowed".to_string()));
    }

    #[test]
    fn arguments_du_moteur_reprise() {
        let args = player_args(&PlayerArgs { state_name: Some("Jeu (Europe)".into()), resume: true, ..base() });
        assert_eq!(value(&args, "--state-name"), "Jeu (Europe)");
        assert!(args.contains(&"--resume".to_string()));
        assert!(!player_args(&base()).contains(&"--resume".to_string()));
    }

    #[test]
    fn arguments_du_moteur_touches_et_manette() {
        let args = player_args(&PlayerArgs { keys_file: Some(r"K:\keyboard.cfg".into()), pad_style: Some("snes".into()), ..base() });
        assert_eq!(value(&args, "--keys-file"), r"K:\keyboard.cfg");
        assert_eq!(value(&args, "--pad-style"), "snes");
        assert!(!player_args(&base()).contains(&"--keys-file".to_string()));
    }

    #[test]
    fn options_imposees_par_le_jeu() {
        assert_eq!(game_option_defaults("cap32", r"D:\Jeux\Pang.cpr"), [("cap32_model".to_string(), "6128+ (experimental)".to_string())]);
        assert!(game_option_defaults("cap32", r"D:\Jeux\Disque.dsk").is_empty());
        let args = player_args(&PlayerArgs { option_defaults: game_option_defaults("cap32", "Pang.cpr"), ..base() });
        assert_eq!(value(&args, "--option-default"), "cap32_model=6128+ (experimental)");
    }
}
