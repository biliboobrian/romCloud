import { test, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const dataDir = path.join(os.tmpdir(), `romcloud-emulator-mirror-test-${process.pid}`);
process.env.DATA_DIR = dataDir;
process.env.EMULATOR_REFRESH_HOURS = '0';

const mirror = await import('../src/emulator-mirror.js');
const { routeMethod } = await import('../src/api.js');

after(() => {
  try {
    fs.rmSync(dataDir, { recursive: true, force: true });
  } catch {
    // Base SQLite encore ouverte sous Windows.
  }
});

// Faux GitHub : liste des Releases de chaque dépôt et fichiers téléchargeables.
let releases = {};
let files = {};
let online = true;
let downloads = 0;

const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });

function fakeFetch(url) {
  if (!online) return Promise.reject(new Error('getaddrinfo ENOTFOUND'));
  const u = String(url);
  if (u.includes('/releases?')) {
    const repo = Object.keys(releases).find((r) => u.includes(`/repos/${r}/`));
    return Promise.resolve(repo ? json(releases[repo]) : new Response('{}', { status: 404 }));
  }
  if (files[u]) {
    downloads++;
    return Promise.resolve(new Response(files[u], { status: 200, headers: { 'content-length': String(files[u].length) } }));
  }
  return Promise.resolve(new Response('', { status: 404 }));
}

const release = (tag, date, names, extra = {}) => ({
  tag_name: tag, name: tag, published_at: date, draft: false, prerelease: false, ...extra,
  assets: names.map((n) => ({ name: n, size: `${tag}:${n}`.length, browser_download_url: `https://dl/${tag}/${n}` })),
});

function publish(repo, list) {
  releases[repo] = list;
  for (const r of list) for (const a of r.assets) files[a.browser_download_url] = Buffer.from(`${r.tag_name}:${a.name}`);
}

beforeEach(() => {
  releases = {};
  files = {};
  online = true;
  downloads = 0;
  mirror.setFetch(fakeFetch);
});

test('fichier récupéré par le serveur à la demande, puis servi depuis le miroir', async () => {
  publish('cemu-project/Cemu', [release('v2.6', '2026-10-01T00:00:00Z', ['cemu-2.6-windows-x64.zip', 'cemu-2.6-ubuntu.AppImage'])]);
  const { releases: list, offline } = await mirror.listReleases('cemu');
  assert.equal(offline, false);
  assert.equal(list[0].tag_name, 'v2.6');
  const asset = list[0].assets.find((a) => a.name === 'cemu-2.6-windows-x64.zip');
  assert.equal(asset.state, 'remote');
  assert.equal(asset.path, '/api/emulators/cemu/files/v2.6/cemu-2.6-windows-x64.zip');
  assert.equal(list[0].source, 'github.com');

  const first = await mirror.prepareFile('cemu', 'v2.6', 'cemu-2.6-windows-x64.zip');
  assert.equal(first.state, 'fetching');
  // Demande répétée pendant la récupération : une seule récupération.
  await mirror.prepareFile('cemu', 'v2.6', 'cemu-2.6-windows-x64.zip');
  const done = await mirror.waitFile('cemu', 'v2.6', 'cemu-2.6-windows-x64.zip');
  assert.equal(done.state, 'stored');
  assert.equal(downloads, 1);
  const stored = mirror.storedFile('cemu', 'v2.6', 'cemu-2.6-windows-x64.zip');
  assert.equal(fs.readFileSync(stored.path, 'utf8'), 'v2.6:cemu-2.6-windows-x64.zip');
  assert.equal(stored.path, path.join(dataDir, 'emulators', 'cemu', 'v2.6', 'cemu-2.6-windows-x64.zip'));
  assert.match(stored.row.sha256, /^[0-9a-f]{64}$/);
  // Déjà là : pas de nouveau téléchargement.
  assert.equal((await mirror.prepareFile('cemu', 'v2.6', 'cemu-2.6-windows-x64.zip')).state, 'stored');
  assert.equal(downloads, 1);
});

test('Internet injoignable ou version retirée : les versions gardées restent proposées', async () => {
  publish('shadps4-emu/shadPS4', [release('v.0.19.0', '2026-09-01T00:00:00Z', ['shadps4-win64-sdl-0.19.0.zip'])]);
  await mirror.prepareFile('shadps4', 'v.0.19.0', 'shadps4-win64-sdl-0.19.0.zip');
  await mirror.waitFile('shadps4', 'v.0.19.0', 'shadps4-win64-sdl-0.19.0.zip');

  // Version retirée du dépôt, une plus récente publiée : les deux listées, la retirée avec ses seuls fichiers gardés.
  publish('shadps4-emu/shadPS4', [release('v.0.20.0', '2026-10-01T00:00:00Z', ['shadps4-win64-sdl-0.20.0.zip'])]);
  mirror.setFetch(fakeFetch);
  const { releases: list } = await mirror.listReleases('shadps4');
  assert.deepEqual(list.map((r) => [r.tag_name, r.mirror_only]), [['v.0.20.0', false], ['v.0.19.0', true]]);
  assert.equal(list[1].assets[0].state, 'stored');

  // Plus de réseau, cache vidé : seules les versions gardées.
  online = false;
  mirror.setFetch(fakeFetch);
  const offline = await mirror.listReleases('shadps4');
  assert.equal(offline.offline, true);
  assert.deepEqual(offline.releases.map((r) => r.tag_name), ['v.0.19.0']);
  await assert.rejects(mirror.prepareFile('shadps4', 'v.0.20.0', 'shadps4-win64-sdl-0.20.0.zip'), /emulatorFileNotFound/);
});

test('préversions seulement pour les sources qui en publient, brouillons ignorés', async () => {
  publish('RPCS3/rpcs3-binaries-win', [
    release('build-2', '2026-10-02T00:00:00Z', ['rpcs3-v0.0.38-2-abc1234_win64_msvc.7z'], { prerelease: true }),
    release('build-1', '2026-10-01T00:00:00Z', ['rpcs3-v0.0.38-1-def5678_win64_msvc.7z']),
  ]);
  publish('zortos293/Lichii', [
    release('build-76', '2026-10-03T00:00:00Z', ['lichii-windows-x86_64.zip'], { draft: true }),
    release('build-75', '2026-10-02T00:00:00Z', ['lichii-windows-x86_64.zip'], { prerelease: true }),
  ]);
  assert.deepEqual((await mirror.listReleases('rpcs3')).releases.map((r) => r.tag_name), ['build-1']);
  assert.deepEqual((await mirror.listReleases('lichii')).releases.map((r) => r.tag_name), ['build-75']);
});

test('noms dangereux et fichiers absents de la Release refusés', async () => {
  publish('flyinghead/flycast', [release('v2.5', '2026-10-01T00:00:00Z', ['flycast-2.5.apk'])]);
  await assert.rejects(mirror.prepareFile('flycast', '..', 'flycast-2.5.apk'), /emulatorFileNotFound/);
  await assert.rejects(mirror.prepareFile('flycast', 'v2.5', '../../romcloud.db'), /emulatorFileNotFound/);
  await assert.rejects(mirror.prepareFile('flycast', 'v2.5', 'autre.apk'), /emulatorFileNotFound/);
  await assert.rejects(mirror.prepareFile('inconnu', 'v2.5', 'flycast-2.5.apk'), /unknownEmulator/);
  assert.throws(() => mirror.storedFile('flycast', 'v2.5', 'flycast-2.5.apk'), /emulatorFileNotFound/);
  assert.equal(mirror.safePart('.hidden'), false);
  assert.equal(mirror.safePart('cmod_v13.1'), true);
});

test('variante d\'un fichier, sans ses numéros de version', () => {
  const v = mirror.variantOf;
  assert.equal(v('Eden-Windows-v0.2.1-amd64-clang-pgo.zip'), v('Eden-Windows-v0.3.0-amd64-clang-pgo.zip'));
  assert.notEqual(v('Eden-Windows-v0.2.1-amd64-clang-pgo.zip'), v('Eden-Windows-v0.2.1-amd64-msvc-standard.zip'));
  assert.equal(v('KytyPS5-2026-10-12-df5b9b9-Windows-x64.zip'), v('KytyPS5-2026-11-01-0a1b2c3-Windows-x64.zip'));
  assert.equal(v('ARMSX2-1.2-sdk30.apk'), v('ARMSX2-1.3-sdk30.apk'));
  assert.notEqual(v('ARMSX2-1.2-sdk30.apk'), v('ARMSX2-1.2-sdk33.apk'));
  assert.equal(v('Fritchii-v4-Windows-x64.zip'), v('Fritchii-v4.1-Windows-x64.zip'));
  assert.notEqual(v('Fritchii-v4.1-Windows-x64.zip'), v('Fritchii-v4.1-debug-symbols-not-needed-to-play.zip'));
});

test('rafraîchissement : nouvelle version des variantes gardées récupérée d\'avance', async () => {
  publish('eden-emu/eden', [release('v0.2.1', '2026-09-01T00:00:00Z', ['Eden-Windows-v0.2.1-amd64-clang-pgo.zip', 'Eden-Windows-v0.2.1-amd64-msvc-standard.zip'])]);
  await mirror.prepareFile('eden', 'v0.2.1', 'Eden-Windows-v0.2.1-amd64-clang-pgo.zip');
  await mirror.waitFile('eden', 'v0.2.1', 'Eden-Windows-v0.2.1-amd64-clang-pgo.zip');
  publish('eden-emu/eden', [
    release('v0.3.0', '2026-10-01T00:00:00Z', ['Eden-Windows-v0.3.0-amd64-clang-pgo.zip', 'Eden-Windows-v0.3.0-amd64-msvc-standard.zip']),
    release('v0.2.1', '2026-09-01T00:00:00Z', ['Eden-Windows-v0.2.1-amd64-clang-pgo.zip']),
  ]);
  const started = await mirror.refreshMirror();
  assert.deepEqual(started.filter((s) => s.source === 'eden').map((s) => s.name), ['Eden-Windows-v0.3.0-amd64-clang-pgo.zip']);
  assert.equal((await mirror.waitFile('eden', 'v0.3.0', 'Eden-Windows-v0.3.0-amd64-clang-pgo.zip')).state, 'stored');
  // Les deux versions gardées ; la variante MSVC, jamais demandée, n'est pas récupérée.
  const eden = mirror.mirrorSummary().find((s) => s.id === 'eden');
  assert.deepEqual(eden.files.map((f) => f.tag).sort(), ['v0.2.1', 'v0.3.0']);
  assert.equal(eden.host, 'git.eden-emu.dev');

  mirror.deleteFile('eden', 'v0.2.1', 'Eden-Windows-v0.2.1-amd64-clang-pgo.zip');
  assert.equal(fs.existsSync(path.join(dataDir, 'emulators', 'eden', 'v0.2.1')), false);
});

test('récupération échouée : erreur signalée, nouvel essai possible', async () => {
  publish('Vita3K/Vita3K-Android', [release('v1', '2026-10-01T00:00:00Z', ['vita3k-android-1.apk'])]);
  delete files['https://dl/v1/vita3k-android-1.apk'];
  await mirror.prepareFile('vita3k-android', 'v1', 'vita3k-android-1.apk');
  const failed = await mirror.waitFile('vita3k-android', 'v1', 'vita3k-android-1.apk');
  assert.equal(failed.state, 'error');
  assert.match(failed.error, /HTTP 404/);
  assert.equal(fs.readdirSync(path.join(dataDir, 'emulators', 'vita3k-android', 'v1')).length, 0); // pas de .part laissé
  files['https://dl/v1/vita3k-android-1.apk'] = Buffer.from('apk');
  // Erreur gardée 30 s pour être vue par l'application (pas de nouvel essai à chaque interrogation), puis nouvel essai.
  assert.equal((await mirror.prepareFile('vita3k-android', 'v1', 'vita3k-android-1.apk')).state, 'error');
  mirror.clearFileErrors();
  assert.equal((await mirror.prepareFile('vita3k-android', 'v1', 'vita3k-android-1.apk')).state, 'fetching');
  assert.equal((await mirror.waitFile('vita3k-android', 'v1', 'vita3k-android-1.apk')).state, 'stored');
});

test('clé des applications : demande de récupération permise, suppression réservée', () => {
  assert.equal(routeMethod('POST', '/emulators/eden/files/v0.2.1/Eden-Windows-v0.2.1-amd64-clang-pgo.zip/prepare'), 'GET');
  assert.equal(routeMethod('POST', '/emulators/refresh'), 'POST');
  assert.equal(routeMethod('DELETE', '/emulators/eden/files/v0.2.1/x.zip'), 'DELETE');
});

test('sources demandées par les applications connues du serveur', (t) => {
  const repo = path.resolve(import.meta.dirname, '..', '..');
  const android = path.join(repo, 'android/core/src/main/java/com/romcloud/app/data/EmulatorReleases.kt');
  const managed = path.join(repo, 'desktop-tauri/src-tauri/src/managed.rs');
  const updates = path.join(repo, 'desktop-tauri/src-tauri/src/emulator_updates.rs');
  if (![android, managed, updates].every((f) => fs.existsSync(f))) return t.skip('sources des applications absentes (image Docker)');
  const ids = new Set();
  // Android : Source("Eden", "eden", …) ; Windows : cœurs des émulateurs gérés, Eden et forks Switch 2.
  for (const m of fs.readFileSync(android, 'utf8').matchAll(/to Source\("[^"]+", "([^"]+)"/g)) ids.add(m[1]);
  for (const m of fs.readFileSync(managed, 'utf8').matchAll(/^\s+core: "([^"]+)",/gm)) ids.add(m[1]);
  for (const m of fs.readFileSync(updates, 'utf8').matchAll(/Nx2Fork \{ id: "([^"]+)"/g)) ids.add(m[1]);
  ids.add('eden');
  assert.ok(ids.size >= 15, [...ids].join(', '));
  for (const id of ids) assert.ok(mirror.SOURCES[id], `source ${id} absente de emulator-mirror.js`);
});

test('dernières versions de tous les émulateurs récupérées d’un coup (fichiers par défaut, l’un après l’autre)', async () => {
  publish('eden-emu/eden', [release('v0.4.0', '2026-11-01T00:00:00Z', [
    'Eden-Windows-v0.4.0-amd64-clang-pgo.zip', 'Eden-Windows-v0.4.0-amd64-msvc-standard.zip',
    'Eden-Android-v0.4.0-standard.apk', 'Eden-Android-v0.4.0-legacy.apk', 'Eden-Android-v0.4.0-optimized.apk', 'Eden-Android-v0.4.0-chromeos.apk',
  ])]);
  publish('ARMSX2/ARMSX3', [release('1.6.7', '2026-11-08T00:00:00Z', [
    'ARMSX3-1.6.7-a11-armv8.2-sdk30.apk', 'ARMSX3-1.6.7-a15-armv8.2-sdk35.apk', 'ARMSX3-1.6.7-legacy-armv8.1-sdk30.apk',
  ])]);
  // Dernière version sans APK : la précédente qui en a un.
  publish('utkarshdalal/GameNative', [
    release('v1.5.0', '2026-11-09T00:00:00Z', ['notes.txt']),
    release('v1.4.0', '2026-11-05T00:00:00Z', ['gamenative-v1.4.0.apk', 'gamenative-v1.4.0-legacy-xr.apk']),
  ]);
  const { running, files } = await mirror.fetchAllLatest();
  assert.equal(running, false);
  const names = files.map((f) => `${f.source}/${f.tag}/${f.name}`).sort();
  assert.deepEqual(names, [
    'armsx3/1.6.7/ARMSX3-1.6.7-a11-armv8.2-sdk30.apk', 'armsx3/1.6.7/ARMSX3-1.6.7-a15-armv8.2-sdk35.apk',
    'eden/v0.4.0/Eden-Android-v0.4.0-legacy.apk', 'eden/v0.4.0/Eden-Android-v0.4.0-optimized.apk',
    'eden/v0.4.0/Eden-Android-v0.4.0-standard.apk', 'eden/v0.4.0/Eden-Windows-v0.4.0-amd64-clang-pgo.zip',
    'gamenative/v1.4.0/gamenative-v1.4.0.apk',
  ]);
  // Déjà en cours : rien de relancé.
  assert.equal((await mirror.fetchAllLatest()).running, true);
  assert.ok(mirror.mirrorSummary().some((s) => s.queued.length));
  await mirror.latestRunDone();
  for (const f of files) assert.equal(mirror.fileState(f.source, f.tag, f.name).state, 'stored', f.name);
  assert.equal(downloads, files.length);
  // Tout est là : plus rien à récupérer.
  assert.deepEqual((await mirror.fetchAllLatest()).files, []);
  await mirror.latestRunDone();
});
