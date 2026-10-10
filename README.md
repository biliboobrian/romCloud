# RomCloud

[![Release](https://img.shields.io/github/v/release/biliboobrian/romCloud?label=APK&logo=android)](https://github.com/biliboobrian/romCloud/releases/latest)
[![Docker Hub](https://img.shields.io/docker/v/biliboobrian/romcloud-server?label=Docker%20Hub&logo=docker&sort=semver)](https://hub.docker.com/r/biliboobrian/romcloud-server)
[![Android APK](https://github.com/biliboobrian/romCloud/actions/workflows/android.yml/badge.svg)](https://github.com/biliboobrian/romCloud/actions/workflows/android.yml)
[![Serveur Docker](https://github.com/biliboobrian/romCloud/actions/workflows/server-docker.yml/badge.svg)](https://github.com/biliboobrian/romCloud/actions/workflows/server-docker.yml)

Frontend Android de rétro-gaming inspiré de [Daijishou](https://github.com/TapiocaFox/Daijishou), dont la bibliothèque de jeux se trouve sur un **serveur distant** : l'application affiche la liste des jeux de chaque système, télécharge une ROM à la demande, puis la lance dans l'émulateur choisi.

> Le code source de l'APK Daijishou n'est pas public : son dépôt GitHub ne contient que les définitions de plateformes et d'émulateurs (JSON, licence MIT). RomCloud est donc une application distincte qui **réutilise ces définitions** : même catalogue de systèmes, mêmes modèles de lancement `am start` (RetroArch, Dolphin, DuckStation, PPSSPP…).

```
┌───────────────────────────┐        HTTP (clé d'API)        ┌──────────────────────────────┐
│ Serveur Node.js  (server/)│ ◄────────────────────────────► │ Application Android (android/)│
│  • interface web d'admin  │   /api/systems                 │  • liste des systèmes / jeux │
│  • ROMs par système       │   /api/systems/:id/games       │  • indicateur téléchargé ✓/☁ │
│  • scraping ScreenScraper │   /api/games/:id/file (Range)  │  • téléchargement à la demande│
│    + Libretro             │   /api/games/:id/media/boxart  │  • lancement de l'émulateur  │
└───────────────────────────┘                                └──────────────────────────────┘
```

## Installation

### Serveur — Docker Hub

L’image [`biliboobrian/romcloud-server`](https://hub.docker.com/r/biliboobrian/romcloud-server) est publiée pour **amd64** et **arm64** (PC, NAS Synology/Unraid, Raspberry Pi 4/5).

```bash
mkdir -p romcloud/data && sudo chown -R 1000:1000 romcloud/data
docker run -d --name romcloud --restart unless-stopped \
  -p 8080:8080 \
  -v "$PWD/romcloud/data:/data" \
  -e API_KEY=cle-des-applications   -e ADMIN_KEY=cle-d-administration \
  biliboobrian/romcloud-server:latest
```

Ou avec Docker Compose — fichier `docker-compose.yml`, puis `docker compose up -d` :

```yaml
services:
  romcloud:
    image: biliboobrian/romcloud-server:latest
    container_name: romcloud
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - ./data:/data                 # base de données, images scrapées, ROMs (./data/roms)
      # - /mnt/nas/roms:/data/roms   # ou un dossier de ROMs existant
    environment:
      API_KEY: cle-des-applications      # applications Windows / Android / TV (lecture seule)
      ADMIN_KEY: cle-d-administration    # interface web (accès complet)
      # Facultatif : scraping ScreenScraper (sinon Libretro seul)
      SCREENSCRAPER_DEV_ID: ""
      SCREENSCRAPER_DEV_PASSWORD: ""
      SCREENSCRAPER_USER: ""
      SCREENSCRAPER_PASSWORD: ""
```

Ouvrez ensuite `http://<ip-du-serveur>:8080` pour l’interface web d’administration (la clé d’API est demandée au premier accès).

| Tag | Contenu |
|---|---|
| `latest` | Dernier état de la branche `main` |
| `1.0.0`, `1.0`, `1` | Versions publiées (recommandé pour éviter les changements inattendus) |
| `sha-xxxxxxx` | Image d’un commit précis |

Mise à jour : `docker compose pull && docker compose up -d` (les données dans `/data` sont conservées).

Le conteneur s’exécute avec l’utilisateur `node` (uid 1000) : le dossier monté sur `/data` doit lui être accessible en écriture (d’où le `chown` ci-dessus), sinon lancez le conteneur avec `--user <votre-uid>`.

### Application Android — Obtainium (recommandé)

[Obtainium](https://github.com/ImranR98/Obtainium) installe l’APK depuis les [Releases GitHub](https://github.com/biliboobrian/romCloud/releases) et vous prévient à chaque nouvelle version.

1. Installez Obtainium depuis ses [Releases](https://github.com/ImranR98/Obtainium/releases/latest) ou [F-Droid](https://f-droid.org/packages/dev.imranr.obtainium.fdroid/).
2. Dans Obtainium : **Ajouter une application**, collez l’URL `https://github.com/biliboobrian/romCloud`, et dans **Filtrer les APK par expression régulière** saisissez `RomCloud-\d` (chaque Release contient aussi l’APK TV), puis **Ajouter**.
3. Appuyez sur **Installer** (autorisez Obtainium à installer des applications si Android le demande).

Les mises à jour apparaissent ensuite dans Obtainium dès qu’une nouvelle Release est publiée.

### Application Android — installation manuelle

Téléchargez `RomCloud-<version>.apk` depuis la [dernière Release](https://github.com/biliboobrian/romCloud/releases/latest) et ouvrez-le sur le téléphone (autorisez l’installation depuis le navigateur ou le gestionnaire de fichiers).

**Mises à jour** : au démarrage, l’application (téléphone et TV) vérifie la dernière Release GitHub et, si elle est plus récente, propose de la télécharger et de l’installer (Android demande la première fois d’autoriser RomCloud à installer des applications). Les versions de développement ne sont pas vérifiées.

Android 8 minimum. Les APK sont signés avec la clé RomCloud — empreinte SHA-256 du certificat :
`3d7da376fa94f95e0c7d46b26638961c9a818c6f2170c04a6dd8cec9d371d559`
(vérifiable avec `apksigner verify --print-certs RomCloud-<version>.apk`).

### Application Android TV

**RomCloud TV** (`RomCloud-TV-<version>.apk`, paquet `com.romcloud.app.tv`) est une application distincte, pensée pour la télécommande : elle apparaît dans le lanceur Android TV / Google TV avec sa bannière. Même serveur, même version, même clé de signature que l’application téléphone ; les deux peuvent être installées côte à côte.

Installation sur le téléviseur :
- **Obtainium** (installé sur le téléviseur) : même URL que ci-dessus, avec le filtre `RomCloud-TV` ;
- ou l’application **Downloader** (AFTVnews) : saisissez l’adresse de l’APK TV de la [dernière Release](https://github.com/biliboobrian/romCloud/releases/latest) ;
- ou depuis un ordinateur : `adb connect <ip-du-téléviseur>` puis `adb install RomCloud-TV-<version>.apk`.

Accès aux fichiers : beaucoup de téléviseurs n’ont pas l’écran « Accès à tous les fichiers ». RomCloud TV l’ouvre s’il existe, sinon il affiche la commande à exécuter une fois depuis un ordinateur (débogage ADB activé dans les Options pour les développeurs) :

```bash
adb shell appops set --uid com.romcloud.app.tv MANAGE_EXTERNAL_STORAGE allow
```

Utilisation à la télécommande :
- **Systèmes** : grille de cartes (image configurée sur le serveur) ; *Actualiser* et *Paramètres* en haut.
- **Jeux** : l’arrière-plan et le haut de l’écran présentent le jeu sélectionné (capture, titre, infos, description, état) ; dessous, les filtres *Tous / Téléchargés / À télécharger*, *Rechercher*, puis les rangées *Téléchargés*, *Ajoutés récemment* et par genre.
- **OK** : joue un jeu téléchargé, sinon propose *Télécharger et jouer* / *Télécharger* ; **appui long sur OK** : fiche du jeu.
- **Fiche du jeu** : Jouer / Télécharger / Annuler, choix de l’émulateur, *Configurer RetroArch* (même guide que sur téléphone), *Supprimer du téléviseur*.
- Le focus revient sur le dernier élément choisi au retour d’un écran ou d’une partie.

### Application Windows

**RomCloud pour Windows** reprend l’application Android sur PC : systèmes, jeux en carrousel ou en liste, filtres, recherche globale (logo de la console sur les cartes), fiche du jeu, téléchargement à la demande avec reprise, cache hors ligne, BIOS, français / anglais.

- **Installation** : `RomCloud-Windows-<version>-tauri-x64.exe` (installeur de 2,5 Mo, sans droits d’administrateur) depuis la [dernière Release](https://github.com/biliboobrian/romCloud/releases/latest). Il utilise WebView2, déjà présent dans Windows 10 et 11 (téléchargé par l’installeur sinon). L’application n’étant pas signée, Windows SmartScreen peut afficher « Windows a protégé votre ordinateur » : *Informations complémentaires → Exécuter quand même*.
- **Mises à jour** : au démarrage, RomCloud lit le `latest.json` de la dernière Release GitHub et propose la nouvelle version ; l’installeur est téléchargé, sa signature vérifiée, puis installé (barre de progression seule) et RomCloud redémarre.
- **Caster l’écran sur un Chromecast** (icône de diffusion en haut à droite) : RomCloud cherche les Chromecast du réseau local (Chromecast, Google TV, téléviseurs et boîtiers Android TV avec Chromecast intégré ; enceintes exclues) et y diffuse tout l’écran du PC et son son, jeux compris (moteur intégré ou émulateurs). L’écran choisi dans la fenêtre de partage de Windows est capturé (1280×720 au plus, 30 images/s), encodé en WebM (VP8 + Opus) et servi en HTTP sur le réseau local au lecteur multimédia du Chromecast : comptez quelques secondes de décalage, plutôt pour montrer une partie que pour jouer en regardant le téléviseur. La première fois, Windows demande d’autoriser RomCloud dans le pare-feu : cochez *Réseaux privés*. L’icône reste colorée pendant la diffusion ; un clic permet de l’arrêter.
- **Paramètres** : adresse du serveur et clé d’API, dossier des ROMs (par défaut `Documents\RomCloud`), dossier des BIOS, chemin de `retroarch.exe`, émulateurs, langue, *Masquer les jeux non identifiés* (jeux introuvables dans les bases de jeux ou pas encore scrapés par le serveur, retirés des listes et de la recherche), *Classer les jeux* par nom ou par note.
- **Lancement des jeux** (choix par système dans la fiche du jeu → *Émulateur*) :
  - **Moteur intégré** (choix par défaut) : `romcloud-player.exe`, frontend libretro natif fourni avec l’application (C++, SDL3, OpenGL, inspiré de LibretroDroid). Il utilise les mêmes cœurs que RetroArch, téléchargés au premier lancement depuis le [buildbot libretro](https://buildbot.libretro.com/nightly/windows/x86_64/latest/) : rien à installer. Plein écran, manettes reconnues automatiquement (Xbox, DualShock 3/4, DualSense, Switch…) ou clavier (flèches, Z/X/A/S, Q/W, E/R, Entrée, Maj droite par défaut, **touches modifiables dans les paramètres ou en jeu, dans le menu du moteur, qui ne propose que les boutons de la console**), vibrations, rendu matériel OpenGL (Nintendo 64, PlayStation…), sauvegardes du jeu, état de sauvegarde rapide (F2 / F4), changement de disque. Menu avec *Échap*, le bouton central de la manette ou *Start + Select* : états, **sauvegarder et quitter** (la fiche du jeu propose ensuite **Reprendre** avant **Jouer**, comme sur Android et Android TV), redémarrage, **options du cœur mémorisées par système**, classées par onglet selon les catégories du cœur (*LB* / *RB* ou *Pg. préc.* / *Pg. suiv.*), **filtre d’image mémorisé par système** (pixels nets, lissage net par défaut — pixels nets sans largeurs inégales malgré l’agrandissement non entier —, lissage doux bicubique, lissage bilinéaire, EPX / Scale2x et xBR — contours du pixel art arrondis ou redessinés en diagonale —, AMD FSR 1 — agrandissement suivant les contours puis netteté, pour les jeux 3D (PlayStation, N64…) —, écran cathodique, écran cathodique avec masque RGB, grille LCD ; visible aussitôt derrière le menu), plein écran (aussi *F11*). Données dans `%APPDATA%\RomCloud\libretro` (cœurs, sauvegardes, options, journal `player.log`) ;
  - **Moteur intégré pour la PlayStation 3 et la Wii U** (pas de cœur libretro pour ces consoles) : RomCloud télécharge au premier lancement la dernière version de [RPCS3](https://rpcs3.net/) ou de [Cemu](https://cemu.info/) dans `%APPDATA%\RomCloud\rpcs3` ou `%APPDATA%\RomCloud\cemu` (configuration et sauvegardes y restent) et lance le jeu en plein écran sans leur interface. PS3 : firmware `PS3UPDAT.PUP` à placer dans le dossier des BIOS (ou un sous-dossier, ex. `ps3\`), installé automatiquement ; jeux en ISO, `EBOOT.BIN` ou dossier du disque zippé. Wii U : Cemu en mode portable, `keys.txt` du dossier des BIOS (`wiiu\`, `cemu\` ou racine) copié pour les jeux chiffrés (`.wud`, `.wux`) ; jeux en `.wua`, `.wux`, `.wud`, `.rpx` ou dossier `code/content/meta` zippé ; l’assistant de Cemu s’ouvre au premier lancement pour configurer les manettes. Les manettes et réglages se configurent dans l’émulateur ; pas d’état de sauvegarde rapide ni de sauvegardes en ligne. *Réinitialiser* télécharge la dernière version au lancement suivant ;
  - **RetroArch pour Windows** : le cœur est repris des modèles d’émulateurs du système (ex. `mupen64plus_next` pour la N64) et le jeu est lancé avec `retroarch.exe -L <dossier de RetroArch>\cores\<cœur>_libretro.dll "<jeu>"`. Installez les cœurs dans RetroArch : *Mise à jour en ligne → Télécharger des cœurs* ; le guide *Configurer RetroArch* indique ce qui manque ;
  - **émulateurs les plus connus**, proposés selon le système avec un lien de téléchargement, leur emplacement par défaut et la ligne de commande qui lance le jeu directement (modifiable) :

    | Émulateur | Systèmes | Lancement |
    |---|---|---|
    | DuckStation | PlayStation | `-batch -fullscreen {file}` |
    | PCSX2 | PlayStation 2 | `-batch -fullscreen -- {file}` |
    | RPCS3 | PlayStation 3 | `--no-gui {file}` |
    | PPSSPP | PSP | `--fullscreen {file}` |
    | Vita3K | PS Vita | émulateur ouvert seul (jeux à installer dans Vita3K) |
    | Dolphin | GameCube, Wii | `-b -e {file}` |
    | Cemu | Wii U | `-f -g {file}` |
    | Eden | Switch | indiqué par son **dossier** (`eden.exe` et `eden-cli.exe`) : jeux lancés avec `eden-cli -f -g {file}`, manettes d’`eden.exe` recopiées (section `[Controls]` de `qt-config.ini` dans `sdl2-config.ini`, original gardé en `.romcloud.bak`) ; *Ouvrir Eden* lance `eden.exe` |
    | Ryujinx | Switch | `--fullscreen {file}` |
    | Project64 | Nintendo 64 | `{file}` |
    | melonDS | DS | `{file}` |
    | Azahar | 3DS | `{file}` |
    | mGBA | Game Boy / Color / Advance | `-f {file}` |
    | Snes9x | Super Nintendo | `{file}` |
    | Mesen | NES, SNES, Game Boy, PC Engine, Master System, Game Gear, WonderSwan | `{file}` |
    | ares | Nintendo 64, NES, SNES, Mega Drive, PC Engine… | `--fullscreen {file}` |
    | Flycast, Redream | Dreamcast (Flycast : Naomi, Atomiswave) | `{file}` |
    | Mednafen | Saturn, PlayStation, PC Engine, Lynx, Virtual Boy… | `{file}` |
    | Supermodel | Sega Model 3 | `{file}` |
    | MAME | Arcade | `-rompath {dir} {basename}` |
    | xemu | Xbox | `-full-screen -dvd_path {file}` |
    | Xenia Canary | Xbox 360 | `{file}` |
    | Stella | Atari 2600 | `{file}` |
    | ScummVM | ScummVM | `-p {dir} --auto-detect` |

    RomCloud cherche les exécutables dans les dossiers habituels (`Program Files`, `%LOCALAPPDATA%\Programs`, Bureau, Téléchargements, `scoop\apps`, `C:\Emulators`, `C:\Emulateurs`, `D:\Games`…) ; sinon, *Indiquer l’emplacement…*. Si l’émulateur est absent au lancement, RomCloud propose de le télécharger ou de le localiser. Quand un lancement direct n’est pas possible, RomCloud ouvre l’émulateur seul et indique le fichier du jeu à charger (copie du chemin, affichage dans l’Explorateur). Le bouton *Ouvrir <émulateur>* lance l’émulateur sans jeu (configuration, manettes…). **Eden** : *Vérifier les mises à jour* compare la version installée (lue dans `eden.exe`) à la dernière publiée sur [git.eden-emu.dev](https://git.eden-emu.dev/eden-emu/eden/releases), puis *Mettre à jour* télécharge l’archive Windows de la même variante (MSVC, clang PGO ou GCC, reconnue dans l’exécutable ; PGO, recommandée par Eden, pour une première installation dans `%LOCALAPPDATA%\Programs\Eden`) et la décompresse à la place de l’ancienne (configuration et jeux gardés ; Eden doit être fermé). La liste complète est dans *Paramètres → Émulateurs* ;
  - **commande personnalisée** par système, `{file}` étant le chemin du jeu, ex. `"C:\Emulateurs\Dolphin\Dolphin.exe" -b -e "{file}"` ;
  - **programme Windows par défaut** associé au type de fichier.

### Premier lancement de l’app

Saisissez l’adresse du serveur (`http://<ip-du-serveur>:8080`) et la clé d’API, **Tester la connexion**, **Enregistrer**, puis autorisez **l’accès à tous les fichiers** (voir [Fonctionnement](#fonctionnement)).

## 1. Serveur (`server/`) — développement et configuration

Sans Docker, prérequis : **Node.js ≥ 22.13** (utilise le module SQLite intégré `node:sqlite`, aucune dépendance native).

```bash
cd server
cp .env.example .env      # puis renseigner API_KEY, ADMIN_KEY et, si possible, ScreenScraper
npm install
npm start                 # http://<ip-du-pc>:8080
```

Avec Docker : voir [Installation](#serveur--docker-hub). Pour compiler l’image vous-même : `docker build -t romcloud-server server`.

**Publication automatique** : le workflow [`.github/workflows/server-docker.yml`](.github/workflows/server-docker.yml) teste le serveur puis publie l’image sur Docker Hub — `latest` à chaque push sur `main` touchant `server/`, `1.2.3` / `1.2` / `1` sur un tag `v1.2.3`. Secrets requis : `DOCKERHUB_USERNAME` et `DOCKERHUB_TOKEN` (jeton d’accès créé dans Docker Hub → *Account settings → Personal access tokens*, droits *Read & Write*).

### Utilisation de l'interface web

L’interface est disponible en **français** et en **anglais** : menu de langue en haut à droite (langue du navigateur par défaut, choix mémorisé). Les messages de l’API (erreurs, libellés des tâches, erreurs de scraping) sont renvoyés dans la langue demandée par le client (`Accept-Language`).


1. **Ajouter un système** → onglet *Catalogue Daijishou* : cochez les plateformes (SNES, PSX, GBA…) et importez-les. Le catalogue est complété par des plateformes fournies par RomCloud, marquées « RomCloud » (dossier [`server/platforms/`](server/platforms/), même format) : **Amstrad GX4000** (cartouches `.cpr`, cœur RetroArch `cap32`), distincte de l’Amstrad CPC. Elles arrivent avec leurs émulateurs, le filtre d'extensions, le nom Libretro et l'identifiant ScreenScraper. L'onglet *Personnalisé* permet de créer un système à la main.
2. **Ajouter des ROMs** : glisser-déposer dans la page (case « scraper automatiquement » cochée par défaut), ou copier les fichiers dans `data/roms/<dossier du système>/` puis cliquer sur **Rescanner**.
3. **Scraper** : bouton *Scraper les jeux* (toute la liste, en tâche de fond) ou depuis la fiche d'un jeu (ScreenScraper / Libretro au choix). Titre, description, date, genre, éditeur, jaquette et capture sont enregistrés sur le serveur et renvoyés à l'application.
4. **Image du système** : dans *Réglages* du système, *Choisir une image* (PNG, JPEG, WebP ou GIF, 10 Mo max.) — logo ou photo de la console, affichée sur la carte du système dans l’application.
5. **Plateformes d’un système** : dans *Réglages → Proposé dans les applications*, décochez Android, Android TV ou Windows pour qu’un système (et ses jeux, recherche comprise) n’y apparaisse pas — ex. PlayStation 3 et Wii U : Windows seulement (moteur intégré avec RPCS3 et Cemu). Les applications envoient leur plateforme dans l’en-tête `X-RomCloud-Platform` ; toutes cochées = proposé partout.
6. **Jeux en plusieurs fichiers** : les **disques** d’un même jeu (« (Disc 2) », « (Disk 2 of 3) », « [CD2] », « - Disc B »…), ses **mises à jour** et ses **DLC** (« [UPD] », « (Update v1.2) », « [DLC] », identifiants Wii U 0005000E… / 0005000C…, paquets Sony `.pkg` — PS Vita, PS3 — dont le type (jeu, patch, DLC) est lu dans l’en-tête du paquet, ou à défaut dans l’identifiant de contenu « EP0001-PCSB00598_00-DLC0100000000000 » / « …_patch_01.02 », rattachés au jeu du même numéro de série ; ou titre du jeu suivi d’un complément — pas d’un numéro de suite) sont regroupés sous le jeu à chaque scan : une seule carte, la taille totale et la liste des fichiers dans sa fiche. Dans la fiche d’un jeu, *Détacher* en fait un jeu à part entière, et un fichier seul peut être *rattaché* à un autre jeu (comme disque, mise à jour ou DLC) ; ces choix manuels sont gardés aux scans suivants (*Revenir au regroupement automatique* pour les annuler). Supprimer un jeu supprime aussi ses parties.
7. **Émulateurs d’un système** : dans *Réglages → Émulateurs*, retirez les modèles inutiles ou ajoutez ceux d’un autre système (du serveur ou du catalogue Daijishou). Utile pour un système créé à la main (onglet *Personnalisé*).
8. **Doublons** (bouton dans la vue d’un système) : liste les **fichiers identiques** (même contenu sous plusieurs noms, vérifié par MD5 — les copies sont pré-cochées) et les **jeux en plusieurs versions** (régions, révisions : à cocher soi-même). Dans chaque groupe, RomCloud propose le fichier à conserver : sans marque de copie « (2) », pas de démo/bêta/dump défectueux, région préférée (`SCRAPE_REGIONS`), révision la plus récente, puis déjà scrapé. Les fichiers de plus de `HASH_MAX_MB` ne sont comparés que par leur titre.
9. **BIOS** (bouton dans la vue d’un système) : liste les BIOS **attendus par les cœurs RetroArch** des émulateurs du système (fiches [libretro-core-info](https://github.com/libretro/libretro-core-info) : chemin, requis ou facultatif, MD5 de référence) et les fichiers envoyés, avec contrôle des empreintes (MD5 et SHA1). Glissez les fichiers via *Ajouter des BIOS* : un fichier nommé comme un BIOS attendu est rangé dans le bon sous-dossier (ex. `dc_boot.bin` → `dc/dc_boot.bin`), ou utilisez *Envoyer…* sur la ligne d’un BIOS. Certains cœurs attendent un **dossier** (PCSX2 : `pcsx2/bios` et `pcsx2/resources`) : *Envoyer un .zip…* sur sa ligne envoie une archive de son contenu, extraite sur le serveur (chaque fichier devient un BIOS du dossier, téléchargé par les applications). Un .zip envoyé auparavant à la place d’un dossier est extrait automatiquement. Les MD5 de référence viennent de ces fiches, complétés par [`server/bios-hashes.json`](server/bios-hashes.json) (MD5 ou SHA1 par nom de fichier, dont les BIOS PlayStation `scph5500/5501/5502` v3.0 dont Beetle PSX contrôle le SHA1), complétable sans reconstruire l’image par un fichier `DATA_DIR/bios-hashes.json` de même format (`{ "scph5501.bin": ["490f66…", "0555c6…"] }`). **Un BIOS dont l’empreinte est connue est refusé à l’envoi s’il n’y correspond pas** ; un BIOS sans empreinte connue est accepté. Les fichiers sont stockés dans `DATA_DIR/bios/<système>/`. Les applications proposent ensuite de les télécharger avec le jeu. **Clés, firmwares et autres fichiers** des émulateurs hors RetroArch sont listés au même endroit, avec leur type (BIOS, clés, firmware, données) et les émulateurs concernés : Switch `switch/prod.keys`, `switch/title.keys` et `switch/firmware.zip` (.zip des fichiers .nca, gardé tel quel) pour Eden, Ryujinx et Citron ; 3DS `3ds/aes_keys.txt` (Azahar) ; Wii U `wiiu/keys.txt` (Cemu) ; PS3 `ps3/PS3UPDAT.PUP` (RPCS3, aPS3e) ; PS Vita `vita/PSVUPDAT.PUP` et `vita/PSP2UPDAT.PUP` (polices) pour Vita3K ; Xbox `xbox/mcpx_1.0.bin`, `xbox/Complex_4627.bin` et `xbox/xbox_hdd.qcow2` (xemu). Les applications les téléchargent dans leur dossier BIOS comme les BIOS (et de nouveau quand ils changent sur le serveur) ; RomCloud pour Windows installe les clés de la Switch là où Eden et Ryujinx les attendent (dossier de l’utilisateur ou mode portable) à leur lancement, et le firmware PS3 dans RPCS3. Sous Android, les émulateurs externes importent eux-mêmes ces fichiers avec le sélecteur de fichiers : RomCloud recopie les clés et le firmware de la Switch dans **Téléchargements › RomCloud › Switch** (`prod.keys`, `title.keys`, `firmware.zip` — un firmware envoyé autrefois en dossier est recompressé, Eden n’acceptant qu’un .zip), indiqué dans la fiche des jeux Switch, à choisir depuis les paramètres d’Eden. **Dernières versions sur Internet** : pour les fichiers diffusés librement, la fenêtre indique la dernière version publiée, celle du serveur, et propose *Télécharger* ou *Mettre à jour* (taille contrôlée) — firmwares PS3 et PS Vita depuis les serveurs de mise à jour officiels de Sony, base de jeux de PCSX2 (`pcsx2/resources/GameIndex.yaml`) depuis GitHub. Les clés et les BIOS sous droits d’auteur n’ont pas de source : ils doivent être extraits de votre propre console.
10. **Émulateurs Android** (bouton en haut de l’écran) : liste les émulateurs utilisés par les modèles de vos systèmes (paquet Android, systèmes, lien Play Store) et les **APK** envoyés sur le serveur. Le paquet et la version sont lus dans chaque APK ; un seul APK par paquet (une nouvelle version remplace la précédente). Les APK sont stockés dans `DATA_DIR/apks/`. Les applications Android proposent ensuite d’installer l’émulateur manquant depuis le serveur RomCloud ou depuis le Play Store.
11. Cliquer sur un jeu permet d'éditer ses informations, remplacer ses images, le télécharger ou le supprimer.
12. **Utilisateurs** (bouton en haut de l’écran) : profils des joueurs créés depuis les applications (ou ici, *Créer un utilisateur*). Pour chaque profil : renommer, nouveau mot de passe (les appareils connectés sont déconnectés), désactiver, supprimer ; **appareils connectés** (nom, plateforme, version de l’application, adresse IP, dernière activité, connexion par mot de passe ou QR code — *Déconnecter* ferme une session), **temps de jeu par jeu**, **sauvegardes en ligne** et **erreurs** signalées par ses appareils. Onglets *Connexions* (toutes les connexions, réussies ou non, avec le motif d’un échec : mot de passe incorrect, utilisateur inconnu, compte désactivé, trop de tentatives) et *Erreurs des applications* (lancement impossible, téléchargement échoué, plantage de l’émulateur intégré ou de l’application, avec les détails). Réservé à la clé d’administration. Onglet **Téléchargements** : journal de qui télécharge quoi sur le serveur (jeux, BIOS / clés / firmwares, APK d’émulateurs) — date, profil connecté ou anonyme, appareil, plateforme, version de l’application, IP, octets envoyés, reprise d’un téléchargement interrompu — et statistiques sur 7, 30 ou 90 jours (ou depuis le début), par type de fichier : totaux, par profil, par appareil, fichiers les plus téléchargés et volume par jour (un clic sur un profil ou un appareil filtre la page). Les applications joignent à leurs téléchargements le nom de l’appareil, leur version et la session du profil connecté. Onglet **Espace disque** : place utilisée par RomCloud et place libre sur les disques des données et des ROMs, en arborescence dépliable (chevrons) : ROMs par système, sauvegardes et historique des états par profil, BIOS par système, images, APK, caches (fiches des cœurs, LaunchBox), base de données et tout autre dossier du répertoire de données (calcul gardé une minute, *Recalculer*).

#### Profils des joueurs

Une icône de profil, à côté des paramètres, permet de **créer un compte** ou de **se connecter** (Windows, Android). **Android TV** propose la connexion par mot de passe ou un **QR code** : dans l’application du téléphone déjà connecté, *Profil › Connecter une TV › Scanner le QR code* (lecteur des services Google Play, ou *Saisir le code* affiché sous le QR code), puis *Connecter* : la TV est connectée au même profil. Le profil garde :

- le **temps de jeu par jeu**, affiché dans la bannière de la liste des jeux et dans la fiche du jeu (émulateur intégré : temps de la partie affichée, menu fermé ; émulateurs externes : du lancement au retour dans RomCloud ; parties de moins de 10 s ignorées ; envoyé plus tard si le serveur est injoignable) ;
- les **sauvegardes en ligne de l’émulateur intégré** (état de la partie et mémoire du jeu, par cœur) : envoyées en quittant le jeu, récupérées avant de jouer si elles sont plus récentes — une partie commencée sur la TV continue sur le téléphone ou le PC (même cœur ; un état d’une autre version du cœur peut être refusé). La fiche du jeu indique la sauvegarde en ligne et propose *Reprendre*.
- l’**historique des états** de chaque jeu (émulateur intégré, Android, Android TV et Windows) : chaque *Sauvegarder l’état* (F2 sous Windows) et *Sauvegarder et quitter* ajoute un état daté avec une **miniature** de l’écran du jeu ; les **10 derniers par jeu et par appareil** sont gardés, plus les états **épinglés** (jamais supprimés automatiquement). *Charger l’état* (menu du jeu) et *États sauvegardés* (fiche du jeu, pour jouer à partir de l’un d’eux) listent ceux de l’appareil et ceux du profil en ligne, avec la **date, l’heure et l’appareil** d’origine : épingler, envoyer en ligne, supprimer (de l’appareil et en ligne). **Envoi automatique** réglable sur chaque appareil (*Paramètres › Envoyer les états en ligne automatiquement*, activé par défaut) : sous Android dès la sauvegarde, sous Windows à la fin de la partie (les états récents des autres appareils sont téléchargés avant la partie pour le menu du moteur) ; sinon bouton *Envoyer en ligne* sur chaque état. Hors ligne, l’envoi attend le retour du serveur.

Mots de passe hachés (scrypt), jetons de session aléatoires (seule leur empreinte est stockée), 10 tentatives échouées par quart d’heure et par nom d’utilisateur. Sauvegardes dans `DATA_DIR/saves/<utilisateur>/<jeu>/`, historique des états dans `DATA_DIR/states/<utilisateur>/<jeu>/`. Les applications **compressent** sauvegardes et états (gzip) avant de les envoyer, quand cela les réduit (états des consoles 8/16 bits, PlayStation, Dreamcast… : souvent 2 à 6 fois plus petits ; les états déjà compressés, PPSSPP, sont envoyés tels quels), et les décompressent à la réception : moins de place sur le serveur et moins de données échangées. Le serveur les garde tels quels ; les fichiers envoyés par une version précédente restent non compressés.

### Configuration (`.env`)

| Variable | Rôle |
|---|---|
| `PORT` | Port HTTP (8080 par défaut) |
| `DATA_DIR` | Base SQLite, ROMs et médias (`./data`) |
| `ROMS_DIR` | Dossier des ROMs, relatif à `DATA_DIR` ou absolu (ex. un NAS monté) |
| `DEFAULT_LANGUAGE` | Langue par défaut du serveur (`fr` ou `en`, `en` par défaut) : journal et clients qui n’envoient pas d’en-tête `Accept-Language` |
| `API_KEY` | Clé des applications (Windows, Android, Android TV) : à saisir dans leurs réglages. Avec `ADMIN_KEY`, elle ne donne accès qu'**en lecture** (consulter, télécharger) ; sans `ADMIN_KEY`, elle donne un accès complet. **À définir** si le serveur est accessible au-delà de votre réseau local. |
| `ADMIN_KEY` | Clé de l'**interface web** d'administration : accès complet (envoi, suppression, scraping, réglages). Recommandée, différente de `API_KEY` : une clé d'application divulguée ne permet alors pas de modifier le serveur. |
| `SCREENSCRAPER_DEV_ID` / `_DEV_PASSWORD` | Identifiants **développeur** ScreenScraper (à demander sur le forum screenscraper.fr). Sans eux, seul Libretro est utilisé. |
| `SCREENSCRAPER_USER` / `_PASSWORD` | Votre compte ScreenScraper (facultatif, augmente les quotas) |
| `SCRAPE_LANGUAGES` / `SCRAPE_REGIONS` | Priorités de langue/région (`fr,en` / `fr,eu,wor,us,ss,jp`) |
| `SCRAPE_CONCURRENCY` | Jeux scrapés **en parallèle** par une tâche (1 à 16, `1` par défaut). Avec ScreenScraper, borné par le nombre de requêtes simultanées de votre compte (`maxthreads`, 1 sans compte) ; une requête refusée pour ce motif (429) est réessayée. |

**Scraping** : ScreenScraper est interrogé par CRC32/MD5 + nom + taille (hachage jusqu'à `HASH_MAX_MB`, 1 Go par défaut). En cas d'absence ou d'image manquante, repli sur [thumbnails.libretro.com](https://thumbnails.libretro.com) (correspondance exacte du nom No-Intro, puis approximative). Les tâches s'exécutent une par une (jeux d'une tâche scrapés en parallèle selon `SCRAPE_CONCURRENCY`) et s'arrêtent si le quota ScreenScraper est atteint. **Informations détaillées** (section *Informations* de la fiche du jeu sous Windows, Android et Android TV) : autres titres et dates de sortie par région, régions et langues, série, modes de jeu, thèmes, classifications d’âge, numéro de série, version de la ROM (révision, beta, proto, disque…), résolution et rotation (arcade), vibrations et stick analogique, CRC32 / MD5, liens ScreenScraper et Wikipedia. Elles viennent de ScreenScraper, des fiches [libretro-database](https://github.com/libretro/libretro-database/tree/master/metadat) (par CRC, nom No-Intro / Redump ou numéro de série : développeur, éditeur, genre, date, joueurs, ESRB, série…) et du nom du fichier ; relancez le scraping des jeux déjà scrapés pour les obtenir. Jeux zippés : la ROM contenue dans l’archive (nom, CRC) sert à la recherche. Jeux d’arcade renommés (« fatal fury.zip » au lieu de « fatfury1.zip », Neo Geo, CPS, MAME…) : le jeu est retrouvé d’après les CRC des fichiers de l’archive dans la DAT de FinalBurn Neo, puis cherché sous son nom court. Jeu introuvable ailleurs : son article **Wikipedia** est recherché (titre du fichier, ou titre qui commence par lui sans être une suite, l’article devant alors citer la plateforme) ; il donne le titre officiel, le résumé, l’image de l’article comme jaquette, et via **Wikidata** le développeur, l’éditeur, le genre, les modes de jeu et la date de sortie ; libretro est ensuite réinterrogé avec le titre officiel pour les images.

**Sites de jeux rétro** : chaque jeu est aussi cherché par son titre dans **[LaunchBox Games DB](https://gamesdb.launchbox-app.com)** (base communautaire gratuite, sans compte, ~140 000 jeux). Son export complet (~110 Mo) est téléchargé au premier scraping puis une fois par semaine, et importé dans `data/launchbox/launchbox.db` (~200 Mo, plateformes rétro seulement). Il complète ce qui manque : résumé (en anglais, à défaut d’un résumé dans une langue préférée), développeur, éditeur, genres, date, nombre de joueurs, jeu coopératif, classification ESRB, note de la communauté, titres alternatifs, jaquette, capture et écran-titre, liens vers la fiche LaunchBox et la vidéo du jeu. Quand le jeu a un article Wikipedia, **Wikidata** ajoute sa série, ses modes de jeu et des liens vers ses fiches sur les sites de jeux rétro : MobyGames, GameFAQs, IGDB, RetroAchievements, HowLongToBeat, Killer List of Videogames, Guardiana, SMS Power!, Sega8bit, UVL, Kultboy, My Abandonware, VGMdb, StrategyWiki et speedrun.com.

**Note en étoiles** : chaque scraping combine les notes trouvées — ScreenScraper (sur 20), la communauté LaunchBox (sur 5, au moins 3 votes) et la presse relevée par Wikidata (Metacritic, GameRankings, Famitsu, IGN… : moyenne par site puis entre les sites, sur 100) — en une **note de 1 à 5 étoiles entières**. Chaque source a son barème, calé sur sa répartition réelle pour que les étoiles aient le même sens partout (5 étoiles pour les ~15 % meilleurs jeux, 4 pour les ~25 % suivants…) : ScreenScraper 17/20, LaunchBox 4,2/5 et presse 85/100 pour 5 étoiles ; 14, 3,7 et 75 pour 4 ; 11, 3,0 et 60 pour 3 ; 8, 2,4 et 45 pour 2 (barèmes dans `server/src/scraper/rating.js`). La note du jeu est la moyenne des étoiles des sources (une demi-étoile compte pour une entière). La fiche du jeu détaille la note de chaque source dans ses *Informations*. Une note saisie dans l’interface web est arrondie à l’étoile. Jeux scrapés avec une version antérieure : *Scraper les jeux* en répondant *Annuler* à « seulement les jeux non scrapés ? » pour recalculer leurs notes.

**Filtres** (Windows, Android et Android TV, liste des jeux d’un système) : en plus de *Tous / Téléchargés / À télécharger*, le bouton **Filtres** propose genre, décennie, nombre de joueurs (solo, 2 et +, 4 et +, coopératif), région, note minimale et éditeur, d’après les informations des jeux de la liste ; les critères se cumulent.

### API

Toutes les routes (sauf `/api/info`) exigent `Authorization: Bearer <clé>` (ou `?key=`) quand une clé est définie : `API_KEY` pour les lectures (GET), `ADMIN_KEY` (ou `API_KEY` seule) pour les modifications ; une clé en lecture seule reçoit `403` sur une modification. Exceptions : `/api/account/*` (profil du joueur) accepte `API_KEY` même pour enregistrer ; `/api/users` et `/api/logs` exigent la clé d’administration même en lecture.

| Méthode | Route | Description |
|---|---|---|
| GET | `/api/info` | Nom, version, clé requise ? |
| GET | `/api/systems` | Systèmes (+ émulateurs, nb de jeux, taille, `platforms`) ; avec l’en-tête `X-RomCloud-Platform` (`android`, `androidtv`, `windows`), seulement ceux proposés à cette plateforme |
| POST/PUT/DELETE | `/api/systems[/:id]` | Créer / modifier (`{ "platforms": ["windows"] }` : proposé seulement sous Windows, `[]` : partout) / supprimer (`?deleteFiles=1`) |
| GET | `/api/daijishou/platforms` | Catalogue Daijishou |
| POST | `/api/daijishou/import` | `{ "filenames": ["SuperNintendoEntertainmentSystem.json"] }` |
| GET/PUT/DELETE | `/api/systems/:id/image` | Image du système (PUT : corps binaire, `Content-Type: image/png`…) |
| GET | `/api/systems/:id/duplicates` | Groupes `identical` / `similar` avec le fichier proposé (`keepId`) |
| POST | `/api/systems/:id/duplicates/delete` | `{ "ids": [12, 15] }` : supprime fichiers, fiches et images |
| POST | `/api/systems/:id/scan` · `/api/scan` | Synchroniser avec les dossiers |
| GET | `/api/systems/:id/bios` | BIOS présents (`files`, avec `md5`, `sha1` et `hashStatus`) et attendus par les cœurs (`expected`) ; `?catalog=0` : fichiers seuls |
| POST | `/api/systems/:id/bios` | Envoi multipart `files[]` (champ `path` facultatif pour imposer le chemin, ex. `dc/dc_boot.bin`) |
| GET/DELETE | `/api/bios/:id[/file]` | Téléchargement (Range) / suppression d’un BIOS |
| GET | `/api/apks` · `/api/apks/emulators` | APK d’émulateurs (paquet, version, taille) · paquets utilisés par les systèmes, avec l’APK disponible |
| POST | `/api/apks` | Envoi multipart `files[]` (paquet et version lus dans l’APK) |
| PUT/DELETE | `/api/apks/:id` | Renommer (`{ "label": "PPSSPP" }`) / supprimer |
| GET | `/api/apks/:id/file` | Téléchargement de l’APK (Range) |
| GET | `/api/search?q=mots&limit=300` | Recherche dans tous les systèmes (chaque mot dans le titre ou le nom de fichier) ; limitée aux systèmes de la plateforme (`X-RomCloud-Platform`) |
| GET | `/api/systems/:id/games` | Liste des jeux (`?q=` recherche) ; disques, mises à jour et DLC dans `parts` de leur jeu (`totalSize` : taille de l’ensemble) |
| POST | `/api/systems/:id/games` | Envoi multipart `files[]` (`?scrape=1`) |
| GET/PUT/DELETE | `/api/games/:id` | Fiche d'un jeu |
| PUT | `/api/games/:id/group` | Rattache le fichier à un jeu `{ "parentId": 12, "kind": "disc\|update\|dlc" }`, en fait un jeu à part entière `{ "parentId": null }` ou le rend au regroupement automatique `{ "auto": true }` |
| GET | `/api/games/:id/file` | Téléchargement de la ROM (Range / reprise) |
| GET/PUT | `/api/games/:id/media/boxart\|screenshot` | Images |
| POST | `/api/games/:id/scrape` | `{ "source": "auto\|screenscraper\|libretro" }` |
| POST | `/api/systems/:id/scrape` | Tâche de fond `{ "onlyMissing": true }` |
| GET/DELETE | `/api/jobs[/:id]` | Suivi / annulation des tâches |
| POST | `/api/account/register` · `/api/account/login` | `{ "username", "password" }` → `{ token, user }` (appareil : en-têtes `X-RomCloud-Device`, `X-RomCloud-Platform`, `X-RomCloud-Version`) |
| POST · GET | `/api/account/logout` · `/api/account/me` | Session : en-tête `X-RomCloud-Session: <token>` ; `401` `errors.signedOut` si elle est fermée |
| POST · GET | `/api/account/pair` · `/api/account/pair/:code?secret=` | TV : demande de connexion (`code`, `secret`) puis attente (`pending`, `approved` + jeton, `expired`) |
| GET · POST | `/api/account/pair/:code` · `/api/account/pair/:code/approve` | Téléphone connecté : appareil derrière le code, puis validation |
| GET · POST | `/api/account/playtime` | Temps de jeu par jeu · `{ "gameId", "seconds" }` ajoute une partie |
| GET | `/api/account/saves?gameId=` | Sauvegardes en ligne (`core`, `kind` : `state` ou `sram`, `savedAt`, appareil) |
| GET/PUT/DELETE | `/api/account/saves/:gameId/:core/:kind` | Fichier de sauvegarde (PUT : corps binaire, `X-Saved-At` = date du fichier en ms ; compressé par l’application : `Content-Encoding: gzip` et `X-Uncompressed-Size`, gardé tel quel et renvoyé compressé avec `Accept-Encoding: gzip`, décompressé sinon) |
| GET | `/api/account/states?gameId=` | Historique des états (`id`, `core`, `createdAt`, `device`, `platform`, `pinned`, `thumbnail`) |
| PUT | `/api/account/states/:gameId/:core/:id` | État (corps binaire, compressé comme les sauvegardes, `X-Created-At` = date en ms, `X-Pinned: 1`) ; 10 gardés par jeu et par appareil, hors épinglés |
| GET · PUT | `/api/account/states/:id` · `/api/account/states/:id/thumbnail` | Fichier de l’état · miniature (JPEG ou PNG) |
| PATCH · DELETE | `/api/account/states/:id` | `{ "pinned": true }` épingle · supprime l’état |
| POST | `/api/account/errors` | `{ "message", "context", "details" }` : erreur d’une application (avec ou sans session) |
| GET/POST · GET/PUT/DELETE | `/api/users` · `/api/users/:id` | Administration des profils (détail : sessions, connexions, temps de jeu, sauvegardes, erreurs) |
| DELETE | `/api/users/:id/sessions/:sessionId` | Déconnecte un appareil |
| GET · GET/DELETE | `/api/logs/logins` · `/api/logs/errors` | Journal des connexions · des erreurs (`DELETE` : effacer) |
| GET | `/api/logs/disk` | Espace disque en arborescence (`tree` : `id`, `label`, `size`, `files`, `children`) et disques (`total`, `free`) ; `?refresh=1` : recalculé |
| GET · GET · DELETE | `/api/logs/downloads` · `/api/logs/downloads/stats` · `/api/logs/downloads` | Journal des téléchargements (ROM, BIOS, APK) et statistiques (`totals`, `byKind`, `byUser`, `byDevice`, `topItems`, `byDay`) ; filtres `days`, `kind`, `userId` (`anonymous`), `device` · effacer |

Tests : `npm test`.

## 2. Application Android (`android/`)

Kotlin + Jetpack Compose, minSdk 26 (Android 8), targetSdk 36 (Android 16). Trois modules Gradle :

| Module | Contenu |
|---|---|
| `core` | Code partagé : API du serveur, cache hors ligne, téléchargements (service de premier plan), lancement des émulateurs (modèles `am start`, chemins SAF RetroArch…), réglages, ViewModels, fenêtres communes |
| `app` | Application téléphone / tablette (`com.romcloud.app`) |
| `tv` | Application Android TV (`com.romcloud.app.tv`), interface [tv-material](https://developer.android.com/jetpack/androidx/releases/tv) pour la télécommande |

L’application Windows est dans [`desktop-tauri/`](desktop-tauri/) (Tauri : processus principal en Rust, interface WebView2 de [`desktop/src/renderer/`](desktop/src/renderer/)) : voir [son README](desktop-tauri/README.md) pour la compiler (`npm run build` produit l’installeur), `cargo test` dans `desktop-tauri/src-tauri` et `npm test` dans `desktop/` (tests de l’interface). Le moteur intégré est dans [`desktop/native/player/`](desktop/native/player/) : `npm run player` (dans `desktop/`) le compile avec CMake (Visual Studio 2022 ou LLVM-MinGW ; SDL3 est téléchargé et lié statiquement) dans `desktop/native/player/build/romcloud-player.exe`, inclus ensuite dans l’installeur. Essai sans interface : `romcloud-player.exe --core <cœur.dll> --rom <jeu> --test-frames 300 --screenshot image.bmp`. Le workflow [`.github/workflows/desktop-tauri.yml`](.github/workflows/desktop-tauri.yml) joint l’installeur et `latest.json` (mise à jour automatique signée) à chaque Release.

### Compiler l'APK

Ouvrir `android/` dans Android Studio, ou en ligne de commande (JDK 17 à 21) :

```bash
cd android
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk (téléphone)
./gradlew :tv:assembleRelease    # tv/build/outputs/apk/release/tv-release.apk (Android TV)
./gradlew :core:testDebugUnitTest
```

L’app cible Android 16 (API 36) et s’installe à partir d’Android 8 (API 26).

**Signature release** : lue depuis les variables d’environnement `ROMCLOUD_KEYSTORE_FILE`, `ROMCLOUD_KEYSTORE_PASSWORD`, `ROMCLOUD_KEY_ALIAS`, `ROMCLOUD_KEY_PASSWORD`, ou depuis `android/keystore.properties` (non versionné) :

```properties
storeFile=C\:/chemin/vers/romcloud-release.jks
storePassword=...
keyAlias=romcloud
keyPassword=...
```

Sans clé configurée, l’APK release est signé avec la clé de debug.

### Compilation automatique (GitHub Actions)

Le workflow [`.github/workflows/android.yml`](.github/workflows/android.yml) :

- à chaque push sur `main` touchant `android/` : tests unitaires + APK release signés (téléphone et TV), téléchargeable dans l’onglet **Actions** (artifact `RomCloud-0.0.<n°>-<commit>`) ;
- sur un tag `v*` : même chose + **Release GitHub** avec les deux APK (`RomCloud-<version>.apk` et `RomCloud-TV-<version>.apk`) :

  ```bash
  git tag v1.1.0 && git push origin v1.1.0
  ```

Secrets à définir dans *Settings → Secrets and variables → Actions* : `ROMCLOUD_KEYSTORE_BASE64` (keystore encodé en base64), `ROMCLOUD_KEYSTORE_PASSWORD`, `ROMCLOUD_KEY_ALIAS`, `ROMCLOUD_KEY_PASSWORD`. Le `versionCode` est le numéro d’exécution du workflow, le `versionName` celui du tag.

Les Releases sont celles qu’Obtainium surveille (voir [Installation](#application-android--obtainium-recommandé)).

### Fonctionnement

- **Premier lancement** : saisir l'adresse du serveur (`http://192.168.x.x:8080`) et la clé d'API, *Tester la connexion*, puis autoriser **l'accès à tous les fichiers** : les ROMs sont écrites dans un dossier partagé (`/storage/emulated/0/RomCloud/<système>/` par défaut) pour que les émulateurs puissent les lire.
- **Systèmes** : cartes avec l’image configurée sur le serveur.
- **Recherche globale** (loupe sur l’écran des systèmes, bouton *Rechercher* sur TV) : cherche dans les jeux de **tous les systèmes** ; résultats en cartes avec l’indicateur de téléchargement et le **logo de la console en bas à droite** (image du système, sinon son nom court). Un résultat ouvre la fiche du jeu. Hors ligne, la recherche porte sur les listes déjà ouvertes.
- **Liste des jeux** : deux affichages au choix (bouton en haut à droite, choix mémorisé) :
  - **liste** : jaquette, titre, année, genre, taille ;
  - **carrousel** façon Netflix : bannière du jeu mis en avant (capture en fond, boutons *Jouer* / *Télécharger* et *Infos*), puis rangées défilant horizontalement — *Téléchargés*, *Ajoutés récemment* (au-delà de 20 jeux), puis une rangée par genre. Chaque carte porte le bouton **ⓘ** (fiche du jeu et choix de l’émulateur). Pendant une recherche, les résultats s’affichent en grille.

  Les filtres *Tous / Téléchargés / À télécharger* et la recherche s’appliquent aux deux affichages. Indicateur de chaque jeu :
  - ☁ gris : non téléchargé
  - cercle de progression : téléchargement en cours (pourcentage)
  - ✓ vert : présent sur l'appareil
  - ⚠ rouge : échec du dernier téléchargement
- **Appui sur un jeu** : s'il n'est pas téléchargé, une fenêtre demande de le télécharger d'abord (option « Lancer le jeu une fois téléchargé ») ; s'il l'est, il se lance directement. **Appui long / ⓘ** : fiche détaillée (description, capture, choix de l'émulateur, suppression locale).
- Téléchargements en arrière-plan avec notification, reprise automatique d'un fichier partiel (`.part`).
- **BIOS** : si le serveur a des BIOS pour le système, la fenêtre de téléchargement propose *Télécharger aussi les BIOS manquants* (cochée par défaut). Pour un jeu déjà présent, RomCloud propose de télécharger les BIOS absents avant de jouer (ou *Jouer quand même*), et la fiche du jeu indique leur état avec un bouton *Télécharger les BIOS*. Ils sont copiés dans le **dossier des BIOS** (*Paramètres*), sous-dossiers compris : choisissez le dossier `system` de RetroArch (raccourcis *RetroArch (Play Store)* → `Android/media/com.retroarch.aarch64/RetroArch/system`, *RetroArch (site)* → `RetroArch/system`, ou `RomCloud/bios`). Sur Windows, le dossier par défaut est `system` à côté de `retroarch.exe`.
- **Émulateur non installé** (au lancement, ou bouton *Installer* de la fiche du jeu) : pour les émulateurs qui publient leur APK, RomCloud lit leur dernière version sur leur gestionnaire de versions et propose *Télécharger la dernière version* — Eden (standard, Legacy, Optimized) sur `git.eden-emu.dev`, et sur GitHub Azahar, melonDS, Flycast, Vita3K, ARMSX2 (variante adaptée à la version d’Android), Cemu, aPS3e, MAME4droid et SkyEmu ; l’APK téléchargé doit installer le paquet attendu. Sinon, si le serveur a l’APK de cet émulateur, RomCloud propose *Installer depuis RomCloud*. Dans les deux cas : téléchargement avec progression puis installateur d’Android, en plus du *Play Store*. La première fois, Android demande d’autoriser RomCloud à installer des applications (*Autoriser cette source*) ; l’installation reprend au retour dans l’application.
- **Caster l’écran sur un Chromecast** (téléphone : icône de diffusion sur les écrans des systèmes et des jeux, entrée *Caster l’écran* du menu de l’émulateur intégré) : Android ne permet pas à une application de recopier elle-même l’écran sur un Chromecast ; RomCloud ouvre l’écran de diffusion d’Android (*Caster* / *Diffuser l’écran*), Smart View sur Samsung ou Google Home (*Caster mon écran*), selon ce que propose l’appareil. Tout l’écran et le son sont recopiés, jeux compris. L’icône indique la diffusion en cours quand Android la signale.
- **Hors ligne** : les listes sont mises en cache ; les jeux déjà téléchargés restent jouables.
- **Filtre d’image de l’émulateur intégré** (entrée *Image* de son menu, liste des filtres avec leur description, mémorisé par système ; le jeu reprend aussitôt pour voir le résultat) : pixels nets, lissage net (par défaut : pixels nets sans largeurs inégales ni flou, malgré la résolution de l’écran rarement multiple de celle du jeu), lissage doux, lissage des contours (comme Lemuroid), contours nets, contours doux, écran cathodique ou LCD. Filtres de LibretroDroid (CUT, CUT2, CUT3 réglés).
- **Langue** : *Paramètres → Langue* : Système, Français ou English (téléphone et TV), appliquée immédiatement. Les messages du serveur suivent la langue choisie.
- **Jeux non identifiés** : *Paramètres → Affichage → Masquer les jeux non identifiés* (téléphone et TV) retire des listes de jeux et de la recherche ceux que le serveur n’a pas pu identifier (introuvables dans les bases de jeux, ou pas encore scrapés).
- **Place occupée** (Windows, téléphone et TV) : sur l’écran des systèmes, chaque carte indique la place prise par les jeux du système sur l’appareil (*Sur ce PC* / *Sur l’appareil*), et une barre à côté du nom de l’application montre l’espace occupé et l’espace libre de la partition du dossier des ROMs (en rouge sous 10 % d’espace libre).
- **Classer les jeux** : *Paramètres → Affichage → Classer les jeux* (Windows, téléphone et TV) : **par nom** ou **par note** (les mieux notés d’abord, les jeux sans note à la fin), dans les listes, la recherche et chaque rangée du carrousel (*Ajoutés récemment* reste par date).
- **Jeux en plusieurs disques, mises à jour et DLC** (Windows, téléphone et TV) : regroupés par le serveur, ils sont téléchargés ensemble dans le dossier du système (taille totale affichée, jeu présent quand tous ses fichiers le sont). Pour un jeu en plusieurs disques, l’application écrit une **liste des disques** (`.m3u`, au nom du premier disque : sauvegardes et états gardent leur nom) et l’ouvre avec le moteur intégré, RetroArch, DuckStation, Mednafen et les émulateurs Android dont le modèle accepte les `.m3u` : **changement de disque en jeu** (menu du moteur intégré). Disques compressés (`.zip`, `.7z`) : premier disque seulement. PlayStation 3 (RPCS3 géré par RomCloud) : mises à jour et DLC en `.pkg` installés automatiquement avant le premier lancement ; ailleurs, ils sont à côté du jeu, à installer depuis l’émulateur.
- **Jouer à plusieurs en réseau local** (Windows, téléphone et TV, entre eux : un PC joue avec un téléphone ou une TV) : quand un autre appareil RomCloud est ouvert sur le même réseau (quel que soit le profil), une icône apparaît sur l’écran principal, avec les parties proposées, et les jeux jouables à plusieurs portent une icône dans les listes (consoles de salon et arcade, jeu à au moins deux joueurs, moteur intégré). *Jouer à plusieurs en réseau* (fiche du jeu) lance le jeu et le propose ; l’autre appareil le rejoint (jeu téléchargé d’abord s’il manque) et l’hôte accepte ou refuse. Netplay synchronisé : chaque appareil émule le jeu, seules les touches circulent, image par image, avec 3 images de délai (environ 50 ms) ; l’état de l’hôte est copié chez l’invité au départ (l’hôte en repart aussi), et une empreinte de la mémoire du jeu (à défaut, de l’état complet) est comparée toutes les 2 secondes (écart : état de l’hôte rechargé chez l’invité). Le cœur sait qu’il joue en réseau : FBNeo y coupe les meilleurs scores enregistrés et fixe l’horloge de la Neo Geo. Cœurs vérifiés entre deux moteurs, sans écart : Mesen, Nestopia, FCEUmm, QuickNES, snes9x, bsnes, Mesen-S, Genesis Plus GX, PicoDrive, Gearsystem, FBNeo, Mupen64Plus-Next, Mednafen PSX (et HW), SwanStation, Mednafen Saturn ; parallel_n64, Yabause, YabaSanshiro, Kronos, Ymir et SMS Plus ne tiennent pas une partie synchronisée et ne la proposent pas. Pendant la partie, charger un état, redémarrer et changer les options du cœur sont désactivés. Découverte par annonces UDP sur le port 47321, connexion TCP directe entre les appareils. Windows : la partie se joue dans le moteur intégré (demande d’un invité acceptée avec A / Entrée, refusée avec B / Échap) ; au premier usage, le pare-feu de Windows demande d’autoriser RomCloud et romcloud-player sur le réseau privé.
- **Relier les consoles portables en réseau local** (Windows, téléphone et TV, entre eux), à part du jeu synchronisé : chaque appareil émule sa console avec **son propre jeu** (Pokémon Rouge relié à Pokémon Bleu), comme avec un câble. Une autre icône (maillon) marque ces jeux dans les listes ; *Relier les consoles en réseau* (fiche du jeu) lance le jeu et propose la liaison, et sur l’autre appareil, la fiche d’un jeu de la même console propose *Se relier à …* (ou *Se relier* depuis l’icône de l’écran principal, avec le même jeu). La liaison passe par le cœur prévu pour elle, quel que soit l’émulateur choisi (sa sauvegarde est reprise si elle est plus récente, et recopiée pour lui en quittant) :
  - **Game Boy et Game Boy Color** : câble Game Link de **Gambatte** (connexion directe entre les deux émulateurs, serveur chez l’hôte, port 56400).
  - **Game Boy Advance** : câble (Pokémon Rubis / Saphir, Advance Wars 1 et 2) et adaptateur sans fil (Pokémon Émeraude, Rouge Feu, Vert Feuille, Mario Golf, Mario Tennis, Mega Man Battle Network 5 et 6, Digimon Racing, DBZ Buu’s Fury, Narnia, Shrek Super Slam, Le Seigneur des Anneaux : Le Tiers Âge, Hamtaro) de **gpSP** ; ses paquets passent par la connexion RomCloud (interface *netpacket* de libretro). Seuls ces jeux sont proposés : gpSP n’émule pas le câble des autres (leur mode à plusieurs attendrait l’autre console devant un écran noir).
  - **PSP** : réseau **ad hoc** de **PPSSPP**, serveur ad hoc intégré chez l’hôte (port 27312), adresse MAC propre à chaque appareil, ports des jeux décalés de 10000 (Android interdit les ports sous 1024) ; d’autres consoles peuvent rejoindre la même partie.
  Les DS (melonDS DS ne relie pas deux consoles) et les autres consoles n’ont pas de liaison. Pendant la liaison, charger un état, redémarrer et changer les options du cœur sont désactivés.
- **Jouer à plusieurs par Internet** (Windows, téléphone et TV, entre eux) : avec un profil connecté, les appareils des autres joueurs connectés au même serveur (tous profils confondus) apparaissent comme ceux du réseau local, « par Internet », avec le nom du profil. Une partie proposée par un joueur connecté l’est aussi par Internet ; elle se rejoint de la même façon (icône de l’écran principal, ou fiche du jeu proposé). Tout passe par le **relais du serveur RomCloud** : les deux appareils ouvrent une connexion vers le serveur, qui fait passer les données de l’un à l’autre, sans rien ouvrir sur les box. Le délai des touches est adapté aux allers-retours mesurés jusqu’au serveur (3 à 15 images). Jeu synchronisé, câble GBA (gpSP) et câble Game Boy (Gambatte, sa connexion passe elle aussi par le relais) ; l’ad hoc de la PSP reste au réseau local (nombreux ports). Le serveur doit être joignable par les deux appareils ; derrière un proxy inverse, les requêtes `Upgrade` du chemin `/api/play/relay` doivent être transmises (comme des WebSockets : avec nginx, `proxy_http_version 1.1`, `proxy_set_header Upgrade $http_upgrade`, `proxy_set_header Connection "upgrade"` et un `proxy_read_timeout` long).
- **Lancement** : les modèles Daijishou sont interprétés (`-n`, `-a`, `-d`, `-t`, `-c`, `-e/--es`, `--ez`, `--ei`, `--esa`, `-f`, `--activity-*`…) avec les placeholders `{file.path}`, `{file.uri}` (via FileProvider), `{file.mime}`. Pour un système sans modèle, le sélecteur « Ouvrir avec » d'Android est proposé.

## Limites connues

- Un jeu = un fichier. Pour les jeux multi-fichiers (`.cue` + `.bin`), privilégiez `.chd` ou `.m3u` + fichiers, ou une archive `.zip` si le cœur la supporte.
- Les placeholders `{tags.*}` de Daijishou (Steam, Vita, PS3…) ne sont pas gérés : ces plateformes ne sont pas pertinentes pour des ROMs téléchargées.
- Les modèles utilisant `{file.uri}` reçoivent une URI `content://` de RomCloud au lieu d'une URI SAF comme dans Daijishou ; la plupart des émulateurs l'acceptent, certains peuvent exiger `{file.path}`.
- La base Libretro ne contient presque aucune image pour l’Amstrad GX4000 (pas de jaquettes) : configurez ScreenScraper pour ce système. À défaut, l’écran-titre ou la capture Libretro sert de couverture.
- Les identifiants ScreenScraper de quelques systèmes rares ne sont pas pré-remplis : on peut les saisir dans *Réglages* du système.
