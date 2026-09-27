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
  -e API_KEY=choisissez-une-cle \
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
      API_KEY: choisissez-une-cle
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

### Premier lancement de l’app

Saisissez l’adresse du serveur (`http://<ip-du-serveur>:8080`) et la clé d’API, **Tester la connexion**, **Enregistrer**, puis autorisez **l’accès à tous les fichiers** (voir [Fonctionnement](#fonctionnement)).

## 1. Serveur (`server/`) — développement et configuration

Sans Docker, prérequis : **Node.js ≥ 22.13** (utilise le module SQLite intégré `node:sqlite`, aucune dépendance native).

```bash
cd server
cp .env.example .env      # puis renseigner API_KEY et, si possible, ScreenScraper
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
5. **Émulateurs d’un système** : dans *Réglages → Émulateurs*, retirez les modèles inutiles ou ajoutez ceux d’un autre système (du serveur ou du catalogue Daijishou). Utile pour un système créé à la main (onglet *Personnalisé*).
6. **Doublons** (bouton dans la vue d’un système) : liste les **fichiers identiques** (même contenu sous plusieurs noms, vérifié par MD5 — les copies sont pré-cochées) et les **jeux en plusieurs versions** (régions, révisions : à cocher soi-même). Dans chaque groupe, RomCloud propose le fichier à conserver : sans marque de copie « (2) », pas de démo/bêta/dump défectueux, région préférée (`SCRAPE_REGIONS`), révision la plus récente, puis déjà scrapé. Les fichiers de plus de `HASH_MAX_MB` ne sont comparés que par leur titre.
7. Cliquer sur un jeu permet d'éditer ses informations, remplacer ses images, le télécharger ou le supprimer.

### Configuration (`.env`)

| Variable | Rôle |
|---|---|
| `PORT` | Port HTTP (8080 par défaut) |
| `DATA_DIR` | Base SQLite, ROMs et médias (`./data`) |
| `ROMS_DIR` | Dossier des ROMs, relatif à `DATA_DIR` ou absolu (ex. un NAS monté) |
| `DEFAULT_LANGUAGE` | Langue par défaut du serveur (`fr` ou `en`, `en` par défaut) : journal et clients qui n’envoient pas d’en-tête `Accept-Language` |
| `API_KEY` | Clé exigée par l'API et l'interface web. **À définir** si le serveur est accessible au-delà de votre réseau local. |
| `SCREENSCRAPER_DEV_ID` / `_DEV_PASSWORD` | Identifiants **développeur** ScreenScraper (à demander sur le forum screenscraper.fr). Sans eux, seul Libretro est utilisé. |
| `SCREENSCRAPER_USER` / `_PASSWORD` | Votre compte ScreenScraper (facultatif, augmente les quotas) |
| `SCRAPE_LANGUAGES` / `SCRAPE_REGIONS` | Priorités de langue/région (`fr,en` / `fr,eu,wor,us,ss,jp`) |

**Scraping** : ScreenScraper est interrogé par CRC32/MD5 + nom + taille (hachage jusqu'à `HASH_MAX_MB`, 1 Go par défaut). En cas d'absence ou d'image manquante, repli sur [thumbnails.libretro.com](https://thumbnails.libretro.com) (correspondance exacte du nom No-Intro, puis approximative). Les tâches s'exécutent une par une et s'arrêtent si le quota ScreenScraper est atteint.

### API

Toutes les routes (sauf `/api/info`) exigent `Authorization: Bearer <API_KEY>` (ou `?key=`) quand une clé est définie.

| Méthode | Route | Description |
|---|---|---|
| GET | `/api/info` | Nom, version, clé requise ? |
| GET | `/api/systems` | Systèmes (+ émulateurs, nb de jeux, taille) |
| POST/PUT/DELETE | `/api/systems[/:id]` | Créer / modifier / supprimer (`?deleteFiles=1`) |
| GET | `/api/daijishou/platforms` | Catalogue Daijishou |
| POST | `/api/daijishou/import` | `{ "filenames": ["SuperNintendoEntertainmentSystem.json"] }` |
| GET/PUT/DELETE | `/api/systems/:id/image` | Image du système (PUT : corps binaire, `Content-Type: image/png`…) |
| GET | `/api/systems/:id/duplicates` | Groupes `identical` / `similar` avec le fichier proposé (`keepId`) |
| POST | `/api/systems/:id/duplicates/delete` | `{ "ids": [12, 15] }` : supprime fichiers, fiches et images |
| POST | `/api/systems/:id/scan` · `/api/scan` | Synchroniser avec les dossiers |
| GET | `/api/search?q=mots&limit=300` | Recherche dans tous les systèmes (chaque mot dans le titre ou le nom de fichier) |
| GET | `/api/systems/:id/games` | Liste des jeux (`?q=` recherche) |
| POST | `/api/systems/:id/games` | Envoi multipart `files[]` (`?scrape=1`) |
| GET/PUT/DELETE | `/api/games/:id` | Fiche d'un jeu |
| GET | `/api/games/:id/file` | Téléchargement de la ROM (Range / reprise) |
| GET/PUT | `/api/games/:id/media/boxart\|screenshot` | Images |
| POST | `/api/games/:id/scrape` | `{ "source": "auto\|screenscraper\|libretro" }` |
| POST | `/api/systems/:id/scrape` | Tâche de fond `{ "onlyMissing": true }` |
| GET/DELETE | `/api/jobs[/:id]` | Suivi / annulation des tâches |

Tests : `npm test`.

## 2. Application Android (`android/`)

Kotlin + Jetpack Compose, minSdk 26 (Android 8), targetSdk 36 (Android 16). Trois modules Gradle :

| Module | Contenu |
|---|---|
| `core` | Code partagé : API du serveur, cache hors ligne, téléchargements (service de premier plan), lancement des émulateurs (modèles `am start`, chemins SAF RetroArch…), réglages, ViewModels, fenêtres communes |
| `app` | Application téléphone / tablette (`com.romcloud.app`) |
| `tv` | Application Android TV (`com.romcloud.app.tv`), interface [tv-material](https://developer.android.com/jetpack/androidx/releases/tv) pour la télécommande |

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
- **Hors ligne** : les listes sont mises en cache ; les jeux déjà téléchargés restent jouables.
- **Langue** : *Paramètres → Langue* : Système, Français ou English (téléphone et TV), appliquée immédiatement. Les messages du serveur suivent la langue choisie.
- **Lancement** : les modèles Daijishou sont interprétés (`-n`, `-a`, `-d`, `-t`, `-c`, `-e/--es`, `--ez`, `--ei`, `--esa`, `-f`, `--activity-*`…) avec les placeholders `{file.path}`, `{file.uri}` (via FileProvider), `{file.mime}`. Pour un système sans modèle, le sélecteur « Ouvrir avec » d'Android est proposé.

## Limites connues

- Un jeu = un fichier. Pour les jeux multi-fichiers (`.cue` + `.bin`), privilégiez `.chd` ou `.m3u` + fichiers, ou une archive `.zip` si le cœur la supporte.
- Les placeholders `{tags.*}` de Daijishou (Steam, Vita, PS3…) ne sont pas gérés : ces plateformes ne sont pas pertinentes pour des ROMs téléchargées.
- Les modèles utilisant `{file.uri}` reçoivent une URI `content://` de RomCloud au lieu d'une URI SAF comme dans Daijishou ; la plupart des émulateurs l'acceptent, certains peuvent exiger `{file.path}`.
- La base Libretro ne contient presque aucune image pour l’Amstrad GX4000 (pas de jaquettes) : configurez ScreenScraper pour ce système. À défaut, l’écran-titre ou la capture Libretro sert de couverture.
- Les identifiants ScreenScraper de quelques systèmes rares ne sont pas pré-remplis : on peut les saisir dans *Réglages* du système.
