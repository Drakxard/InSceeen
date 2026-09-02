(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.SynthesisMarkdown = api;
}(globalThis, function () {
  'use strict';

  const mathDelimiters = [
    { open: '$$', close: '$$' }, { open: '\\[', close: '\\]' },
    { open: '\\(', close: '\\)' }, { open: '$', close: '$' }
  ];
  const escaped = (text, at) => {
    let slashes = 0;
    for (let i = at - 1; i >= 0 && text[i] === '\\'; i--) slashes++;
    return slashes % 2 === 1;
  };

  function mathAt(text, at) {
    if (escaped(text, at)) return null;
    for (const delimiter of mathDelimiters) {
      if (!text.startsWith(delimiter.open, at)) continue;
      if (delimiter.open === '$' && (text[at - 1] === '$' || text[at + 1] === '$')) continue;
      for (let end = at + delimiter.open.length; end < text.length; end++) {
        if (!escaped(text, end) && text.startsWith(delimiter.close, end)) {
          if (delimiter.close === '$' && text[end + 1] === '$') continue;
          return text.slice(at, end + delimiter.close.length);
        }
      }
    }
    return null;
  }

  function appendInline(parent, value, documentRef) {
    const text = String(value ?? '');
    let at = 0;
    const appendText = raw => { if (raw) parent.appendChild(documentRef.createTextNode(raw)); };
    while (at < text.length) {
      const math = mathAt(text, at);
      if (math) { appendText(math); at += math.length; continue; }
      if (text[at] === '`') {
        const end = text.indexOf('`', at + 1);
        if (end > at + 1) {
          const code = documentRef.createElement('code');
          code.textContent = text.slice(at + 1, end); parent.appendChild(code); at = end + 1; continue;
        }
      }
      if (text.startsWith('**', at)) {
        const end = text.indexOf('**', at + 2);
        if (end > at + 2) {
          const strong = documentRef.createElement('strong');
          appendInline(strong, text.slice(at + 2, end), documentRef); parent.appendChild(strong); at = end + 2; continue;
        }
      }
      if (text[at] === '*' && text[at + 1] !== '*') {
        const end = text.indexOf('*', at + 1);
        if (end > at + 1) {
          const em = documentRef.createElement('em');
          appendInline(em, text.slice(at + 1, end), documentRef); parent.appendChild(em); at = end + 1; continue;
        }
      }
      if (text[at] === '[') {
        const labelEnd = text.indexOf('](', at + 1);
        const urlEnd = labelEnd < 0 ? -1 : text.indexOf(')', labelEnd + 2);
        if (labelEnd > at && urlEnd > labelEnd) {
          const label = text.slice(at + 1, labelEnd);
          const url = text.slice(labelEnd + 2, urlEnd).trim();
          if (/^https?:\/\//i.test(url)) {
            const link = documentRef.createElement('a');
            link.href = url; link.rel = 'noreferrer noopener'; link.target = '_blank';
            appendInline(link, label, documentRef); parent.appendChild(link);
          } else appendText(label);
          at = urlEnd + 1; continue;
        }
      }
      let next = at + 1;
      while (next < text.length && !'`*$[\\'.includes(text[next])) next++;
      appendText(text.slice(at, next)); at = next;
    }
  }

  const tableCells = line => line.trim().replace(/^\||\|$/g, '').split('|').map(value => value.trim());
  const tableRule = line => /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);

  function normalizeImportedMath(source) {
    const delimiters = String(source ?? '')
      .replace(/\\\\\[/g, '\\[').replace(/\\\\\]/g, '\\]')
      .replace(/\\\\\(/g, '\\(').replace(/\\\\\)/g, '\\)');
    return delimiters.replace(/(\$\$[\s\S]*?\$\$|\\\[[\s\S]*?\\\]|\\\([\s\S]*?\\\))/g, math =>
      math.replace(/\\\\(?=[A-Za-z])/g, '\\')
    );
  }

  function render(container, source) {
    const doc = container.ownerDocument;
    container.replaceChildren();
    const lines = normalizeImportedMath(source).replace(/\r\n?/g, '\n').split('\n');
    let index = 0;
    while (index < lines.length) {
      const line = lines[index];
      if (!line.trim()) { index++; continue; }
      const fence = /^\s*```([^`]*)$/.exec(line);
      if (fence) {
        const body = []; index++;
        while (index < lines.length && !/^\s*```\s*$/.test(lines[index])) body.push(lines[index++]);
        if (index < lines.length) index++;
        const pre = doc.createElement('pre'); const code = doc.createElement('code');
        code.textContent = body.join('\n');
        if (fence[1].trim()) code.dataset.language = fence[1].trim().slice(0, 40);
        pre.appendChild(code); container.appendChild(pre); continue;
      }
      const heading = /^(#{1,6})\s+(.+)$/.exec(line);
      if (heading) {
        const element = doc.createElement(`h${heading[1].length}`);
        appendInline(element, heading[2], doc); container.appendChild(element); index++; continue;
      }
      if (index + 1 < lines.length && line.includes('|') && tableRule(lines[index + 1])) {
        const table = doc.createElement('table'); const head = doc.createElement('thead'); const headRow = doc.createElement('tr');
        tableCells(line).forEach(value => { const cell = doc.createElement('th'); appendInline(cell, value, doc); headRow.appendChild(cell); });
        head.appendChild(headRow); table.appendChild(head); index += 2;
        const body = doc.createElement('tbody');
        while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
          const row = doc.createElement('tr');
          tableCells(lines[index++]).forEach(value => { const cell = doc.createElement('td'); appendInline(cell, value, doc); row.appendChild(cell); });
          body.appendChild(row);
        }
        table.appendChild(body); container.appendChild(table); continue;
      }
      const list = /^\s*(?:([-+*])|(\d+)\.)\s+(.+)$/.exec(line);
      if (list) {
        const ordered = Boolean(list[2]); const element = doc.createElement(ordered ? 'ol' : 'ul');
        while (index < lines.length) {
          const item = /^\s*(?:([-+*])|(\d+)\.)\s+(.+)$/.exec(lines[index]);
          if (!item || Boolean(item[2]) !== ordered) break;
          const li = doc.createElement('li'); appendInline(li, item[3], doc); element.appendChild(li); index++;
        }
        container.appendChild(element); continue;
      }
      if (/^\s*>\s?/.test(line)) {
        const quote = doc.createElement('blockquote'); const values = [];
        while (index < lines.length && /^\s*>\s?/.test(lines[index])) values.push(lines[index++].replace(/^\s*>\s?/, ''));
        appendInline(quote, values.join('\n'), doc); container.appendChild(quote); continue;
      }
      if (/^\s*(?:---+|___+|\*\*\*+)\s*$/.test(line)) { container.appendChild(doc.createElement('hr')); index++; continue; }
      const paragraph = [];
      while (index < lines.length && lines[index].trim()) {
        if (paragraph.length && (/^(#{1,6})\s+/.test(lines[index]) || /^\s*```/.test(lines[index]) || /^\s*(?:[-+*]|\d+\.)\s+/.test(lines[index]))) break;
        paragraph.push(lines[index++]);
      }
      const p = doc.createElement('p'); appendInline(p, paragraph.join('\n'), doc); container.appendChild(p);
    }
    return container;
  }

  return { render, mathAt, normalizeImportedMath };
}));
