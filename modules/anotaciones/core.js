(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.AnotacionesCore=api;}(globalThis,function(){
  'use strict';
  const VERSION=2;
  // Las tarjetas admiten texto en varias líneas; solo normalizamos los finales de línea.
  const clean=value=>String(value||'').replace(/\r\n?/g,'\n').trim();
  const folderName=value=>clean(value).replace(/\s+/g,' ');
  const makeId=prefix=>`${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const blank=()=>({id:makeId('card'),cabecera:'',respuesta:'',actualizada:Date.now()});
  const isBlank=card=>!clean(card?.cabecera)&&!clean(card?.respuesta);
  function normalizeCards(value){
    const cards=Array.isArray(value)?value.map(card=>({id:String(card?.id||blank().id),cabecera:clean(card?.cabecera),respuesta:clean(card?.respuesta),actualizada:Number(card?.actualizada)||Date.now()})):[];
    if(!cards.length||!isBlank(cards[cards.length-1]))cards.push(blank());
    return cards;
  }
  function normalize(value){
    if(value&&Array.isArray(value.carpetas)){
      const carpetas=value.carpetas.map(folder=>({id:String(folder?.id||makeId('folder')),nombre:folderName(folder?.nombre)||'Sin nombre',tarjetas:normalizeCards(folder?.tarjetas)}));
      const requested=String(value.carpetaActivaId||'');
      return {version:VERSION,carpetas,carpetaActivaId:carpetas.some(folder=>folder.id===requested)?requested:null};
    }
    if(value&&Array.isArray(value.tarjetas)){
      const legacy={id:makeId('folder'),nombre:'General',tarjetas:normalizeCards(value.tarjetas)};
      return {version:VERSION,carpetas:[legacy],carpetaActivaId:legacy.id};
    }
    return {version:VERSION,carpetas:[],carpetaActivaId:null};
  }
  function findFolder(state,id){return state.carpetas.find(folder=>folder.id===id)||null;}
  function sameFolderName(left,right){return folderName(left).toLocaleLowerCase('es')===folderName(right).toLocaleLowerCase('es');}
  function canCreateFolder(state,name){const normalized=folderName(name);return Boolean(normalized)&&!state.carpetas.some(folder=>sameFolderName(folder.nombre,normalized));}
  function createFolder(state,name){
    const normalized=folderName(name);if(!canCreateFolder(state,normalized))return null;
    const folder={id:makeId('folder'),nombre:normalized,tarjetas:[blank()]};state.carpetas.push(folder);return folder;
  }
  function removeFolder(state,id){
    const position=state.carpetas.findIndex(folder=>folder.id===id);if(position<0)return null;
    const [removed]=state.carpetas.splice(position,1);if(state.carpetaActivaId===id)state.carpetaActivaId=null;return removed;
  }
  function parseClipboard(text){
    return String(text||'').split(/\r?\n/).map((line,lineNumber)=>{
      const source=line.trim();if(!source)return null;
      const separator=source.indexOf(':'),cabecera=clean(separator<0?source:source.slice(0,separator));if(!cabecera)return null;
      return {cabecera,respuesta:clean(separator<0?'':source.slice(separator+1)),linea:lineNumber+1};
    }).filter(Boolean);
  }
  function appendCards(folder,cards){
    if(!folder||!Array.isArray(cards)||!cards.length)return 0;
    const insertAt=isBlank(folder.tarjetas.at(-1))?folder.tarjetas.length-1:folder.tarjetas.length;
    const now=Date.now(),created=cards.map(card=>({id:makeId('card'),cabecera:clean(card.cabecera),respuesta:clean(card.respuesta),actualizada:now}));
    folder.tarjetas.splice(insertAt,0,...created);if(!isBlank(folder.tarjetas.at(-1)))folder.tarjetas.push(blank());return created.length;
  }
  function navigableIndices(folder){return folder?.tarjetas?.map((card,index)=>clean(card.cabecera)?index:-1).filter(index=>index>=0)||[];}
  function scrubPosition(clientX,left,width,count){if(count<=1)return 0;const ratio=Math.max(0,Math.min(1,(clientX-left)/Math.max(1,width)));return Math.round(ratio*(count-1));}
  function canAdvance(state,index){return index<state.tarjetas.length-1||Boolean(state.tarjetas[index]?.cabecera);}
  function advance(state,index){if(index<state.tarjetas.length-1)return index+1;if(!canAdvance(state,index))return index;state.tarjetas.push(blank());return index+1;}
  function remove(state,index){state.tarjetas.splice(index,1);if(!state.tarjetas.length)state.tarjetas.push(blank());return Math.min(index,state.tarjetas.length-1);}
  function removeUndoable(state,index){
    const removed=state.tarjetas.splice(index,1)[0]||null;let replacementId=null;
    if(!state.tarjetas.length||state.tarjetas[state.tarjetas.length-1].cabecera){const replacement=blank();replacementId=replacement.id;state.tarjetas.push(replacement);}
    return {index:Math.min(index,state.tarjetas.length-1),removed,originalIndex:index,replacementId};
  }
  function restoreRemoved(state,operation){
    if(!operation?.removed)return 0;if(operation.replacementId){const replacement=state.tarjetas.findIndex(card=>card.id===operation.replacementId);if(replacement>=0)state.tarjetas.splice(replacement,1);}
    const index=Math.max(0,Math.min(Number(operation.originalIndex)||0,state.tarjetas.length));state.tarjetas.splice(index,0,operation.removed);return index;
  }
  function classifySwipe(dx,dy,threshold=50,dominance=1.15){
    if(Math.abs(dy)>threshold&&Math.abs(dy)>Math.abs(dx)*dominance)return dy<0?'up':'down';
    if(Math.abs(dx)>threshold&&Math.abs(dx)>Math.abs(dy)*dominance)return dx<0?'left':'right';return null;
  }
  return {VERSION,clean,folderName,blank,isBlank,normalizeCards,normalize,findFolder,sameFolderName,canCreateFolder,createFolder,removeFolder,parseClipboard,appendCards,navigableIndices,scrubPosition,canAdvance,advance,remove,removeUndoable,restoreRemoved,classifySwipe};
}));
