// Options par défaut des émulateurs intégrés, choisies dans l'administration :
//  • par système (systems.core_options), puis par jeu (games.core_options), qui les remplacent ;
//  • les choix faits par le joueur pendant la partie (gardés sur son appareil) l'emportent sur les deux.
//
// Chaque niveau est un objet { clé: valeur } :
//  • cœurs libretro (moteur intégré de Windows, LibretroDroid sur Android) : variables du cœur
//    (« mupen64plus-43screensize », « beetle_psx_hw_internal_resolution »…). Elles portent le nom du
//    cœur : chaque application n'applique que celles que déclare le cœur lancé ;
//  • jeux PC (RomCloud PC) : réglages de la fiche du jeu (« wineVersion », « dxvk », « driver »…).
//
// Catalogue : les applications envoient les options déclarées par chaque cœur qu'elles lancent
// (libellés, valeurs possibles), pour que l'administration propose des listes plutôt que des clés à taper.
import { db } from './db.js';
import { HttpError } from './http-error.js';

const MAX_OPTIONS = 500;
const MAX_KEY = 200;
const MAX_VALUE = 500;

/** Options { clé: valeur } validées ; valeur vide ou null : option retirée (défaut du niveau au-dessus). */
export function normalizeCoreOptions(input) {
  if (input === null || input === undefined || input === '') return {};
  if (typeof input !== 'object' || Array.isArray(input)) throw new HttpError(400, 'errors.invalidCoreOptions');
  const result = {};
  for (const [rawKey, rawValue] of Object.entries(input)) {
    const key = String(rawKey).trim();
    if (rawValue === null || rawValue === undefined || String(rawValue) === '') continue;
    if (!key || key.length > MAX_KEY || /[\s=]/.test(key)) throw new HttpError(400, 'errors.invalidCoreOptions');
    const value = String(rawValue);
    if (value.length > MAX_VALUE || /[\r\n]/.test(value)) throw new HttpError(400, 'errors.invalidCoreOptions');
    result[key] = value;
  }
  if (Object.keys(result).length > MAX_OPTIONS) throw new HttpError(400, 'errors.invalidCoreOptions');
  return result;
}

/** Options gardées en base (JSON) ; illisibles : aucune. */
export function parseCoreOptions(text) {
  try {
    const value = JSON.parse(text || '{}');
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch {
    return {};
  }
}

const str = (value, max) => String(value ?? '').slice(0, max);

/**
 * Options déclarées par un cœur, envoyées par une application : [{ key, label, values: [{ value,
 * label }], default, category }]. Valeurs données en simples chaînes acceptées. Entrées invalides ignorées.
 */
function normalizeCatalog(options) {
  if (!Array.isArray(options)) throw new HttpError(400, 'errors.invalidCoreOptions');
  const seen = new Set();
  const result = [];
  for (const o of options.slice(0, MAX_OPTIONS)) {
    const key = str(o?.key, MAX_KEY).trim();
    if (!key || seen.has(key) || /[\s=]/.test(key)) continue;
    seen.add(key);
    const values = (Array.isArray(o.values) ? o.values : [])
      .slice(0, 200)
      .map((v) => (typeof v === 'object' && v !== null ? { value: str(v.value, MAX_VALUE), label: str(v.label ?? v.value, MAX_VALUE) } : { value: str(v, MAX_VALUE), label: str(v, MAX_VALUE) }))
      .filter((v) => v.value !== '');
    result.push({
      key,
      label: str(o.label || key, 300),
      values,
      default: str(o.default ?? values[0]?.value ?? '', MAX_VALUE),
      ...(o.category ? { category: str(o.category, 100) } : {}),
      ...(o.info ? { info: str(o.info, 1000) } : {}),
    });
  }
  return result;
}

/**
 * Catalogue d'un cœur envoyé par une application : remplace celui de la même plateforme
 * (« android », « androidtv », « windows ») ; [system] est ajouté aux systèmes où le cœur a servi.
 */
export function reportCatalog({ core, system, options } = {}, client = {}) {
  const name = str(core, 100).trim();
  if (!name || !/^[\w.+-]+$/.test(name)) throw new HttpError(400, 'errors.invalidCoreOptions');
  const platform = str(client.platform || 'unknown', 30);
  const list = normalizeCatalog(options);
  const current = db.prepare('SELECT systems FROM core_option_catalog WHERE core = ? AND platform = ?').get(name, platform);
  const systems = new Set(current ? JSON.parse(current.systems || '[]') : []);
  const systemId = str(system, 100).trim();
  if (systemId) systems.add(systemId);
  db.prepare(
    `INSERT INTO core_option_catalog (core, platform, systems, options, app_version, updated_at)
     VALUES (?, ?, ?, ?, ?, datetime('now'))
     ON CONFLICT (core, platform) DO UPDATE SET systems = excluded.systems, options = excluded.options,
       app_version = excluded.app_version, updated_at = excluded.updated_at`,
  ).run(name, platform, JSON.stringify([...systems].sort()), JSON.stringify(list), str(client.appVersion, 40) || null);
}

/**
 * Catalogue des cœurs, ceux qui ont servi pour [systemId] seulement s'il est donné : [{ core,
 * platform, systems, options, appVersion, updatedAt }], par cœur puis plateforme.
 */
export function listCatalog(systemId) {
  return db
    .prepare('SELECT * FROM core_option_catalog ORDER BY core, platform')
    .all()
    .map((row) => ({
      core: row.core,
      platform: row.platform,
      systems: JSON.parse(row.systems || '[]'),
      options: JSON.parse(row.options || '[]'),
      appVersion: row.app_version,
      updatedAt: row.updated_at,
    }))
    .filter((entry) => !systemId || entry.systems.includes(systemId));
}
