let cards = [];
let cardIndex = 0;
let pointerStartX = null;
let didSwipe = false;

function openErrorOverlay(context, error) {
  document.getElementById('errorDetailsText').textContent = `${context}\n\n${error?.message || String(error)}`;
  document.getElementById('errorOverlay').classList.add('active');
}
function closeErrorOverlay() { document.getElementById('errorOverlay').classList.remove('active'); }
function showScreen(screenId) {
  document.querySelectorAll('.screen').forEach(screen => screen.classList.remove('active'));
  document.getElementById(screenId).classList.add('active');
  document.getElementById('back-button-wrapper').style.display = screenId === 'screen-main' ? 'none' : 'block';
}

const gearBtn = document.getElementById('gearBtn');
const settingsPanel = document.getElementById('settingsPanel');
const modeSwitch = document.getElementById('modeSwitch');
const modeLabel = document.getElementById('modeLabel');
const confirmBtn = document.getElementById('confirmBtn');
const statusMsg = document.getElementById('statusMsg');
const flashcard = document.getElementById('flashcard');

gearBtn.addEventListener('click', () => settingsPanel.classList.toggle('active'));
modeSwitch.addEventListener('change', () => { modeLabel.textContent = modeSwitch.checked ? 'Traducción (tarjetas)' : 'Página (preguntas IA)'; });
function showStatus(message, error = false) { statusMsg.textContent = message; statusMsg.className = `status-msg ${error ? 'status-error' : 'status-success'}`; statusMsg.style.display = 'block'; }
function cleanJsonOutput(output) { return output.replace(/```json/gi, '').replace(/```/g, '').trim(); }

// Cada línea válida de todos los TXT se convierte en una tarjeta. Solo se divide en el primer ':' para permitir dos puntos en la traducción.
function parseTranslationFiles(files) {
  const parsed = [];
  files.forEach(file => (file.contenido || '').split(/\r?\n/).forEach((line, lineIndex) => {
    const separator = line.indexOf(':');
    if (separator < 1) return;
    const english = line.slice(0, separator).trim();
    const spanish = line.slice(separator + 1).trim();
    if (english && spanish) parsed.push({ english, spanish, source: `${file.nombre || 'TXT'}, línea ${lineIndex + 1}` });
  }));
  return parsed;
}
function renderCard() {
  const count = cards.length;
  document.getElementById('cardCounter').textContent = count ? `${cardIndex + 1} / ${count}` : '0 / 0';
  flashcard.classList.remove('is-flipped');
  if (!count) return;
  document.getElementById('flashcard-english').textContent = cards[cardIndex].english;
  document.getElementById('flashcard-spanish').textContent = cards[cardIndex].spanish;
}
function moveCard(direction) {
  if (cards.length < 2) return;
  flashcard.classList.remove('is-flipped');
  flashcard.classList.add(direction > 0 ? 'leaving-left' : 'leaving-right');
  window.setTimeout(() => {
    cardIndex = (cardIndex + direction + cards.length) % cards.length;
    renderCard();
    flashcard.classList.remove('leaving-left', 'leaving-right');
    flashcard.classList.add(direction > 0 ? 'entering-right' : 'entering-left');
    requestAnimationFrame(() => flashcard.classList.remove('entering-right', 'entering-left'));
  }, 180);
}
flashcard.addEventListener('click', () => {
  if (didSwipe) { didSwipe = false; return; }
  flashcard.classList.toggle('is-flipped');
});
flashcard.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); flashcard.classList.toggle('is-flipped'); } });
flashcard.addEventListener('pointerdown', event => { pointerStartX = event.clientX; flashcard.setPointerCapture(event.pointerId); });
flashcard.addEventListener('pointerup', event => { if (pointerStartX === null) return; const delta = event.clientX - pointerStartX; pointerStartX = null; if (Math.abs(delta) > 50) { didSwipe = true; moveCard(delta < 0 ? 1 : -1); } });
document.getElementById('previousCard').addEventListener('click', () => moveCard(-1));
document.getElementById('nextCard').addEventListener('click', () => moveCard(1));

confirmBtn.addEventListener('click', async () => {
  const day = Number.parseInt(document.getElementById('dayInput').value, 10) || 0;
  const translationMode = modeSwitch.checked;
  const promptExtra = document.getElementById('promptInput').value.trim().slice(0, 10000);
  confirmBtn.disabled = true; statusMsg.style.display = 'none';
  try {
    if (!window.InScreen?.module) throw new Error('Esta función está disponible dentro de InScreen.');
    const api = window.InScreen.module;
    confirmBtn.textContent = 'Obteniendo archivos…';
    const result = translationMode ? await api.traduccion(day) : await api.paginasLeidas(day);
    if (!result?.ok || !Array.isArray(result.archivos)) throw new Error(result?.error || 'No se pudieron obtener los archivos.');
    if (translationMode) {
      cards = parseTranslationFiles(result.archivos); cardIndex = 0;
      if (!cards.length) throw new Error('No encontré líneas con formato inglés:español en los TXT recibidos.');
      renderCard(); showScreen('screen-tarjetas'); showStatus(`${cards.length} tarjetas cargadas.`);
    } else {
      const material = result.archivos.map(file => file.contenido || '').join('\n').slice(0, 200000);
      if (!material.trim()) throw new Error('Los archivos recibidos están vacíos.');
      confirmBtn.textContent = 'Generando pregunta…';
      const prompt = `${promptExtra ? `Instrucción adicional: ${promptExtra}\n\n` : ''}Basándote en el contenido, elaborá un enunciado y 4 opciones. La cuarta opción debe ser literalmente "Ninguna de las anteriores". Devolvé únicamente JSON: {"enunciado":"pregunta", "opciones":["A","B","C","Ninguna de las anteriores"], "correcta":1}`;
      const response = await api.consulta(prompt, material);
      if (!response?.ok) throw new Error(response?.error || 'La IA no pudo generar la pregunta.');
      const quiz = JSON.parse(cleanJsonOutput(response.contenido));
      if (!quiz.enunciado || !Array.isArray(quiz.opciones)) throw new Error('La respuesta de la IA no tiene el formato esperado.');
      document.getElementById('choi-enunciado').textContent = quiz.enunciado;
      quiz.opciones.slice(0, 4).forEach((option, index) => { const el = document.getElementById(`choi-op-${index}`); if (el) el.textContent = option; });
      document.querySelectorAll('input[name="pregunta"]').forEach(input => { input.checked = false; });
      showScreen('screen-preguntas'); showStatus('Pregunta generada.');
    }
    window.setTimeout(() => settingsPanel.classList.remove('active'), 700);
  } catch (error) { console.error(error); showStatus(error.message || 'Ocurrió un error.', true); openErrorOverlay('No se pudo procesar el material.', error); }
  finally { confirmBtn.disabled = false; confirmBtn.textContent = 'Cargar material'; }
});
