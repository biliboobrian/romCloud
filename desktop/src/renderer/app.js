// Interface de RomCloud pour Windows (script classique, API exposée par preload.js).
(function () {
  const rc = window.romcloud;
  const { t, errorText, formatSize } = window.I18N;
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

  // ---------------------------------------------------------------------------
  // État
  // ---------------------------------------------------------------------------

  const S = {
    settings: null,
    systems: [],
    systemsOffline: false,
    systemsError: null,
    systemsLoading: true,
    search: { query: '', results: [], downloaded: new Set(), offline: false, loading: false, error: null },
    games: { systemId: null, list: [], downloaded: new Set(), offline: false, loading: false, error: null, filter: 'all', query: '' },
    downloads: {}, // gameId -> { status, bytes, total, title, error }
    autoLaunch: new Set(),
    route: { name: 'systems' },
    history: [],
    focusedGameId: null,
  };

  // ---------------------------------------------------------------------------
  // Utilitaires
  // ---------------------------------------------------------------------------

  const ICONS = {
    back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
    gear: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84a.48.48 0 0 0-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.74 8.87a.47.47 0 0 0 .12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.47.47 0 0 0-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z',
    refresh: 'M17.65 6.35A7.96 7.96 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    search: 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
    close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    play: 'M8 5v14l11-7z',
    cloud: 'M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM17 13l-5 5-5-5h3V9h4v4h3z',
    check: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z',
    error: 'M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z',
    info: 'M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z',
    list: 'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
    carousel: 'M7 19h10V4H7v15zm-5-2h4V6H2v11zM18 6v11h4V6h-4z',
    folder: 'M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z',
    trash: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
    open: 'M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z',
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

  const withKey = (url) => (S.settings.apiKey ? `${url}${url.includes('?') ? '&' : '?'}key=${encodeURIComponent(S.settings.apiKey)}` : url);

  function mediaUrl(game, type) {
    const has = type === 'boxart' ? game.hasBoxart : game.hasScreenshot;
    if (!has || !S.settings.serverUrl) return null;
    return withKey(`${S.settings.serverUrl}/api/games/${game.id}/media/${type}?v=${encodeURIComponent(game.updatedAt || '')}`);
  }

  function systemImageUrl(system) {
    if (!system?.hasImage || !S.settings.serverUrl) return null;
    return withKey(`${S.settings.serverUrl}/api/systems/${encodeURIComponent(system.id)}/image?v=${encodeURIComponent(system.imageVersion || '')}`);
  }

  const systemById = (id) => S.systems.find((s) => s.id === id);
  const year = (g) => (g.releaseDate && /^\d{4}/.test(g.releaseDate) ? g.releaseDate.slice(0, 4) : null);
  const mainGenre = (g) => (g.genre || '').split(/[,/;]/)[0].trim() || null;

  // ---------------------------------------------------------------------------
  // Statut local des jeux (téléchargé, en cours, échec)
  // ---------------------------------------------------------------------------

  function statusOf(game, downloaded) {
    const d = S.downloads[game.id];
    if (d?.status === 'running') return { kind: 'running', progress: d.total ? d.bytes / d.total : 0, d };
    if (d?.status === 'failed') return { kind: 'failed', error: errorText(d.error) };
    return downloaded.has(game.id) ? { kind: 'downloaded' } : { kind: 'remote' };
  }

  function statusHtml(st) {
    switch (st.kind) {
      case 'downloaded': return `<span class="status-icon ok" title="${esc(t('status.downloaded'))}">${icon('check', 24)}</span>`;
      case 'running': return `<span class="ring">${Math.round(st.progress * 100)}%</span>`;
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
    for (const el of $$(`[data-progress="${gameId}"]`)) {
      el.classList.toggle('hidden', st.kind !== 'running');
      $('div', el).style.width = `${Math.round((st.progress || 0) * 100)}%`;
    }
    for (const el of $$(`[data-card="${gameId}"]`)) el.classList.toggle('remote', st.kind !== 'downloaded');
    if (S.route.name === 'game' && S.route.gameId === gameId) renderGame();
    if (S.route.name === 'games' && S.focusedGameId === gameId) renderHero();
  }

  function findGame(id) {
    return S.games.list.find((g) => g.id === id) || S.search.results.find((g) => g.id === id) || S.detailGame?.id === id && S.detailGame;
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
    S.route = S.history.pop() || { name: 'systems' };
    render();
  }

  function render() {
    $('#modalRoot').innerHTML = '';
    $('#banners').innerHTML = '';
    $('#backBtn').classList.toggle('hidden', S.route.name === 'systems');
    document.body.classList.remove('immersive');
    $('#main').scrollTop = 0;
    ({ systems: renderSystems, games: renderGames, game: renderGame, settings: renderSettings })[S.route.name]();
  }

  function setTopbar({ title, subtitle = '', center = '', right = '' }) {
    $('#title').textContent = title;
    $('#subtitle').textContent = subtitle;
    $('#topbarCenter').innerHTML = center;
    $('#topbarRight').innerHTML = right;
  }

  function banner(text, error = false) {
    $('#banners').insertAdjacentHTML('beforeend', `<div class="banner${error ? ' error' : ''}">${esc(text)}</div>`);
  }

  const settingsBtn = () => `<button class="icon-btn" data-action="settings" title="${esc(t('app.settings'))}">${icon('gear')}</button>`;

  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-action]');
    if (el?.dataset.action === 'settings') go({ name: 'settings' });
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
    } catch (err) {
      S.systemsError = err.message;
    }
    S.systemsLoading = false;
    if (S.route.name === 'systems') renderSystems();
  }

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
    setTopbar({
      title: 'RomCloud',
      subtitle: S.systems.length ? t('app.games', { n: S.systems.reduce((n, s) => n + s.gameCount, 0) }) : '',
      center: `<input type="search" class="search-box" id="globalSearch" placeholder="${esc(t('app.searchAll'))}" value="${esc(S.search.query)}">`,
      right: `<button class="icon-btn" id="refreshBtn" title="${esc(t('app.refresh'))}">${icon('refresh')}</button>${settingsBtn()}`,
    });
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
    if (!S.systems.length) {
      const text = S.systemsLoading ? t('app.loading') : S.systemsError ? t('app.unreachable', { detail: S.systemsError }) : t('app.noSystems');
      main.innerHTML = `<div class="centered"><p>${esc(text)}</p>${S.systemsError ? `<button class="btn" data-action="settings">${esc(t('app.settings'))}</button>` : ''}</div>`;
      return;
    }
    main.innerHTML = `<div class="page"><div class="systems-grid">${S.systems.map((s) => {
      const img = systemImageUrl(s);
      return `<div class="system-card" data-system="${esc(s.id)}">
        <div class="img">${img ? `<img src="${esc(img)}" alt="">` : `<span class="short">${esc(s.shortname.toUpperCase())}</span>`}</div>
        <div class="info"><div class="name">${esc(s.name)}</div>
        <div class="muted">${esc(t('app.games', { n: s.gameCount }))} · ${esc(formatSize(s.totalSize))}</div></div>
      </div>`;
    }).join('')}</div></div>`;
    for (const el of $$('[data-system]', main)) el.onclick = () => openSystem(el.dataset.system);
  }

  function renderSearchResults() {
    const main = $('#main');
    const s = S.search;
    if (s.offline) banner(t('app.searchOffline'));
    if (!s.results.length) {
      const text = s.loading ? t('app.loading') : s.error ? s.error : t('app.searchNone', { q: s.query });
      main.innerHTML = `<div class="centered"><p>${esc(text)}</p></div>`;
      return;
    }
    main.innerHTML = `<div class="page"><p class="muted">${esc(t('app.searchResults', { n: s.results.length, q: s.query }))}</p>
      <div class="cards-grid">${s.results.map((g) => cardHtml(g, s.downloaded, { badge: true })).join('')}</div></div>`;
    bindCards(main, s.results, (g) => openGameDetail(g.systemId, g.id));
  }

  // ---------------------------------------------------------------------------
  // Cartes et lignes de jeux
  // ---------------------------------------------------------------------------

  function cardHtml(game, downloaded, { badge = false } = {}) {
    const st = statusOf(game, downloaded);
    const cover = mediaUrl(game, 'boxart');
    const system = systemById(game.systemId);
    const logo = systemImageUrl(system);
    return `<div class="game-card${st.kind === 'downloaded' ? '' : ' remote'}" data-card="${game.id}">
      <div class="cover">
        ${cover ? `<img src="${esc(cover)}" alt="" loading="lazy">` : esc(game.title)}
        <div class="overlay status" data-status="${game.id}">${statusHtml(st)}</div>
        <button class="overlay info-btn" data-info="${game.id}" title="${esc(t('action.details'))}">${icon('info', 18)}</button>
        ${badge && system ? `<div class="overlay badge">${logo ? `<img src="${esc(logo)}" alt="${esc(system.name)}">` : esc(system.shortname.toUpperCase())}</div>` : ''}
        <div class="progress${st.kind === 'running' ? '' : ' hidden'}" data-progress="${game.id}"><div style="width:${Math.round((st.progress || 0) * 100)}%"></div></div>
      </div>
      <div class="title">${esc(game.title)}</div>
      <div class="meta">${esc([year(game), formatSize(game.size)].filter(Boolean).join(' · '))}</div>
    </div>`;
  }

  /** Clic : jouer / télécharger ; bouton ⓘ ou clic droit : fiche du jeu. */
  function bindCards(root, games, onDetails, onClick) {
    const byId = new Map(games.map((g) => [g.id, g]));
    for (const el of $$('[data-card]', root)) {
      const game = byId.get(Number(el.dataset.card));
      el.onclick = (e) => {
        if (e.target.closest('[data-info]')) return onDetails(game);
        (onClick || onDetails)(game);
      };
      el.oncontextmenu = (e) => {
        e.preventDefault();
        onDetails(game);
      };
      el.onmouseenter = () => {
        if (S.route.name === 'games') {
          S.focusedGameId = game.id;
          renderHero();
        }
      };
    }
  }

  // ---------------------------------------------------------------------------
  // Jeux d'un système
  // ---------------------------------------------------------------------------

  function openSystem(systemId) {
    if (S.games.systemId !== systemId) {
      S.games = { systemId, list: [], downloaded: new Set(), offline: false, loading: true, error: null, filter: 'all', query: '' };
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
    } catch (err) {
      S.games.error = err.message;
    }
    S.games.loading = false;
    if (S.route.name === 'games' && S.route.systemId === systemId) renderGamesBody();
  }

  async function refreshDownloaded() {
    if (S.games.list.length) S.games.downloaded = new Set(await call(rc.library.downloaded, S.systems, S.games.list));
    if (S.search.results.length) S.search.downloaded = new Set(await call(rc.library.downloaded, S.systems, S.search.results));
  }

  function visibleGames() {
    const q = S.games.query.trim().toLowerCase();
    return S.games.list.filter((g) => {
      if (q && !g.title.toLowerCase().includes(q) && !g.fileName.toLowerCase().includes(q)) return false;
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

  function renderGames() {
    const system = systemById(S.route.systemId);
    const f = S.games.filter;
    const view = S.settings.view;
    setTopbar({
      title: system?.name || '',
      subtitle: t('games.subtitle', { n: S.games.list.length, d: S.games.downloaded.size }),
      center: `<div class="games-toolbar">
        ${['all', 'downloaded', 'remote'].map((k) => `<button class="chip${f === k ? ' selected' : ''}" data-filter="${k}">${esc(t(`filter.${k}`))}</button>`).join('')}
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
      const text = S.games.loading ? t('app.loading') : S.games.error || t('games.none');
      main.innerHTML = `<div class="centered"><p>${esc(text)}</p></div>`;
      return;
    }
    if (S.settings.view === 'list') {
      main.innerHTML = `<div class="page"><div class="list">${games.map((g) => {
        const cover = mediaUrl(g, 'boxart');
        return `<div class="list-row" data-card="${g.id}">
          <div class="thumb">${cover ? `<img src="${esc(cover)}" alt="" loading="lazy">` : ''}</div>
          <div class="main"><div class="t">${esc(g.title)}</div>
            <div class="muted">${esc([year(g), g.genre, formatSize(g.size)].filter(Boolean).join(' · '))}</div></div>
          <div class="status" data-status="${g.id}">${statusHtml(statusOf(g, S.games.downloaded))}</div>
          <button class="icon-btn" data-info="${g.id}" title="${esc(t('action.details'))}">${icon('info')}</button>
        </div>`;
      }).join('')}</div></div>`;
    } else {
      const rows = S.games.query.trim() ? [{ title: S.games.query, games }] : carouselRows(games, S.games.downloaded);
      main.innerHTML = `<div class="carousel">
        <div class="backdrop" id="backdrop"></div>
        <div class="hero" id="hero"></div>
        ${rows.map((r) => `<section class="row"><h3>${esc(r.title)} <span class="count">· ${r.games.length}</span></h3>
          <div class="row-track">${r.games.map((g) => cardHtml(g, S.games.downloaded)).join('')}</div></section>`).join('')}
      </div>`;
      // Molette verticale -> défilement horizontal des rangées
      for (const track of $$('.row-track', main)) {
        track.addEventListener('wheel', (e) => {
          if (Math.abs(e.deltaY) > Math.abs(e.deltaX) && track.scrollWidth > track.clientWidth) {
            const atStart = track.scrollLeft <= 0 && e.deltaY < 0;
            const atEnd = track.scrollLeft + track.clientWidth >= track.scrollWidth - 1 && e.deltaY > 0;
            if (!atStart && !atEnd) {
              e.preventDefault();
              track.scrollLeft += e.deltaY;
            }
          }
        }, { passive: false });
      }
      renderHero();
    }
    bindCards(main, games, (g) => openGameDetail(g.systemId, g.id), (g) => onGameClick(systemById(g.systemId), g));
  }

  function renderHero() {
    const hero = $('#hero');
    if (!hero) return;
    const game = featured(visibleGames());
    if (!game) return;
    const shot = mediaUrl(game, 'screenshot') || mediaUrl(game, 'boxart');
    $('#backdrop').innerHTML = shot ? `<img src="${esc(shot)}" alt="">` : '';
    const cover = mediaUrl(game, 'boxart');
    const st = statusOf(game, S.games.downloaded);
    const statusLine = st.kind === 'downloaded' ? `<span class="good">${esc(t('hero.ready'))}</span>`
      : st.kind === 'running' ? `<span style="color:var(--accent)">${esc(t('hero.downloading', { p: Math.round(st.progress * 100) }))}</span>`
      : st.kind === 'failed' ? `<span class="bad">${esc(t('status.failed', { error: st.error }))}</span>`
      : `<span class="muted">${esc(t('hero.toDownload'))}</span>`;
    hero.innerHTML = `
      <div class="cover-lg">${cover ? `<img src="${esc(cover)}" alt="">` : ''}</div>
      <div class="text">
        <h2>${esc(game.title)}</h2>
        <div class="muted">${esc([year(game), mainGenre(game), game.players ? `${game.players} 👤` : null, formatSize(game.size)].filter(Boolean).join('  ·  '))}</div>
        ${statusLine}
        ${game.description ? `<div class="desc">${esc(game.description)}</div>` : ''}
        <div class="actions">
          <button class="btn primary" id="heroMain">${icon(st.kind === 'downloaded' ? 'play' : 'cloud', 18)} ${esc(st.kind === 'downloaded' ? t('action.play') : st.kind === 'running' ? `${Math.round(st.progress * 100)} %` : t('action.download'))}</button>
          <button class="btn" id="heroInfo">${icon('info', 18)} ${esc(t('action.info'))}</button>
        </div>
      </div>`;
    $('#heroMain').onclick = () => onGameClick(systemById(game.systemId), game);
    $('#heroInfo').onclick = () => openGameDetail(game.systemId, game.id);
  }

  // ---------------------------------------------------------------------------
  // Clic sur un jeu : jouer, ou proposer le téléchargement
  // ---------------------------------------------------------------------------

  function onGameClick(system, game) {
    const downloaded = S.route.name === 'systems' ? S.search.downloaded : S.games.downloaded;
    const st = statusOf(game, downloaded);
    if (st.kind === 'downloaded') return playChecked(system, game);
    if (st.kind === 'running') return askCancel(game);
    return askDownload(system, game, st.kind === 'failed' ? st.error : null);
  }

  const biosSize = (list) => formatSize(list.reduce((n, b) => n + b.size, 0));

  /** Joue, en proposant d'abord de télécharger les BIOS du système absents du PC. */
  async function playChecked(system, game) {
    const missing = await call(rc.bios.missing, system).catch(() => []);
    if (!missing.length) return play(system, game);
    modal({
      title: t('bios.beforePlayTitle'),
      body: `<p>${esc(t('bios.beforePlayText', { n: missing.length, size: biosSize(missing) }))}</p>`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost', left: true },
        { label: t('bios.playAnyway'), onClick: () => play(system, game) },
        {
          label: t('bios.downloadAndPlay'),
          kind: 'primary',
          onClick: async () => {
            S.autoLaunch.add(game.id);
            await call(rc.downloads.dismiss, game.id);
            await call(rc.downloads.start, system, game, { bios: missing, includeRom: false });
          },
        },
      ],
    });
  }

  async function play(system, game) {
    try {
      const result = await call(rc.launcher.play, system, game);
      if (result?.manual) showManualLaunch(result);
    } catch (err) {
      const key = err.info?.key;
      if (key === 'errors.emulatorMissing') {
        showEmulatorMissing(err.info.vars.id);
      } else if (key === 'errors.retroarchMissing' || key === 'errors.coreMissing') {
        toast(err.message, { type: 'error', action: t('detail.configureRetroArch'), onAction: () => showRetroArchHelp(system) });
      } else {
        toast(err.message, { type: 'error' });
      }
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
        { label: t('emulator.browse'), onClick: () => locateEmulator(emu) },
        { label: t('emulator.download', { name: emu.name }), kind: 'primary', onClick: () => rc.shell.openExternal(emu.url) },
      ],
    });
  }

  /** Choix manuel de l'exécutable d'un émulateur. */
  async function locateEmulator(emu) {
    const file = await call(rc.dialog.pickFile, [{ name: emu.name, extensions: ['exe'] }]);
    if (!file) return false;
    await call(rc.emulators.setPath, emu.id, file);
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

  async function askDownload(system, game, lastError) {
    const missing = await call(rc.bios.missing, system).catch(() => []);
    const root = modal({
      title: t('download.title'),
      body: `<p>${esc(t('download.text', { title: game.title }))}</p>
        <p class="muted">${esc(game.fileName)} · ${esc(formatSize(game.size))}</p>
        ${lastError ? `<p class="bad">${esc(t('download.lastError', { error: lastError }))}</p>` : ''}
        <label class="check"><input type="checkbox" id="launchAfter" checked> ${esc(t('download.launchAfter'))}</label>
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
            close();
            await startDownload(system, game, launchAfter, bios);
          },
        },
      ],
    });
  }

  async function startDownload(system, game, launchAfter, bios = []) {
    await call(rc.downloads.dismiss, game.id);
    // Guide RetroArch après confirmation (si l'émulateur choisi est RetroArch et pas masqué) :
    // le lancement automatique attend alors sa fermeture.
    const check = await call(rc.launcher.check, system);
    const showHelp = check.usesRetroArch && !S.settings.retroarchHelpDismissed && (!check.retroarchOk || !check.coreOk);
    if (launchAfter && !showHelp) S.autoLaunch.add(game.id);
    await call(rc.downloads.start, system, game, { bios });
    if (showHelp) {
      showRetroArchHelp(system, {
        auto: true,
        onClose: () => {
          if (!launchAfter) return;
          const downloaded = S.route.name === 'systems' ? S.search.downloaded : S.games.downloaded;
          if (statusOf(game, downloaded).kind === 'downloaded') play(system, game);
          else S.autoLaunch.add(game.id);
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
    const [emu, localPath, missingBios, emulators, command] = await Promise.all([
      call(rc.launcher.options, system),
      call(rc.library.path, system, game),
      system.biosCount ? call(rc.bios.missing, system).catch(() => []) : [],
      call(rc.emulators.list),
      call(rc.launcher.describe, system, game),
    ]);
    if (S.route.name !== 'game' || S.route.gameId !== gameId) return;
    const st = statusOf(game, downloaded);
    const cover = mediaUrl(game, 'boxart');
    const shot = mediaUrl(game, 'screenshot');
    const selected = emu.options.find((o) => o.id === emu.selected);
    const optionLabel = (o) => {
      if (o.kind === 'retroarch') return t('emu.retroarch', { core: o.core });
      if (o.kind === 'emulator') return o.installed ? o.name : t('emu.notInstalled', { name: o.name });
      return t(`emu.${o.kind}`);
    };
    const catalogEmu = selected.kind === 'emulator' ? emulators.find((e) => e.id === selected.emuId) : null;
    const meta = [
      game.releaseDate && t('detail.release', { v: game.releaseDate }),
      game.genre,
      game.developer && t('detail.developer', { v: game.developer }),
      game.publisher && t('detail.publisher', { v: game.publisher }),
      game.players && t('detail.players', { v: game.players }),
      game.rating != null && t('detail.rating', { v: new Intl.NumberFormat(window.I18N.language, { maximumFractionDigits: 1 }).format(game.rating) }),
    ].filter(Boolean);

    let actions = '';
    if (st.kind === 'running') {
      actions = `<div style="flex:1"><div class="bar"><div style="width:${Math.round(st.progress * 100)}%"></div></div>
        <p class="muted">${esc(t('download.progress', { bytes: formatSize(st.d.bytes), total: formatSize(st.d.total) }))}</p></div>
        <button class="btn" id="cancelBtn">${icon('close', 18)} ${esc(t('download.cancelPercent', { p: Math.round(st.progress * 100) }))}</button>`;
    } else if (st.kind === 'downloaded') {
      actions = `<button class="btn primary big" id="playBtn">${icon('play')} ${esc(t('action.play'))}</button>
        <button class="btn" id="folderBtn">${icon('folder', 18)} ${esc(t('detail.showInFolder'))}</button>
        <button class="btn danger" id="deleteBtn">${icon('trash', 18)} ${esc(t('detail.delete'))}</button>`;
    } else {
      actions = `<button class="btn primary big" id="downloadBtn">${icon('cloud')} ${esc(st.kind === 'failed' ? t('app.retry') : t('download.withSize', { size: formatSize(game.size) }))}</button>`;
    }

    main.innerHTML = `<div class="detail">
      <div class="backdrop">${shot || cover ? `<img src="${esc(shot || cover)}" alt="">` : ''}</div>
      <div class="detail-body">
        <div class="cover-lg">${cover ? `<img src="${esc(cover)}" alt="">` : icon('pad', 64)}</div>
        <div class="col">
          <span class="system-name">${esc(system.name.toUpperCase())}</span>
          <h2>${esc(game.title)}</h2>
          <div class="muted">${meta.map(esc).join('  ·  ')}</div>
          ${st.kind === 'downloaded' ? `<div class="ok">${esc(t('detail.onPc'))}</div>` : ''}
          ${st.kind === 'failed' ? `<div class="error">${esc(t('status.failed', { error: st.error }))}</div>` : ''}
          ${st.kind === 'remote' ? `<div class="muted">${esc(t('detail.mustDownload'))}</div>` : ''}
          <div class="actions">${actions}</div>
          ${system.biosCount && st.kind !== 'running' ? (missingBios.length
            ? `<div class="line"><span class="muted">${esc(t('bios.missing', { n: missingBios.length, size: biosSize(missingBios) }))}</span>${st.kind === 'downloaded' ? `<button class="btn ghost" id="biosBtn">${icon('cloud', 18)} ${esc(t('bios.download'))}</button>` : ''}</div>`
            : `<div class="ok">${esc(t('bios.present', { n: system.biosCount }))}</div>`) : ''}
          <div class="emu">
            <label class="muted">${esc(t('detail.emulator'))}</label>
            <select id="emuSelect">${emu.options.map((o) => `<option value="${esc(o.id)}"${o.id === emu.selected ? ' selected' : ''}>${esc(optionLabel(o))}</option>`).join('')}</select>
            ${selected.kind === 'custom' ? `<label class="muted">${esc(t('detail.command'))}</label>
              <input type="text" id="emuCommand" value="${esc(emu.command)}" placeholder="${esc(t('detail.commandHint'))}">` : ''}
            ${selected.kind === 'retroarch' ? `<div><button class="btn ghost" id="raHelpBtn">${icon('info', 18)} ${esc(t('detail.configureRetroArch'))}</button></div>` : ''}
            ${catalogEmu ? emulatorBlock(catalogEmu) : ''}
            ${command ? `<div class="muted small">${esc(t('emulator.command'))}</div><div class="mono">${esc(command)}</div>` : ''}
          </div>
          ${game.description ? `<div class="desc">${esc(game.description)}</div>` : ''}
          ${shot ? `<img class="shot" src="${esc(shot)}" alt="">` : ''}
          <div class="mono muted">${esc(localPath)}</div>
        </div>
      </div>
    </div>`;

    $('#playBtn')?.addEventListener('click', () => playChecked(system, game));
    $('#downloadBtn')?.addEventListener('click', () => startDownload(system, game, false, missingBios));
    $('#biosBtn')?.addEventListener('click', async () => {
      await call(rc.downloads.dismiss, game.id);
      await call(rc.downloads.start, system, game, { bios: missingBios, includeRom: false });
    });
    $('#cancelBtn')?.addEventListener('click', () => rc.downloads.cancel(game.id));
    $('#folderBtn')?.addEventListener('click', () => rc.shell.showItem(localPath));
    $('#raHelpBtn')?.addEventListener('click', () => showRetroArchHelp(system));
    if (catalogEmu) bindEmulatorBlock($('#main'), catalogEmu, () => renderGame());
    $('#deleteBtn')?.addEventListener('click', () => modal({
      title: t('detail.deleteTitle'),
      body: `<p>${esc(t('detail.deleteText'))}</p>`,
      buttons: [
        { label: t('app.cancel'), kind: 'ghost' },
        { label: t('detail.delete'), kind: 'danger', onClick: async () => { await call(rc.library.remove, system, game); await refreshDownloaded(); renderGame(); } },
      ],
    }));
    $('#emuSelect').onchange = async (e) => {
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
      : `<div class="muted">${esc(t('emulator.manual', { name: emu.name }))}</div>`;
    return `<div class="emu-block" data-emu="${esc(emu.id)}">
      ${compact ? '' : status}
      <div class="emu-actions">
        ${emu.path ? `<button class="btn" data-emu-launch>${icon('open', 18)} ${esc(t('emulator.launch', { name: emu.name }))}</button>` : `<button class="btn primary" data-emu-download>${icon('cloud', 18)} ${esc(t('emulator.download', { name: emu.name }))}</button>`}
        <button class="btn" data-emu-browse>${icon('folder', 18)} ${esc(t('emulator.browse'))}</button>
        ${emu.path ? `<button class="btn ghost" data-emu-download>${esc(t('emulator.website'))}</button>` : `<button class="btn ghost" data-emu-detect>${icon('search', 18)} ${esc(t('emulator.detect'))}</button>`}
      </div>
      ${args}
    </div>`;
  }

  function bindEmulatorBlock(root, emu, refresh) {
    const block = $(`[data-emu="${emu.id}"]`, root);
    if (!block) return;
    for (const b of $$('[data-emu-download]', block)) b.onclick = () => rc.shell.openExternal(emu.url);
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
  }

  // ---------------------------------------------------------------------------
  // Paramètres
  // ---------------------------------------------------------------------------

  async function renderSettings() {
    const s = S.settings;
    const [version, emulators] = await Promise.all([call(rc.app.version), call(rc.emulators.list)]);
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

  rc.downloads.onUpdate(async ({ gameId, state, event }) => {
    if (state) S.downloads[gameId] = state;
    else delete S.downloads[gameId];
    if (event?.type === 'completed') {
      await refreshDownloaded();
      const system = systemById(event.systemId);
      const game = findGame(gameId) || { id: gameId, systemId: event.systemId, title: event.title };
      if (S.autoLaunch.delete(gameId) && document.hasFocus()) {
        play(system, game);
      } else if (event.romIncluded === false) {
        toast(t('bios.done', { system: system?.name || '' }));
      } else {
        toast(t('download.done', { title: event.title }), { action: t('action.play'), onAction: () => play(system, game) });
      }
      if (S.route.name === 'games') renderGamesBody();
      else if (S.route.name === 'systems' && S.search.query) renderSystemsBody();
    } else if (event?.type === 'failed') {
      S.autoLaunch.delete(gameId);
      toast(t('download.failed', { title: event.title, error: errorText(event.error) }), { type: 'error' });
    }
    patchGame(gameId);
  });

  // ---------------------------------------------------------------------------
  // Démarrage
  // ---------------------------------------------------------------------------

  $('#backBtn').innerHTML = icon('back');
  $('#backBtn').title = t('app.back');
  $('#backBtn').onclick = back;
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && $('#modalRoot').innerHTML) $('#modalRoot').innerHTML = '';
    else if ((e.key === 'Escape' || (e.altKey && e.key === 'ArrowLeft')) && S.route.name !== 'systems' && !e.target.matches('input')) back();
  });
  // Au retour dans l'application (fin de partie), met à jour les jeux présents.
  window.addEventListener('focus', async () => {
    await refreshDownloaded();
    if (S.route.name === 'games') renderGamesBody();
  });

  // Barre du haut opaque dès que le contenu défile dessous (mode immersif).
  $('#main').addEventListener('scroll', (e) => {
    $('.topbar').classList.toggle('scrolled', e.target.scrollTop > 8);
  });

  (async function init() {
    S.settings = await call(rc.settings.get);
    window.I18N.setLanguage(S.settings.effectiveLanguage);
    $('#backBtn').title = t('app.back');
    S.downloads = await call(rc.downloads.states);
    render();
    if (S.settings.serverUrl) await loadSystems();
  })();
})();
