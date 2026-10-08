// Évènements envoyés à l'interface (téléchargements, profil, connexion, diffusion…).
use serde::Serialize;
use std::sync::OnceLock;
use tauri::{AppHandle, Emitter};

static HANDLE: OnceLock<AppHandle> = OnceLock::new();

pub fn init(handle: AppHandle) {
    let _ = HANDLE.set(handle);
}

pub fn handle() -> Option<&'static AppHandle> {
    HANDLE.get()
}

/// Envoie l'évènement [name] à l'interface.
pub fn emit<S: Serialize + Clone>(name: &str, payload: S) {
    if let Some(handle) = HANDLE.get() {
        let _ = handle.emit(name, payload);
    }
}
