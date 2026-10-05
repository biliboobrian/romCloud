const { test } = require('node:test');
const assert = require('node:assert/strict');
const { pickTarget, nearest } = require('../src/renderer/gamepad');

const box = (id, left, top, width = 100, height = 100) => ({ id, rect: { left, top, width, height } });

// Deux rangées de trois cartes, et un bouton au-dessus à droite.
const grid = [
  box('a1', 0, 200), box('a2', 120, 200), box('a3', 240, 200),
  box('b1', 0, 320), box('b2', 120, 320), box('b3', 240, 320),
  box('top', 300, 0, 80, 30),
];
const from = (id) => grid.find((c) => c.id === id).rect;
const others = (id) => grid.filter((c) => c.id !== id);

test('reste dans la rangée ou la colonne', () => {
  assert.equal(pickTarget(from('a1'), others('a1'), 'right').id, 'a2');
  assert.equal(pickTarget(from('a2'), others('a2'), 'down').id, 'b2');
  assert.equal(pickTarget(from('b3'), others('b3'), 'left').id, 'b2');
  assert.equal(pickTarget(from('b1'), others('b1'), 'up').id, 'a1');
});

test('rien dans cette direction', () => {
  assert.equal(pickTarget(from('a3'), others('a3'), 'right'), null);
  assert.equal(pickTarget(from('b2'), others('b2'), 'down'), null);
});

test('rangée du haut -> bouton au-dessus', () => {
  assert.equal(pickTarget(from('a3'), others('a3'), 'up').id, 'top');
  // Hors du cône : seulement sans restriction (repli pour haut / bas).
  assert.equal(pickTarget(from('a1'), others('a1'), 'up'), null);
  assert.equal(pickTarget(from('a1'), others('a1'), 'up', false).id, 'top');
});

test('focus perdu : élément le plus proche', () => {
  assert.equal(nearest({ left: 130, top: 330, width: 10, height: 10 }, grid).id, 'b2');
});
