const { spawn } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const edge = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const debugPort = 9700 + Math.floor(Math.random() * 200);
const webPort = 9900 + Math.floor(Math.random() * 80);
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'apuntes-smoke-'));
const root = path.resolve(__dirname, '..', 'modules', 'apuntes');
let browser;

const mock = `<script>
window.__mock={pages:[],queries:[],saves:0,voiceStarts:0};
const hash=(letter)=>letter.repeat(64);
window.InScreen={module:{
  apuntes:async()=>({ok:true,conjunto:{id:'session-1',createdAt:1},archivos:[
    {numero:1,nombre:'1.txt',id:'0001.jpg'},{numero:2,nombre:'2.txt',id:'0002.jpg'},{numero:3,nombre:'3.txt',id:'0003.jpg'}]}),
  apunte:async(number)=>{window.__mock.pages.push(number);return {ok:true,conjuntoId:'session-1',archivo:{
    numero:number,nombre:number+'.txt',id:'000'+number+'.jpg',hash:hash(number===1?'a':number===2?'b':'c'),contenido:'Página '+number}}},
  apuntesEstado:async()=>({ok:true,conjuntoId:'session-1',estado:JSON.parse(localStorage.getItem('mock-state')||'null')}),
  guardarApuntesEstado:async(state)=>{window.__mock.saves+=1;localStorage.setItem('mock-state',JSON.stringify(state));return {ok:true}},
  consulta:async(prompt,content)=>{window.__mock.queries.push({prompt,content});
    if(content==='Página 2')return {ok:true,contenido:'{"bloques":[]}'};
    if(content==='Página 3'&&window.__mock.queries.filter(item=>item.content==='Página 3').length===1)
      return {ok:true,contenido:'{"bloques":[{"orden":1,"cabecera":"Tema tres","respuesta":"no"}]}'};
    return {ok:true,contenido:content==='Página 1'
      ?'{"bloques":[{"orden":1,"cabecera":"Tema uno"},{"orden":2,"cabecera":"Tema uno B"}]}'
      :'{"bloques":[{"orden":1,"cabecera":"Tema tres"}]}' }},
  vozEstado:async()=>({ok:true,permiso:true,onDevice:true,servicioSistema:true,idioma:'es-AR'}),
  vozIniciar:async()=>{window.__mock.voiceStarts+=1;return {ok:true,onDevice:true}},
  vozDetener:async()=>({ok:true}),vozCancelar:async()=>({ok:true})
}};
</script>`;

const server = http.createServer((request, response) => {
  const relative = request.url === '/' ? 'index.html' : request.url.slice(1).split('?')[0];
  const target = path.resolve(root, relative);
  if (!target.startsWith(root) || !fs.existsSync(target)) return response.writeHead(404).end();
  const extension = path.extname(target);
  response.setHeader('Content-Type', extension === '.html' ? 'text/html; charset=utf-8' : extension === '.css' ? 'text/css' : 'text/javascript; charset=utf-8');
  if (relative === 'index.html') {
    const html = fs.readFileSync(target, 'utf8').replace('<script src="core.js"></script>', mock + '<script src="core.js"></script>');
    response.end(html);
  } else fs.createReadStream(target).pipe(response);
});

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

async function main() {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(webPort, '127.0.0.1', resolve); });
  const pageUrl = `http://127.0.0.1:${webPort}/`;
  browser = spawn(edge, ['--headless=new', '--disable-gpu', `--remote-debugging-port=${debugPort}`, `--user-data-dir=${profile}`, '--no-first-run', '--window-size=500,700', pageUrl], { stdio: 'ignore', windowsHide: true });
  let page;
  for (let attempt = 0; attempt < 60 && !page; attempt += 1) {
    try { page = (await (await fetch(`http://127.0.0.1:${debugPort}/json`)).json()).find(item => item.type === 'page' && item.url === pageUrl); } catch {}
    if (!page) await delay(100);
  }
  if (!page) throw new Error('No se abrió el navegador de prueba.');
  const socket = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let sequence = 0;
  const pending = new Map();
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data); const task = pending.get(message.id); if (!task) return;
    pending.delete(message.id); if (message.error) task.reject(new Error(message.error.message)); else task.resolve(message.result);
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })); });
  const evaluate = async expression => {
    const response = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (response.exceptionDetails) throw new Error(response.exceptionDetails.exception?.description || response.exceptionDetails.text);
    return response.result.value;
  };
  const waitFor = async (expression, message) => {
    for (let attempt = 0; attempt < 80; attempt += 1) { if (await evaluate(expression)) return; await delay(100); }
    const diagnostics = await evaluate(`({url:location.href,title:document.title,text:document.body?.innerText,html:document.documentElement?.outerHTML?.slice(0,300)})`);
    throw new Error(message + ' ' + JSON.stringify(diagnostics));
  };

  await waitFor(`document.querySelector('#cardHeader')?.textContent==='Tema uno'`, 'No apareció la primera tarjeta.');
  let state = await evaluate(`({pages:window.__mock.pages.slice(),queries:window.__mock.queries.length})`);
  if (JSON.stringify(state.pages) !== '[1]' || state.queries !== 1) throw new Error(`La carga no fue por demanda: ${JSON.stringify(state)}`);

  await evaluate(`document.querySelector('#nextCard').click();true`); await delay(500);
  state = await evaluate(`({title:document.querySelector('#cardHeader').textContent,pages:window.__mock.pages.slice()})`);
  if (state.title !== 'Tema uno B' || JSON.stringify(state.pages) !== '[1]') throw new Error('Se anticipó la página 2.');

  await evaluate(`document.querySelector('#nextCard').click();true`);
  await waitFor(`document.querySelector('#cardHeader').textContent==='Tema tres'`, 'No se saltó la página vacía.');
  state = await evaluate(`({pages:window.__mock.pages.slice(),empty:window.__mock.queries.filter(x=>x.content==='Página 2').length,repairs:window.__mock.queries.filter(x=>x.content==='Página 3').length})`);
  if (JSON.stringify(state.pages) !== '[1,2,3]' || state.empty !== 1 || state.repairs !== 2) throw new Error(`No se omitió/reparó la secuencia: ${JSON.stringify(state)}`);

  await evaluate(`document.querySelector('#studyCard').click();true`);
  await waitFor(`window.__mock.voiceStarts===1`, 'No comenzó el dictado.');
  await evaluate(`window.dispatchEvent(new CustomEvent('inscreen:voz',{detail:{estado:'parcial',texto:'mi explicación'}}));true`);
  await evaluate(`window.dispatchEvent(new CustomEvent('inscreen:voz',{detail:{estado:'final',texto:'mi explicación final'}}));true`);
  await waitFor(`document.querySelector('#answerText').textContent==='mi explicación final'`, 'No se guardó la transcripción.');
  state = await evaluate(`({answer:document.querySelector('#answerText').textContent,saves:window.__mock.saves,stored:localStorage.getItem('mock-state')})`);
  if (state.answer !== 'mi explicación final' || state.saves < 3 || !state.stored.includes('mi explicación final')) throw new Error('La respuesta no quedó persistida.');

  await evaluate(`document.querySelector('#rerecordAnswer').click();true`);
  await waitFor(`window.__mock.voiceStarts===2`, 'No comenzó la regrabación.');
  await evaluate(`window.dispatchEvent(new CustomEvent('inscreen:voz',{detail:{estado:'error',texto:'intento roto',error:'recognizer_audio_error'}}));true`);
  await evaluate(`document.querySelector('#studyCard').click();true`);
  state = await evaluate(`({answer:document.querySelector('#answerText').textContent,visible:!document.querySelector('#answerView').classList.contains('is-hidden')})`);
  if (!state.visible || state.answer !== 'mi explicación final') throw new Error('Una regrabación fallida reemplazó la respuesta anterior.');

  await evaluate(`document.querySelector('#editAnswer').click();document.querySelector('#answerEditor').value='respuesta corregida';document.querySelector('#saveEdit').click();true`);
  await waitFor(`document.querySelector('#answerText').textContent==='respuesta corregida'`, 'No se editó la respuesta.');
  socket.close();
}

main().catch(error => { console.error(error.stack || error); process.exitCode = 1; }).finally(async () => {
  browser?.kill();
  await new Promise(resolve => server.close(resolve));
  await delay(500);
  try { fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 150 }); } catch {}
});
