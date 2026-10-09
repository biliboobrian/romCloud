// RomCloud pour Windows (Tauri) : interface de desktop/src/renderer affichée par WebView2,
// processus principal en Rust, moteur d'émulation intégré desktop/native/player
// (romcloud-player.exe), données dans %APPDATA%\RomCloud.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod account;
mod api;
mod builtin;
mod cast;
mod catalog;
mod connectivity;
mod cores;
mod downloads;
mod error;
mod events;
mod ipc;
mod keyboard;
mod launcher;
mod library;
mod managed;
mod netplay;
mod paths;
mod screencast;
mod settings;
mod updater;

use tauri::webview::NewWindowResponse;
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

fn main() {
    tauri::Builder::default()
        // Une seule fenêtre : un second lancement ramène la première au premier plan.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        // Mises à jour signées (latest.json des Releases GitHub, voir updater.rs).
        .plugin(tauri_plugin_updater::Builder::new().build())
        .invoke_handler(tauri::generate_handler![ipc::ipc, ipc::cast_chunk])
        .setup(|app| {
            events::init(app.handle().clone());
            // Jeu à plusieurs : annonce de ce PC et appareils RomCloud du réseau local.
            netplay::start();
            let mut window = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()));
            // Développement : outils de débogage de WebView2 sur le port ROMCLOUD_DEBUG_PORT (arguments
            // par défaut de Tauri conservés).
            if cfg!(debug_assertions) {
                if let Ok(port) = std::env::var("ROMCLOUD_DEBUG_PORT") {
                    window = window.additional_browser_args(&format!(
                        "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --remote-debugging-port={port}"
                    ));
                }
            }
            window
                .title("RomCloud")
                .inner_size(1400.0, 880.0)
                .min_inner_size(900.0, 600.0)
                .background_color(tauri::window::Color(17, 19, 24, 255))
                .initialization_script(include_str!("bridge.js"))
                // Liens externes (ex. site de RetroArch) : navigateur par défaut.
                .on_new_window(|url, _features| {
                    if url.scheme() == "http" || url.scheme() == "https" {
                        let _ = tauri_plugin_opener::open_url(url.as_str(), None::<&str>);
                    }
                    NewWindowResponse::Deny
                })
                .build()?;
            // Travail laissé en attente à la dernière fermeture.
            tauri::async_runtime::spawn(async {
                account::flush().await;
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("RomCloud")
        .run(|_app, event| {
            if let tauri::RunEvent::ExitRequested { .. } = event {
                tauri::async_runtime::block_on(screencast::stop());
            }
        });
}
