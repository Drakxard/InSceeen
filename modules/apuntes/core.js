(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ApuntesCore = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  function cleanJsonOutput(output) {
    const text = String(output || '').trim();
    const fenced = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/i);
    return (fenced ? fenced[1] : text).trim();
  }

  function parseBlockOutput(output) {
    let parsed;
    try {
      parsed = JSON.parse(cleanJsonOutput(output));
    } catch {
      throw new Error('Groq no devolvió JSON válido.');
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || !Array.isArray(parsed.bloques)) {
      throw new Error('La respuesta no contiene el arreglo “bloques”.');
    }
    const seen = new Set();
    return parsed.bloques.map((item, index) => {
      if (!item || typeof item !== 'object' || Array.isArray(item)) throw new Error('Hay un bloque inválido.');
      if (Object.keys(item).some(key => key !== 'orden' && key !== 'cabecera')) {
        throw new Error('Cada bloque debe contener únicamente orden y cabecera.');
      }
      if (item.orden !== index + 1) throw new Error('Los bloques no mantienen un orden consecutivo.');
      if (typeof item.cabecera !== 'string' || /[\r\n]/.test(item.cabecera)) {
        throw new Error('Cada cabecera debe ocupar una sola línea.');
      }
      const header = item.cabecera.trim().replace(/\s+/g, ' ');
      if (!header || header.length > 160) throw new Error('Hay una cabecera vacía o demasiado extensa.');
      const normalized = header.toLocaleLowerCase('es');
      if (seen.has(normalized)) throw new Error('Groq devolvió cabeceras repetidas.');
      seen.add(normalized);
      return { order: item.orden, header };
    });
  }

  function hashText(value) {
    let hash = 0x811c9dc5;
    const text = String(value || '');
    for (let index = 0; index < text.length; index += 1) {
      hash ^= text.charCodeAt(index);
      hash = Math.imul(hash, 0x01000193);
    }
    return (hash >>> 0).toString(16).padStart(8, '0');
  }

  function buildCards(pageId, sourceHash, blocks) {
    return blocks.map(block => ({
      id: `${pageId}:${sourceHash.slice(0, 12)}:${block.order}:${hashText(block.header)}`,
      orden: block.order,
      cabecera: block.header,
      respuesta: null,
      respuestaActualizada: null,
    }));
  }

  function validSavedPage(page, sourceHash) {
    if (!page || page.sourceHash !== sourceHash || !Array.isArray(page.tarjetas)) return false;
    let previous = 0;
    return page.tarjetas.every(card => {
      if (!card || typeof card.id !== 'string' || !card.id || !Number.isInteger(card.orden) || card.orden <= previous) return false;
      previous = card.orden;
      return typeof card.cabecera === 'string' && card.cabecera.trim() && card.cabecera.length <= 160 &&
        (card.respuesta === null || typeof card.respuesta === 'string');
    });
  }

  return { cleanJsonOutput, parseBlockOutput, hashText, buildCards, validSavedPage };
}));
