// Page « Utilisateurs » de l'administration : profils des joueurs (création, mot de passe,
// désactivation, suppression), détail d'un profil (sessions par appareil, connexions, temps de jeu,
// sauvegardes en ligne, erreurs), journal de toutes les connexions et des erreurs des applications.
import { formatSize, getLanguage, t } from './i18n.js';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

let helpers;
let tab = 'users';
let detailId = null;

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
  const box = $('#usersContent');
  box.innerHTML = `<p class="muted">${helpers.escapeHtml(t('apks.loading'))}</p>`;
  await helpers.guard(async () => {
    if (detailId != null) return renderDetail(box, await helpers.api(`/users/${detailId}`));
    if (tab === 'users') return renderList(box, await helpers.api('/users'));
    if (tab === 'logins') return renderLogins(box, await helpers.api('/logs/logins'));
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
    <h4>${e(t('users.sessionsTitle'))}</h4>
    ${table([t('users.device'), t('users.method'), t('users.since'), t('users.lastSeen'), t('users.ip'), ''], d.sessions.map((s) => `<tr>
      <td>${e(device(s))}</td><td>${e(t(`users.method.${s.method}`))}</td><td>${e(formatDate(s.createdAt))}</td>
      <td>${e(formatDate(s.lastSeenAt))}</td><td><code>${e(s.ip || '–')}</code></td>
      <td><button class="btn small danger" data-revoke="${s.id}">${e(t('users.revoke'))}</button></td></tr>`), t('users.noSessions'))}
    <h4>${e(t('users.playtimeTitle'))}</h4>
    ${table([t('users.game'), t('users.playtime'), t('users.sessionsCount'), t('users.lastPlayed')], d.playtime.map((p) => `<tr>
      <td><strong>${e(p.title || `#${p.gameId}`)}</strong><br><span class="muted">${e(p.system || '')}</span></td>
      <td>${e(formatDuration(p.seconds))}</td><td>${p.sessions}</td><td>${e(formatDate(p.lastPlayedAt))}</td></tr>`), t('users.noPlaytime'))}
    <h4>${e(t('users.savesTitle'))}</h4>
    ${table([t('users.game'), t('users.saveKind'), t('users.size'), t('users.savedAt'), t('users.device')], d.saves.map((s) => `<tr>
      <td><strong>${e(s.title || `#${s.gameId}`)}</strong><br><span class="muted">${e([s.system, s.core].filter(Boolean).join(' · '))}</span></td>
      <td>${e(t(`users.kind.${s.kind}`))}</td><td>${e(formatSize(s.size))}</td><td>${e(formatDate(s.savedAt))}</td>
      <td>${e(device(s))}</td></tr>`), t('users.noSaves'))}
    <h4>${e(t('users.loginsTitle'))}</h4>
    ${table([t('users.date'), t('users.eventCol'), t('users.device'), t('users.ip')], loginRows(d.logins, false), t('users.noLogins'))}
    <h4>${e(t('users.errorsTitle'))}</h4>
    ${table([t('users.date'), t('users.device'), t('users.message')], errorRows(d.errors, false), t('users.noErrors'))}`;

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
