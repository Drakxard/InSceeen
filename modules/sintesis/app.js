(function () {
  'use strict';

  const STORAGE_KEY = 'inscreen.sintesis.tree.v1';
  const HOLD_MS = 560;
  const MOVE_TOLERANCE = 14;
  const core = window.SynthesisCore;
  const markdown = window.SynthesisMarkdown;
  const elements = Object.fromEntries([
    'treeView','treeHeader','outlineButton','board','sheetView','sheetBack','clipboardButton','menuButton',
    'sheetContent','sheetEmpty','toast','menuOverlay','renameAction','deleteAction',
    'renameOverlay','renameForm','renameInput','cancelRename'
  ].map(id => [id, document.getElementById(id)]));

  let state = loadState();
  let currentParentId = null;
  let sheetNodeId = null;
  let activeDraft = null;
  let sheetEditor = null;
  let editorSaveTimer = 0;
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
    fitPlaqueText(button);
    return button;
  }

  function textFits(plaque) {
    return plaque.scrollHeight <= plaque.clientHeight && plaque.scrollWidth <= plaque.clientWidth;
  }

  function setPlaqueBox(plaque, width) {
    const height = width / (792 / 910);
    plaque.style.width = `${width}px`;
    plaque.style.height = `${height}px`;
    plaque.style.padding = `${Math.max(12, height * .19)}px ${Math.max(12, width * .19)}px`;
  }

  function fitPlaqueText(plaque, requestedScale = 1) {
    if (plaque.classList.contains('back-plaque')) {
      plaque.style.width = '64px';
      plaque.style.height = '74px';
      plaque.style.padding = '15px 12px';
      plaque.style.fontSize = '18px';
      return 1;
    }
    const boardWidth = Math.max(1, elements.board?.clientWidth || innerWidth);
    const minimumFont = boardWidth >= 700 ? 16 : 14;
    const normalWidth = Math.min(190, Math.max(128, boardWidth * .37));
    const maximumWidth = Math.max(normalWidth, boardWidth * .94);
    let width = Math.min(maximumWidth, Math.max(64, normalWidth * requestedScale));

    // Agranda el contenedor antes de permitir texto recortado o ilegible.
    plaque.style.fontSize = `${minimumFont}px`;
    setPlaqueBox(plaque, width);
    while (width < maximumWidth && !textFits(plaque)) {
      width = Math.min(maximumWidth, width + 12);
      setPlaqueBox(plaque, width);
    }

    // Busca la mayor tipografia que usa bien el espacio realmente disponible.
    let low = minimumFont;
    let high = Math.min(boardWidth >= 700 ? 38 : 34, Math.max(20, width * .16));
    while (high - low >= .5) {
      const candidate = (low + high) / 2;
      plaque.style.fontSize = `${candidate}px`;
      if (textFits(plaque)) low = candidate;
      else high = candidate;
    }
    plaque.style.fontSize = `${Math.floor(low * 2) / 2}px`;
    return requestedScale;
  }

  function safePlaquePosition(node, plaque, scale) {
    const halfHeight = plaque.offsetHeight / 2;
    return {
      x: Math.max(0, Math.min(1, node.x)),
      y: Math.max(0, node.y),
      halfHeight
    };
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
    commitSheetEditor();
    const previousScroll = elements.treeView.scrollTop;
    sheetNodeId = null;
    elements.sheetView.hidden = true;
    elements.treeView.hidden = false;
    elements.board.replaceChildren();
    elements.treeHeader.replaceChildren();
    const current = currentParentId === null ? null : state.nodes[currentParentId];
    if (currentParentId !== null && !current) currentParentId = null;
    if (current) {
      elements.treeHeader.hidden = false;
      const back = makePlaque('←', 'back-plaque', elements.treeHeader);
      back.title = 'Volver';
      back.setAttribute('aria-label', `Volver desde ${current.name}`);
      back.addEventListener('click', () => { currentParentId = current.parentId; renderTree(); });
    } else elements.treeHeader.hidden = true;

    const nodes = core.children(state, currentParentId);
    elements.board.style.height = '150dvh';
    let requiredHeight = 1.5;
    for (const node of nodes) {
      const plaque = makePlaque(node.name, 'node-plaque', elements.board);
      const effectiveScale = fitPlaqueText(plaque, node.scale);
      const visible = safePlaquePosition(node, plaque, effectiveScale);
      plaque.style.left = `${visible.x * 100}%`;
      plaque.style.top = `${visible.y * 100}dvh`;
      plaque.style.transform = 'translate(-50%,-50%)';
      plaque.dataset.displayX = String(visible.x);
      plaque.dataset.displayY = String(visible.y);
      plaque.dataset.effectiveScale = String(effectiveScale);
      requiredHeight = Math.max(requiredHeight, visible.y + visible.halfHeight / Math.max(1, elements.treeView.clientHeight) + .3);
      plaque.setAttribute('aria-label', `${node.name}. Toca para entrar; mantén para abrir su hoja.`);
      bindNodeGestures(plaque, node);
    }
    elements.board.style.height = `${requiredHeight * 100}dvh`;
    elements.treeView.scrollTop = previousScroll;
  }

  function bindNodeGestures(plaque, node) {
    const pointers = new Map();
    let gesture = null;
    const point = event => ({ x: event.clientX, y: event.clientY });
    const distance = () => {
      const values = [...pointers.values()];
      return values.length < 2 ? 0 : Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y);
    };
    plaque.addEventListener('pointerdown', event => {
      if (event.button !== 0) return;
      event.stopPropagation();
      pointers.set(event.pointerId, point(event));
      try { plaque.setPointerCapture(event.pointerId); } catch (_) {}
      if (pointers.size === 1) {
        const current = state.nodes[node.id];
        if (!current) return;
        const cursor = boardPoint(event);
        const displayX = Number(plaque.dataset.displayX) || current.x;
        const displayY = Number(plaque.dataset.displayY) || current.y;
        gesture = {
          start: point(event), origin: { x: displayX, y: displayY }, scale: current.scale,
          grab: { x: cursor.x - displayX, y: cursor.y - displayY }, moved: false, held: false
        };
        gesture.timer = setTimeout(() => {
          if (!gesture || gesture.moved || pointers.size !== 1) return;
          // Esperamos a que se levante el dedo. Si mostramos la hoja ahora,
          // Android aplica esta misma pulsacion larga al texto recien visible
          // y abre su menu nativo de seleccionar/pegar.
          gesture.held = true;
          if (navigator.vibrate) navigator.vibrate(25);
        }, HOLD_MS);
      } else if (pointers.size === 2 && gesture) {
        clearTimeout(gesture.timer); gesture.moved = true;
        gesture.pinchDistance = distance(); gesture.pinchScale = gesture.previewScale ?? gesture.scale;
      }
    });
    plaque.addEventListener('pointermove', event => {
      if (!pointers.has(event.pointerId) || !gesture) return;
      pointers.set(event.pointerId, point(event));
      if (pointers.size >= 2) {
        const requested = Math.max(core.MIN_SCALE, Math.min(core.MAX_SCALE, gesture.pinchScale * distance() / Math.max(1, gesture.pinchDistance)));
        const effective = requested;
        fitPlaqueText(plaque, effective);
        plaque.style.transform = 'translate(-50%,-50%)';
        gesture.previewScale = requested; return;
      }
      const dx = event.clientX - gesture.start.x;
      const dy = event.clientY - gesture.start.y;
      if (!gesture.moved && Math.hypot(dx, dy) <= MOVE_TOLERANCE) return;
      clearTimeout(gesture.timer); gesture.moved = true;
      const cursor = boardPoint(event);
      const next = {
        x: Math.max(0, Math.min(1, cursor.x - gesture.grab.x)),
        y: Math.max(0, Math.min(50, cursor.y - gesture.grab.y))
      };
      plaque.style.left = `${next.x * 100}%`; plaque.style.top = `${next.y * 100}dvh`;
      plaque.dataset.displayX = String(next.x); plaque.dataset.displayY = String(next.y);
      gesture.previewPoint = next;
    });
    const finish = event => {
      if (!pointers.has(event.pointerId) || !gesture) return;
      pointers.delete(event.pointerId);
      if (pointers.size) return;
      clearTimeout(gesture.timer);
      const completed = gesture; gesture = null;
      if (completed.previewScale != null) acceptState(core.scaleNode(state, node.id, completed.previewScale));
      if (completed.previewPoint) acceptState(core.moveNode(state, node.id, completed.previewPoint.x, completed.previewPoint.y));
      if (completed.previewScale != null) { renderTree(); return; }
      if (completed.held) { openSheet(node.id); return; }
      if (!completed.moved && !completed.held) { currentParentId = node.id; renderTree(); }
    };
    plaque.addEventListener('pointerup', finish);
    plaque.addEventListener('pointercancel', finish);
    plaque.addEventListener('contextmenu', event => event.preventDefault());
  }

  function boardPoint(event) {
    const rect = elements.board.getBoundingClientRect();
    const x = Math.max(0, Math.min(1, (event.clientX - rect.left) / Math.max(1, rect.width)));
    const minimumY = currentParentId === null ? .08 : .18;
    const viewportHeight = Math.max(1, elements.treeView.clientHeight);
    const y = Math.max(minimumY, Math.min(50, (event.clientY - rect.top) / viewportHeight));
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
    wrapper.style.top = `${point.y * 100}dvh`;
    wrapper.style.transform = `translate(-50%,-50%) scale(${state.defaultScale})`;
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
    setTimeout(() => {
      try { input.focus({ preventScroll: true }); }
      catch (_) { input.focus(); }
    }, 0);
  }

  function commitDraft() {
    const draft = activeDraft;
    if (!draft) return false;
    activeDraft = null;
    const name = draft.cancelled ? '' : core.cleanName(draft.input.value);
    draft.wrapper.remove();
    if (!name) return true;
    try {
      const next = core.addNode(state, { id: newId(), parentId: currentParentId, name, x: draft.point.x, y: draft.point.y, scale: state.defaultScale });
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

  function beginSheetEditor() {
    const node = state.nodes[sheetNodeId];
    if (!node || sheetEditor) return;
    const textarea = document.createElement('textarea');
    textarea.className = 'sheet-editor';
    textarea.setAttribute('aria-label', 'Editar contenido de la hoja');
    textarea.value = node.content;
    elements.sheetContent.hidden = true; elements.sheetEmpty.hidden = true;
    elements.sheetContent.after(textarea); sheetEditor = textarea;
    textarea.addEventListener('input', () => {
      clearTimeout(editorSaveTimer);
      editorSaveTimer = setTimeout(() => {
        const current = state.nodes[sheetNodeId];
        if (sheetEditor === textarea && current && textarea.value !== current.content) acceptState(core.setContent(state, current.id, textarea.value));
      }, 180);
    });
    textarea.addEventListener('blur', commitSheetEditor, { once: true });
    setTimeout(() => { textarea.focus(); textarea.setSelectionRange(textarea.value.length, textarea.value.length); }, 0);
  }

  function commitSheetEditor() {
    const textarea = sheetEditor;
    if (!textarea) return false;
    clearTimeout(editorSaveTimer);
    sheetEditor = null;
    const node = state.nodes[sheetNodeId];
    if (node && textarea.value !== node.content) acceptState(core.setContent(state, node.id, textarea.value));
    const content = state.nodes[sheetNodeId]?.content ?? textarea.value;
    textarea.remove();
    if (!elements.sheetView.hidden) renderContent(content);
    return true;
  }

  function openSheet(id) {
    const node = state.nodes[id];
    if (!node) return renderTree();
    commitDraft();
    sheetNodeId = id;
    elements.treeView.hidden = true;
    elements.sheetView.hidden = false;
    elements.sheetBack.replaceChildren();
    const back = makePlaque('←', 'back-plaque', elements.sheetBack);
    back.title = 'Volver';
    back.setAttribute('aria-label', `Volver a la sección ${node.name}`);
    back.addEventListener('click', () => { currentParentId = id; renderTree(); });
    renderContent(node.content);
    elements.sheetView.scrollTo(0, 0);
  }

  async function pasteClipboard() {
    commitSheetEditor();
    const node = state.nodes[sheetNodeId];
    if (!node) return;
    try {
      const result = await window.InScreen?.module?.portapapeles?.();
      const text = result?.ok ? String(result.texto ?? '') : '';
      if (!text.trim()) return showToast(result?.ok ? 'El portapapeles está vacío.' : 'No se pudo leer el portapapeles.');
      const importedText = core.stripSourceReferences(text);
      const content = node.content.trim() ? `${node.content.trimEnd()}\n\n${importedText.trimStart()}` : importedText;
      const next = core.setContent(state, node.id, content);
      if (acceptState(next)) { renderContent(content); showToast(node.content.trim() ? 'Contenido agregado debajo.' : 'Contenido importado.'); }
    } catch (_) { showToast('No se pudo leer el portapapeles.'); }
  }

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
    commitSheetEditor(); elements.menuOverlay.hidden = false;
  });
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
    const parentId = node.parentId;
    const result = core.deleteBranch(state, node.id);
    if (acceptState(result.state)) {
      hideOverlay(elements.menuOverlay); currentParentId = parentId; renderTree(); showToast('Rama eliminada.');
    }
  });

  function closeTopOverlay() {
    for (const overlay of [elements.renameOverlay, elements.menuOverlay]) {
      if (!overlay.hidden) { overlay.hidden = true; return true; }
    }
    return false;
  }
  window.addEventListener('inscreen:atras', event => {
    if (closeTopOverlay() || commitDraft() || commitSheetEditor()) { event.preventDefault(); return; }
    if (sheetNodeId !== null) { currentParentId = sheetNodeId; renderTree(); event.preventDefault(); return; }
    if (currentParentId !== null) {
      currentParentId = state.nodes[currentParentId]?.parentId ?? null;
      renderTree(); event.preventDefault();
    }
  });
  elements.sheetContent.addEventListener('click', event => { if (event.target.closest('a')) event.preventDefault(); });
  bindPress(elements.sheetContent, () => {}, beginSheetEditor);
  window.addEventListener('pagehide', commitSheetEditor);
  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      if (!elements.treeView.hidden && !activeDraft && !sheetEditor) renderTree();
    }, 80);
  });
  renderTree();
}());
