// Réglages des émulateurs (renderer/emulator-config.js) : chaque champ désigne un fichier, une section
// et une clé, ses valeurs sont uniques et ses libellés existent en anglais et en français.
const test = require('node:test');
const assert = require('node:assert');
const path = require('node:path');

global.window = {};
require(path.join(__dirname, '..', 'src', 'renderer', 'emulator-config.js'));
const CONFIG = global.window.EMULATOR_CONFIG;

const labelled = (l) => typeof l === 'string' ? l.length > 0 : Boolean(l?.en && l?.fr);

test('champs complets et valeurs uniques', () => {
  // Mêmes fichiers que emulator_config.rs (target_for).
  const files = { eden: ['qt-config.ini'], lichii: ['qt-config.ini'], orchid: ['qt-config.ini'], fritchii: ['qt-config.ini'], dolphin: ['Dolphin.ini', 'GFX.ini'] };
  assert.deepStrictEqual(Object.keys(CONFIG).sort(), Object.keys(files).sort());
  for (const [id, schema] of Object.entries(CONFIG)) {
    assert.ok(labelled(schema.note), id);
    const seen = new Set();
    for (const group of schema.groups) {
      assert.ok(labelled(group.title), id);
      for (const f of group.fields) {
        const where = `${id} ${f.file} [${f.section}] ${f.key}`;
        assert.ok(files[id].includes(f.file), where);
        assert.ok(f.section && f.key && labelled(f.label), where);
        assert.ok(!seen.has(`${f.file}/${f.section}/${f.key}`), `${where} en double`);
        seen.add(`${f.file}/${f.section}/${f.key}`);
        if (f.type === 'select') {
          const values = f.options.map((o) => o.value);
          assert.strictEqual(new Set(values).size, values.length, where);
          assert.ok(values.every((v) => v !== ''), where);
          assert.ok(f.options.every((o) => labelled(o.label)), where);
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

test('valeurs des énumérations d’Eden (settings_enums.h)', () => {
  const field = (key) => CONFIG.eden.groups.flatMap((g) => g.fields).find((f) => f.key === key);
  const values = (key) => field(key).options.map((o) => Number(o.value)).sort((a, b) => a - b);
  assert.deepStrictEqual(values('resolution_setup'), [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
  assert.deepStrictEqual(values('language_index'), [...Array(20).keys()]);
  assert.deepStrictEqual(values('region_index'), [0, 1, 2, 3, 4, 5, 6]);
  assert.strictEqual(field('resolution_setup').options.find((o) => o.value === '3').label.en.startsWith('1×'), true);
  assert.strictEqual(field('language_index').options.find((o) => o.value === '2').label, 'Français');
  assert.strictEqual(field('use_docked_mode').options.find((o) => o.value === '1').label.en, 'Docked (TV)');
});
