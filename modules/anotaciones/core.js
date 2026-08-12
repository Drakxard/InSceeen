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
  return {VERSION,clean,blank,normalize,canAdvance,advance,remove};
}));
