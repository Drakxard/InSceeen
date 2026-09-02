(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.SynthesisCore = api;
}(globalThis, function () {
  'use strict';

  const VERSION = 2;
  const MAX_NAME = 80;
  const MIN_SCALE = 0.5;
  const MAX_SCALE = 2.4;

  const emptyState = () => ({ version: VERSION, defaultScale: 1, nodes: {} });
  const cleanName = value => String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, MAX_NAME);
  const stripSourceReferences = value => String(value ?? '')
    .replace(/\[(?:\d+\s*)(?:,\s*\d+\s*)*\]/g, '')
    .replace(/[ \t]+(?=[,.;:!?])/g, '')
    .replace(/[ \t]{2,}/g, ' ');
  const finiteUnit = value => Number.isFinite(Number(value)) ? Math.max(0, Math.min(1, Number(value))) : 0.5;
  const finiteY = value => Number.isFinite(Number(value)) ? Math.max(0, Math.min(50, Number(value))) : 0.5;
  const finiteScale = value => Number.isFinite(Number(value)) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, Number(value))) : 1;

  function normalizeState(raw) {
    const state = emptyState();
    state.defaultScale = finiteScale(raw?.defaultScale);
    const source = raw && typeof raw === 'object' && raw.nodes && typeof raw.nodes === 'object' ? raw.nodes : {};
    for (const [key, candidate] of Object.entries(source)) {
      if (!candidate || typeof candidate !== 'object') continue;
      const id = String(candidate.id ?? key);
      const name = cleanName(candidate.name);
      if (!/^[A-Za-z0-9_-]{1,80}$/.test(id) || !name) continue;
      state.nodes[id] = {
        id,
        parentId: candidate.parentId == null ? null : String(candidate.parentId),
        name,
        x: finiteUnit(candidate.x),
        y: finiteY(candidate.y),
        scale: finiteScale(candidate.scale),
        content: typeof candidate.content === 'string' ? candidate.content : ''
      };
    }
    for (const node of Object.values(state.nodes)) {
      if (node.parentId === node.id || (node.parentId !== null && !state.nodes[node.parentId])) node.parentId = null;
      const visited = new Set([node.id]);
      let parent = node.parentId;
      while (parent !== null) {
        if (visited.has(parent)) { node.parentId = null; break; }
        visited.add(parent);
        parent = state.nodes[parent]?.parentId ?? null;
      }
    }
    return state;
  }

  function addNode(state, node) {
    const next = normalizeState(state);
    const id = String(node?.id ?? '');
    const name = cleanName(node?.name);
    if (!/^[A-Za-z0-9_-]{1,80}$/.test(id) || next.nodes[id] || !name) throw new Error('invalid_node');
    const parentId = node.parentId == null ? null : String(node.parentId);
    if (parentId !== null && !next.nodes[parentId]) throw new Error('parent_not_found');
    next.nodes[id] = { id, parentId, name, x: finiteUnit(node.x), y: finiteY(node.y), scale: finiteScale(node.scale ?? next.defaultScale), content: '' };
    return next;
  }

  function moveNode(state, id, x, y) {
    const next = normalizeState(state);
    if (!next.nodes[id]) throw new Error('node_not_found');
    next.nodes[id].x = finiteUnit(x);
    next.nodes[id].y = finiteY(y);
    return next;
  }

  function scaleNode(state, id, scale) {
    const next = normalizeState(state);
    if (!next.nodes[id]) throw new Error('node_not_found');
    const normalized = finiteScale(scale);
    Object.values(next.nodes).forEach(node => { node.scale = normalized; });
    next.defaultScale = normalized;
    return next;
  }

  function renameNode(state, id, name) {
    const next = normalizeState(state);
    if (!next.nodes[id]) throw new Error('node_not_found');
    const normalized = cleanName(name);
    if (!normalized) throw new Error('invalid_name');
    next.nodes[id].name = normalized;
    return next;
  }

  function setContent(state, id, content) {
    const next = normalizeState(state);
    if (!next.nodes[id]) throw new Error('node_not_found');
    next.nodes[id].content = String(content ?? '');
    return next;
  }

  function children(state, parentId) {
    const normalized = normalizeState(state);
    return Object.values(normalized.nodes).filter(node => node.parentId === (parentId ?? null));
  }

  function branchIds(state, id) {
    const normalized = normalizeState(state);
    if (!normalized.nodes[id]) return [];
    const result = [];
    const visit = current => {
      result.push(current);
      Object.values(normalized.nodes).filter(node => node.parentId === current).forEach(node => visit(node.id));
    };
    visit(id);
    return result;
  }

  function deleteBranch(state, id) {
    const next = normalizeState(state);
    const removed = branchIds(next, id);
    removed.forEach(nodeId => delete next.nodes[nodeId]);
    return { state: next, removed };
  }

  function path(state, id) {
    const normalized = normalizeState(state);
    const names = [];
    let node = normalized.nodes[id];
    while (node) {
      names.unshift(node.name);
      node = node.parentId === null ? null : normalized.nodes[node.parentId];
    }
    return names;
  }

  function parseOutline(raw) {
    let value;
    try {
      const source = typeof raw === 'string'
        ? raw.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '')
        : raw;
      value = typeof source === 'string' ? JSON.parse(source) : source;
    }
    catch (_) { throw new Error('invalid_json'); }
    const rootItems = Array.isArray(value) ? value :
      value && typeof value === 'object' ? (value.temas ?? value.subtemas ?? value.children ?? value.items ?? [value]) : null;
    if (!Array.isArray(rootItems) || !rootItems.length) throw new Error('empty_outline');
    let count = 0;
    const normalize = (item, depth) => {
      if (depth > 12 || ++count > 200) throw new Error('outline_too_large');
      if (typeof item === 'string') {
        const name = cleanName(stripSourceReferences(item)); if (!name) throw new Error('invalid_outline_item');
        return { name, children: [] };
      }
      if (!item || typeof item !== 'object') throw new Error('invalid_outline_item');
      const name = cleanName(stripSourceReferences(item.nombre ?? item.name ?? item.tema ?? item.title));
      if (!name) throw new Error('invalid_outline_item');
      const children = item.subtemas ?? item.temas ?? item.children ?? item.items ?? [];
      if (!Array.isArray(children)) throw new Error('invalid_outline_item');
      if (children.length > 12) throw new Error('outline_too_wide');
      return { name, children: children.map(child => normalize(child, depth + 1)) };
    };
    if (rootItems.length > 12) throw new Error('outline_too_wide');
    return rootItems.map(item => normalize(item, 1));
  }

  function importOutline(state, parentId, outline, idFactory) {
    let next = normalizeState(state);
    if (parentId !== null && !next.nodes[parentId]) throw new Error('parent_not_found');
    let imported = 0;
    const addItems = (items, parent) => {
      const offset = children(next, parent).length;
      if (offset + items.length > 12) throw new Error('section_too_full');
      items.forEach((item, index) => {
        const slot = offset + index;
        const id = idFactory();
        next = addNode(next, {
          id, parentId: parent, name: item.name,
          x: .18 + (slot % 3) * .32,
          y: .20 + (Math.floor(slot / 3) % 4) * .22,
          scale: next.defaultScale
        });
        imported++;
        addItems(item.children, id);
      });
    };
    addItems(outline, parentId);
    return { state: next, imported };
  }

  return { VERSION, MAX_NAME, MIN_SCALE, MAX_SCALE, emptyState, normalizeState, addNode, moveNode, scaleNode, renameNode, setContent, children, branchIds, deleteBranch, path, parseOutline, importOutline, cleanName, stripSourceReferences };
}));
