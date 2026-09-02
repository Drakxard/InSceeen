(function () {
  'use strict';

  const STORAGE_KEY = 'inscreen.sintesis.tree.v1';
  const HOLD_MS = 560;
  const MOVE_TOLERANCE = 14;
  const core = window.SynthesisCore;
  const markdown = window.SynthesisMarkdown;
  const elements = Object.fromEntries([
    'treeView','treeHeader','outlineButton','board','emptyHint','sheetView','sheetBack','clipboardButton','menuButton',
    'sheetContent','sheetEmpty','toast','menuOverlay','menuTitle','renameAction','deleteAction','closeMenu',
    'renameOverlay','renameForm','renameInput','cancelRename','deleteOverlay','deleteSummary','cancelDelete','confirmDelete',
    'pasteOverlay','replacePaste','appendPaste','cancelPaste'
  ].map(id => [id, document.getElementById(id)]));

  let state = loadState();
  let currentParentId = null;
  let sheetNodeId = null;
  let activeDraft = null;
  let pendingSheetPaste = '';
  let toastTimer = 0;

  function loadState() {
    let parsed = null;
    try { parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null'); } catch (_) {}
    const normalized = core.normalizeState(parsed);
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(normalized)); } catch (_) {}
    return normalized;
  }

  function acceptState(next) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      state = next;
      return true;
    } catch (_) {
      showToast('No se pudo guardar. Liberá espacio e intentá otra vez.');
      return false;
    }
  }

  function showToast(message) {
    clearTimeout(toastTimer);
    elements.toast.textContent = message;
    elements.toast.hidden = false;
    toastTimer = setTimeout(() => { elements.toast.hidden = true; }, 2600);
  }

  function makePlaque(name, className, parent) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `plaque ${className}`;
    button.textContent = name;
    button.setAttribute('aria-label', name);
    parent.appendChild(button);
    return button;
  }

  function bindPress(element, onTap, onHold) {
    let press = null;
    const finish = event => {
      if (!press || event.pointerId !== press.id) return;
      clearTimeout(press.timer);
      const tap = !press.held && !press.moved;
      press = null;
      if (tap) onTap(event);
    };
    element.addEventListener('pointerdown', event => {
      if (event.button !== 0 || press) return;
      press = { id: event.pointerId, x: event.clientX, y: event.clientY, held: false, moved: false };
      press.timer = setTimeout(() => {
        if (!press || press.moved) return;
        press.held = true;
        onHold(event);
      }, HOLD_MS);
    });
    element.addEventListener('pointermove', event => {
      if (!press || event.pointerId !== press.id) return;
      if (Math.hypot(event.clientX - press.x, event.clientY - press.y) > MOVE_TOLERANCE) {
        press.moved = true;
        clearTimeout(press.timer);
      }
    });
    element.addEventListener('pointerup', finish);
    element.addEventListener('pointercancel', event => {
      if (press && event.pointerId === press.id) { clearTimeout(press.timer); press = null; }
    });
    element.addEventListener('contextmenu', event => event.preventDefault());
    element.addEventListener('click', event => { if (event.detail === 0) onTap(event); });
  }

  function renderTree() {
    sheetNodeId = null;
    elements.sheetView.hidden = true;
    elements.treeView.hidden = false;
    elements.board.replaceChildren();
    elements.treeHeader.replaceChildren();
    const current = currentParentId === null ? null : state.nodes[currentParentId];
    if (currentParentId !== null && !current) currentParentId = null;
    if (current) {
      elements.treeHeader.hidden = false;
      const back = makePlaque(current.name, 'header-plaque', elements.treeHeader);
      back.setAttribute('aria-label', `Volver desde ${current.name}`);
      back.addEventListener('click', () => { currentParentId = current.parentId; renderTree(); });
    } else elements.treeHeader.hidden = true;

    const nodes = core.children(state, currentParentId);
    elements.emptyHint.hidden = nodes.length > 0;
    for (const node of nodes) {
      const plaque = makePlaque(node.name, 'node-plaque', elements.board);
      plaque.style.left = `${node.x * 100}%`;
      plaque.style.top = `${node.y * 100}%`;
      plaque.setAttribute('aria-label', `${node.name}. Toca para entrar; mantén para abrir su hoja.`);
      bindPress(plaque, () => { currentParentId = node.id; renderTree(); }, () => openSheet(node.id));
    }
  }

  function boardPoint(event) {
    const rect = elements.board.getBoundingClientRect();
    const x = Math.max(.12, Math.min(.88, (event.clientX - rect.left) / Math.max(1, rect.width)));
    const minimumY = currentParentId === null ? .08 : .18;
    const y = Math.max(minimumY, Math.min(.92, (event.clientY - rect.top) / Math.max(1, rect.height)));
    return { x, y };
  }

  function newId() {
    if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
    return `node_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
  }

  async function importOutlineFromClipboard() {
    try {
      const result = await window.InScreen?.module?.portapapeles?.();
      const text = result?.ok ? String(result.texto ?? '') : '';
      if (!text.trim()) return showToast(result?.ok ? 'El portapapeles está vacío.' : 'No se pudo leer el portapapeles.');
      const outline = core.parseOutline(text);
      const imported = core.importOutline(state, currentParentId, outline, newId);
      if (acceptState(imported.state)) {
        renderTree();
        showToast(`${imported.imported} elemento${imported.imported === 1 ? '' : 's'} importado${imported.imported === 1 ? '' : 's'}.`);
      }
    } catch (error) {
      if (error?.message === 'outline_too_wide' || error?.message === 'section_too_full') {
        showToast('Cada sección admite hasta 12 elementos.');
      } else if (error?.message === 'outline_too_large') {
        showToast('El esquema supera 200 elementos o 12 niveles.');
      } else showToast('JSON inválido. Usá {"temas":[{"nombre":"Tema","subtemas":[]}]}');
    }
  }
  elements.outlineButton.addEventListener('click', importOutlineFromClipboard);

  function createDraft(point) {
    commitDraft();
    const wrapper = document.createElement('div');
    wrapper.className = 'plaque draft-plaque';
    wrapper.style.left = `${point.x * 100}%`;
    wrapper.style.top = `${point.y * 100}%`;
    const input = document.createElement('input');
    input.maxLength = core.MAX_NAME;
    input.setAttribute('aria-label', 'Nombre del nuevo elemento');
    wrapper.appendChild(input);
    elements.board.appendChild(wrapper);
    activeDraft = { wrapper, input, point, cancelled: false };
    input.addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); input.blur(); }
      if (event.key === 'Escape') { event.preventDefault(); activeDraft.cancelled = true; input.blur(); }
    });
    input.addEventListener('blur', () => setTimeout(commitDraft, 0), { once: true });
    setTimeout(() => input.focus(), 0);
  }

  function commitDraft() {
    const draft = activeDraft;
    if (!draft) return false;
    activeDraft = null;
    const name = draft.cancelled ? '' : core.cleanName(draft.input.value);
    draft.wrapper.remove();
    if (!name) return true;
    try {
      const next = core.addNode(state, { id: newId(), parentId: currentParentId, name, x: draft.point.x, y: draft.point.y });
      if (acceptState(next)) renderTree();
    } catch (_) { showToast('No se pudo crear el elemento.'); }
    return true;
  }

  let boardPress = null;
  elements.board.addEventListener('pointerdown', event => {
    if (event.button !== 0 || event.target !== elements.board) return;
    const point = boardPoint(event);
    boardPress = { id: event.pointerId, x: event.clientX, y: event.clientY, point };
    boardPress.timer = setTimeout(() => {
      if (!boardPress) return;
      createDraft(boardPress.point);
      boardPress.held = true;
    }, HOLD_MS);
  });
  elements.board.addEventListener('pointermove', event => {
    if (!boardPress || event.pointerId !== boardPress.id) return;
    if (Math.hypot(event.clientX - boardPress.x, event.clientY - boardPress.y) > MOVE_TOLERANCE) {
      clearTimeout(boardPress.timer); boardPress = null;
    }
  });
  const endBoardPress = event => {
    if (!boardPress || event.pointerId !== boardPress.id) return;
    clearTimeout(boardPress.timer); boardPress = null;
  };
  elements.board.addEventListener('pointerup', endBoardPress);
  elements.board.addEventListener('pointercancel', endBoardPress);
  elements.board.addEventListener('contextmenu', event => event.preventDefault());
  document.addEventListener('pointerdown', event => {
    if (activeDraft && !activeDraft.wrapper.contains(event.target)) commitDraft();
  }, true);

  function renderContent(content) {
    elements.sheetContent.hidden = !content.trim();
    elements.sheetEmpty.hidden = Boolean(content.trim());
    if (!content.trim()) { elements.sheetContent.replaceChildren(); return; }
    markdown.render(elements.sheetContent, content);
    if (typeof window.renderMathInElement === 'function') {
      try {
        renderMathInElement(elements.sheetContent, {
          delimiters: [
            { left: '$$', right: '$$', display: true }, { left: '\\[', right: '\\]', display: true },
            { left: '\\(', right: '\\)', display: false }, { left: '$', right: '$', display: false }
          ],
          ignoredTags: ['script','noscript','style','textarea','pre','code','option'],
          throwOnError: false, strict: 'ignore', trust: false
        });
      } catch (_) { showToast('Algunas fórmulas no pudieron representarse.'); }
    }
  }

  function openSheet(id) {
    const node = state.nodes[id];
    if (!node) return renderTree();
    commitDraft();
    sheetNodeId = id;
    elements.treeView.hidden = true;
    elements.sheetView.hidden = false;
    elements.sheetBack.replaceChildren();
    const back = makePlaque(node.name, 'header-plaque', elements.sheetBack);
    back.setAttribute('aria-label', `Volver a la sección ${node.name}`);
    back.addEventListener('click', () => { currentParentId = id; renderTree(); });
    renderContent(node.content);
    elements.sheetView.scrollTo(0, 0);
  }

  async function pasteClipboard() {
    const node = state.nodes[sheetNodeId];
    if (!node) return;
    try {
      const result = await window.InScreen?.module?.portapapeles?.();
      const text = result?.ok ? String(result.texto ?? '') : '';
      if (!text.trim()) return showToast(result?.ok ? 'El portapapeles está vacío.' : 'No se pudo leer el portapapeles.');
      pendingSheetPaste = text;
      elements.pasteOverlay.hidden = false;
    } catch (_) { showToast('No se pudo leer el portapapeles.'); }
  }

  function applySheetPaste(append) {
    const node = state.nodes[sheetNodeId];
    if (!node || !pendingSheetPaste) return hideOverlay(elements.pasteOverlay);
    const content = append && node.content.trim()
      ? `${node.content.trimEnd()}\n\n${pendingSheetPaste.trimStart()}`
      : pendingSheetPaste;
    const next = core.setContent(state, node.id, content);
    if (acceptState(next)) {
      pendingSheetPaste = '';
      hideOverlay(elements.pasteOverlay);
      renderContent(content);
      showToast(append ? 'Contenido agregado debajo.' : 'Contenido reemplazado.');
    }
  }
  elements.replacePaste.addEventListener('click', () => applySheetPaste(false));
  elements.appendPaste.addEventListener('click', () => applySheetPaste(true));
  elements.cancelPaste.addEventListener('click', () => { pendingSheetPaste = ''; hideOverlay(elements.pasteOverlay); });
  elements.pasteOverlay.addEventListener('pointerdown', event => {
    if (event.target === elements.pasteOverlay) { pendingSheetPaste = ''; hideOverlay(elements.pasteOverlay); }
  });

  async function copyPath() {
    const node = state.nodes[sheetNodeId];
    if (!node) return;
    const text = core.path(state, node.id).join(', ');
    try {
      const result = await window.InScreen?.module?.escribirPortapapeles?.(text);
      if (!result?.ok) throw new Error('clipboard_write_failed');
      if (navigator.vibrate) navigator.vibrate(35);
      showToast(`Ruta copiada: ${text}`);
    } catch (_) { showToast('No se pudo copiar la ruta.'); }
  }
  bindPress(elements.clipboardButton, pasteClipboard, copyPath);

  function hideOverlay(overlay) { overlay.hidden = true; }
  elements.menuButton.addEventListener('click', () => {
    const node = state.nodes[sheetNodeId]; if (!node) return;
    elements.menuTitle.textContent = node.name; elements.menuOverlay.hidden = false;
  });
  elements.closeMenu.addEventListener('click', () => hideOverlay(elements.menuOverlay));
  elements.menuOverlay.addEventListener('pointerdown', event => { if (event.target === elements.menuOverlay) hideOverlay(elements.menuOverlay); });
  elements.renameAction.addEventListener('click', () => {
    const node = state.nodes[sheetNodeId]; if (!node) return;
    hideOverlay(elements.menuOverlay); elements.renameInput.value = node.name; elements.renameOverlay.hidden = false;
    setTimeout(() => { elements.renameInput.focus(); elements.renameInput.select(); }, 0);
  });
  elements.cancelRename.addEventListener('click', () => hideOverlay(elements.renameOverlay));
  elements.renameForm.addEventListener('submit', event => {
    event.preventDefault();
    try {
      const next = core.renameNode(state, sheetNodeId, elements.renameInput.value);
      if (acceptState(next)) { hideOverlay(elements.renameOverlay); openSheet(sheetNodeId); showToast('Elemento renombrado.'); }
    } catch (_) { showToast('Escribí un nombre válido.'); }
  });
  elements.deleteAction.addEventListener('click', () => {
    const node = state.nodes[sheetNodeId]; if (!node) return;
    hideOverlay(elements.menuOverlay);
    const ids = core.branchIds(state, node.id);
    const contents = ids.filter(id => state.nodes[id]?.content.trim()).length;
    const descendants = ids.length - 1;
    elements.deleteSummary.textContent = `Se eliminará “${node.name}”${descendants ? ` junto con ${descendants} descendiente${descendants === 1 ? '' : 's'}` : ''}${contents ? ` y ${contents} hoja${contents === 1 ? '' : 's'} con contenido` : ''}. Esta acción no se puede deshacer.`;
    elements.deleteOverlay.hidden = false;
  });
  elements.cancelDelete.addEventListener('click', () => hideOverlay(elements.deleteOverlay));
  elements.confirmDelete.addEventListener('click', () => {
    const node = state.nodes[sheetNodeId]; if (!node) return;
    const parentId = node.parentId;
    const result = core.deleteBranch(state, node.id);
    if (acceptState(result.state)) {
      hideOverlay(elements.deleteOverlay); currentParentId = parentId; renderTree(); showToast('Rama eliminada.');
    }
  });

  function closeTopOverlay() {
    for (const overlay of [elements.pasteOverlay, elements.deleteOverlay, elements.renameOverlay, elements.menuOverlay]) {
      if (!overlay.hidden) { overlay.hidden = true; pendingSheetPaste = ''; return true; }
    }
    return false;
  }
  window.addEventListener('inscreen:atras', event => {
    if (closeTopOverlay() || commitDraft()) { event.preventDefault(); return; }
    if (sheetNodeId !== null) { currentParentId = sheetNodeId; renderTree(); event.preventDefault(); return; }
    if (currentParentId !== null) {
      currentParentId = state.nodes[currentParentId]?.parentId ?? null;
      renderTree(); event.preventDefault();
    }
  });
  elements.sheetContent.addEventListener('click', event => { if (event.target.closest('a')) event.preventDefault(); });
  renderTree();
}());
