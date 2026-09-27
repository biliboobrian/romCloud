import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-i18n-test-${process.pid}`);

const { I18nError, localize, requestLanguage, t, token } = await import('../src/i18n.js');

const fakeReq = (header) => ({ get: (name) => (name.toLowerCase() === 'accept-language' ? header : undefined) });

test('traduction avec variables et repli sur l’anglais', () => {
  assert.equal(t('fr', 'errors.systemNotFound', { id: 'snes' }), 'Système inconnu : snes');
  assert.equal(t('en', 'errors.systemNotFound', { id: 'snes' }), 'Unknown system: snes');
  assert.equal(t('de', 'errors.systemNotFound', { id: 'snes' }), 'Unknown system: snes');
  assert.equal(t('fr', 'cle.inexistante'), 'cle.inexistante');
});

test('jetons stockés puis traduits dans la réponse', () => {
  const stored = token('jobs.newGames', { system: 'SNES', count: 3 });
  const body = { jobs: [{ label: stored, other: 'texte normal', n: 1 }], error: new I18nError('errors.noGames').message };
  assert.deepEqual(localize(body, 'fr'), { jobs: [{ label: 'SNES : 3 nouveau(x) jeu(x)', other: 'texte normal', n: 1 }], error: 'Aucun jeu indiqué' });
  assert.equal(localize(body, 'en').jobs[0].label, 'SNES: 3 new game(s)');
});

test('langue du client (Accept-Language)', () => {
  assert.equal(requestLanguage(fakeReq('fr-FR,fr;q=0.9,en;q=0.8')), 'fr');
  assert.equal(requestLanguage(fakeReq('de-DE,en;q=0.5')), 'en');
  assert.equal(requestLanguage(fakeReq('')), 'en'); // DEFAULT_LANGUAGE par défaut
});

/** Clés d'un bloc « <langue>: { … } » d'un fichier de dictionnaire. */
function keysOf(file, lang) {
  const src = fs.readFileSync(new URL(file, import.meta.url), 'utf8');
  const block = src.split(`${lang}: {`)[1].split('\n  },')[0];
  return [...block.matchAll(/^\s+'([\w.]+)':/gm)].map((m) => m[1]).sort();
}

test('dictionnaires du serveur et de l’interface web complets dans les deux langues', () => {
  for (const file of ['../src/i18n.js', '../public/i18n.js']) {
    const en = keysOf(file, 'en');
    const fr = keysOf(file, 'fr');
    assert.ok(en.length > 30, `${file} : dictionnaire vide ?`);
    assert.deepEqual(fr, en, `${file} : clés différentes entre fr et en`);
  }
});
