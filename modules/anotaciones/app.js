const core=window.AnotacionesCore,$=id=>document.getElementById(id);
const STATE_KEY='anotaciones:v1',MODE_KEY='anotaciones:modo:v1',card=$('card');
let state,index=0,side='front',mode=localStorage.getItem(MODE_KEY)==='text'?'text':'voice',voice=false,voiceSide='front',pointer=null,hold=null,held=false,consentResolve=null,allowSystem=false,persistTimer=null;

function current(){return state.tarjetas[index]}
function editorFor(target=side){return $(target==='front'?'headerEditor':'answerEditor')}
function fieldFor(target=side){return target==='front'?'cabecera':'respuesta'}
function syncEditors(){const c=current();if(document.activeElement!==$('headerEditor'))$('headerEditor').value=c.cabecera;if(document.activeElement!==$('answerEditor'))$('answerEditor').value=c.respuesta}
function renderMode(){
  const text=mode==='text';card.classList.toggle('text-mode',text);$('modeIcon').src=text?'mode-writing.png':'mode-microphone.png';
  $('modeToggle').setAttribute('aria-pressed',String(text));$('modeToggle').setAttribute('aria-label',text?'Cambiar a dictado por micrófono':'Cambiar a escritura manual');
  $('edit').classList.toggle('hidden',!text);$('rerecord').classList.toggle('hidden',text);
}
function render(){
  const c=current();$('position').textContent=`${index+1} de ${state.tarjetas.length}`;$('headerText').textContent=c.cabecera;$('answerText').textContent=c.respuesta;
  $('frontHint').textContent=c.cabecera?'':'Tocá para dictar la cabecera';$('backHint').textContent='';$('front').classList.toggle('hidden',side!=='front');$('back').classList.toggle('hidden',side!=='back');$('listening').classList.add('hidden');$('previous').disabled=index===0;
  syncEditors();renderMode();
}
function persist(){localStorage.setItem(STATE_KEY,JSON.stringify(state));return Promise.resolve()}
function schedulePersist(){clearTimeout(persistTimer);persistTimer=setTimeout(()=>void persist(),120)}
function commitEditor(target=side){const editor=editorFor(target),field=fieldFor(target),value=core.clean(editor.value);current()[field]=value;current().actualizada=Date.now();editor.value=value;void persist()}
function toast(){const view=$('toast');view.hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>view.hidden=true,2400)}
async function startVoice(target=side){if(voice)return;const status=await InScreen.module.vozEstado();let system=false;if(!status?.onDevice){if(!status?.servicioSistema)return;if(!allowSystem){$('consent').hidden=false;const ok=await new Promise(r=>consentResolve=r);if(!ok)return;}system=true;}voice=true;voiceSide=target;$('front').classList.add('hidden');$('back').classList.add('hidden');$('listening').classList.remove('hidden');$('transcript').textContent='Escuchando…';const result=await InScreen.module.vozIniciar({permitirServicioSistema:system});if(!result?.ok){voice=false;render();}}
function focusEditor(){const editor=editorFor();editor.focus({preventScroll:true});editor.setSelectionRange(editor.value.length,editor.value.length);requestAnimationFrame(()=>editor.scrollIntoView({block:'center',behavior:'smooth'}))}
function tap(){if(mode==='text'){focusEditor();return}const c=current();if(side==='front'&&!c.cabecera)return void startVoice('front');if(side==='front'){side='back';render();return}if(!c.respuesta)return void startVoice('back');side='front';render()}
function flipSide(){if(document.activeElement?.classList?.contains('card-editor'))document.activeElement.blur();side=side==='front'?'back':'front';render();if(mode==='text')requestAnimationFrame(focusEditor)}
async function move(direction){if(voice)return;if(document.activeElement?.classList?.contains('card-editor'))commitEditor();if(direction<0){if(index===0)return;index--;side='front';render();return}if(!core.canAdvance(state,index)){toast();return}index=core.advance(state,index);side='front';await persist();render()}
function openMenu(){if(voice)return;$('menu').hidden=false}
function closeMenu(){$('menu').hidden=true}
function interactiveTarget(target){return Boolean(target.closest('button'))}

card.addEventListener('pointerdown',e=>{
  if(interactiveTarget(e.target))return;pointer=e.clientX;held=false;
  hold=setTimeout(()=>{held=true;if(mode==='text')flipSide();else openMenu()},550);
});
card.addEventListener('pointermove',e=>{if(pointer!==null&&Math.abs(e.clientX-pointer)>12)clearTimeout(hold)});
card.addEventListener('pointerup',e=>{if(pointer===null)return;clearTimeout(hold);const delta=e.clientX-pointer;pointer=null;if(held){held=false;return}if($('menu').hidden===false)return;if(Math.abs(delta)>50)void move(delta<0?1:-1);else tap()});
card.addEventListener('pointercancel',()=>{clearTimeout(hold);pointer=null;held=false});

$('modeToggle').onclick=()=>{if(voice)return;if(document.activeElement?.classList?.contains('card-editor'))commitEditor();const button=$('modeToggle');button.classList.add('flipping');setTimeout(()=>{mode=mode==='voice'?'text':'voice';localStorage.setItem(MODE_KEY,mode);render();button.classList.remove('flipping');if(mode==='text')focusEditor()},110)};
$('options').onclick=openMenu;$('previous').onclick=()=>void move(-1);$('next').onclick=()=>void move(1);$('stop').onclick=()=>void InScreen.module.vozDetener();$('cancelMenu').onclick=closeMenu;
$('edit').onclick=()=>{closeMenu();focusEditor()};$('rerecord').onclick=()=>{closeMenu();void startVoice(side)};
$('remove').onclick=async()=>{closeMenu();if(!confirm('¿Eliminar esta tarjeta?'))return;index=core.remove(state,index);side='front';await persist();render()};
$('deny').onclick=()=>{$('consent').hidden=true;consentResolve?.(false);consentResolve=null};$('allow').onclick=()=>{allowSystem=true;$('consent').hidden=true;consentResolve?.(true);consentResolve=null};

for(const target of ['front','back']){
  const editor=editorFor(target);
  editor.addEventListener('input',()=>{current()[fieldFor(target)]=editor.value;current().actualizada=Date.now();schedulePersist()});
  editor.addEventListener('blur',()=>commitEditor(target));
  editor.addEventListener('keydown',e=>e.stopPropagation());
}

function updateViewport(){const viewport=window.visualViewport,height=Math.round(viewport?.height||window.innerHeight),keyboard=Boolean(viewport&&window.innerHeight-height>120);document.documentElement.style.setProperty('--viewport-height',`${height}px`);document.body.classList.toggle('keyboard-open',keyboard);if(keyboard&&document.activeElement?.classList?.contains('card-editor'))requestAnimationFrame(()=>document.activeElement.scrollIntoView({block:'center'}))}
window.visualViewport?.addEventListener('resize',updateViewport);window.visualViewport?.addEventListener('scroll',updateViewport);window.addEventListener('resize',updateViewport);updateViewport();

window.addEventListener('inscreen:voz',async e=>{if(!voice)return;const d=e.detail||{};if(d.estado==='parcial')$('transcript').textContent=d.texto||'Escuchando…';if(d.estado==='error'){voice=false;render()}if(d.estado==='final'){voice=false;const text=core.clean(d.texto);if(text){if(voiceSide==='front')current().cabecera=text;else current().respuesta=text;current().actualizada=Date.now();await persist()}side=voiceSide;render()}});
(async()=>{let saved=null;try{saved=JSON.parse(localStorage.getItem(STATE_KEY)||'null')}catch{}state=core.normalize(saved);await persist();render()})().catch(error=>{document.body.textContent=error.message});
