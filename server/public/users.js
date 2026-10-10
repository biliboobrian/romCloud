// Page « Utilisateurs » de l'administration : profils des joueurs (création, mot de passe,
// désactivation, suppression), détail d'un profil (sessions par appareil, connexions, temps de jeu,
// sauvegardes en ligne, erreurs), journal de toutes les connexions et des erreurs des applications,
// téléchargements (ROM, BIOS, APK) : qui télécharge quoi, statistiques par profil, appareil, jeu et jour.
import { formatSize, getLanguage, t } from './i18n.js';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

let helpers;
let tab = 'users';
let detailId = null;
/** Filtres de l'onglet Téléchargements : période (jours, 0 = tout), type, profil (« anonymous »), appareil. */
const dlFilters = { days: 30, kind: '', userId: '', device: '' };

/** Durée lisible : « 3 h 25 min », « 12 min », « 40 s ». */
export function formatDuration(seconds) {
  const s = Math.round(seconds || 0);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h) return t('users.hours', { h, m: String(m).padStart(2, '0') });
  if (m) return t('users.minutes', { m });
  return t('users.seconds', { s });
}

/** Date du serveur (UTC, « 2026-10-05 14:03:00 » ou ISO) en heure locale. */
function formatDate(value) {
  if (!value) return '–';
  const date = new Date(/Z|T/.test(value) ? value : `${value.replace(' ', 'T')}Z`);
  return date.toLocaleString(getLanguage(), { dateStyle: 'short', timeStyle: 'short' });
}

/** Taille d'origine, et place sur le disque si le fichier est compressé : « 4,4 Mo (1,3 Mo compressé) ». */
const sizeLabel = (r) => (r.compressed && r.storedSize != null
  ? t('users.sizeCompressed', { size: formatSize(r.size), stored: formatSize(r.storedSize) })
  : formatSize(r.size));

const platformLabel = (p) => (p ? t(`users.platform.${p}`) : '–');
const device = (r) => [r.device, platformLabel(r.platform), r.appVersion && `v${r.appVersion}`].filter((x) => x && x !== '–').join(' · ') || '–';

function table(headers, rows, empty) {
  const e = helpers.escapeHtml;
  if (!rows.length) return `<p class="muted">${e(empty)}</p>`;
  return `<div class="table-wrap"><table class="table"><thead><tr>${headers.map((h) => `<th>${e(h)}</th>`).join('')}</tr></thead>
    <tbody>${rows.join('')}</tbody></table></div>`;
}

export function initUsers(h) {
  helpers = h;
  $('#usersBtn').onclick = () => openUsers();
  for (const el of $$('#usersDialog [data-users-tab]')) {
    el.onclick = () => {
      tab = el.dataset.usersTab;
      detailId = null;
      render();
    };
  }
  $('#userCreateBtn').onclick = createUser;
}

export function openUsers() {
  if (!$('#usersDialog').open) $('#usersDialog').showModal();
  detailId = null;
  render();
}

async function render() {
  for (const el of $$('#usersDialog [data-users-tab]')) el.classList.toggle('active', el.dataset.usersTab === tab && !detailId);
  $('#userCreateBtn').classList.toggle('hidden', tab !== 'users' || detailId != null);
  $('#errorsClearBtn').classList.toggle('hidden', tab !== 'errors' || detailId != null);
  $('#downloadsClearBtn').classList.toggle('hidden', tab !== 'downloads' || detailId != null);
  $('#diskRefreshBtn').classList.toggle('hidden', tab !== 'disk' || detailId != null);
  const box = $('#usersContent');
  box.innerHTML = `<p class="muted">${helpers.escapeHtml(t('apks.loading'))}</p>`;
  await helpers.guard(async () => {
    if (detailId != null) return renderDetail(box, await helpers.api(`/users/${detailId}`));
    if (tab === 'users') return renderList(box, await helpers.api('/users'));
    if (tab === 'logins') return renderLogins(box, await helpers.api('/logs/logins'));
    if (tab === 'disk') return renderDisk(box, await helpers.api(`/logs/disk${diskRefresh ? '?refresh=1' : ''}`));
    if (tab === 'downloads') {
      const query = new URLSearchParams(Object.entries(dlFilters).filter(([, v]) => v !== '' && v != null)).toString();
      const [stats, rows] = await Promise.all([helpers.api(`/logs/downloads/stats?${query}`), helpers.api(`/logs/downloads?${query}&limit=200`)]);
      return renderDownloads(box, stats, rows);
    }
    return renderErrors(box, await helpers.api('/logs/errors'));
  });
}

function renderList(box, users) {
  const e = helpers.escapeHtml;
  box.innerHTML = table(
    [t('users.name'), t('users.lastSeen'), t('users.sessions'), t('users.playtime'), t('users.saves'), t('users.errors'), ''],
    users.map((u) => `<tr data-user="${u.id}" class="clickable${u.disabled ? ' disabled' : ''}">
      <td><strong>${e(u.username)}</strong>${u.disabled ? ` <span class="tag mismatch">${e(t('users.disabled'))}</span>` : ''}
        <br><span class="muted">${e(t('users.created', { date: formatDate(u.createdAt) }))}</span></td>
      <td>${e(formatDate(u.lastSeenAt || u.lastLoginAt))}</td>
      <td>${u.sessions}</td>
      <td>${e(formatDuration(u.playSeconds))}<br><span class="muted">${e(t('users.gamesPlayed', { n: u.gamesPlayed }))}</span></td>
      <td>${e(formatSize(u.savesSize))}</td>
      <td>${u.errors ? `<span class="tag mismatch">${u.errors}</span>` : '0'}</td>
      <td><button class="btn small">${e(t('users.open'))}</button></td>
    </tr>`),
    t('users.none'),
  );
  for (const row of $$('[data-user]', box)) {
    row.onclick = () => {
      detailId = Number(row.dataset.user);
      render();
    };
  }
}

function loginRows(events, withUser) {
  const e = helpers.escapeHtml;
  return events.map((ev) => `<tr>
    <td>${e(formatDate(ev.at))}</td>
    ${withUser ? `<td>${e(ev.username || '–')}</td>` : ''}
    <td><span class="tag ${ev.success ? 'ok' : 'mismatch'}">${e(t(`users.event.${ev.event}`))}</span>
      ${ev.success ? '' : `<br><span class="muted">${e(t(`users.reason.${ev.reason}`))}</span>`}
      ${ev.event === 'pair' && ev.reason ? `<br><span class="muted">${e(ev.reason)}</span>` : ''}</td>
    <td>${e(device(ev))}</td>
    <td><code>${e(ev.ip || '–')}</code></td>
  </tr>`);
}

function renderLogins(box, events) {
  box.innerHTML = table([t('users.date'), t('users.name'), t('users.eventCol'), t('users.device'), t('users.ip')], loginRows(events, true), t('users.noLogins'));
}

function errorRows(errors, withUser) {
  const e = helpers.escapeHtml;
  return errors.map((err) => `<tr>
    <td>${e(formatDate(err.at))}</td>
    ${withUser ? `<td>${e(err.username || t('users.anonymous'))}</td>` : ''}
    <td>${e(device(err))}${err.context ? `<br><span class="muted">${e(err.context)}</span>` : ''}</td>
    <td class="error-cell"><strong>${e(err.message)}</strong>
      ${err.details ? `<details><summary>${e(t('users.details'))}</summary><pre>${e(err.details)}</pre></details>` : ''}</td>
  </tr>`);
}

// ---------------------------------------------------------------------------
// Espace disque : arborescence repliable (ROMs par système, sauvegardes par profil…).

let diskRefresh = false;
/** Nœuds ouverts (gardés en recalculant). */
const diskOpen = new Set(['root']);

/** Libellé d'un nœud : traduit pour les dossiers connus (« roms », « saves »…), sinon tel quel. */
function diskLabel(n) {
  const key = `disk.node.${n.label}`;
  const text = t(key);
  return text === key ? n.label : text;
}

function diskNode(n, parentSize, depth) {
  const e = helpers.escapeHtml;
  const share = parentSize > 0 ? Math.round((n.size / parentSize) * 1000) / 10 : 100;
  const line = `<span class="disk-name">${e(diskLabel(n))}</span>
    <span class="disk-bar"><span style="width:${Math.min(100, share)}%"></span></span>
    <span class="disk-size">${e(formatSize(n.size))}</span>
    <span class="disk-meta muted">${e(t('disk.files', { n: n.files }))}${depth > 0 ? ` · ${share} %` : ''}</span>`;
  if (!n.children?.length) return `<div class="disk-row leaf" title="${e(n.path || '')}">${line}</div>`;
  return `<details class="disk-row" data-disk="${e(n.id)}"${diskOpen.has(n.id) ? ' open' : ''}>
    <summary title="${e(n.path || '')}">${line}</summary>
    <div class="disk-children">${n.children.map((c) => diskNode(c, n.size, depth + 1)).join('')}</div>
  </details>`;
}

function renderDisk(box, usage) {
  const e = helpers.escapeHtml;
  diskRefresh = false;
  const diskLine = (d, i) => `<div><strong>${e(formatSize(d.free))}</strong><span class="muted">${e(t(i === 0 ? 'disk.freeData' : 'disk.freeRoms', { total: formatSize(d.total) }))}</span></div>`;
  box.innerHTML = `
    <div class="dl-tiles">
      <div><strong>${e(formatSize(usage.tree.size))}</strong><span class="muted">${e(t('disk.used', { n: usage.tree.files }))}</span></div>
      ${usage.disks.map(diskLine).join('')}
    </div>
    <p class="muted">${e(t('disk.computedAt', { date: formatDate(usage.computedAt), dir: usage.dataDir }))}</p>
    <div class="disk-tree">${usage.tree.children.map((c) => diskNode(c, usage.tree.size, 1)).join('')}</div>`;
  for (const el of $$('details[data-disk]', box)) {
    el.addEventListener('toggle', () => (el.open ? diskOpen.add(el.dataset.disk) : diskOpen.delete(el.dataset.disk)));
  }
  $('#diskRefreshBtn').onclick = () => {
    diskRefresh = true;
    render();
  };
}

const kindLabel = (k) => t(`downloads.kind.${k}`);
const userLabel = (r) => (r.userId == null ? t('users.anonymous') : r.username || `#${r.userId}`);

/** Onglet Téléchargements : filtres, totaux, statistiques et journal. */
function renderDownloads(box, stats, rows) {
  const e = helpers.escapeHtml;
  const counts = (r) => `<td>${r.downloads}</td><td>${r.completed}</td><td>${r.requests - r.downloads}</td><td>${e(formatSize(r.bytes))}</td>`;
  const countHeaders = [t('downloads.count'), t('downloads.completed'), t('downloads.resumes'), t('downloads.volume')];
  const select = (name, options, value) => `<select data-dl="${name}">${options.map(([v, label]) => `<option value="${e(v)}"${String(value) === String(v) ? ' selected' : ''}>${e(label)}</option>`).join('')}</select>`;
  const active = [
    dlFilters.userId !== '' && `${t('users.name')} : ${dlFilters.userId === 'anonymous' ? t('users.anonymous') : stats.byUser.find((u) => String(u.userId) === dlFilters.userId)?.username || dlFilters.userId}`,
    dlFilters.device && `${t('users.device')} : ${dlFilters.device}`,
  ].filter(Boolean);
  const total = stats.totals;
  box.innerHTML = `
    <div class="line dl-filters">
      ${select('days', [[7, t('downloads.days', { n: 7 })], [30, t('downloads.days', { n: 30 })], [90, t('downloads.days', { n: 90 })], [0, t('downloads.allTime')]], dlFilters.days)}
      ${select('kind', [['', t('downloads.allKinds')], ...['game', 'bios', 'apk'].map((k) => [k, kindLabel(k)])], dlFilters.kind)}
      ${active.map((a) => `<span class="tag">${e(a)}</span>`).join('')}
      ${active.length ? `<button class="btn small" data-dl-reset>${e(t('downloads.resetFilters'))}</button>` : ''}
    </div>
    <div class="dl-tiles">
      <div><strong>${total.downloads}</strong><span class="muted">${e(t('downloads.count'))}</span></div>
      <div><strong>${total.completed}</strong><span class="muted">${e(t('downloads.completed'))}</span></div>
      <div><strong>${e(formatSize(total.bytes))}</strong><span class="muted">${e(t('downloads.volume'))}</span></div>
      <div><strong>${stats.byDevice.length}</strong><span class="muted">${e(t('downloads.devices'))}</span></div>
    </div>
    <section class="user-section"><h4>${e(t('downloads.byKind'))}</h4>
      ${table([t('downloads.kindCol'), ...countHeaders], stats.byKind.map((r) => `<tr><td>${e(kindLabel(r.kind))}</td>${counts(r)}</tr>`), t('downloads.empty'))}</section>
    <section class="user-section"><h4>${e(t('downloads.byUser'))}</h4>
      ${table([t('users.name'), ...countHeaders, t('downloads.last')], stats.byUser.map((r) => `<tr>
        <td><a href="#" data-dl-user="${r.userId == null ? 'anonymous' : r.userId}">${e(userLabel(r))}</a></td>${counts(r)}<td>${e(formatDate(r.lastAt))}</td></tr>`), t('downloads.empty'))}</section>
    <section class="user-section"><h4>${e(t('downloads.byDevice'))}</h4>
      ${table([t('users.device'), ...countHeaders, t('downloads.last')], stats.byDevice.map((r) => `<tr>
        <td><a href="#" data-dl-device="${e(r.device ?? '')}">${e(r.device || t('downloads.unknownDevice'))}</a><br><span class="muted">${e(platformLabel(r.platform))}</span></td>${counts(r)}<td>${e(formatDate(r.lastAt))}</td></tr>`), t('downloads.empty'))}</section>
    <section class="user-section"><h4>${e(t('downloads.topItems'))}</h4>
      ${table([t('downloads.item'), ...countHeaders], stats.topItems.map((r) => `<tr>
        <td><strong>${e(r.label || `#${r.itemId}`)}</strong><br><span class="muted">${e([kindLabel(r.kind), r.systemId].filter(Boolean).join(' · '))}</span></td>${counts(r)}</tr>`), t('downloads.empty'))}</section>
    <section class="user-section"><h4>${e(t('downloads.byDay'))}</h4>
      ${table([t('users.date'), ...countHeaders], stats.byDay.map((r) => `<tr><td>${e(new Date(`${r.day}T00:00:00`).toLocaleDateString(getLanguage()))}</td>${counts(r)}</tr>`), t('downloads.empty'))}</section>
    <section class="user-section"><h4>${e(t('downloads.log'))}</h4>
      ${table([t('users.date'), t('users.name'), t('users.device'), t('downloads.item'), t('downloads.volume'), t('users.ip')], rows.map((r) => `<tr>
        <td>${e(formatDate(r.at))}</td>
        <td>${e(userLabel(r))}</td>
        <td>${e(device(r))}</td>
        <td><strong>${e(r.title || r.label || `#${r.itemId}`)}</strong><br><span class="muted">${e([kindLabel(r.kind), r.systemId, r.resumed && t('downloads.resumed'), !r.complete && t('downloads.incomplete')].filter(Boolean).join(' · '))}</span></td>
        <td>${e(formatSize(r.bytes))}${r.fileSize ? `<br><span class="muted">/ ${e(formatSize(r.fileSize))}</span>` : ''}</td>
        <td>${e(r.ip || '–')}</td></tr>`), t('downloads.empty'))}</section>`;
  for (const el of $$('[data-dl]', box)) {
    el.onchange = () => {
      dlFilters[el.dataset.dl] = el.value;
      render();
    };
  }
  for (const el of $$('[data-dl-user]', box)) {
    el.onclick = (ev) => {
      ev.preventDefault();
      dlFilters.userId = el.dataset.dlUser;
      render();
    };
  }
  for (const el of $$('[data-dl-device]', box)) {
    el.onclick = (ev) => {
      ev.preventDefault();
      dlFilters.device = el.dataset.dlDevice;
      render();
    };
  }
  const reset = $('[data-dl-reset]', box);
  if (reset) reset.onclick = () => {
    dlFilters.userId = '';
    dlFilters.device = '';
    render();
  };
  $('#downloadsClearBtn').onclick = () => helpers.guard(async () => {
    if (!confirm(t('downloads.confirmClear'))) return;
    await helpers.api('/logs/downloads', { method: 'DELETE' });
    render();
  });
}

function renderErrors(box, errors) {
  box.innerHTML = table([t('users.date'), t('users.name'), t('users.device'), t('users.message')], errorRows(errors, true), t('users.noErrors'));
  $('#errorsClearBtn').onclick = () => helpers.guard(async () => {
    if (!confirm(t('users.confirmClearErrors'))) return;
    await helpers.api('/logs/errors', { method: 'DELETE' });
    render();
  });
}

function renderDetail(box, d) {
  const e = helpers.escapeHtml;
  const u = d.user;
  const total = d.playtime.reduce((n, p) => n + p.seconds, 0);
  box.innerHTML = `
    <div class="user-head">
      <button class="btn small ghost" data-back>← ${e(t('users.back'))}</button>
      <h4>${e(u.username)} ${u.disabled ? `<span class="tag mismatch">${e(t('users.disabled'))}</span>` : ''}</h4>
      <span class="muted">${e(t('users.created', { date: formatDate(u.createdAt) }))} · ${e(t('users.totalPlaytime', { time: formatDuration(total) }))}</span>
      <div class="actions">
        <button class="btn small" data-rename>${e(t('users.rename'))}</button>
        <button class="btn small" data-password>${e(t('users.setPassword'))}</button>
        <button class="btn small" data-toggle>${e(t(u.disabled ? 'users.enable' : 'users.disable'))}</button>
        <button class="btn small danger" data-delete>${e(t('users.delete'))}</button>
      </div>
    </div>
    <div class="user-grid">
      <section class="user-section"><h4>${e(t('users.sessionsTitle'))}</h4>
    ${table([t('users.device'), t('users.method'), t('users.since'), t('users.lastSeen'), t('users.ip'), ''], d.sessions.map((s) => `<tr>
      <td>${e(device(s))}</td><td>${e(t(`users.method.${s.method}`))}</td><td>${e(formatDate(s.createdAt))}</td>
      <td>${e(formatDate(s.lastSeenAt))}</td><td><code>${e(s.ip || '–')}</code></td>
      <td><button class="btn small danger" data-revoke="${s.id}">${e(t('users.revoke'))}</button></td></tr>`), t('users.noSessions'))}</section>
      <section class="user-section"><h4>${e(t('users.playtimeTitle'))}</h4>
    ${table([t('users.game'), t('users.playtime'), t('users.sessionsCount'), t('users.lastPlayed')], d.playtime.map((p) => `<tr>
      <td><strong>${e(p.title || `#${p.gameId}`)}</strong><br><span class="muted">${e(p.system || '')}</span></td>
      <td>${e(formatDuration(p.seconds))}</td><td>${p.sessions}</td><td>${e(formatDate(p.lastPlayedAt))}</td></tr>`), t('users.noPlaytime'))}</section>
      <section class="user-section"><h4>${e(t('users.savesTitle'))}</h4>
    ${table([t('users.game'), t('users.saveKind'), t('users.size'), t('users.savedAt'), t('users.device')], d.saves.map((s) => `<tr>
      <td><strong>${e(s.title || `#${s.gameId}`)}</strong><br><span class="muted">${e([s.system, s.core].filter(Boolean).join(' · '))}</span></td>
      <td>${e(t(`users.kind.${s.kind}`))}</td><td>${e(sizeLabel(s))}</td><td>${e(formatDate(s.savedAt))}</td>
      <td>${e(device(s))}</td></tr>`), t('users.noSaves'))}</section>
      <section class="user-section"><h4>${e(t('users.statesTitle'))}</h4>
    ${table([t('users.game'), t('users.size'), t('users.savedAt'), t('users.device')], (d.states || []).map((s) => `<tr>
      <td><strong>${e(s.title || `#${s.gameId}`)}</strong>${s.pinned ? ` <span class="muted">${e(t('users.pinned'))}</span>` : ''}<br><span class="muted">${e([s.system, s.core].filter(Boolean).join(' · '))}</span></td>
      <td>${e(sizeLabel(s))}</td><td>${e(formatDate(s.createdAt))}</td>
      <td>${e(device(s))}</td></tr>`), t('users.noStates'))}</section>
      <section class="user-section"><h4>${e(t('users.loginsTitle'))}</h4>
    ${table([t('users.date'), t('users.eventCol'), t('users.device'), t('users.ip')], loginRows(d.logins, false), t('users.noLogins'))}</section>
      <section class="user-section"><h4>${e(t('users.errorsTitle'))}</h4>
    ${table([t('users.date'), t('users.device'), t('users.message')], errorRows(d.errors, false), t('users.noErrors'))}</section>
    </div>`;

  $('[data-back]', box).onclick = () => {
    detailId = null;
    render();
  };
  const update = (body, message) => helpers.guard(async () => {
    await helpers.api(`/users/${u.id}`, { method: 'PUT', body });
    if (message) helpers.toast(message);
    render();
  });
  $('[data-rename]', box).onclick = () => {
    const username = prompt(t('users.renamePrompt'), u.username);
    if (username && username.trim() !== u.username) update({ username: username.trim() });
  };
  $('[data-password]', box).onclick = () => {
    const password = prompt(t('users.passwordPrompt'));
    if (password) update({ password }, t('users.passwordChanged'));
  };
  $('[data-toggle]', box).onclick = () => update({ disabled: !u.disabled });
  $('[data-delete]', box).onclick = () => helpers.guard(async () => {
    if (!confirm(t('users.confirmDelete', { name: u.username }))) return;
    await helpers.api(`/users/${u.id}`, { method: 'DELETE' });
    detailId = null;
    render();
  });
  for (const btn of $$('[data-revoke]', box)) {
    btn.onclick = () => helpers.guard(async () => {
      await helpers.api(`/users/${u.id}/sessions/${btn.dataset.revoke}`, { method: 'DELETE' });
      render();
    });
  }
}

function createUser() {
  const username = prompt(t('users.namePrompt'));
  if (!username) return;
  const password = prompt(t('users.passwordPrompt'));
  if (!password) return;
  helpers.guard(async () => {
    await helpers.api('/users', { method: 'POST', body: { username: username.trim(), password } });
    helpers.toast(t('users.createdToast', { name: username.trim() }));
    render();
  });
}
