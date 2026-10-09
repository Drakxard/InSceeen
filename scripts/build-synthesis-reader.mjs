import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Usage: node scripts/build-synthesis-reader.mjs <path-to-katex-0.16.22>
// No runtime dependency: native shouldInterceptRequest blocks external assets.
const root = fileURLToPath(new URL('../', import.meta.url));
const katexDir = path.resolve(process.argv[2] || path.join(root, '../v0-neon-database-persistence2-main/node_modules/katex'));
const metadata = JSON.parse(readFileSync(path.join(katexDir, 'package.json'), 'utf8'));
if (metadata.version !== '0.16.22') throw new Error('The reader must use the same KaTeX 0.16.22 as v0.');
const dist = path.join(katexDir, 'dist');
const css = readFileSync(path.join(dist, 'katex.min.css'), 'utf8').replace(/src:[^;}]+/g, declaration => {
  const font = declaration.match(/url\((?:["'])?(fonts\/[^\s)"']+\.woff2)(?:["'])?\)/);
  if (!font) throw new Error('Missing WOFF2 font in KaTeX CSS.');
  const bytes = readFileSync(path.join(dist, font[1]));
  return `src:url(data:font/woff2;base64,${bytes.toString('base64')}) format("woff2")`;
});
const js = readFileSync(path.join(dist, 'katex.min.js'), 'utf8').replace(/<\/script/gi, '<\\/script');
const license = readFileSync(path.join(katexDir, 'LICENSE'), 'utf8');
const template = readFileSync(path.join(root, 'scripts/synthesis-reader.template.html'), 'utf8');
const html = template.replace('__INSCREEN_KATEX_CSS__', () => css)
  .replace('__INSCREEN_KATEX_JS__', () => `/* KaTeX 0.16.22\n${license}\n*/\n${js}`);
writeFileSync(path.join(root, 'modules/sintesis/reader.html'), html);
console.log(`Self-contained synthesis reader: ${Buffer.byteLength(html)} bytes (KaTeX ${metadata.version}).`);
