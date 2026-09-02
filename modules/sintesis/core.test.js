'use strict';
const assert = require('node:assert/strict');
const test = require('node:test');
const core = require('./core.js');
const markdown = require('./markdown.js');

function tree() {
  let state = core.emptyState();
  state = core.addNode(state, { id: 'prog3', parentId: null, name: 'Prog3', x: .2, y: .3 });
  state = core.addNode(state, { id: 'tap', parentId: 'prog3', name: 'TAP', x: .4, y: .5 });
  state = core.addNode(state, { id: 'listas', parentId: 'tap', name: 'Listas Enlazadas', x: .6, y: .7 });
  return state;
}

test('crea nodos persistibles y conserva posiciones relativas', () => {
  const state = tree();
  assert.deepEqual(core.children(state, 'prog3').map(node => node.id), ['tap']);
  assert.equal(state.nodes.listas.x, .6);
  assert.equal(state.nodes.listas.scale, 1);
  assert.equal(core.normalizeState(JSON.parse(JSON.stringify(state))).nodes.listas.name, 'Listas Enlazadas');
});

test('mueve y aplica la escala global a elementos existentes y posteriores', () => {
  let state = tree();
  state = core.moveNode(state, 'tap', .91, -.4);
  assert.deepEqual([state.nodes.tap.x, state.nodes.tap.y], [.91, 0]);
  state = core.moveNode(state, 'tap', .91, 2.4);
  assert.equal(state.nodes.tap.y, 2.4);
  state = core.scaleNode(state, 'tap', 9);
  assert.equal(state.nodes.tap.scale, core.MAX_SCALE);
  assert.equal(state.nodes.prog3.scale, core.MAX_SCALE);
  assert.equal(state.nodes.listas.scale, core.MAX_SCALE);
  assert.equal(state.defaultScale, core.MAX_SCALE);
  state = core.addNode(state, { id: 'nuevo', parentId: null, name: 'Nuevo', x: .5, y: .5 });
  assert.equal(state.nodes.nuevo.scale, core.MAX_SCALE);
});

test('migra estados anteriores con escala normal', () => {
  const state = core.normalizeState({ version: 1, nodes: { viejo: { id: 'viejo', name: 'Viejo', x: .2, y: .3 } } });
  assert.equal(state.version, core.VERSION);
  assert.equal(state.defaultScale, 1);
  assert.equal(state.nodes.viejo.scale, 1);
});

test('produce la ruta completa y actualiza nombre y contenido', () => {
  let state = tree();
  assert.equal(core.path(state, 'listas').join(', '), 'Prog3, TAP, Listas Enlazadas');
  state = core.renameNode(state, 'listas', '  Listas   Dobles ');
  state = core.setContent(state, 'listas', '# Explicación');
  assert.equal(state.nodes.listas.name, 'Listas Dobles');
  assert.equal(state.nodes.listas.content, '# Explicación');
});

test('elimina una rama completa sin afectar hermanos', () => {
  let state = tree();
  state = core.addNode(state, { id: 'otro', parentId: null, name: 'Otro', x: .5, y: .5 });
  const result = core.deleteBranch(state, 'prog3');
  assert.deepEqual(result.removed, ['prog3', 'tap', 'listas']);
  assert.deepEqual(Object.keys(result.state.nodes), ['otro']);
});

test('recupera datos inválidos sin ciclos ni referencias rotas', () => {
  const state = core.normalizeState({ nodes: {
    a: { id: 'a', parentId: 'b', name: 'A', x: 4, y: -1 },
    b: { id: 'b', parentId: 'a', name: 'B' },
    bad: { id: 'bad', parentId: 'missing', name: '' }
  } });
  assert.equal(state.nodes.a.parentId, null);
  assert.equal(state.nodes.a.x, 1);
  assert.equal(state.nodes.a.y, 0);
  assert.equal(state.nodes.bad, undefined);
});

test('importa temas y subtemas desde el JSON esperado de NotebookLM', () => {
  const outline = core.parseOutline(JSON.stringify({ temas: [
    { nombre: 'TAP', subtemas: ['Listas Enlazadas', { nombre: 'Árboles', subtemas: ['AVL'] }] },
    { tema: 'Grafos', subtemas: [] }
  ] }));
  let sequence = 0;
  const result = core.importOutline(core.emptyState(), null, outline, () => `import_${++sequence}`);
  assert.equal(result.imported, 5);
  assert.deepEqual(core.children(result.state, null).map(node => node.name), ['TAP', 'Grafos']);
  assert.deepEqual(core.path(result.state, 'import_4'), ['TAP', 'Árboles', 'AVL']);
});

test('rechaza JSON vacío, mal formado o excesivo', () => {
  assert.throws(() => core.parseOutline('{'), /invalid_json/);
  assert.throws(() => core.parseOutline({ temas: [] }), /empty_outline/);
  assert.throws(() => core.parseOutline({ temas: [{ nombre: '' }] }), /invalid_outline_item/);
  assert.throws(() => core.parseOutline({ temas: Array.from({ length: 13 }, (_, index) => `Tema ${index}`) }), /outline_too_wide/);
});

test('acepta JSON envuelto en un bloque de código', () => {
  assert.deepEqual(core.parseOutline('```json\n{"temas":["Uno"]}\n```'), [{ name: 'Uno', children: [] }]);
});

test('elimina referencias numéricas de NotebookLM al importar', () => {
  const outline = core.parseOutline({ temas: [
    { nombre: 'Ecuaciones [1]', subtemas: ['Definición [5,7]', 'Caso [x]'] }
  ] });
  assert.equal(outline[0].name, 'Ecuaciones');
  assert.deepEqual(outline[0].children.map(item => item.name), ['Definición', 'Caso [x]']);
  assert.equal(core.stripSourceReferences('Texto [1]. Otro [5, 7] final.'), 'Texto. Otro final.');
});

test('normaliza delimitadores y comandos LaTeX duplicados al renderizar', () => {
  assert.equal(markdown.normalizeImportedMath('\\\\[\\\\frac{dy}{dx}=y\\\\]'), '\\[\\frac{dy}{dx}=y\\]');
  assert.equal(markdown.normalizeImportedMath('ruta \\\\ servidor'), 'ruta \\\\ servidor');
});
