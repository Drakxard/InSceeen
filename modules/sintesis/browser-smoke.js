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
  const clipboardText = '# Explicación\n\n- Punto **importante**\n\n| Tema | Valor |\n| --- | --- |\n| Fórmula | $$x^2$$ |\n\n```js\nconst seguro = true;\n```';
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `window.__copied='';window.__clipboard=${JSON.stringify(clipboardText)};window.InScreen={module:{portapapeles:async()=>({ok:true,texto:window.__clipboard}),escribirPortapapeles:async text=>(window.__copied=text,{ok:true})}};` });
  await send('Page.reload');
  await delay(600);
  await evaluate(`localStorage.clear();location.reload()`);
  await delay(600);
  const hold = async (selector, x, y) => {
    await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});e.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:1,clientX:${x},clientY:${y}}))})()`);
    await delay(620);
    await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});e.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:1,clientX:${x},clientY:${y}}))})()`);
  };
  const nameDraft = async name => {
    const found = await evaluate(`Boolean(document.querySelector('.draft-plaque input'))`); if (!found) throw new Error('No se creó el editor');
    await evaluate(`(()=>{const e=document.querySelector('.draft-plaque input');e.value=${JSON.stringify(name)};e.dispatchEvent(new Event('blur'))})()`); await delay(80);
  };
  await hold('#board', 250, 280); await nameDraft('Prog3');
  await evaluate(`document.querySelector('.node-plaque').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:2,clientX:250,clientY:280}));document.querySelector('.node-plaque').dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:2,clientX:250,clientY:280}))`);
  await evaluate(`window.__clipboard='{"temas":[{"nombre":"TAP","subtemas":["Listas Enlazadas"]}]}'`);
  await evaluate(`document.querySelector('#outlineButton').click()`); await delay(100);
  if (await evaluate(`Object.keys(JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes).length`) !== 3) throw new Error('No se importó el esquema JSON');
  await hold('.node-plaque', 90, 155); await delay(80);
  if (!await evaluate(`!document.querySelector('#sheetView').hidden`)) throw new Error('No se abrió la hoja');
  await evaluate(`window.__clipboard=${JSON.stringify(clipboardText)}`);
  await evaluate(`document.querySelector('#clipboardButton').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:3}));document.querySelector('#clipboardButton').dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:3}))`); await delay(100);
  await evaluate(`document.querySelector('#replacePaste').click()`); await delay(100);
  if (!await evaluate(`document.querySelectorAll('#sheetContent .katex').length>0&&document.querySelectorAll('#sheetContent table').length===1&&document.querySelectorAll('#sheetContent pre code').length===1`)) throw new Error('Markdown o KaTeX no se representaron');
  await evaluate(`window.__clipboard='## Ampliación\\n\\nContenido adicional.';document.querySelector('#clipboardButton').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,pointerId:4}));document.querySelector('#clipboardButton').dispatchEvent(new PointerEvent('pointerup',{bubbles:true,button:0,pointerId:4}))`); await delay(80);
  await evaluate(`document.querySelector('#appendPaste').click()`); await delay(80);
  if (!await evaluate(`document.querySelector('#sheetContent').innerText.includes('Explicación')&&document.querySelector('#sheetContent').innerText.includes('Ampliación')`)) throw new Error('No se agregó el contenido debajo');
  await hold('#clipboardButton', 430, 30); await delay(80);
  const result = await evaluate(`({copied:window.__copied,nodes:Object.keys(JSON.parse(localStorage.getItem('inscreen.sintesis.tree.v1')).nodes).length,plaque:getComputedStyle(document.querySelector('.header-plaque')).backgroundImage})`);
  if (result.copied !== 'Prog3, TAP' || result.nodes !== 3 || !result.plaque.includes('synthesis_plaque')) throw new Error(`Estado inesperado: ${JSON.stringify(result)}`);
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
