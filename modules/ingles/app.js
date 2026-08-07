let historyFiles = [];
let currentFileIndex = -1;
let cards = [];
let cardIndex = 0;
let pointerStartX = null;
let didSwipe = false;

const flashcard = document.getElementById('flashcard');
const dayCard = document.getElementById('dayCard');
const loadingCard = document.getElementById('loadingCard');
const deckControls = document.getElementById('deckControls');
const previousCard = document.getElementById('previousCard');
const nextCard = document.getElementById('nextCard');
const previousDay = document.getElementById('previousDay');
const nextDay = document.getElementById('nextDay');
const dayNotice = document.getElementById('dayNotice');

function openErrorOverlay(context, error) {
  document.getElementById('errorDetailsText').textContent = `${context}\n\n${error?.message || String(error)}`;
  document.getElementById('errorOverlay').classList.add('active');
}
function closeErrorOverlay() { document.getElementById('errorOverlay').classList.remove('active'); }

function shuffle(values) {
  const result = [...values];
  for (let index = result.length - 1; index > 0; index -= 1) {
    const target = Math.floor(Math.random() * (index + 1));
    [result[index], result[target]] = [result[target], result[index]];
  }
  return result;
}

function parseCards(file) {
  return (file.contenido || '').split(/\r?\n/).flatMap((line, lineIndex) => {
    const separator = line.indexOf(':');
    if (separator < 1) return [];
    const english = line.slice(0, separator).trim();
    const spanish = line.slice(separator + 1).trim();
    return english && spanish ? [{ english, spanish, line: lineIndex + 1 }] : [];
  });
}

function setView(view) {
  flashcard.classList.toggle('is-hidden', view !== 'card');
  dayCard.classList.toggle('is-hidden', view !== 'day');
  loadingCard.classList.toggle('is-hidden', view !== 'loading');
  deckControls.classList.toggle('is-hidden', view !== 'card');
}

function renderCard() {
  const current = historyFiles[currentFileIndex];
  flashcard.classList.remove('is-flipped');
  document.getElementById('fileName').textContent = current?.nombre || 'Sin TXT';
  document.getElementById('cardCounter').textContent = cards.length ? `${cardIndex + 1} / ${cards.length}` : '0 / 0';
  if (!cards.length) return;
  document.getElementById('flashcard-english').textContent = cards[cardIndex].english;
  document.getElementById('flashcard-spanish').textContent = cards[cardIndex].spanish;
  previousCard.disabled = cardIndex === 0;
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
  cardIndex = 0;
  dayNotice.textContent = '';
  setView('card');
  renderCard();
}

function showDayCard(message = '') {
  const file = historyFiles[currentFileIndex];
  flashcard.classList.remove('is-flipped');
  document.getElementById('dayNumber').textContent = file?.numero || Number.parseInt(file?.nombre, 10) || '—';
  document.getElementById('fileName').textContent = file?.nombre || 'Sin TXT';
  document.getElementById('cardCounter').textContent = 'Completado';
  previousDay.disabled = currentFileIndex <= 0;
  nextDay.disabled = false;
  dayNotice.textContent = message;
  setView('day');
}

function moveCard(direction) {
  if (!cards.length) return;
  if (direction < 0 && cardIndex === 0) return;
  if (direction > 0 && cardIndex === cards.length - 1) { showDayCard(); return; }
  flashcard.classList.remove('is-flipped');
  flashcard.classList.add(direction > 0 ? 'leaving-left' : 'leaving-right');
  window.setTimeout(() => {
    cardIndex += direction;
    renderCard();
    flashcard.classList.remove('leaving-left', 'leaving-right');
    flashcard.classList.add(direction > 0 ? 'entering-right' : 'entering-left');
    requestAnimationFrame(() => flashcard.classList.remove('entering-right', 'entering-left'));
  }, 180);
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
    await readHistory();
    if (!historyFiles.length) {
      await requestMore(false);
      await readHistory();
    }
    if (!historyFiles.length) { currentFileIndex = -1; showDayCard('Sigue leyendo para más 👍'); return; }
    loadFile(historyFiles.length - 1);
  } catch (error) {
    setView('day');
    showDayCard();
    openErrorOverlay('No se pudo iniciar el módulo.', error);
  }
}

flashcard.addEventListener('click', () => {
  if (didSwipe) { didSwipe = false; return; }
  flashcard.classList.toggle('is-flipped');
});
flashcard.addEventListener('keydown', event => {
  if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); flashcard.classList.toggle('is-flipped'); }
  if (event.key === 'ArrowRight') moveCard(1);
  if (event.key === 'ArrowLeft') moveCard(-1);
});
flashcard.addEventListener('pointerdown', event => { pointerStartX = event.clientX; flashcard.setPointerCapture(event.pointerId); });
flashcard.addEventListener('pointerup', event => {
  if (pointerStartX === null) return;
  const delta = event.clientX - pointerStartX; pointerStartX = null;
  if (Math.abs(delta) > 50) { didSwipe = true; moveCard(delta < 0 ? 1 : -1); }
});
previousCard.addEventListener('click', () => moveCard(-1));
nextCard.addEventListener('click', () => moveCard(1));
previousDay.addEventListener('click', () => { if (currentFileIndex > 0) loadFile(currentFileIndex - 1); });
nextDay.addEventListener('click', async () => {
  if (currentFileIndex + 1 < historyFiles.length) { loadFile(currentFileIndex + 1); return; }
  const currentId = historyFiles[currentFileIndex]?.id;
  nextDay.disabled = true; dayNotice.textContent = 'Buscando un nuevo día…';
  try {
    const result = await requestMore(historyFiles[currentFileIndex]?.nombre || false);
    await readHistory();
    const refreshedIndex = historyFiles.findIndex(file => file.id === currentId);
    currentFileIndex = refreshedIndex >= 0 ? refreshedIndex : Math.max(0, historyFiles.length - 1);
    if ((result.nuevos || 0) > 0 && currentFileIndex + 1 < historyFiles.length) loadFile(currentFileIndex + 1);
    else showDayCard('Sigue leyendo para más 👍');
  } catch (error) {
    showDayCard();
    openErrorOverlay('No se pudo buscar el siguiente día.', error);
  }
});

initialize();
