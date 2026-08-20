(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.AnotacionesCore=api;}(globalThis,function(){
  'use strict';
  const VERSION=2;
  // Las tarjetas admiten texto en varias líneas; solo normalizamos los finales de línea.
  const clean=value=>String(value||'').replace(/\r\n?/g,'\n').trim();
  const folderName=value=>clean(value).replace(/\s+/g,' ');
  const makeId=prefix=>`${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const blank=()=>({id:makeId('card'),cabecera:'',respuesta:'',actualizada:Date.now()});
  const isSeparator=card=>card?.tipo==='separador';
  const clampChannel=value=>Math.max(0,Math.min(255,Math.round(Number(value)||0)));
  const normalizeColor=value=>({r:clampChannel(value?.r),g:clampChannel(value?.g),b:clampChannel(value?.b)});
  const colorLuminance=color=>(.2126*color.r+.7152*color.g+.0722*color.b)/255;
  function safeColor(value){
    const color=normalizeColor(value),luminance=colorLuminance(color);if(luminance>=.25&&luminance<=.78)return color;
    if(luminance<.25){const mix=(.25-luminance)/Math.max(.01,1-luminance);return normalizeColor({r:color.r+(255-color.r)*mix,g:color.g+(255-color.g)*mix,b:color.b+(255-color.b)*mix})}
    const ratio=.78/luminance;return normalizeColor({r:color.r*ratio,g:color.g*ratio,b:color.b*ratio});
  }
  function randomColor(random=Math.random){
    const hue=Math.floor(random()*360),saturation=62+random()*18,lightness=42+random()*20,c=(1-Math.abs(2*lightness/100-1))*saturation/100,x=c*(1-Math.abs((hue/60)%2-1)),m=lightness/100-c/2;
    const [r,g,b]=hue<60?[c,x,0]:hue<120?[x,c,0]:hue<180?[0,c,x]:hue<240?[0,x,c]:hue<300?[x,0,c]:[c,0,x];return safeColor({r:(r+m)*255,g:(g+m)*255,b:(b+m)*255});
  }
  const separator=(color=randomColor())=>({id:makeId('separator'),tipo:'separador',nombre:'',color:safeColor(color),actualizada:Date.now()});
  const isBlank=card=>!isSeparator(card)&&!clean(card?.cabecera)&&!clean(card?.respuesta);
  function normalizeCard(card){
    if(isSeparator(card))return {id:String(card?.id||makeId('separator')),tipo:'separador',nombre:clean(card?.nombre),color:safeColor(card?.color||randomColor()),actualizada:Number(card?.actualizada)||Date.now()};
    return {id:String(card?.id||blank().id),cabecera:clean(card?.cabecera),respuesta:clean(card?.respuesta),actualizada:Number(card?.actualizada)||Date.now()};
  }
  function normalizeCards(value){
    const cards=Array.isArray(value)?value.map(normalizeCard):[];
    if(!cards.length||!isBlank(cards[cards.length-1]))cards.push(blank());
    return cards;
  }
  function normalize(value){
    if(value&&Array.isArray(value.carpetas)){
      const carpetas=value.carpetas.map(folder=>{const tarjetas=normalizeCards(folder?.tarjetas),ultimaTarjetaId=String(folder?.ultimaTarjetaId||'');return {id:String(folder?.id||makeId('folder')),nombre:folderName(folder?.nombre)||'Sin nombre',tarjetas,ultimaTarjetaId:tarjetas.some(card=>card.id===ultimaTarjetaId)?ultimaTarjetaId:null}});
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
    const first=blank(),folder={id:makeId('folder'),nombre:normalized,tarjetas:[first],ultimaTarjetaId:first.id};state.carpetas.push(folder);return folder;
  }
  function removeFolder(state,id){
    const position=state.carpetas.findIndex(folder=>folder.id===id);if(position<0)return null;
    const [removed]=state.carpetas.splice(position,1);if(state.carpetaActivaId===id)state.carpetaActivaId=null;return removed;
  }
  function parseClipboard(text){
    return String(text||'').split(/\r?\n/).map((line,lineNumber)=>{
      const source=line.trim();if(!source)return null;
      const separatorMatch=source.match(/^-\{(.+)\}$/);if(separatorMatch){const nombre=clean(separatorMatch[1]);return nombre?{tipo:'separador',nombre,linea:lineNumber+1}:null}
      const separator=source.indexOf(':'),cabecera=clean(separator<0?source:source.slice(0,separator));if(!cabecera)return null;
      return {cabecera,respuesta:clean(separator<0?'':source.slice(separator+1)),linea:lineNumber+1};
    }).filter(Boolean);
  }
  function insertCards(folder,cards,position){
    if(!folder||!Array.isArray(cards)||!cards.length)return 0;
    const fallback=isBlank(folder.tarjetas.at(-1))?folder.tarjetas.length-1:folder.tarjetas.length,insertAt=Math.max(0,Math.min(Number.isInteger(position)?position:fallback,fallback));
    const now=Date.now(),created=cards.map(card=>{if(isSeparator(card)){const createdSeparator=separator(card.color);createdSeparator.nombre=clean(card.nombre);createdSeparator.actualizada=now;return createdSeparator}return {id:makeId('card'),cabecera:clean(card.cabecera),respuesta:clean(card.respuesta),actualizada:now}});
    folder.tarjetas.splice(insertAt,0,...created);if(!isBlank(folder.tarjetas.at(-1)))folder.tarjetas.push(blank());return created.length;
  }
  function appendCards(folder,cards){return insertCards(folder,cards)}
  function relativeInsertIndex(folder,anchorId,placement='after'){
    const fallback=isBlank(folder?.tarjetas?.at(-1))?folder.tarjetas.length-1:folder?.tarjetas?.length||0,index=folder?.tarjetas?.findIndex(item=>item.id===anchorId)??-1;
    if(index<0)return fallback;return Math.max(0,Math.min(index+(placement==='before'?0:1),fallback));
  }
  function insertSeparator(folder,position,color){if(!folder)return -1;const fallback=isBlank(folder.tarjetas.at(-1))?folder.tarjetas.length-1:folder.tarjetas.length,insertAt=Math.max(0,Math.min(Number.isInteger(position)?position:fallback,fallback));folder.tarjetas.splice(insertAt,0,separator(color));if(!isBlank(folder.tarjetas.at(-1)))folder.tarjetas.push(blank());return insertAt}
  function navigableIndices(folder){return folder?.tarjetas?.map((card,index)=>isSeparator(card)||clean(card.cabecera)?index:-1).filter(index=>index>=0)||[];}
  function scrubPosition(clientX,left,width,count){if(count<=1)return 0;const ratio=Math.max(0,Math.min(1,(clientX-left)/Math.max(1,width)));return Math.round(ratio*(count-1));}
  function canAdvance(state,index){return index<state.tarjetas.length-1||isSeparator(state.tarjetas[index])||Boolean(state.tarjetas[index]?.cabecera);}
  function advance(state,index){if(index<state.tarjetas.length-1)return index+1;if(!canAdvance(state,index))return index;state.tarjetas.push(blank());return index+1;}
  function remove(state,index){state.tarjetas.splice(index,1);if(!state.tarjetas.length)state.tarjetas.push(blank());return Math.min(index,state.tarjetas.length-1);}
  function removeUndoable(state,index){
    const removed=state.tarjetas.splice(index,1)[0]||null;let replacementId=null;
    if(!state.tarjetas.length||!isBlank(state.tarjetas[state.tarjetas.length-1])){const replacement=blank();replacementId=replacement.id;state.tarjetas.push(replacement);}
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
  return {VERSION,clean,folderName,blank,isBlank,isSeparator,normalizeColor,colorLuminance,safeColor,randomColor,separator,normalizeCards,normalize,findFolder,sameFolderName,canCreateFolder,createFolder,removeFolder,parseClipboard,insertCards,appendCards,relativeInsertIndex,insertSeparator,navigableIndices,scrubPosition,canAdvance,advance,remove,removeUndoable,restoreRemoved,classifySwipe};
}));
