// Catalogue des émulateurs Windows les plus connus : page de téléchargement, exécutable,
// emplacements habituels et ligne de commande pour lancer un jeu directement.
//
// Arguments (« args ») : un élément par argument, avec les variables {file} (chemin du jeu),
// {dir} (dossier du jeu), {name} (nom du fichier) et {basename} (nom sans extension).
// args = None : l'émulateur ne sait pas ouvrir un jeu passé en ligne de commande ; RomCloud
// l'ouvre seul et l'utilisateur choisit le jeu dans son menu.
use crate::paths;
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

pub struct Emulator {
    pub id: &'static str,
    pub name: &'static str,
    pub systems: &'static [&'static str],
    pub url: &'static str,
    pub exe: &'static [&'static str],
    pub dirs: &'static [&'static str],
    pub args: Option<&'static [&'static str]>,
}

macro_rules! emu {
    ($id:expr, $name:expr, [$($s:expr),*], $url:expr, [$($e:expr),*], [$($d:expr),*], None) => {
        Emulator { id: $id, name: $name, systems: &[$($s),*], url: $url, exe: &[$($e),*], dirs: &[$($d),*], args: None }
    };
    ($id:expr, $name:expr, [$($s:expr),*], $url:expr, [$($e:expr),*], [$($d:expr),*], [$($a:expr),*]) => {
        Emulator { id: $id, name: $name, systems: &[$($s),*], url: $url, exe: &[$($e),*], dirs: &[$($d),*], args: Some(&[$($a),*]) }
    };
}

pub static EMULATORS: &[Emulator] = &[
    emu!("duckstation", "DuckStation", ["psx"], "https://github.com/stenzek/duckstation/releases",
        ["duckstation-qt-x64-ReleaseLTCG.exe", "duckstation-qt.exe"], ["DuckStation"], ["-batch", "-fullscreen", "{file}"]),
    emu!("pcsx2", "PCSX2", ["ps2"], "https://pcsx2.net/downloads", ["pcsx2-qt.exe", "pcsx2.exe"], ["PCSX2"],
        ["-batch", "-fullscreen", "--", "{file}"]),
    emu!("rpcs3", "RPCS3", ["ps3"], "https://rpcs3.net/download", ["rpcs3.exe"], ["RPCS3"], ["--no-gui", "{file}"]),
    emu!("ppsspp", "PPSSPP", ["psp", "pspminis"], "https://www.ppsspp.org/download/", ["PPSSPPWindows64.exe", "PPSSPPWindows.exe"],
        ["PPSSPP"], ["--fullscreen", "{file}"]),
    // Les jeux doivent d'abord être installés dans Vita3K (fichiers .vpk / .pkg) : pas de lancement direct.
    emu!("vita3k", "Vita3K", ["vita"], "https://vita3k.org/", ["Vita3K.exe"], ["Vita3K"], None),
    emu!("dolphin", "Dolphin", ["gc", "wii", "wiiware", "triforce"], "https://dolphin-emu.org/download/", ["Dolphin.exe"],
        ["Dolphin", "Dolphin-x64", "Dolphin Emulator"], ["-b", "-e", "{file}"]),
    emu!("cemu", "Cemu", ["wiiu"], "https://cemu.info/", ["Cemu.exe"], ["Cemu"], ["-f", "-g", "{file}"]),
    emu!("project64", "Project64", ["n64"], "https://www.pj64-emu.com/", ["Project64.exe"], ["Project64 3.0", "Project64"], ["{file}"]),
    emu!("melonds", "melonDS", ["nds", "ndsi"], "https://melonds.kuribo64.net/downloads.php", ["melonDS.exe"], ["melonDS"], ["{file}"]),
    emu!("azahar", "Azahar", ["3ds"], "https://azahar-emu.org/", ["azahar.exe"], ["Azahar"], ["{file}"]),
    emu!("mgba", "mGBA", ["gba", "gb", "gbc"], "https://mgba.io/downloads.html", ["mGBA.exe"], ["mGBA"], ["-f", "{file}"]),
    emu!("snes9x", "Snes9x", ["snes", "satellaview"], "https://www.snes9x.com/", ["snes9x-x64.exe", "snes9x.exe"], ["Snes9x", "snes9x"], ["{file}"]),
    emu!("mesen", "Mesen", ["nes", "fds", "snes", "gb", "gbc", "gba", "tg16", "tgcd", "supergrafx", "master", "gamegear", "ws", "wsc"],
        "https://www.mesen.ca/", ["Mesen.exe"], ["Mesen", "Mesen2"], ["{file}"]),
    emu!("ares", "ares", ["n64", "nes", "snes", "gb", "gbc", "gba", "genesis", "segacd", "sega32x", "master", "gamegear", "sg1000", "tg16", "tgcd", "supergrafx", "ngp", "ngpc", "ws", "wsc", "coleco", "msx"],
        "https://ares-emu.net/download", ["ares.exe"], ["ares"], ["--fullscreen", "{file}"]),
    emu!("flycast", "Flycast", ["dreamcast", "naomi", "atomiswave"], "https://github.com/flyinghead/flycast/releases", ["flycast.exe"],
        ["Flycast", "flycast"], ["{file}"]),
    emu!("redream", "Redream", ["dreamcast"], "https://redream.io/download", ["redream.exe"], ["redream", "Redream"], ["{file}"]),
    emu!("mednafen", "Mednafen", ["saturn", "psx", "tg16", "tgcd", "supergrafx", "pcfx", "lynx", "virtualboy", "ngp", "ngpc", "ws", "wsc"],
        "https://mednafen.github.io/releases/", ["mednafen.exe"], ["mednafen", "Mednafen"], ["{file}"]),
    emu!("supermodel", "Supermodel", ["model3"], "https://www.supermodel3.com/Download.html", ["Supermodel.exe"], ["Supermodel"], ["{file}"]),
    // MAME attend le nom court du jeu et le dossier qui contient le zip.
    emu!("mame", "MAME", ["mame", "fbneo", "cps1", "cps2", "cps3", "neogeo", "stv", "naomi"], "https://www.mamedev.org/release.html",
        ["mame.exe", "mame64.exe"], ["MAME", "mame"], ["-rompath", "{dir}", "{basename}"]),
    emu!("xemu", "xemu", ["xbox"], "https://xemu.app/", ["xemu.exe"], ["xemu"], ["-full-screen", "-dvd_path", "{file}"]),
    emu!("xenia", "Xenia Canary", ["xbox360"], "https://github.com/xenia-canary/xenia-canary-releases/releases",
        ["xenia_canary.exe", "xenia.exe"], ["Xenia", "xenia", "xenia_canary"], ["{file}"]),
    emu!("stella", "Stella", ["atari2600"], "https://stella-emu.github.io/downloads.html", ["Stella.exe"], ["Stella"], ["{file}"]),
    // Détecte le jeu contenu dans le dossier du fichier.
    emu!("scummvm", "ScummVM", ["scummvm"], "https://www.scummvm.org/downloads/", ["scummvm.exe"], ["ScummVM"], ["-p", "{dir}", "--auto-detect"]),
];

pub fn by_id(id: &str) -> Option<&'static Emulator> {
    EMULATORS.iter().find(|e| e.id == id)
}

/// Émulateurs du catalogue adaptés à un système (identifiant ou nom court).
pub fn for_system(system: &Value) -> Vec<&'static Emulator> {
    let keys: Vec<String> = ["shortname", "id"]
        .iter()
        .filter_map(|k| system.get(*k).and_then(Value::as_str))
        .filter(|s| !s.is_empty())
        .map(|s| s.to_lowercase())
        .collect();
    EMULATORS.iter().filter(|e| e.systems.iter().any(|s| keys.iter().any(|k| k == s))).collect()
}

/// Remplace les variables d'une liste d'arguments pour le fichier de jeu donné.
pub fn build_args<S: AsRef<str>>(args: &[S], file: &str) -> Vec<String> {
    let path = Path::new(file);
    let values = [
        ("{file}", file.to_string()),
        ("{dir}", paths::dirname(file)),
        ("{name}", paths::file_name(path)),
        ("{basename}", paths::stem(file)),
    ];
    args.iter()
        .map(|a| {
            let mut out = a.as_ref().to_string();
            for (k, v) in &values {
                out = out.replace(k, v);
            }
            out
        })
        .collect()
}

fn quote(s: &str) -> String {
    if s.is_empty() || s.chars().any(|c| c.is_whitespace() || c == '"') { format!("\"{s}\"") } else { s.to_string() }
}

/// Ligne de commande affichée à l'utilisateur (arguments contenant des espaces entre guillemets).
pub fn command_line<S: AsRef<str>>(exe: &str, args: &[S]) -> String {
    std::iter::once(quote(exe)).chain(args.iter().map(|a| quote(a.as_ref()))).collect::<Vec<_>>().join(" ")
}

/// Arguments seuls, sous forme de texte modifiable (« -b -e {file} »).
pub fn args_line(args: Option<&[&str]>) -> String {
    args.unwrap_or(&[]).iter().map(|a| quote(a)).collect::<Vec<_>>().join(" ")
}

pub fn system_name(id: &str) -> String {
    let name = match id {
        "psx" => "PlayStation", "ps2" => "PlayStation 2", "ps3" => "PlayStation 3", "psp" => "PSP", "pspminis" => "PSP Minis",
        "vita" => "PS Vita", "gc" => "GameCube", "wii" => "Wii", "wiiware" => "WiiWare", "triforce" => "Triforce", "wiiu" => "Wii U",
        "n64" => "Nintendo 64", "nds" => "Nintendo DS", "ndsi" => "Nintendo DSi", "3ds" => "Nintendo 3DS", "gba" => "Game Boy Advance",
        "gb" => "Game Boy", "gbc" => "Game Boy Color", "snes" => "Super Nintendo", "satellaview" => "Satellaview", "nes" => "NES",
        "fds" => "Famicom Disk System", "tg16" => "PC Engine", "tgcd" => "PC Engine CD", "supergrafx" => "SuperGrafx", "pcfx" => "PC-FX",
        "master" => "Master System", "gamegear" => "Game Gear", "sg1000" => "SG-1000", "genesis" => "Mega Drive", "segacd" => "Mega-CD",
        "sega32x" => "32X", "saturn" => "Saturn", "dreamcast" => "Dreamcast", "naomi" => "Naomi", "atomiswave" => "Atomiswave",
        "model3" => "Model 3", "stv" => "ST-V", "ws" => "WonderSwan", "wsc" => "WonderSwan Color", "ngp" => "Neo Geo Pocket",
        "ngpc" => "Neo Geo Pocket Color", "neogeo" => "Neo Geo", "lynx" => "Lynx", "virtualboy" => "Virtual Boy", "coleco" => "ColecoVision",
        "msx" => "MSX", "atari2600" => "Atari 2600", "mame" => "Arcade (MAME)", "fbneo" => "FinalBurn Neo", "cps1" => "CPS-1",
        "cps2" => "CPS-2", "cps3" => "CPS-3", "xbox" => "Xbox", "xbox360" => "Xbox 360", "scummvm" => "ScummVM",
        other => other,
    };
    name.to_string()
}

/// Variables d'environnement : celles de Windows, ou fournies (tests).
pub type Env<'a> = &'a dyn Fn(&str) -> Option<String>;

fn system_env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

/// Dossiers où chercher les émulateurs, avec la profondeur de recherche.
fn search_roots(env: Env) -> Vec<(PathBuf, u32)> {
    let home = env("USERPROFILE").map(PathBuf::from);
    let mut roots: Vec<(Option<PathBuf>, u32)> = vec![
        (Some(PathBuf::from(env("ProgramFiles").unwrap_or_else(|| "C:\\Program Files".into()))), 2),
        (Some(PathBuf::from(env("ProgramFiles(x86)").unwrap_or_else(|| "C:\\Program Files (x86)".into()))), 2),
        (env("LOCALAPPDATA").map(|d| PathBuf::from(d).join("Programs")), 2),
        (home.as_ref().map(|h| h.join("Desktop")), 2),
        (home.as_ref().map(|h| h.join("Downloads")), 2),
        (home.as_ref().map(|h| h.join("Documents")), 1),
        (home.as_ref().map(|h| h.join("scoop").join("apps")), 2),
        (home.clone(), 1),
    ];
    for drive in ['C', 'D', 'E', 'F'] {
        roots.push((Some(PathBuf::from(format!("{drive}:\\"))), 1));
        for name in ["Emulators", "Emulateurs", "Emulation", "Emu", "Games", "Jeux"] {
            roots.push((Some(PathBuf::from(format!("{drive}:\\{name}"))), 3));
        }
    }
    let mut seen = HashSet::new();
    roots
        .into_iter()
        .filter_map(|(dir, depth)| dir.map(|d| (d, depth)))
        .filter(|(dir, _)| seen.insert(dir.to_string_lossy().to_lowercase()))
        .collect()
}

/// Emplacement par défaut affiché pour un émulateur (dossier d'installation habituel).
pub fn default_location(emu: &Emulator) -> PathBuf {
    default_location_in(emu, &system_env)
}

fn default_location_in(emu: &Emulator, env: Env) -> PathBuf {
    let program_files = env("ProgramFiles").unwrap_or_else(|| "C:\\Program Files".into());
    let dir = if emu.id == "project64" {
        PathBuf::from(env("ProgramFiles(x86)").unwrap_or_else(|| "C:\\Program Files (x86)".into())).join("Project64 3.0")
    } else {
        PathBuf::from(program_files).join(emu.dirs[0])
    };
    dir.join(emu.exe[0])
}

// Dossiers système ou volumineux jamais parcourus.
const SKIP_DIRS: &[&str] = &["windows", "appdata", "node_modules", "$recycle.bin", "system volume information", "programdata", "common files", "windowsapps", ".git"];

/// Cherche les exécutables des émulateurs du catalogue ; renvoie { id: chemin trouvé }.
pub fn detect() -> HashMap<String, String> {
    detect_in(&system_env, &EMULATORS.iter().collect::<Vec<_>>())
}

/// Recherche des [emulators] avec les variables d'environnement [env].
fn detect_in(env: Env, emulators: &[&Emulator]) -> HashMap<String, String> {
    let mut found: HashMap<String, String> = HashMap::new();
    let mut by_exe: HashMap<String, Vec<&Emulator>> = HashMap::new();
    for emu in emulators.iter().copied() {
        for exe in emu.exe {
            by_exe.entry(exe.to_lowercase()).or_default().push(emu);
        }
    }
    // D'abord les emplacements d'installation habituels (rapide).
    for emu in emulators {
        let file = default_location_in(emu, env);
        if file.exists() {
            found.insert(emu.id.into(), file.to_string_lossy().into_owned());
        }
    }
    let mut visited: HashMap<String, u32> = HashMap::new();
    let total = emulators.len();
    fn walk(dir: &Path, depth: u32, total: usize, visited: &mut HashMap<String, u32>, found: &mut HashMap<String, String>, by_exe: &HashMap<String, Vec<&Emulator>>) {
        let key = dir.to_string_lossy().to_lowercase();
        if visited.get(&key).map(|d| *d >= depth).unwrap_or(false) || found.len() == total {
            return;
        }
        visited.insert(key, depth);
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        let entries: Vec<_> = entries.flatten().collect();
        for e in &entries {
            if e.file_type().map(|t| t.is_file()).unwrap_or(false) {
                for emu in by_exe.get(&e.file_name().to_string_lossy().to_lowercase()).into_iter().flatten() {
                    found.entry(emu.id.into()).or_insert_with(|| e.path().to_string_lossy().into_owned());
                }
            }
        }
        if depth == 0 {
            return;
        }
        for e in &entries {
            let name = e.file_name().to_string_lossy().to_lowercase();
            if e.file_type().map(|t| t.is_dir()).unwrap_or(false) && !SKIP_DIRS.contains(&name.as_str()) && !name.starts_with('.') {
                walk(&e.path(), depth - 1, total, visited, found, by_exe);
            }
        }
    }
    for (root, depth) in search_roots(env) {
        walk(&root, depth, total, &mut visited, &mut found, &by_exe);
    }
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn catalogue_coherent() {
        let ids: HashSet<&str> = EMULATORS.iter().map(|e| e.id).collect();
        assert_eq!(ids.len(), EMULATORS.len());
        for e in EMULATORS {
            assert!(e.url.starts_with("https://"), "{}", e.id);
            assert!(!e.exe.is_empty() && e.exe.iter().all(|x| x.ends_with(".exe")), "{}", e.id);
            assert!(!e.systems.is_empty(), "{}", e.id);
            if let Some(args) = e.args {
                assert!(args.iter().any(|a| a.contains("{file}") || a.contains("{dir}") || a.contains("{basename}")), "{}", e.id);
            }
        }
    }

    #[test]
    fn emulateurs_selon_le_systeme() {
        let names = |system: Value| for_system(&system).iter().map(|e| e.id).collect::<Vec<_>>();
        assert_eq!(names(json!({ "id": "psx", "shortname": "psx" })), ["duckstation", "mednafen"]);
        assert_eq!(names(json!({ "id": "gamecube", "shortname": "gc" })), ["dolphin"]);
        assert!(names(json!({ "id": "n64", "shortname": "n64" })).contains(&"project64"));
        assert_eq!(names(json!({ "id": "vita", "shortname": "vita" })), ["vita3k"]);
        assert!(names(json!({ "id": "custom", "shortname": "custom" })).is_empty());
    }

    #[test]
    fn arguments_et_ligne_de_commande() {
        let file = r"D:\Jeux\gc\Super Mario Sunshine (Europe).rvz";
        assert_eq!(build_args(by_id("dolphin").unwrap().args.unwrap(), file), ["-b", "-e", file]);
        assert_eq!(build_args(by_id("mame").unwrap().args.unwrap(), r"D:\Jeux\mame\sf2.zip"), ["-rompath", r"D:\Jeux\mame", "sf2"]);
        assert_eq!(
            command_line(r"C:\Program Files\Dolphin\Dolphin.exe", &["-b", "-e", file]),
            r#""C:\Program Files\Dolphin\Dolphin.exe" -b -e "D:\Jeux\gc\Super Mario Sunshine (Europe).rvz""#,
        );
        assert!(by_id("vita3k").unwrap().args.is_none());
    }

    #[test]
    fn recherche_des_emulateurs() {
        let root = std::env::temp_dir().join(format!("romcloud-emu-{}", std::process::id()));
        let put = |parts: &[&str]| {
            let file = parts.iter().fold(root.clone(), |p, part| p.join(part));
            std::fs::create_dir_all(file.parent().unwrap()).unwrap();
            std::fs::write(&file, b"").unwrap();
            file
        };
        let pcsx2 = put(&["pf", "PCSX2", "pcsx2-qt.exe"]);
        let supermodel = put(&["home", "Downloads", "Supermodel_0.3a", "bin", "SUPERMODEL.EXE"]);
        put(&["home", "Downloads", "a", "b", "c", "xemu.exe"]); // trop profond
        let vars = HashMap::from([
            ("ProgramFiles", root.join("pf")),
            ("ProgramFiles(x86)", root.join("pf86")),
            ("USERPROFILE", root.join("home")),
        ]);
        let env = |name: &str| vars.get(name).map(|p| p.to_string_lossy().into_owned());
        assert_eq!(default_location_in(by_id("pcsx2").unwrap(), &env), pcsx2);
        let emulators = [by_id("pcsx2").unwrap(), by_id("supermodel").unwrap(), by_id("xemu").unwrap()];
        let found = detect_in(&env, &emulators);
        let _ = std::fs::remove_dir_all(&root);
        assert_eq!(found.get("pcsx2"), Some(&pcsx2.to_string_lossy().into_owned()));
        assert_eq!(found.get("supermodel").map(|p| p.to_lowercase()), Some(supermodel.to_string_lossy().to_lowercase()));
        assert_eq!(found.get("xemu"), None);
    }
}
