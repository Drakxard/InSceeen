(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.AnotacionesMath=api;}(globalThis,function(){
  'use strict';
  const OPEN='\\(',CLOSE='\\)';
  function parse(value){
    const source=String(value??''),parts=[];let cursor=0;
    while(cursor<source.length){
      const start=source.indexOf(OPEN,cursor);if(start<0){parts.push({type:'text',value:source.slice(cursor)});break}
      if(start>cursor)parts.push({type:'text',value:source.slice(cursor,start)});
      const end=source.indexOf(CLOSE,start+OPEN.length);if(end<0){parts.push({type:'text',value:source.slice(start)});break}
      parts.push({type:'math',latex:source.slice(start+OPEN.length,end),raw:source.slice(start,end+CLOSE.length),start,end:end+CLOSE.length});cursor=end+CLOSE.length;
    }
    if(!source.length)return [{type:'text',value:''}];return parts;
  }
  const formula=latex=>`${OPEN}${String(latex??'')}${CLOSE}`;
  function insert(source,offset,latex){const text=String(source??''),at=Math.max(0,Math.min(Number(offset)||0,text.length)),raw=formula(latex);return {value:text.slice(0,at)+raw+text.slice(at),start:at,end:at+raw.length}}
  function replace(source,start,end,latex){const text=String(source??''),from=Math.max(0,Math.min(Number(start)||0,text.length)),to=Math.max(from,Math.min(Number(end)||from,text.length)),raw=formula(latex);return {value:text.slice(0,from)+raw+text.slice(to),start:from,end:from+raw.length}}
  return {OPEN,CLOSE,parse,formula,insert,replace};
}));
