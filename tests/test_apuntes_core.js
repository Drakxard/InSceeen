const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('../modules/apuntes/core.js');

test('parses ordered conceptual headers without imposing a card maximum', () => {
  const blocks = Array.from({ length: 12 }, (_, index) => ({ orden: index + 1, cabecera: `Concepto ${index + 1}` }));
  assert.equal(core.parseBlockOutput(JSON.stringify({ bloques: blocks })).length, 12);
  assert.deepEqual(core.parseBlockOutput('```json\n{"bloques":[]}\n```'), []);
});

test('rejects physical multiline cards, answers, duplicates and broken ordering', () => {
  assert.throws(() => core.parseBlockOutput('{"bloques":[{"orden":1,"cabecera":"Uno\\nDos"}]}'), /sola línea/);
  assert.throws(() => core.parseBlockOutput('{"bloques":[{"orden":1,"cabecera":"Uno","respuesta":"Explicación"}]}'), /únicamente/);
  assert.throws(() => core.parseBlockOutput('{"bloques":[{"orden":2,"cabecera":"Uno"}]}'), /consecutivo/);
  assert.throws(() => core.parseBlockOutput('{"bloques":[{"orden":1,"cabecera":"Uno"},{"orden":2,"cabecera":"uno"}]}'), /repetidas/);
});

test('builds deterministic cards and invalidates cache only when the source changes', () => {
  const sourceHash = 'a'.repeat(64);
  const cards = core.buildCards('0002.jpg', sourceHash, [{ order: 1, header: 'Procesos celulares' }]);
  const page = { sourceHash, tarjetas: cards };
  assert.equal(cards[0].respuesta, null);
  assert.match(cards[0].id, /^0002\.jpg:a{12}:1:/);
  assert.equal(core.validSavedPage(page, sourceHash), true);
  assert.equal(core.validSavedPage(page, 'b'.repeat(64)), false);
});
