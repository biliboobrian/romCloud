// Catalogue des pilotes graphiques et composants (DXVK, VKD3D-Proton, Box64, WOWBox64, FEXCore) à
// télécharger pour les jeux PC (RomCloud PC) : lit les publications GitHub de leurs dépôts et écrit
// core/src/main/java/com/romcloud/app/launch/PcCatalog.kt.
//
// Pilotes (zip AdrenoTools) : identifiant = « name » de leur meta.json, lu par requêtes partielles
// (Range) sans télécharger le zip. Composants (.wcp de Winlator, tar zstd) : téléchargés pour lire
// leur profile.json (type, versionName, versionCode). Résultats gardés dans pc-catalog-cache.json :
// seuls les nouveaux fichiers sont lus.
//
// Usage : node android/scripts/pc-catalog.mjs   (Node 22.15 ou plus, pour zstd)
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const cacheFile = path.join(here, 'pc-catalog-cache.json');
const output = path.join(here, '../core/src/main/java/com/romcloud/app/launch/PcCatalog.kt');
const cache = fs.existsSync(cacheFile) ? JSON.parse(fs.readFileSync(cacheFile, 'utf8')) : {};

const DRIVER_REPOS = ['tiagosouzacandido/AdrenoToolsTurnipDrivers', 'K11MCH1/AdrenoToolsDrivers'];
const WCP_REPO = 'Arihany/WinlatorWCPHub';
/** Publications du dépôt des .wcp gardées (FEXCore-unixlib, Proton : pour d'autres versions de Proton). */
const WCP_RELEASES = [
  'DXVK', 'DXVK-ARM64EC', 'DXVK-GPLASYNC', 'DXVK-GPLASYNC-ARM64EC', 'DXVK-SAREK-ASYNC', 'DXVK-SAREK-ASYNC-ARM64EC',
  'VKD3D-PROTON', 'VKD3D-PROTON-ARM64EC', 'BOX64-BIONIC', 'WOWBOX64', 'WOWBOX64-NIGHTLY', 'FEXCore',
];

const headers = { 'User-Agent': 'romcloud-pc-catalog', ...(process.env.GITHUB_TOKEN ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}) };

async function releases(repo) {
  const all = [];
  for (let page = 1; ; page++) {
    const res = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=100&page=${page}`, { headers });
    if (!res.ok) throw new Error(`${repo} : HTTP ${res.status}`);
    const list = await res.json();
    all.push(...list);
    if (list.length < 100) return all;
  }
}

async function range(url, start, end) {
  const res = await fetch(url, { headers: { ...headers, Range: `bytes=${start}-${end}` } });
  if (res.status !== 206) throw new Error(`${url} : HTTP ${res.status} (Range)`);
  return Buffer.from(await res.arrayBuffer());
}

/** « name » du meta.json d'un zip AdrenoTools, lu par requêtes partielles. */
async function driverName(url, size) {
  const tailStart = Math.max(0, size - 65_557);
  const tail = await range(url, tailStart, size - 1);
  const eocd = tail.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) throw new Error(`${url} : zip illisible`);
  const cdSize = tail.readUInt32LE(eocd + 12);
  const cdOffset = tail.readUInt32LE(eocd + 16);
  const cd = cdOffset >= tailStart ? tail.subarray(cdOffset - tailStart, cdOffset - tailStart + cdSize) : await range(url, cdOffset, cdOffset + cdSize - 1);
  for (let p = 0; p + 46 <= cd.length && cd.readUInt32LE(p) === 0x02014b50;) {
    const method = cd.readUInt16LE(p + 10);
    const compressed = cd.readUInt32LE(p + 20);
    const n = cd.readUInt16LE(p + 28), e = cd.readUInt16LE(p + 30), c = cd.readUInt16LE(p + 32);
    const local = cd.readUInt32LE(p + 42);
    const name = cd.subarray(p + 46, p + 46 + n).toString();
    p += 46 + n + e + c;
    if (!/(^|\/)meta\.json$/.test(name)) continue;
    const head = await range(url, local, local + 29);
    const dataStart = local + 30 + head.readUInt16LE(26) + head.readUInt16LE(28);
    const data = await range(url, dataStart, dataStart + compressed - 1);
    const json = JSON.parse((method === 8 ? zlib.inflateRawSync(data) : data).toString().replace(/^﻿/, ''));
    return String(json.name);
  }
  throw new Error(`${url} : pas de meta.json`);
}

/** profile.json d'un .wcp (tar zstd ou xz). */
async function wcpProfile(url) {
  const res = await fetch(url, { headers });
  if (!res.ok) throw new Error(`${url} : HTTP ${res.status}`);
  const packed = Buffer.from(await res.arrayBuffer());
  const tar = packed.readUInt32LE(0) === 0xfd2fb528 ? zlib.zstdDecompressSync(packed) : null;
  if (!tar) throw new Error(`${url} : format inconnu`);
  for (let o = 0; o + 512 <= tar.length;) {
    const name = tar.subarray(o, o + 100).toString().replace(/\0.*$/s, '');
    if (!name) break;
    const size = parseInt(tar.subarray(o + 124, o + 136).toString().replace(/\0.*$/s, '').trim(), 8) || 0;
    if (/(^|\/)profile\.json$/.test(name)) {
      const p = JSON.parse(tar.subarray(o + 512, o + 512 + size).toString());
      return { type: p.type, versionName: p.versionName, versionCode: p.versionCode };
    }
    o += 512 + Math.ceil(size / 512) * 512;
  }
  throw new Error(`${url} : pas de profile.json`);
}

async function cached(key, read) {
  if (!cache[key]) {
    cache[key] = await read();
    fs.writeFileSync(cacheFile, `${JSON.stringify(cache, null, 1)}\n`);
  }
  return cache[key];
}

const MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];

/** Libellé d'un pilote d'après le nom de son fichier et sa date de publication. */
function driverLabel(repo, file, date) {
  const base = file.replace(/\.zip$/i, '');
  const tiago = /^turnip_(\d{8})_([\d.]+)-devel_vk[\d.]+_([0-9a-f]+)(?:_(.+))?$/.exec(base);
  if (tiago) return `Turnip ${tiago[2]} (${tiago[3]}${tiago[4] ? `, ${tiago[4]}` : ''})`;
  const qualcomm = /^(?:qualcomm_|adreno-?|vulkan-|v)?(\d{3}(?:\.\d+)?)(?:[._-]adpkg)?$/i.exec(base);
  if (qualcomm) return `Qualcomm ${qualcomm[1]}`;
  if (/^8Elite2-/.test(base)) return `Qualcomm ${base.replace(/^8Elite2-/, '')} (8 Elite 2)`;
  return base.replace(/[._]adpkg/i, '').replace(/[_]+/g, ' ').replace(/^turnip/i, 'Turnip').replace(/\s+/g, ' ').trim();
}

const kotlin = (s) => `"${String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$')}"`;

async function main() {
  const drivers = [];
  const seen = new Map();
  for (const repo of DRIVER_REPOS) {
    for (const release of await releases(repo)) {
      for (const asset of release.assets.filter((a) => /\.zip$/i.test(a.name))) {
        const url = asset.browser_download_url;
        let id;
        try {
          id = await cached(url, () => driverName(url, asset.size));
        } catch (err) {
          console.warn(`ignoré : ${err.message}`);
          continue;
        }
        // Même nom de pilote dans deux fichiers : un seul dossier d'AdrenoTools, le premier gardé.
        if (seen.has(id)) {
          console.warn(`ignoré (même nom que ${seen.get(id)}) : ${url}`);
          continue;
        }
        seen.set(id, url);
        drivers.push({ id, label: driverLabel(repo, asset.name, release.published_at), url, date: release.published_at.slice(0, 10), repo });
      }
    }
  }
  drivers.sort((a, b) => b.date.localeCompare(a.date) || a.label.localeCompare(b.label));

  const contents = [];
  for (const release of (await releases(WCP_REPO)).filter((r) => WCP_RELEASES.includes(r.tag_name))) {
    for (const asset of release.assets.filter((a) => /\.wcp$/i.test(a.name))) {
      const url = asset.browser_download_url;
      let profile;
      try {
        profile = await cached(url, () => wcpProfile(url));
      } catch (err) {
        console.warn(`ignoré : ${err.message}`);
        continue;
      }
      contents.push({ type: profile.type.toLowerCase(), version: `${profile.versionName}-${profile.versionCode}`, name: profile.versionName, url, release: release.tag_name });
    }
  }
  const versionKey = (v) => v.replace(/^(\d+(?:\.\d+)*).*$/, '$1').split('.').map((n) => n.padStart(4, '0')).join('.');
  contents.sort((a, b) => a.type.localeCompare(b.type) || versionKey(b.name).localeCompare(versionKey(a.name)) || a.name.localeCompare(b.name));

  const lines = [
    'package com.romcloud.app.launch',
    '',
    '// Fichier généré par android/scripts/pc-catalog.mjs : ne pas modifier à la main.',
    '',
    '/**',
    ' * Pilotes graphiques et composants de Winlator (DXVK, VKD3D-Proton, Box64, WOWBox64, FEXCore) publiés',
    ' * sur GitHub, téléchargés par RomCloud PC au premier usage : versions stables, de développement et',
    ' * expérimentales, de la plus récente à la plus ancienne.',
    ' */',
    'object PcCatalog {',
    '',
    `    /** Pilotes AdrenoTools (${DRIVER_REPOS.join(', ')}) : identifiant (meta.json), libellé, paquet. */`,
    '    val DRIVERS: List<PcSettings.Driver> = listOf(',
    ...drivers.map((d) => `        PcSettings.Driver(${kotlin(d.id)}, ${kotlin(d.label)}, ${kotlin(d.url)}),`),
    '    )',
    '',
    `    /** Composants .wcp (${WCP_REPO}) : type (dxvk, vkd3d, box64, wowbox64, fexcore), version (« versionName-versionCode »), paquet. */`,
    '    val CONTENTS: List<PcSettings.Content> = listOf(',
    ...contents.map((c) => `        PcSettings.Content(${kotlin(c.type)}, ${kotlin(c.version)}, ${kotlin(c.name)}, ${kotlin(c.url)}),`),
    '    )',
    '}',
    '',
  ];
  fs.writeFileSync(output, lines.join('\n'));
  console.log(`${drivers.length} pilotes, ${contents.length} composants -> ${path.relative(process.cwd(), output)}`);
}

await main();
