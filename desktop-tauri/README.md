# RomCloud pour Windows (Tauri)

Application Windows : interface de `desktop/src/renderer` (affichée par WebView2), processus
principal en Rust, moteur d'émulation intégré `desktop/native/player` (romcloud-player.exe), données
dans `%APPDATA%\RomCloud` (réglages, cache, cœurs, sauvegardes, profil ; mêmes emplacements que
l'ancienne version Electron, reprise sans migration).

- `src-tauri/src/bridge.js` : objet `window.romcloud` de l'interface.
- `src-tauri/src/ipc.rs` : répartition des canaux vers les modules.

## Compilation (sans Visual Studio)

Outils : Rust (cible `x86_64-pc-windows-gnullvm`), llvm-mingw dans le `PATH`, Node.js.

```sh
rustup toolchain install stable --target x86_64-pc-windows-gnullvm
npm install
npm run build          # installeur : src-tauri/target/x86_64-pc-windows-gnullvm/release/bundle/nsis/
```

Mises à jour signées (tauri-plugin-updater) : l'installeur est signé avec la clé privée
`%USERPROFILE%\.tauri\romcloud-updater.key` (clé publique dans `tauri.conf.json`) ; avant `npm run build` :

```sh
export TAURI_SIGNING_PRIVATE_KEY="$HOME/.tauri/romcloud-updater.key" TAURI_SIGNING_PRIVATE_KEY_PASSWORD=""
```

Sans clé : `npx tauri build --target x86_64-pc-windows-gnullvm --config '{"bundle":{"createUpdaterArtifacts":false}}'`.
Clé perdue : les installations existantes ne pourront plus se mettre à jour d'elles-mêmes.

Le moteur (`desktop/native/player/build/romcloud-player.exe`) doit être compilé avant (`npm run player`
dans `desktop`). `src-tauri/resources/WebView2Loader.dll` (Microsoft, redistribuable) est installée à
côté de l'exe, nécessaire avec cette chaîne de compilation.

Tests : `cargo test` dans `src-tauri` (fonctions sans effets de bord : Chromecast, catalogue des
émulateurs, cœurs, clavier, arguments du moteur, mises à jour…). Les tests de l'interface restent dans
`desktop/test` (`criteria`, `gamepad`).

Intégration continue : `.github/workflows/desktop-tauri.yml` (tests, moteur, installeur ; chaîne de
Visual Studio du runner) ; sur un tag `v*`, `RomCloud-Windows-<version>-tauri-x64.exe` est ajouté à la
Release avec `latest.json` (version, adresse et signature de l'installeur), lu par la mise à jour
automatique (`https://github.com/biliboobrian/romCloud/releases/latest/download/latest.json`). Secrets
du dépôt : `TAURI_SIGNING_PRIVATE_KEY` (contenu de la clé privée) et `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`.

Développement : `npm run dev` ; `ROMCLOUD_DEBUG_PORT=9222` ouvre les outils de débogage de WebView2
(versions de développement).
