const STUDY_VERSION = 1;
const REPAIR_SUFFIX = `\n\nTu respuesta anterior no respetó el contrato. Reintentá desde cero y devolvé solamente el JSON exacto solicitado, sin Markdown ni texto adicional.`;

const core = window.ApuntesCore;
const conceptPrompt = document.getElementById('conceptPrompt').textContent.trim();
const cardStage = document.getElementById('cardStage');
const studyCard = document.getElementById('studyCard');
const loadingCard = document.getElementById('loadingCard');
const retryCard = document.getElementById('retryCard');
const emptyCard = document.getElementById('emptyCard');
const completeCard = document.getElementById('completeCard');
const loadingText = document.getElementById('loadingText');
const retryText = document.getElementById('retryText');
const pagePosition = document.getElementById('pagePosition');
const cardPosition = document.getElementById('cardPosition');
const previousCard = document.getElementById('previousCard');
const nextCard = document.getElementById('nextCard');
const navigationHint = document.getElementById('navigationHint');
const frontView = document.getElementById('frontView');
const listeningView = document.getElementById('listeningView');
const answerView = document.getElementById('answerView');
const editView = document.getElementById('editView');
const cardHeader = document.getElementById('cardHeader');
const listeningHeader = document.getElementById('listeningHeader');
const liveTranscript = document.getElementById('liveTranscript');
const answerText = document.getElementById('answerText');
const answerEditor = document.getElementById('answerEditor');
const frontHint = document.getElementById('frontHint');
const stopVoice = document.getElementById('stopVoice');
const errorOverlay = document.getElementById('errorOverlay');
const consentOverlay = document.getElementById('consentOverlay');

let inventory = [];
let studyState = null;
let loadedPages = new Map();
let pageIndex = -1;
let cardIndex = 0;
let currentCards = [];
let scene = 'loading';
let cardMode = 'front';
let failedPageIndex = -1;
let pointerStartX = null;
let moving = false;
let voiceActive = false;
let voiceCardId = null;
let systemVoiceAllowed = false;
let consentResolve = null;
let saveQueue = Promise.resolve();

function showError(title, error) {
  document.getElementById('errorTitle').textContent = title;
  document.getElementById('errorText').textContent = error?.message || String(error || 'Error desconocido');
  errorOverlay.hidden = false;
}

function closeError() { errorOverlay.hidden = true; }

function setScene(next) {
  scene = next;
  studyCard.classList.toggle('is-hidden', next !== 'card');
  loadingCard.classList.toggle('is-hidden', next !== 'loading');
  retryCard.classList.toggle('is-hidden', next !== 'retry');
  emptyCard.classList.toggle('is-hidden', next !== 'empty');
  completeCard.classList.toggle('is-hidden', next !== 'complete');
  const noNavigation = next === 'loading' || next === 'retry' || next === 'empty';
  previousCard.disabled = noNavigation || (next === 'card' && pageIndex === 0 && cardIndex === 0) || voiceActive || cardMode === 'edit';
  nextCard.disabled = noNavigation || next === 'complete' || voiceActive || cardMode === 'edit';
  navigationHint.textContent = next === 'complete' ? 'Conjunto completado' : next === 'card' ? 'Deslizá para avanzar' : '';
}

function setCardMode(next) {
  cardMode = next;
  frontView.classList.toggle('is-hidden', next !== 'front');
  listeningView.classList.toggle('is-hidden', next !== 'listening');
  answerView.classList.toggle('is-hidden', next !== 'answer');
  editView.classList.toggle('is-hidden', next !== 'edit');
  studyCard.setAttribute('aria-label', next === 'front' ? 'Tarjeta conceptual; tocá para responder' : 'Respuesta de la tarjeta');
  setScene('card');
}

function currentCard() { return currentCards[cardIndex] || null; }

function renderCard(mode = 'front') {
  const card = currentCard();
  if (!card) return;
  cardHeader.textContent = card.cabecera;
  listeningHeader.textContent = card.cabecera;
  answerText.textContent = card.respuesta || '';
  frontHint.textContent = card.respuesta ? 'Tocá para ver tu respuesta' : 'Tocá para responder';
  pagePosition.textContent = `Página ${pageIndex + 1} de ${inventory.length}`;
  cardPosition.textContent = `Tarjeta ${cardIndex + 1} de ${currentCards.length}`;
  setCardMode(mode);
}

function initialState(sessionId) { return { version: STUDY_VERSION, conjuntoId: sessionId, paginas: {} }; }

function stateLooksUsable(value, sessionId) {
  return value && value.version === STUDY_VERSION && value.conjuntoId === sessionId &&
    value.paginas && typeof value.paginas === 'object' && !Array.isArray(value.paginas);
}

function persistState() {
  const snapshot = JSON.parse(JSON.stringify(studyState));
  saveQueue = saveQueue.catch(() => undefined).then(async () => {
    const result = await window.InScreen.module.guardarApuntesEstado(snapshot);
    if (!result?.ok) throw new Error(result?.error || 'No se pudo guardar el progreso.');
  });
  return saveQueue;
}

async function generatePage(content) {
  let firstValidationError = null;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const prompt = attempt === 0 ? conceptPrompt : conceptPrompt + REPAIR_SUFFIX;
    const result = await window.InScreen.module.consulta(prompt, content);
    if (!result?.ok) throw new Error(result?.error || 'Groq no pudo crear los bloques conceptuales.');
    try {
      return core.parseBlockOutput(result.contenido);
    } catch (error) {
      firstValidationError ||= error;
      if (attempt === 1) throw new Error(`${firstValidationError.message} El reintento también fue inválido.`);
    }
  }
  return [];
}

async function ensurePage(index) {
  if (loadedPages.has(index)) return loadedPages.get(index);
  const descriptor = inventory[index];
  if (!descriptor) throw new Error('La página solicitada ya no existe.');
  loadingText.textContent = `Preparando página ${index + 1} de ${inventory.length}…`;
  setScene('loading');
  const result = await window.InScreen.module.apunte(descriptor.numero);
  if (!result?.ok || !result.archivo) throw new Error(result?.error || 'No se pudo leer la transcripción de la página.');
  const file = result.archivo;
  const pageId = String(file.id || descriptor.id || file.nombre || descriptor.nombre);
  const sourceHash = String(file.hash || '');
  if (!/^[0-9a-f]{64}$/.test(sourceHash)) throw new Error('La APK no entregó un hash válido para esta página.');
  let page = studyState.paginas[pageId];
  if (!core.validSavedPage(page, sourceHash)) {
    const blocks = await generatePage(String(file.contenido || ''));
    page = { sourceHash, tarjetas: core.buildCards(pageId, sourceHash, blocks) };
    studyState.paginas[pageId] = page;
    try { await persistState(); }
    catch (error) { delete studyState.paginas[pageId]; throw error; }
  }
  const loaded = { pageId, sourceHash, cards: page.tarjetas };
  loadedPages.set(index, loaded);
  return loaded;
}

async function showPage(targetIndex, direction = 1, lastCard = false) {
  let index = targetIndex;
  const step = direction < 0 ? -1 : 1;
  while (index >= 0 && index < inventory.length) {
    try {
      const page = await ensurePage(index);
      if (page.cards.length) {
        pageIndex = index;
        currentCards = page.cards;
        cardIndex = lastCard || step < 0 ? page.cards.length - 1 : 0;
        renderCard('front');
        return true;
      }
    } catch (error) {
      failedPageIndex = index;
      retryText.textContent = error?.message || String(error);
      pagePosition.textContent = `Página ${index + 1} de ${inventory.length}`;
      cardPosition.textContent = '';
      setScene('retry');
      return false;
    }
    index += step;
  }
  if (step > 0) {
    pageIndex = inventory.length;
    currentCards = [];
    pagePosition.textContent = `${inventory.length} página${inventory.length === 1 ? '' : 's'}`;
    cardPosition.textContent = '';
    setScene('complete');
    previousCard.disabled = ![...loadedPages.values()].some(page => page.cards.length);
  }
  return false;
}

function animateCard(direction, update) {
  if (moving) return;
  moving = true;
  studyCard.classList.add(direction > 0 ? 'leaving-left' : 'leaving-right');
  window.setTimeout(() => {
    update();
    studyCard.classList.remove('leaving-left', 'leaving-right');
    studyCard.classList.add('is-positioning', direction > 0 ? 'entering-right' : 'entering-left');
    void studyCard.offsetWidth;
    studyCard.classList.remove('is-positioning');
    requestAnimationFrame(() => requestAnimationFrame(() => studyCard.classList.remove('entering-right', 'entering-left')));
    window.setTimeout(() => { moving = false; }, 190);
  }, 180);
}

async function moveCard(direction) {
  if (moving || voiceActive || cardMode === 'edit') return;
  if (scene === 'complete' && direction < 0) {
    await showPage(inventory.length - 1, -1, true);
    return;
  }
  if (scene !== 'card') return;
  if (direction < 0 && cardIndex > 0) return animateCard(-1, () => { cardIndex -= 1; renderCard('front'); });
  if (direction > 0 && cardIndex < currentCards.length - 1) return animateCard(1, () => { cardIndex += 1; renderCard('front'); });
  if (direction < 0 && pageIndex === 0) return;
  await showPage(pageIndex + direction, direction, direction < 0);
}

function askSystemVoiceConsent() {
  if (systemVoiceAllowed) return Promise.resolve(true);
  consentOverlay.hidden = false;
  return new Promise(resolve => { consentResolve = resolve; });
}

function finishConsent(allowed) {
  consentOverlay.hidden = true;
  if (allowed) systemVoiceAllowed = true;
  const resolve = consentResolve;
  consentResolve = null;
  resolve?.(allowed);
}

async function startVoice() {
  const card = currentCard();
  if (!card || voiceActive) return;
  try {
    if (typeof window.InScreen?.module?.vozEstado !== 'function') throw new Error('Actualizá InScreen para usar el dictado del teléfono.');
    const status = await window.InScreen.module.vozEstado();
    if (!status?.ok) throw new Error(status?.error || 'No se pudo consultar el reconocimiento de voz.');
    let allowSystem = false;
    if (!status.onDevice) {
      if (!status.servicioSistema) throw new Error('Este teléfono no tiene un servicio de reconocimiento de voz disponible.');
      if (!await askSystemVoiceConsent()) return;
      allowSystem = true;
    }
    voiceActive = true;
    voiceCardId = card.id;
    liveTranscript.textContent = 'Escuchando…';
    stopVoice.disabled = false;
    stopVoice.textContent = 'Detener';
    setCardMode('listening');
    const started = await window.InScreen.module.vozIniciar({ permitirServicioSistema: allowSystem });
    if (!started?.ok) throw new Error(started?.error || 'No se pudo iniciar el reconocimiento de voz.');
  } catch (error) {
    voiceActive = false;
    voiceCardId = null;
    renderCard('front');
    showError('No se pudo iniciar el dictado', error);
  }
}

async function stopListening() {
  if (!voiceActive) return;
  stopVoice.disabled = true;
  stopVoice.textContent = 'Deteniendo…';
  const result = await window.InScreen.module.vozDetener();
  if (!result?.ok) {
    voiceActive = false;
    voiceCardId = null;
    renderCard('front');
    showError('No se pudo detener el dictado', new Error(result?.error || 'Error de reconocimiento.'));
  }
}

async function handleVoiceEvent(event) {
  const detail = event.detail || {};
  if (!voiceActive) return;
  if (detail.estado === 'escuchando') return;
  if (detail.estado === 'parcial') {
    liveTranscript.textContent = String(detail.texto || '').trim() || 'Escuchando…';
    return;
  }
  const card = currentCard();
  if (!card || card.id !== voiceCardId) return;
  voiceActive = false;
  voiceCardId = null;
  if (detail.estado === 'error') {
    renderCard('front');
    showError('El reconocimiento se interrumpió', new Error(detail.error || 'recognizer_error'));
    return;
  }
  if (detail.estado !== 'final') return;
  const transcript = String(detail.texto || '').trim().replace(/\s+/g, ' ');
  if (!transcript) {
    renderCard('front');
    showError('No se guardó la respuesta', new Error('Android no reconoció texto en este intento.'));
    return;
  }
  card.respuesta = transcript;
  card.respuestaActualizada = Date.now();
  answerText.textContent = transcript;
  setCardMode('answer');
  try { await persistState(); } catch (error) { showError('La respuesta quedó solo en memoria', error); }
}

function showAnswer() {
  const card = currentCard();
  if (!card?.respuesta) return;
  answerText.textContent = card.respuesta;
  setCardMode('answer');
}

function beginEdit() {
  const card = currentCard();
  if (!card?.respuesta) return;
  answerEditor.value = card.respuesta;
  setCardMode('edit');
  answerEditor.focus({ preventScroll: true });
}

async function saveEditedAnswer() {
  const card = currentCard();
  const value = answerEditor.value.trim().replace(/\s+/g, ' ');
  if (!card || !value) return showError('No se pudo guardar', new Error('La respuesta no puede quedar vacía.'));
  card.respuesta = value;
  card.respuestaActualizada = Date.now();
  answerText.textContent = value;
  setCardMode('answer');
  try { await persistState(); } catch (error) { showError('La edición quedó solo en memoria', error); }
}

async function initialize() {
  try {
    if (!core || typeof window.InScreen?.module?.apuntes !== 'function') throw new Error('Actualizá InScreen para abrir conjuntos de apuntes.');
    const listed = await window.InScreen.module.apuntes();
    if (!listed?.ok) {
      if (listed?.error === 'notes_session_not_selected') return setScene('empty');
      throw new Error(listed?.error || 'No se pudo leer el conjunto de apuntes.');
    }
    inventory = Array.isArray(listed.archivos) ? listed.archivos.slice().sort((a, b) => a.numero - b.numero) : [];
    if (!inventory.length) {
      document.getElementById('emptyTitle').textContent = 'El conjunto está vacío';
      document.getElementById('emptyText').textContent = 'No hay páginas transcritas para estudiar.';
      return setScene('empty');
    }
    const sessionId = String(listed.conjunto?.id || '');
    const stored = await window.InScreen.module.apuntesEstado();
    studyState = stored?.ok && stateLooksUsable(stored.estado, sessionId) ? stored.estado : initialState(sessionId);
    const availableIds = new Set(inventory.map(file => String(file.id || '')));
    let pruned = false;
    Object.keys(studyState.paginas).forEach(id => {
      if (!availableIds.has(id)) { delete studyState.paginas[id]; pruned = true; }
    });
    if (pruned || !stored?.ok) await persistState();
    await showPage(0, 1);
  } catch (error) {
    setScene('empty');
    document.getElementById('emptyTitle').textContent = 'No se pudo abrir el conjunto';
    document.getElementById('emptyText').textContent = error?.message || String(error);
  }
}

studyCard.addEventListener('click', event => {
  if (event.target.closest('button,textarea')) return;
  if (cardMode === 'front') {
    if (currentCard()?.respuesta) showAnswer(); else void startVoice();
  } else if (cardMode === 'answer') renderCard('front');
});
studyCard.addEventListener('keydown', event => {
  if ((event.key === 'Enter' || event.key === ' ') && cardMode === 'front') {
    event.preventDefault();
    if (currentCard()?.respuesta) showAnswer(); else void startVoice();
  }
  if (event.key === 'ArrowRight') void moveCard(1);
  if (event.key === 'ArrowLeft') void moveCard(-1);
});
cardStage.addEventListener('pointerdown', event => {
  if (scene !== 'card' || voiceActive || cardMode === 'edit' || event.target.closest('button,textarea')) return;
  pointerStartX = event.clientX;
  cardStage.setPointerCapture(event.pointerId);
});
cardStage.addEventListener('pointerup', event => {
  if (pointerStartX === null) return;
  const delta = event.clientX - pointerStartX;
  pointerStartX = null;
  if (Math.abs(delta) > 50) void moveCard(delta < 0 ? 1 : -1);
});
cardStage.addEventListener('pointercancel', () => { pointerStartX = null; });

previousCard.addEventListener('click', () => void moveCard(-1));
nextCard.addEventListener('click', () => void moveCard(1));
document.getElementById('retryPage').addEventListener('click', () => { loadedPages.delete(failedPageIndex); void showPage(failedPageIndex, 1); });
document.getElementById('closeError').addEventListener('click', closeError);
document.getElementById('denySystemVoice').addEventListener('click', () => finishConsent(false));
document.getElementById('allowSystemVoice').addEventListener('click', () => finishConsent(true));
stopVoice.addEventListener('click', () => void stopListening());
document.getElementById('showFront').addEventListener('click', () => renderCard('front'));
document.getElementById('editAnswer').addEventListener('click', beginEdit);
document.getElementById('rerecordAnswer').addEventListener('click', () => void startVoice());
document.getElementById('cancelEdit').addEventListener('click', showAnswer);
document.getElementById('saveEdit').addEventListener('click', () => void saveEditedAnswer());
window.addEventListener('inscreen:voz', event => void handleVoiceEvent(event));
window.addEventListener('beforeunload', () => { if (voiceActive) void window.InScreen?.module?.vozCancelar?.(); });

void initialize();
