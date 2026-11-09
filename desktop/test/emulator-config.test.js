// Réglages des émulateurs (renderer/emulator-config/emulator-config.json, commun à Windows et Android) :
// chaque champ désigne un fichier, une section et une clé, ses valeurs sont uniques et ses libellés
// existent en anglais et en français.
const test = require('node:test');
const assert = require('node:assert');
const path = require('node:path');

const CONFIG = require(path.join(__dirname, '..', 'src', 'renderer', 'emulator-config', 'emulator-config.json'));
const PLATFORMS = ['windows', 'android'];

const labelled = (l) => typeof l === 'string' ? l.length > 0 : Boolean(l?.en && l?.fr);
const platformsOk = (x) => x.platforms === undefined || (x.platforms.length > 0 && x.platforms.every((p) => PLATFORMS.includes(p)));

test('champs complets et valeurs uniques', () => {
  // Fichiers de chaque schéma (emulator_config.rs : target_for ; Android : android.files).
  const files = { eden: ['qt-config.ini'], dolphin: ['Dolphin.ini', 'GFX.ini'] };
  assert.deepStrictEqual(Object.keys(CONFIG.schemas).sort(), Object.keys(files).sort());
  for (const [id, schema] of Object.entries(CONFIG.schemas)) {
    assert.ok(['eden', 'spaced'].includes(schema.style), id);
    for (const p of PLATFORMS) assert.ok(labelled(schema.notes[p]), `${id} ${p}`);
    const seen = new Set();
    for (const group of schema.groups) {
      assert.ok(labelled(group.title), id);
      for (const f of group.fields) {
        const where = `${id} ${f.file} [${f.section}] ${f.key}`;
        assert.ok(files[id].includes(f.file) && CONFIG.android.files[f.file], where);
        assert.ok(f.section && f.key && labelled(f.label) && platformsOk(f), where);
        assert.ok(!seen.has(`${f.file}/${f.section}/${f.key}`), `${where} en double`);
        seen.add(`${f.file}/${f.section}/${f.key}`);
        if (f.type === 'select') {
          const values = f.options.map((o) => o.value);
          assert.strictEqual(new Set(values).size, values.length, where);
          assert.ok(values.every((v) => typeof v === 'string' && v !== ''), where);
          assert.ok(f.options.every((o) => labelled(o.label) && platformsOk(o)), where);
          // Au moins une valeur sur chaque plateforme du champ.
          for (const p of f.platforms || PLATFORMS) assert.ok(f.options.some((o) => !o.platforms || o.platforms.includes(p)), `${where} ${p}`);
        } else if (f.type === 'bool') {
          assert.ok(f.on && f.off && f.on !== f.off, where);
        } else {
          assert.strictEqual(f.type, 'number', where);
          assert.ok(f.min <= f.max, where);
        }
      }
    }
  }
});

test('émulateurs de Windows et paquets Android', () => {
  // Mêmes émulateurs que emulator_config.rs (supported).
  assert.deepStrictEqual(CONFIG.windows, { eden: 'eden', lichii: 'eden', orchid: 'eden', fritchii: 'eden', dolphin: 'dolphin' });
  for (const p of CONFIG.android.packages) assert.ok(CONFIG.schemas[p.schema] && /^[a-z][\w.]+$/.test(p.prefix), p.prefix);
});

test('valeurs des énumérations d’Eden (settings_enums.h)', () => {
  const field = (key) => CONFIG.schemas.eden.groups.flatMap((g) => g.fields).find((f) => f.key === key);
  const values = (key) => field(key).options.map((o) => Number(o.value)).sort((a, b) => a - b);
  assert.deepStrictEqual(values('resolution_setup'), [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
  assert.deepStrictEqual(values('language_index'), [...Array(20).keys()]);
  assert.deepStrictEqual(values('region_index'), [0, 1, 2, 3, 4, 5, 6]);
  assert.ok(field('resolution_setup').options.find((o) => o.value === '3').label.en.startsWith('1×'));
  assert.strictEqual(field('language_index').options.find((o) => o.value === '2').label, 'Français');
  assert.strictEqual(field('use_docked_mode').options.find((o) => o.value === '1').label.en, 'Docked (TV)');
  // Android : Vulkan seulement.
  assert.deepStrictEqual(field('backend').options.filter((o) => !o.platforms).map((o) => o.value), ['1']);
});
