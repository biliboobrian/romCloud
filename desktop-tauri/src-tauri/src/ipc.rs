// Opérations demandées par l'interface : canaux « settings:get », « launcher:play »…, reçus par
// une seule commande et répartis ici. Le pont
// (bridge.js) renvoie à l'interface { ok, data } ou { ok: false, error }.
use crate::error::{AppError, Result};
use crate::{account, api, builtin, connectivity, downloads, keyboard, launcher, library, netplay, screencast, settings, updater};
use serde_json::{json, Value};
use tauri_plugin_dialog::DialogExt;

fn str_arg(args: &[Value], i: usize) -> String {
    args.get(i).and_then(Value::as_str).unwrap_or("").to_string()
}

fn arg(args: &[Value], i: usize) -> Value {
    args.get(i).cloned().unwrap_or(Value::Null)
}

fn list(args: &[Value], i: usize) -> Vec<Value> {
    args.get(i).and_then(Value::as_array).cloned().unwrap_or_default()
}

/// Boîte de dialogue de choix d'un fichier ([filters] : [{ name, extensions }]) ou d'un dossier.
async fn pick(app: tauri::AppHandle, filters: Value, folder: bool) -> Result<Value> {
    tokio::task::spawn_blocking(move || {
        let mut dialog = app.dialog().file();
        for f in filters.as_array().cloned().unwrap_or_default() {
            let extensions: Vec<String> = f["extensions"].as_array().cloned().unwrap_or_default().iter().filter_map(|e| e.as_str().map(String::from)).collect();
            let refs: Vec<&str> = extensions.iter().map(String::as_str).collect();
            dialog = dialog.add_filter(f["name"].as_str().unwrap_or(""), &refs);
        }
        let path = if folder { dialog.blocking_pick_folder() } else { dialog.blocking_pick_file() };
        path.and_then(|p| p.into_path().ok()).map(|p| json!(p.to_string_lossy())).unwrap_or(Value::Null)
    })
    .await
    .map_err(|e| AppError::msg(e.to_string()))
}

#[tauri::command]
pub async fn ipc(app: tauri::AppHandle, channel: String, args: Vec<Value>) -> std::result::Result<Value, AppError> {
    let a = &args;
    Ok(match channel.as_str() {
        "settings:get" => settings::view(),
        "settings:save" => {
            settings::save(arg(a, 0));
            settings::view()
        }
        "api:test" => api::test(&str_arg(a, 0), &str_arg(a, 1)).await?,
        "api:systems" => api::systems().await?,
        "api:games" => api::games(&str_arg(a, 0)).await?,
        "api:search" => api::search(&str_arg(a, 0)).await?,
        "library:downloaded" => json!(library::downloaded_ids(&list(a, 0), &list(a, 1))),
        "library:path" => json!(library::file_for(&arg(a, 0), &arg(a, 1)).to_string_lossy()),
        "library:remove" => {
            library::remove(&arg(a, 0), &arg(a, 1));
            Value::Null
        }
        "library:systemsWithGames" => json!(library::systems_with_games(&list(a, 0))),
        "library:usage" => library::usage(&list(a, 0)),
        "connectivity:state" => connectivity::state(),
        "connectivity:check" => {
            connectivity::probe().await;
            connectivity::state()
        }
        // BIOS du système absents du PC (téléchargés avec le jeu si l'utilisateur le demande).
        "bios:missing" => json!(library::missing_bios(api::bios(&arg(a, 0)).await)),
        "downloads:start" => {
            downloads::start(arg(a, 0), arg(a, 1), arg(a, 2)); // la progression arrive par « downloads:update »
            Value::Null
        }
        "downloads:cancel" => {
            downloads::cancel(&arg(a, 0));
            Value::Null
        }
        "downloads:dismiss" => {
            downloads::dismiss_error(&arg(a, 0));
            Value::Null
        }
        "downloads:states" => downloads::states(),
        "launcher:options" => launcher::options(&arg(a, 0)),
        "launcher:choose" => {
            launcher::choose(&str_arg(a, 0), &arg(a, 1), &arg(a, 2));
            Value::Null
        }
        "launcher:play" => launcher::play(&arg(a, 0), &arg(a, 1), &arg(a, 2)).await?,
        "launcher:resumable" => json!(launcher::resumable(&arg(a, 0), &arg(a, 1))),
        "launcher:resumableIds" => json!(launcher::resumable_ids(&arg(a, 0), &list(a, 1))),
        "launcher:check" => launcher::check(&arg(a, 0)),
        "launcher:resumableOnline" => launcher::resumable_online(&arg(a, 0), &arg(a, 1)).await,
        "launcher:describe" => launcher::describe(&arg(a, 0), &arg(a, 1)),
        // Historique des états du moteur intégré (fiche du jeu).
        "states:list" => launcher::states(&arg(a, 0), &arg(a, 1)).await,
        "states:action" => launcher::state_action(&arg(a, 0), &arg(a, 1), &str_arg(a, 2), &arg(a, 3)).await?,
        // Moteur intégré arrêté sur une erreur : l'interface propose de réinitialiser le cœur.
        "player:resetCore" => {
            builtin::reset_core(&str_arg(a, 0), &str_arg(a, 1))?;
            Value::Null
        }
        "account:state" => account::state().await,
        "account:register" => account::register(&str_arg(a, 0), &str_arg(a, 1)).await?,
        "account:login" => account::login(&str_arg(a, 0), &str_arg(a, 1)).await?,
        "account:logout" => account::logout().await?,
        "account:playtime" => account::playtime().await,
        "account:reportError" => {
            let report = arg(a, 0);
            let details = report.get("details").map(|d| d.as_str().map(String::from).unwrap_or_else(|| d.to_string()));
            account::report_error(report["context"].as_str().unwrap_or(""), report["message"].as_str().unwrap_or(""), details);
            Value::Null
        }
        "emulators:list" => launcher::list_emulators(),
        "netplay:peers" => netplay::peers(),
        "netplay:systemAllows" => json!(netplay::system_allows(&arg(a, 0))),
        "netplay:systemTogether" => netplay::system_together(&arg(a, 0)),
        "emulators:detect" => tokio::task::spawn_blocking(launcher::detect_emulators).await.map_err(|e| AppError::msg(e.to_string()))?,
        "emulators:setPath" => launcher::set_emulator_path(&str_arg(a, 0), &str_arg(a, 1))?,
        "emulators:setArgs" => launcher::set_emulator_args(&str_arg(a, 0), &str_arg(a, 1))?,
        // Dernière version d'un émulateur (Eden) : vérifiée puis installée à la place de l'actuelle.
        "emulators:checkUpdate" => crate::emulator_updates::check(&str_arg(a, 0)).await?,
        "emulators:update" => crate::emulator_updates::install(&str_arg(a, 0)).await?,
        "emulators:launch" => {
            launcher::launch_emulator(&str_arg(a, 0))?;
            Value::Null
        }
        "dialog:pickFile" => pick(app, arg(a, 0), false).await?,
        "dialog:pickFolder" => pick(app, Value::Null, true).await?,
        "shell:showItem" => {
            let _ = tauri_plugin_opener::reveal_item_in_dir(str_arg(a, 0));
            Value::Null
        }
        "shell:openExternal" => {
            let _ = tauri_plugin_opener::open_url(str_arg(a, 0), None::<&str>);
            Value::Null
        }
        "keyboard:layout" => keyboard::layout(builtin::load_keys()),
        "keyboard:save" => builtin::save_keys(&arg(a, 0))?,
        "app:version" => json!(account::version()),
        // Diffusion de l'écran sur un Chromecast.
        "cast:discover" => crate::cast::discover().await,
        "cast:start" => screencast::start(arg(a, 0)).await?,
        "cast:stop" => {
            screencast::stop().await;
            Value::Null
        }
        "cast:state" => screencast::state().await,
        "cast:captureError" => {
            screencast::capture_error(&str_arg(a, 0)).await;
            Value::Null
        }
        // Mise à jour : vérifiée au chargement de l'interface ; installation sur confirmation.
        "update:check" => updater::check().await,
        "update:install" => {
            updater::install().await?;
            Value::Null
        }
        other => return Err(AppError::msg(format!("canal inconnu : {other}"))),
    })
}

/// Morceau du flux WebM de la diffusion sur un Chromecast (corps binaire de la requête).
#[tauri::command]
pub async fn cast_chunk(request: tauri::ipc::Request<'_>) -> std::result::Result<(), AppError> {
    if let tauri::ipc::InvokeBody::Raw(bytes) = request.body() {
        screencast::chunk(bytes.clone()).await;
    }
    Ok(())
}
