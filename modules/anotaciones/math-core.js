(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.AnotacionesMath=api;}(globalThis,function(){
  'use strict';
  const OPEN='\\(',CLOSE='\\)';
  const DELIMITERS=[{open:'$$',close:'$$'},{open:'\\[',close:'\\]'},{open:OPEN,close:CLOSE},{open:'$',close:'$'}];
  function escaped(source,index){let slashes=0;for(let at=index-1;at>=0&&source[at]==='\\';at--)slashes++;return slashes%2===1}
  function findDelimiter(source,from){
    let found=null;for(let at=from;at<source.length;at++){if(escaped(source,at))continue;for(const delimiter of DELIMITERS){if(!source.startsWith(delimiter.open,at)||delimiter.open==='$'&&(source[at+1]==='$'||source[at-1]==='$'))continue;found={...delimiter,start:at};break}if(found)break}return found;
  }
  function findClose(source,delimiter){
    for(let at=delimiter.start+delimiter.open.length;at<source.length;at++){if(escaped(source,at))continue;if(source.startsWith(delimiter.close,at)&&(delimiter.close!=='$'||source[at+1]!=='$'))return at}return -1;
  }
  function parse(value){
    const source=String(value??''),parts=[];let cursor=0;
    while(cursor<source.length){
      const delimiter=findDelimiter(source,cursor);if(!delimiter){parts.push({type:'text',value:source.slice(cursor)});break}const start=delimiter.start;
      if(start>cursor)parts.push({type:'text',value:source.slice(cursor,start)});
      const end=findClose(source,delimiter);if(end<0){parts.push({type:'text',value:source.slice(start)});break}
      const latex=source.slice(start+delimiter.open.length,end);if(delimiter.open==='$'&&(!latex.trim()||/^\s|\s$/.test(latex))){parts.push({type:'text',value:'$'});cursor=start+1;continue}
      parts.push({type:'math',latex,raw:source.slice(start,end+delimiter.close.length),start,end:end+delimiter.close.length});cursor=end+delimiter.close.length;
    }
    if(!source.length)return [{type:'text',value:''}];return parts;
  }
  const formula=latex=>`${OPEN}${String(latex??'')}${CLOSE}`;
  function insert(source,offset,latex){const text=String(source??''),at=Math.max(0,Math.min(Number(offset)||0,text.length)),raw=formula(latex);return {value:text.slice(0,at)+raw+text.slice(at),start:at,end:at+raw.length}}
  function replace(source,start,end,latex){const text=String(source??''),from=Math.max(0,Math.min(Number(start)||0,text.length)),to=Math.max(from,Math.min(Number(end)||from,text.length)),raw=formula(latex);return {value:text.slice(0,from)+raw+text.slice(to),start:from,end:from+raw.length}}
  return {OPEN,CLOSE,DELIMITERS,parse,formula,insert,replace};
}));
