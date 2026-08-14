const core=window.AnotacionesCore,$=id=>document.getElementById(id);
const STATE_KEY='anotaciones:v1',MODE_KEY='anotaciones:modo:v1',SYSTEM_VOICE_KEY='anotaciones:voz-sistema:v1',FONT_SCALE_KEY='anotaciones:tipografia:v1',card=$('card');
const SWIPE_THRESHOLD=50,DRAG_TOLERANCE=12,ANIMATION_MS=180;
let state,index=0,side='front',mode=localStorage.getItem(MODE_KEY)==='text'?'text':'voice';
let voice=false,voiceSide='front',editing=false,pointer=null,hold=null,held=false,heldAction=null;
let consentResolve=null,allowSystem=localStorage.getItem(SYSTEM_VOICE_KEY)==='allowed',persistTimer=null,undoOperation=null,toastTimer=null,transitioning=false,keyboardWasOpen=false;
const storedFontScale=(()=>{try{return JSON.parse(localStorage.getItem(FONT_SCALE_KEY)||'null')}catch{return null}})();
const clampFontScale=value=>Math.max(.7,Math.min(1.8,Number(value)||1));
let fontScales=typeof storedFontScale==='number'?{front:clampFontScale(storedFontScale),back:clampFontScale(storedFontScale)}:{front:clampFontScale(storedFontScale?.front),back:clampFontScale(storedFontScale?.back)};

function current(){return state.tarjetas[index]}
function editorFor(target=side){return $(target==='front'?'headerEditor':'answerEditor')}
function fieldFor(target=side){return target==='front'?'cabecera':'respuesta'}
function syncEditors(){const c=current();if(document.activeElement!==$('headerEditor'))$('headerEditor').value=c.cabecera;if(document.activeElement!==$('answerEditor'))$('answerEditor').value=c.respuesta}
function renderMode(){
  const text=mode==='text';card.classList.toggle('text-mode',text);card.classList.toggle('editing',text&&editing);
  $('modeIcon').src=text?'mode-writing.png':'mode-microphone.png';
  $('modeToggle').setAttribute('aria-pressed',String(text));
  $('modeToggle').setAttribute('aria-label',text?'Cambiar a dictado por micrófono':'Cambiar a escritura manual');
  applyFontScale();
}
function applyFontScale(){
  const frontScale=fontScales.front,backScale=fontScales.back;
  const values={
    '--front-font-min':`${1.6*frontScale}rem`,'--front-font-fluid':`${7*frontScale}vw`,'--front-font-max':`${2.3*frontScale}rem`,
    '--answer-font-size':`${backScale}rem`,'--editor-font-min':`${1.35*frontScale}rem`,'--editor-font-fluid':`${6*frontScale}vw`,
    '--editor-font-max':`${2*frontScale}rem`,'--back-editor-font-min':`${1.05*backScale}rem`,'--back-editor-font-fluid':`${4.8*backScale}vw`,'--back-editor-font-max':`${1.4*backScale}rem`
  };
  Object.entries(values).forEach(([name,value])=>card.style.setProperty(name,value));
  const activeScale=fontScales[side];
  $('fontDecrease').disabled=activeScale<=.7;$('fontIncrease').disabled=activeScale>=1.8;
  $('fontControls').setAttribute('aria-label',`Tamaño de tipografía ${side==='front'?'de la portada':'de la respuesta'} ${Math.round(activeScale*100)}%`);
}
function changeFontScale(delta){
  fontScales[side]=clampFontScale(Math.round((fontScales[side]+delta)*10)/10);
  localStorage.setItem(FONT_SCALE_KEY,JSON.stringify(fontScales));applyFontScale();
}
function selectMode(next){
  if(mode===next){renderMode();return;}
  mode=next;localStorage.setItem(MODE_KEY,mode);
  const button=$('modeToggle');button.classList.add('flipping');renderMode();
  setTimeout(()=>button.classList.remove('flipping'),180);
}
function render(){
  const c=current();
  $('headerText').textContent=c.cabecera;$('answerText').textContent=c.respuesta;
  $('front').classList.toggle('hidden',side!=='front');$('back').classList.toggle('hidden',side!=='back');
  $('listening').classList.add('hidden');
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
function reducedMotion(){return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches===true}
function waitForAnimation(){return new Promise(resolve=>setTimeout(resolve,reducedMotion()?0:ANIMATION_MS))}
function resetCardStyle(){card.style.transition='';card.style.transform='';card.style.opacity=''}
function setDeleteProgress(distance){
  const amount=Math.max(0,distance),maxHeight=card.clientHeight*.2,height=Math.min(amount,maxHeight);
  const visualProgress=maxHeight?height/maxHeight:0;
  card.classList.add('delete-dragging');card.classList.toggle('delete-armed',amount>SWIPE_THRESHOLD);
  card.style.setProperty('--delete-height',`${height}px`);
  card.style.setProperty('--delete-icon-opacity',String(Math.min(1,visualProgress*1.8)));
  card.style.setProperty('--delete-icon-scale',String(.7+Math.min(1,visualProgress)*.3));
  return amount>SWIPE_THRESHOLD;
}
function resetDeleteIndicator(animate=true){
  card.classList.remove('delete-dragging','delete-armed');
  card.style.setProperty('--delete-height','0px');card.style.setProperty('--delete-icon-opacity','0');card.style.setProperty('--delete-icon-scale','.7');
  if(!animate){card.style.removeProperty('--delete-height');card.style.removeProperty('--delete-icon-opacity');card.style.removeProperty('--delete-icon-scale');}
}
function setRecordProgress(distance){
  const amount=Math.max(0,distance),maxHeight=card.clientHeight*.2,height=Math.min(amount,maxHeight);
  const visualProgress=maxHeight?height/maxHeight:0;
  card.classList.add('record-dragging');card.classList.toggle('record-armed',amount>SWIPE_THRESHOLD);
  card.style.setProperty('--record-height',`${height}px`);
  card.style.setProperty('--record-icon-opacity',String(Math.min(1,visualProgress*1.8)));
  card.style.setProperty('--record-icon-scale',String(.7+Math.min(1,visualProgress)*.3));
  return amount>SWIPE_THRESHOLD;
}
function resetRecordIndicator(animate=true){
  card.classList.remove('record-dragging','record-armed');
  card.style.setProperty('--record-height','0px');card.style.setProperty('--record-icon-opacity','0');card.style.setProperty('--record-icon-scale','.7');
  if(!animate){card.style.removeProperty('--record-height');card.style.removeProperty('--record-icon-opacity');card.style.removeProperty('--record-icon-scale');}
}
function springBack(){
  card.style.transition=reducedMotion()?'none':`transform ${ANIMATION_MS}ms ease, opacity ${ANIMATION_MS}ms ease`;
  card.style.transform='translate(0,0) rotate(0deg)';card.style.opacity='1';
  setTimeout(resetCardStyle,reducedMotion()?0:ANIMATION_MS);
}
async function transitionCard(exitX,exitY,mutate){
  if(transitioning)return;
  transitioning=true;
  try{
    const rotation=exitX?Math.sign(exitX)*7:0;
    card.style.transition=reducedMotion()?'none':`transform ${ANIMATION_MS}ms ease, opacity ${ANIMATION_MS}ms ease`;
    card.style.transform=`translate(${exitX}px,${exitY}px) rotate(${rotation}deg)`;card.style.opacity='0';
    await waitForAnimation();await mutate();render();
    const entrySign=exitX?-Math.sign(exitX):1,entryX=entrySign*(window.innerWidth+80);
    card.style.transition='none';card.style.transform=`translate(${entryX}px,0) rotate(${entrySign*7}deg)`;card.style.opacity='0';
    void card.offsetWidth;
    card.style.transition=reducedMotion()?'none':`transform ${ANIMATION_MS}ms ease, opacity ${ANIMATION_MS}ms ease`;
    card.style.transform='translate(0,0) rotate(0deg)';card.style.opacity='1';
    await waitForAnimation();
  }finally{resetCardStyle();transitioning=false;}
}
async function enterFromRight(mutate){
  if(transitioning)return;
  transitioning=true;
  try{
    await mutate();render();
    card.style.transition='none';card.style.transform=`translate(${window.innerWidth+80}px,0) rotate(7deg)`;card.style.opacity='0';
    void card.offsetWidth;
    card.style.transition=reducedMotion()?'none':`transform ${ANIMATION_MS}ms ease, opacity ${ANIMATION_MS}ms ease`;
    card.style.transform='translate(0,0) rotate(0deg)';card.style.opacity='1';
    await waitForAnimation();
  }finally{resetCardStyle();transitioning=false;}
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
async function changeCard(direction){
  finishEditing();
  if(direction<0)index--;
  else index=core.advance(state,index);
  side='front';await persist();
}
async function navigate(direction,exitSign){
  if(voice||transitioning)return;
  if(direction<0&&index===0){springBack();return;}
  if(direction>0&&!core.canAdvance(state,index)){springBack();showToast('No hay más tarjetas por ahora');return;}
  await transitionCard(exitSign*(window.innerWidth+80),0,()=>changeCard(direction));
}
async function removeCurrent(){
  if(voice||transitioning)return;
  transitioning=true;
  try{
    finishEditing();resetDeleteIndicator(false);resetRecordIndicator(false);
    undoOperation=core.removeUndoable(state,index);index=undoOperation.index;side='front';editing=false;
    await persist();render();showToast('Tarjeta eliminada',{undo:true});
  }finally{transitioning=false;}
}
async function undoRemove(){
  if(!undoOperation||transitioning)return;
  clearTimeout(toastTimer);$('toast').hidden=true;
  await enterFromRight(async()=>{index=core.restoreRemoved(state,undoOperation);undoOperation=null;side='front';editing=false;await persist()});
}
function interactiveTarget(target){return Boolean(target.closest('button,textarea'))}
function stopVoice(){if(voice)void InScreen.module.vozDetener()}
function beginManualEditing(){selectMode('text');beginEditing()}
function beginGestureRecording(){
  finishEditing();resetDeleteIndicator(false);resetRecordIndicator(false);selectMode('voice');void startVoice(side);
}

card.addEventListener('pointerdown',e=>{
  if(interactiveTarget(e.target)||voice||transitioning)return;
  pointer={x:e.clientX,y:e.clientY,id:e.pointerId,axis:null,deleteArmed:false,recordArmed:false};held=false;heldAction=null;
  card.setPointerCapture?.(e.pointerId);
  hold=setTimeout(()=>{held=true;heldAction='edit'},550);
});
card.addEventListener('pointermove',e=>{
  if(!pointer)return;
  if(held)return;
  const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y;
  if(Math.hypot(dx,dy)>DRAG_TOLERANCE){
    clearTimeout(hold);
    if(!pointer.axis){
      if(Math.abs(dx)>Math.abs(dy)*1.15)pointer.axis='horizontal';
      else if(Math.abs(dy)>Math.abs(dx)*1.15)pointer.axis='vertical';
    }
  }
  if(pointer.axis==='horizontal'){
    resetDeleteIndicator(false);resetRecordIndicator(false);card.style.transition='none';card.style.transform=`translate(${dx}px,0) rotate(${dx/35}deg)`;
    card.style.opacity=String(Math.max(.68,1-Math.abs(dx)/(window.innerWidth*1.8)));
  }else if(pointer.axis==='vertical'){
    resetCardStyle();
    if(dy<0){resetRecordIndicator(false);pointer.recordArmed=false;pointer.deleteArmed=setDeleteProgress(-dy);}
    else{resetDeleteIndicator(false);pointer.deleteArmed=false;pointer.recordArmed=setRecordProgress(dy);}
  }
});
card.addEventListener('pointerup',e=>{
  if(!pointer){held=false;return;}
  clearTimeout(hold);
  const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y,axis=pointer.axis,deleteArmed=pointer.deleteArmed,recordArmed=pointer.recordArmed;pointer=null;
  if(held){
    const action=heldAction;held=false;heldAction=null;
    if(action==='edit')beginManualEditing();
    return;
  }
  if(axis==='vertical'){
    if(deleteArmed&&dy<0){void removeCurrent();return;}
    if(recordArmed&&dy>0){beginGestureRecording();return;}
    resetDeleteIndicator(true);resetRecordIndicator(true);return;
  }
  const swipe=axis==='horizontal'?core.classifySwipe(dx,0,SWIPE_THRESHOLD):null;
  if(swipe==='left'||swipe==='right'){void navigate(swipe==='left'?1:-1,swipe==='left'?-1:1);return;}
  if(Math.hypot(dx,dy)<DRAG_TOLERANCE){resetCardStyle();tap();return;}
  springBack();
});
card.addEventListener('pointercancel',()=>{clearTimeout(hold);pointer=null;held=false;heldAction=null;resetDeleteIndicator(true);resetRecordIndicator(true);springBack()});
card.addEventListener('contextmenu',e=>{if(!editing)e.preventDefault()});

$('modeToggle').onclick=()=>{
  if(voice)return;
  finishEditing();
  const button=$('modeToggle');button.classList.add('flipping');
  setTimeout(()=>{mode=mode==='voice'?'text':'voice';localStorage.setItem(MODE_KEY,mode);render();button.classList.remove('flipping')},110);
};
$('fontControls').addEventListener('pointerdown',e=>e.preventDefault());
$('fontDecrease').onclick=()=>changeFontScale(-.1);
$('fontIncrease').onclick=()=>changeFontScale(.1);
$('stop').onclick=stopVoice;
$('listening').addEventListener('click',e=>{if(!e.target.closest('#stop'))stopVoice()});
$('undo').onclick=()=>void undoRemove();
$('deny').onclick=()=>{$('consent').hidden=true;consentResolve?.(false);consentResolve=null};
$('allow').onclick=()=>{allowSystem=true;localStorage.setItem(SYSTEM_VOICE_KEY,'allowed');$('consent').hidden=true;consentResolve?.(true);consentResolve=null};

for(const target of ['front','back']){
  const editor=editorFor(target);
  editor.addEventListener('input',()=>{current()[fieldFor(target)]=editor.value;current().actualizada=Date.now();schedulePersist()});
  editor.addEventListener('blur',()=>{if(editing){commitEditor(target);editing=false;render()}});
  editor.addEventListener('keydown',e=>e.stopPropagation());
}

function updateViewport(){
  const viewport=window.visualViewport,height=Math.round(viewport?.height||window.innerHeight),keyboard=Boolean(viewport&&window.innerHeight-height>120);
  const activeEditor=document.activeElement?.classList?.contains('card-editor')?document.activeElement:null;
  document.documentElement.style.setProperty('--viewport-height',`${height}px`);document.body.classList.toggle('keyboard-open',keyboard);
  if(keyboard&&activeEditor)requestAnimationFrame(()=>activeEditor.scrollIntoView({block:'center'}));
  if(keyboardWasOpen&&!keyboard&&activeEditor)activeEditor.blur();
  keyboardWasOpen=keyboard;
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
