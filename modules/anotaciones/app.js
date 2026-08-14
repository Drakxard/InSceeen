const core=window.AnotacionesCore,$=id=>document.getElementById(id);
const STATE_KEY='anotaciones:v1',MODE_KEY='anotaciones:modo:v1',card=$('card');
let state,index=0,side='front',mode=localStorage.getItem(MODE_KEY)==='text'?'text':'voice';
let voice=false,voiceSide='front',editing=false,pointer=null,hold=null,held=false;
let consentResolve=null,allowSystem=false,persistTimer=null,undoOperation=null,toastTimer=null;

function current(){return state.tarjetas[index]}
function editorFor(target=side){return $(target==='front'?'headerEditor':'answerEditor')}
function fieldFor(target=side){return target==='front'?'cabecera':'respuesta'}
function syncEditors(){const c=current();if(document.activeElement!==$('headerEditor'))$('headerEditor').value=c.cabecera;if(document.activeElement!==$('answerEditor'))$('answerEditor').value=c.respuesta}
function renderMode(){
  const text=mode==='text';card.classList.toggle('text-mode',text);card.classList.toggle('editing',text&&editing);
  $('modeIcon').src=text?'mode-writing.png':'mode-microphone.png';
  $('modeToggle').setAttribute('aria-pressed',String(text));
  $('modeToggle').setAttribute('aria-label',text?'Cambiar a dictado por micrófono':'Cambiar a escritura manual');
}
function render(){
  const c=current();
  $('position').textContent=`${index+1} de ${state.tarjetas.length}`;
  $('headerText').textContent=c.cabecera;$('answerText').textContent=c.respuesta;
  $('frontHint').textContent=c.cabecera?'':'Mantené para completar la cabecera';
  $('backHint').textContent=c.respuesta?'':'Mantené para completar la respuesta';
  $('front').classList.toggle('hidden',side!=='front');$('back').classList.toggle('hidden',side!=='back');
  $('listening').classList.add('hidden');$('previous').disabled=index===0;
  syncEditors();renderMode();
}
function persist(){localStorage.setItem(STATE_KEY,JSON.stringify(state));return Promise.resolve()}
function schedulePersist(){clearTimeout(persistTimer);persistTimer=setTimeout(()=>void persist(),120)}
function commitEditor(target=side){
  const editor=editorFor(target),field=fieldFor(target),value=core.clean(editor.value);
  current()[field]=value;current().actualizada=Date.now();editor.value=value;void persist();
}
function finishEditing(){
  if(!editing)return;
  const active=document.activeElement?.classList?.contains('card-editor')?document.activeElement:null;
  if(active)commitEditor(active.id==='headerEditor'?'front':'back');else commitEditor();
  editing=false;active?.blur();render();
}
function showToast(message,{undo=false}={}){
  const view=$('toast');$('toastText').textContent=message;$('undo').hidden=!undo;view.hidden=false;
  clearTimeout(toastTimer);toastTimer=setTimeout(()=>{view.hidden=true;undoOperation=null},3000);
}
async function startVoice(target=side){
  if(voice)return;
  const status=await InScreen.module.vozEstado();let system=false;
  if(!status?.onDevice){
    if(!status?.servicioSistema)return;
    if(!allowSystem){$('consent').hidden=false;const ok=await new Promise(resolve=>consentResolve=resolve);if(!ok)return;}
    system=true;
  }
  voice=true;voiceSide=target;
  $('front').classList.add('hidden');$('back').classList.add('hidden');$('listening').classList.remove('hidden');
  $('transcript').textContent='Escuchando…';
  const result=await InScreen.module.vozIniciar({permitirServicioSistema:system});
  if(!result?.ok){voice=false;render();}
}
function beginEditing(){
  if(mode!=='text'||voice)return;
  editing=true;renderMode();
  const editor=editorFor();editor.focus({preventScroll:true});editor.setSelectionRange(editor.value.length,editor.value.length);
  requestAnimationFrame(()=>editor.scrollIntoView({block:'center',behavior:'smooth'}));
}
function tap(){
  if(voice)return;
  if(editing)finishEditing();
  side=side==='front'?'back':'front';render();
}
async function move(direction){
  if(voice)return;
  finishEditing();
  if(direction<0){if(index===0)return;index--;side='front';render();return;}
  if(!core.canAdvance(state,index)){showToast('No hay más tarjetas por ahora');return;}
  index=core.advance(state,index);side='front';await persist();render();
}
async function removeCurrent(){
  if(voice)return;
  finishEditing();
  undoOperation=core.removeUndoable(state,index);index=undoOperation.index;side='front';editing=false;
  await persist();render();showToast('Tarjeta eliminada',{undo:true});
}
async function undoRemove(){
  if(!undoOperation)return;
  index=core.restoreRemoved(state,undoOperation);undoOperation=null;side='front';editing=false;
  clearTimeout(toastTimer);$('toast').hidden=true;await persist();render();
}
function interactiveTarget(target){return Boolean(target.closest('button,textarea'))}

card.addEventListener('pointerdown',e=>{
  if(interactiveTarget(e.target)||voice)return;
  pointer={x:e.clientX,y:e.clientY};held=false;
  hold=setTimeout(()=>{held=true;pointer=null;if(mode==='text')beginEditing();else void startVoice(side)},550);
});
card.addEventListener('pointermove',e=>{
  if(!pointer)return;
  const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y;
  if(Math.hypot(dx,dy)>12)clearTimeout(hold);
});
card.addEventListener('pointerup',e=>{
  if(!pointer){held=false;return;}
  clearTimeout(hold);
  const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y;pointer=null;
  if(held){held=false;return;}
  if(Math.abs(dy)>70&&Math.abs(dy)>Math.abs(dx)*1.15){void removeCurrent();return;}
  if(Math.abs(dx)>50&&Math.abs(dx)>Math.abs(dy)*1.15){void move(dx<0?1:-1);return;}
  if(Math.hypot(dx,dy)<12)tap();
});
card.addEventListener('pointercancel',()=>{clearTimeout(hold);pointer=null;held=false});

$('modeToggle').onclick=()=>{
  if(voice)return;
  finishEditing();
  const button=$('modeToggle');button.classList.add('flipping');
  setTimeout(()=>{mode=mode==='voice'?'text':'voice';localStorage.setItem(MODE_KEY,mode);render();button.classList.remove('flipping')},110);
};
$('previous').onclick=()=>void move(-1);$('next').onclick=()=>void move(1);
$('stop').onclick=()=>void InScreen.module.vozDetener();$('undo').onclick=()=>void undoRemove();
$('deny').onclick=()=>{$('consent').hidden=true;consentResolve?.(false);consentResolve=null};
$('allow').onclick=()=>{allowSystem=true;$('consent').hidden=true;consentResolve?.(true);consentResolve=null};

for(const target of ['front','back']){
  const editor=editorFor(target);
  editor.addEventListener('input',()=>{current()[fieldFor(target)]=editor.value;current().actualizada=Date.now();schedulePersist()});
  editor.addEventListener('blur',()=>{if(editing){commitEditor(target);editing=false;render()}});
  editor.addEventListener('keydown',e=>e.stopPropagation());
}

function updateViewport(){
  const viewport=window.visualViewport,height=Math.round(viewport?.height||window.innerHeight),keyboard=Boolean(viewport&&window.innerHeight-height>120);
  document.documentElement.style.setProperty('--viewport-height',`${height}px`);document.body.classList.toggle('keyboard-open',keyboard);
  if(keyboard&&document.activeElement?.classList?.contains('card-editor'))requestAnimationFrame(()=>document.activeElement.scrollIntoView({block:'center'}));
}
window.visualViewport?.addEventListener('resize',updateViewport);window.visualViewport?.addEventListener('scroll',updateViewport);window.addEventListener('resize',updateViewport);updateViewport();

window.addEventListener('inscreen:voz',async e=>{
  if(!voice)return;const d=e.detail||{};
  if(d.estado==='parcial')$('transcript').textContent=d.texto||'Escuchando…';
  if(d.estado==='error'){voice=false;render();}
  if(d.estado==='final'){
    voice=false;const text=core.clean(d.texto);
    if(text){if(voiceSide==='front')current().cabecera=text;else current().respuesta=text;current().actualizada=Date.now();await persist();}
    side=voiceSide;render();
  }
});
(async()=>{let saved=null;try{saved=JSON.parse(localStorage.getItem(STATE_KEY)||'null')}catch{}state=core.normalize(saved);await persist();render()})().catch(error=>{document.body.textContent=error.message});
