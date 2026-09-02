'use strict';
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const edge = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const root = path.resolve(__dirname, '../..');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'sintesis-smoke-'));
const port = 9800 + Math.floor(Math.random() * 100);
const debugPort = 9900 + Math.floor(Math.random() * 100);
let browser;
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.woff2': 'font/woff2' };
const server = http.createServer((request, response) => {
  const url = decodeURIComponent(request.url.split('?')[0]);
  let file;
  if (url.startsWith('/app-assets/')) file = path.join(root, 'web', url.slice('/app-assets/'.length));
  else if (url === '/app-res/drawable/synthesis_plaque.png') file = path.join(root, 'mobile_android/app/src/main/res/drawable-nodpi/synthesis_plaque.png');
  else file = path.join(__dirname, url === '/' ? 'index.html' : url.slice(1));
  if (!file.startsWith(root) || !fs.existsSync(file)) return response.writeHead(404).end();
  response.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream');
  fs.createReadStream(file).pipe(response);
});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function main() {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  const pageUrl = `http://127.0.0.1:${port}/`;
  browser = spawn(edge, ['--headless=new', '--disable-gpu', `--remote-debugging-port=${debugPort}`, `--user-data-dir=${profile}`, '--no-first-run', '--window-size=480,760', pageUrl], { stdio: 'ignore', windowsHide: true });
  let page;
  for (let attempt = 0; attempt < 60 && !page; attempt++) {
    try { page = (await (await fetch(`http://127.0.0.1:${debugPort}/json`)).json()).find(item => item.type === 'page'); } catch (_) {}
    if (!page) await delay(100);
  }
  if (!page) throw new Error('No se abrió Síntesis');
  const socket = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let sequence = 0;
  const pending = new Map();
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data); const task = pending.get(message.id); if (!task) return;
    pending.delete(message.id); message.error ? task.reject(new Error(message.error.message)) : task.resolve(message.result);
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })); });
  const evaluate = async expression => (await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })).result.value;
  await send('Page.enable');
  const clipboardText = '# Explicación [1]\n\n- Punto **importante** [5,7]\n\n| Tema | Valor |\n| --- | --- |\n| Fórmula | $$x^2$$ |\n\n```js\nconst seguro = true;\n```';
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `window.__copied='';window.__clipboard=${JSON.stringify(clipboardText)};window.InScreen={module:{portapapeles:async()=>({ok:true,texto:window.__clipboard}),escribirPortapapeles:async text=>(window.__copied=text,{ok:true})}};` });
  await send('Page.reload');
  await delay(600);
  await evaluate(`localStorage.clear();location.reload()`);
  await delay(600);
  if (!await evaluate(`document.querySelector('#board').scrollHeight>document.querySelector('#treeView').clientHeight`)) throw new Error('El tablero no ofrece desplazamiento vertical');
  const hold = async (selector, x, y) => {
    await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});e.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:1,clientX:${x},clientY:${y}}))})()`);
    await delay(620);
    await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});e.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:1,clientX:${x},clientY:${y}}))})()`);
  };
  const nameDraft = async name => {
    const found = await evaluate(`Boolean(document.querySelector('.draft-plaque input'))`); if (!found) throw new Error('No se creó el editor');
    await evaluate(`(()=>{const e=document.querySelector('.draft-plaque input');e.value=${JSON.stringify(name)};e.dispatchEvent(new Event('blur'))})()`); await delay(80);
  };
  await hold('#board', 250, 280);
  await evaluate(`window.dispatchEvent(new Event('resize'))`); await delay(120);
  if (!await evaluate(`Boolean(document.querySelector('.draft-plaque input'))`)) throw new Error('El teclado cerró el editor del elemento nuevo');
  await nameDraft('Modelos con Ecuaciones Diferenciales No Lineales');
  if (!await evaluate(`(()=>{const e=document.querySelector('.node-plaque');return e.scrollHeight<=e.clientHeight&&e.scrollWidth<=e.clientWidth})()`)) throw new Error('El nombre largo desborda la placa');
  if (await evaluate(`parseFloat(getComputedStyle(document.querySelector('.node-plaque')).fontSize)`) < 14) throw new Error('Se redujo la tipografía por debajo del mínimo legible');
  if (!await evaluate(`(()=>{const e=document.querySelector('.node-plaque'),t=e.firstChild,s=t.data.indexOf('Diferenciales'),r=new Range();r.setStart(t,s);r.setEnd(t,s+'Diferenciales'.length);return r.getClientRects().length===1})()`)) throw new Error('Una palabra larga se cortó entre líneas');
  if (!await evaluate(`document.querySelector('.node-plaque').offsetHeight/document.querySelector('.node-plaque').offsetWidth>.64`)) throw new Error('La placa no conserva la proporción 3:2');
  await evaluate(`(()=>{const e=document.querySelector('.node-plaque'),r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;e.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:20,clientX:x,clientY:y}));e.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,button:0,pointerId:20,clientX:x+45,clientY:y+35}));e.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:20,clientX:x+45,clientY:y+35}))})()`); await delay(50);
  if (!await evaluate(`JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes[Object.keys(JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes)[0]].x>.5`)) throw new Error('No se guardó el arrastre');
  const firstDragX = await evaluate(`JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes[Object.keys(JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes)[0]].x`);
  await evaluate(`(()=>{const e=document.querySelector('.node-plaque'),r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;e.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:23,clientX:x,clientY:y}));e.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,button:0,pointerId:23,clientX:x+25,clientY:y}));e.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:23,clientX:x+25,clientY:y}))})()`); await delay(50);
  const secondDragX = await evaluate(`JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes[Object.keys(JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes)[0]].x`);
  if (secondDragX <= firstDragX) throw new Error('El segundo arrastre no partió desde la posición guardada');
  await evaluate(`(()=>{const e=document.querySelector('.node-plaque'),r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;e.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:21,clientX:x-20,clientY:y}));e.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:22,clientX:x+20,clientY:y}));e.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,button:0,pointerId:21,clientX:x-35,clientY:y}));e.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,button:0,pointerId:22,clientX:x+35,clientY:y}));e.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:21,clientX:x-35,clientY:y}));e.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:22,clientX:x+35,clientY:y}))})()`); await delay(50);
  if (!await evaluate(`JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).defaultScale>1`)) throw new Error('No se guardó la escala por pellizco');
  if (!await evaluate(`(()=>{const e=document.querySelector('.node-plaque');return e.scrollHeight<=e.clientHeight&&e.scrollWidth<=e.clientWidth})()`)) throw new Error('El texto escalado desborda la placa');
  await evaluate(`(()=>{const e=document.querySelector('.node-plaque'),r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;e.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:24,clientX:x,clientY:y}));e.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,button:0,pointerId:24,clientX:x-1000,clientY:y}));e.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:24,clientX:x-1000,clientY:y}))})()`); await delay(50);
  if (!await evaluate(`(()=>{const r=document.querySelector('.node-plaque').getBoundingClientRect();return Math.abs((r.left+r.right)/2)<2&&r.right>0})()`)) throw new Error('No se permite dejar media placa fuera del borde');
  await evaluate(`document.querySelector('.node-plaque').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:2,clientX:250,clientY:280}));document.querySelector('.node-plaque').dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:2,clientX:250,clientY:280}))`);
  if (await evaluate(`document.querySelector('#treeHeader .back-plaque').textContent`) !== '←') throw new Error('La navegación no usa el elemento de regreso compacto');
  if (!await evaluate(`document.querySelector('#treeHeader .back-plaque').getAttribute('aria-label').includes('Modelos con Ecuaciones Diferenciales No Lineales')`)) throw new Error('El regreso perdió el nombre accesible completo');
  await evaluate(`window.__clipboard='{"temas":[{"nombre":"TAP","subtemas":["Listas Enlazadas"]}]}'`);
  await evaluate(`document.querySelector('#outlineButton').click()`); await delay(100);
  if (await evaluate(`Object.keys(JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes).length`) !== 3) throw new Error('No se importó el esquema JSON');
  await evaluate(`document.querySelector('.node-plaque').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:31,clientX:90,clientY:155}))`);
  await delay(620);
  if (!await evaluate(`document.querySelector('#sheetView').hidden`)) throw new Error('La hoja apareció mientras la pulsación seguía activa');
  await evaluate(`document.querySelector('.node-plaque').dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:31,clientX:90,clientY:155}))`); await delay(80);
  if (!await evaluate(`!document.querySelector('#sheetView').hidden`)) throw new Error('No se abrió la hoja');
  if (!await evaluate(`document.querySelector('#sheetBack .back-plaque')?.textContent==='←'`)) throw new Error('La hoja no muestra el elemento de regreso compacto');
  await evaluate(`window.__clipboard=${JSON.stringify(clipboardText)}`);
  await evaluate(`document.querySelector('#clipboardButton').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:3}));document.querySelector('#clipboardButton').dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:3}))`); await delay(100);
  if (!await evaluate(`document.querySelectorAll('#sheetContent .katex').length>0&&document.querySelectorAll('#sheetContent table').length===1&&document.querySelectorAll('#sheetContent pre code').length===1`)) throw new Error('Markdown o KaTeX no se representaron');
  if (await evaluate(`document.querySelector('#sheetContent').innerText.includes('[1]')||document.querySelector('#sheetContent').innerText.includes('[5,7]')`)) throw new Error('No se limpiaron las referencias de NotebookLM');
  await evaluate(`window.__clipboard='## Ampliación\\n\\nContenido adicional.';document.querySelector('#clipboardButton').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:4}));document.querySelector('#clipboardButton').dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:4}))`); await delay(80);
  if (!await evaluate(`document.querySelector('#sheetContent').innerText.includes('Explicación')&&document.querySelector('#sheetContent').innerText.includes('Ampliación')`)) throw new Error('No se agregó el contenido debajo');
  await hold('#sheetContent', 240, 300); await delay(80);
  if (!await evaluate(`Boolean(document.querySelector('.sheet-editor'))`)) throw new Error('No se abrió el editor por pulsación larga');
  await evaluate(`(()=>{const e=document.querySelector('.sheet-editor');e.value+='\\n\\nEdición manual';e.blur()})()`); await delay(80);
  if (!await evaluate(`document.querySelector('#sheetContent').innerText.includes('Edición manual')`)) throw new Error('No se guardó la edición al cerrar el teclado');
  await evaluate(`document.querySelector('#menuButton').click()`); await delay(30);
  if (await evaluate(`document.querySelectorAll('#menuOverlay .icon-action').length`) !== 2) throw new Error('El menú no contiene las dos acciones con iconos');
  await evaluate(`document.querySelector('#menuOverlay').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}))`);
  await hold('#clipboardButton', 430, 30); await delay(80);
  const result = await evaluate(`({copied:window.__copied,nodes:Object.keys(JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes).length,plaque:getComputedStyle(document.querySelector('.back-plaque')).backgroundImage})`);
  if (result.copied !== 'Modelos con Ecuaciones Diferenciales No Lineales, TAP' || result.nodes !== 3 || !result.plaque.includes('synthesis_plaque')) throw new Error(`Estado inesperado: ${JSON.stringify(result)}`);
  await send('Page.reload'); await delay(500);
  if (await evaluate(`Object.keys(JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes).length`) !== 3) throw new Error('El árbol no persistió al reabrir');
  socket.close();
  console.log('Síntesis browser smoke: OK');
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  if (browser) browser.kill();
  server.close();
  await delay(350);
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch (_) {}
});
