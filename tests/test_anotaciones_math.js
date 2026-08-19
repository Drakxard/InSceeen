const test=require('node:test'),assert=require('node:assert/strict'),math=require('../modules/anotaciones/math-core.js');

test('parses mixed text and inline formulas without losing source offsets',()=>{const source='Antes \\(x^2\\) después';const parts=math.parse(source);assert.deepEqual(parts.map(part=>part.type),['text','math','text']);assert.equal(parts[1].latex,'x^2');assert.equal(source.slice(parts[1].start,parts[1].end),'\\(x^2\\)')});
test('keeps unmatched latex delimiters as visible text',()=>{assert.deepEqual(math.parse('Error \\(x^2'),[{type:'text',value:'Error '},{type:'text',value:'\\(x^2'}])});
test('inserts a formula at the requested caret without replacing selected text',()=>{const result=math.insert('abc',2,'\\frac{1}{2}');assert.equal(result.value,'ab\\(\\frac{1}{2}\\)c');assert.equal(result.value.slice(result.end),'c')});
test('replaces exactly one existing formula',()=>{const source='a \\(x\\) b \\(y\\)',start=source.indexOf('\\(x'),end=start+'\\(x\\)'.length,result=math.replace(source,start,end,'x^2');assert.equal(result.value,'a \\(x^2\\) b \\(y\\)')});
