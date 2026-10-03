const test = require('node:test');
const assert = require('node:assert/strict');
const keyboard = require('../src/main/keyboard');

test('touches par défaut : même disposition que le moteur sans fichier', () => {
  const lines = keyboard.formatFile({}).trim().split('\n');
  assert.equal(lines.length, keyboard.BUTTONS.length);
  assert.ok(lines.includes('up=82')); // flèche haut
  assert.ok(lines.includes('b=29')); // Z
  assert.ok(lines.includes('start=40')); // Entrée
  assert.ok(lines.includes('l3=')); // aucune touche
});

test('touches choisies : remplacées, retirées, invalides ignorées', () => {
  const keys = keyboard.resolve({ b: 'Space', a: '', up: 'Escape', down: 'Nope' });
  assert.equal(keys.b, 'Space');
  assert.equal(keys.a, '');
  assert.equal(keys.up, 'ArrowUp'); // réservée au moteur
  assert.equal(keys.down, 'ArrowDown'); // inconnue
});

test('fichier des touches : relu tel qu’écrit par le moteur', () => {
  const keys = keyboard.parseFile('b=44\r\na=\r\nup=41\r\nr3=4\r\nautre=5\r\n');
  assert.equal(keys.b, 'Space');
  assert.equal(keys.a, '');
  assert.equal(keys.up, 'ArrowUp'); // Échap (41) : réservée
  assert.equal(keys.r3, 'KeyA');
  assert.equal(keys.start, 'Enter'); // absent : par défaut
  assert.deepEqual(keyboard.parseFile(keyboard.formatFile(keys)), keys);
});

test('codes des touches -> scancodes SDL', () => {
  assert.equal(keyboard.SCANCODES.KeyA, 4);
  assert.equal(keyboard.SCANCODES.KeyZ, 29);
  assert.equal(keyboard.SCANCODES.Digit0, 39);
  assert.equal(keyboard.SCANCODES.F12, 69);
  assert.equal(keyboard.SCANCODES.Numpad0, 98);
  assert.equal(keyboard.SCANCODES.ShiftRight, 229);
  assert.ok(!keyboard.assignable('F1'));
});
