const core=window.AnotacionesCore,$=id=>document.getElementById(id);
const STATE_KEY='anotaciones:v2',LEGACY_STATE_KEY='anotaciones:v1',MODE_KEY='anotaciones:modo:v1',SYSTEM_VOICE_KEY='anotaciones:voz-sistema:v1',FONT_SCALE_KEY='anotaciones:tipografia:v1',card=$('card');
const SWIPE_THRESHOLD=50,DRAG_TOLERANCE=12,ANIMATION_MS=180,HOLD_MS=550;
let state,index=0,side='front',view='folders',mode=localStorage.getItem(MODE_KEY)==='text'?'text':'voice';
let voice=false,voiceSide='front',editing=false,pointer=null,hold=null,held=false,heldAction=null,folderHold=null,scrubbing=false;
let consentResolve=null,allowSystem=localStorage.getItem(SYSTEM_VOICE_KEY)==='allowed',persistTimer=null,undoOperation=null,toastTimer=null,transitioning=false,keyboardWasOpen=false,pendingDeleteFolderId=null,pendingImport=null;
const storedFontScale=(()=>{try{return JSON.parse(localStorage.getItem(FONT_SCALE_KEY)||'null')}catch{return null}})();
const clampFontScale=value=>Math.max(.7,Math.min(1.8,Number(value)||1));
let fontScales=typeof storedFontScale==='number'?{front:clampFontScale(storedFontScale),back:clampFontScale(storedFontScale)}:{front:clampFontScale(storedFontScale?.front),back:clampFontScale(storedFontScale?.back)};

function folder(){return core.findFolder(state,state.carpetaActivaId)}
function deck(){return folder()}
function current(){return folder()?.tarjetas[index]}
function editorFor(target=side){return $(target==='front'?'headerEditor':'answerEditor')}
function fieldFor(target=side){return target==='front'?'cabecera':'respuesta'}
function persist(){localStorage.setItem(STATE_KEY,JSON.stringify(state));return Promise.resolve()}
function schedulePersist(){clearTimeout(persistTimer);persistTimer=setTimeout(()=>void persist(),120)}
function showToast(message,{undo=false}={}){const toast=$('toast');$('toastText').textContent=message;$('undo').hidden=!undo;toast.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>{toast.hidden=true;undoOperation=null},3000)}
function reducedMotion(){return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches===true}
function waitForAnimation(){return new Promise(resolve=>setTimeout(resolve,reducedMotion()?0:ANIMATION_MS))}
function syncEditors(){const c=current();if(!c)return;if(document.activeElement!==$('headerEditor'))$('headerEditor').value=c.cabecera;if(document.activeElement!==$('answerEditor'))$('answerEditor').value=c.respuesta}
function applyFontScale(){
  const frontScale=fontScales.front,backScale=fontScales.back,values={'--front-font-min':`${1.6*frontScale}rem`,'--front-font-fluid':`${7*frontScale}vw`,'--front-font-max':`${2.3*frontScale}rem`,'--answer-font-size':`${backScale}rem`,'--editor-font-min':`${1.35*frontScale}rem`,'--editor-font-fluid':`${6*frontScale}vw`,'--editor-font-max':`${2*frontScale}rem`,'--back-editor-font-min':`${1.05*backScale}rem`,'--back-editor-font-fluid':`${4.8*backScale}vw`,'--back-editor-font-max':`${1.4*backScale}rem`};
  Object.entries(values).forEach(([name,value])=>card.style.setProperty(name,value));const active=fontScales[side];$('fontDecrease').disabled=active<=.7;$('fontIncrease').disabled=active>=1.8;
}
function renderMode(){card.classList.toggle('text-mode',mode==='text');card.classList.toggle('editing',mode==='text'&&editing);applyFontScale()}
function selectMode(next){mode=next;localStorage.setItem(MODE_KEY,mode);renderMode()}
function renderCard(){
  const c=current();if(!c)return;$('headerText').textContent=c.cabecera;$('answerText').textContent=c.respuesta;
  $('front').classList.toggle('hidden',side!=='front');$('back').classList.toggle('hidden',side!=='back');$('listening').classList.add('hidden');syncEditors();renderMode();
}
function renderFolders(){
  const list=$('folderList');list.replaceChildren();$('emptyFolders').hidden=state.carpetas.length>0;
  state.carpetas.forEach(item=>{
    const row=document.createElement('div');row.className='folder-row';row.dataset.folderId=item.id;
    const open=document.createElement('button');open.type='button';open.className='folder-open';open.textContent=item.nombre;open.setAttribute('aria-label',`Abrir ${item.nombre}. Mantén presionado para eliminar`);
    const imported=document.createElement('button');imported.type='button';imported.className='folder-import';imported.textContent='↓';imported.setAttribute('aria-label',`Importar tarjetas en ${item.nombre}`);
    bindFolderHold(open,row,item);open.addEventListener('click',()=>{if(open.dataset.suppressClick){delete open.dataset.suppressClick;return}openFolder(item.id)});imported.addEventListener('click',()=>void readClipboard(item));
    row.append(open,imported);list.append(row);
  });
}
function renderView(){
  const active=folder();if(view==='deck'&&!active)view='folders';const deckVisible=view==='deck';$('deckView').hidden=!deckVisible;$('folderBack').hidden=!deckVisible;$('foldersView').hidden=deckVisible;
  if(deckVisible)renderCard();else renderFolders();
}
function openFolder(id){const selected=core.findFolder(state,id);if(!selected)return;state.carpetaActivaId=id;index=0;side='front';editing=false;view='deck';void persist();renderView()}
function showFolders(){finishEditing();if(voice)void InScreen.module.vozCancelar();voice=false;view='folders';renderView()}
function bindFolderHold(button,row,item){
  const cancel=()=>{clearTimeout(folderHold);folderHold=null;row.classList.remove('holding')};
  button.addEventListener('pointerdown',event=>{cancel();const startX=event.clientX,startY=event.clientY;folderHold={button,startX,startY};row.classList.add('holding');folderHold.timer=setTimeout(()=>{button.dataset.suppressClick='true';cancel();askDeleteFolder(item)},HOLD_MS)});
  button.addEventListener('pointermove',event=>{if(folderHold?.button===button&&Math.hypot(event.clientX-folderHold.startX,event.clientY-folderHold.startY)>DRAG_TOLERANCE)cancel()});
  button.addEventListener('pointerup',cancel);button.addEventListener('pointercancel',cancel);button.addEventListener('pointerleave',cancel);button.addEventListener('contextmenu',event=>event.preventDefault());
}
function openFolderDialog(){$('folderError').hidden=true;$('folderName').value='';$('folderDialog').hidden=false;setTimeout(()=>$('folderName').focus(),0)}
function closeFolderDialog(){$('folderDialog').hidden=true;$('folderName').blur()}
function askDeleteFolder(item){pendingDeleteFolderId=item.id;const count=core.navigableIndices(item).length;$('deleteFolderText').textContent=`¿Eliminar “${item.nombre}” y sus ${count} tarjeta${count===1?'':'s'}?`;$('deleteFolderDialog').hidden=false}
function closeDeleteFolder(){$('deleteFolderDialog').hidden=true;pendingDeleteFolderId=null}
async function readClipboard(targetFolder){
  let result;try{result=await window.InScreen?.module?.portapapeles?.()}catch{result={ok:false,error:'clipboard_read_failed'}}
  if(!result?.ok){showToast('No se pudo leer el portapapeles');return}const cards=core.parseClipboard(result.texto);if(!cards.length){showToast('No hay tarjetas admisibles para importar');return}
  pendingImport={folderId:targetFolder.id,cards};$('importSummary').textContent=`Se añadirán ${cards.length} tarjeta${cards.length===1?'':'s'} a “${targetFolder.nombre}”.`;
  const preview=$('importPreview');preview.replaceChildren();cards.slice(0,5).forEach(item=>{const entry=document.createElement('div');entry.className='preview-card';const header=document.createElement('strong');header.textContent=item.cabecera;const answer=document.createElement('span');answer.textContent=item.respuesta||'Sin respuesta';entry.append(header,answer);preview.append(entry)});
  if(cards.length>5){const more=document.createElement('p');more.textContent=`…y ${cards.length-5} más`;preview.append(more)}$('importDialog').hidden=false;
}
function closeImport(){$('importDialog').hidden=true;pendingImport=null}
function commitEditor(target=side){const c=current();if(!c)return;const editor=editorFor(target),field=fieldFor(target),value=core.clean(editor.value);c[field]=value;c.actualizada=Date.now();editor.value=value;void persist()}
function finishEditing(){if(!editing)return;const active=document.activeElement?.classList?.contains('card-editor')?document.activeElement:null;if(active)commitEditor(active.id==='headerEditor'?'front':'back');else commitEditor();editing=false;active?.blur();if(view==='deck')renderCard()}
function changeFontScale(delta){fontScales[side]=clampFontScale(Math.round((fontScales[side]+delta)*10)/10);localStorage.setItem(FONT_SCALE_KEY,JSON.stringify(fontScales));applyFontScale()}
function resetCardStyle(){card.style.transition='';card.style.transform='';card.style.opacity=''}
function setDeleteProgress(distance){const amount=Math.max(0,distance),maxHeight=card.clientHeight*.2,height=Math.min(amount,maxHeight),progress=maxHeight?height/maxHeight:0;card.classList.add('delete-dragging');card.classList.toggle('delete-armed',amount>SWIPE_THRESHOLD);card.style.setProperty('--delete-height',`${height}px`);card.style.setProperty('--delete-icon-opacity',String(Math.min(1,progress*1.8)));card.style.setProperty('--delete-icon-scale',String(.7+Math.min(1,progress)*.3));return amount>SWIPE_THRESHOLD}
function resetDeleteIndicator(animate=true){card.classList.remove('delete-dragging','delete-armed');card.style.setProperty('--delete-height','0px');card.style.setProperty('--delete-icon-opacity','0');card.style.setProperty('--delete-icon-scale','.7');if(!animate){card.style.removeProperty('--delete-height');card.style.removeProperty('--delete-icon-opacity');card.style.removeProperty('--delete-icon-scale')}}
function setRecordProgress(distance){const amount=Math.max(0,distance),maxHeight=card.clientHeight*.2,height=Math.min(amount,maxHeight),progress=maxHeight?height/maxHeight:0;card.classList.add('record-dragging');card.classList.toggle('record-armed',amount>SWIPE_THRESHOLD);card.style.setProperty('--record-height',`${height}px`);card.style.setProperty('--record-icon-opacity',String(Math.min(1,progress*1.8)));card.style.setProperty('--record-icon-scale',String(.7+Math.min(1,progress)*.3));return amount>SWIPE_THRESHOLD}
function resetRecordIndicator(animate=true){card.classList.remove('record-dragging','record-armed');card.style.setProperty('--record-height','0px');card.style.setProperty('--record-icon-opacity','0');card.style.setProperty('--record-icon-scale','.7');if(!animate){card.style.removeProperty('--record-height');card.style.removeProperty('--record-icon-opacity');card.style.removeProperty('--record-icon-scale')}}
function springBack(){card.style.transition=reducedMotion()?'none':`transform ${ANIMATION_MS}ms ease, opacity ${ANIMATION_MS}ms ease`;card.style.transform='translate(0,0) rotate(0deg)';card.style.opacity='1';setTimeout(resetCardStyle,reducedMotion()?0:ANIMATION_MS)}
async function transitionCard(exitX,mutate){if(transitioning)return;transitioning=true;try{const rotation=Math.sign(exitX)*7;card.style.transition=reducedMotion()?'none':`transform ${ANIMATION_MS}ms ease, opacity ${ANIMATION_MS}ms ease`;card.style.transform=`translate(${exitX}px,0) rotate(${rotation}deg)`;card.style.opacity='0';await waitForAnimation();await mutate();renderCard();const sign=-Math.sign(exitX);card.style.transition='none';card.style.transform=`translate(${sign*(window.innerWidth+80)}px,0) rotate(${sign*7}deg)`;card.style.opacity='0';void card.offsetWidth;card.style.transition=reducedMotion()?'none':`transform ${ANIMATION_MS}ms ease, opacity ${ANIMATION_MS}ms ease`;card.style.transform='translate(0,0)';card.style.opacity='1';await waitForAnimation()}finally{resetCardStyle();transitioning=false}}
async function startVoice(target=side){if(voice)return;const status=await InScreen.module.vozEstado();let system=false;if(!status?.onDevice){if(!status?.servicioSistema)return;if(!allowSystem){$('consent').hidden=false;const ok=await new Promise(resolve=>consentResolve=resolve);if(!ok)return}system=true}voice=true;voiceSide=target;$('front').classList.add('hidden');$('back').classList.add('hidden');$('listening').classList.remove('hidden');$('transcript').textContent='Escuchando…';const result=await InScreen.module.vozIniciar({permitirServicioSistema:system});if(!result?.ok){voice=false;renderCard()}}
function beginEditing(){if(mode!=='text'||voice)return;editing=true;renderMode();const editor=editorFor();editor.focus({preventScroll:true});editor.setSelectionRange(editor.value.length,editor.value.length);requestAnimationFrame(()=>editor.scrollIntoView({block:'center',behavior:'smooth'}))}
function tap(){if(voice)return;if(editing)finishEditing();side=side==='front'?'back':'front';renderCard()}
async function changeCard(direction){finishEditing();if(direction<0)index--;else index=core.advance(deck(),index);side='front';await persist()}
async function navigate(direction,exitSign){if(voice||transitioning)return;if(direction<0&&index===0){springBack();return}if(direction>0&&!core.canAdvance(deck(),index)){springBack();showToast('No hay más tarjetas por ahora');return}await transitionCard(exitSign*(window.innerWidth+80),()=>changeCard(direction))}
async function removeCurrent(){if(voice||transitioning)return;transitioning=true;try{finishEditing();resetDeleteIndicator(false);resetRecordIndicator(false);undoOperation=core.removeUndoable(deck(),index);index=undoOperation.index;side='front';editing=false;await persist();renderCard();showToast('Tarjeta eliminada',{undo:true})}finally{transitioning=false}}
async function undoRemove(){if(!undoOperation||transitioning)return;clearTimeout(toastTimer);$('toast').hidden=true;index=core.restoreRemoved(deck(),undoOperation);undoOperation=null;side='front';editing=false;await persist();renderCard()}
function interactiveTarget(target){return Boolean(target.closest('button,textarea'))}

card.addEventListener('pointerdown',e=>{if(interactiveTarget(e.target)||voice||transitioning)return;pointer={x:e.clientX,y:e.clientY,id:e.pointerId,axis:null,deleteArmed:false,recordArmed:false};held=false;heldAction=null;card.setPointerCapture?.(e.pointerId);hold=setTimeout(()=>{held=true;heldAction='edit'},HOLD_MS)});
card.addEventListener('pointermove',e=>{if(!pointer||held)return;const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y;if(Math.hypot(dx,dy)>DRAG_TOLERANCE){clearTimeout(hold);if(!pointer.axis){if(Math.abs(dx)>Math.abs(dy)*1.15)pointer.axis='horizontal';else if(Math.abs(dy)>Math.abs(dx)*1.15)pointer.axis='vertical'}}if(pointer.axis==='horizontal'){resetDeleteIndicator(false);resetRecordIndicator(false);card.style.transition='none';card.style.transform=`translate(${dx}px,0) rotate(${dx/35}deg)`;card.style.opacity=String(Math.max(.68,1-Math.abs(dx)/(window.innerWidth*1.8)))}else if(pointer.axis==='vertical'){resetCardStyle();if(dy<0){resetRecordIndicator(false);pointer.recordArmed=false;pointer.deleteArmed=setDeleteProgress(-dy)}else{resetDeleteIndicator(false);pointer.deleteArmed=false;pointer.recordArmed=setRecordProgress(dy)}}});
card.addEventListener('pointerup',e=>{if(!pointer){held=false;return}clearTimeout(hold);const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y,axis=pointer.axis,deleteArmed=pointer.deleteArmed,recordArmed=pointer.recordArmed;pointer=null;if(held){held=false;heldAction=null;selectMode('text');beginEditing();return}if(axis==='vertical'){if(deleteArmed&&dy<0){void removeCurrent();return}if(recordArmed&&dy>0){finishEditing();resetDeleteIndicator(false);resetRecordIndicator(false);selectMode('voice');void startVoice(side);return}resetDeleteIndicator(true);resetRecordIndicator(true);return}const swipe=axis==='horizontal'?core.classifySwipe(dx,0,SWIPE_THRESHOLD):null;if(swipe==='left'||swipe==='right'){void navigate(swipe==='left'?1:-1,swipe==='left'?-1:1);return}if(Math.hypot(dx,dy)<DRAG_TOLERANCE){resetCardStyle();tap();return}springBack()});
card.addEventListener('pointercancel',()=>{clearTimeout(hold);pointer=null;held=false;heldAction=null;resetDeleteIndicator(true);resetRecordIndicator(true);springBack()});card.addEventListener('contextmenu',e=>{if(!editing)e.preventDefault()});

function scrubTo(event){const indices=core.navigableIndices(folder());if(!indices.length)return;const rect=$('scrubber').getBoundingClientRect(),position=core.scrubPosition(event.clientX,rect.left,rect.width,indices.length);index=indices[position];side='front';editing=false;renderCard();const bubble=$('scrubBubble');bubble.value=`(${position+1})`;bubble.textContent=`(${position+1})`;bubble.style.left=`${Math.max(rect.left+24,Math.min(rect.right-24,event.clientX))}px`;bubble.style.top=`${event.clientY}px`;bubble.hidden=false}
$('scrubber').addEventListener('pointerdown',event=>{if(voice||view!=='deck'||!core.navigableIndices(folder()).length)return;scrubbing=true;try{$('scrubber').setPointerCapture?.(event.pointerId)}catch{}finishEditing();scrubTo(event)});
$('scrubber').addEventListener('pointermove',event=>{if(scrubbing)scrubTo(event)});const endScrub=()=>{if(!scrubbing)return;scrubbing=false;$('scrubBubble').hidden=true};$('scrubber').addEventListener('pointerup',endScrub);$('scrubber').addEventListener('pointercancel',endScrub);

$('folderBack').onclick=showFolders;$('addFolder').onclick=openFolderDialog;$('cancelFolder').onclick=closeFolderDialog;
$('folderForm').addEventListener('submit',event=>{event.preventDefault();const name=$('folderName').value;if(!core.folderName(name)){$('folderError').textContent='Escribe un nombre para la carpeta.';$('folderError').hidden=false;return}const created=core.createFolder(state,name);if(!created){$('folderError').textContent='Ya existe una carpeta con ese nombre.';$('folderError').hidden=false;return}closeFolderDialog();void persist();renderFolders();showToast('Carpeta creada')});
$('cancelDeleteFolder').onclick=closeDeleteFolder;$('confirmDeleteFolder').onclick=()=>{const removed=core.removeFolder(state,pendingDeleteFolderId);closeDeleteFolder();void persist();renderFolders();if(removed)showToast('Carpeta eliminada')};
$('cancelImport').onclick=closeImport;$('confirmImport').onclick=()=>{if(!pendingImport)return;const target=core.findFolder(state,pendingImport.folderId),count=core.appendCards(target,pendingImport.cards);closeImport();void persist();renderFolders();showToast(`${count} tarjeta${count===1?'':'s'} importada${count===1?'':'s'}`)};
$('fontControls').addEventListener('pointerdown',e=>e.preventDefault());$('fontDecrease').onclick=()=>changeFontScale(-.1);$('fontIncrease').onclick=()=>changeFontScale(.1);$('stop').onclick=()=>{if(voice)void InScreen.module.vozDetener()};$('listening').addEventListener('click',e=>{if(!e.target.closest('#stop')&&voice)void InScreen.module.vozDetener()});$('undo').onclick=()=>void undoRemove();
$('deny').onclick=()=>{$('consent').hidden=true;consentResolve?.(false);consentResolve=null};$('allow').onclick=()=>{allowSystem=true;localStorage.setItem(SYSTEM_VOICE_KEY,'allowed');$('consent').hidden=true;consentResolve?.(true);consentResolve=null};
for(const target of ['front','back']){const editor=editorFor(target);editor.addEventListener('input',()=>{const c=current();if(!c)return;c[fieldFor(target)]=editor.value;c.actualizada=Date.now();schedulePersist()});editor.addEventListener('blur',()=>{if(editing){commitEditor(target);editing=false;if(view==='deck')renderCard()}});editor.addEventListener('keydown',e=>e.stopPropagation())}
function updateViewport(){const viewport=window.visualViewport,height=Math.round(viewport?.height||window.innerHeight),keyboard=Boolean(viewport&&window.innerHeight-height>120),active=document.activeElement?.classList?.contains('card-editor')?document.activeElement:null;document.documentElement.style.setProperty('--viewport-height',`${height}px`);document.body.classList.toggle('keyboard-open',keyboard);if(keyboard&&active)requestAnimationFrame(()=>active.scrollIntoView({block:'center'}));if(keyboardWasOpen&&!keyboard&&active)active.blur();keyboardWasOpen=keyboard}
window.visualViewport?.addEventListener('resize',updateViewport);window.visualViewport?.addEventListener('scroll',updateViewport);window.addEventListener('resize',updateViewport);updateViewport();
window.addEventListener('inscreen:voz',async e=>{if(!voice)return;const d=e.detail||{};if(d.estado==='parcial')$('transcript').textContent=d.texto||'Escuchando…';if(d.estado==='error'){voice=false;renderCard()}if(d.estado==='final'){voice=false;const text=core.clean(d.texto);if(text){current()[fieldFor(voiceSide)]=text;current().actualizada=Date.now();await persist()}side=voiceSide;renderCard()}});
(async()=>{let saved=null;try{saved=JSON.parse(localStorage.getItem(STATE_KEY)||'null');if(!saved)saved=JSON.parse(localStorage.getItem(LEGACY_STATE_KEY)||'null')}catch{}state=core.normalize(saved);view=folder()?'deck':'folders';index=0;await persist();renderView()})().catch(error=>{document.body.textContent=error.message});
