// Traductions de l'interface web. Langue : choix mémorisé, sinon langue du navigateur.
//  - t('clé', { variables }) dans le code ;
//  - attributs data-i18n (texte), data-i18n-html, data-i18n-placeholder, data-i18n-title,
//    data-i18n-aria-label dans le HTML (appliqués par applyTranslations()).

export const LANGUAGES = { fr: 'Français', en: 'English' };

const dict = {
  en: {
    'app.systems': 'Systems',
    'app.add': '+ Add',
    'app.rescanAll': 'Rescan all folders',
    'app.jobs': 'Jobs',
    'app.apiKey': 'API key',
    'app.language': 'Language',
    'app.close': 'Close',
    'app.welcome': 'Welcome',
    'app.welcomeText': 'Add a system (from the Daijishou catalog or manually), then drop your ROMs.',
    'app.addSystem': 'Add a system',
    'scraper.both': 'ScreenScraper + Libretro',
    'scraper.libretroOnly': 'Libretro only (ScreenScraper not configured)',

    'system.addRoms': 'Add ROMs',
    'system.scrape': 'Scrape games',
    'system.rescan': 'Rescan',
    'system.duplicates': 'Duplicates',
    'system.settings': 'Settings',
    'system.drop': 'Drag and drop ROMs here',
    'system.autoScrape': 'scrape automatically',
    'system.search': 'Search a game…',
    'system.filterAll': 'All',
    'system.filterScraped': 'Scraped',
    'system.filterMissing': 'Not scraped',
    'system.meta.games': '{n} game(s)',
    'system.meta.folder': 'folder: roms/{folder}',
    'system.meta.emulators': '{n} emulator(s)',
    'system.noGames': 'No games yet. Drop ROMs above or copy them into the system folder, then click “Rescan”.',
    'status.ok': 'Scraped',
    'status.none': 'Not scraped',
    'status.notfound': 'Not found',
    'status.error': 'Error',

    'add.title': 'Add a system',
    'add.tabCatalog': 'Daijishou catalog',
    'add.tabCustom': 'Custom',
    'add.catalogIntro': 'Platforms and emulator templates from <a href="https://github.com/TapiocaFox/Daijishou/tree/main/platforms" target="_blank" rel="noopener">github.com/TapiocaFox/Daijishou</a>, completed by RomCloud (marked “RomCloud”, e.g. Amstrad GX4000).',
    'add.filter': 'Filter…',
    'add.loading': 'Loading…',
    'add.selected': '{n} selected',
    'add.import': 'Import',
    'add.catalogUnavailable': 'Catalog unavailable (no access to GitHub?). Use the “Custom” tab.',
    'add.updateAvailable': 'update available',
    'add.alreadyImported': 'already imported',
    'add.imported': '{n} system(s) imported',
    'add.created': 'System “{name}” created',
    'add.create': 'Create',
    'field.id': 'Identifier',
    'field.name': 'Name',
    'field.shortname': 'Short name',
    'field.folder': 'Folder',
    'field.folderPlaceholder': '(same as the identifier)',
    'field.regex': 'Accepted file name regex',
    'field.libretro': 'Libretro name',
    'field.ssId': 'ScreenScraper system ID',

    'sys.title': 'System settings',
    'sys.noImage': 'No image',
    'sys.imageHint': 'Image shown in the Android apps (logo or photo of the console).',
    'sys.chooseImage': 'Choose an image',
    'sys.remove': 'Remove',
    'sys.folderInfo': 'Identifier: {id} — ROM folder: roms/{folder}',
    'sys.emulators': 'Emulators ({n})',
    'sys.noEmulators': 'None: the Android apps can only open games with “Open with”. Add the emulators of a similar system below.',
    'sys.copyHint': 'Add the emulators of another system (e.g. Amstrad CPC → GX4000):',
    'sys.copyChoose': 'Choose a system…',
    'sys.copyServer': 'Server systems',
    'sys.copyCatalog': 'Daijishou catalog',
    'sys.copyAdd': 'Add',
    'sys.emulatorRemoved': 'Emulator removed',
    'sys.emulatorsPresent': 'These emulators are already present',
    'sys.emulatorsAdded': '{n} emulator(s) added',
    'sys.delete': 'Delete…',
    'sys.save': 'Save',
    'sys.saved': 'Settings saved',
    'sys.imageSaved': 'System image saved',
    'sys.confirmDelete': 'Delete the system “{name}” from RomCloud?',
    'sys.confirmDeleteFiles': 'Also delete the {n} file(s) in the folder roms/{folder}?\n\nOK = delete the files, Cancel = keep them on disk.',
    'sys.deleted': 'System deleted',

    'upload.files': '{n} file(s) · {size}',
    'upload.failed': 'Upload failed ({status})',
    'upload.interrupted': 'Upload failed (connection interrupted)',
    'upload.done': '{n} ROM(s) added',
    'upload.rejected': ', {n} rejected (extension not accepted)',
    'rescan.done': '{added} added, {updated} updated, {removed} removed',
    'rescan.allDone': 'Folders rescanned',

    'game.title': 'Game',
    'game.boxart': 'Box art',
    'game.screenshot': 'Screenshot',
    'game.replace': 'Replace',
    'game.fieldTitle': 'Title',
    'game.releaseDate': 'Release date',
    'game.players': 'Players',
    'game.rating': 'Rating /5',
    'game.developer': 'Developer',
    'game.publisher': 'Publisher',
    'game.genre': 'Genre',
    'game.description': 'Description',
    'game.scrape': 'Scrape',
    'game.download': 'Download',
    'game.delete': 'Delete',
    'game.save': 'Save',
    'game.saved': 'Game saved',
    'game.deleted': 'Game deleted',
    'game.never': 'Never scraped',
    'game.scrapedVia': 'Scraped via {source}',
    'game.notFound': 'Not found during the last scrape',
    'game.error': 'Error: {error}',
    'game.scraping': 'Scraping…',
    'game.found': 'Information retrieved',
    'game.missing': 'Game not found',
    'game.confirmDelete': 'Permanently delete “{file}” from the server?',

    'dup.title': 'Duplicates — {name}',
    'dup.analysing': 'Analysing… (computing fingerprints of files of the same size)',
    'dup.groupsIdentical': '{n} group(s) of identical files',
    'dup.noneIdentical': 'no identical file',
    'dup.groupsSimilar': '{n} game(s) in several versions',
    'dup.noneSimilar': 'no game in several versions',
    'dup.unhashed': ' {n} file(s) larger than {mb} MB were not compared byte by byte.',
    'dup.identical': 'Identical files',
    'dup.identicalHint': 'Same content under several names: the copies are checked for deletion.',
    'dup.similar': 'Same game, different versions',
    'dup.similarHint': 'Regions, revisions or variants: check the ones to delete.',
    'dup.files': '{n} files · {size}',
    'dup.keep': 'Keep',
    'dup.scraped': ' · scraped',
    'dup.selection': '{n} file(s) selected · {size} freed',
    'dup.noSelection': 'No file selected',
    'dup.deleteSelection': 'Delete selection',
    'dup.confirmWipe': 'All the files of “{title}”{more} are checked: the game will disappear completely. Continue?',
    'dup.confirmWipeMore': ' (and {n} other game(s))',
    'dup.confirmDelete': 'Permanently delete {n} file(s) from the server?',
    'dup.deleted': '{n} file(s) deleted, {size} freed',

    'jobs.title': 'Scraping jobs',
    'jobs.none': 'No recent job.',
    'jobs.queued': 'Queued',
    'jobs.running': 'Running',
    'jobs.done': 'Done',
    'jobs.cancelled': 'Cancelled',
    'jobs.failed': 'Interrupted',
    'jobs.progress': '{done}/{total} — {ok} found, {notFound} not found, {failed} error(s)',
    'jobs.cancel': 'Cancel',
    'scrape.onlyMissing': 'Scrape only the games not scraped yet?\n\nOK = only the missing ones, Cancel = re-scrape everything.',
    'scrape.started': 'Scraping started ({n} game(s))',

    'key.prompt': 'API key of the RomCloud server:',
    'key.required': 'API key required',
    'errors.http': 'Error {status}',
  },
  fr: {
    'app.systems': 'Systèmes',
    'app.add': '+ Ajouter',
    'app.rescanAll': 'Rescanner tous les dossiers',
    'app.jobs': 'Tâches',
    'app.apiKey': 'Clé d’API',
    'app.language': 'Langue',
    'app.close': 'Fermer',
    'app.welcome': 'Bienvenue',
    'app.welcomeText': 'Ajoutez un système (depuis le catalogue Daijishou ou manuellement), puis déposez vos ROMs.',
    'app.addSystem': 'Ajouter un système',
    'scraper.both': 'ScreenScraper + Libretro',
    'scraper.libretroOnly': 'Libretro seul (ScreenScraper non configuré)',

    'system.addRoms': 'Ajouter des ROMs',
    'system.scrape': 'Scraper les jeux',
    'system.rescan': 'Rescanner',
    'system.duplicates': 'Doublons',
    'system.settings': 'Réglages',
    'system.drop': 'Glissez-déposez des ROMs ici',
    'system.autoScrape': 'scraper automatiquement',
    'system.search': 'Rechercher un jeu…',
    'system.filterAll': 'Tous',
    'system.filterScraped': 'Scrapés',
    'system.filterMissing': 'Non scrapés',
    'system.meta.games': '{n} jeu(x)',
    'system.meta.folder': 'dossier : roms/{folder}',
    'system.meta.emulators': '{n} émulateur(s)',
    'system.noGames': 'Aucun jeu pour l’instant. Déposez des ROMs ci-dessus ou copiez-les dans le dossier du système puis cliquez sur « Rescanner ».',
    'status.ok': 'Scrapé',
    'status.none': 'Non scrapé',
    'status.notfound': 'Introuvable',
    'status.error': 'Erreur',

    'add.title': 'Ajouter un système',
    'add.tabCatalog': 'Catalogue Daijishou',
    'add.tabCustom': 'Personnalisé',
    'add.catalogIntro': 'Plateformes et modèles d’émulateurs issus de <a href="https://github.com/TapiocaFox/Daijishou/tree/main/platforms" target="_blank" rel="noopener">github.com/TapiocaFox/Daijishou</a>, complétés par RomCloud (marqués « RomCloud », ex. Amstrad GX4000).',
    'add.filter': 'Filtrer…',
    'add.loading': 'Chargement…',
    'add.selected': '{n} sélectionné(s)',
    'add.import': 'Importer',
    'add.catalogUnavailable': 'Catalogue indisponible (pas d’accès à GitHub ?). Utilisez l’onglet « Personnalisé ».',
    'add.updateAvailable': 'mise à jour dispo',
    'add.alreadyImported': 'déjà importé',
    'add.imported': '{n} système(s) importé(s)',
    'add.created': 'Système « {name} » créé',
    'add.create': 'Créer',
    'field.id': 'Identifiant',
    'field.name': 'Nom',
    'field.shortname': 'Nom court',
    'field.folder': 'Dossier',
    'field.folderPlaceholder': '(identique à l’identifiant)',
    'field.regex': 'Regex des fichiers acceptés',
    'field.libretro': 'Nom Libretro',
    'field.ssId': 'ID système ScreenScraper',

    'sys.title': 'Réglages du système',
    'sys.noImage': 'Aucune image',
    'sys.imageHint': 'Image affichée dans les applications Android (logo ou photo de la console).',
    'sys.chooseImage': 'Choisir une image',
    'sys.remove': 'Retirer',
    'sys.folderInfo': 'Identifiant : {id} — dossier des ROMs : roms/{folder}',
    'sys.emulators': 'Émulateurs ({n})',
    'sys.noEmulators': 'Aucun : les applications Android ne pourront lancer les jeux que via « Ouvrir avec ». Ajoutez les émulateurs d’un système proche ci-dessous.',
    'sys.copyHint': 'Ajouter les émulateurs d’un autre système (ex. Amstrad CPC → GX4000) :',
    'sys.copyChoose': 'Choisir un système…',
    'sys.copyServer': 'Systèmes du serveur',
    'sys.copyCatalog': 'Catalogue Daijishou',
    'sys.copyAdd': 'Ajouter',
    'sys.emulatorRemoved': 'Émulateur retiré',
    'sys.emulatorsPresent': 'Ces émulateurs sont déjà présents',
    'sys.emulatorsAdded': '{n} émulateur(s) ajouté(s)',
    'sys.delete': 'Supprimer…',
    'sys.save': 'Enregistrer',
    'sys.saved': 'Réglages enregistrés',
    'sys.imageSaved': 'Image du système enregistrée',
    'sys.confirmDelete': 'Supprimer le système « {name} » de RomCloud ?',
    'sys.confirmDeleteFiles': 'Supprimer aussi les {n} fichier(s) du dossier roms/{folder} ?\n\nOK = supprimer les fichiers, Annuler = les conserver sur le disque.',
    'sys.deleted': 'Système supprimé',

    'upload.files': '{n} fichier(s) · {size}',
    'upload.failed': 'Échec de l’envoi ({status})',
    'upload.interrupted': 'Échec de l’envoi (connexion interrompue)',
    'upload.done': '{n} ROM(s) ajoutée(s)',
    'upload.rejected': ', {n} refusée(s) (extension non acceptée)',
    'rescan.done': '{added} ajouté(s), {updated} modifié(s), {removed} retiré(s)',
    'rescan.allDone': 'Dossiers rescannés',

    'game.title': 'Jeu',
    'game.boxart': 'Jaquette',
    'game.screenshot': 'Capture',
    'game.replace': 'Remplacer',
    'game.fieldTitle': 'Titre',
    'game.releaseDate': 'Date de sortie',
    'game.players': 'Joueurs',
    'game.rating': 'Note /5',
    'game.developer': 'Développeur',
    'game.publisher': 'Éditeur',
    'game.genre': 'Genre',
    'game.description': 'Description',
    'game.scrape': 'Scraper',
    'game.download': 'Télécharger',
    'game.delete': 'Supprimer',
    'game.save': 'Enregistrer',
    'game.saved': 'Jeu enregistré',
    'game.deleted': 'Jeu supprimé',
    'game.never': 'Jamais scrapé',
    'game.scrapedVia': 'Scrapé via {source}',
    'game.notFound': 'Introuvable lors du dernier scraping',
    'game.error': 'Erreur : {error}',
    'game.scraping': 'Scraping…',
    'game.found': 'Informations récupérées',
    'game.missing': 'Jeu introuvable',
    'game.confirmDelete': 'Supprimer définitivement « {file} » du serveur ?',

    'dup.title': 'Doublons — {name}',
    'dup.analysing': 'Analyse en cours… (calcul des empreintes des fichiers de même taille)',
    'dup.groupsIdentical': '{n} groupe(s) de fichiers identiques',
    'dup.noneIdentical': 'aucun fichier identique',
    'dup.groupsSimilar': '{n} jeu(x) en plusieurs versions',
    'dup.noneSimilar': 'aucun jeu en plusieurs versions',
    'dup.unhashed': ' {n} fichier(s) de plus de {mb} Mo n’ont pas été comparés octet par octet.',
    'dup.identical': 'Fichiers identiques',
    'dup.identicalHint': 'Même contenu sous plusieurs noms : les copies sont cochées pour suppression.',
    'dup.similar': 'Même jeu, versions différentes',
    'dup.similarHint': 'Régions, révisions ou variantes : cochez celles à supprimer.',
    'dup.files': '{n} fichiers · {size}',
    'dup.keep': 'À conserver',
    'dup.scraped': ' · scrapé',
    'dup.selection': '{n} fichier(s) sélectionné(s) · {size} libérés',
    'dup.noSelection': 'Aucun fichier sélectionné',
    'dup.deleteSelection': 'Supprimer la sélection',
    'dup.confirmWipe': 'Tous les fichiers de « {title} »{more} sont cochés : le jeu disparaîtra complètement. Continuer ?',
    'dup.confirmWipeMore': ' (et {n} autre(s) jeu(x))',
    'dup.confirmDelete': 'Supprimer définitivement {n} fichier(s) du serveur ?',
    'dup.deleted': '{n} fichier(s) supprimé(s), {size} libérés',

    'jobs.title': 'Tâches de scraping',
    'jobs.none': 'Aucune tâche récente.',
    'jobs.queued': 'En attente',
    'jobs.running': 'En cours',
    'jobs.done': 'Terminé',
    'jobs.cancelled': 'Annulé',
    'jobs.failed': 'Interrompu',
    'jobs.progress': '{done}/{total} — {ok} trouvé(s), {notFound} introuvable(s), {failed} erreur(s)',
    'jobs.cancel': 'Annuler',
    'scrape.onlyMissing': 'Scraper uniquement les jeux pas encore scrapés ?\n\nOK = seulement les manquants, Annuler = tout re-scraper.',
    'scrape.started': 'Scraping lancé ({n} jeu(x))',

    'key.prompt': 'Clé d’API du serveur RomCloud :',
    'key.required': 'Clé d’API requise',
    'errors.http': 'Erreur {status}',
  },
};

// Unités de taille par langue
const UNITS = { en: ['B', 'KB', 'MB', 'GB', 'TB'], fr: ['o', 'Ko', 'Mo', 'Go', 'To'] };

function detectLanguage() {
  try {
    const saved = localStorage.getItem('romcloud.lang');
    if (saved && dict[saved]) return saved;
  } catch {
    /* stockage indisponible */
  }
  for (const l of navigator.languages || [navigator.language || 'en']) {
    const code = String(l).slice(0, 2).toLowerCase();
    if (dict[code]) return code;
  }
  return 'en';
}

let lang = detectLanguage();

export function getLanguage() {
  return lang;
}

export function setLanguage(code) {
  if (!dict[code]) return;
  lang = code;
  try {
    localStorage.setItem('romcloud.lang', code);
  } catch {
    /* stockage indisponible */
  }
  applyTranslations();
}

export function t(key, vars = {}) {
  const text = dict[lang][key] ?? dict.en[key] ?? key;
  return text.replace(/\{(\w+)\}/g, (m, name) => (vars[name] !== undefined ? String(vars[name]) : m));
}

export function formatSize(bytes) {
  const units = UNITS[lang] || UNITS.en;
  if (!bytes) return `0 ${units[0]}`;
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  const value = new Intl.NumberFormat(lang, { maximumFractionDigits: i ? 1 : 0, minimumFractionDigits: i ? 1 : 0 })
    .format(bytes / 1024 ** i);
  return `${value} ${units[i]}`;
}

/** Applique les traductions aux éléments marqués data-i18n* (page statique). */
export function applyTranslations(root = document) {
  document.documentElement.lang = lang;
  for (const el of root.querySelectorAll('[data-i18n]')) el.textContent = t(el.dataset.i18n);
  for (const el of root.querySelectorAll('[data-i18n-html]')) el.innerHTML = t(el.dataset.i18nHtml);
  for (const el of root.querySelectorAll('[data-i18n-placeholder]')) el.placeholder = t(el.dataset.i18nPlaceholder);
  for (const el of root.querySelectorAll('[data-i18n-title]')) el.title = t(el.dataset.i18nTitle);
  for (const el of root.querySelectorAll('[data-i18n-aria-label]')) el.setAttribute('aria-label', t(el.dataset.i18nAriaLabel));
}
