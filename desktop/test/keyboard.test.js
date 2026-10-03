const test = require('node:test');
const assert = require('node:assert/strict');
const keyboard = require('../src/main/keyboard');

test('touches par défaut : même disposition que le moteur sans réglage', () => {
  const spec = keyboard.playerKeys({});
  assert.ok(spec.split(',').includes('4=82')); // haut -> flèche haut
  assert.ok(spec.split(',').includes('0=29')); // B -> Z
  assert.ok(spec.split(',').includes('3=40')); // Start -> Entrée
  assert.ok(!spec.split(',').some((e) => e.startsWith('14='))); // L3 sans touche
});

test('touches choisies : remplacées, retirées, invalides ignorées', () => {
  const keys = keyboard.resolve({ b: 'Space', a: '', up: 'Escape', down: 'Nope' });
  assert.equal(keys.b, 'Space');
  assert.equal(keys.a, '');
  assert.equal(keys.up, 'ArrowUp'); // réservée au moteur
  assert.equal(keys.down, 'ArrowDown'); // inconnue
  const spec = keyboard.playerKeys({ b: 'Space', a: '' }).split(',');
  assert.ok(spec.includes('0=44'));
  assert.ok(!spec.some((e) => e.startsWith('8=')));
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
