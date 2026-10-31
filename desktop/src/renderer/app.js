// Interface de RomCloud pour Windows (script classique, API window.romcloud exposée par
// desktop-tauri/src-tauri/src/bridge.js).
(function () {
  const rc = window.romcloud;
  const { t, errorText, formatSize } = window.I18N;
  const Criteria = window.RomCloudCriteria;
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

  // ---------------------------------------------------------------------------
  // État
  // ---------------------------------------------------------------------------

  const S = {
    settings: null,
    // Jeu à plusieurs : appareils RomCloud du réseau local, systèmes compatibles (émulateur choisi),
    // partie à rejoindre dès la fin du téléchargement de son jeu.
    peers: [],
    // Multijoueurs (serveur) : joueurs connectés, lobbies, ce PC ; lobby dont il est membre.
    lobbies: { players: [], lobbies: [], me: null },
    lobby: null,
    netplayAllowed: {},
    pendingJoin: null,
    systems: [],
    systemsOffline: false,
    systemsError: null,
    systemsLoading: true,
    search: { query: '', results: [], downloaded: new Set(), offline: false, loading: false, error: null },
    // Place occupée sur le PC par système et espace de la partition des ROMs (library:usage).
    usage: { systems: {}, disk: null },
    games: { systemId: null, list: [], downloaded: new Set(), resumable: new Set(), offline: false, loading: false, error: null, filter: 'all', query: '', criteria: {} },
    downloads: {}, // gameId -> { status, bytes, total, title, error }
    // Jeux à lancer à la fin de leur téléchargement, avec les options du lancement (reprise…).
    autoLaunch: new Map(),
    route: { name: 'systems' },
    history: [],
    focusedGameId: null,
    cast: { status: 'idle', device: null, error: null },
    account: { signedIn: false }, // profil du joueur sur le serveur
    playtime: {}, // temps de jeu du profil : { [gameId]: secondes }
    connectivity: { online: true, pending: 0 }, // serveur joignable ; envois en attente
    localSystems: new Set(), // systèmes avec des jeux sur le PC (seuls affichés hors ligne)
    localGames: [], // jeux téléchargés, tous systèmes (rangée de la page des systèmes)
    favorites: [], // jeux favoris, téléchargés ou non (rangée au-dessus des jeux téléchargés)
    // Manettes du moteur intégré dans la fiche du jeu (pads:event) : service du système affiché.
    pads: { key: null },
  };

  // ---------------------------------------------------------------------------
  // Utilitaires
  // ---------------------------------------------------------------------------

  const ICONS = {
    link: 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z',
    crown: 'M5 16 3 5l5.5 5L12 4l3.5 6L21 5l-2 11H5zm14 3c0 .6-.4 1-1 1H6c-.6 0-1-.4-1-1v-1h14v1z',
    group: 'M12 12.75c1.63 0 3.07.39 4.24.9 1.08.48 1.76 1.56 1.76 2.73V18H6v-1.61c0-1.18.68-2.26 1.76-2.73 1.17-.52 2.61-.91 4.24-.91zM4 13c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm1.13 1.1c-.37-.06-.74-.1-1.13-.1-.99 0-1.93.21-2.78.58A2.01 2.01 0 0 0 0 16.43V18h4.5v-1.61c0-.83.23-1.61.63-2.29zM20 13c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm4 3.43c0-.81-.48-1.53-1.22-1.85A6.95 6.95 0 0 0 20 14c-.39 0-.76.04-1.13.1.4.68.63 1.46.63 2.29V18H24v-1.57zM12 6c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3z',
    back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
    gear: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84a.48.48 0 0 0-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.74 8.87a.47.47 0 0 0 .12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.47.47 0 0 0-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z',
    refresh: 'M17.65 6.35A7.96 7.96 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    search: 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
    close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    play: 'M8 5v14l11-7z',
    cloudOff: 'M19.35 10.04A7.49 7.49 0 0 0 12 4c-1.48 0-2.85.43-4.01 1.17l1.46 1.46A5.497 5.497 0 0 1 17.5 11v.5H19c1.66 0 3 1.34 3 3 0 1.13-.64 2.11-1.56 2.62l1.45 1.45C23.16 17.16 24 15.68 24 14c0-2.64-2.05-4.78-4.65-4.96zM3 5.27l2.75 2.74C2.56 8.15 0 10.77 0 14c0 3.31 2.69 6 6 6h11.73l2 2L21 20.73 4.27 4 3 5.27zM7.73 10l8 8H6c-2.21 0-4-1.79-4-4s1.79-4 4-4h1.73z',
    cloud: 'M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM17 13l-5 5-5-5h3V9h4v4h3z',
    check: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z',
    error: 'M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z',
    info: 'M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z',
    list: 'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
    carousel: 'M7 19h10V4H7v15zm-5-2h4V6H2v11zM18 6v11h4V6h-4z',
    filter: 'M10 18h4v-2h-4v2zM3 6v2h18V6H3zm3 7h12v-2H6v2z',
    folder: 'M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z',
    trash: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
    open: 'M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z',
    cast: 'M21 3H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7zm0-4v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11z',
    castOn: 'M1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7zm18-7H5v1.63c3.96 1.28 7.09 4.41 8.37 8.37H19V7zM1 10v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11zm20-7H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z',
    user: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
    timer: 'M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61 1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42A8.962 8.962 0 0 0 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z',
    favorite: 'm12 21.35-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z',
    favoriteBorder: 'M16.5 3c-1.74 0-3.41.81-4.5 2.09C10.91 3.81 9.24 3 7.5 3 4.42 3 2 5.42 2 8.5c0 3.78 3.4 6.86 8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 16.5 3zm-4.4 15.55-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05z',
    pad: 'M21 6H3c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-10 7H8v3H6v-3H3v-2h3V8h2v3h3v2zm4.5 2a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm4-3a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z',
  };
  const icon = (name, size = 20) => `<svg class="icon" viewBox="0 0 24 24" style="width:${size}px;height:${size}px"><path d="${ICONS[name]}"/></svg>`;

  function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  }

  /** Appelle le processus principal ; lève une erreur traduite en cas d'échec. */
  async function call(fn, ...args) {
    const r = await fn(...args);
    if (!r.ok) {
      const err = new Error(errorText(r.error));
      err.info = r.error;
      throw err;
    }
    return r.data;
  }

  function toast(message, { type = '', action, onAction, duration } = {}) {
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.innerHTML = `<span>${esc(message)}</span>`;
    if (action) {
      const btn = document.createElement('button');
      btn.className = 'btn primary';
      btn.textContent = action;
      btn.onclick = () => {
        el.remove();
        onAction();
      };
      el.append(btn);
    }
    $('#toasts').append(el);
    setTimeout(() => el.remove(), duration ?? (action ? 10000 : type === 'error' ? 7000 : 3500));
  }

  // Clé d'API, et identifiant de ce PC (appareils d'une clé d'accès générée sur le serveur).
  const withKey = (url) => (S.settings.apiKey
    ? `${url}${url.includes('?') ? '&' : '?'}key=${encodeURIComponent(S.settings.apiKey)}${S.settings.deviceId ? `&device_id=${encodeURIComponent(S.settings.deviceId)}` : ''}`
    : url);

  /** Nombre de captures d'écran du jeu (serveur d'avant les 4 captures : 0 ou 1). */
  const screenshotCount = (game) => game.screenshotCount ?? (game.hasScreenshot ? 1 : 0);
  /** Médias des captures du jeu : « screenshot », « screenshot2 »… */
  const screenshotTypes = (game) => Array.from({ length: screenshotCount(game) }, (_, i) => (i ? `screenshot${i + 1}` : 'screenshot'));

  /**
   * Image d'un jeu ; [size] : version allégée du serveur (« thumb » : grilles et listes, « medium » :
   * fiche et fond d'écran), sinon l'original (capture en grand).
   */
  function mediaUrl(game, type, size) {
    const shot = /^screenshot(\d?)$/.exec(type);
    const has = type === 'boxart' ? game.hasBoxart : shot ? Number(shot[1] || 1) <= screenshotCount(game) : false;
    if (!has || !S.settings.serverUrl) return null;
    const sized = size ? `&size=${size}` : '';
    return withKey(`${S.settings.serverUrl}/api/games/${game.id}/media/${type}?v=${encodeURIComponent(game.updatedAt || '')}${sized}`);
  }

  /** Image du système, en vignette (cartes des systèmes, pastilles). */
  function systemImageUrl(system) {
    if (!system?.hasImage || !S.settings.serverUrl) return null;
    return withKey(`${S.settings.serverUrl}/api/systems/${encodeURIComponent(system.id)}/image?v=${encodeURIComponent(system.imageVersion || '')}&size=thumb`);
  }

  const systemById = (id) => S.systems.find((s) => s.id === id);
  const year = (g) => (g.releaseDate && /^\d{4}/.test(g.releaseDate) ? g.releaseDate.slice(0, 4) : null);
  const mainGenre = (g) => (g.genre || '').split(/[,/;]/)[0].trim() || null;
  // Taille du jeu et de ses parties (disques, mises à jour, DLC téléchargés avec lui).
  const gameSize = (game) => game.totalSize ?? game.size;
  // Jeu PC compressé par le serveur (une archive par fichier) : taille installée et taille à télécharger.
  const zipped = (game) => Boolean(game.folder && game.packed && game.packed !== 'flat' && game.packedSize != null);
  const sizeLabel = (game) =>
    zipped(game) ? t('size.flatZip', { size: formatSize(gameSize(game)), zip: formatSize(game.packedSize) }) : formatSize(gameSize(game));

  // ---------------------------------------------------------------------------
  // Statut local des jeux (téléchargé, en cours, échec)
  // ---------------------------------------------------------------------------

  function statusOf(game, downloaded) {
    const d = S.downloads[game.id];
    // Jeu PC compressé : unpack, part décompressée (null : rien à décompresser) ; unpacking : tout est
    // reçu, la décompression continue (jeu jouable à la fin).
    if (d?.status === 'running') {
      const unpack = d.unpackTotal ? d.unpackBytes / d.unpackTotal : null;
      return { kind: 'running', progress: d.total ? d.bytes / d.total : 0, unpack, unpacking: unpack != null && d.bytes >= d.total && unpack < 1, d };
    }
    if (d?.status === 'failed') return { kind: 'failed', error: errorText(d.error) };
    return downloaded.has(game.id) ? { kind: 'downloaded' } : { kind: 'remote' };
  }

  /**
   * Barres en bas de la jaquette : téléchargement et, collée dessous, décompression (jeu PC
   * compressé : archives décompressées une à une pendant le téléchargement).
   */
  function cardBarsHtml(gameId, st) {
    const pct = (v) => `${Math.round((v || 0) * 100)}%`;
    const running = st.kind === 'running';
    const unpack = running && st.unpack != null;
    return `<div class="progress-bars${running ? '' : ' hidden'}" data-progress="${gameId}">
      <div class="progress"><div style="width:${pct(st.progress)}"></div></div>
      ${unpack ? `<div class="progress unpack"><div style="width:${pct(st.unpack)}"></div></div>` : ''}</div>`;
  }

  /** Progression écrite sous les barres : octets reçus, puis décompressés (jeu PC compressé). */
  function downloadProgressText(st) {
    const d = st.d;
    const unpack = st.unpack != null ? t('download.unpack', { bytes: formatSize(d.unpackBytes), total: formatSize(d.unpackTotal) }) : '';
    if (st.unpacking) return `${t('download.unpackingPlayable')} ${unpack}`;
    const received = t('download.progress', { bytes: formatSize(d.bytes), total: formatSize(d.total) });
    return unpack ? `${received} · ${unpack}` : received;
  }

  function statusHtml(st) {
    switch (st.kind) {
      case 'downloaded': return `<span class="status-icon ok" title="${esc(t('status.downloaded'))}">${icon('check', 24)}</span>`;
      case 'running': return `<span class="ring">${Math.round((st.unpacking ? st.unpack : st.progress) * 100)}%</span>`;
      case 'failed': return `<span class="status-icon error" title="${esc(t('status.failed', { error: st.error }))}">${icon('error', 22)}</span>`;
      default: return `<span class="status-icon remote" title="${esc(t('status.remote'))}">${icon('cloud', 22)}</span>`;
    }
  }

  /** Met à jour l'affichage d'un jeu (progression) sans redessiner l'écran. */
  function patchGame(gameId) {
    const game = findGame(gameId);
    if (!game) return;
    const downloaded = S.route.name === 'systems' ? S.search.downloaded : S.games.downloaded;
    const st = statusOf(game, downloaded);
    for (const el of $$(`[data-status="${gameId}"]`)) el.innerHTML = statusHtml(st);
    for (const el of $$(`[data-progress="${gameId}"]`)) el.outerHTML = cardBarsHtml(gameId, st);
    for (const el of $$(`[data-card="${gameId}"]`)) el.classList.toggle('remote', st.kind !== 'downloaded');
    if (S.route.name === 'game' && S.route.gameId === gameId) renderGame();
    if (S.route.name === 'games' && S.focusedGameId === gameId) renderHero();
  }

  function findGame(id) {
    return S.games.list.find((g) => g.id === id) || S.search.results.find((g) => g.id === id) || S.localGames.find((g) => g.id === id) || S.favorites.find((g) => g.id === id) || S.detailGame?.id === id && S.detailGame;
  }

  // ---------------------------------------------------------------------------
  // Navigation
  // ---------------------------------------------------------------------------

  function go(route, { replace = false } = {}) {
    if (!replace) S.history.push(S.route);
    S.route = route;
    render();
  }

  function back() {
    // Invité d'un lobby : il ne quitte la page du lobby qu'en quittant le lobby.
    if (lobbyLocked() && S.route.name === 'lobby') return;
    S.route = S.history.pop() || { name: 'systems' };
    render();
  }

  function render() {
    if (lobbyLocked() && S.route.name !== 'lobby') S.route = { name: 'lobby' };
    $('#modalRoot').innerHTML = '';
    $('#banners').innerHTML = '';
    $('#backBtn').classList.toggle('hidden', S.route.name === 'systems');
    document.body.classList.remove('immersive');
    applySystemColor();
    if (S.route.name !== 'game') stopPads();
    $('#main').scrollTop = 0;
    ({ systems: renderSystems, games: renderGames, game: renderGame, settings: renderSettings, lobby: renderLobby })[S.route.name]();
  }

  /** Couleur donnée à la barre de titre de la fenêtre (envoyée seulement quand elle change). */
  let titleColor = null;

  /**
   * Écran des systèmes sur fond noir ; liste des jeux et fiche d'un jeu sur la couleur du système
   * (réglages du serveur), surfaces et bordures éclaircies à partir d'elle.
   */
  function applySystemColor() {
    const { name, systemId } = S.route;
    // Lobby : couleur du système du jeu choisi, sinon fond noir (comme l'écran des systèmes).
    const lobbyColor = name === 'lobby' && S.lobby?.game ? systemById(S.lobby.game.systemId)?.color : null;
    const color = ((name === 'games' || name === 'game') && systemById(systemId)?.color) || lobbyColor;
    document.body.classList.toggle('systems-screen', name === 'systems' || (name === 'lobby' && !lobbyColor));
    const style = document.body.style;
    for (const [prop, light] of [['--bg', 0], ['--surface', 6], ['--surface-2', 12], ['--border', 20]]) {
      if (color && /^#[0-9a-f]{6}$/i.test(color)) style.setProperty(prop, light ? `color-mix(in srgb, ${color}, #fff ${light}%)` : color);
      else style.removeProperty(prop);
    }
    // Barre de titre de la fenêtre : même couleur que le fond affiché.
    const rgb = getComputedStyle(document.body).backgroundColor.match(/\d+(\.\d+)?/g)?.slice(0, 3).map(Number);
    const hex = rgb && `#${rgb.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')}`;
    if (hex && hex !== titleColor && rc.window?.titleColor) {
      titleColor = hex;
      rc.window.titleColor(hex).catch(() => {});
    }
  }

  function setTopbar({ title, subtitle = '', center = '', right = '', disk = false }) {
    $('#title').textContent = title;
    renderDiskBar(disk ? S.usage.disk : null);
    $('#subtitle').textContent = subtitle;
    $('#topbarCenter').innerHTML = center;
    $('#topbarRight').innerHTML = right;
    lobbyTopbarButton();
  }

  // Barre d'espace de la partition des ROMs : place occupée, espace libre (rouge sous 10 %).
  function renderDiskBar(disk) {
    const bar = $('#diskBar');
    bar.classList.toggle('hidden', !disk);
    if (!disk) return;
    const used = 1 - disk.free / disk.total;
    bar.classList.toggle('low', disk.free / disk.total < 0.1);
    bar.title = t('disk.tooltip', { free: formatSize(disk.free), total: formatSize(disk.total), dir: S.settings.romsDir });
    bar.innerHTML = `<div class="track"><div class="fill" style="width:${Math.round(used * 100)}%"></div></div><span>${esc(t('disk.free', { size: formatSize(disk.free) }))}</span>`;
  }

  // Place occupée par les jeux de chaque système sur le PC et espace de la partition, puis
  // mise à jour de l'écran des systèmes s'il est affiché.
  async function loadUsage() {
    const usage = await call(rc.library.usage, S.systems).catch(() => null);
    if (!usage) return;
    S.usage = usage;
    if (S.route.name === 'systems') {
      renderDiskBar(usage.disk);
      if (!S.search.query.trim()) renderSystemsBody();
    }
  }

  function banner(text, error = false) {
    $('#banners').insertAdjacentHTML('beforeend', `<div class="banner${error ? ' error' : ''}">${esc(text)}</div>`);
  }

  // Bouton de diffusion sur un Chromecast, placé avant celui des paramètres sur chaque écran.
  const castBtnInner = () => icon(S.cast.status === 'idle' ? 'cast' : 'castOn');
  const castBtnTitle = () => (S.cast.status === 'idle' ? t('cast.button') : t('cast.active', { name: S.cast.device?.name || '' }));
  // Bouton du profil (à gauche de celui des paramètres) : coloré quand un joueur est connecté.
  const profileTitle = () => (S.account.signedIn ? t('profile.signedInAs', { name: S.account.username }) : t('profile.button'));
  const profileBtn = () => `<button class="icon-btn${S.account.signedIn ? ' signed-in' : ''}" data-action="profile" title="${esc(profileTitle())}">${icon('user')}</button>`;
  // Pastille « hors ligne », placée avant ces boutons sur chaque écran.
  const offlineTitle = () => [t('offline.badge'), S.connectivity.pending ? t('offline.pending', { n: S.connectivity.pending }) : ''].filter(Boolean).join(' ');
  const offlineBadge = () => `<button class="offline-badge${S.connectivity.online ? ' hidden' : ''}" data-offline data-action="reconnect" title="${esc(offlineTitle())}">${icon('cloudOff', 20)}</button>`;
  const settingsBtn = () => offlineBadge() + `<button class="icon-btn${S.cast.status === 'idle' ? '' : ' casting'}" data-action="cast" title="${esc(castBtnTitle())}">${castBtnInner()}</button>`
    + profileBtn()
    + `<button class="icon-btn" data-action="settings" title="${esc(t('app.settings'))}">${icon('gear')}</button>`;

  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-action]');
    if (el?.dataset.action === 'settings') go({ name: 'settings' });
    if (el?.dataset.action === 'cast') openCast();
    if (el?.dataset.action === 'profile') openProfile();
    if (el?.dataset.action === 'reconnect') reconnect();
  });

  // ---------------------------------------------------------------------------
  // Profil du joueur : connexion, temps de jeu, sauvegardes en ligne
  // ---------------------------------------------------------------------------

  /** Durée lisible : « 3 h 05 », « 12 min », « moins d'une minute ». */
  function formatDuration(seconds) {
    const s = Math.round(seconds || 0);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    if (h) return t('profile.hours', { h, m: String(m).padStart(2, '0') });
    return m ? t('profile.minutes', { m }) : t('profile.lessThanMinute');
  }

  /** Temps de jeu du profil pour ce jeu (bannière, fiche) ; vide s'il n'y a jamais joué. */
  function playtimeHtml(game) {
    const seconds = S.playtime[game.id];
    if (!S.account.signedIn || !seconds) return '';
    return `<div class="playtime">${icon('timer', 16)} ${esc(t('profile.playtime', { time: formatDuration(seconds) }))}</div>`;
  }

  function paintProfileButtons() {
    for (const el of $$('[data-action="profile"]')) {
      el.classList.toggle('signed-in', S.account.signedIn);
      el.title = profileTitle();
    }
  }

  /** Profil et temps de jeu relus (connexion, fin de partie) ; l'écran affiché est mis à jour. */
  async function refreshAccount() {
    S.account = await call(rc.account.state).catch(() => ({ signedIn: false }));
    S.playtime = S.account.signedIn ? await call(rc.account.playtime).catch(() => ({})) : {};
    paintProfileButtons();
    if (S.route.name === 'games') renderHero();
    if (S.route.name === 'game' && !$('#modalRoot').innerHTML) renderGame();
  }

  const PLATFORMS = { android: 'Android', androidtv: 'Android TV', windows: 'Windows' };

  /**
   * Statistiques du profil : totaux, jeux téléchargés et temps de jeu par appareil, jeux les plus
   * joués et dernières parties (dates du serveur en UTC, affichées à l'heure du PC).
   */
  function profileStatsHtml(stats) {
    const date = (utc) => new Date(`${utc.replace(' ', 'T')}Z`).toLocaleString(window.I18N.language, { dateStyle: 'medium', timeStyle: 'short' });
    const line = (title, detail) => `<li><strong>${esc(title)}</strong><span class="muted small">${esc(detail)}</span></li>`;
    const join = (...parts) => parts.filter(Boolean).join(' · ');
    const { totals } = stats;
    const figure = (value, label) => `<div class="stat-figure"><strong>${esc(value)}</strong><span class="muted small">${esc(label)}</span></div>`;
    return `<h4>${esc(t('stats.title'))}</h4>
      <div class="stat-figures">
        ${figure(formatDuration(totals.seconds), t('stats.playtime'))}
        ${figure(String(totals.sessions), t('stats.sessions'))}
        ${figure(String(totals.gamesPlayed), t('stats.gamesPlayed'))}
        ${figure(String(totals.gamesDownloaded), t('stats.gamesDownloaded'))}
      </div>
      ${stats.devices.length ? `<h4>${esc(t('stats.devices'))}</h4><ul class="stat-list">${stats.devices.map((d) => line(
        join(d.device || t('stats.unknownDevice'), PLATFORMS[d.platform] || d.platform),
        join(t('stats.deviceGames', { n: d.games }), d.seconds > 0 && t('stats.devicePlaytime', { time: formatDuration(d.seconds) })),
      )).join('')}</ul>` : ''}
      ${stats.topGames.length ? `<h4>${esc(t('stats.topGames'))}</h4><ul class="stat-list">${stats.topGames.map((g) => line(
        g.title || `#${g.gameId}`, join(formatDuration(g.seconds), t('stats.gameSessions', { n: g.sessions })),
      )).join('')}</ul>` : ''}
      <h4>${esc(t('stats.history'))}</h4>
      ${stats.history.length ? `<ul class="stat-list">${stats.history.slice(0, 20).map((h) => line(
        h.title || `#${h.gameId}`, join(date(h.endedAt), h.device, formatDuration(h.seconds)),
      )).join('')}</ul>` : `<p class="muted small">${esc(t('stats.historyEmpty'))}</p>`}`;
  }

  function openProfile(tab = 'login') {
    if (S.account.signedIn) {
      const a = S.account;
      const avatars = a.avatars || [];
      const root = modal({
        title: t('profile.title'),
        body: `<div class="profile-head">${a.avatar ? `<span class="avatar big">${esc(a.avatar)}</span>` : icon('user', 40)}<div><h4>${esc(a.username)}</h4>
            ${a.offline ? `<span class="muted">${esc(t('profile.offline'))}</span>`
            : `<span class="muted">${esc(a.playSeconds ? t('profile.stats', { time: formatDuration(a.playSeconds), n: a.gamesPlayed }) : t('profile.noPlay'))}</span>`}</div></div>
          ${avatars.length ? `<h4>${esc(t('profile.avatar'))}</h4>
          <p class="muted small">${esc(t('profile.avatarHint'))}</p>
          <div class="avatar-grid">
            <button class="avatar-choice${a.avatar ? '' : ' selected'}" data-avatar="" title="${esc(t('profile.noAvatar'))}">${icon('user', 22)}</button>
            ${avatars.map((v) => `<button class="avatar-choice${v === a.avatar ? ' selected' : ''}" data-avatar="${esc(v)}">${esc(v)}</button>`).join('')}
          </div>` : ''}
          <p class="muted">${esc(t('profile.cloudHint'))}</p>
          <div id="profileStats" class="profile-stats"></div>`,
        buttons: [
          { label: t('profile.logout'), kind: 'danger', left: true, onClick: async () => { await call(rc.account.logout); toast(t('profile.loggedOut')); } },
          { label: t('app.close'), kind: 'primary' },
        ],
      });
      $('.modal', root).classList.add('wide');
      call(rc.account.stats).then((stats) => {
        const el = $('#profileStats', root);
        if (stats && el) el.innerHTML = profileStatsHtml(stats);
      }).catch(() => {});
      // Avatar choisi : enregistré sur le serveur, affiché à côté du nom dans les lobbies.
      for (const el of $$('[data-avatar]', root)) {
        el.onclick = async () => {
          try {
            S.account = await call(rc.account.setAvatar, el.dataset.avatar || null);
            openProfile();
          } catch (err) {
            toast(err.message, { type: 'error' });
          }
        };
      }
      return;
    }
    if (!S.settings.serverUrl) return toast(t('app.configure'), { type: 'error' });
    const creating = tab === 'register';
    const root = modal({
      title: t('profile.title'),
      body: `<div class="line">${['login', 'register'].map((k) => `<button class="chip${k === tab ? ' selected' : ''}" data-profile-tab="${k}">${esc(t(`profile.${k}`))}</button>`).join('')}</div>
        <p class="muted">${esc(t(creating ? 'profile.registerHint' : 'profile.loginHint'))}</p>
        <form id="profileForm" class="profile-form">
          <label>${esc(t('profile.username'))}<input type="text" id="profileUser" autocomplete="username" maxlength="32"></label>
          <label>${esc(t('profile.password'))}<input type="password" id="profilePass" autocomplete="${creating ? 'new-password' : 'current-password'}"></label>
          ${creating ? `<label>${esc(t('profile.confirm'))}<input type="password" id="profilePass2" autocomplete="new-password"></label>` : ''}
          <p class="bad hidden" id="profileError"></p>
          <button type="submit" class="hidden"></button>
        </form>`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost' },
        { label: t(creating ? 'profile.create' : 'profile.signIn'), kind: 'primary', keepOpen: true, onClick: () => submit() },
      ],
    });
    for (const el of $$('[data-profile-tab]', root)) el.onclick = () => openProfile(el.dataset.profileTab);
    $('#profileUser', root).focus();
    $('#profileForm', root).onsubmit = (e) => {
      e.preventDefault();
      submit();
    };
    async function submit() {
      const username = $('#profileUser', root).value.trim();
      const password = $('#profilePass', root).value;
      const error = $('#profileError', root);
      const fail = (message) => {
        error.textContent = message;
        error.classList.remove('hidden');
      };
      if (!username || !password) return fail(t('profile.required'));
      if (creating && password !== $('#profilePass2', root).value) return fail(t('profile.mismatch'));
      try {
        await call(creating ? rc.account.register : rc.account.login, username, password);
      } catch (err) {
        return fail(err.message);
      }
      root.innerHTML = '';
      toast(t(creating ? 'profile.created' : 'profile.welcome', { name: username }));
    }
  }

  rc.account.onUpdate(() => {
    refreshAccount();
    loadFavorites(); // connexion (favoris du profil), déconnexion
    call(rc.connectivity.state).then(paintConnectivity).catch(() => {});
  });

  // ---------------------------------------------------------------------------
  // Connexion au serveur : pastille « hors ligne », synchronisation au retour
  // ---------------------------------------------------------------------------

  function paintConnectivity(state) {
    S.connectivity = { online: state.online, pending: state.pending || 0 };
    for (const el of $$('[data-offline]')) {
      el.classList.toggle('hidden', state.online);
      el.title = offlineTitle();
    }
  }

  /** Écran affiché redessiné après un changement de connexion. */
  function rerenderLists() {
    if (S.route.name === 'systems') renderSystems();
    else if (S.route.name === 'games') renderGames();
  }

  /** Pastille « hors ligne » touchée : le serveur est sondé ; joignable, l'application repasse en ligne. */
  async function reconnect() {
    const badges = $$('[data-offline]');
    if (badges.some((b) => b.classList.contains('checking'))) return;
    for (const b of badges) b.classList.add('checking');
    try {
      const state = await call(rc.connectivity.check);
      if (!state.online) toast(t('offline.still'), { type: 'error' });
    } catch {
      toast(t('offline.still'), { type: 'error' });
    } finally {
      for (const b of $$('[data-offline]')) b.classList.remove('checking');
    }
  }

  /**
   * Sauvegardes faites hors ligne (retour en ligne, ouverture de l'application) : envoi proposé. Ne
   * sont pas envoyées celles qui ne seraient pas gardées (assez d'états plus récents en ligne pour le
   * jeu) ou qui écraseraient une sauvegarde plus récente du serveur.
   */
  let askingUpload = false;
  let uploadingPending = false;
  function askUploadPending(n) {
    if (askingUpload || uploadingPending || !n || !S.connectivity.online) return;
    askingUpload = true;
    const done = () => (askingUpload = false);
    modal({
      title: t('offline.uploadTitle'),
      body: `<p>${esc(t('offline.uploadText', { n }))}</p><p class="muted">${esc(t('offline.uploadRule'))}</p>`,
      buttons: [
        { label: t('offline.uploadLater'), kind: 'ghost', onClick: done },
        {
          label: t('offline.uploadSend'),
          kind: 'primary',
          onClick: async () => {
            uploadingPending = true;
            try {
              const r = await call(rc.account.uploadPending);
              if (r.sent) toast(t('offline.synced', { n: r.sent }));
              if (r.skipped) toast(t('offline.uploadSkipped', { n: r.skipped }));
              paintConnectivity(await call(rc.connectivity.state));
            } catch (e) {
              toast(e.message, { type: 'error' });
            } finally {
              uploadingPending = false;
            }
          },
        },
      ],
    });
    // Fermée sans choisir (clic à côté) : comme « Plus tard ».
    const root = $('#modalRoot');
    const watch = new MutationObserver(() => {
      if (root.innerHTML) return;
      watch.disconnect();
      done();
    });
    watch.observe(root, { childList: true });
  }

  rc.connectivity.onUpdate(async (state) => {
    const wasOnline = S.connectivity.online;
    paintConnectivity(state);
    if (state.online) askUploadPending(state.pendingSaves);
    if (wasOnline === state.online) return;
    if (state.online) {
      // Listes complètes relues sur le serveur.
      toast(t('offline.back'));
      await loadSystems();
      if (S.games.systemId) await loadGames();
      rerenderLists();
    } else {
      // Sans attendre une nouvelle requête : seuls les jeux du PC restent proposés.
      toast(t('offline.lost'), { type: 'error' });
      S.systemsOffline = true;
      S.games.offline = true;
      if (S.search.query) S.search.offline = true;
      await refreshLocalSystems();
      rerenderLists();
    }
  });


  // ---------------------------------------------------------------------------
  // Moteur intégré arrêté sur une erreur : réinitialiser le cœur
  // ---------------------------------------------------------------------------

  /** Supprime le cœur (téléchargé à nouveau au lancement suivant) et ses options pour ce système. */
  async function resetCore(systemId, core) {
    try {
      await call(rc.player.resetCore, systemId, core);
      toast(t('crash.resetDone', { core }), { duration: 7000 });
    } catch (err) {
      toast(err.message, { type: 'error' });
    }
    if (S.route.name === 'game') renderGame();
  }

  /** Rapport d'erreur (paramètres) : description et journaux, envoyés au serveur après confirmation (gardés hors ligne). */
  function showReport() {
    const root = modal({
      title: t('report.title'),
      body: `<p class="muted">${esc(t('report.confirm'))}</p>
        <label>${esc(t('report.description'))}<textarea id="reportText" rows="4"></textarea></label>
        <label class="check"><input type="checkbox" id="reportLogs" checked> ${esc(t('report.withLogs'))}</label>`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost' },
        {
          label: t('report.confirmSend'),
          kind: 'primary',
          keepOpen: true,
          onClick: async (close) => {
            for (const btn of $$('.foot .btn', root)) btn.disabled = true;
            try {
              const result = await call(rc.account.sendReport, $('#reportText', root).value, $('#reportLogs', root).checked);
              toast(t(result.sent ? 'report.sent' : 'report.queued'));
              close();
            } catch (err) {
              toast(err.message, { type: 'error' });
              for (const btn of $$('.foot .btn', root)) btn.disabled = false;
            }
          },
        },
      ],
    });
    $('#reportText', root).focus();
  }

  function confirmResetCore(systemId, core) {
    modal({
      title: t('crash.resetTitle', { core }),
      body: `<p>${esc(t('crash.resetText', { core }))}</p>`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost' },
        { label: t('crash.reset'), kind: 'danger', onClick: () => resetCore(systemId, core) },
      ],
    });
  }

  rc.player.onCrash((crash) => {
    modal({
      title: t('crash.title'),
      body: `<p>${esc(t('crash.text', { game: crash.game.title || '', core: crash.core, code: crash.code > 255 ? `0x${(crash.code >>> 0).toString(16).toUpperCase()}` : crash.code }))}</p>
        <p class="muted">${esc(t('crash.hint'))}</p>
        ${crash.log ? `<details><summary class="muted">${esc(t('crash.log'))}</summary><pre class="mono crash-log">${esc(crash.log)}</pre></details>` : ''}`,
      buttons: [
        { label: t('app.close'), kind: 'ghost' },
        { label: t('crash.reset'), kind: 'primary', onClick: () => resetCore(crash.system.id, crash.core) },
      ],
    });
  });

  // ---------------------------------------------------------------------------
  // Diffusion de l'écran sur un Chromecast
  // ---------------------------------------------------------------------------

  function paintCastButtons() {
    for (const el of $$('[data-action="cast"]')) {
      el.innerHTML = castBtnInner();
      el.title = castBtnTitle();
      el.classList.toggle('casting', S.cast.status !== 'idle');
    }
  }

  function openCast() {
    if (S.cast.status !== 'idle') {
      modal({
        title: t('cast.title'),
        body: `<p>${esc(t(S.cast.status === 'casting' ? 'cast.active' : 'cast.connecting', { name: S.cast.device?.name || '' }))}</p>
          <p class="muted">${esc(t('cast.hint'))}</p>`,
        buttons: [
          { label: t('app.close'), kind: 'ghost' },
          { label: t('cast.stop'), kind: 'primary', onClick: () => call(rc.cast.stop).catch(() => {}) },
        ],
      });
      return;
    }
    const root = modal({
      title: t('cast.title'),
      body: `<div id="castDevices"></div><p class="muted">${esc(t('cast.hint'))}</p>`,
      buttons: [
        { label: t('cast.search'), kind: 'ghost', left: true, keepOpen: true, onClick: () => scan() },
        { label: t('app.cancel'), kind: 'ghost' },
      ],
    });
    async function scan() {
      const list = $('#castDevices', root);
      if (!list) return;
      list.innerHTML = `<p>${esc(t('cast.searching'))}</p>`;
      let devices = [];
      try {
        devices = await call(rc.cast.discover);
      } catch (err) {
        list.innerHTML = `<p class="bad">${esc(err.message)}</p>`;
        return;
      }
      if (!$('#castDevices', root)) return; // fenêtre fermée pendant la recherche
      if (!devices.length) {
        list.innerHTML = `<p>${esc(t('cast.none'))}</p>`;
        return;
      }
      list.innerHTML = `<div class="cast-devices">${devices.map((d, i) => `<button class="btn cast-device" data-device="${i}">
        ${icon('cast', 18)}<span><b>${esc(d.name)}</b>${d.model ? `<span class="muted"> · ${esc(d.model)}</span>` : ''}</span></button>`).join('')}</div>`;
      for (const el of $$('[data-device]', list)) {
        el.onclick = () => {
          root.innerHTML = '';
          startCast(devices[Number(el.dataset.device)]);
        };
      }
    }
    scan();
  }

  async function startCast(device) {
    toast(t('cast.connecting', { name: device.name }));
    try {
      await call(rc.cast.start, device);
      toast(t('cast.started', { name: device.name }));
    } catch (err) {
      toast(err.message, { type: 'error', duration: 12000 });
    }
  }

  rc.cast.onUpdate((state) => {
    const was = S.cast.status;
    S.cast = state;
    paintCastButtons();
    if (state.error && state.status === 'idle' && was !== 'idle') toast(errorText(state.error), { type: 'error', duration: 12000 });
    else if (was === 'casting' && state.status === 'idle') toast(t('cast.stopped'));
  });

  // ---------------------------------------------------------------------------
  // Systèmes et recherche globale
  // ---------------------------------------------------------------------------

  async function loadSystems() {
    S.systemsLoading = true;
    try {
      const r = await call(rc.api.systems);
      S.systems = r.data;
      S.systemsOffline = r.offline;
      S.systemsError = null;
      if (r.offline) await refreshLocalSystems();
    } catch (err) {
      S.systemsError = err.message;
    }
    S.systemsLoading = false;
    if (S.route.name === 'systems') renderSystems();
  }

  async function refreshLocalSystems() {
    S.localSystems = new Set(await call(rc.library.systemsWithGames, S.systems).catch(() => []));
  }

  /** Systèmes proposés : hors ligne, seulement ceux dont des jeux sont sur le PC. */
  // Lobby : seulement les systèmes que tous ses membres savent jouer en réseau (émulateur intégré).
  const visibleSystems = () => (S.systemsOffline ? S.systems.filter((s) => S.localSystems.has(s.id)) : S.systems)
    .filter((s) => !S.lobby || lobbySystem(s.id));

  let searchTimer = null;
  function setSearch(query) {
    S.search.query = query;
    clearTimeout(searchTimer);
    if (!query.trim()) {
      S.search = { query: '', results: [], downloaded: new Set(), offline: false, loading: false, error: null };
      renderSystemsBody();
      return;
    }
    S.search.loading = true;
    searchTimer = setTimeout(runSearch, 350);
  }

  async function runSearch() {
    const query = S.search.query.trim();
    try {
      const r = await call(rc.api.search, query);
      if (S.search.query.trim() !== query) return;
      const ids = await call(rc.library.downloaded, S.systems, r.data);
      S.search = { ...S.search, results: r.data, downloaded: new Set(ids), offline: r.offline, loading: false, error: null };
    } catch (err) {
      S.search = { ...S.search, loading: false, error: err.message };
    }
    if (S.route.name === 'systems') renderSystemsBody();
  }

  function renderSystems() {
    loadUsage();
    loadLocalGames();
    loadFavorites();
    setTopbar({
      title: 'RomCloud',
      disk: true,
      subtitle: S.systems.length ? t('app.games', { n: S.systems.reduce((n, s) => n + s.gameCount, 0) }) : '',
      center: `<input type="search" class="search-box" id="globalSearch" placeholder="${esc(t('app.searchAll'))}" value="${esc(S.search.query)}">`,
      right: `${multiplayerBtn()}<button class="icon-btn" id="refreshBtn" title="${esc(t('app.refresh'))}">${icon('refresh')}</button>${settingsBtn()}`,
    });
    $('#netplayBtn')?.addEventListener('click', showMultiplayer);
    $('#globalSearch').oninput = (e) => setSearch(e.target.value);
    $('#refreshBtn').onclick = () => {
      loadSystems();
      if (S.search.query) runSearch();
    };
    renderSystemsBody();
  }

  function renderSystemsBody() {
    const main = $('#main');
    $('#banners').innerHTML = '';
    if (!S.settings.serverUrl) {
      main.innerHTML = `<div class="centered"><p>${esc(t('app.configure'))}</p><button class="btn primary" data-action="settings">${esc(t('app.settings'))}</button></div>`;
      return;
    }
    if (S.search.query.trim()) return renderSearchResults();
    if (S.systemsOffline) banner(t('app.offline'));
    const systems = visibleSystems();
    if (!systems.length) {
      const text = S.systemsLoading ? t('app.loading') : S.systemsError ? t('app.unreachable', { detail: S.systemsError }) : t('app.noSystems');
      main.innerHTML = `<div class="centered"><p>${esc(text)}</p>${S.systemsError ? `<button class="btn" data-action="settings">${esc(t('app.settings'))}</button>` : ''}</div>`;
      return;
    }
    // Jeux favoris puis téléchargés, tous systèmes confondus (comme la recherche, avec le logo du
    // système) ; hors ligne : favoris téléchargés seulement.
    const local = S.localGames.filter((g) => systemById(g.systemId) && fitsLobby(g));
    const localIds = new Set(S.localGames.map((g) => g.id));
    const favorites = S.favorites.filter((g) => systemById(g.systemId) && fitsLobby(g) && (!S.systemsOffline || localIds.has(g.id)));
    const gamesRow = (cls, title, games) => games.length ? `<section class="row ${cls}"><h3>${esc(title)} <span class="count">· ${games.length}</span></h3>
      <div class="row-track">${games.map((g) => cardHtml(g, localIds, { badge: true })).join('')}</div></section>` : '';
    main.innerHTML = `${gamesRow('favorite-row', t('row.favorites'), favorites)}${gamesRow('local-row', t('row.downloaded'), local)}<div class="page"><div class="systems-grid">${systems.map((s) => {
      const img = systemImageUrl(s);
      const color = /^#[0-9a-f]{6}$/i.test(s.color || '') ? ` style="--card:${s.color}"` : '';
      return `<div class="system-card" data-system="${esc(s.id)}"${color}>
        <div class="img">${img ? `<img src="${esc(img)}" alt="">` : `<span class="short">${esc(s.shortname.toUpperCase())}</span>`}</div>
        <div class="info"><div class="name">${esc(s.name)}</div>
        <div class="muted">${esc(t('app.games', { n: s.gameCount }))} · ${esc(formatSize(s.totalSize))}</div>
        ${S.usage.systems[s.id] ? `<div class="small on-pc">${esc(t('app.onPc', { size: formatSize(S.usage.systems[s.id]) }))}</div>` : ''}</div>
      </div>`;
    }).join('')}</div></div>`;
    for (const el of $$('[data-system]', main)) el.onclick = () => openSystem(el.dataset.system);
    for (const [cls, games] of [['favorite-row', favorites], ['local-row', local]]) {
      const row = $(`.${cls}`, main);
      if (!row) continue;
      bindCards(row, games, (g) => openGameDetail(g.systemId, g.id));
      bindTrackDrag($('.row-track', row));
    }
  }

  /** Jeux favoris (rangée de la page des systèmes) : ceux du profil, sinon ceux de ce PC ; réaffichée s'ils changent. */
  async function loadFavorites() {
    const games = await call(rc.account.favorites).catch(() => S.favorites);
    showFavorites(games);
  }

  function showFavorites(games) {
    const sig = (list) => JSON.stringify(list.map((g) => [g.id, g.updatedAt, g.hasBoxart, g.title]));
    const same = sig(games) === sig(S.favorites);
    S.favorites = games;
    if (!same && S.route.name === 'systems' && !S.search.query.trim()) renderSystemsBody();
  }

  /** Ajoute le jeu aux favoris, ou l'en retire (fiche du jeu). */
  async function toggleFavorite(game) {
    const favorite = !S.favorites.some((g) => g.id === game.id);
    try {
      showFavorites(await call(rc.account.setFavorite, game, favorite));
    } catch (err) {
      toast(t('detail.favoriteNotSaved', { detail: err.message }), { type: 'error' });
    }
    if (S.route.name === 'game') renderGame();
  }

  /**
   * Jeux téléchargés sur le PC (rangée de la page des systèmes), réaffichée si elle change (jeux,
   * ordre, ou fiche : jaquette, titre) ; puis fiches relues sur le serveur, rangée mise à jour.
   */
  async function loadLocalGames() {
    const show = async () => {
      const games = await call(rc.library.downloadedGames).catch(() => []);
      const sig = (list) => JSON.stringify(list.map((g) => [g.id, g.updatedAt, g.hasBoxart, g.title]));
      const same = sig(games) === sig(S.localGames);
      S.localGames = games;
      if (!same && S.route.name === 'systems' && !S.search.query.trim()) renderSystemsBody();
    };
    await show();
    if (await call(rc.library.refreshDownloaded).catch(() => false)) await show();
  }

  function renderSearchResults() {
    const main = $('#main');
    const s = S.search;
    if (s.offline) banner(t('app.searchOffline'));
    // Hors ligne : seuls les jeux présents sur le PC peuvent être lancés.
    const results = (s.offline ? s.results.filter((g) => s.downloaded.has(g.id)) : s.results).filter(fitsLobby);
    if (!results.length) {
      const text = s.loading ? t('app.loading') : s.error ? s.error : t('app.searchNone', { q: s.query });
      main.innerHTML = `<div class="centered"><p>${esc(text)}</p></div>`;
      return;
    }
    main.innerHTML = `<div class="page"><p class="muted">${esc(t('app.searchResults', { n: results.length, q: s.query }))}</p>
      <div class="cards-grid">${results.map((g) => cardHtml(g, s.downloaded, { badge: true })).join('')}</div></div>`;
    bindCards(main, results, (g) => openGameDetail(g.systemId, g.id));
  }

  // ---------------------------------------------------------------------------
  // Cartes et lignes de jeux
  // ---------------------------------------------------------------------------

  function cardHtml(game, downloaded, { badge = false } = {}) {
    const st = statusOf(game, downloaded);
    const cover = mediaUrl(game, 'boxart', 'thumb');
    const system = systemById(game.systemId);
    const logo = systemImageUrl(system);
    return `<div class="game-card${st.kind === 'downloaded' ? '' : ' remote'}" data-card="${game.id}">
      <div class="cover">
        ${cover ? `<img src="${esc(cover)}" alt="" loading="lazy">` : esc(game.title)}
        <div class="overlay status" data-status="${game.id}">${statusHtml(st)}</div>
        <button class="overlay info-btn" data-info="${game.id}" title="${esc(t('action.details'))}">${icon('info', 18)}</button>
        ${badge && system ? `<div class="overlay badge"${/^#[0-9a-f]{6}$/i.test(system.color || '') ? ` style="background:${system.color}"` : ''}>${logo ? `<img src="${esc(logo)}" alt="${esc(system.name)}">` : esc(system.shortname.toUpperCase())}</div>` : ''}
        ${cardBarsHtml(game.id, st)}
      </div>
      <div class="title">${esc(game.title)}${multiBadge(game)}</div>
      <div class="meta">${esc([year(game), partsSummary(game), sizeLabel(game)].filter(Boolean).join(' · '))}</div>
    </div>`;
  }

  /**
   * Clic : [onClick] (par défaut la fiche du jeu) ; bouton ⓘ ou clic droit : fiche du jeu
   * (jouer / télécharger depuis la fiche ou la bannière).
   */
  function bindCards(root, games, onDetails, onClick = onDetails) {
    const byId = new Map(games.map((g) => [g.id, g]));
    for (const el of $$('[data-card]', root)) {
      const game = byId.get(Number(el.dataset.card));
      el.onclick = (e) => (e.target.closest('[data-info]') ? onDetails(game) : onClick(game));
      el.oncontextmenu = (e) => {
        e.preventDefault();
        onDetails(game);
      };
    }
  }

  /** Jeu du carrousel affiché dans la bannière ; sa carte est encadrée. */
  function selectGame(game) {
    S.focusedGameId = game.id;
    renderHero();
    for (const el of $$('.game-card[data-card]', $('#main'))) el.classList.toggle('selected', Number(el.dataset.card) === game.id);
  }

  // ---------------------------------------------------------------------------
  // Jeux d'un système
  // ---------------------------------------------------------------------------

  function openSystem(systemId) {
    if (S.games.systemId !== systemId) {
      S.games = { systemId, list: [], downloaded: new Set(), resumable: new Set(), offline: false, loading: true, error: null, filter: 'all', query: '', criteria: {} };
      S.focusedGameId = null;
    }
    go({ name: 'games', systemId });
    loadGames();
  }

  async function loadGames() {
    const systemId = S.games.systemId;
    S.games.loading = true;
    try {
      const r = await call(rc.api.games, systemId);
      const ids = await call(rc.library.downloaded, S.systems, r.data);
      Object.assign(S.games, { list: r.data, offline: r.offline, downloaded: new Set(ids), error: null });
      await refreshResumable();
      await refreshNetplaySystem(systemById(systemId), true);
    } catch (err) {
      S.games.error = err.message;
    }
    S.games.loading = false;
    if (S.route.name !== 'games' || S.route.systemId !== systemId) return;
    // Barre du haut aussi : bouton « Filtres » et nombre de jeux dépendent de la liste chargée.
    const typing = document.activeElement?.id === 'gameSearch';
    renderGames();
    if (typing) $('#gameSearch')?.focus();
  }

  /** Jeux téléchargés du système affiché avec une partie sauvegardée (moteur intégré) : « Reprendre ». */
  async function refreshResumable() {
    const system = systemById(S.games.systemId);
    const games = S.games.list.filter((g) => S.games.downloaded.has(g.id));
    S.games.resumable = new Set(system && games.length ? await call(rc.launcher.resumableIds, system, games).catch(() => []) : []);
  }

  async function refreshDownloaded() {
    if (S.games.list.length) {
      S.games.downloaded = new Set(await call(rc.library.downloaded, S.systems, S.games.list));
      await refreshResumable();
    }
    if (S.search.results.length) S.search.downloaded = new Set(await call(rc.library.downloaded, S.systems, S.search.results));
    loadLocalGames();
  }

  function visibleGames() {
    const q = S.games.query.trim().toLowerCase();
    return S.games.list.filter((g) => {
      if (!fitsLobby(g)) return false; // lobby : jeux à assez de joueurs pour ses membres
      if (q && !g.title.toLowerCase().includes(q) && !g.fileName.toLowerCase().includes(q)) return false;
      if (!Criteria.matches(g, S.games.criteria)) return false;
      if (S.games.offline) return S.games.downloaded.has(g.id); // hors ligne : jeux du PC seulement
      if (S.games.filter === 'downloaded') return S.games.downloaded.has(g.id);
      if (S.games.filter === 'remote') return !S.games.downloaded.has(g.id);
      return true;
    });
  }

  /** Rangées façon Netflix (même règle que l'application Android). */
  function carouselRows(games, downloaded) {
    const rows = [];
    const dl = games.filter((g) => downloaded.has(g.id));
    if (dl.length) rows.push({ title: t('row.downloaded'), games: dl });
    if (games.length > 20 && games.some((g) => g.addedAt)) {
      rows.push({ title: t('row.recent'), games: [...games].sort((a, b) => (b.addedAt || '').localeCompare(a.addedAt || '')).slice(0, 20) });
    }
    const other = t('row.other');
    const byGenre = new Map();
    for (const g of games) {
      const key = mainGenre(g) || other;
      byGenre.set(key, [...(byGenre.get(key) || []), g]);
    }
    [...byGenre.entries()]
      .sort((a, b) => (a[0] === other) - (b[0] === other) || b[1].length - a[1].length)
      .forEach(([genre, list]) => rows.push({ title: byGenre.size === 1 && genre === other ? t('row.all') : genre, games: list }));
    return rows;
  }

  function featured(games) {
    const by = (list) => list.sort((a, b) => (b.addedAt || '').localeCompare(a.addedAt || ''))[0];
    return games.find((g) => g.id === S.focusedGameId)
      || by(games.filter((g) => g.hasScreenshot && S.games.downloaded.has(g.id)))
      || by(games.filter((g) => g.hasScreenshot))
      || by(games.filter((g) => g.hasBoxart))
      || games[0];
  }

  // ---------------------------------------------------------------------------
  // Recherche avancée (genre, décennie, joueurs, région, note, éditeur)
  // ---------------------------------------------------------------------------

  /** Bouton « Filtres » de la barre du haut (absent si les jeux n'ont aucune information). */
  function criteriaChip() {
    if (Criteria.isEmpty(Criteria.facets(S.games.list))) return '';
    const n = Criteria.count(S.games.criteria);
    return `<button class="chip${n ? ' selected' : ''}" id="criteriaBtn">${icon('filter', 16)} ${esc(n ? t('criteria.count', { n }) : t('criteria.title'))}</button>`;
  }

  /** Libellé d'une valeur de critère. */
  function criteriaLabel(key, value) {
    if (key === 'decade') return t('criteria.decadeValue', { n: value });
    if (key === 'players') return t(`criteria.players.${value}`);
    if (key === 'minRating') return t('criteria.ratingValue', { n: value });
    return value;
  }

  /** Fenêtre « Filtres » : une rangée de puces par critère ; un appui choisit, un second retire. */
  function openCriteria() {
    const facets = Criteria.facets(S.games.list);
    const c = S.games.criteria;
    const sections = Criteria.KEYS.filter((k) => facets[k].length).map((k) => `<div class="criteria-section">
        <h4>${esc(t(`criteria.${k}`))}</h4>
        <div class="criteria-values">${facets[k].map((v, i) => `<button class="chip${c[k] === v ? ' selected' : ''}" data-key="${k}" data-index="${i}">${esc(criteriaLabel(k, v))}</button>`).join('')}</div>
      </div>`).join('');
    const update = (next) => {
      S.games.criteria = next;
      renderGames();
      openCriteria();
    };
    const root = modal({
      title: t('criteria.title'),
      body: sections,
      buttons: [
        { label: t('criteria.reset'), kind: 'ghost', left: true, keepOpen: true, onClick: () => update({}) },
        { label: t('app.close'), kind: 'primary' },
      ],
    });
    for (const el of $$('[data-key]', root)) {
      el.onclick = () => {
        const key = el.dataset.key;
        const value = facets[key][Number(el.dataset.index)];
        update({ ...c, [key]: c[key] === value ? undefined : value });
      };
    }
  }

  function renderGames() {
    const system = systemById(S.route.systemId);
    const f = S.games.filter;
    const view = S.settings.view;
    setTopbar({
      title: system?.name || '',
      subtitle: t('games.subtitle', { n: S.games.list.length, d: S.games.downloaded.size }),
      center: `<div class="games-toolbar">
        ${(S.games.offline ? [] : ['all', 'downloaded', 'remote']).map((k) => `<button class="chip${f === k ? ' selected' : ''}" data-filter="${k}">${esc(t(`filter.${k}`))}</button>`).join('')}
        ${criteriaChip()}
        <input type="search" class="search-box" id="gameSearch" placeholder="${esc(t('games.search'))}" value="${esc(S.games.query)}" style="width:240px">
      </div>`,
      right: `<button class="icon-btn" id="viewBtn" title="${esc(t(view === 'list' ? 'games.carousel' : 'games.list'))}">${icon(view === 'list' ? 'carousel' : 'list')}</button>
        <button class="icon-btn" id="refreshBtn" title="${esc(t('app.refresh'))}">${icon('refresh')}</button>${settingsBtn()}`,
    });
    for (const el of $$('[data-filter]')) {
      el.onclick = () => {
        S.games.filter = el.dataset.filter;
        renderGames();
      };
    }
    const criteriaBtn = $('#criteriaBtn');
    if (criteriaBtn) criteriaBtn.onclick = openCriteria;
    $('#gameSearch').oninput = (e) => {
      S.games.query = e.target.value;
      renderGamesBody();
    };
    $('#viewBtn').onclick = async () => {
      S.settings = await call(rc.settings.save, { view: view === 'list' ? 'carousel' : 'list' });
      renderGames();
    };
    $('#refreshBtn').onclick = loadGames;
    renderGamesBody();
  }

  function renderGamesBody() {
    const main = $('#main');
    $('#banners').innerHTML = '';
    if (S.games.offline) banner(t('app.offline'));
    $('#subtitle').textContent = t('games.subtitle', { n: S.games.list.length, d: S.games.downloaded.size });
    const games = visibleGames();
    // Carrousel sans bandeau : l'image de fond monte sous la barre du haut.
    document.body.classList.toggle('immersive', games.length > 0 && S.settings.view !== 'list' && !S.games.offline);
    if (!games.length) {
      const text = S.games.loading ? t('app.loading') : S.games.error
        || (S.games.list.length && Criteria.count(S.games.criteria) ? t('criteria.none') : t('games.none'));
      main.innerHTML = `<div class="centered"><p>${esc(text)}</p></div>`;
      return;
    }
    if (S.settings.view === 'list') {
      main.innerHTML = `<div class="page"><div class="list">${games.map((g) => {
        const cover = mediaUrl(g, 'boxart', 'thumb');
        return `<div class="list-row" data-card="${g.id}">
          <div class="thumb">${cover ? `<img src="${esc(cover)}" alt="" loading="lazy">` : ''}</div>
          <div class="main"><div class="t">${esc(g.title)}${multiBadge(g)}</div>
            <div class="muted">${esc([year(g), g.genre, partsSummary(g), sizeLabel(g)].filter(Boolean).join(' · '))}</div></div>
          <div class="status" data-status="${g.id}">${statusHtml(statusOf(g, S.games.downloaded))}</div>
          ${isLobbyHost()
            ? (playKind(g) ? `<button class="btn primary" data-lobby-set="${g.id}">${icon('group', 18)} ${esc(t('lobby.setGame'))}</button>` : '')
            : canResume(g) ? `<button class="btn primary" data-resume="${g.id}">${icon('play', 18)} ${esc(t('action.resume'))}</button>` : ''}
          <button class="icon-btn" data-info="${g.id}" title="${esc(t('action.details'))}">${icon('info')}</button>
        </div>`;
      }).join('')}</div></div>`;
    } else {
      const rows = S.games.query.trim() ? [{ title: S.games.query, games }] : carouselRows(games, S.games.downloaded);
      // Bannière fixe en haut (image, jaquette, nom, description) : les rangées défilent dessous.
      main.innerHTML = `<div class="carousel">
        <div class="carousel-head"><div class="backdrop" id="backdrop"></div><div class="hero" id="hero"></div></div>
        ${rows.map((r) => `<section class="row"><h3>${esc(r.title)} <span class="count">· ${r.games.length}</span></h3>
          <div class="row-track">${r.games.map((g) => cardHtml(g, S.games.downloaded)).join('')}</div></section>`).join('')}
      </div>`;
      bindCarouselMouse(main);
      renderHero();
    }
    const details = (g) => openGameDetail(g.systemId, g.id);
    // Carrousel : clic sur un jeu -> affiché dans la bannière ; liste : fiche du jeu.
    bindCards(main, games, details, S.settings.view === 'list' ? details : selectGame);
    if (S.settings.view !== 'list') {
      // Manette : le jeu sélectionné s'affiche dans la bannière ; A passe à son bouton « Jouer ».
      for (const el of $$('.game-card[data-card]', main)) {
        const game = games.find((g) => g.id === Number(el.dataset.card));
        el.addEventListener('focus', () => S.focusedGameId !== game.id && selectGame(game));
        el.addEventListener('padactivate', (e) => {
          e.preventDefault();
          selectGame(game);
          ($('#heroResume') || $('#heroMain'))?.focus();
        });
      }
    }
    for (const el of $$('[data-resume]', main)) {
      const game = games.find((g) => g.id === Number(el.dataset.resume));
      el.onclick = (e) => {
        e.stopPropagation(); // pas la fiche du jeu (clic sur la ligne)
        playChecked(systemById(game.systemId), game, { resume: true });
      };
    }
    for (const el of $$('[data-lobby-set]', main)) {
      const game = games.find((g) => g.id === Number(el.dataset.lobbySet));
      el.onclick = (e) => {
        e.stopPropagation();
        setLobbyGame(game);
      };
    }
  }

  /**
   * Souris dans le carrousel : chaque cran de molette passe à la rangée suivante ou précédente
   * (alignée sous la bannière fixe) ; un clic maintenu fait glisser une rangée de côté, avec un
   * peu d'élan au lâcher. Un glisser n'ouvre pas le jeu sous le pointeur.
   */
  function bindCarouselMouse(main) {
    const carousel = $('.carousel', main);
    const head = $('.carousel-head', main);
    const rows = $$('.row', main);
    if (!carousel || !rows.length) return;

    let wheelLock = 0;
    carousel.addEventListener('wheel', (e) => {
      if (Math.abs(e.deltaY) < Math.abs(e.deltaX) || e.ctrlKey) return;
      e.preventDefault();
      // Un cran par geste : les pavés tactiles envoient une rafale d'évènements.
      const now = Date.now();
      if (now < wheelLock) return;
      wheelLock = now + 280;
      const top = head ? head.offsetHeight : 0;
      // Rangée en haut de la zone visible (sous la bannière), puis la voisine.
      const offset = (row) => row.offsetTop - top;
      const current = rows.findIndex((row) => offset(row) >= main.scrollTop - 4);
      const index = current === -1 ? rows.length - 1 : current;
      const atRow = Math.abs(offset(rows[index]) - main.scrollTop) <= 4;
      const target = e.deltaY > 0 ? (atRow ? index + 1 : index) : index - 1;
      const next = Math.max(0, Math.min(rows.length - 1, target));
      main.scrollTo({ top: next === 0 && e.deltaY < 0 ? 0 : offset(rows[next]), behavior: 'smooth' });
    }, { passive: false });

    for (const track of $$('.row-track', main)) bindTrackDrag(track);
  }

  /** Clic maintenu dans une rangée : glissée de côté, avec un peu d'élan au lâcher. */
  function bindTrackDrag(track) {
    let drag = null; // { x, left, moved, samples }
    let glide = 0;
    track.addEventListener('dragstart', (e) => e.preventDefault()); // pas de fantôme des jaquettes
    track.addEventListener('pointerdown', (e) => {
      if (e.button !== 0 || e.pointerType !== 'mouse') return;
      cancelAnimationFrame(glide);
      track.classList.remove('gliding');
      drag = { x: e.clientX, left: track.scrollLeft, moved: false, samples: [[e.timeStamp, e.clientX]] };
    });
    track.addEventListener('pointermove', (e) => {
      if (!drag) return;
      const dx = e.clientX - drag.x;
      if (!drag.moved && Math.abs(dx) < 6) return;
      if (!drag.moved) {
        drag.moved = true;
        track.setPointerCapture(e.pointerId);
        track.classList.add('dragging'); // défilement immédiat (pas d'animation) et curseur
      }
      track.scrollLeft = drag.left - dx;
      drag.samples = [...drag.samples, [e.timeStamp, e.clientX]].slice(-5);
    });
    const end = (e) => {
      if (!drag) return;
      const { moved, samples } = drag;
      drag = null;
      if (!moved) return;
      track.releasePointerCapture?.(e.pointerId);
      // Le clic qui suit le relâchement ne doit pas sélectionner la carte sous le pointeur.
      track.addEventListener('click', (c) => { c.stopPropagation(); c.preventDefault(); }, { capture: true, once: true });
      track.classList.remove('dragging');
      track.classList.add('gliding'); // défilement direct pendant l'élan
      // Élan : vitesse des derniers déplacements, amortie.
      const [t0, x0] = samples[0];
      const [t1, x1] = samples[samples.length - 1];
      let speed = t1 > t0 ? (x1 - x0) / (t1 - t0) : 0; // px/ms
      let last = performance.now();
      const step = (now) => {
        const dt = now - last;
        last = now;
        track.scrollLeft -= speed * dt;
        speed *= Math.pow(0.95, dt / 16);
        if (Math.abs(speed) > 0.02) glide = requestAnimationFrame(step);
        else track.classList.remove('gliding');
      };
      if (Math.abs(speed) > 0.1) glide = requestAnimationFrame(step);
      else track.classList.remove('gliding');
    };
    track.addEventListener('pointerup', end);
    track.addEventListener('pointercancel', end);
  }

  /** Partie sauvegardée dans le moteur intégré pour ce jeu téléchargé. */
  const canResume = (game) => S.games.resumable.has(game.id) && statusOf(game, S.games.downloaded).kind === 'downloaded';

  function renderHero() {
    const hero = $('#hero');
    if (!hero) return;
    const game = featured(visibleGames());
    if (!game) return;
    for (const el of $$('.game-card[data-card]', $('#main'))) el.classList.toggle('selected', Number(el.dataset.card) === game.id);
    const shot = mediaUrl(game, 'screenshot', 'medium') || mediaUrl(game, 'boxart', 'medium');
    $('#backdrop').innerHTML = shot ? `<img src="${esc(shot)}" alt="">` : '';
    const cover = mediaUrl(game, 'boxart', 'thumb');
    const st = statusOf(game, S.games.downloaded);
    const resume = canResume(game);
    const statusLine = st.kind === 'downloaded' ? `<span class="good">${esc(t('hero.ready'))}</span>`
      : st.kind === 'running' ? `<span style="color:var(--accent)">${esc(st.unpacking ? t('hero.unpacking', { p: Math.round(st.unpack * 100) }) : t('hero.downloading', { p: Math.round(st.progress * 100) }))}</span>`
      : st.kind === 'failed' ? `<span class="bad">${esc(t('status.failed', { error: st.error }))}</span>`
      : `<span class="muted">${esc(t('hero.toDownload'))}</span>`;
    hero.innerHTML = `
      <div class="cover-lg">${cover ? `<img src="${esc(cover)}" alt="">` : ''}</div>
      <div class="text">
        <h2>${esc(game.title)}</h2>
        <div class="muted">${esc([year(game), mainGenre(game), game.players ? `${game.players} 👤` : null, partsSummary(game), sizeLabel(game)].filter(Boolean).join('  ·  '))}</div>
        ${statusLine}
        ${playtimeHtml(game)}
        ${game.description ? `<div class="desc">${esc(game.description)}</div>` : ''}
        <div class="actions">
          ${isLobbyHost() ? (playKind(game) ? `<button class="btn primary" id="heroLobbySet">${icon('group', 18)} ${esc(t('lobby.setGame'))}</button>` : '') : `${resume ? `<button class="btn primary" id="heroResume">${icon('play', 18)} ${esc(t('action.resume'))}</button>` : ''}
          <button class="btn${resume ? '' : ' primary'}" id="heroMain">${icon(resume ? 'refresh' : st.kind === 'downloaded' ? 'play' : 'cloud', 18)} ${esc(st.kind === 'downloaded' ? t('action.play') : st.kind === 'running' ? `${Math.round(st.progress * 100)} %` : t('action.download'))}</button>`}
          <button class="btn" id="heroInfo">${icon('info', 18)} ${esc(t('action.info'))}</button>
        </div>
      </div>`;
    $('#heroLobbySet')?.addEventListener('click', () => setLobbyGame(game));
    $('#heroMain')?.addEventListener('click', () => onGameClick(systemById(game.systemId), game));
    // Jeu téléchargé, émulateur externe à configurer : « Configurer » au lieu de « Jouer ».
    if (st.kind === 'downloaded' && !resume && !isLobbyHost()) {
      gameIssues(systemById(game.systemId), game).then((issues) => {
        const button = $('#heroMain');
        if (!issues.length || !button || featured(visibleGames())?.id !== game.id) return;
        button.innerHTML = `${icon('gear', 18)} ${esc(t('action.configure'))}`;
        button.title = issues.map(issueText).join('\n');
      });
    }
    $('#heroResume')?.addEventListener('click', () => playChecked(systemById(game.systemId), game, { resume: true }));
    $('#heroInfo').onclick = () => openGameDetail(game.systemId, game.id);
  }

  // ---------------------------------------------------------------------------
  // Clic sur un jeu : jouer, ou proposer le téléchargement
  // ---------------------------------------------------------------------------

  async function onGameClick(system, game) {
    if (isLobbyHost()) return openGameDetail(game.systemId, game.id);
    const downloaded = S.route.name === 'systems' ? S.search.downloaded : S.games.downloaded;
    const st = statusOf(game, downloaded);
    if (st.kind === 'downloaded') {
      // Émulateur externe à configurer : la fiche du jeu explique quoi corriger.
      if ((await gameIssues(system, game)).length) return openGameDetail(system.id, game.id);
      return playChecked(system, game);
    }
    if (st.kind === 'running') return askCancel(game);
    return askDownload(system, game, st.kind === 'failed' ? st.error : null);
  }

  const biosSize = (list) => formatSize(list.reduce((n, b) => n + b.size, 0));

  /** Problèmes qui empêchent de jouer avec l'émulateur externe choisi ([] : prêt, ou moteur intégré). */
  const gameIssues = (system, game) => (system && rc.launcher.issues ? call(rc.launcher.issues, system, game).catch(() => []) : Promise.resolve([]));
  const issueText = (issue) => t(issue.key, issue.vars || {});

  /** Encadré des problèmes en tête de la section de l'émulateur, dans la fiche du jeu. */
  function issuesHtml(issues) {
    if (!issues.length) return '';
    return `<div class="emu-issues"><strong>${icon('info', 18)} ${esc(t('issues.title'))}</strong>
      <ul>${issues.map((i) => `<li>${esc(issueText(i))}</li>`).join('')}</ul></div>`;
  }

  /** Joue, en proposant d'abord de télécharger les BIOS du système absents du PC. */
  /** Même cœur, aux variantes près (« mupen64plus_next » / « mupen64plus_next_gles3 »). */
  const sameCore = (a, b) => Boolean(a && b) && (a === b || a.startsWith(`${b}_`) || b.startsWith(`${a}_`));

  /**
   * Reprise ou état de l'historique faits avec un autre cœur que l'émulateur choisi pour le système :
   * l'émulateur du système devient ce cœur (les états ne passent pas d'un cœur à l'autre), après
   * confirmation. Renvoie faux si l'utilisateur annule.
   */
  async function confirmCoreSwitch(system, game, options) {
    const target = options.stateCore || options.resumeCore
      || (options.resume ? await call(rc.launcher.resumeCore, system, game).catch(() => null) : null);
    if (!target) return true;
    const opts = await call(rc.launcher.options, system).catch(() => null);
    const current = opts?.options?.find((o) => o.id === opts.selected);
    if (!opts || (current?.kind === 'builtin' && sameCore(current.core, target))) return true;
    const next = opts.options.find((o) => o.kind === 'builtin' && sameCore(o.core, target));
    if (!next) return true;
    const label = (o) => (!o ? '?' : o.kind === 'builtin' ? (o.name ? t('emu.builtinApp', { name: o.name }) : t('emu.builtin', { core: o.core }))
      : o.name || t(`emu.${o.kind}`));
    return new Promise((resolve) => {
      let confirmed = false;
      modal({
        title: t('coreSwitch.title'),
        body: `<p>${esc(t('coreSwitch.text', { core: next.core, from: label(current), to: label(next) }))}</p>`,
        buttons: [
          { label: t('app.cancel'), kind: 'ghost', left: true },
          {
            label: t('coreSwitch.confirm'),
            kind: 'primary',
            onClick: () => {
              confirmed = true;
              call(rc.launcher.choose, system.id, next.id).catch(() => {}).then(() => resolve(true));
            },
          },
        ],
      });
      // Fermeture sans confirmation (Annuler, clic à côté, Échap) : annulé.
      const root = $('#modalRoot');
      const watch = new MutationObserver(() => {
        if (root.innerHTML) return;
        watch.disconnect();
        if (!confirmed) resolve(false);
      });
      watch.observe(root, { childList: true });
    });
  }

  async function playChecked(system, game, options = {}) {
    if ((options.resume || options.state) && !(await confirmCoreSwitch(system, game, options))) return;
    const missing = await call(rc.bios.missing, system).catch(() => []);
    if (!missing.length) return play(system, game, options);
    modal({
      title: t('bios.beforePlayTitle'),
      body: `<p>${esc(t('bios.beforePlayText', { n: missing.length, size: biosSize(missing) }))}</p>`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost', left: true },
        { label: t('bios.playAnyway'), onClick: () => play(system, game, options) },
        {
          label: t('bios.downloadAndPlay'),
          kind: 'primary',
          onClick: async () => {
            S.autoLaunch.set(game.id, options);
            await call(rc.downloads.dismiss, game.id);
            await call(rc.downloads.start, system, game, { bios: missing, includeRom: false });
          },
        },
      ],
    });
  }

  // ---------------------------------------------------------------------------
  // Jeu à plusieurs en réseau local (appareils RomCloud du même réseau : PC, téléphones, TV)
  // ---------------------------------------------------------------------------

  /** Nombre de joueurs maximal d'après le champ « joueurs » du scraping (« 1-2 », « 4 »…), 0 si inconnu. */
  const maxPlayers = (players) => Math.max(0, ...String(players || '').split(/\D+/).filter(Boolean).map(Number));

  /**
   * Façon de jouer à plusieurs avec ce jeu : 'netplay' (système et émulateur compatibles) ou 'link'
   * (consoles reliées : câble, ad hoc), jeu à plusieurs joueurs ; null sinon.
   */
  const playKind = (game) => {
    const kind = S.netplayAllowed[game.systemId];
    if (kind !== 'netplay' && kind !== 'link') return null;
    // GBA : seulement les jeux que gpSP sait relier, même annoncés à un joueur (échanges Pokémon).
    if (kind === 'link' && LINK_OF[String(game.systemId).toLowerCase()] === 'gba') return gbaLinkGame(game) ? kind : null;
    return maxPlayers(game.players) >= 2 ? kind : null;
  };

  /** Au moins un appareil libre sur le réseau (ni hôte d'une partie, ni en partie) : une partie peut être proposée. */
  const anyAvailable = () => S.peers.some((p) => !p.hosting && !p.busy);

  /** Appareils qui proposent ce jeu (même jeu du serveur, ou même fichier). */
  const hostsOf = (game) => S.peers.filter((p) => p.hosting && p.hosting.systemId === game.systemId
    && (p.hosting.gameId === game.id || (p.hosting.fileName === game.fileName && p.hosting.size === game.size)));

  /** Icône du jeu : partie à proposer, ou ce jeu proposé par un appareil (à rejoindre). */
  const together = (game) => ((anyAvailable() || hostsOf(game).length) ? playKind(game) : null);

  /**
   * Jeux GBA que gpSP sait relier (sa liste, gba_over.h ; même règle que LinkRules.kt) : câble de
   * Pokémon Rubis / Saphir et d'Advance Wars, adaptateur sans fil des autres. Ailleurs, le mode à
   * plusieurs du jeu attend une console qui ne répond jamais (écran noir).
   */
  const GBA_LINK_GAMES = new RegExp('pokemon.*(ruby|rubis|rubin|rubi|rubino|sapphire|saphir|zafiro|zaffiro|emerald|emeraude|smaragd|esmeralda|smeraldo|'
    + 'fire ?red|rouge feu|feuerrot|rojo fuego|rosso fuoco|leaf ?green|vert feuille|blattgrun|verde hoja|verde foglia)'
    + "|advance wars|mario golf|mario (power )?tennis|battle network [56]|rockman exe [56]|digimon racing|buu'?s fury"
    + '|narnia|shrek super slam|third age|tiers age|hamtaro|hamutaro|chailien');
  const plain = (text) => String(text || '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();
  const gbaLinkGame = (game) => GBA_LINK_GAMES.test(plain(game.title)) || GBA_LINK_GAMES.test(plain(game.fileName));

  const multiBadge = (game) => {
    const kind = together(game);
    if (!kind) return '';
    const link = kind === 'link';
    return ` <span class="multi-badge${link ? ' link' : ''}" title="${esc(t(link ? 'link.badge' : 'netplay.badge'))}">${icon(link ? 'link' : 'group', 16)}</span>`;
  };

  /** Liaison de chaque console (même règle que netplay.rs). */
  const LINK_OF = { gb: 'gb', gbc: 'gb', gbcolor: 'gb', gba: 'gba', psp: 'psp' };

  /** Le système se joue-t-il à plusieurs (avec l'émulateur choisi) ou se relie-t-il ? (mémorisé ; [force] : relu). */
  async function refreshNetplaySystem(system, force = false) {
    if (!system || (!force && S.netplayAllowed[system.id] !== undefined)) return;
    S.netplayAllowed[system.id] = await call(rc.netplay.systemTogether, system).catch(() => null);
  }

  /** Nom affiché d'un appareil : précédé du profil par Internet (« alice · Pixel 8 »). */
  const peerName = (p) => (p.internet && p.user ? `${p.user} · ${p.name}` : p.name);

  const platformLabel = (p) => t(p === 'androidtv' ? 'netplay.platform.tv' : p === 'windows' ? 'netplay.platform.pc' : 'netplay.platform.phone');

  /**
   * Rejoint la partie proposée par [peer] : même jeu du serveur (même fichier), avec le cœur de
   * l'hôte ; jeu absent du PC : téléchargé d'abord, partie rejointe à la fin du téléchargement.
   */
  async function joinNetplay(peer) {
    const hosted = peer?.hosting;
    if (!hosted) return;
    const system = systemById(hosted.systemId);
    let game = null;
    if (system) {
      const r = await call(rc.api.games, system.id).catch(() => null);
      game = r?.data.find((g) => g.id === hosted.gameId && g.fileName === hosted.fileName)
        || r?.data.find((g) => g.fileName === hosted.fileName && g.size === hosted.size) || null;
    }
    if (!system || !game) {
      toast(t('netplay.unknownGame', { title: hosted.title }), { type: 'error' });
      return;
    }
    const downloaded = new Set(await call(rc.library.downloaded, [system], [game]));
    if (!downloaded.has(game.id)) {
      S.pendingJoin = { gameId: game.id, peer };
      const bios = system.biosCount ? await call(rc.bios.missing, system).catch(() => []) : [];
      await call(rc.downloads.start, system, game, { bios, includeRom: true });
      toast(t('netplay.downloading', { title: game.title }));
      return;
    }
    // Par Internet : relais du serveur ; aller-retour de l'hôte jusqu'au serveur (délai des touches).
    play(system, game, { netplay: { join: { address: peer.address, port: peer.port, peerName: peerName(peer), game: hosted, internet: !!peer.internet, rtt: peer.rtt || 0 } } });
  }

  /** État des appareils qui change les icônes et les boutons : appareils libres, parties proposées. */
  const peersSignature = () => `${anyAvailable()}|${S.peers.filter((p) => p.hosting).map((p) => `${p.id}:${p.hosting.gameId}:${p.hosting.fileName}`).sort().join(',')}`;

  rc.netplay.onPeers((peers) => {
    const before = peersSignature();
    S.peers = peers || [];
    // Icônes et boutons mis à jour quand un appareil se libère, propose une partie ou disparaît.
    if (S.route.name === 'systems') {
      // Bouton « Multijoueurs » : profil connecté (relu avec les lobbies).
    } else if (before !== peersSignature()) {
      if (S.route.name === 'games') renderGamesBody();
      if (S.route.name === 'game') renderGame();
    }
  });

  // ---------------------------------------------------------------------------
  // Multijoueurs : joueurs connectés à un profil (pastille présent / absent) et lobbies du serveur.
  // Un lobby a un hôte (qui choisit le jeu et le lance) et des invités (qui rejoignent sa partie) ;
  // la partie elle-même passe par le mécanisme habituel (réseau local ou relais du serveur).
  // ---------------------------------------------------------------------------

  /** Lobby dont ce PC est membre (null sinon). */
  const myLobby = () => S.lobbies.lobbies.find((l) => l.members.some((m) => m.id === S.lobbies.me)) || null;
  const isLobbyHost = (lobby = S.lobby) => Boolean(lobby) && lobby.host === S.lobbies.me;
  /** Invité d'un lobby : il reste sur la page du lobby tant qu'il ne l'a pas quitté. */
  const lobbyLocked = () => Boolean(S.lobby) && !isLobbyHost();
  /** Joueurs minimum des jeux affichés dans un lobby (2 au moins). */
  const lobbyPlayers = () => (S.lobby ? Math.max(2, S.lobby.members.length) : 0);
  /** Système jouable en réseau par tous les membres du lobby (émulateur intégré chez chacun). */
  const lobbySystem = (systemId) => !S.lobby?.systems || S.lobby.systems.includes(systemId);
  /** Jeu jouable par les membres du lobby (système, nombre de joueurs du jeu suffisant). */
  const fitsLobby = (game) => !S.lobby || (lobbySystem(game.systemId) && maxPlayers(game.players) >= lobbyPlayers());
  /** Systèmes que ce PC sait jouer en réseau avec un émulateur intégré (envoyés au lobby). */
  const capableSystems = () => call(rc.netplay.capabilities, S.systems).catch(() => null);
  /** Façons de jouer en réseau possibles pour ce jeu (netplay, liaison entre consoles). */
  const lobbyModes = (game) => [playKind(game)].filter(Boolean);
  const lobbyTitle = (lobby) => lobby.game?.title || t('lobby.new');
  // Avatar du profil devant le nom (lobbies, joueurs connectés).
  const memberName = (m) => `${m.avatar ? `${m.avatar} ` : ''}${m.user ? `${m.user} · ${m.name}` : m.name}`;

  /** Bouton « Multijoueurs » de l'écran principal (nombre de lobbies ouverts en pastille). */
  const multiplayerBtn = () => {
    if (!S.account.signedIn) return '';
    const n = S.lobbies.lobbies.length;
    return `<button class="icon-btn netplay-btn" id="netplayBtn" title="${esc(t('netplay.title'))}">${icon('group')}${n ? `<span class="pill">${n}</span>` : ''}</button>`;
  };

  /** Bouton de retour au lobby (hôte parti choisir un jeu), dans la barre du haut. */
  function lobbyTopbarButton() {
    $('#lobbyBackBtn')?.remove();
    if (!S.lobby || S.route.name === 'lobby') return;
    $('#topbarRight').insertAdjacentHTML('afterbegin', `<button class="btn primary small" id="lobbyBackBtn">${icon('group', 18)} ${esc(t('lobby.back'))}</button>`);
    $('#lobbyBackBtn').onclick = () => go({ name: 'lobby' });
  }

  /** Lobbies et joueurs relus sur le serveur ; lobby dissous ou quitté : retour à l'écran d'accueil. */
  async function refreshLobbies() {
    if (!S.account.signedIn) {
      S.lobbies = { players: [], lobbies: [], me: null };
      S.lobby = null;
      return;
    }
    const data = await call(rc.lobby.list).catch(() => null);
    if (!data) return;
    const before = S.lobby;
    S.lobbies = { players: data.players || [], lobbies: data.lobbies || [], me: data.me };
    S.lobby = myLobby();
    if (before && !S.lobby) {
      if (!S.lobbyLeaving) toast(t('lobby.dissolved'));
      S.lobbyLeaving = false;
      S.history = [];
      S.route = { name: 'systems' };
      render();
      return;
    }
    await syncLobbyStatus();
    if (S.route.name === 'lobby') renderLobby();
    else if (S.route.name === 'systems') $('#netplayBtn')?.replaceWith(...htmlNodes(multiplayerBtn(), showMultiplayer));
    if (S.multiplayerOpen) renderMultiplayer();
    // Joueurs du lobby changés : systèmes et jeux affichés (nombre de joueurs, émulateurs) à revoir.
    const size = `${lobbyPlayers()}|${S.lobby?.systems?.join(',') ?? ''}`;
    if (size !== S.lobbySize) {
      S.lobbySize = size;
      if (S.route.name === 'games') renderGamesBody();
      if (S.route.name === 'systems' && !S.search.query) renderSystemsBody();
    }
  }

  /** Éléments d'un fragment HTML, le premier relié à [onClick]. */
  function htmlNodes(html, onClick) {
    const box = document.createElement('div');
    box.innerHTML = html;
    if (box.firstElementChild && onClick) box.firstElementChild.addEventListener('click', onClick);
    return box.childNodes.length ? [...box.childNodes] : [document.createTextNode('')];
  }

  /**
   * « En jeu » tant qu'un jeu lancé par l'application est ouvert : de retour en attente à sa fermeture
   * (évènement « game:exited », et contrôle de secours à chaque relecture des lobbies).
   */
  async function syncLobbyStatus() {
    const self = S.lobby?.members.find((m) => m.id === S.lobbies.me);
    if (!self || self.status !== 'playing') return;
    // Jeu en cours de lancement (téléchargement du cœur, invité qui attend la fin d'un téléchargement).
    if (Date.now() - (S.lobbyPlayingSince || 0) < 20000) return;
    const running = await call(rc.launcher.running).catch(() => null);
    if (running === 0) {
      await call(rc.lobby.status, S.lobby.id, 'waiting').catch(() => {});
      self.status = 'waiting';
    }
  }

  rc.launcher.onExited?.(async ({ running }) => {
    const self = S.lobby?.members.find((m) => m.id === S.lobbies.me);
    if (!self || self.status !== 'playing' || running > 0) return;
    await call(rc.lobby.status, S.lobby.id, 'waiting').catch(() => {});
    await refreshLobbies();
  });

  // Lobbies et pastilles : relus toutes les 3 s (profil connecté).
  setInterval(() => refreshLobbies(), 3000);

  // Activité de l'utilisateur : joueur « présent » pour les autres (signalée au plus toutes les 30 s).
  let lastActivity = 0;
  const onActivity = () => {
    const now = Date.now();
    if (now - lastActivity < 30000) return;
    lastActivity = now;
    rc.presence?.activity().catch(() => {});
  };
  for (const type of ['keydown', 'mousedown', 'mousemove', 'wheel']) window.addEventListener(type, onActivity, { passive: true, capture: true });

  /** Fenêtre « Multijoueurs » : joueurs connectés à gauche, lobbies à droite. */
  function showMultiplayer() {
    if (!S.account.signedIn) {
      modal({ title: t('netplay.title'), body: `<p>${esc(t('lobby.signIn'))}</p>`, buttons: [{ label: t('app.close'), kind: 'primary' }] });
      return;
    }
    S.multiplayerOpen = true;
    const root = modal({ title: t('netplay.title'), body: '<div class="multiplayer" id="multiplayerBody"></div>', buttons: [{ label: t('app.close'), kind: 'ghost' }] });
    $('.modal', root).classList.add('wide');
    // « Créer un lobby » dans la barre du titre (masqué tant que ce PC est dans un lobby).
    const title = $('.modal h3', root);
    title.classList.add('with-action');
    title.insertAdjacentHTML('beforeend', `<button class="btn primary small" id="lobbyCreateBtn">${icon('group', 18)} ${esc(t('lobby.create'))}</button>`);
    $('#lobbyCreateBtn').onclick = () => createLobby();
    renderMultiplayer();
    const watch = new MutationObserver(() => {
      if (!root.innerHTML) {
        S.multiplayerOpen = false;
        watch.disconnect();
      }
    });
    watch.observe(root, { childList: true });
    refreshLobbies();
  }

  function renderMultiplayer() {
    const box = $('#multiplayerBody');
    if (!box) return;
    const players = [...S.lobbies.players].sort((a, b) => (b.id === S.lobbies.me) - (a.id === S.lobbies.me) || Number(b.active) - Number(a.active));
    box.innerHTML = `<section>
        <h4>${esc(t('lobby.players'))}</h4>
        ${players.length ? players.map((p) => `<div class="mp-row">
          <span class="dot ${p.active ? 'present' : 'away'}" title="${esc(t(p.active ? 'lobby.present' : 'lobby.away'))}"></span>
          <div class="grow"><strong>${esc(memberName(p))}</strong>${p.id === S.lobbies.me ? ` <span class="muted">${esc(t('lobby.you'))}</span>` : ''}
            <div class="muted small">${esc(platformLabel(p.platform))}${p.busy ? ` · ${esc(t('lobby.inGame'))}` : ''}</div></div>
        </div>`).join('') : `<p class="muted">${esc(t('netplay.nobody'))}</p>`}
      </section>
      <section>
        <h4>${esc(t('lobby.lobbies'))}</h4>
        ${S.lobbies.lobbies.length ? S.lobbies.lobbies.map((l) => `<button class="mp-lobby${l.members.some((m) => m.id === S.lobbies.me) ? ' mine' : ''}" data-lobby="${esc(l.id)}">
          <strong>${esc(lobbyTitle(l))}</strong>
          <span class="muted small">${esc(l.members.map(memberName).join(', '))}</span>
          <span class="tag">${l.members.length}</span>
        </button>`).join('') : `<p class="muted">${esc(t('lobby.none'))}</p>`}
      </section>`;
    $('#lobbyCreateBtn')?.classList.toggle('hidden', Boolean(S.lobby));
    for (const el of $$('[data-lobby]', box)) el.onclick = () => openLobby(el.dataset.lobby);
  }

  /** Nouveau lobby (vierge, ou avec ce jeu) : ce PC en est l'hôte. */
  async function createLobby(game = null) {
    if (game) await refreshNetplaySystem(systemById(game.systemId));
    const options = { ...(game ? { game: lobbyGameOf(game), modes: lobbyModes(game) } : {}), systems: await capableSystems() };
    try {
      S.lobby = await call(rc.lobby.create, options);
      await refreshLobbies();
      $('#modalRoot').innerHTML = '';
      go({ name: 'lobby' });
    } catch (err) {
      toast(err.message, { type: 'error' });
    }
  }

  /** Jeu tel que le lobby le garde (identifiant, fichier, nombre de joueurs). */
  const lobbyGameOf = (game) => ({ gameId: game.id, systemId: game.systemId, title: game.title, fileName: game.fileName, size: game.size, players: maxPlayers(game.players) });

  /** Ouvre un lobby : le rejoint d'abord si ce PC n'en est pas membre. */
  async function openLobby(id) {
    const lobby = S.lobbies.lobbies.find((l) => l.id === id);
    try {
      if (lobby && !lobby.members.some((m) => m.id === S.lobbies.me)) await call(rc.lobby.join, id, await capableSystems());
      await refreshLobbies();
      $('#modalRoot').innerHTML = '';
      if (S.lobby) go({ name: 'lobby' });
    } catch (err) {
      toast(err.message, { type: 'error' });
    }
  }

  /** Jeu du lobby (fiche du serveur), mémorisé ; null sans jeu ou hors de la bibliothèque. */
  async function lobbyGame(lobby) {
    const g = lobby?.game;
    if (!g) return null;
    if (S.lobbyGame && S.lobbyGame.id === g.gameId && S.lobbyGame.fileName === g.fileName) return S.lobbyGame;
    const system = systemById(g.systemId);
    const r = system ? await call(rc.api.games, system.id).catch(() => null) : null;
    S.lobbyGame = r?.data.find((x) => x.id === g.gameId) || r?.data.find((x) => x.fileName === g.fileName && x.size === g.size) || null;
    return S.lobbyGame;
  }

  /** Page du lobby : jeu, façon de jouer, membres (couronne de l'hôte, statut), boutons. */
  async function renderLobby() {
    const lobby = S.lobby;
    if (!lobby) return;
    const host = isLobbyHost(lobby);
    const hostMember = lobby.members.find((m) => m.host);
    applySystemColor(); // jeu du lobby changé : couleur de son système
    setTopbar({ title: lobbyTitle(lobby), subtitle: t('lobby.hostedBy', { name: hostMember ? memberName(hostMember) : '?' }) });
    $('#backBtn').classList.toggle('hidden', !host);
    const game = await lobbyGame(lobby);
    if (S.route.name !== 'lobby' || S.lobby !== lobby) return;
    const system = game && systemById(game.systemId);
    const cover = game && mediaUrl(game, 'boxart', 'thumb');
    const self = lobby.members.find((m) => m.id === S.lobbies.me);
    const canPlay = Boolean(lobby.game) && (host || lobby.started);
    const modeText = (m) => t(m === 'link' ? 'lobby.mode.link' : 'lobby.mode.netplay');
    const mode = lobby.modes.length > 1 && host
      ? `<label class="lobby-mode">${esc(t('lobby.modeLabel'))} <select id="lobbyMode">${lobby.modes.map((m) => `<option value="${m}"${m === lobby.mode ? ' selected' : ''}>${esc(modeText(m))}</option>`).join('')}</select></label>`
      : lobby.mode ? `<p class="muted">${esc(t('lobby.modeUsed', { mode: modeText(lobby.mode) }))}</p>` : '';
    $('#main').innerHTML = `<div class="page lobby">
      <div class="lobby-game">
        <div class="cover-lg">${cover ? `<img src="${esc(cover)}" alt="">` : icon('pad', 64)}</div>
        <div class="col">
          <h2>${esc(lobbyTitle(lobby))}</h2>
          ${system ? `<p class="muted">${esc(system.name)}${game.players ? ` · ${esc(t('detail.players', { v: game.players }))}` : ''}</p>` : ''}
          ${lobby.game ? mode : `<p class="muted">${esc(t(host ? 'lobby.chooseHint' : 'lobby.waitGame'))}</p>`}
          <div class="actions">
            ${self?.status === 'playing'
              ? `<button class="btn primary big" disabled>${icon('play')} ${esc(t('lobby.playing'))}</button>`
              : canPlay
                ? `<button class="btn primary big" id="lobbyPlayBtn">${icon('play')} ${esc(t('action.play'))}</button>`
                : host ? '' : `<button class="btn big" disabled>${esc(t(lobby.game ? 'lobby.waitHost' : 'lobby.waitGame'))}</button>`}
            ${host ? `<button class="btn big" id="lobbyChooseBtn">${icon('search', 18)} ${esc(t(lobby.game ? 'lobby.changeGame' : 'lobby.chooseGame'))}</button>` : ''}
            ${host
              ? `<button class="btn danger" id="lobbyDissolveBtn">${icon('close', 18)} ${esc(t('lobby.dissolve'))}</button>`
              : `<button class="btn" id="lobbyLeaveBtn">${icon('back', 18)} ${esc(t('lobby.leave'))}</button>`}
          </div>
          ${game ? `<div class="lobby-download hidden" data-lobby-download="${game.id}"><div class="progress update-progress"><div></div></div><p class="muted"></p></div>` : ''}
        </div>
      </div>
      <h3>${esc(t('lobby.members', { n: lobby.members.length }))}</h3>
      <div class="lobby-members">${lobby.members.map((m) => `<div class="mp-row">
        <span class="crown">${m.host ? icon('crown', 18) : ''}</span>
        <div class="grow"><strong>${esc(memberName(m))}</strong>${m.id === S.lobbies.me ? ` <span class="muted">${esc(t('lobby.you'))}</span>` : ''}</div>
        <span class="tag${m.status === 'playing' ? ' playing' : ''}">${esc(t(m.status === 'playing' ? 'lobby.statusPlaying' : 'lobby.statusWaiting'))}</span>
      </div>`).join('')}</div>
    </div>`;
    if (game) patchLobbyDownload(game.id);
    $('#lobbyMode')?.addEventListener('change', (e) => call(rc.lobby.update, lobby.id, { mode: e.target.value }).then(refreshLobbies).catch((err) => toast(err.message, { type: 'error' })));
    $('#lobbyChooseBtn')?.addEventListener('click', () => go({ name: 'systems' }));
    $('#lobbyPlayBtn')?.addEventListener('click', () => playLobby(lobby, system, game));
    $('#lobbyLeaveBtn')?.addEventListener('click', async () => {
      S.lobbyLeaving = true;
      await call(rc.lobby.leave, lobby.id).catch(() => {});
      await refreshLobbies();
    });
    $('#lobbyDissolveBtn')?.addEventListener('click', () => modal({
      title: t('lobby.dissolve'),
      body: `<p>${esc(t('lobby.dissolveText'))}</p>`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost', left: true },
        {
          label: t('lobby.dissolve'),
          kind: 'danger',
          onClick: async () => {
            S.lobbyLeaving = true;
            await call(rc.lobby.dissolve, lobby.id).catch(() => {});
            await refreshLobbies();
          },
        },
      ],
    }));
  }

  /** Page du lobby : progression du téléchargement de son jeu (absent de l'appareil). */
  function patchLobbyDownload(gameId) {
    const el = $(`[data-lobby-download="${gameId}"]`);
    if (!el) return;
    const d = S.downloads[gameId];
    const running = d?.status === 'running';
    el.classList.toggle('hidden', !running);
    if (!running) return;
    const pct = Math.round((d.total ? d.bytes / d.total : 0) * 100);
    $('.progress > div', el).style.width = `${pct}%`;
    $('p', el).textContent = t('lobby.downloadProgress', { pct });
  }

  /**
   * « Jouer » dans le lobby : l'hôte lance la partie (le moteur la propose), les invités la
   * rejoignent ; chacun passe « en jeu » pour les autres.
   */
  async function playLobby(lobby, system, game) {
    if (!system || !game) {
      toast(t('netplay.unknownGame', { title: lobbyTitle(lobby) }), { type: 'error' });
      return;
    }
    if (isLobbyHost(lobby)) {
      const downloaded = new Set(await call(rc.library.downloaded, [system], [game]));
      if (!downloaded.has(game.id)) {
        const bios = system.biosCount ? await call(rc.bios.missing, system).catch(() => []) : [];
        await call(rc.downloads.start, system, game, { bios, includeRom: true });
        toast(t('lobby.downloading', { title: game.title }));
        return;
      }
      await play(system, game, { netplay: { host: true } });
    } else {
      // Partie de l'hôte (son appareil annonce le jeu proposé) : rejointe, jeu téléchargé d'abord s'il manque.
      // Moteur de l'hôte qui s'ouvre encore : sa partie est attendue quelques secondes.
      const hostPeer = () => S.peers.find((p) => p.id === lobby.host && p.hosting);
      let peer = hostPeer();
      for (let i = 0; !peer && i < 20; i++) {
        await new Promise((resolve) => setTimeout(resolve, 500));
        peer = hostPeer();
      }
      if (!peer) {
        toast(t('lobby.hostNotReady'), { type: 'error' });
        return;
      }
      await joinNetplay(peer);
    }
    S.lobbyPlayingSince = Date.now();
    await call(rc.lobby.status, lobby.id, 'playing').catch((err) => toast(err.message, { type: 'error' }));
    await refreshLobbies();
  }

  /** Hôte : ce jeu devient celui du lobby, retour à la page du lobby. */
  async function setLobbyGame(game) {
    await refreshNetplaySystem(systemById(game.systemId));
    try {
      await call(rc.lobby.update, S.lobby.id, { game: lobbyGameOf(game), modes: lobbyModes(game) });
      S.lobbyGame = null;
      await refreshLobbies();
      S.history = [];
      go({ name: 'lobby' }, { replace: true });
    } catch (err) {
      toast(err.message, { type: 'error' });
    }
  }

  /** Lobbies ouverts pour ce jeu (même jeu du serveur, ou même fichier). */
  const lobbiesOf = (game) => S.lobbies.lobbies.filter((l) => l.game && l.game.systemId === game.systemId
    && (l.game.gameId === game.id || (l.game.fileName === game.fileName && l.game.size === game.size)));

  /** « Rejoindre un lobby » de la fiche du jeu : directement, ou choix entre les lobbies de ce jeu. */
  function joinLobbyOf(game) {
    const list = lobbiesOf(game);
    if (list.length === 1) return openLobby(list[0].id);
    const root = modal({
      title: t('lobby.joinTitle'),
      body: `<div class="multiplayer single">${list.map((l) => `<button class="mp-lobby" data-lobby="${esc(l.id)}">
        <strong>${esc(memberName(l.members.find((m) => m.host) || l.members[0]))}</strong>
        <span class="muted small">${esc(l.members.map(memberName).join(', '))}</span>
        <span class="tag">${l.members.length}</span></button>`).join('')}</div>`,
      buttons: [{ label: t('app.cancel'), kind: 'ghost' }],
    });
    for (const el of $$('[data-lobby]', root)) el.onclick = () => openLobby(el.dataset.lobby);
  }

  /** Lance le jeu ; `options.resume` : reprend la partie sauvegardée (moteur intégré). */
  async function play(system, game, options) {
    // Lancement plus long que d'habitude (cœur du moteur intégré à télécharger) : message d'attente.
    const waiting = setTimeout(() => toast(t('emu.preparing')), 700);
    try {
      const result = await call(rc.launcher.play, system, game, options);
      if (result?.manual) showManualLaunch(result);
    } catch (err) {
      const key = err.info?.key;
      rc.account.reportError({ context: `launch:${system.id}`, message: err.message, details: game.fileName });
      if (key === 'errors.emulatorMissing') {
        showEmulatorMissing(err.info.vars.id);
      } else if (key === 'errors.retroarchMissing' || key === 'errors.coreMissing') {
        toast(err.message, { type: 'error', action: t('detail.configureRetroArch'), onAction: () => showRetroArchHelp(system) });
      } else {
        toast(err.message, { type: 'error' });
      }
    } finally {
      clearTimeout(waiting);
    }
  }

  /** Émulateur ouvert seul (pas de lancement direct possible) : indiquer le jeu à ouvrir. */
  function showManualLaunch({ emulator, file }) {
    modal({
      title: t('emulator.manualTitle', { name: emulator }),
      body: `<p>${esc(t('emulator.manualText', { name: emulator }))}</p><p class="mono">${esc(file)}</p>`,
      buttons: [
        { label: t('emulator.copyPath'), left: true, keepOpen: true, onClick: () => { navigator.clipboard.writeText(file); toast(t('emulator.copied')); } },
        { label: t('detail.showInFolder'), onClick: () => rc.shell.showItem(file) },
        { label: t('app.close'), kind: 'primary' },
      ],
    });
  }

  /** Émulateur du catalogue introuvable : le télécharger ou indiquer son emplacement. */
  async function showEmulatorMissing(id) {
    const emu = (await call(rc.emulators.list)).find((e) => e.id === id);
    if (!emu) return;
    modal({
      title: t('emulator.missingTitle', { name: emu.name }),
      body: `<p>${esc(t('emulator.missingText', { name: emu.name }))}</p>
        <p class="muted">${esc(t('emulator.defaultLocation', { path: emu.defaultLocation }))}</p>`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost', left: true },
        { label: t(emu.folder ? 'emulator.browseFolder' : 'emulator.browse'), onClick: () => locateEmulator(emu) },
        { label: t('emulator.download', { name: emu.name }), kind: 'primary', onClick: () => downloadEmulator(emu) },
      ],
    });
  }

  /**
   * « Télécharger » : émulateur installable par RomCloud (Eden) -> dernière version téléchargée et
   * décompressée dans le dossier choisi (proposé par défaut, créé s'il n'existe pas) ; sinon son site.
   */
  async function downloadEmulator(emu, button) {
    if (!emu.updatable) return rc.shell.openExternal(emu.url);
    const label = button?.innerHTML;
    if (button) {
      button.disabled = true;
      button.textContent = t('emulator.checking');
    }
    let u;
    try {
      u = await call(rc.emulators.checkUpdate, emu.id);
    } catch (err) {
      toast(err.message, { type: 'error' });
      return;
    } finally {
      if (button) {
        button.disabled = false;
        button.innerHTML = label;
      }
    }
    if (!u.asset) {
      toast(t('emulator.noBuild'), { type: 'error' });
      return rc.shell.openExternal(emu.url);
    }
    const root = modal({
      title: t('emulator.installTitle', { name: emu.name, version: u.latest }),
      body: `<p>${esc(t('emulator.installText', { file: u.asset }))}</p>
        <label>${esc(t('emulator.installDir'))}<span class="line"><input type="text" id="emuInstallDir" value="${esc(u.dir)}">
        <button class="btn" id="emuInstallBrowse">${esc(t('settings.browse'))}</button></span></label>
        <p class="muted small">${esc(t('emulator.installDirHint'))}</p>
        ${emu.id === 'eden' ? `<p class="muted small">${esc(t('emulator.edenSetup'))}</p>` : ''}`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost', left: true },
        { label: t('emulator.website'), keepOpen: true, onClick: () => rc.shell.openExternal(emu.url) },
        {
          label: t('emulator.installVersion', { version: u.latest }),
          kind: 'primary',
          keepOpen: true,
          onClick: async (close) => {
            const dir = $('#emuInstallDir', root).value.trim();
            if (!dir) return;
            const buttons = $$('.foot .btn', root);
            const install = buttons.pop();
            for (const b of [...buttons, install, $('#emuInstallBrowse', root)]) b.disabled = true;
            install.textContent = t('emulator.installing');
            try {
              const done = await call(rc.emulators.update, emu.id, dir);
              close();
              toast(t('emulator.installed', { name: emu.name, version: done.installed || done.latest }));
              if (S.route.name === 'game') renderGame();
              else if (S.route.name === 'settings') renderSettings();
            } catch (err) {
              toast(err.message, { type: 'error' });
              for (const b of [...buttons, install, $('#emuInstallBrowse', root)]) b.disabled = false;
              install.textContent = t('emulator.installVersion', { version: u.latest });
            }
          },
        },
      ],
    });
    $('#emuInstallBrowse', root).onclick = async () => {
      const dir = await call(rc.dialog.pickFolder);
      if (dir) $('#emuInstallDir', root).value = dir;
    };
  }

  /** Choix manuel de l'exécutable d'un émulateur. */
  async function locateEmulator(emu) {
    // Émulateur indiqué par son dossier (Eden : eden.exe et eden-cli.exe) ; sinon son exécutable.
    const file = emu.folder ? await call(rc.dialog.pickFolder) : await call(rc.dialog.pickFile, [{ name: emu.name, extensions: ['exe'] }]);
    if (!file) return false;
    try {
      await call(rc.emulators.setPath, emu.id, file);
    } catch (err) {
      toast(err.message, { type: 'error' });
      return false;
    }
    toast(t('emulator.located', { name: emu.name }));
    if (S.route.name === 'game') renderGame();
    else if (S.route.name === 'settings') renderSettings();
    return true;
  }

  async function detectEmulators(button) {
    if (button) {
      button.disabled = true;
      button.textContent = t('emulator.detecting');
    }
    const list = await call(rc.emulators.detect);
    toast(t('emulator.detected', { n: list.filter((e) => e.path).length }));
    if (S.route.name === 'game') renderGame();
    else if (S.route.name === 'settings') renderSettings();
  }

  /** Capture d'écran en grand ; flèches (ou clic sur les côtés) pour passer aux autres, Échap pour fermer. */
  function showScreenshot(urls, index) {
    const root = $('#modalRoot');
    let i = index;
    const draw = () => {
      root.innerHTML = `<div class="modal-backdrop shot-viewer">
        <img src="${esc(urls[i])}" alt="">
        ${urls.length > 1 ? `<button class="icon-btn prev" aria-label="${esc(t('detail.previous'))}">${icon('back', 28)}</button>
          <button class="icon-btn next" aria-label="${esc(t('detail.next'))}">${icon('back', 28)}</button>
          <div class="count">${i + 1} / ${urls.length}</div>` : ''}</div>`;
      const go = (step) => {
        i = (i + step + urls.length) % urls.length;
        draw();
      };
      $('.prev', root)?.addEventListener('click', () => go(-1));
      $('.next', root)?.addEventListener('click', () => go(1));
      $('.modal-backdrop', root).onclick = (e) => {
        if (e.target === e.currentTarget || e.target.tagName === 'IMG') close();
      };
      goShot = go;
    };
    const close = () => {
      root.innerHTML = '';
      goShot = null;
    };
    draw();
  }
  // Capture affichée en grand : passage à la précédente / suivante au clavier.
  let goShot = null;
  document.addEventListener('keydown', (e) => {
    if (!goShot || !$('.shot-viewer')) return;
    if (e.key === 'ArrowLeft') goShot(-1);
    else if (e.key === 'ArrowRight') goShot(1);
    else return;
    e.preventDefault();
    e.stopPropagation();
  }, true);

  function modal({ title, body, buttons }) {
    const root = $('#modalRoot');
    root.innerHTML = `<div class="modal-backdrop"><div class="modal" role="dialog">
      <h3>${esc(title)}</h3><div class="body">${body}</div><div class="foot"></div></div></div>`;
    const foot = $('.foot', root);
    const close = () => (root.innerHTML = '');
    for (const b of buttons) {
      const btn = document.createElement('button');
      btn.className = `btn ${b.kind || ''} ${b.left ? 'left' : ''}`;
      btn.textContent = b.label;
      btn.onclick = () => {
        if (b.keepOpen) return b.onClick(close);
        close();
        b.onClick?.();
      };
      foot.append(btn);
    }
    $('.modal-backdrop', root).onclick = (e) => {
      if (e.target.classList.contains('modal-backdrop')) close();
    };
    const primary = $$('.btn.primary', foot).pop();
    primary?.focus();
    return root;
  }

  /**
   * Historique des états du jeu (moteur intégré) : états de ce PC et du profil en ligne, avec leur
   * miniature ; jouer à partir d'un état, l'épingler, l'envoyer en ligne ou le supprimer.
   */
  async function showStates(system, game) {
    const history = await call(rc.states.list, system, game).catch((e) => {
      toast(e.message, { type: 'error' });
      return null;
    });
    if (!history) return;
    const states = history.states || [];
    const dateText = (ms) => new Date(ms).toLocaleString(window.I18N.language, { dateStyle: 'medium', timeStyle: 'short' });
    const rows = states.map((st) => `<div class="state-row" data-id="${esc(st.id)}">
        <div class="state-thumb">${icon('pad', 32)}<span class="state-download">${icon('cloud', 28)}</span></div>
        <div class="state-info">
          <strong>${esc(dateText(st.createdAt))}</strong>
          <span class="muted">${esc(st.local ? t('states.thisPc') : st.device || t('states.otherDevice'))}${st.online ? ` · ${icon('cloud', 14)} ${esc(t('states.online'))}` : ''}${st.pinned ? ` · ${esc(t('states.pinned'))}` : ''}${st.disk != null ? ` · ${esc(t('states.disc', { n: st.disk + 1 }))}` : ''}</span>
          ${(history.cores || []).length > 1 ? `<span class="muted small">${esc(t('states.core', { core: st.core }))}</span>` : ''}
          <span class="state-status muted small"></span>
          <div class="line">
            <button class="btn primary" data-state-play>${icon('play', 18)} ${esc(t('action.play'))}</button>
            <button class="btn ghost" data-state-pin>${esc(t(st.pinned ? 'states.unpin' : 'states.pin'))}</button>
            ${st.canUpload && history.signedIn ? `<button class="btn ghost" data-state-upload>${icon('cloud', 18)} ${esc(t('states.upload'))}</button>` : ''}
            <button class="btn ghost danger" data-state-delete title="${esc(t('states.delete'))}">${icon('trash', 18)}</button>
          </div>
        </div>
      </div>`).join('');
    const root = modal({
      title: t('states.title'),
      body: `${history.signedIn && !history.online ? `<p class="muted">${esc(t('states.offline'))}</p>` : ''}
        ${states.length ? `<div class="state-list">${rows}</div>` : `<p>${esc(t('states.empty'))}</p>`}`,
      buttons: [{ label: t('app.close'), kind: 'ghost' }],
    });
    $('.modal', root).classList.add('wide');
    const close = () => (root.innerHTML = '');
    const run = async (action, st) => {
      try {
        await call(rc.states.action, system, game, action, st);
        if (action === 'upload') toast(t('states.uploaded'));
      } catch (e) {
        toast(e.message, { type: 'error' });
      }
      showStates(system, game);
      if (action === 'delete') renderGame();
    };
    for (const row of $$('.state-row', root)) {
      const st = states.find((s) => s.id === row.dataset.id);
      call(rc.states.action, system, game, 'thumbnail', st).then((url) => {
        if (url) $('.state-thumb', row).innerHTML = `<img src="${esc(url)}" alt=""><span class="state-download">${icon('cloud', 28)}</span>`;
      }).catch(() => {});
      $('[data-state-play]', row).onclick = async () => {
        // État d'un autre appareil : téléchargé d'abord, icône de téléchargement sur sa ligne.
        if (!st.local) {
          row.classList.add('downloading');
          $('.state-status', row).innerHTML = `${icon('cloud', 14)} ${esc(t('states.downloading'))}`;
          for (const b of $$('button', root)) b.disabled = true;
          try {
            await call(rc.states.action, system, game, 'download', st);
          } catch (e) {
            toast(e.message, { type: 'error' });
            showStates(system, game);
            return;
          }
        }
        close();
        // Chargé avec le cœur qui l'a créé.
        playChecked(system, game, { state: st.id, stateCore: st.core });
      };
      $('[data-state-pin]', row).onclick = () => run(st.pinned ? 'unpin' : 'pin', st);
      const upload = $('[data-state-upload]', row);
      if (upload) upload.onclick = () => run('upload', st);
      $('[data-state-delete]', row).onclick = () => modal({
        title: t('states.deleteTitle'),
        body: `<p>${esc(t(st.online ? 'states.deleteTextOnline' : 'states.deleteText', { date: dateText(st.createdAt) }))}</p>`,
        buttons: [
          { label: t('app.cancel'), kind: 'ghost', onClick: () => showStates(system, game) },
          { label: t('states.delete'), kind: 'danger', onClick: () => run('delete', st) },
        ],
      });
    }
  }

  async function askDownload(system, game, lastError) {
    const missing = await call(rc.bios.missing, system).catch(() => []);
    const root = modal({
      title: t('download.title'),
      body: `<p>${esc(t('download.text', { title: game.title }))}</p>
        <p class="muted">${esc(game.fileName)}${game.parts?.length ? ` + ${esc(partsSummary(game, true))}` : ''} · ${esc(sizeLabel(game))}</p>
        ${lastError ? `<p class="bad">${esc(t('download.lastError', { error: lastError }))}</p>` : ''}
        <label class="check"><input type="checkbox" id="launchAfter" checked> ${esc(t('download.launchAfter'))}</label>
        ${rc.shortcut ? `<label class="check"><input type="checkbox" id="desktopShortcut"> ${esc(t('download.shortcut'))}</label>` : ''}
        ${missing.length ? `<label class="check"><input type="checkbox" id="withBios" checked> ${esc(t('bios.downloadToo', { n: missing.length, size: biosSize(missing) }))}</label>` : ''}`,
      buttons: [
        { label: t('action.details'), kind: 'ghost', left: true, onClick: () => openGameDetail(game.systemId, game.id) },
        { label: t('app.cancel'), kind: 'ghost' },
        {
          label: t('action.download'),
          kind: 'primary',
          keepOpen: true,
          onClick: async (close) => {
            const launchAfter = $('#launchAfter', root).checked;
            const bios = $('#withBios', root)?.checked ? missing : [];
            if ($('#desktopShortcut', root)?.checked) createShortcut(game);
            close();
            await startDownload(system, game, launchAfter, bios);
          },
        },
      ],
    });
  }

  /** Raccourci du jeu sur le bureau : il ouvre RomCloud et lance le jeu ([playFromShortcut]). */
  async function createShortcut(game) {
    try {
      await call(rc.shortcut.create, game);
      toast(t('download.shortcutDone', { title: game.title }));
    } catch (err) {
      toast(t('download.shortcutFailed', { detail: err.message }), { type: 'error' });
    }
  }

  /**
   * Jeu demandé par son raccourci sur le bureau : sa fiche, puis le jeu lancé (reprise de la partie
   * s'il y en a une) ; pas sur le PC : la fiche reste affichée.
   */
  async function playFromShortcut(request) {
    if (!request?.systemId || !request.gameId) return;
    await openGameDetail(request.systemId, request.gameId);
    const system = systemById(request.systemId);
    const game = S.detailGame;
    if (!system || !game) return;
    const downloaded = await call(rc.library.downloaded, [system], [game]).catch(() => []);
    if (!downloaded.length) return;
    const [localResume, online] = await Promise.all([
      call(rc.launcher.resumable, system, game).catch(() => false),
      S.account.signedIn ? call(rc.launcher.resumableOnline, system, game).catch(() => null) : null,
    ]);
    playChecked(system, game, localResume || online ? { resume: true, resumeCore: !localResume && online?.core ? online.core : undefined } : {});
  }

  async function startDownload(system, game, launchAfter, bios = []) {
    await call(rc.downloads.dismiss, game.id);
    // Guide RetroArch après confirmation (si l'émulateur choisi est RetroArch et pas masqué) :
    // le lancement automatique attend alors sa fermeture.
    const check = await call(rc.launcher.check, system);
    const showHelp = check.usesRetroArch && !S.settings.retroarchHelpDismissed && (!check.retroarchOk || !check.coreOk);
    if (launchAfter && !showHelp) S.autoLaunch.set(game.id, {});
    await call(rc.downloads.start, system, game, { bios });
    if (showHelp) {
      showRetroArchHelp(system, {
        auto: true,
        onClose: () => {
          if (!launchAfter) return;
          const downloaded = S.route.name === 'systems' ? S.search.downloaded : S.games.downloaded;
          if (statusOf(game, downloaded).kind === 'downloaded') play(system, game);
          else S.autoLaunch.set(game.id, {});
        },
      });
    }
  }

  function askCancel(game) {
    modal({
      title: t('download.inProgressTitle'),
      body: `<p>${esc(t('download.inProgressText', { title: game.title }))}</p>`,
      buttons: [
        { label: t('download.cancel'), kind: 'danger', onClick: () => { S.autoLaunch.delete(game.id); rc.downloads.cancel(game.id); } },
        { label: t('app.continue'), kind: 'primary' },
      ],
    });
  }

  /** Guide RetroArch pour Windows, avec l'état actuel (exécutable, cœur). */
  async function showRetroArchHelp(system, { auto = false, onClose } = {}) {
    const c = await call(rc.launcher.check, system);
    const root = modal({
      title: t('ra.title'),
      body: `
        <div class="step"><div class="n">1</div><div class="c"><strong>${esc(t('ra.step1'))}</strong>
          <span>${esc(t('ra.step1Text'))}</span>
          <span class="${c.retroarchOk ? 'good' : 'bad'}">${esc(c.retroarchOk ? t('ra.step1Ok', { path: c.retroarchPath }) : t('ra.step1Missing'))}</span></div></div>
        ${c.core ? `<div class="step"><div class="n">2</div><div class="c"><strong>${esc(t('ra.step2'))}</strong>
          <span>${esc(t('ra.step2Text', { core: c.core }))}</span>
          ${c.retroarchOk ? `<span class="${c.coreOk ? 'good' : 'bad'}">${esc(c.coreOk ? t('ra.step2Ok') : t('ra.step2Missing', { dir: c.coreDir }))}</span>` : ''}</div></div>` : ''}
        <div class="step"><div class="n">3</div><div class="c"><strong>${esc(t('ra.step3'))}</strong><span>${esc(t('ra.step3Text'))}</span></div></div>
        ${auto ? `<label class="check"><input type="checkbox" id="raDismiss"> ${esc(t('ra.dontShowAgain'))}</label>` : ''}`,
      buttons: [
        { label: t('ra.download'), kind: 'ghost', left: true, keepOpen: true, onClick: () => rc.shell.openExternal('https://www.retroarch.com/?page=platforms') },
        { label: t('ra.openSettings'), kind: 'ghost', onClick: () => { onClose?.(); go({ name: 'settings' }); } },
        { label: t('app.close'), kind: 'primary', onClick: () => onClose?.() },
      ],
    });
    const box = $('#raDismiss', root);
    if (box) {
      box.onchange = async () => {
        S.settings = await call(rc.settings.save, { retroarchHelpDismissed: box.checked });
      };
    }
  }

  // ---------------------------------------------------------------------------
  // Fiche d'un jeu
  // ---------------------------------------------------------------------------

  async function openGameDetail(systemId, gameId) {
    let game = findGame(gameId);
    if (!game) {
      try {
        game = (await call(rc.api.games, systemId)).data.find((g) => g.id === gameId);
      } catch {
        /* affiché comme introuvable */
      }
    }
    S.detailGame = game || null;
    go({ name: 'game', systemId, gameId });
  }

  /** Informations détaillées du jeu (scraping, nom de fichier) : [libellé, valeur, lien ?]. */
  // « 3 disques · 1 DLC » (le premier disque est le jeu lui-même) ; [extraOnly] : parties seules
  // (« 2 disques » de plus, mises à jour, DLC).
  function partsSummary(game, extraOnly = false) {
    const parts = game.parts || [];
    if (!parts.length) return '';
    const count = (kind) => parts.filter((p) => p.kind === kind).length;
    const discs = count('disc');
    return [
      discs && t('parts.discs', { n: discs + (extraOnly ? 0 : 1) }),
      count('update') && t('parts.updates', { n: count('update') }),
      count('dlc') && t('parts.dlcs', { n: count('dlc') }),
    ].filter(Boolean).join(' · ');
  }

  // Fichiers du jeu, un par ligne (« Disque 2 : FF7 (Disc 2).chd (512 Mo) »).
  function partsText(game) {
    const label = (p) => (p.kind === 'disc' ? t('parts.disc', { n: p.index ?? '?' }) : t(`parts.${p.kind}`));
    return (game.parts || []).map((p) => `${label(p)} : ${p.fileName} (${formatSize(p.size)})`).join('\n');
  }

  // Note sur 5 en étoiles entières (« ★★★★☆ »).
  function starsText(rating) {
    const n = Math.min(5, Math.max(0, Math.round(Number(rating) || 0)));
    return '★'.repeat(n) + '☆'.repeat(5 - n);
  }

  // Notes de chaque source combinées par le serveur (« LaunchBox ★★★★☆ (120 votes) »).
  function ratingsText(ratings) {
    const name = { screenscraper: 'ScreenScraper', launchbox: 'LaunchBox', steam: 'Steam', press: t('info.press') };
    return (ratings || []).filter((r) => r.rating != null).map((r) => [
      name[r.source] || r.source,
      starsText(r.rating),
      r.count && `(${t(r.source === 'press' ? 'info.sites' : 'info.votes', { n: r.count })})`,
    ].filter(Boolean).join(' ')).join('\n');
  }

  function gameFacts(game) {
    const d = game.details || {};
    const region = (code) => (!code ? null : code.toLowerCase() === 'wor' ? t('info.world') : code.toUpperCase());
    const byRegion = (list) => (list || []).map((e) => (region(e.region) ? `${region(e.region)} : ${e.text}` : e.text)).join('\n');
    const list = (v) => (v || []).join(', ');
    const facts = [
      [t('info.otherTitles'), byRegion(d.otherTitles)],
      [t('info.releaseDates'), byRegion(d.releaseDates)],
      [t('info.regions'), list(d.regions)],
      [t('info.languages'), list(d.languages)],
      [t('info.ratings'), ratingsText(d.ratings)],
      [t('info.series'), d.series],
      // Coopération signalée par LaunchBox sans être citée dans les modes de jeu.
      [t('info.modes'), list([...(d.modes || []), ...(d.cooperative && !(d.modes || []).some((m) => /coop/i.test(m)) ? [t('criteria.players.coop')] : [])])],
      [t('info.themes'), list(d.themes)],
      [t('info.ageRatings'), (d.ageRatings || []).map((r) => [r.type, r.text].filter(Boolean).join(' ')).join(', ')],
      [t('info.serial'), d.serial],
      [t('info.romFlags'), list(d.romFlags)],
      [t('info.arcadeSet'), [d.arcadeSet, d.arcadeParent && `(${t('info.arcadeClone', { name: d.arcadeParent })})`].filter(Boolean).join(' ')],
      [t('info.resolution'), d.resolution],
      [t('info.rotation'), d.rotation],
      [t('info.controls'), d.controls],
      [t('info.features'), [d.rumble && t('info.rumble'), d.analog && t('info.analog')].filter(Boolean).join(', ')],
      [t('info.file'), game.fileName],
      [t('info.parts'), partsText(game)],
      [t('info.size'), formatSize(gameSize(game))],
      ...(zipped(game) ? [[t('info.sizeZip'), formatSize(game.packedSize)], [t('info.compression'), t(game.packed === 'zip' ? 'info.compressionZip' : 'info.compressionPartial', { format: { zst: 'zstd', xz: 'xz', mixed: 'zstd + xz + zip' }[game.packFormat] || 'zip' })]] : []),
      ['CRC32', game.crc32 && game.crc32.toUpperCase()],
      ['MD5', game.md5],
      ...(d.links || []).map((l) => [l.label, l.url.replace(/^https?:\/\//, '').split('/')[0], l.url]),
    ];
    return facts.filter(([, value]) => value);
  }

  // Prérequis d'un jeu PC (chemin de l'exécutable) -> nom affiché.
  const EXTRA_EXE_KINDS = [
    [/dxsetup|dxwebsetup|directx/i, 'DirectX'], [/vc_?redist|vcredist/i, 'Visual C++'], [/dotnet|ndp\d|netfx/i, '.NET'],
    [/physx/i, 'PhysX'], [/oalinst/i, 'OpenAL'], [/xnafx/i, 'XNA'], [/ue[34]?prereq/i, 'Unreal Engine'],
  ];

  /** Autre exécutable d'un jeu PC : « DirectX (DXSETUP.exe) », sinon son nom de fichier. */
  function extraExeLabel(path) {
    const name = path.split('/').pop();
    const kind = EXTRA_EXE_KINDS.find(([re]) => re.test(path))?.[1];
    return kind ? `${kind} (${name})` : name;
  }

  function gameInfoHtml(game) {
    const facts = gameFacts(game);
    if (!facts.length) return '';
    const rows = facts.map(([label, value, url]) => `<dt>${esc(label)}</dt><dd>${url
      ? `<a href="${esc(url)}" target="_blank" rel="noreferrer">${esc(value)}</a>`
      : esc(value)}</dd>`).join('');
    return `<div class="info"><h3>${esc(t('info.title'))}</h3><dl>${rows}</dl></div>`;
  }

  async function renderGame() {
    const { systemId, gameId } = S.route;
    const system = systemById(systemId);
    const game = S.detailGame?.id === gameId ? S.detailGame : findGame(gameId);
    setTopbar({ title: game?.title || '', subtitle: system?.name || '', right: settingsBtn() });
    const main = $('#main');
    if (!game || !system) {
      main.innerHTML = `<div class="centered"><p>${esc(t('detail.notFound'))}</p></div>`;
      return;
    }
    const downloaded = new Set(await call(rc.library.downloaded, [system], [game]));
    const multiDisc = downloaded.size > 0 && (game.parts || []).some((p) => p.kind === 'disc');
    const [emu, localPath, missingBios, emulators, command, localResume, online, history, issues, discs] = await Promise.all([
      call(rc.launcher.options, system),
      call(rc.library.path, system, game),
      system.biosCount ? call(rc.bios.missing, system).catch(() => []) : [],
      call(rc.emulators.list),
      call(rc.launcher.describe, system, game),
      call(rc.launcher.resumable, system, game),
      S.account.signedIn ? call(rc.launcher.resumableOnline, system, game).catch(() => null) : null,
      // Historique des états (moteur intégré) : null pour un autre émulateur.
      rc.states && downloaded.size ? call(rc.states.list, system, game).catch(() => null) : null,
      gameIssues(system, game),
      // Jeu en plusieurs disques : { count, resume (partie à reprendre sur chaque disque), latest }.
      multiDisc ? call(rc.launcher.resumeDiscs, system, game).catch(() => null) : null,
    ]);
    const savedStates = history?.states?.length || 0;
    // Jeu PC téléchargé : nouvelle version sur le serveur (seuls les fichiers changés sont téléchargés).
    const folderStatus = game.folder && downloaded.size ? await call(rc.library.folderStatus, system, game).catch(() => null) : null;
    // Jeu à plusieurs : compatibilité de l'émulateur choisi (relue : il a pu changer).
    await refreshNetplaySystem(system, true);
    // Partie à reprendre : état sur ce PC ou sauvegarde en ligne (récupérée au lancement si plus récente).
    // Jeu en plusieurs disques : disque choisi (celui de la dernière partie par défaut), « Reprendre »
    // reprend la dernière partie faite sur lui, « Jouer » démarre sur lui ; sauvegarde en ligne seule
    // (disque inconnu) : reprise sur le premier.
    let disc = discs ? Math.min(S.discChoice?.[game.id] ?? discs.latest ?? 0, discs.count - 1) : 0;
    const resumableOn = (d) => (discs ? Boolean(discs.resume[d]) || (Boolean(online) && !localResume && d === 0) : localResume || Boolean(online));
    let resumable = resumableOn(disc);
    if (S.route.name !== 'game' || S.route.gameId !== gameId) return;
    const st = statusOf(game, downloaded);
    const cover = mediaUrl(game, 'boxart', 'medium');
    const shot = mediaUrl(game, 'screenshot', 'medium');
    // Vignettes des captures ; en grand (clic) : les originaux.
    const shotThumbs = screenshotTypes(game).map((type) => mediaUrl(game, type, 'thumb'));
    const shots = screenshotTypes(game).map((type) => mediaUrl(game, type));
    const selected = emu.options.find((o) => o.id === emu.selected);
    const optionLabel = (o) => {
      if (o.kind === 'builtin' && o.name) return t(o.installed ? 'emu.builtinApp' : 'emu.builtinAppPending', { name: o.name });
      if (o.kind === 'builtin') return t(o.installed ? 'emu.builtin' : 'emu.builtinPending', { core: o.core });
      if (o.kind === 'retroarch') return t('emu.retroarch', { core: o.core });
      if (o.kind === 'emulator') return o.installed ? o.name : t('emu.notInstalled', { name: o.name });
      return t(`emu.${o.kind}`);
    };
    const catalogEmu = selected.kind === 'emulator' ? emulators.find((e) => e.id === selected.emuId) : null;
    // Moteur intégré (pas RPCS3, Cemu… : applications à part) : manettes des joueurs et touches.
    const builtinPads = !game.folder && selected.kind === 'builtin' && !selected.name;
    // Vita3K (sans ligne de commande personnalisée) : procédure d'installation du jeu.
    const vita = catalogEmu?.id === 'vita3k' && !catalogEmu.customArgs ? await call(rc.launcher.vitaStatus, system, game).catch(() => null) : null;
    const meta = [
      game.releaseDate && t('detail.release', { v: game.releaseDate }),
      game.genre,
      game.developer && t('detail.developer', { v: game.developer }),
      game.publisher && t('detail.publisher', { v: game.publisher }),
      game.players && t('detail.players', { v: game.players }),
      game.rating != null && t('detail.rating', { v: starsText(game.rating) }),
      game.version && t('detail.version', { v: game.version }),
    ].filter(Boolean);

    let actions = '';
    const favorite = S.favorites.some((g) => g.id === game.id);
    const favoriteBtn = `<button class="btn${favorite ? ' favorite-on' : ''}" id="favoriteBtn" title="${esc(t(favorite ? 'detail.favoriteRemove' : 'detail.favoriteAdd'))}">${icon(favorite ? 'favorite' : 'favoriteBorder', 18)} ${esc(t(favorite ? 'detail.favoriteRemove' : 'detail.favoriteAdd'))}</button>`;
    // Ce jeu proposé par un autre appareil : partie rejointe (jeu téléchargé d'abord s'il manque).
    const joinBtns = hostsOf(game).map((p) => `<button class="btn primary big" data-join-peer="${esc(p.id)}">${icon(p.hosting.link ? 'link' : 'group')} ${esc(t(p.hosting.link ? 'link.joinPeer' : 'netplay.joinPeer', { name: peerName(p) }))}</button>`).join('');
    if (st.kind === 'running') {
      actions = `<div style="flex:1"><div class="bars"><div class="bar"><div style="width:${Math.round(st.progress * 100)}%"></div></div>
        ${st.unpack != null ? `<div class="bar unpack"><div style="width:${Math.round(st.unpack * 100)}%"></div></div>` : ''}</div>
        <p class="muted">${esc(downloadProgressText(st))}</p></div>
        <button class="btn" id="cancelBtn">${icon('close', 18)} ${esc(t('download.cancelPercent', { p: Math.round(st.progress * 100) }))}</button>`;
    } else if (isLobbyHost()) {
      // Hôte d'un lobby : ce jeu devient celui du lobby (téléchargé au lancement de la partie).
      actions = playKind(game)
        ? `<button class="btn primary big" id="lobbySetGameBtn">${icon('group')} ${esc(t('lobby.setGame'))}</button>`
        : `<p class="muted">${esc(t('lobby.notNetwork'))}</p>`;
    } else if (st.kind === 'downloaded') {
      // Partie sauvegardée dans le moteur intégré : « Reprendre » d'abord, puis « Jouer » (nouvelle partie).
      actions = `${joinBtns}${resumable || discs ? `<button class="btn primary big" id="resumeBtn"${resumable ? '' : ' hidden'}>${icon('play')} ${esc(t('action.resume'))}</button>` : ''}
        ${issues.length
          // Émulateur externe à configurer : « Configurer » mène à l'encadré de l'émulateur au lieu de lancer le jeu.
          ? `<button class="btn primary big" id="configureBtn">${icon('gear')} ${esc(t('action.configure'))}</button>`
          : `<button class="btn ${resumable ? '' : 'primary '}big" id="playBtn">${icon(resumable ? 'refresh' : 'play')} ${esc(t('action.play'))}</button>`}
        ${savedStates ? `<button class="btn big" id="statesBtn">${icon('timer')} ${esc(t('states.button', { n: savedStates }))}</button>` : ''}
        ${S.account.signedIn && !S.lobby && playKind(game) ? `<button class="btn big" id="lobbyCreateGameBtn">${icon(playKind(game) === 'link' ? 'link' : 'group')} ${esc(t('lobby.create'))}</button>` : ''}
        ${S.account.signedIn && !S.lobby && lobbiesOf(game).length ? `<button class="btn big" id="lobbyJoinGameBtn">${icon('group')} ${esc(t('lobby.join'))}</button>` : ''}
        <button class="btn" id="folderBtn">${icon('folder', 18)} ${esc(t('detail.showInFolder'))}</button>
        <button class="btn danger" id="deleteBtn">${icon('trash', 18)} ${esc(t('detail.delete'))}</button>`;
    } else {
      actions = `${joinBtns}<button class="btn primary big" id="downloadBtn">${icon('cloud')} ${esc(st.kind === 'failed' ? t('app.retry') : t('download.withSize', { size: sizeLabel(game) }))}</button>`;
    }

    main.innerHTML = `<div class="detail">
      <div class="backdrop">${shot || cover ? `<img src="${esc(shot || cover)}" alt="">` : ''}</div>
      <div class="detail-body">
        <div class="cover-lg">${cover ? `<img src="${esc(cover)}" alt="">` : icon('pad', 64)}</div>
        <div class="col">
          <span class="system-name">${esc(system.name.toUpperCase())}</span>
          <h2>${esc(game.title)}</h2>
          <div class="muted">${meta.map(esc).join('  ·  ')}</div>
          ${playtimeHtml(game)}
          ${online ? `<div class="muted">${icon('cloud', 16)} ${esc(t('profile.onlineSave', { device: online.device || '?', date: new Date(online.savedAt).toLocaleString(window.I18N.language) }))}</div>` : ''}
          ${st.kind === 'downloaded' ? `<div class="ok">${esc(t('detail.onPc'))}</div>` : ''}
          ${st.kind === 'downloaded' && folderStatus?.updateAvailable
            ? `<div class="line"><span class="warn">${esc(
                game.version && folderStatus.localVersion && folderStatus.localVersion !== game.version
                  ? t('detail.updateFrom', { v: game.version, local: folderStatus.localVersion })
                  : game.version ? t('detail.update', { v: game.version }) : t('detail.updateUnknown'),
              )}</span> <button class="btn primary small" id="updateBtn">${icon('cloud', 18)} ${esc(t('detail.updateBtn'))}</button></div>`
            : ''}
          ${st.kind === 'failed' ? `<div class="error">${esc(t('status.failed', { error: st.error }))}</div>` : ''}
          ${st.kind === 'remote' ? `<div class="muted">${esc(t('detail.mustDownload'))}</div>` : ''}
          ${discs && st.kind === 'downloaded' && !isLobbyHost() ? `<div class="line disc-choice">${Array.from({ length: discs.count }, (_, d) => `<button class="chip${d === disc ? ' selected' : ''}${discs.resume[d] ? ' has-resume' : ''}" data-disc="${d}"${discs.resume[d] ? ` title="${esc(t('parts.discResume'))}"` : ''}>${esc(t('parts.disc', { n: d + 1 }))}</button>`).join('')}</div>` : ''}
          <div class="actions">${actions}${favoriteBtn}</div>
          ${system.biosCount && st.kind !== 'running' ? (missingBios.length
            ? `<div class="line"><span class="muted">${esc(t('bios.missing', { n: missingBios.length, size: biosSize(missingBios) }))}</span>${st.kind === 'downloaded' ? `<button class="btn ghost" id="biosBtn">${icon('cloud', 18)} ${esc(t('bios.download'))}</button>` : ''}</div>`
            : `<div class="ok">${esc(t('bios.present', { n: system.biosCount }))}</div>`) : ''}
          ${game.folder ? `<div class="emu" id="emuBox">
            <label class="muted">${esc(t('detail.exe'))}</label>
            <div class="mono">${esc(game.exe || t('detail.noExe'))}</div>
            ${game.extraExes?.length ? `<label class="muted">${esc(t('detail.extraExes'))}</label>
              <div class="line">${game.extraExes.map((p) => `<button class="btn ghost" data-extra-exe="${esc(p)}" title="${esc(p)}"${st.kind === 'downloaded' ? '' : ' disabled'}>${icon('play', 18)} ${esc(extraExeLabel(p))}</button>`).join('')}</div>` : ''}
          </div>` : `<div class="emu${issues.length ? ' blocked' : ''}" id="emuBox">
            ${issuesHtml(issues)}
            <label class="muted">${esc(t('detail.emulator'))}</label>
            <select id="emuSelect">${emu.options.map((o) => `<option value="${esc(o.id)}"${o.id === emu.selected ? ' selected' : ''}>${esc(optionLabel(o))}</option>`).join('')}</select>
            ${selected.kind === 'custom' ? `<label class="muted">${esc(t('detail.command'))}</label>
              <input type="text" id="emuCommand" value="${esc(emu.command)}" placeholder="${esc(t('detail.commandHint'))}">` : ''}
            ${selected.kind === 'retroarch' ? `<div><button class="btn ghost" id="raHelpBtn">${icon('info', 18)} ${esc(t('detail.configureRetroArch'))}</button></div>` : ''}
            ${selected.kind === 'builtin' && selected.installed ? `<div><button class="btn ghost" id="resetCoreBtn">${icon('refresh', 18)} ${esc(t('crash.reset'))}</button></div>` : ''}
            ${catalogEmu ? emulatorBlock(catalogEmu) : ''}
            ${vita ? vitaProcedureHtml(vita) : ''}
            ${command ? `<div class="muted small">${esc(t('emulator.command'))}</div><div class="mono">${esc(command)}</div>` : ''}
          </div>`}
          ${builtinPads ? '<section class="pads" id="padsBox"></section>' : ''}
          ${game.description ? `<div class="desc">${esc(game.description)}</div>` : ''}
          ${gameInfoHtml(game)}
          ${shots.length ? `<div class="shots">${shotThumbs.map((url, i) => `<button class="shot" data-shot="${i}" title="${esc(t('detail.screenshot', { n: i + 1, total: shots.length }))}"><img src="${esc(url)}" alt=""></button>`).join('')}</div>` : ''}
          <div class="mono muted">${esc(localPath)}</div>
        </div>
      </div>
    </div>`;

    // Partie en ligne d'un autre cœur intégré : reprise avec lui.
    const discOption = () => (discs ? { disk: disc } : {});
    $('#resumeBtn')?.addEventListener('click', () => playChecked(system, game, { resume: true, resumeCore: !localResume && online?.core ? online.core : undefined, ...discOption() }));
    $('#playBtn')?.addEventListener('click', () => playChecked(system, game, discOption()));
    // Choix du disque : boutons « Reprendre » et « Jouer » mis à jour sans tout redessiner.
    for (const btn of $$('[data-disc]', main)) {
      btn.addEventListener('click', () => {
        disc = Number(btn.dataset.disc);
        S.discChoice = { ...S.discChoice, [game.id]: disc };
        resumable = resumableOn(disc);
        for (const other of $$('[data-disc]', main)) other.classList.toggle('selected', other === btn);
        const resumeBtn = $('#resumeBtn');
        if (resumeBtn) resumeBtn.hidden = !resumable;
        const playBtn = $('#playBtn');
        if (playBtn) {
          playBtn.classList.toggle('primary', !resumable);
          playBtn.innerHTML = `${icon(resumable ? 'refresh' : 'play')} ${esc(t('action.play'))}`;
        }
      });
    }
    $('#configureBtn')?.addEventListener('click', () => $('#emuBox').scrollIntoView({ behavior: 'smooth', block: 'center' }));
    $('#statesBtn')?.addEventListener('click', () => showStates(system, game));
    for (const btn of $$('[data-shot]')) btn.addEventListener('click', () => showScreenshot(shots, Number(btn.dataset.shot)));
    $('#lobbyCreateGameBtn')?.addEventListener('click', () => createLobby(game));
    $('#lobbyJoinGameBtn')?.addEventListener('click', () => joinLobbyOf(game));
    $('#lobbySetGameBtn')?.addEventListener('click', () => setLobbyGame(game));
    for (const btn of $$('[data-join-peer]')) {
      btn.addEventListener('click', () => joinNetplay(S.peers.find((p) => p.id === btn.dataset.joinPeer && p.hosting)));
    }
    $('#downloadBtn')?.addEventListener('click', () => startDownload(system, game, false, missingBios));
    // Mise à jour du jeu PC : téléchargement des fichiers changés (les autres gardés).
    $('#updateBtn')?.addEventListener('click', () => startDownload(system, game, false));
    $('#favoriteBtn')?.addEventListener('click', () => toggleFavorite(game));
    $('#biosBtn')?.addEventListener('click', async () => {
      await call(rc.downloads.dismiss, game.id);
      await call(rc.downloads.start, system, game, { bios: missingBios, includeRom: false });
    });
    $('#cancelBtn')?.addEventListener('click', () => rc.downloads.cancel(game.id));
    $('#folderBtn')?.addEventListener('click', () => rc.shell.showItem(localPath));
    for (const btn of $$('[data-extra-exe]', main)) btn.addEventListener('click', () => call(rc.library.runExtra, system, game, btn.dataset.extraExe));
    $('#raHelpBtn')?.addEventListener('click', () => showRetroArchHelp(system));
    $('#resetCoreBtn')?.addEventListener('click', () => confirmResetCore(system.id, selected.core));
    if (builtinPads) startPads(system, selected.core);
    else stopPads();
    if (catalogEmu) bindEmulatorBlock($('#main'), catalogEmu, () => renderGame());
    $('#vitaLicenseSave')?.addEventListener('click', async () => {
      await call(rc.launcher.setVitaLicense, game.id, $('#vitaLicense').value);
      toast(t('vita.licenseSaved'));
      renderGame();
    });
    $('#deleteBtn')?.addEventListener('click', () => modal({
      title: t('detail.deleteTitle'),
      body: `<p>${esc(t(game.folder && game.uninstallExe ? 'detail.deleteUninstallText' : 'detail.deleteText', { exe: game.uninstallExe }))}</p>`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost' },
        {
          label: t('detail.delete'),
          kind: 'danger',
          onClick: async () => {
            // Jeu PC avec un désinstalleur : désinstallation à terminer dans sa fenêtre.
            const removed = await call(rc.library.remove, system, game);
            if (removed?.uninstalling) toast(t('detail.uninstalling', { title: game.title }));
            await refreshDownloaded();
            loadUsage();
            renderGame();
          },
        },
      ],
    }));
    if ($('#emuSelect')) $('#emuSelect').onchange = async (e) => {
      await call(rc.launcher.choose, system.id, e.target.value);
      renderGame();
    };
    $('#emuCommand')?.addEventListener('change', (e) => call(rc.launcher.choose, system.id, 'custom', e.target.value));
  }

  /** Bloc d'un émulateur du catalogue : emplacement, téléchargement, ligne de commande. */
  function emulatorBlock(emu, { compact = false } = {}) {
    const status = emu.path
      ? `<div class="good">${esc(t('emulator.found', { path: emu.path }))}</div>`
      : `<div class="bad">${esc(t('emulator.notFound'))}</div><div class="muted">${esc(t('emulator.defaultLocation', { path: emu.defaultLocation }))}</div>`;
    const args = emu.direct || emu.customArgs
      ? `<label class="muted">${esc(t(compact ? 'emulator.argsShort' : 'emulator.args'))}<input type="text" data-emu-args="${esc(emu.id)}" value="${esc(emu.customArgs)}" placeholder="${esc(emu.defaultArgs)}"></label>`
      : emu.id === 'vita3k' ? '' : `<div class="muted">${esc(t('emulator.manual', { name: emu.name }))}</div>`;
    return `<div class="emu-block" data-emu="${esc(emu.id)}">
      ${compact ? '' : status}
      <div class="emu-actions">
        ${emu.path ? `<button class="btn" data-emu-launch>${icon('open', 18)} ${esc(t('emulator.launch', { name: emu.name }))}</button>` : `<button class="btn primary" data-emu-download>${icon('cloud', 18)} ${esc(t('emulator.download', { name: emu.name }))}</button>`}
        <button class="btn" data-emu-browse>${icon('folder', 18)} ${esc(t(emu.folder ? 'emulator.browseFolder' : 'emulator.browse'))}</button>
        ${emu.path ? `<button class="btn ghost" data-emu-download>${esc(t('emulator.website'))}</button>` : `<button class="btn ghost" data-emu-detect>${icon('search', 18)} ${esc(t('emulator.detect'))}</button>`}
        ${emu.updatable ? `<button class="btn ghost" data-emu-check>${icon('refresh', 18)} ${esc(t('emulator.checkUpdate'))}</button>` : ''}
      </div>
      ${emu.updatable ? '<div class="emu-update" data-emu-update></div>' : ''}
      ${emu.id === 'eden' ? `<div class="muted small">${esc(t('emulator.edenSetup'))}</div>` : ''}
      ${args}
    </div>`;
  }

  /**
   * Procédure Vita3K selon le jeu : .vpk / .zip installés puis démarrés au premier lancement, .pkg
   * installés avec leur licence (zRIF), jeu déjà installé démarré directement.
   */
  function vitaProcedureHtml(vita) {
    const step = (key, vars) => `<li>${esc(t(key, vars))}</li>`;
    const steps = [step('vita.stepFirmware')];
    if (vita.installed) steps.push(step('vita.stepInstalled', { id: vita.titleId }));
    else if (vita.format === 'vpk' || vita.format === 'zip') steps.push(step('vita.stepArchive'));
    else if (vita.format === 'pkg' && !vita.titleId) steps.push(step('vita.stepNoTitleId'));
    else if (vita.format === 'pkg') {
      steps.push(`<li>${esc(t('vita.stepLicense'))}
        <div class="line"><input type="text" id="vitaLicense" class="mono" value="${esc(vita.license || '')}" placeholder="KO5ifR1dQ+eHBl…">
        <button class="btn" id="vitaLicenseSave">${esc(t('vita.licenseSave'))}</button></div>
        ${vita.license ? `<div class="${vita.licenseFromServer ? 'muted' : 'ok'}">${esc(t(vita.licenseFromServer ? 'vita.licenseServer' : 'vita.licenseKnown'))}</div>` : ''}</li>`);
      steps.push(step('vita.stepPkgInstall', { id: vita.titleId }));
    } else steps.push(step('vita.stepFormat', { format: vita.format }));
    return `<div class="vita-steps">
      <h4>${esc(t('vita.title'))}</h4>
      <ol>${steps.join('')}</ol>
      ${vita.appDir ? `<div class="muted small">${esc(t('vita.appDir', { dir: vita.appDir }))}</div>` : ''}
    </div>`;
  }

  function bindEmulatorBlock(root, emu, refresh) {
    const block = $(`[data-emu="${emu.id}"]`, root);
    if (!block) return;
    // Émulateur absent : dernière version installée par RomCloud si possible ; présent : son site.
    for (const b of $$('[data-emu-download]', block)) b.onclick = () => (emu.path ? rc.shell.openExternal(emu.url) : downloadEmulator(emu, b));
    $('[data-emu-browse]', block).onclick = () => locateEmulator(emu);
    $('[data-emu-detect]', block)?.addEventListener('click', (e) => detectEmulators(e.currentTarget));
    $('[data-emu-launch]', block)?.addEventListener('click', async () => {
      try {
        await call(rc.emulators.launch, emu.id);
      } catch (err) {
        toast(err.message, { type: 'error' });
      }
    });
    $('[data-emu-args]', block)?.addEventListener('change', async (e) => {
      await call(rc.emulators.setArgs, emu.id, e.target.value);
      refresh();
    });
    $('[data-emu-check]', block)?.addEventListener('click', (e) => checkEmulatorUpdate(emu, block, e.currentTarget, refresh));
  }

  /**
   * Dernière version de l'émulateur (Eden) : comparée à celle installée, avec un bouton pour
   * l'installer (même variante que l'actuelle, dans son dossier).
   */
  async function checkEmulatorUpdate(emu, block, button, refresh) {
    const box = $('[data-emu-update]', block);
    button.disabled = true;
    box.innerHTML = `<span class="muted">${esc(t('emulator.checking'))}</span>`;
    try {
      const u = await call(rc.emulators.checkUpdate, emu.id);
      const installed = u.installed ? t('emulator.installedVersion', { version: u.installed, variant: u.variant }) : t('emulator.notInstalledVersion');
      const status = !u.asset ? t('emulator.noBuild') : u.updateAvailable ? t(u.installed ? 'emulator.updateAvailable' : 'emulator.installAvailable', { version: u.latest }) : t('emulator.upToDate');
      box.innerHTML = `<div class="${u.updateAvailable ? 'good' : 'muted'}">${esc(installed)} · ${esc(t('emulator.latestVersion', { version: u.latest }))} — ${esc(status)}</div>
        ${u.updateAvailable ? `<div class="line"><button class="btn primary" data-emu-install>${icon('cloud', 18)} ${esc(t(u.installed ? 'emulator.updateTo' : 'emulator.installVersion', { version: u.latest }))}</button>
          <span class="muted small">${esc(t('emulator.installHint', { file: u.asset, dir: u.dir }))}</span></div>` : ''}`;
      $('[data-emu-install]', box)?.addEventListener('click', async (e) => {
        const install = e.currentTarget;
        install.disabled = true;
        install.textContent = t('emulator.installing');
        try {
          const done = await call(rc.emulators.update, emu.id);
          toast(t('emulator.installed', { name: emu.name, version: done.installed || done.latest }));
          refresh();
        } catch (err) {
          toast(err.message, { type: 'error' });
          install.disabled = false;
          install.textContent = t(u.installed ? 'emulator.updateTo' : 'emulator.installVersion', { version: u.latest });
        }
      });
    } catch (err) {
      box.innerHTML = `<div class="bad">${esc(err.message)}</div>`;
    } finally {
      button.disabled = false;
    }
  }

  // ---------------------------------------------------------------------------
  // Manettes du moteur intégré (fiche du jeu) : romcloud-player sans fenêtre (pads.rs) donne les
  // manettes des joueurs, les boutons de la console et, pour chacun, l'élément de la manette qui le
  // commande ; mêmes réglages que le menu du jeu (« Joueurs des manettes », « Configurer la manette »).
  // ---------------------------------------------------------------------------

  // Illustrations des manettes (pads/<nom>.webp, desktop/native/player/pads/generate_pads.py), manette de chaque console.
  const CONSOLE_PADS = {
    nes: 'nes', fds: 'famicom', snes: 'snes', snesmsu1: 'snes', satellaview: 'snes', n64: 'n64', gc: 'gamecube', ngc: 'gamecube',
    wii: 'wii', psx: 'psx', ps2: 'ps2', genesis: 'genesis', genesismsu: 'genesis', segacd: 'genesis', sega32x: 'genesis',
    pico: 'genesis', master: 'master', sg1000: 'master', gamegear: 'gamegear', saturn: 'saturn', stv: 'saturn', dreamcast: 'dreamcast', naomi: 'dreamcast',
    atomiswave: 'dreamcast', tg16: 'pcengine', tgcd: 'pcengine', supergrafx: 'pcengine', neogeo: 'neogeo', neogeocd: 'neogeo',
    atari2600: 'atari2600', gb: 'gameboy', gbc: 'gameboy', megaduck: 'gameboy', gw: 'gameboy', pokemini: 'gameboy',
    supervision: 'gameboy', gba: 'gba', psp: 'psp', pspminis: 'psp', ngp: 'ngp', ngpc: 'ngp', cps1: 'cps', cps2: 'cps',
    cps3: 'cps', fbneo: 'arcade', mame: 'arcade', gx4000: 'gx4000', atari7800: 'atari7800', lynx: 'lynx',
  };
  const consolePadImage = (system) => {
    const name = CONSOLE_PADS[(system.shortname || '').toLowerCase()] || CONSOLE_PADS[system.id];
    return name ? `pads/${name}.webp` : null;
  };

  /** Manette branchée (type donné par SDL, nom) : son image et la famille des noms de ses boutons. */
  function controllerOf(pad) {
    const kind = pad.kind || '';
    if (/^ps[345]$/.test(kind)) return { image: kind, family: 'ps' };
    if (kind.startsWith('switch') || kind.startsWith('joycon')) return { image: 'joycon', family: 'nintendo' };
    if (kind === 'xbox360') return { image: 'xbox360', family: 'xbox' };
    if (kind === 'xboxone') return { image: /series/i.test(pad.name || '') ? 'xboxseries' : 'xboxone', family: 'xbox' };
    return { image: 'xboxseries', family: 'xbox' };
  }

  /** Image d'un joueur : manette de la console du jeu, sinon celle qui est branchée. */
  const playerPadImage = (pad) => consolePadImage(S.pads.system) || `pads/${controllerOf(pad).image}.webp`;

  // Éléments de SDL (position sur la manette : « a » = bouton du bas) -> nom sur chaque famille.
  const PAD_BUTTON_NAMES = {
    xbox: { a: 'A', b: 'B', x: 'X', y: 'Y', leftshoulder: 'LB', rightshoulder: 'RB', lefttrigger: 'LT', righttrigger: 'RT', leftstick: 'LS', rightstick: 'RS', start: 'Menu', back: 'View', guide: 'Xbox' },
    ps: { a: '✕', b: '○', x: '□', y: '△', leftshoulder: 'L1', rightshoulder: 'R1', lefttrigger: 'L2', righttrigger: 'R2', leftstick: 'L3', rightstick: 'R3', start: 'Options', back: 'Share', guide: 'PS' },
    nintendo: { a: 'B', b: 'A', x: 'Y', y: 'X', leftshoulder: 'L', rightshoulder: 'R', lefttrigger: 'ZL', righttrigger: 'ZR', leftstick: 'L3', rightstick: 'R3', start: '+', back: '−', guide: 'Home' },
  };

  /** Nom de l'élément de la manette (« a », « dpup », « +rightx », source brute « b7 »…) pour sa famille. */
  function padElementLabel(element, family) {
    if (!element) return '—';
    const arrows = { up: '↑', down: '↓', left: '←', right: '→' };
    let m = /^dp(up|down|left|right)$/.exec(element);
    if (m) return t('pads.dpad', { dir: arrows[m[1]] });
    m = /^([+-]?)(left|right)([xy])$/.exec(element);
    if (m) {
      const dir = !m[1] ? (m[3] === 'x' ? '↔' : '↕') : m[3] === 'x' ? (m[1] === '+' ? '→' : '←') : m[1] === '+' ? '↓' : '↑';
      return t(m[2] === 'left' ? 'pads.leftStick' : 'pads.rightStick', { dir });
    }
    m = /^b(\d+)$/.exec(element);
    if (m) return t('pads.button', { n: Number(m[1]) + 1 });
    m = /^h\d+\.(\d+)$/.exec(element);
    if (m) return t('pads.dpad', { dir: { 1: '↑', 2: '→', 4: '↓', 8: '←' }[m[1]] || '?' });
    m = /^([+-]?)a(\d+)~?$/.exec(element);
    if (m) return `${t('pads.axis', { n: Number(m[2]) + 1 })}${m[1] ? ` ${m[1]}` : ''}`;
    return (PAD_BUTTON_NAMES[family] || PAD_BUTTON_NAMES.xbox)[element] || element;
  }

  /** Service des manettes pour le système du jeu (relancé seulement si le système ou le cœur change). */
  function startPads(system, core) {
    const key = `${system.id}|${core}`;
    if (S.pads.key !== key) {
      S.pads = { key, system, steps: [], pads: [], wizard: null, order: null, shown: null, error: null };
      call(rc.pads.start, system, core).catch((err) => {
        S.pads.error = err.message;
        paintPads();
      });
    }
    paintPads();
  }

  function stopPads() {
    if (!S.pads.key) return;
    S.pads = { key: null };
    document.body.classList.remove('pad-capture');
    rc.pads.stop();
  }

  function paintPads() {
    const box = $('#padsBox');
    const p = S.pads;
    if (!box || !p.key) return;
    const connected = p.pads.filter((x) => x.name);
    const shown = connected.find((x) => x.port === p.shown) || connected[0];
    const family = shown ? controllerOf(shown).family : 'xbox';
    const image = consolePadImage(p.system);
    const player = (port) => {
      const pad = p.pads.find((x) => x.port === port) || { port };
      // Manette de la console du jeu (sinon celle branchée) ; joueur 1 sans manette : le clavier.
      const tile = pad.name ? playerPadImage(pad) : port === 0 ? 'pads/keyboard.webp' : null;
      const name = pad.name || (port === 0 ? t('pads.keyboard') : pad.saved ? t('pads.unplugged') : t('pads.none'));
      return `<div class="pad-player${tile ? '' : ' empty'}">
        <div class="pad-tile">${tile ? `<img src="${tile}" alt="">` : icon('pad', 32)}</div>
        <strong>${esc(t('pads.player', { n: port + 1 }))}</strong><span class="muted small">${esc(name)}</span></div>`;
    };
    box.innerHTML = `<h3>${esc(t('pads.title'))}</h3>
      ${p.error ? `<p class="bad">${esc(p.error)}</p>` : ''}
      <div class="pad-players">${[0, 1, 2, 3].map(player).join('')}</div>
      <div class="line"><button class="btn" id="padOrderBtn"${connected.length ? '' : ' disabled'}>${icon('group', 18)} ${esc(t('pads.order'))}</button></div>
      <div class="pad-layout">
        ${image ? `<div class="pad-tile big"><img src="${image}" alt="${esc(p.system.name)}"></div>` : ''}
        <div class="grow">
          ${connected.length > 1 ? `<div class="line">${connected.map((x) => `<button class="chip${x === shown ? ' selected' : ''}" data-pad-shown="${x.port}">${esc(t('pads.player', { n: x.port + 1 }))}</button>`).join('')}</div>` : ''}
          ${connected.length ? `<table class="pad-binds"><tr><th>${esc(p.system.name)}</th><th>${esc(shown.name)}</th></tr>
            ${p.steps.map((label, i) => `<tr><td>${esc(label)}</td><td>${esc(padElementLabel(shown.elements?.[i], family))}</td></tr>`).join('')}</table>`
            : `<p class="muted">${esc(t('pads.noPad'))}</p>`}
        </div>
      </div>
      <div class="line"><button class="btn" id="padConfigBtn"${connected.length ? '' : ' disabled'}>${icon('pad', 18)} ${esc(t('pads.configure'))}</button></div>
      <p class="muted small">${esc(t('pads.hint', { system: p.system.name }))}</p>`;
    for (const el of $$('[data-pad-shown]', box)) {
      el.onclick = () => {
        p.shown = Number(el.dataset.padShown);
        paintPads();
      };
    }
    $('#padOrderBtn', box).onclick = openPadOrder;
    $('#padConfigBtn', box).onclick = openPadWizard;
  }

  /** Fenêtre de la page qui lit les manettes : navigation à la manette suspendue, rétablie à sa fermeture. */
  function padCaptureModal(options, onClosed) {
    document.body.classList.add('pad-capture');
    const root = modal(options);
    const dialog = $('.modal', root);
    if (options.wide) dialog.classList.add('wide');
    const observer = new MutationObserver(() => {
      if (dialog.isConnected) return;
      observer.disconnect();
      document.body.classList.remove('pad-capture');
      onClosed?.();
    });
    observer.observe(root, { childList: true });
    return root;
  }

  function closePadModal() {
    if ($('#padOrderBody') || $('#padWizardBody')) $('#modalRoot').innerHTML = '';
  }

  /** Joueurs des manettes : chaque joueur appuie à son tour sur un bouton de sa manette. */
  function openPadOrder() {
    S.pads.order = [];
    padCaptureModal({
      title: t('pads.orderTitle'),
      body: '<div id="padOrderBody"></div>',
      buttons: [
        { label: t('app.cancel'), kind: 'ghost', left: true },
        { label: t('pads.orderDone'), kind: 'primary', onClick: () => savePadOrder() },
      ],
    }, () => (S.pads.order = null));
    paintPadModal();
  }

  function savePadOrder() {
    const order = S.pads.order || [];
    if (!order.length) return;
    call(rc.pads.command, `order ${order.join(',')}`);
    toast(t('pads.orderSaved'));
  }

  function onPadPress(e) {
    const p = S.pads;
    if (!p.order || !$('#padOrderBody') || p.order.includes(e.port)) return;
    p.order.push(e.port);
    // Toutes les manettes branchées ont un joueur : enregistré.
    if (p.order.length >= p.pads.filter((x) => x.name).length) {
      savePadOrder();
      closePadModal();
      return;
    }
    paintPadModal();
  }

  /** Configuration d'une manette, étape par étape, comme dans le menu du jeu. */
  function openPadWizard() {
    S.pads.wizard = { device: '', step: 0, message: '', assigned: [] };
    call(rc.pads.command, 'config');
    padCaptureModal({
      title: t('pads.configTitle'),
      wide: true,
      body: '<div id="padWizardBody"></div>',
      buttons: [
        { label: t('app.cancel'), kind: 'ghost', left: true, keepOpen: true, onClick: () => call(rc.pads.command, 'cancel') },
        { label: t('pads.reset'), keepOpen: true, onClick: () => call(rc.pads.command, 'reset') },
        { label: t('pads.skip'), kind: 'primary', keepOpen: true, onClick: () => call(rc.pads.command, 'skip') },
      ],
    }, () => {
      if (S.pads.wizard) call(rc.pads.command, 'cancel');
    });
    paintPadModal();
  }

  /**
   * Illustration de la manette de la console pendant sa configuration (comme dans le menu du jeu) :
   * repère de la couleur de l'application sur le bouton demandé, vert sur ceux déjà attribués,
   * discret sur les autres ; vide sans illustration ou sans repère.
   */
  function padArt(p, w) {
    if (!p.art || !p.spots?.some(Boolean)) return '';
    const circles = p.spots.map((spot, i) => {
      if (!spot) return '';
      const [x, y, r] = spot;
      if (w.device && i === w.step) return `<circle class="current" cx="${x}" cy="${y}" r="${r + 1.5}"/><circle class="ring" cx="${x}" cy="${y}" r="${r + 3}"/>`;
      return `<circle class="${w.assigned[i] ? 'done' : 'idle'}" cx="${x}" cy="${y}" r="${r + 1.5}"/>`;
    }).join('');
    // Partie de l'image où est la manette (sans le fond autour), comme le moteur.
    return `<svg class="pad-art" viewBox="10 55 271 190"><image href="pads/${esc(p.art)}.webp" width="291" height="306"/>${circles}</svg>`;
  }

  function paintPadModal() {
    const p = S.pads;
    const order = $('#padOrderBody');
    if (order && p.order) {
      const next = p.order.length;
      order.innerHTML = `<p class="pad-prompt">${esc(t('pads.orderPrompt', { n: next + 1 }))}</p>
        <div class="pad-players">${[0, 1, 2, 3].map((n) => {
          const pad = p.pads.find((x) => x.port === p.order[n]);
          return `<div class="pad-player${pad ? '' : ' empty'}${n === next ? ' current' : ''}">
            <div class="pad-tile">${pad ? `<img src="${playerPadImage(pad)}" alt="">` : icon('pad', 32)}</div>
            <strong>${esc(t('pads.player', { n: n + 1 }))}</strong><span class="muted small">${esc(pad?.name || '…')}</span></div>`;
        }).join('')}</div>`;
    }
    const wizard = $('#padWizardBody');
    const w = p.wizard;
    if (!wizard || !w) return;
    const image = consolePadImage(p.system);
    const current = p.steps[w.step];
    const art = padArt(p, w);
    wizard.innerHTML = `<div class="pad-wizard${art ? ' with-art' : ''}">
        ${art || (image ? `<div class="pad-tile big"><img src="${image}" alt=""></div>` : '')}
        <div class="grow">
          <p class="pad-prompt">${esc(w.device ? t('pads.press', { button: current || '' }) : t('pads.pressAny'))}</p>
          ${w.device ? `<p class="muted small">${esc(t('pads.configuring', { name: w.device }))}</p>` : ''}
          ${w.message ? `<p class="bad">${esc(w.message)}</p>` : ''}
          <ol class="pad-steps">${p.steps.map((label, i) => `<li class="${i === w.step && w.device ? 'current' : ''}${w.assigned[i] ? ' done' : ''}">
            <span>${esc(label)}</span><span class="muted">${esc(w.assigned[i] || '')}</span></li>`).join('')}</ol>
        </div>
      </div>
      <p class="muted small">${esc(t('pads.wizardHint', { system: p.system.name }))}</p>`;
    // Passer et Par défaut : une fois la manette choisie.
    const buttons = $$('#modalRoot .foot .btn');
    for (const b of buttons.slice(1)) b.disabled = !w.device;
  }

  // ---------------------------------------------------------------------------
  // Paramètres
  // ---------------------------------------------------------------------------

  async function renderSettings() {
    const s = S.settings;
    const [version, emulators, keys] = await Promise.all([call(rc.app.version), call(rc.emulators.list), call(rc.keyboard.layout)]);
    // Nom des systèmes du serveur quand ils existent, sinon l'identifiant court.
    const systemLabel = (id) => S.systems.find((x) => x.shortname === id || x.id === id)?.name;
    setTopbar({ title: t('settings.title') });
    const langs = [['system', t('settings.system')], ['fr', 'Français'], ['en', 'English']];
    $('#main').innerHTML = `<div class="page"><div class="settings">
      <h3>${esc(t('settings.server'))}</h3>
      <label>${esc(t('settings.serverUrl'))}<input type="text" id="serverUrl" value="${esc(s.serverUrl)}"></label>
      <label>${esc(t('settings.apiKey'))}<input type="password" id="apiKey" value="${esc(s.apiKey)}"></label>
      <div class="line"><button class="btn" id="testBtn">${esc(t('settings.test'))}</button><span id="testResult" class="muted" style="align-self:center"></span></div>

      <h3>${esc(t('settings.language'))}</h3>
      <div class="line">${langs.map(([code, label]) => `<button class="chip${s.language === code ? ' selected' : ''}" data-lang="${code}">${esc(label)}</button>`).join('')}</div>

      <h3>${esc(t('report.section'))}</h3>
      <p class="muted">${esc(t('report.hint'))}</p>
      <div class="line"><button class="btn" id="reportBtn">${esc(t('report.send'))}</button></div>

      <h3>${esc(t('settings.display'))}</h3>
      <label class="check"><input type="checkbox" id="hideUnidentified"${s.hideUnidentified ? ' checked' : ''}> ${esc(t('settings.hideUnidentified'))}</label>
      <p class="muted">${esc(t('settings.hideUnidentifiedHint'))}</p>
      <label class="check"><input type="checkbox" id="autoUploadStates"${s.autoUploadStates !== false ? ' checked' : ''}> ${esc(t('settings.autoUploadStates'))}</label>
      <p class="muted">${esc(t('settings.autoUploadStatesHint'))}</p>
      <label>${esc(t('settings.gameOrder'))}</label>
      <div class="line">${['name', 'rating'].map((o) => `<button class="chip${(s.gameOrder || 'name') === o ? ' selected' : ''}" data-order="${o}">${esc(t(`settings.order.${o}`))}</button>`).join('')}</div>
      <p class="muted">${esc(t('settings.gameOrderHint'))}</p>

      <h3>${esc(t('settings.storage'))}</h3>
      <label>${esc(t('settings.romsDir'))}<span class="line"><input type="text" id="romsDir" value="${esc(s.romsDir)}"><button class="btn" id="romsBrowse">${esc(t('settings.browse'))}</button></span></label>
      <label>${esc(t('settings.biosDir'))}<span class="line"><input type="text" id="biosDir" value="${esc(s.biosDir)}"><button class="btn" id="biosBrowse">${esc(t('settings.browse'))}</button></span></label>
      <p class="muted">${esc(t('settings.biosHint', { dir: s.effectiveBiosDir }))}</p>

      <h3>${esc(t('settings.retroarch'))}</h3>
      <label>${esc(t('settings.retroarchPath'))}<span class="line"><input type="text" id="retroarchPath" value="${esc(s.retroarchPath)}" placeholder="C:\\RetroArch-Win64\\retroarch.exe"><button class="btn" id="raBrowse">${esc(t('settings.browse'))}</button></span></label>
      <p class="muted">${esc(t('settings.retroarchHint'))}</p>

      <h3>${esc(t('settings.emulators'))}</h3>
      <p class="muted">${esc(t('settings.emulatorsHint'))}</p>
      <div class="line"><button class="btn" id="detectBtn">${icon('search', 18)} ${esc(t('emulator.detect'))}</button></div>
      <div class="emu-list">${emulators.map((e) => `<div class="emu-item">
        <div class="emu-head"><strong>${esc(e.name)}</strong>
          <span class="${e.path ? 'good' : 'muted'}">${esc(e.path || t('emulator.notFound'))}</span></div>
        <div class="muted small">${esc(t('settings.emulatorSystems', { list: e.systems.map((id, i) => systemLabel(id) || e.systemNames[i]).join(', ') }))}${e.direct ? '' : ` · ${esc(t('emulator.noDirect'))}`}</div>
        ${emulatorBlock(e, { compact: true })}
      </div>`).join('')}</div>

      <h3>${esc(t('settings.keyboard'))}</h3>
      <p class="muted">${esc(t('settings.keyboardHint'))}</p>
      <div class="key-grid">${keys.buttons.map((b) => `<div class="key-row">
        <span>${esc(buttonLabel(b))}</span>
        <button class="btn key-btn" data-key="${b}"></button>
        <button class="icon-btn" data-key-clear="${b}" title="${esc(t('settings.keyClear'))}">${icon('close', 16)}</button>
      </div>`).join('')}</div>
      <div class="line"><button class="btn" id="keysReset">${esc(t('settings.keyReset'))}</button></div>

      <div class="line" style="margin-top:14px"><button class="btn primary" id="saveBtn">${esc(t('app.save'))}</button></div>
      <p class="muted">${esc(t('settings.version', { v: version }))}</p>
    </div></div>`;

    $('#testBtn').onclick = async () => {
      const out = $('#testResult');
      out.className = 'muted';
      out.textContent = t('settings.testing');
      try {
        const info = await call(rc.api.test, $('#serverUrl').value, $('#apiKey').value);
        out.className = 'good';
        out.textContent = t('settings.connected', { name: info.name, version: info.version });
      } catch (err) {
        out.className = 'bad';
        out.textContent = err.message;
      }
    };
    for (const el of $$('[data-lang]')) {
      el.onclick = async () => {
        S.settings = await call(rc.settings.save, { language: el.dataset.lang });
        window.I18N.setLanguage(S.settings.effectiveLanguage);
        renderSettings();
      };
    }
    // Appliqué tout de suite : listes de jeux rechargées à la prochaine ouverture d'un système.
    $('#hideUnidentified').onchange = async (e) => {
      S.settings = await call(rc.settings.save, { hideUnidentified: e.target.checked });
      S.games = { ...S.games, systemId: null, list: [] };
    };
    $('#reportBtn').onclick = showReport;
    $('#autoUploadStates').onchange = async (e) => {
      S.settings = await call(rc.settings.save, { autoUploadStates: e.target.checked });
    };
    for (const el of $$('[data-order]')) {
      el.onclick = async () => {
        S.settings = await call(rc.settings.save, { gameOrder: el.dataset.order });
        S.games = { ...S.games, systemId: null, list: [] };
        for (const chip of $$('[data-order]')) chip.classList.toggle('selected', chip === el);
      };
    }
    $('#romsBrowse').onclick = async () => {
      const dir = await call(rc.dialog.pickFolder);
      if (dir) $('#romsDir').value = dir;
    };
    $('#biosBrowse').onclick = async () => {
      const dir = await call(rc.dialog.pickFolder);
      if (dir) $('#biosDir').value = dir;
    };
    $('#detectBtn').onclick = (e) => detectEmulators(e.currentTarget);
    for (const e of emulators) bindEmulatorBlock($('#main'), e, () => renderSettings());
    bindKeyboard(keys);
    $('#raBrowse').onclick = async () => {
      const file = await call(rc.dialog.pickFile, [{ name: t('settings.exe'), extensions: ['exe'] }]);
      if (file) $('#retroarchPath').value = file;
    };
    $('#saveBtn').onclick = async () => {
      const before = S.settings.serverUrl + S.settings.apiKey;
      S.settings = await call(rc.settings.save, {
        serverUrl: $('#serverUrl').value,
        apiKey: $('#apiKey').value.trim(),
        romsDir: $('#romsDir').value,
        biosDir: $('#biosDir').value,
        retroarchPath: $('#retroarchPath').value.trim(),
      });
      toast(t('settings.saved'));
      if (before !== S.settings.serverUrl + S.settings.apiKey) {
        S.games = { ...S.games, systemId: null, list: [] };
        await loadSystems();
      }
      S.history = [];
      go({ name: 'systems' }, { replace: true });
    };
  }

  // ---------------------------------------------------------------------------
  // Téléchargements (événements du processus principal)
  // ---------------------------------------------------------------------------

  rc.pads.onEvent(({ event: e }) => {
    const p = S.pads;
    if (!p.key) return;
    if (e.type === 'press') return onPadPress(e);
    if (e.type === 'steps') Object.assign(p, { steps: e.steps, art: e.art, spots: e.spots || [] });
    else if (e.type === 'pads') p.pads = e.pads;
    else if (e.type === 'wizard') p.wizard = e;
    else if (e.type === 'wizardDone') {
      p.wizard = null;
      closePadModal();
      if (e.result) toast(e.result);
    }
    paintPads();
    paintPadModal();
  });

  rc.downloads.onUpdate(async ({ gameId, state, event }) => {
    if (state) S.downloads[gameId] = state;
    else delete S.downloads[gameId];
    if (event?.type === 'completed') {
      await refreshDownloaded();
      loadUsage();
      if (S.pendingJoin?.gameId === gameId) {
        const pending = S.pendingJoin;
        S.pendingJoin = null;
        joinNetplay(S.peers.find((p) => p.id === pending.peer.id && p.hosting) || pending.peer);
        return;
      }
      const system = systemById(event.systemId);
      const game = findGame(gameId) || { id: gameId, systemId: event.systemId, title: event.title };
      const launchOptions = S.autoLaunch.get(gameId);
      if (S.autoLaunch.delete(gameId) && document.hasFocus()) {
        play(system, game, launchOptions);
      } else if (event.romIncluded === false) {
        toast(t('bios.done', { system: system?.name || '' }));
      } else {
        toast(t('download.done', { title: event.title }), { action: t('action.play'), onAction: () => onGameClick(system, game) });
      }
      if (S.route.name === 'games') renderGamesBody();
      else if (S.route.name === 'systems' && S.search.query) renderSystemsBody();
    } else if (event?.type === 'uninstalled') {
      // Jeu PC désinstallé : dossier effacé (ou laissé si la désinstallation n'a pas abouti).
      await refreshDownloaded();
      loadUsage();
      toast(t(event.complete ? 'detail.uninstalled' : 'detail.uninstallIncomplete', { title: event.title }), event.complete ? {} : { type: 'error' });
      if (S.route.name === 'game' && S.route.gameId === gameId) renderGame();
    } else if (event?.type === 'failed') {
      S.autoLaunch.delete(gameId);
      toast(t('download.failed', { title: event.title, error: errorText(event.error) }), { type: 'error' });
    }
    patchGame(gameId);
    patchLobbyDownload(gameId);
  });

  // Touches du clavier (moteur intégré) : un bouton par bouton de manette ; clic, puis touche.
  const buttonLabel = (b) => (['up', 'down', 'left', 'right'].includes(b) ? t(`key.${b}`) : b.toUpperCase().replace('START', 'Start').replace('SELECT', 'Select'));
  const KEY_SYMBOLS = { ArrowUp: '↑', ArrowDown: '↓', ArrowLeft: '←', ArrowRight: '→', Backspace: '⌫' };
  let layoutMap = null; // disposition du clavier (AZERTY…) : « KeyQ » affiché « A »
  navigator.keyboard?.getLayoutMap?.().then((m) => { layoutMap = m; }).catch(() => {});

  function keyName(code) {
    if (!code) return t('settings.keyNone');
    if (KEY_SYMBOLS[code]) return KEY_SYMBOLS[code];
    const named = t(`keyname.${code}`);
    if (named !== `keyname.${code}`) return named;
    const local = layoutMap?.get(code);
    if (local && local.trim()) return local.toUpperCase();
    return code.replace(/^Key|^Digit/, '').replace(/^Numpad(.+)/, (m, k) => t('keyname.numpad', { key: k }));
  }

  function bindKeyboard(layout) {
    let keys = { ...layout.keys };
    let waiting = null; // bouton en attente d'une touche
    const paint = () => {
      for (const el of $$('[data-key]')) {
        const b = el.dataset.key;
        el.textContent = waiting === b ? t('settings.keyPress') : keyName(keys[b]);
        el.classList.toggle('selected', waiting === b);
        el.classList.toggle('unset', !keys[b]);
      }
    };
    const save = async (next) => {
      keys = next;
      paint();
      keys = await call(rc.keyboard.save, next);
      paint();
    };
    const stop = () => {
      waiting = null;
      window.removeEventListener('keydown', onKey, true);
      window.removeEventListener('mousedown', cancel, true);
      paint();
    };
    const cancel = (e) => { if (!e.target.closest?.('[data-key]')) stop(); };
    function onKey(e) {
      e.preventDefault();
      e.stopImmediatePropagation();
      if (e.repeat) return;
      const button = waiting;
      if (e.code === 'Escape') return stop();
      if (layout.reserved.includes(e.code)) return toast(t('settings.keyReserved', { key: e.code }), { type: 'error' });
      if (!layout.codes.includes(e.code)) return toast(t('settings.keyUnknown'), { type: 'error' });
      // Une touche ne commande qu'un bouton : retirée de celui qui l'avait.
      const next = { ...keys };
      for (const b of layout.buttons) if (next[b] === e.code) next[b] = '';
      next[button] = e.code;
      stop();
      save(next);
    }
    for (const el of $$('[data-key]')) {
      el.onclick = () => {
        if (waiting === el.dataset.key) return stop();
        if (!waiting) {
          window.addEventListener('keydown', onKey, true);
          window.addEventListener('mousedown', cancel, true);
        }
        waiting = el.dataset.key;
        el.blur(); // Espace / Entrée ne doivent pas recliquer le bouton
        paint();
      };
    }
    for (const el of $$('[data-key-clear]')) el.onclick = () => save({ ...keys, [el.dataset.keyClear]: '' });
    $('#keysReset').onclick = () => save({ ...layout.defaults });
    paint();
  }

  // ---------------------------------------------------------------------------
  // Démarrage
  // ---------------------------------------------------------------------------

  $('#backBtn').innerHTML = icon('back');
  $('#backBtn').title = t('app.back');
  $('#backBtn').onclick = back;
  // Navigation à la manette ; touches rappelées à la première utilisation.
  window.RomCloudGamepad.start();
  document.addEventListener('padconnected', () => toast(t('pad.connected'), { duration: 8000 }));
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && $('#modalRoot').innerHTML) $('#modalRoot').innerHTML = '';
    else if ((e.key === 'Escape' || (e.altKey && e.key === 'ArrowLeft')) && S.route.name !== 'systems' && !e.target.matches('input')) back();
  });
  // Bouton « précédent » de la souris (bouton 3) : fenêtre ouverte fermée, sinon retour, là où la
  // flèche de retour est affichée ; bouton « suivant » (bouton 4) : clic sur ce qui est sous le
  // pointeur. Navigation du navigateur intégré empêchée (boutons 3 et 4).
  const mouseHistoryButton = (e) => e.button === 3 || e.button === 4;
  document.addEventListener('mousedown', (e) => {
    if (mouseHistoryButton(e)) e.preventDefault();
  });
  document.addEventListener('mouseup', (e) => {
    if (!mouseHistoryButton(e)) return;
    e.preventDefault();
    if (e.button === 4) {
      const target = e.target instanceof Element ? e.target : null;
      // Champ de saisie : sélectionné, comme au clic ; le reste : clic (mêmes actions qu'au bouton gauche).
      if (target?.matches('input:not([type=checkbox]):not([type=radio]), textarea, select')) target.focus();
      else target?.click();
      return;
    }
    if ($('#modalRoot').innerHTML) $('#modalRoot').innerHTML = '';
    else if (!$('#backBtn').classList.contains('hidden')) back();
  });
  // Erreurs de l'interface signalées à l'administration (avec le profil connecté).
  window.addEventListener('error', (e) => rc.account.reportError({ context: 'ui', message: e.message, details: e.error?.stack }));
  window.addEventListener('unhandledrejection', (e) => rc.account.reportError({ context: 'ui', message: String(e.reason?.message || e.reason), details: e.reason?.stack }));

  // Au retour dans l'application (fin de partie), met à jour les jeux présents.
  window.addEventListener('focus', async () => {
    await refreshDownloaded();
    if (S.route.name === 'games') renderGamesBody();
  });

  // Barre du haut opaque dès que le contenu défile dessous (mode immersif).
  // Carrousel : la bannière reste fixe sous la barre, qui reste translucide.
  $('#main').addEventListener('scroll', (e) => {
    $('.topbar').classList.toggle('scrolled', e.target.scrollTop > 8 && !$('.carousel-head'));
  });

  // Fin du défilement : la carte coupée par le haut (sous la bannière du carrousel : la rangée) est
  // montrée en entier si plus de la moitié en est visible, sinon la suivante est remontée en haut.
  let snapTimer = 0;
  $('#main').addEventListener('onscrollend' in window ? 'scrollend' : 'scroll', () => {
    clearTimeout(snapTimer);
    snapTimer = setTimeout(snapCards, 'onscrollend' in window ? 0 : 160);
  });

  function snapCards() {
    const main = $('#main');
    const head = $('.carousel-head', main);
    const items = $('.carousel', main) ? $$('.row', main) : $$('.system-card, .cards-grid .game-card, .list-row', main);
    if (!items.length) return;
    const top = main.getBoundingClientRect().top + (head ? head.offsetHeight : 0);
    const boxes = items.map((el) => el.getBoundingClientRect());
    const cut = boxes.find((r) => r.top < top - 1 && r.bottom > top + 1);
    if (!cut) return;
    let delta = cut.top - top;
    if (cut.bottom - top < cut.height / 2) {
      const next = Math.min(...boxes.filter((r) => r.top >= cut.bottom - 1).map((r) => r.top));
      if (!Number.isFinite(next)) return;
      delta = next - top;
      // Bas de la page déjà atteint : la suivante ne peut pas monter plus haut.
      if (main.scrollTop + delta > main.scrollHeight - main.clientHeight + 1) return;
    }
    main.scrollBy({ top: delta, behavior: 'smooth' });
  }

  // ---------------------------------------------------------------------------
  // Mise à jour de l'application (vérifiée au chargement)
  // ---------------------------------------------------------------------------

  async function checkUpdate() {
    const update = await call(rc.update.check).catch(() => null);
    if (!update) return;
    modal({
      title: t('update.title'),
      body: `<p>${esc(t('update.text', { version: update.version, installed: update.installed }))}</p>
        <p class="muted">${esc(t(update.portable ? 'update.portable' : 'update.installer', { size: update.size ? formatSize(update.size) : t('update.sizeUnknown') }))}</p>
        ${changesHtml(update)}`,
      buttons: [
        { label: t('update.later'), kind: 'ghost' },
        { label: t('update.install'), kind: 'primary', onClick: () => installUpdate(update) },
      ],
    });
  }

  /** Modifications depuis la version installée, par version (repliées). */
  function changesHtml(update) {
    const groups = update.changes || [];
    if (!groups.length) return '';
    return `<details class="update-changes">
      <summary>${esc(t('update.changes', { installed: update.installed, version: update.version }))}</summary>
      ${groups.map((g) => `<h4>v${esc(g.version)}</h4><ul>${g.items.map((i) => `<li>${esc(i)}</li>`).join('')}</ul>`).join('')}
    </details>`;
  }

  async function installUpdate(update) {
    const root = modal({
      title: t('update.downloading', { version: update.version }),
      body: `<div class="progress update-progress"><div style="width:0%"></div></div>
        <p class="muted" id="updateBytes">${esc(formatSize(0))}${update.size ? ` / ${esc(formatSize(update.size))}` : ''}</p>`,
      buttons: [],
    });
    rc.update.onProgress(({ bytes, total: announced }) => {
      // Taille annoncée par le téléchargement, sinon celle trouvée en cherchant la mise à jour.
      const total = announced || update.size;
      const bar = $('.progress > div', root);
      if (bar && total) bar.style.width = `${Math.min(100, Math.round((bytes / total) * 100))}%`;
      const text = $('#updateBytes', root);
      if (!text) return;
      if (total && bytes >= total) text.textContent = t('update.restarting');
      else text.textContent = total ? `${formatSize(bytes)} / ${formatSize(total)}` : formatSize(bytes);
    });
    try {
      await call(rc.update.install); // RomCloud se ferme puis se relance une fois à jour
    } catch (err) {
      root.innerHTML = '';
      toast(t('update.failed', { error: err.message }), { type: 'error' });
    }
  }

  // Retour du jeu (moteur intégré fermé) : la fiche affiche « Reprendre » si la partie a été sauvegardée.
  window.addEventListener('focus', () => {
    if (S.route.name === 'game' && !$('#modalRoot').innerHTML) renderGame();
  });

  (async function init() {
    S.settings = await call(rc.settings.get);
    window.I18N.setLanguage(S.settings.effectiveLanguage);
    $('#backBtn').title = t('app.back');
    S.downloads = await call(rc.downloads.states);
    S.cast = await call(rc.cast.state);
    S.account = await call(rc.account.state).catch(() => ({ signedIn: false }));
    // Mode en ligne ou hors ligne : le serveur est sondé une fois à l'ouverture (hors ligne, il n'est
    // plus interrogé jusqu'à ce que la pastille soit touchée).
    S.connectivity = await call(S.settings.serverUrl ? rc.connectivity.check : rc.connectivity.state).catch(() => S.connectivity);
    askUploadPending(S.connectivity.pendingSaves);
    if (S.account.signedIn) S.playtime = await call(rc.account.playtime).catch(() => ({}));
    S.peers = await call(rc.netplay.peers).catch(() => []);
    render();
    checkUpdate();
    if (S.settings.serverUrl) await loadSystems();
    // Ouvert par le raccourci d'un jeu sur le bureau (puis raccourcis utilisés RomCloud ouvert).
    if (rc.shortcut) {
      playFromShortcut(await call(rc.shortcut.pending).catch(() => null));
      rc.shortcut.onPlay(playFromShortcut);
    }
  })();
})();
