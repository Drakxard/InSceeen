import assert from 'node:assert/strict'
import test from 'node:test'
import { createRequire } from 'node:module'
import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { PassThrough } from 'node:stream'

const source = resolve(process.env.CURSADO_SOURCE_DIR || '.cursado-reader')
const require = createRequire(join(source, 'package.json'))
const { transform } = require('esbuild')
const React = require('react')
const { renderToPipeableStream } = require('react-dom/server')

function render(Component) {
  return new Promise((resolve, reject) => {
    const output = new PassThrough()
    let html = ''
    output.on('data', chunk => { html += chunk })
    output.on('end', () => resolve(html))
    const stream = renderToPipeableStream(React.createElement(Component, { title: 'Importantes sin conexión' }), {
      onAllReady() { stream.pipe(output) },
      onError: reject,
    })
  })
}

test('native dynamic renders import() namespaces and selected component exports', async () => {
  const directory = await mkdtemp(join(source, '.dynamic-test-'))
  try {
    const { code } = await transform(await readFile('scripts/cursado-native-dynamic.ts', 'utf8'), { loader: 'ts', format: 'esm' })
    const file = join(directory, 'dynamic.mjs')
    await writeFile(file, code)
    const { default: dynamic } = await import(pathToFileURL(file).href)
    const Component = ({ title }) => React.createElement('h1', null, title)
    // ES module namespace objects cannot be coerced to a primitive. Wrapping
    // the namespace in another default export caused the reported React crash.
    const namespace = Object.assign(Object.create(null), { default: Component })
    for (const loaded of [namespace, Component]) {
      const html = await render(dynamic(async () => loaded))
      assert.match(html, /<h1>Importantes sin conexión<\/h1>/)
    }
  } finally { await rm(directory, { recursive: true, force: true }) }
})
