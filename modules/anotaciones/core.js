(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.AnotacionesCore=api;}(globalThis,function(){
  'use strict';
  const VERSION=1;
  const clean=value=>String(value||'').trim().replace(/\s+/g,' ');
  const blank=()=>({id:`card-${Date.now()}-${Math.random().toString(16).slice(2)}`,cabecera:'',respuesta:'',actualizada:Date.now()});
  function normalize(value){
    const cards=Array.isArray(value?.tarjetas)?value.tarjetas.map(card=>({id:String(card?.id||blank().id),cabecera:clean(card?.cabecera),respuesta:clean(card?.respuesta),actualizada:Number(card?.actualizada)||Date.now()})):[];
    if(!cards.length||cards[cards.length-1].cabecera)cards.push(blank());
    return {version:VERSION,tarjetas:cards};
  }
  function canAdvance(state,index){return index<state.tarjetas.length-1||Boolean(state.tarjetas[index]?.cabecera);}
  function advance(state,index){if(index<state.tarjetas.length-1)return index+1;if(!canAdvance(state,index))return index;state.tarjetas.push(blank());return index+1;}
  function remove(state,index){state.tarjetas.splice(index,1);if(!state.tarjetas.length)state.tarjetas.push(blank());return Math.min(index,state.tarjetas.length-1);}
  function removeUndoable(state,index){
    const removed=state.tarjetas.splice(index,1)[0]||null;
    let replacementId=null;
    if(!state.tarjetas.length||state.tarjetas[state.tarjetas.length-1].cabecera){const replacement=blank();replacementId=replacement.id;state.tarjetas.push(replacement);}
    return {index:Math.min(index,state.tarjetas.length-1),removed,originalIndex:index,replacementId};
  }
  function restoreRemoved(state,operation){
    if(!operation?.removed)return 0;
    if(operation.replacementId){const replacement=state.tarjetas.findIndex(card=>card.id===operation.replacementId);if(replacement>=0)state.tarjetas.splice(replacement,1);}
    const index=Math.max(0,Math.min(Number(operation.originalIndex)||0,state.tarjetas.length));
    state.tarjetas.splice(index,0,operation.removed);
    return index;
  }
  function classifySwipe(dx,dy,threshold=50,dominance=1.15){
    if(Math.abs(dy)>threshold&&Math.abs(dy)>Math.abs(dx)*dominance)return dy<0?'up':'down';
    if(Math.abs(dx)>threshold&&Math.abs(dx)>Math.abs(dy)*dominance)return dx<0?'left':'right';
    return null;
  }
  return {VERSION,clean,blank,normalize,canAdvance,advance,remove,removeUndoable,restoreRemoved,classifySwipe};
}));
