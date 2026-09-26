// Interface d'administration RomCloud (vanilla JS, sans build).

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
  const opts = { method, headers: { ...headers } };
  const key = apiKey();
  if (key) opts.headers.Authorization = `Bearer ${key}`;
  if (body !== undefined && !(body instanceof Blob)) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  } else if (body) {
    opts.body = body;
  }
  const res = await fetch(`/api${path}`, opts);
  if (res.status === 401) {
    askKey();
    throw new Error('Clé d’API requise');
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Erreur ${res.status}`);
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

function formatSize(bytes) {
  if (!bytes) return '0 o';
  const units = ['o', 'Ko', 'Mo', 'Go', 'To'];
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  return `${(bytes / 1024 ** i).toFixed(i ? 1 : 0)} ${units[i]}`;
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
  const key = prompt('Clé d’API du serveur RomCloud :', apiKey());
  if (key !== null) {
    setApiKey(key.trim());
    init();
  }
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
  const bits = [`${s.gameCount} jeu(x)`, formatSize(s.totalSize), `dossier : roms/${s.folder}`];
  if (s.players.length) bits.push(`${s.players.length} émulateur(s)`);
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
    grid.innerHTML = '<p class="muted">Aucun jeu pour l’instant. Déposez des ROMs ci-dessus ou copiez-les dans le dossier du système puis cliquez sur « Rescanner ».</p>';
    return;
  }
  const statusLabel = { ok: 'Scrapé', none: 'Non scrapé', notfound: 'Introuvable', error: 'Erreur' };
  for (const g of games) {
    const card = document.createElement('div');
    card.className = 'game';
    card.innerHTML = `
      <div class="cover">${g.hasBoxart ? `<img loading="lazy" src="${mediaUrl(g, 'boxart')}" alt="">` : escapeHtml(g.title)}</div>
      <div class="game-info">
        <div class="game-title" title="${escapeHtml(g.fileName)}">${escapeHtml(g.title)}</div>
        <div class="game-sub"><span>${formatSize(g.size)}${g.releaseDate ? ` · ${escapeHtml(g.releaseDate.slice(0, 4))}` : ''}</span>
          <span class="dot ${g.scrapeStatus}" title="${statusLabel[g.scrapeStatus] || ''}"></span></div>
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
    list.innerHTML = '<p class="muted" style="padding:12px">Catalogue indisponible (pas d’accès à GitHub ?). Utilisez l’onglet « Personnalisé ».</p>';
    return;
  }
  const q = $('#platformSearch').value.trim().toLowerCase();
  list.innerHTML = '';
  for (const p of state.platforms) {
    if (q && !`${p.name} ${p.uniqueId}`.toLowerCase().includes(q)) continue;
    const row = document.createElement('label');
    row.className = 'platform-row';
    row.innerHTML = `<input type="checkbox" ${state.selectedPlatforms.has(p.filename) ? 'checked' : ''}>
      <span>${escapeHtml(p.name)}</span>
      <span class="tag">${p.imported ? (p.importedRevision !== null && p.importedRevision < p.revision ? 'mise à jour dispo' : 'déjà importé') : escapeHtml(p.uniqueId)}</span>`;
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
  $('#platformSel').textContent = `${n} sélectionné${n > 1 ? 's' : ''}`;
  $('#importBtn').disabled = n === 0;
}

async function importPlatforms() {
  $('#importBtn').disabled = true;
  await guard(async () => {
    const imported = await api('/daijishou/import', { method: 'POST', body: { filenames: [...state.selectedPlatforms] } });
    toast(`${imported.length} système(s) importé(s)`);
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
    toast(`Système « ${created.name} » créé`);
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
  renderSystemImage(s);
  $('#sysFolder').textContent = `Identifiant : ${s.id} — dossier des ROMs : roms/${s.folder}`;
  $('#sysPlayers').innerHTML = s.players.length
    ? s.players.map((p) => `<li><strong>${escapeHtml(p.name)}</strong><br><span class="mono">${escapeHtml(p.amStartArguments)}</span></li>`).join('')
    : '<li class="muted">Aucun (système personnalisé). L’application Android ne pourra lancer les jeux que via « Ouvrir avec ».</li>';
  $('#sysDialog').showModal();
}

function renderSystemImage(s) {
  $('#sysImagePreview').innerHTML = s.hasImage
    ? `<img src="${systemImageUrl(s)}" alt="">`
    : '<span class="muted">Aucune image</span>';
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
    toast('Image du système enregistrée');
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
  const data = Object.fromEntries(new FormData(e.target));
  await guard(async () => {
    state.current = await api(`/systems/${encodeURIComponent(state.current.id)}`, { method: 'PUT', body: data });
    $('#sysDialog').close();
    toast('Réglages enregistrés');
    await loadSystems();
  });
}

async function deleteCurrentSystem() {
  const s = state.current;
  if (!confirm(`Supprimer le système « ${s.name} » de RomCloud ?`)) return;
  const deleteFiles = confirm(`Supprimer aussi les ${s.gameCount} fichier(s) du dossier roms/${s.folder} ?\n\nOK = supprimer les fichiers, Annuler = les conserver sur le disque.`);
  await guard(async () => {
    await api(`/systems/${encodeURIComponent(s.id)}${deleteFiles ? '?deleteFiles=1' : ''}`, { method: 'DELETE' });
    $('#sysDialog').close();
    state.current = null;
    toast('Système supprimé');
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
  row.innerHTML = `<span>${files.length} fichier(s) · ${formatSize(total)}</span><progress max="1" value="0"></progress><span class="pct">0 %</span>`;
  $('#uploads').append(row);

  const form = new FormData();
  for (const f of files) form.append('files', f, f.name);
  const xhr = new XMLHttpRequest();
  const scrape = $('#autoScrape').checked ? '?scrape=1' : '';
  xhr.open('POST', `/api/systems/${encodeURIComponent(s.id)}/games${scrape}`);
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
    if (xhr.status >= 300) return toast(data.error || `Échec de l’envoi (${xhr.status})`, 'error');
    toast(`${data.saved.length} ROM(s) ajoutée(s)${data.rejected.length ? `, ${data.rejected.length} refusée(s) (extension non acceptée)` : ''}`, data.rejected.length ? 'error' : '');
    if (data.job) refreshJobs();
    await loadSystems();
  };
  xhr.onerror = () => {
    row.remove();
    toast('Échec de l’envoi (connexion interrompue)', 'error');
  };
  xhr.send(form);
}

// ---------------------------------------------------------------------------
// Fiche d'un jeu
// ---------------------------------------------------------------------------

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
    none: 'Jamais scrapé',
    ok: `Scrapé via ${game.scrapeSource}`,
    notfound: 'Introuvable lors du dernier scraping',
    error: `Erreur : ${game.scrapeError || ''}`,
  }[game.scrapeStatus];
  $('#gameScrapeInfo').textContent = `${statusText}${game.scrapedAt ? ` (${game.scrapedAt})` : ''}`;
  for (const slot of $$('.media-slot')) {
    const type = slot.dataset.type;
    const has = type === 'boxart' ? game.hasBoxart : game.hasScreenshot;
    $('img', slot).src = has ? mediaUrl(game, type, bust) : '';
  }
  $('#gameDownload').href = withKey(`/api/games/${game.id}/file`);
}

async function saveGame(e) {
  e.preventDefault();
  const data = Object.fromEntries(new FormData(e.target));
  await guard(async () => {
    await api(`/games/${state.editingGame.id}`, { method: 'PUT', body: data });
    $('#gameDialog').close();
    toast('Jeu enregistré');
    await loadGames();
  });
}

async function scrapeCurrentGame(source, btn) {
  const buttons = $$('[data-scrape]');
  buttons.forEach((b) => (b.disabled = true));
  const label = btn.textContent;
  btn.textContent = 'Scraping…';
  await guard(async () => {
    const game = await api(`/games/${state.editingGame.id}/scrape`, { method: 'POST', body: { source } });
    state.editingGame = game;
    fillGameDialog(game, `-${Date.now()}`);
    toast(game.scrapeStatus === 'ok' ? 'Informations récupérées' : 'Jeu introuvable', game.scrapeStatus === 'ok' ? '' : 'error');
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
  if (!confirm(`Supprimer définitivement « ${g.fileName} » du serveur ?`)) return;
  await guard(async () => {
    await api(`/games/${g.id}`, { method: 'DELETE' });
    $('#gameDialog').close();
    toast('Jeu supprimé');
    await loadSystems();
  });
}

// ---------------------------------------------------------------------------
// Scraping d'un système et tâches
// ---------------------------------------------------------------------------

async function scrapeSystem() {
  const s = state.current;
  const all = confirm('Scraper uniquement les jeux pas encore scrapés ?\n\nOK = seulement les manquants, Annuler = tout re-scraper.');
  await guard(async () => {
    const res = await api(`/systems/${encodeURIComponent(s.id)}/scrape`, { method: 'POST', body: { onlyMissing: all } });
    if (!res.job) return toast(res.message);
    toast(`Scraping lancé (${res.job.total} jeu(x))`);
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
    list.innerHTML = '<p class="muted">Aucune tâche récente.</p>';
    return;
  }
  const statusText = { queued: 'En attente', running: 'En cours', done: 'Terminé', cancelled: 'Annulé', failed: 'Interrompu' };
  list.innerHTML = '';
  for (const j of jobs) {
    const el = document.createElement('div');
    el.className = 'job';
    el.innerHTML = `
      <div class="job-head"><strong>${escapeHtml(j.label)}</strong><span class="muted">${statusText[j.status]}</span></div>
      <progress max="${j.total}" value="${j.done}"></progress>
      <div class="muted">${j.done}/${j.total} — ${j.ok} trouvé(s), ${j.notFound} introuvable(s), ${j.failed} erreur(s)</div>
      ${j.lastError ? `<div class="err">${escapeHtml(j.lastError)}</div>` : ''}`;
    if (j.cancellable) {
      const btn = document.createElement('button');
      btn.className = 'btn small ghost';
      btn.textContent = 'Annuler';
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
    toast('Dossiers rescannés');
    await loadSystems();
  });

  for (const tab of $$('.tab')) {
    tab.onclick = () => {
      $$('.tab').forEach((t) => t.classList.toggle('active', t === tab));
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
  $('#scanSysBtn').onclick = () => guard(async () => {
    const r = await api(`/systems/${encodeURIComponent(state.current.id)}/scan`, { method: 'POST' });
    toast(`${r.added.length} ajouté(s), ${r.updated} modifié(s), ${r.removed} retiré(s)`);
    await loadSystems();
  });
  $('#scrapeSysBtn').onclick = scrapeSystem;

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
    $('#scraperState').textContent = state.status.scrapers.screenscraper
      ? 'ScreenScraper + Libretro'
      : 'Libretro seul (ScreenScraper non configuré)';
    await loadSystems();
    refreshJobs();
  });
}

bindEvents();
init();
