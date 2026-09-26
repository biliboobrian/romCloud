# RomCloud

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

## 1. Serveur (`server/`)

Prérequis : **Node.js ≥ 22.13** (utilise le module SQLite intégré `node:sqlite`, aucune dépendance native).

```bash
cd server
cp .env.example .env      # puis renseigner API_KEY et, si possible, ScreenScraper
npm install
npm start                 # http://<ip-du-pc>:8080
```

Ou avec Docker :

```bash
docker build -t romcloud server
docker run -d -p 8080:8080 -v /chemin/vers/data:/data -e API_KEY=monsecret romcloud
```

### Utilisation de l'interface web

1. **Ajouter un système** → onglet *Catalogue Daijishou* : cochez les plateformes (SNES, PSX, GBA…) et importez-les. Elles arrivent avec leurs émulateurs, le filtre d'extensions, le nom Libretro et l'identifiant ScreenScraper. L'onglet *Personnalisé* permet de créer un système à la main.
2. **Ajouter des ROMs** : glisser-déposer dans la page (case « scraper automatiquement » cochée par défaut), ou copier les fichiers dans `data/roms/<dossier du système>/` puis cliquer sur **Rescanner**.
3. **Scraper** : bouton *Scraper les jeux* (toute la liste, en tâche de fond) ou depuis la fiche d'un jeu (ScreenScraper / Libretro au choix). Titre, description, date, genre, éditeur, jaquette et capture sont enregistrés sur le serveur et renvoyés à l'application.
4. Cliquer sur un jeu permet d'éditer ses informations, remplacer ses images, le télécharger ou le supprimer.

### Configuration (`.env`)

| Variable | Rôle |
|---|---|
| `PORT` | Port HTTP (8080 par défaut) |
| `DATA_DIR` | Base SQLite, ROMs et médias (`./data`) |
| `ROMS_DIR` | Dossier des ROMs, relatif à `DATA_DIR` ou absolu (ex. un NAS monté) |
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
| POST | `/api/systems/:id/scan` · `/api/scan` | Synchroniser avec les dossiers |
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

Kotlin + Jetpack Compose, minSdk 26 (Android 8), targetSdk 35.

### Compiler l'APK

Ouvrir `android/` dans Android Studio, ou en ligne de commande (JDK 17 à 21) :

```bash
cd android
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk (signé avec la clé de debug)
```

Pour une diffusion, remplacez `signingConfig` dans `app/build.gradle.kts` par votre propre keystore.

### Fonctionnement

- **Premier lancement** : saisir l'adresse du serveur (`http://192.168.x.x:8080`) et la clé d'API, *Tester la connexion*, puis autoriser **l'accès à tous les fichiers** : les ROMs sont écrites dans un dossier partagé (`/storage/emulated/0/RomCloud/<système>/` par défaut) pour que les émulateurs puissent les lire.
- **Liste des jeux** : jaquette, titre, année, genre, taille, et un indicateur à droite :
  - ☁ gris : non téléchargé
  - cercle de progression : téléchargement en cours (pourcentage)
  - ✓ vert : présent sur l'appareil
  - ⚠ rouge : échec du dernier téléchargement
- **Appui sur un jeu** : s'il n'est pas téléchargé, une fenêtre demande de le télécharger d'abord (option « Lancer le jeu une fois téléchargé ») ; s'il l'est, il se lance directement. **Appui long / ⓘ** : fiche détaillée (description, capture, choix de l'émulateur, suppression locale).
- Téléchargements en arrière-plan avec notification, reprise automatique d'un fichier partiel (`.part`).
- **Hors ligne** : les listes sont mises en cache ; les jeux déjà téléchargés restent jouables.
- **Lancement** : les modèles Daijishou sont interprétés (`-n`, `-a`, `-d`, `-t`, `-c`, `-e/--es`, `--ez`, `--ei`, `--esa`, `-f`, `--activity-*`…) avec les placeholders `{file.path}`, `{file.uri}` (via FileProvider), `{file.mime}`. Pour un système sans modèle, le sélecteur « Ouvrir avec » d'Android est proposé.

## Limites connues

- Un jeu = un fichier. Pour les jeux multi-fichiers (`.cue` + `.bin`), privilégiez `.chd` ou `.m3u` + fichiers, ou une archive `.zip` si le cœur la supporte.
- Les placeholders `{tags.*}` de Daijishou (Steam, Vita, PS3…) ne sont pas gérés : ces plateformes ne sont pas pertinentes pour des ROMs téléchargées.
- Les modèles utilisant `{file.uri}` reçoivent une URI `content://` de RomCloud au lieu d'une URI SAF comme dans Daijishou ; la plupart des émulateurs l'acceptent, certains peuvent exiger `{file.path}`.
- Les identifiants ScreenScraper de quelques systèmes rares ne sont pas pré-remplis : on peut les saisir dans *Réglages* du système.
