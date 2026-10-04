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

test('boutons proposés : ceux de la console, sous leur nom', () => {
  assert.equal(keyboard.consoleButtons('gba', 'vbam'), 'up,down,left,right,b=B,a=A,l=L,r=R,start=Start,select=Select');
  // Mega Drive : A B C sur Y B A, X Y Z sur L X R, pas de Select.
  assert.equal(keyboard.consoleButtons('genesis', 'genesis_plus_gx'), 'up,down,left,right,y=A,b=B,a=C,l=X,x=Y,r=Z,start=Start');
  assert.ok(keyboard.consoleButtons('psx', 'pcsx_rearmed', 'en').includes('b=Cross'));
  assert.ok(keyboard.consoleButtons('psx', 'pcsx_rearmed', 'fr').includes('b=Croix'));
  // Système inconnu : d'après le cœur, sinon tous les boutons.
  assert.ok(keyboard.consoleButtons('perso', 'snes9x').includes('y=Y'));
  assert.ok(keyboard.consoleButtons('perso', 'inconnu').includes('r3=R3'));
  assert.ok(!keyboard.consoleButtons('gx4000', 'cap32').includes('start'));
});
