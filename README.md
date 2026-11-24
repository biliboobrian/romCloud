# RomCloud (dépôt archivé)

Ce dépôt n’est plus mis à jour. RomCloud est maintenant développé et publié dans cinq dépôts, avec
l’historique de chaque partie :

| Dépôt | Contenu | Téléchargement |
|---|---|---|
| [romcloud-android](https://github.com/biliboobrian/romcloud-android) | Application Android (téléphone, tablette) et Android TV | [Releases](https://github.com/biliboobrian/romcloud-android/releases/latest) |
| [romcloud-desktop](https://github.com/biliboobrian/romcloud-desktop) | Application Windows (Tauri) et son moteur intégré | [Releases](https://github.com/biliboobrian/romcloud-desktop/releases/latest) |
| [romcloud-server](https://github.com/biliboobrian/romcloud-server) | Serveur (Node.js) | [Docker Hub](https://hub.docker.com/r/biliboobrian/romcloud-server) (image inchangée) |
| [romcloud-pc](https://github.com/biliboobrian/romcloud-pc) | RomCloud PC : jeux PC sous Android (Winlator Cmod) | [Releases](https://github.com/biliboobrian/romcloud-pc/releases/latest) |
| [romcloud-native](https://github.com/biliboobrian/romcloud-native) | RomCloud Native : jeux PC traduits en ARM64 (prototype de recherche) | [Releases](https://github.com/biliboobrian/romcloud-native/releases/latest) |

## Mises à jour

- **Applications installées** (Android, Android TV, Windows, RomCloud PC) : rien à faire. La dernière
  Release de ce dépôt (1.106.0) est la même que celle des nouveaux dépôts ; une fois installée, chaque
  application cherche ses mises à jour au bon endroit.
- **Obtainium** : remplacez l’URL `https://github.com/biliboobrian/romCloud` par
  `https://github.com/biliboobrian/romcloud-android` (filtre `RomCloud-\d`, ou `RomCloud-TV` sur un
  téléviseur), et pour RomCloud PC par `https://github.com/biliboobrian/romcloud-pc`.
- **Serveur Docker** : l’image `biliboobrian/romcloud-server` ne change pas de nom ; mettez-le à jour
  (`docker compose pull && docker compose up -d`) pour que son miroir des émulateurs propose les
  nouvelles versions de RomCloud PC.

Les versions jusqu’à 1.105.0 restent dans les [Releases](https://github.com/biliboobrian/romCloud/releases)
de ce dépôt, et les sources ci-dessous dans l’état de la 1.105.0.
