// Manettes du moteur intégré dans la fiche d'un jeu : romcloud-player lancé sans fenêtre
// (--pad-service, voir desktop/native/player/src/padservice.h) pour le système du jeu. Ses lignes
// JSON (manettes des joueurs, bouton appuyé, configuration en cours) sont envoyées à l'interface
// (évènement « pads:event ») ; les commandes de l'interface lui sont transmises (config, skip,
// reset, cancel, order …). Un seul à la fois : arrêté en quittant la fiche ou en lançant un jeu.
use crate::builtin;
use crate::error::{AppError, Result};
use crate::events;
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::Mutex;

struct Service {
    child: Child,
    stdin: ChildStdin,
}

static SERVICE: Mutex<Option<Service>> = Mutex::new(None);

/// Démarre le service pour le système du jeu (celui en cours est arrêté).
pub fn start(system: &Value, core: &str) -> Result<()> {
    stop();
    if !builtin::available() {
        return Err(AppError::new("errors.playerMissing", json!({ "path": builtin::player_path().to_string_lossy() })));
    }
    let mut command = Command::new(builtin::player_path());
    command.args(builtin::pad_service_args(system, core)).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let mut child = command.spawn().map_err(|e| AppError::new("errors.launchFailed", json!({ "detail": e.to_string() })))?;
    let stdin = child.stdin.take().ok_or_else(|| AppError::msg("stdin".to_string()))?;
    let stdout = child.stdout.take().ok_or_else(|| AppError::msg("stdout".to_string()))?;
    let pid = child.id();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(std::result::Result::ok) {
            if let Ok(value) = serde_json::from_str::<Value>(&line) {
                events::emit("pads:event", json!({ "pid": pid, "event": value }));
            }
        }
    });
    *SERVICE.lock().unwrap() = Some(Service { child, stdin });
    Ok(())
}

/// Commande pour le service en cours (sans effet s'il n'y en a pas).
pub fn command(line: &str) -> Result<()> {
    let mut service = SERVICE.lock().unwrap();
    if let Some(s) = service.as_mut() {
        writeln!(s.stdin, "{}", line.replace('\n', " "))?;
        s.stdin.flush()?;
    }
    Ok(())
}

/// Arrête le service : « quit », puis arrêt forcé s'il ne s'est pas fermé.
pub fn stop() {
    let Some(mut s) = SERVICE.lock().unwrap().take() else { return };
    let _ = writeln!(s.stdin, "quit");
    let _ = s.stdin.flush();
    drop(s.stdin);
    std::thread::spawn(move || {
        for _ in 0..20 {
            if matches!(s.child.try_wait(), Ok(Some(_))) {
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        let _ = s.child.kill();
        let _ = s.child.wait();
    });
}
