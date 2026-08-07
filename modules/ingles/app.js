const QUESTION_CACHE_VERSION = 2;
const QUESTION_BATCH_SIZE = 5;
const QUESTION_PREFETCH_POSITION = 4;
const QUESTION_CACHE_PREFIX = 'inscreen:ingles:questions:';
const NONE_OPTION = 'Ninguna de las anteriores';

let historyFiles = [];
let currentFileIndex = -1;
let cards = [];
let questionCards = [];
let cardIndex = 0;
let questionIndex = 0;
let pointerStartX = null;
let didSwipe = false;
let questionHoldTimer = null;
let questionMode = false;
let questionAnimating = false;
let generationPromise = null;
let generationError = '';
let unavailableNextFileId = null;
let questionCache = { version: QUESTION_CACHE_VERSION, entries: {} };
const questionAnswers = new Map();
const generatingKeys = new Set();

const flashcard = document.getElementById('flashcard');
const cardStage = document.getElementById('cardStage');
const questionCard = document.getElementById('questionCard');
const questionPromptText = document.getElementById('questionPromptText');
const questionOptions = document.getElementById('questionOptions');
const questionStatus = document.getElementById('questionStatus');
const retryQuestions = document.getElementById('retryQuestions');
const dayCard = document.getElementById('dayCard');
const loadingCard = document.getElementById('loadingCard');
const loadingText = document.getElementById('loadingText');
const previousDay = document.getElementById('previousDay');
const nextDay = document.getElementById('nextDay');
const retryDay = document.getElementById('retryDay');
const dayNotice = document.getElementById('dayNotice');
const questionPrompt = document.getElementById('questionPrompt').textContent.trim();

function openErrorOverlay(context, error) {
  document.getElementById('errorDetailsText').textContent = `${context}\n\n${error?.message || String(error)}`;
  document.getElementById('errorOverlay').classList.add('active');
}

function closeErrorOverlay() {
  document.getElementById('errorOverlay').classList.remove('active');
}

function shuffle(values) {
  const result = [...values];
  for (let index = result.length - 1; index > 0; index -= 1) {
    const target = Math.floor(Math.random() * (index + 1));
    [result[index], result[target]] = [result[target], result[index]];
  }
  return result;
}

function normalizeSource(english, spanish) {
  return `${english.trim().replace(/\s+/g, ' ')}:${spanish.trim().replace(/\s+/g, ' ')}`;
}

function hashSource(source) {
  let hash = 0x811c9dc5;
  for (let index = 0; index < source.length; index += 1) {
    hash ^= source.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return `q${(hash >>> 0).toString(16).padStart(8, '0')}`;
}

function parseCards(file) {
  return (file.contenido || '').split(/\r?\n/).flatMap((line, lineIndex) => {
    const separator = line.indexOf(':');
    if (separator < 1) return [];
    const english = line.slice(0, separator).trim();
    const spanish = line.slice(separator + 1).trim();
    if (!english || !spanish) return [];
    const source = normalizeSource(english, spanish);
    return [{ english, spanish, source, key: hashSource(source), line: lineIndex + 1 }];
  });
}

function subjectCacheKey() {
  let subjectId = 'unknown';
  try {
    subjectId = String(window.InScreen?.module?.context?.()?.id || 'unknown');
  } catch {
    // The module remains usable even if context is unavailable.
  }
  return `${QUESTION_CACHE_PREFIX}${subjectId}`;
}

function loadQuestionCache() {
  try {
    const stored = JSON.parse(localStorage.getItem(subjectCacheKey()) || 'null');
    if (stored?.version === QUESTION_CACHE_VERSION && stored.entries && typeof stored.entries === 'object') {
      questionCache = stored;
      return;
    }
  } catch {
    // Invalid or unavailable local storage starts with an empty cache.
  }
  questionCache = { version: QUESTION_CACHE_VERSION, entries: {} };
}

function saveQuestionCache() {
  try {
    localStorage.setItem(subjectCacheKey(), JSON.stringify(questionCache));
  } catch {
    generationError = 'No se pudo guardar el progreso de preguntas.';
  }
}

function cachedQuestion(card) {
  const entry = questionCache.entries[card?.key];
  if (!entry || entry.source !== card.source) return null;
  if (!Array.isArray(entry.options) || entry.options.length !== 4) return null;
  if (entry.options[3] !== NONE_OPTION || !Number.isInteger(entry.correct) || entry.correct < 0 || entry.correct > 3) return null;
  return entry;
}

function questionBatchStart(index) {
  return Math.floor(Math.max(0, index) / QUESTION_BATCH_SIZE) * QUESTION_BATCH_SIZE;
}

function setView(view) {
  flashcard.classList.toggle('is-hidden', view !== 'card');
  questionCard.classList.toggle('is-hidden', view !== 'question');
  dayCard.classList.toggle('is-hidden', view !== 'day');
  loadingCard.classList.toggle('is-hidden', view !== 'loading');
}

function renderCard() {
  flashcard.classList.remove('is-flipped');
  if (!cards.length) return;
  document.getElementById('flashcard-english').textContent = cards[cardIndex].english;
  document.getElementById('flashcard-spanish').textContent = cards[cardIndex].spanish;
}

function renderQuestion() {
  const card = questionCards[questionIndex];
  const question = cachedQuestion(card);
  if (!card || !question) return false;

  questionPromptText.textContent = `¿Cuál es la traducción de “${card.english}”?`;
  questionStatus.textContent = generationError;
  retryQuestions.classList.toggle('is-hidden', !generationError);
  questionOptions.replaceChildren();
  const selected = questionAnswers.get(card.key);

  question.options.forEach((option, index) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'question-option';
    button.textContent = option;
    if (selected !== undefined) {
      button.disabled = true;
      if (index === question.correct) button.classList.add('is-correct');
      else if (index === selected) button.classList.add('is-wrong');
    }
    button.addEventListener('click', () => answerQuestion(index));
    questionOptions.append(button);
  });

  if (!generationError && (questionIndex % QUESTION_BATCH_SIZE) + 1 === QUESTION_PREFETCH_POSITION) {
    void generateQuestionBatch(questionBatchStart(questionIndex) + QUESTION_BATCH_SIZE, true);
  }
  return true;
}

function answerQuestion(selectedIndex) {
  const card = questionCards[questionIndex];
  if (!card || questionAnswers.has(card.key)) return;
  questionAnswers.set(card.key, selectedIndex);
  renderQuestion();
}

function cleanJsonOutput(output) {
  return String(output || '').replace(/```json/gi, '').replace(/```/g, '').trim();
}

function normalizeAnswer(value) {
  return String(value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase('es');
}

function validateGeneratedItem(item, requested) {
  if (!item || typeof item !== 'object' || !requested.has(item.id)) return null;
  if (!Array.isArray(item.opciones) || item.opciones.length !== 4) return null;
  const options = item.opciones.map(option => typeof option === 'string' ? option.trim() : '');
  if (options.some(option => !option || option === NONE_OPTION)) return null;
  if (new Set(options.map(normalizeAnswer)).size !== 4) return null;
  if (!Number.isInteger(item.correcta) || item.correcta < 0 || item.correcta > 3) return null;
  const expected = normalizeAnswer(requested.get(item.id).spanish);
  const expectedIndex = options.map(normalizeAnswer).indexOf(expected);
  if (expectedIndex !== item.correcta) return null;
  return { id: item.id, candidates: options, correct: item.correcta };
}

function prepareDisplayedQuestion(item) {
  const removedIndex = Math.floor(Math.random() * item.candidates.length);
  const removedValue = item.candidates[removedIndex];
  const remaining = item.candidates.filter((_, index) => index !== removedIndex);
  const correct = removedIndex === item.correct
    ? 3
    : remaining.findIndex(option => normalizeAnswer(option) === normalizeAnswer(item.candidates[item.correct]));
  return {
    options: [...remaining, NONE_OPTION],
    correct,
    removed: { index: removedIndex, value: removedValue, wasCorrect: removedIndex === item.correct },
  };
}

function pendingCardsFrom(startIndex) {
  const pending = [];
  const included = new Set();
  const normalizedStart = questionBatchStart(startIndex);
  const end = Math.min(questionCards.length, normalizedStart + QUESTION_BATCH_SIZE);
  for (let index = normalizedStart; index < end; index += 1) {
    const card = questionCards[index];
    if (included.has(card.key) || cachedQuestion(card) || generatingKeys.has(card.key)) continue;
    included.add(card.key);
    pending.push(card);
  }
  return pending;
}

async function generateQuestionBatch(startIndex, background = false) {
  if (generationPromise) return generationPromise;
  const pending = pendingCardsFrom(startIndex);
  if (!pending.length) return true;

  pending.forEach(card => generatingKeys.add(card.key));
  generationError = '';
  const requested = new Map(pending.map(card => [card.key, card]));
  const content = JSON.stringify(pending.map(card => ({ id: card.key, linea: card.source })));

  generationPromise = (async () => {
    try {
      const result = await window.InScreen.module.consulta(questionPrompt, content);
      if (!result?.ok) throw new Error(result?.error || 'No se pudieron generar las preguntas.');
      const parsed = JSON.parse(cleanJsonOutput(result.contenido));
      const items = Array.isArray(parsed) ? parsed : parsed?.preguntas;
      if (!Array.isArray(items)) throw new Error('La respuesta no contiene un lote de preguntas válido.');

      let saved = 0;
      for (const rawItem of items) {
        const item = validateGeneratedItem(rawItem, requested);
        if (!item) continue;
        const card = requested.get(item.id);
        const displayed = prepareDisplayedQuestion(item);
        questionCache.entries[item.id] = {
          source: card.source,
          options: displayed.options,
          correct: displayed.correct,
          removed: displayed.removed,
          generatedAt: new Date().toISOString(),
        };
        requested.delete(item.id);
        saved += 1;
        saveQuestionCache();
      }
      if (!saved) throw new Error('Groq no devolvió preguntas válidas para este lote.');
      if (requested.size) generationError = 'Faltan preguntas del lote. Tocá para reintentar.';
      else generationError = '';
      return requested.size === 0;
    } catch (error) {
      generationError = error?.message || 'No se pudieron generar las preguntas.';
      if (!background && questionMode) showQuestionFailure();
      return false;
    } finally {
      pending.forEach(card => generatingKeys.delete(card.key));
      generationPromise = null;
      if (questionMode && cachedQuestion(questionCards[questionIndex])) {
        setView('question');
        renderQuestion();
      }
    }
  })();

  return generationPromise;
}

function showQuestionFailure() {
  setView('question');
  questionPromptText.textContent = 'No se pudo cargar esta pregunta.';
  questionOptions.replaceChildren();
  questionStatus.textContent = generationError;
  retryQuestions.classList.remove('is-hidden');
}

async function ensureQuestion(index, enterDirection = 0) {
  const card = questionCards[index];
  if (!card) return false;
  if (!cachedQuestion(card)) {
    loadingText.textContent = 'Generando preguntas…';
    setView('loading');
    await generateQuestionBatch(questionBatchStart(index));
  }
  if (!cachedQuestion(card)) {
    showQuestionFailure();
    return false;
  }
  setView('question');
  renderQuestion();
  if (enterDirection) animateEntering(questionCard, enterDirection);
  return true;
}

function animateEntering(element, direction) {
  element.classList.remove('entering-right', 'entering-left', 'leaving-right', 'leaving-left');
  element.classList.add('is-positioning');
  element.classList.add(direction > 0 ? 'entering-right' : 'entering-left');
  void element.offsetWidth;
  element.classList.remove('is-positioning');
  requestAnimationFrame(() => requestAnimationFrame(() => {
    element.classList.remove('entering-right', 'entering-left');
  }));
}

function loadFile(index) {
  const file = historyFiles[index];
  if (!file) return;
  const parsed = parseCards(file);
  if (!parsed.length) {
    openErrorOverlay(`No se pudo abrir ${file.nombre}.`, new Error('El TXT no contiene líneas válidas con formato inglés:español.'));
    return;
  }
  currentFileIndex = index;
  cards = shuffle(parsed);
  questionCards = parsed;
  cardIndex = 0;
  questionIndex = 0;
  questionMode = false;
  generationError = '';
  questionAnswers.clear();
  dayNotice.textContent = '';
  setView('card');
  renderCard();
}

function configureDayCard(message) {
  previousDay.disabled = currentFileIndex <= 0;
  const hasLocalNext = currentFileIndex >= 0 && currentFileIndex + 1 < historyFiles.length;
  const currentId = historyFiles[currentFileIndex]?.id || null;
  nextDay.disabled = !hasLocalNext && unavailableNextFileId === currentId;
  dayNotice.textContent = message;
}

function showDayCard(message = '', animate = false, source = flashcard) {
  configureDayCard(message);
  flashcard.classList.remove('is-flipped');
  if (!animate) {
    setView('day');
    return;
  }
  source.classList.toggle('is-sliding', source === flashcard);
  source.classList.add('leaving-left');
  window.setTimeout(() => {
    source.classList.remove('leaving-left', 'is-sliding');
    setView('day');
    animateEntering(dayCard, 1);
  }, 180);
}

function moveCard(direction) {
  if (!cards.length) return;
  if (direction < 0 && cardIndex === 0) return;
  if (direction > 0 && cardIndex === cards.length - 1) {
    showDayCard('', true, flashcard);
    return;
  }
  flashcard.classList.remove('is-flipped');
  flashcard.classList.add('is-sliding');
  flashcard.classList.add(direction > 0 ? 'leaving-left' : 'leaving-right');
  window.setTimeout(() => {
    cardIndex += direction;
    renderCard();
    flashcard.classList.remove('leaving-left', 'leaving-right');
    animateEntering(flashcard, direction);
    window.setTimeout(() => flashcard.classList.remove('is-sliding'), 200);
  }, 180);
}

function moveQuestion(direction) {
  if (questionAnimating || !questionCards.length) return;
  if (direction < 0 && questionIndex === 0) return;
  if (direction > 0 && questionIndex === questionCards.length - 1) {
    showDayCard('', true, questionCard);
    return;
  }
  questionAnimating = true;
  questionCard.classList.add(direction > 0 ? 'leaving-left' : 'leaving-right');
  window.setTimeout(async () => {
    questionCard.classList.remove('leaving-left', 'leaving-right');
    questionIndex += direction;
    await ensureQuestion(questionIndex, direction);
    questionAnimating = false;
  }, 180);
}

async function enterQuestionMode() {
  if (questionMode || !questionCards.length) return;
  questionMode = true;
  await ensureQuestion(questionIndex);
}

function leaveQuestionMode() {
  questionMode = false;
  setView('card');
  renderCard();
}

function toggleQuestionMode() {
  if (questionMode) leaveQuestionMode();
  else void enterQuestionMode();
}

function moveActive(direction) {
  if (questionMode) moveQuestion(direction);
  else moveCard(direction);
}

async function readHistory() {
  const result = await window.InScreen.module.historial(true);
  if (!result?.ok || !Array.isArray(result.archivos)) throw new Error(result?.error || 'No se pudo leer el historial local.');
  historyFiles = result.archivos;
}

async function requestMore(lastFile) {
  const result = await window.InScreen.module.traduccion(lastFile || false);
  if (!result?.ok) throw new Error(result?.error || 'No se pudieron pedir nuevas traducciones.');
  return result;
}

async function initialize() {
  try {
    if (!window.InScreen?.module?.historial) throw new Error('Actualizá InScreen para usar el historial de tarjetas.');
    loadQuestionCache();
    await readHistory();
    if (!historyFiles.length) {
      await requestMore(false);
      await readHistory();
    }
    if (!historyFiles.length) {
      currentFileIndex = -1;
      showDayCard('Sigue leyendo para más 👍');
      return;
    }
    loadFile(historyFiles.length - 1);
  } catch (error) {
    showDayCard();
    openErrorOverlay('No se pudo iniciar el módulo.', error);
  }
}

flashcard.addEventListener('click', () => {
  if (didSwipe) {
    didSwipe = false;
    return;
  }
  flashcard.classList.toggle('is-flipped');
});

flashcard.addEventListener('keydown', event => {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    flashcard.classList.toggle('is-flipped');
  }
  if (event.key === 'ArrowRight') moveCard(1);
  if (event.key === 'ArrowLeft') moveCard(-1);
});

function clearQuestionHold() {
  if (questionHoldTimer !== null) window.clearTimeout(questionHoldTimer);
  questionHoldTimer = null;
}

document.body.addEventListener('pointerdown', event => {
  if (event.target !== document.body && !event.target.classList.contains('bg-blob')) return;
  clearQuestionHold();
  questionHoldTimer = window.setTimeout(toggleQuestionMode, 800);
});
document.body.addEventListener('pointerup', clearQuestionHold);
document.body.addEventListener('pointercancel', clearQuestionHold);

cardStage.addEventListener('pointerdown', event => {
  pointerStartX = event.clientX;
  cardStage.setPointerCapture(event.pointerId);
});
cardStage.addEventListener('pointerup', event => {
  if (pointerStartX === null) return;
  const delta = event.clientX - pointerStartX;
  pointerStartX = null;
  if (Math.abs(delta) <= 50 || !dayCard.classList.contains('is-hidden')) return;
  if (!questionMode) didSwipe = true;
  moveActive(delta < 0 ? 1 : -1);
});
cardStage.addEventListener('pointercancel', () => {
  pointerStartX = null;
});

retryQuestions.addEventListener('click', async () => {
  generationError = '';
  await generateQuestionBatch(questionBatchStart(questionIndex));
  await ensureQuestion(questionIndex);
});
previousDay.addEventListener('click', () => {
  if (currentFileIndex > 0) loadFile(currentFileIndex - 1);
});
retryDay.addEventListener('click', () => loadFile(currentFileIndex));
nextDay.addEventListener('click', async () => {
  if (currentFileIndex + 1 < historyFiles.length) {
    loadFile(currentFileIndex + 1);
    return;
  }
  const currentId = historyFiles[currentFileIndex]?.id;
  nextDay.disabled = true;
  dayNotice.textContent = 'Buscando un nuevo día…';
  try {
    const result = await requestMore(historyFiles[currentFileIndex]?.nombre || false);
    await readHistory();
    const refreshedIndex = historyFiles.findIndex(file => file.id === currentId);
    currentFileIndex = refreshedIndex >= 0 ? refreshedIndex : Math.max(0, historyFiles.length - 1);
    if ((result.nuevos || 0) > 0 && currentFileIndex + 1 < historyFiles.length) loadFile(currentFileIndex + 1);
    else {
      unavailableNextFileId = historyFiles[currentFileIndex]?.id || null;
      showDayCard('Sigue leyendo para más 👍');
    }
  } catch (error) {
    showDayCard();
    openErrorOverlay('No se pudo buscar el siguiente día.', error);
  }
});

initialize();
