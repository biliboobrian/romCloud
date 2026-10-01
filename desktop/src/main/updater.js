// Mise à jour de l'application au démarrage : la dernière Release GitHub de RomCloud est comparée
// à la version installée. Version installée (NSIS) : le nouvel installeur est téléchargé, vérifié
// (SHA256 donné par GitHub) puis lancé en mode silencieux, qui relance RomCloud une fois installé.
// Version portable : le nouvel exécutable remplace l'actuel, puis il est relancé.
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { Readable, Transform } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const { AppError } = require('./api');

const LATEST_RELEASE = 'https://api.github.com/repos/biliboobrian/romCloud/releases/latest';

/** « v1.12.0 » -> [1, 12, 0] ; null pour une version de développement ou mal formée. */
function parseVersion(version) {
  const parts = String(version || '').trim().replace(/^v/, '').split('.');
  if (!parts.length || parts.some((p) => !/^\d+$/.test(p))) return null;
  return parts.map(Number);
}

/** `candidate` est-elle plus récente que `installed` ? */
function isNewer(candidate, installed) {
  const a = parseVersion(candidate);
  const b = parseVersion(installed);
  if (!a || !b) return false;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i] || 0;
    const y = b[i] || 0;
    if (x !== y) return x > y;
  }
  return false;
}

/** Fichier de la Release pour cette installation (voir .github/workflows/desktop.yml). */
function pickAsset(release, portable) {
  const version = String(release.tag_name || '').replace(/^v/, '');
  const name = portable ? `RomCloud-Windows-${version}-portable.exe` : `RomCloud-Windows-${version}-x64.exe`;
  const asset = (release.assets || []).find((a) => a.name === name);
  if (!asset) return null;
  const digest = /^sha256:([0-9a-f]{64})$/i.exec(asset.digest || '');
  return { version, name, url: asset.browser_download_url, size: asset.size, sha256: digest ? digest[1].toLowerCase() : null };
}

/** Exécutable de la version portable (variable posée par son lanceur), sinon null. */
const portableExe = () => process.env.PORTABLE_EXECUTABLE_FILE || null;

function createUpdater({ app }) {
  let available = null;

  /** Ancien exécutable portable laissé par une mise à jour précédente. */
  function cleanup() {
    const exe = portableExe();
    if (exe) fs.rmSync(`${exe}.old`, { force: true });
  }

  /** Mise à jour proposée, ou null (version de développement, à jour, ou hors ligne). */
  async function check() {
    if (!app.isPackaged || !parseVersion(app.getVersion())) return null;
    try {
      const res = await fetch(LATEST_RELEASE, {
        headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'RomCloud' },
        signal: AbortSignal.timeout(10000),
      });
      if (!res.ok) return null;
      const release = await res.json();
      const asset = pickAsset(release, Boolean(portableExe()));
      if (!asset || !isNewer(asset.version, app.getVersion())) return null;
      available = asset;
      return { version: asset.version, installed: app.getVersion(), size: asset.size, portable: Boolean(portableExe()) };
    } catch {
      return null; // hors ligne : nouvelle vérification au prochain lancement
    }
  }

  /** Télécharge et vérifie le fichier, puis l'installe et quitte RomCloud. */
  async function install(onProgress) {
    const asset = available;
    if (!asset) throw new AppError('errors.updateNone');
    const exe = portableExe();
    // Version portable : à côté de l'exécutable (même disque, pour le remplacer par renommage).
    const target = exe ? `${exe}.update` : path.join(app.getPath('temp'), 'romcloud-update', asset.name);
    await download(asset, target, onProgress);
    if (exe) {
      fs.rmSync(`${exe}.old`, { force: true });
      fs.renameSync(exe, `${exe}.old`); // possible même pendant son exécution
      fs.renameSync(target, exe);
      spawn(exe, [], { detached: true, stdio: 'ignore' }).unref();
    } else {
      // Installation silencieuse au même endroit, puis relance de RomCloud.
      spawn(target, ['/S', '--updated', '--force-run'], { detached: true, stdio: 'ignore' }).unref();
    }
    setTimeout(() => app.quit(), 300);
  }

  return { check, install, cleanup };
}

async function download(asset, target, onProgress) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const res = await fetch(asset.url, { headers: { 'User-Agent': 'RomCloud' } });
  if (!res.ok) throw new AppError('errors.http', { status: res.status });
  const hash = crypto.createHash('sha256');
  let bytes = 0;
  let last = 0;
  const progress = new Transform({
    transform(chunk, _enc, cb) {
      bytes += chunk.length;
      hash.update(chunk);
      const now = Date.now();
      if (now - last > 250) {
        last = now;
        onProgress(bytes, asset.size);
      }
      cb(null, chunk);
    },
  });
  try {
    await pipeline(Readable.fromWeb(res.body), progress, fs.createWriteStream(target));
    if (bytes !== asset.size) throw new AppError('errors.wrongSize', { got: bytes, expected: asset.size });
    if (asset.sha256 && hash.digest('hex') !== asset.sha256) throw new AppError('errors.updateDigest');
  } catch (err) {
    fs.rmSync(target, { force: true });
    if (err instanceof AppError) throw err;
    throw new AppError('errors.download', { detail: err.message });
  }
  onProgress(bytes, asset.size);
}

module.exports = { createUpdater, parseVersion, isNewer, pickAsset };
