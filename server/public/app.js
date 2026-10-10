// Interface d'administration RomCloud (vanilla JS, sans build).
import { LANGUAGES, applyTranslations, formatSize, getLanguage, setLanguage, t } from './i18n.js';
import { initUsers } from './users.js';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const state = {
  systems: [],
  current: null,
  games: [],
  platforms: null,
  selectedPlatforms: new Set(),
  editingGame: null,
  status: null,
};

// ---------------------------------------------------------------------------
// Utilitaires
// ---------------------------------------------------------------------------

function apiKey() {
  try {
    return localStorage.getItem('romcloud.key') || '';
  } catch {
    return '';
  }
}

function setApiKey(key) {
  try {
    localStorage.setItem('romcloud.key', key);
  } catch {
    /* stockage indisponible */
  }
}

function withKey(url) {
  const key = apiKey();
  return key ? `${url}${url.includes('?') ? '&' : '?'}key=${encodeURIComponent(key)}` : url;
}

async function api(path, { method = 'GET', body, headers = {} } = {}) {
  // Accept-Language : le serveur renvoie ses messages (erreurs, tâches) dans la langue choisie.
  const opts = { method, headers: { 'Accept-Language': getLanguage(), ...headers } };
  const key = apiKey();
  if (key) opts.headers.Authorization = `Bearer ${key}`;
  if (body !== undefined && !(body instanceof Blob)) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  } else if (body) {
    opts.body = body;
  }
  const res = await fetch(`/api${path}`, opts);
  if (res.status === 401 || res.status === 403) {
    // 403 : clé des applications (lecture seule) ; l'interface demande la clé d'administration.
    askKey();
    throw new Error(res.status === 403 ? t('key.adminRequired') : t('key.required'));
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || t('errors.http', { status: res.status }));
  return data;
}

function toast(message, type = '') {
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.textContent = message;
  $('#toasts').append(el);
  setTimeout(() => el.remove(), type === 'error' ? 6000 : 3000);
}

async function guard(fn) {
  try {
    return await fn();
  } catch (err) {
    toast(err.message, 'error');
  }
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function systemImageUrl(system) {
  return system.hasImage ? withKey(`/api/systems/${encodeURIComponent(system.id)}/image?v=${encodeURIComponent(system.imageVersion)}`) : '';
}

function mediaUrl(game, type, bust = '') {
  return withKey(`/api/games/${game.id}/media/${type}?v=${encodeURIComponent(game.updatedAt + bust)}`);
}

function askKey() {
  const key = prompt(t('key.prompt'), apiKey());
  if (key !== null) {
    setApiKey(key.trim());
    init();
  }
}

// ---------------------------------------------------------------------------
// Langue
// ---------------------------------------------------------------------------

function setupLanguageSelect() {
  const select = $('#langSelect');
  select.innerHTML = Object.entries(LANGUAGES)
    .map(([code, name]) => `<option value="${code}">${escapeHtml(name)}</option>`)
    .join('');
  select.value = getLanguage();
  select.onchange = () => {
    setLanguage(select.value);
    refreshTexts();
  };
}

/** Redessine les parties dynamiques dans la nouvelle langue (et recharge les textes du serveur). */
function refreshTexts() {
  applyTranslations();
  updateScraperState();
  updatePlatformSelection();
  if (state.platforms) renderPlatforms();
  if (state.current && $('#sysDialog').open) openSystemSettings();
  if (state.editingGame && $('#gameDialog').open) fillGameDialog(state.editingGame);
  if ($('#dupDialog').open) openDuplicates();
  if ($('#biosDialog').open) openBios();
  if ($('#apksDialog').open) openApks();
  guard(async () => {
    await loadSystems();
    await refreshJobs();
  });
}

function updateScraperState() {
  if (!state.status) return;
  $('#scraperState').textContent = t(state.status.scrapers.screenscraper ? 'scraper.both' : 'scraper.libretroOnly');
}

// ---------------------------------------------------------------------------
// Systèmes
// ---------------------------------------------------------------------------

async function loadSystems() {
  state.systems = await api('/systems');
  renderSystems();
  if (state.current) {
    state.current = state.systems.find((s) => s.id === state.current.id) || null;
  }
  if (!state.current && state.systems.length) {
    let saved = null;
    try {
      saved = localStorage.getItem('romcloud.system');
    } catch {
      /* ignore */
    }
    state.current = state.systems.find((s) => s.id === saved) || state.systems[0];
  }
  showSystem();
}

function renderSystems() {
  const list = $('#systemList');
  list.innerHTML = '';
  for (const s of state.systems) {
    const btn = document.createElement('button');
    btn.className = `system-item${state.current?.id === s.id ? ' active' : ''}`;
    btn.innerHTML = `${s.hasImage ? `<img class="thumb" src="${systemImageUrl(s)}" alt="">` : ''}<span>${escapeHtml(s.name)}</span><span class="n">${s.gameCount}</span>`;
    btn.onclick = () => {
      state.current = s;
      try {
        localStorage.setItem('romcloud.system', s.id);
      } catch {
        /* ignore */
      }
      $('#sidebar').classList.remove('open');
      renderSystems();
      showSystem();
    };
    list.append(btn);
  }
}

async function showSystem() {
  const s = state.current;
  $('#emptyState').classList.toggle('hidden', Boolean(s));
  $('#systemView').classList.toggle('hidden', !s);
  if (!s) return;
  $('#sysName').textContent = s.name;
  $('#sysHeadImage').classList.toggle('hidden', !s.hasImage);
  $('#sysHeadImage').src = systemImageUrl(s);
  const bits = [t('system.meta.games', { n: s.gameCount }), formatSize(s.totalSize), t('system.meta.folder', { folder: s.folder })];
  if (s.players.length) bits.push(t('system.meta.emulators', { n: s.players.length }));
  if (s.biosCount) bits.push(t('system.meta.bios', { n: s.biosCount }));
  if (s.platforms.length) bits.push(t('system.meta.platforms', { list: s.platforms.map((p) => t(`platform.${p}`)).join(', ') }));
  $('#sysMeta').textContent = bits.join(' · ');
  await loadGames();
}

async function loadGames() {
  const s = state.current;
  if (!s) return;
  state.games = await api(`/systems/${encodeURIComponent(s.id)}/games`);
  renderGames();
}

function renderGames() {
  const q = $('#search').value.trim().toLowerCase();
  const filter = $('#statusFilter').value;
  const games = state.games.filter((g) => {
    if (q && !g.title.toLowerCase().includes(q) && !g.fileName.toLowerCase().includes(q)) return false;
    if (filter === 'ok' && g.scrapeStatus !== 'ok') return false;
    if (filter === 'missing' && g.scrapeStatus === 'ok') return false;
    return true;
  });
  $('#gameCount').textContent = `${games.length} / ${state.games.length}`;
  const grid = $('#games');
  grid.innerHTML = '';
  if (!state.games.length) {
    grid.innerHTML = `<p class="muted">${escapeHtml(t('system.noGames'))}</p>`;
    return;
  }
  for (const g of games) {
    const card = document.createElement('div');
    card.className = 'game';
    card.innerHTML = `
      <div class="cover">${g.hasBoxart ? `<img loading="lazy" src="${mediaUrl(g, 'boxart')}" alt="">` : escapeHtml(g.title)}</div>
      <div class="game-info">
        <div class="game-title" title="${escapeHtml(g.fileName)}">${escapeHtml(g.title)}</div>
        ${g.parts?.length ? `<div class="game-badge">${escapeHtml(partsSummary(g))}</div>` : ''}
        <div class="game-sub"><span>${formatSize(g.totalSize ?? g.size)}${g.releaseDate ? ` · ${escapeHtml(g.releaseDate.slice(0, 4))}` : ''}</span>
          <span class="dot ${g.scrapeStatus}" title="${escapeHtml(t(`status.${g.scrapeStatus}`))}"></span></div>
      </div>`;
    card.onclick = () => openGame(g);
    grid.append(card);
  }
}

// ---- Ajout d'un système ----

async function openAddDialog() {
  $('#addDialog').showModal();
  state.selectedPlatforms.clear();
  updatePlatformSelection();
  if (!state.platforms) {
    await guard(async () => {
      state.platforms = await api('/daijishou/platforms');
    });
  } else {
    // rafraîchit l'indicateur « importé »
    const ids = new Set(state.systems.map((s) => s.id));
    for (const p of state.platforms) p.imported = ids.has(p.uniqueId);
  }
  renderPlatforms();
}

function renderPlatforms() {
  const list = $('#platformList');
  if (!state.platforms) {
    list.innerHTML = `<p class="muted" style="padding:12px">${escapeHtml(t('add.catalogUnavailable'))}</p>`;
    return;
  }
  const q = $('#platformSearch').value.trim().toLowerCase();
  list.innerHTML = '';
  for (const p of state.platforms) {
    if (q && !`${p.name} ${p.uniqueId}`.toLowerCase().includes(q)) continue;
    const row = document.createElement('label');
    row.className = 'platform-row';
    const tag = p.imported
      ? t(p.importedRevision !== null && p.importedRevision < p.revision ? 'add.updateAvailable' : 'add.alreadyImported')
      : p.uniqueId;
    row.innerHTML = `<input type="checkbox" ${state.selectedPlatforms.has(p.filename) ? 'checked' : ''}>
      <span>${escapeHtml(p.name)}</span>
      <span class="tag">${p.source === 'romcloud' ? 'RomCloud · ' : ''}${escapeHtml(tag)}</span>`;
    $('input', row).onchange = (e) => {
      if (e.target.checked) state.selectedPlatforms.add(p.filename);
      else state.selectedPlatforms.delete(p.filename);
      updatePlatformSelection();
    };
    list.append(row);
  }
}

function updatePlatformSelection() {
  const n = state.selectedPlatforms.size;
  $('#platformSel').textContent = t('add.selected', { n });
  $('#importBtn').disabled = n === 0;
}

async function importPlatforms() {
  $('#importBtn').disabled = true;
  await guard(async () => {
    const imported = await api('/daijishou/import', { method: 'POST', body: { filenames: [...state.selectedPlatforms] } });
    toast(t('add.imported', { n: imported.length }));
    $('#addDialog').close();
    state.current = imported[0];
    await loadSystems();
  });
  updatePlatformSelection();
}

async function createCustomSystem(e) {
  e.preventDefault();
  const data = Object.fromEntries(new FormData(e.target));
  for (const k of Object.keys(data)) if (data[k] === '') delete data[k];
  await guard(async () => {
    const created = await api('/systems', { method: 'POST', body: data });
    toast(t('add.created', { name: created.name }));
    e.target.reset();
    $('#addDialog').close();
    state.current = created;
    await loadSystems();
  });
}

// ---- Réglages d'un système ----

function openSystemSettings() {
  const s = state.current;
  const form = $('#sysForm');
  for (const k of ['name', 'shortname', 'filenameRegex', 'libretroName', 'screenscraperId']) {
    form.elements[k].value = s[k] ?? '';
  }
  // Aucune plateforme enregistrée : proposé partout, toutes cochées.
  for (const box of form.querySelectorAll('input[name="platforms"]')) box.checked = !s.platforms.length || s.platforms.includes(box.value);
  renderSystemImage(s);
  $('#sysFolder').textContent = t('sys.folderInfo', { id: s.id, folder: s.folder });
  renderPlayers(s);
  // Sans émulateur, la section est ouverte d'emblée pour inviter à en ajouter.
  $('#sysPlayersBox').open = s.players.length === 0;
  fillCopyPlayersSelect(s);
  if (!$('#sysDialog').open) $('#sysDialog').showModal();
}

function renderPlayers(s) {
  $('#sysPlayersSummary').textContent = t('sys.emulators', { n: s.players.length });
  const list = $('#sysPlayers');
  list.innerHTML = '';
  if (!s.players.length) {
    list.innerHTML = `<li class="muted">${escapeHtml(t('sys.noEmulators'))}</li>`;
    return;
  }
  s.players.forEach((p, i) => {
    const li = document.createElement('li');
    li.innerHTML = `<div><strong>${escapeHtml(p.name)}</strong>${p.acceptedFilenameRegex ? ` <span class="muted mono">${escapeHtml(p.acceptedFilenameRegex)}</span>` : ''}<br><span class="mono">${escapeHtml(p.amStartArguments)}</span></div>`;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn small ghost danger remove';
    btn.textContent = t('sys.remove');
    btn.onclick = () => savePlayers(s.players.filter((_, j) => j !== i), t('sys.emulatorRemoved'));
    li.append(btn);
    list.append(li);
  });
}

async function fillCopyPlayersSelect(s) {
  const select = $('#copyPlayersFrom');
  const local = state.systems.filter((o) => o.id !== s.id && o.players.length);
  const options = [`<option value="">${escapeHtml(t('sys.copyChoose'))}</option>`];
  if (local.length) {
    options.push(`<optgroup label="${escapeHtml(t('sys.copyServer'))}">`);
    for (const o of local) options.push(`<option value="sys:${escapeHtml(o.id)}">${escapeHtml(o.name)} (${o.players.length})</option>`);
    options.push('</optgroup>');
  }
  select.innerHTML = options.join('');
  if (!state.platforms) {
    try {
      state.platforms = await api('/daijishou/platforms');
    } catch {
      return; // catalogue indisponible : seuls les systèmes du serveur sont proposés
    }
  }
  const group = document.createElement('optgroup');
  group.label = t('sys.copyCatalog');
  for (const p of state.platforms) {
    const opt = document.createElement('option');
    opt.value = `dj:${p.filename}`;
    opt.textContent = p.name;
    group.append(opt);
  }
  select.append(group);
}

async function copyPlayers() {
  const value = $('#copyPlayersFrom').value;
  if (!value) return;
  const btn = $('#copyPlayersBtn');
  btn.disabled = true;
  await guard(async () => {
    const source = value.startsWith('sys:')
      ? state.systems.find((o) => o.id === value.slice(4))?.players || []
      : await api(`/daijishou/platforms/${encodeURIComponent(value.slice(3))}/players`);
    const current = state.current.players;
    const known = new Set(current.map((p) => p.uniqueId));
    const added = source.filter((p) => !known.has(p.uniqueId));
    if (!added.length) return toast(t('sys.emulatorsPresent'));
    await savePlayers([...current, ...added], t('sys.emulatorsAdded', { n: added.length }));
  });
  btn.disabled = false;
}

async function savePlayers(players, message) {
  await guard(async () => {
    state.current = await api(`/systems/${encodeURIComponent(state.current.id)}`, { method: 'PUT', body: { players } });
    renderPlayers(state.current);
    toast(message);
    await loadSystems();
  });
}

function renderSystemImage(s) {
  $('#sysImagePreview').innerHTML = s.hasImage
    ? `<img src="${systemImageUrl(s)}" alt="">`
    : `<span class="muted">${escapeHtml(t('sys.noImage'))}</span>`;
  $('#sysImageDelete').disabled = !s.hasImage;
}

async function uploadSystemImage(file) {
  await guard(async () => {
    state.current = await api(`/systems/${encodeURIComponent(state.current.id)}/image`, {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': file.type },
    });
    renderSystemImage(state.current);
    toast(t('sys.imageSaved'));
    await loadSystems();
  });
}

async function deleteSystemImage() {
  await guard(async () => {
    state.current = await api(`/systems/${encodeURIComponent(state.current.id)}/image`, { method: 'DELETE' });
    renderSystemImage(state.current);
    await loadSystems();
  });
}

async function saveSystemSettings(e) {
  e.preventDefault();
  const form = new FormData(e.target);
  const data = Object.fromEntries(form);
  data.platforms = form.getAll('platforms');
  if (!data.platforms.length) return toast(t('sys.platformsNone'), 'error');
  await guard(async () => {
    state.current = await api(`/systems/${encodeURIComponent(state.current.id)}`, { method: 'PUT', body: data });
    $('#sysDialog').close();
    toast(t('sys.saved'));
    await loadSystems();
  });
}

async function deleteCurrentSystem() {
  const s = state.current;
  if (!confirm(t('sys.confirmDelete', { name: s.name }))) return;
  const deleteFiles = confirm(t('sys.confirmDeleteFiles', { n: s.gameCount, folder: s.folder }));
  await guard(async () => {
    await api(`/systems/${encodeURIComponent(s.id)}${deleteFiles ? '?deleteFiles=1' : ''}`, { method: 'DELETE' });
    $('#sysDialog').close();
    state.current = null;
    toast(t('sys.deleted'));
    await loadSystems();
  });
}

// ---------------------------------------------------------------------------
// Envoi de ROMs
// ---------------------------------------------------------------------------

function uploadFiles(files) {
  const s = state.current;
  if (!s || !files.length) return;
  const row = document.createElement('div');
  row.className = 'upload-row';
  const total = [...files].reduce((n, f) => n + f.size, 0);
  row.innerHTML = `<span>${escapeHtml(t('upload.files', { n: files.length, size: formatSize(total) }))}</span><progress max="1" value="0"></progress><span class="pct">0 %</span>`;
  $('#uploads').append(row);

  const form = new FormData();
  for (const f of files) form.append('files', f, f.name);
  const xhr = new XMLHttpRequest();
  const scrape = $('#autoScrape').checked ? '?scrape=1' : '';
  xhr.open('POST', `/api/systems/${encodeURIComponent(s.id)}/games${scrape}`);
  xhr.setRequestHeader('Accept-Language', getLanguage());
  const key = apiKey();
  if (key) xhr.setRequestHeader('Authorization', `Bearer ${key}`);
  xhr.upload.onprogress = (e) => {
    if (!e.lengthComputable) return;
    $('progress', row).value = e.loaded / e.total;
    $('.pct', row).textContent = `${Math.round((e.loaded / e.total) * 100)} %`;
  };
  xhr.onload = async () => {
    row.remove();
    let data = {};
    try {
      data = JSON.parse(xhr.responseText);
    } catch {
      /* ignore */
    }
    if (xhr.status >= 300) return toast(data.error || t('upload.failed', { status: xhr.status }), 'error');
    const rejected = data.rejected.length ? t('upload.rejected', { n: data.rejected.length }) : '';
    toast(t('upload.done', { n: data.saved.length }) + rejected, data.rejected.length ? 'error' : '');
    if (data.job) refreshJobs();
    await loadSystems();
  };
  xhr.onerror = () => {
    row.remove();
    toast(t('upload.interrupted'), 'error');
  };
  xhr.send(form);
}

// ---------------------------------------------------------------------------
// Fiche d'un jeu
// ---------------------------------------------------------------------------

/** « 3 disques · 1 mise à jour · 2 DLC » : parties d'un jeu (le premier disque est le jeu lui-même). */
function partsSummary(game) {
  const count = (kind) => game.parts.filter((p) => p.kind === kind).length;
  const discs = count('disc');
  return [
    discs && t('parts.discs', { n: discs + 1 }),
    count('update') && t('parts.updates', { n: count('update') }),
    count('dlc') && t('parts.dlcs', { n: count('dlc') }),
  ].filter(Boolean).join(' · ');
}

/** Disques, mises à jour et DLC du jeu, et rattachement de ce fichier à un autre jeu. */
function renderGameParts(game) {
  const box = $('#gameParts');
  const label = (p) => (p.kind === 'disc' ? t('parts.disc', { n: p.index ?? '?' }) : t(`parts.${p.kind}`));
  const parts = game.parts || [];
  const others = state.games.filter((g) => g.id !== game.id).sort((a, b) => a.title.localeCompare(b.title));
  box.innerHTML = `
    ${parts.length ? `<strong>${escapeHtml(t('parts.title'))}</strong>
      <ul>${parts.map((p) => `<li><span><b>${escapeHtml(label(p))}</b> — <span class="mono">${escapeHtml(p.fileName)}</span> — ${formatSize(p.size)}</span>
        <button type="button" class="btn small ghost" data-detach="${p.id}">${escapeHtml(t('parts.detach'))}</button></li>`).join('')}</ul>`
      : `<span class="muted">${escapeHtml(t('parts.attachHint'))}</span>
      <div class="row">
        <select id="attachParent"><option value="">${escapeHtml(t('parts.choose'))}</option>${others
          .map((g) => `<option value="${g.id}">${escapeHtml(g.title)} — ${escapeHtml(g.fileName)}</option>`).join('')}</select>
        <select id="attachKind">${['disc', 'update', 'dlc'].map((k) => `<option value="${k}">${escapeHtml(t(`parts.kind.${k}`))}</option>`).join('')}</select>
        <button type="button" class="btn small" id="attachBtn">${escapeHtml(t('parts.attach'))}</button>
      </div>`}
    ${game.groupMode === 'manual' ? `<div><button type="button" class="btn small ghost" id="autoGroupBtn">${escapeHtml(t('parts.auto'))}</button></div>` : ''}`;
  const regroup = async (id, body, message) => {
    await guard(async () => {
      const updated = await api(`/games/${id}/group`, { method: 'PUT', body });
      // Partie détachée depuis la fiche du jeu : la fiche reste celle du jeu.
      const shown = String(id) === String(game.id) ? updated : await api(`/games/${game.id}`);
      await loadGames();
      state.editingGame = shown;
      fillGameDialog(shown);
      toast(message);
    });
  };
  for (const btn of $$('[data-detach]', box)) btn.onclick = () => regroup(btn.dataset.detach, { parentId: null }, t('parts.detached'));
  $('#attachBtn', box)?.addEventListener('click', () => {
    const parentId = $('#attachParent', box).value;
    if (!parentId) return toast(t('parts.choose'), 'error');
    regroup(game.id, { parentId: Number(parentId), kind: $('#attachKind', box).value }, t('parts.attached'));
  });
  $('#autoGroupBtn', box)?.addEventListener('click', () => regroup(game.id, { auto: true }, t('parts.autoDone')));
}

function openGame(game) {
  state.editingGame = game;
  fillGameDialog(game);
  $('#gameDialog').showModal();
}

function fillGameDialog(game, bust = '') {
  $('#gameDialogTitle').textContent = game.title;
  $('#gameFile').textContent = `${game.fileName} — ${formatSize(game.size)}${game.crc32 ? ` — CRC ${game.crc32}` : ''}`;
  const form = $('#gameForm');
  for (const k of ['title', 'releaseDate', 'players', 'rating', 'developer', 'publisher', 'genre', 'description']) {
    form.elements[k].value = game[k] ?? '';
  }
  const statusText = {
    none: t('game.never'),
    ok: t('game.scrapedVia', { source: game.scrapeSource }),
    notfound: t('game.notFound'),
    error: t('game.error', { error: game.scrapeError || '' }),
  }[game.scrapeStatus];
  $('#gameScrapeInfo').textContent = `${statusText}${game.scrapedAt ? ` (${game.scrapedAt})` : ''}`;
  for (const slot of $$('.media-slot')) {
    const type = slot.dataset.type;
    const has = type === 'boxart' ? game.hasBoxart : game.hasScreenshot;
    $('img', slot).src = has ? mediaUrl(game, type, bust) : '';
  }
  $('#gameDownload').href = withKey(`/api/games/${game.id}/file`);
  renderGameParts(game);
}

async function saveGame(e) {
  e.preventDefault();
  const data = Object.fromEntries(new FormData(e.target));
  await guard(async () => {
    await api(`/games/${state.editingGame.id}`, { method: 'PUT', body: data });
    $('#gameDialog').close();
    toast(t('game.saved'));
    await loadGames();
  });
}

async function scrapeCurrentGame(source, btn) {
  const buttons = $$('[data-scrape]');
  buttons.forEach((b) => (b.disabled = true));
  const label = btn.textContent;
  btn.textContent = t('game.scraping');
  await guard(async () => {
    const game = await api(`/games/${state.editingGame.id}/scrape`, { method: 'POST', body: { source } });
    state.editingGame = game;
    fillGameDialog(game, `-${Date.now()}`);
    toast(t(game.scrapeStatus === 'ok' ? 'game.found' : 'game.missing'), game.scrapeStatus === 'ok' ? '' : 'error');
    await loadGames();
  });
  btn.textContent = label;
  buttons.forEach((b) => (b.disabled = false));
}

async function uploadMedia(type, file) {
  await guard(async () => {
    const game = await api(`/games/${state.editingGame.id}/media/${type}`, {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': file.type },
    });
    state.editingGame = game;
    fillGameDialog(game, `-${Date.now()}`);
    await loadGames();
  });
}

async function deleteCurrentGame() {
  const g = state.editingGame;
  if (!confirm(t('game.confirmDelete', { file: g.fileName }))) return;
  await guard(async () => {
    await api(`/games/${g.id}`, { method: 'DELETE' });
    $('#gameDialog').close();
    toast(t('game.deleted'));
    await loadSystems();
  });
}

// ---------------------------------------------------------------------------
// Doublons
// ---------------------------------------------------------------------------

const dup = { groups: [], marked: new Set(), sizes: new Map() };

async function openDuplicates() {
  const s = state.current;
  $('#dupTitle').textContent = t('dup.title', { name: s.name });
  $('#dupSummary').textContent = t('dup.analysing');
  $('#dupGroups').innerHTML = '';
  dup.marked.clear();
  updateDupSelection();
  if (!$('#dupDialog').open) $('#dupDialog').showModal();
  await guard(async () => {
    const res = await api(`/systems/${encodeURIComponent(s.id)}/duplicates`);
    dup.groups = [...res.identical, ...res.similar];
    dup.sizes = new Map(dup.groups.flatMap((g) => g.games.map((x) => [x.id, x.size])));
    // Copies identiques : toutes pré-cochées sauf le fichier conservé. Versions différentes : rien.
    for (const g of res.identical) for (const x of g.games) if (x.id !== g.keepId) dup.marked.add(x.id);
    const parts = [
      res.identical.length ? t('dup.groupsIdentical', { n: res.identical.length }) : t('dup.noneIdentical'),
      res.similar.length ? t('dup.groupsSimilar', { n: res.similar.length }) : t('dup.noneSimilar'),
    ];
    let summary = parts.join(', ') + '.';
    if (res.unhashed) summary += t('dup.unhashed', { n: res.unhashed, mb: res.hashMaxMb });
    $('#dupSummary').textContent = summary;
    renderDuplicates(res);
  });
}

function renderDuplicates(res) {
  const box = $('#dupGroups');
  box.innerHTML = '';
  const section = (title, hint, groups) => {
    if (!groups.length) return;
    const h = document.createElement('h4');
    h.textContent = title;
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = hint;
    box.append(h, p);
    for (const g of groups) box.append(renderDupGroup(g));
  };
  section(t('dup.identical'), t('dup.identicalHint'), res.identical);
  section(t('dup.similar'), t('dup.similarHint'), res.similar);
  updateDupSelection();
}

function renderDupGroup(g) {
  const el = document.createElement('div');
  el.className = 'dup-group';
  const total = g.games.reduce((n, x) => n + x.size, 0);
  el.innerHTML = `<div class="dup-group-head"><span>${escapeHtml(g.games[0].title)}</span><span class="muted">${escapeHtml(t('dup.files', { n: g.games.length, size: formatSize(total) }))}</span></div>`;
  for (const x of g.games) {
    const row = document.createElement('label');
    row.className = `dup-row${dup.marked.has(x.id) ? ' marked' : ''}`;
    row.innerHTML = `<input type="checkbox" ${dup.marked.has(x.id) ? 'checked' : ''}>
      <span class="name">${escapeHtml(x.fileName)}</span>
      ${x.id === g.keepId ? `<span class="keep">${escapeHtml(t('dup.keep'))}</span>` : ''}
      <span class="muted">${formatSize(x.size)}${x.scrapeStatus === 'ok' ? escapeHtml(t('dup.scraped')) : ''}</span>`;
    $('input', row).onchange = (e) => {
      if (e.target.checked) dup.marked.add(x.id);
      else dup.marked.delete(x.id);
      row.classList.toggle('marked', e.target.checked);
      updateDupSelection();
    };
    el.append(row);
  }
  return el;
}

function updateDupSelection() {
  const n = dup.marked.size;
  const size = [...dup.marked].reduce((total, id) => total + (dup.sizes.get(id) || 0), 0);
  $('#dupSelection').textContent = n ? t('dup.selection', { n, size: formatSize(size) }) : t('dup.noSelection');
  $('#dupDeleteBtn').disabled = n === 0;
}

async function deleteDuplicates() {
  const ids = [...dup.marked];
  // Garde-fou : ne jamais supprimer tous les fichiers d'un groupe sans le signaler.
  const wiped = dup.groups.filter((g) => g.games.every((x) => dup.marked.has(x.id)));
  if (wiped.length) {
    const more = wiped.length > 1 ? t('dup.confirmWipeMore', { n: wiped.length - 1 }) : '';
    if (!confirm(t('dup.confirmWipe', { title: wiped[0].games[0].title, more }))) return;
  }
  if (!confirm(t('dup.confirmDelete', { n: ids.length }))) return;
  await guard(async () => {
    const r = await api(`/systems/${encodeURIComponent(state.current.id)}/duplicates/delete`, { method: 'POST', body: { ids } });
    toast(t('dup.deleted', { n: r.deleted, size: formatSize(r.freed) }));
    await loadSystems();
    await openDuplicates();
  });
}

// ---------------------------------------------------------------------------
// Scraping d'un système et tâches
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// BIOS
// ---------------------------------------------------------------------------

async function openBios() {
  const s = state.current;
  if (!s) return;
  $('#biosTitle').textContent = t('bios.title', { name: s.name });
  if (!$('#biosDialog').open) {
    $('#biosSummary').textContent = t('bios.loading');
    $('#biosContent').innerHTML = '';
    $('#biosDialog').showModal();
  }
  await guard(async () => renderBios(await api(`/systems/${encodeURIComponent(s.id)}/bios`)));
}

/** « dc/naomi.zip (Naomi Bios from MAME) » -> « Naomi Bios from MAME » (le chemin est déjà affiché). */
function biosDescription({ path, description }) {
  if (!description?.startsWith(path)) return description;
  return description.slice(path.length).trim().replace(/^\((.*)\)$/, '$1');
}

function renderBios(res) {
  const bits = [t('bios.files', { n: res.files.length })];
  if (!res.cores.length && !res.expected.length) bits.push(t('bios.noCores'));
  else if (res.cores.length && !res.coreInfoAvailable) bits.push(t('bios.noCoreInfo', { cores: res.cores.join(', ') }));
  else if (res.cores.length) bits.push(t('bios.cores', { cores: res.cores.join(', ') }));
  const required = res.expected.filter((e) => e.required && !e.present).length;
  if (required) bits.push(t('bios.missingRequired', { n: required }));
  const updates = res.expected.filter((e) => e.source?.updateAvailable).length;
  if (updates) bits.push(t('bios.updates', { n: updates }));
  $('#biosSummary').textContent = bits.join(' · ');

  const box = $('#biosContent');
  box.innerHTML = '';
  const section = (title, hint, rows) => {
    const h = document.createElement('h4');
    h.textContent = title;
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = hint;
    const group = document.createElement('div');
    group.className = 'dup-group';
    group.append(...rows);
    box.append(h, p, group);
  };

  if (res.expected.length) {
    section(t('bios.expected'), t('bios.expectedHint'), res.expected.map((e) => {
      const row = document.createElement('div');
      row.className = 'dup-row bios-row';
      const tag = `<span class="tag">${escapeHtml(t(`bios.kind.${e.kind || 'bios'}`))}</span>
        <span class="tag${e.required ? ' req' : ''}">${escapeHtml(t(e.required ? 'bios.required' : 'bios.optional'))}</span>`;
      const details = [
        e.folder && t('bios.folder', { n: e.fileCount || 0 }),
        biosDescription(e),
        e.emulators?.length && t('bios.emulators', { list: e.emulators.join(', ') }),
        e.md5s?.length && `md5 ${e.md5s.join(' / ')}`,
        e.sha1s?.length && `sha1 ${e.sha1s.join(' / ')}`,
      ].filter(Boolean).join(' · ');
      // Source sur Internet : versions installée et publiée.
      const src = e.source;
      const sourceLine = src ? `<br><span class="muted">${escapeHtml(t('bios.source', { name: src.name }))} · ${escapeHtml(
        src.latest ? t('bios.latest', { version: src.latest }) : t('bios.latestUnknown'),
      )}${src.installed ? ` · ${escapeHtml(t('bios.installed', { version: src.installed }))}` : ''}${src.page ? ` · <a href="${escapeHtml(src.page)}" target="_blank" rel="noopener">${escapeHtml(t('bios.sourcePage'))}</a>` : ''}</span>` : '';
      row.innerHTML = `<span class="bios-state ${e.present ? 'ok' : 'missing'}">${e.present ? '✓' : '✗'}</span>
        <span class="name"><code>${escapeHtml(e.path)}</code><br><span class="muted">${escapeHtml(details)}</span>${sourceLine}</span>
        ${tag}`;
      if (src?.updateAvailable) {
        const fetchBtn = document.createElement('button');
        fetchBtn.className = 'btn small primary';
        fetchBtn.textContent = t(e.present ? 'bios.update' : 'bios.fetch', { version: src.latest });
        fetchBtn.onclick = () => fetchBios(e.path, fetchBtn);
        row.append(fetchBtn);
      }
      // Dossier (« pcsx2/bios ») : un .zip de son contenu, extrait sur le serveur ; toujours proposé.
      if (!e.present || e.folder) {
        const label = document.createElement('label');
        label.className = 'btn small';
        label.innerHTML = `<span>${escapeHtml(t(e.folder ? 'bios.sendZip' : 'bios.send'))}</span><input type="file" hidden>`;
        $('input', label).onchange = (ev) => {
          if (ev.target.files[0]) uploadBios([ev.target.files[0]], e.path);
        };
        row.append(label);
      }
      return row;
    }));
  }

  if (res.files.length) {
    section(t('bios.onServer'), t('bios.onServerHint'), res.files.map((f) => {
      const row = document.createElement('div');
      row.className = 'dup-row bios-row';
      const status = { ok: t('bios.hashOk'), mismatch: t('bios.hashMismatch') }[f.hashStatus];
      row.innerHTML = `<span class="name"><code>${escapeHtml(f.path)}</code><br><span class="muted">${formatSize(f.size)} · md5 ${escapeHtml(f.md5 || '?')} · sha1 ${escapeHtml(f.sha1 || '?')}${f.description ? ` · ${escapeHtml(biosDescription(f))}` : ''}</span></span>
        ${status ? `<span class="tag ${f.hashStatus}">${escapeHtml(status)}</span>` : ''}
        <a class="btn small" href="${withKey(`/api/bios/${f.id}/file`)}" download>${escapeHtml(t('bios.download'))}</a>
        <button class="btn small danger">${escapeHtml(t('bios.delete'))}</button>`;
      $('button', row).onclick = () => guard(async () => {
        if (!confirm(t('bios.confirmDelete', { path: f.path }))) return;
        await api(`/bios/${f.id}`, { method: 'DELETE' });
        await openBios();
        await loadSystems();
      });
      return row;
    }));
  }
  if (!res.expected.length && !res.files.length) {
    const p = document.createElement('p');
    p.textContent = t('bios.empty');
    box.append(p);
  }
}

/** Récupère la dernière version d'un fichier sur sa source Internet (firmware officiel, fichier libre). */
function fetchBios(biosPath, button) {
  const s = state.current;
  if (!s) return;
  button.disabled = true;
  button.textContent = t('bios.fetching');
  guard(async () => {
    try {
      const data = await api(`/systems/${encodeURIComponent(s.id)}/bios/fetch`, { method: 'POST', body: { path: biosPath } });
      toast(t('bios.fetched', { path: data.fetched.path, version: data.fetched.version }));
      renderBios(data);
      await loadSystems();
    } finally {
      button.disabled = false;
    }
  });
}

/** Envoie des BIOS ; « target » impose le chemin (fichier choisi pour un BIOS attendu précis). */
function uploadBios(files, target) {
  const s = state.current;
  if (!s || !files.length) return;
  const form = new FormData();
  if (target) form.append('path', target);
  for (const f of files) form.append('files', f, f.name);
  const headers = { 'Accept-Language': getLanguage() };
  const key = apiKey();
  if (key) headers.Authorization = `Bearer ${key}`;
  guard(async () => {
    const res = await fetch(`/api/systems/${encodeURIComponent(s.id)}/bios`, { method: 'POST', headers, body: form });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || t('errors.http', { status: res.status }));
    toast(t('bios.uploaded', { n: data.saved.length }));
    renderBios(data);
    await loadSystems();
  });
}

// ---------------------------------------------------------------------------
// APK des émulateurs Android
// ---------------------------------------------------------------------------

async function openApks() {
  if (!$('#apksDialog').open) {
    $('#apksContent').innerHTML = `<p class="muted">${escapeHtml(t('apks.loading'))}</p>`;
    $('#apksDialog').showModal();
  }
  await guard(async () => {
    const [emulators, list] = await Promise.all([api('/apks/emulators'), api('/apks')]);
    renderApks(emulators, list);
  });
}

function renderApks(emulators, list) {
  const box = $('#apksContent');
  box.innerHTML = '';
  const byId = new Map(list.map((a) => [a.id, a]));
  const section = (title, hint, rows) => {
    const h = document.createElement('h4');
    h.textContent = title;
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = hint;
    const group = document.createElement('div');
    group.className = 'dup-group';
    group.append(...rows);
    box.append(h, p, group);
  };
  const version = (a) => [a.versionName && `v${a.versionName}`, formatSize(a.size)].filter(Boolean).join(' · ');

  if (emulators.length) {
    section(t('apks.used'), t('apks.usedHint'), emulators.map((e) => {
      const apk = e.apkId != null ? byId.get(e.apkId) : null;
      const row = document.createElement('div');
      row.className = 'dup-row bios-row';
      const store = `https://play.google.com/store/apps/details?id=${encodeURIComponent(e.packageName)}`;
      row.innerHTML = `<span class="bios-state ${apk ? 'ok' : 'missing'}">${apk ? '✓' : '–'}</span>
        <span class="name"><strong>${escapeHtml(e.names.join(' / '))}</strong> <code>${escapeHtml(e.packageName)}</code><br>
          <span class="muted">${escapeHtml(e.systems.map((s) => s.name).join(', '))}</span></span>
        ${apk ? `<span class="tag ok">${escapeHtml(t('apks.onServer', { version: version(apk) }))}</span>` : ''}
        <a class="btn small" href="${store}" target="_blank" rel="noopener">${escapeHtml(t('apks.playStore'))}</a>`;
      if (!apk) {
        const label = document.createElement('label');
        label.className = 'btn small';
        label.innerHTML = `<span>${escapeHtml(t('apks.send'))}</span><input type="file" accept=".apk" hidden>`;
        $('input', label).onchange = (ev) => {
          if (ev.target.files[0]) uploadApks([ev.target.files[0]], e.packageName);
        };
        row.append(label);
      }
      return row;
    }));
  }

  if (list.length) {
    section(t('apks.files'), t('apks.filesHint'), list.map((a) => {
      const row = document.createElement('div');
      row.className = 'dup-row bios-row';
      row.innerHTML = `<span class="name"><strong>${escapeHtml(a.label)}</strong> <code>${escapeHtml(a.packageName)}</code><br>
          <span class="muted">${escapeHtml([version(a), a.versionCode != null && t('apks.versionCode', { code: a.versionCode }), a.fileName].filter(Boolean).join(' · '))}</span></span>
        <button class="btn small" data-rename>${escapeHtml(t('apks.rename'))}</button>
        <a class="btn small" href="${withKey(`/api/apks/${a.id}/file`)}" download>${escapeHtml(t('bios.download'))}</a>
        <button class="btn small danger" data-delete>${escapeHtml(t('bios.delete'))}</button>`;
      $('[data-rename]', row).onclick = () => guard(async () => {
        const label = prompt(t('apks.renamePrompt'), a.label);
        if (!label || label.trim() === a.label) return;
        await api(`/apks/${a.id}`, { method: 'PUT', body: { label } });
        await openApks();
      });
      $('[data-delete]', row).onclick = () => guard(async () => {
        if (!confirm(t('apks.confirmDelete', { label: a.label }))) return;
        await api(`/apks/${a.id}`, { method: 'DELETE' });
        await openApks();
      });
      return row;
    }));
  }
  if (!emulators.length && !list.length) {
    const p = document.createElement('p');
    p.textContent = t('apks.empty');
    box.append(p);
  }
}

/** Envoie des APK (avec progression : un APK d'émulateur pèse souvent plus de 100 Mo). */
function uploadApks(files, expectedPackage) {
  if (!files.length) return;
  const row = document.createElement('div');
  row.className = 'upload-row';
  const total = files.reduce((n, f) => n + f.size, 0);
  row.innerHTML = `<span>${escapeHtml(t('upload.files', { n: files.length, size: formatSize(total) }))}</span><progress max="1" value="0"></progress><span class="pct">0 %</span>`;
  $('#apksUpload').append(row);
  const form = new FormData();
  for (const f of files) form.append('files', f, f.name);
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/apks');
  xhr.setRequestHeader('Accept-Language', getLanguage());
  const key = apiKey();
  if (key) xhr.setRequestHeader('Authorization', `Bearer ${key}`);
  xhr.upload.onprogress = (e) => {
    if (!e.lengthComputable) return;
    $('progress', row).value = e.loaded / e.total;
    $('.pct', row).textContent = `${Math.round((e.loaded / e.total) * 100)} %`;
  };
  xhr.onload = async () => {
    row.remove();
    let data = {};
    try {
      data = JSON.parse(xhr.responseText);
    } catch {
      /* ignore */
    }
    if (xhr.status >= 300) return toast(data.error || t('upload.failed', { status: xhr.status }), 'error');
    const other = expectedPackage && data.find((a) => a.packageName !== expectedPackage);
    if (other) toast(t('apks.otherPackage', { got: other.packageName, expected: expectedPackage }), 'error');
    else toast(t('apks.uploaded', { list: data.map((a) => `${a.label} ${a.versionName || ''}`.trim()).join(', ') }));
    await openApks();
  };
  xhr.onerror = () => {
    row.remove();
    toast(t('upload.interrupted'), 'error');
  };
  xhr.send(form);
}

async function scrapeSystem() {
  const s = state.current;
  const onlyMissing = confirm(t('scrape.onlyMissing'));
  await guard(async () => {
    const res = await api(`/systems/${encodeURIComponent(s.id)}/scrape`, { method: 'POST', body: { onlyMissing } });
    if (!res.job) return toast(res.message);
    toast(t('scrape.started', { n: res.job.total }));
    refreshJobs();
  });
}

let jobsTimer = null;
let lastActive = 0;

async function refreshJobs() {
  clearTimeout(jobsTimer);
  let jobs = [];
  try {
    jobs = await api('/jobs');
  } catch {
    return;
  }
  const active = jobs.filter((j) => j.status === 'running' || j.status === 'queued');
  $('#jobsCount').textContent = active.length;
  $('#jobsCount').classList.toggle('hidden', !active.length);
  renderJobs(jobs);
  if (active.length) {
    // pendant un scraping, rafraîchit la grille régulièrement pour afficher les jaquettes
    if (state.current && active.some((j) => j.systemId === state.current.id)) loadGames();
    jobsTimer = setTimeout(refreshJobs, 2000);
  } else if (lastActive) {
    loadSystems();
  }
  lastActive = active.length;
}

function renderJobs(jobs) {
  const list = $('#jobsList');
  if (!jobs.length) {
    list.innerHTML = `<p class="muted">${escapeHtml(t('jobs.none'))}</p>`;
    return;
  }
  list.innerHTML = '';
  for (const j of jobs) {
    const el = document.createElement('div');
    el.className = 'job';
    el.innerHTML = `
      <div class="job-head"><strong>${escapeHtml(j.label)}</strong><span class="muted">${escapeHtml(t(`jobs.${j.status}`))}</span></div>
      <progress max="${j.total}" value="${j.done}"></progress>
      <div class="muted">${escapeHtml(t('jobs.progress', j))}</div>
      ${j.lastError ? `<div class="err">${escapeHtml(j.lastError)}</div>` : ''}`;
    if (j.cancellable) {
      const btn = document.createElement('button');
      btn.className = 'btn small ghost';
      btn.textContent = t('jobs.cancel');
      btn.onclick = () => guard(async () => {
        await api(`/jobs/${j.id}`, { method: 'DELETE' });
        refreshJobs();
      });
      el.append(btn);
    }
    list.append(el);
  }
}

// ---------------------------------------------------------------------------
// Initialisation
// ---------------------------------------------------------------------------

function bindEvents() {
  $('#menuBtn').onclick = () => $('#sidebar').classList.toggle('open');
  $('#keyBtn').onclick = askKey;
  $('#jobsBtn').onclick = () => {
    refreshJobs();
    $('#jobsDialog').showModal();
  };
  $('#addSystemBtn').onclick = openAddDialog;
  $('#emptyAddBtn').onclick = openAddDialog;
  $('#scanAllBtn').onclick = () => guard(async () => {
    await api('/scan', { method: 'POST' });
    toast(t('rescan.allDone'));
    await loadSystems();
  });

  for (const tab of $$('.tab')) {
    tab.onclick = () => {
      $$('.tab').forEach((x) => x.classList.toggle('active', x === tab));
      $('#tab-daijishou').classList.toggle('hidden', tab.dataset.tab !== 'daijishou');
      $('#tab-custom').classList.toggle('hidden', tab.dataset.tab !== 'custom');
    };
  }
  $('#platformSearch').oninput = renderPlatforms;
  $('#importBtn').onclick = importPlatforms;
  $('#tab-custom').onsubmit = createCustomSystem;

  $('#editSysBtn').onclick = openSystemSettings;
  $('#sysForm').onsubmit = saveSystemSettings;
  $('#deleteSysBtn').onclick = deleteCurrentSystem;
  $('#sysImageInput').onchange = (e) => {
    const file = e.target.files[0];
    if (file) uploadSystemImage(file);
    e.target.value = '';
  };
  $('#sysImageDelete').onclick = deleteSystemImage;
  $('#copyPlayersBtn').onclick = copyPlayers;
  $('#scanSysBtn').onclick = () => guard(async () => {
    const r = await api(`/systems/${encodeURIComponent(state.current.id)}/scan`, { method: 'POST' });
    toast(t('rescan.done', { added: r.added.length, updated: r.updated, removed: r.removed }));
    await loadSystems();
  });
  $('#scrapeSysBtn').onclick = scrapeSystem;
  $('#dupSysBtn').onclick = openDuplicates;
  $('#dupDeleteBtn').onclick = deleteDuplicates;
  $('#biosSysBtn').onclick = openBios;
  $('#apksBtn').onclick = openApks;
  $('#apksInput').onchange = (e) => {
    uploadApks([...e.target.files]);
    e.target.value = '';
  };
  $('#biosInput').onchange = (e) => {
    uploadBios([...e.target.files]);
    e.target.value = '';
  };

  $('#search').oninput = renderGames;
  $('#statusFilter').onchange = renderGames;
  $('#fileInput').onchange = (e) => {
    uploadFiles(e.target.files);
    e.target.value = '';
  };
  const dz = $('#dropzone');
  const main = $('#main');
  main.addEventListener('dragover', (e) => {
    if (!state.current) return;
    e.preventDefault();
    dz.classList.add('over');
  });
  main.addEventListener('dragleave', (e) => {
    if (!main.contains(e.relatedTarget)) dz.classList.remove('over');
  });
  main.addEventListener('drop', (e) => {
    e.preventDefault();
    dz.classList.remove('over');
    if (state.current) uploadFiles(e.dataTransfer.files);
  });

  $('#gameForm').onsubmit = saveGame;
  $('#deleteGameBtn').onclick = deleteCurrentGame;
  for (const btn of $$('[data-scrape]')) btn.onclick = () => scrapeCurrentGame(btn.dataset.scrape, btn);
  for (const slot of $$('.media-slot')) {
    $('input', slot).onchange = (e) => {
      const file = e.target.files[0];
      if (file) uploadMedia(slot.dataset.type, file);
      e.target.value = '';
    };
  }
}

async function init() {
  await guard(async () => {
    state.status = await api('/status');
    updateScraperState();
    await loadSystems();
    refreshJobs();
  });
}

applyTranslations();
setupLanguageSelect();
updatePlatformSelection();
bindEvents();
initUsers({ api, guard, toast, escapeHtml });
init();
