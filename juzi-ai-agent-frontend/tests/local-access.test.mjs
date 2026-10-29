import assert from 'node:assert/strict'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer as createNetServer } from 'node:net'
import test from 'node:test'
import { createServer, preview } from 'vite'

const root = fileURLToPath(new URL('../', import.meta.url))
const configFile = fileURLToPath(new URL('../vite.config.ts', import.meta.url))

async function availablePort() {
  const probe = createNetServer()
  await new Promise((resolve, reject) => {
    probe.once('error', reject)
    probe.listen(0, '127.0.0.1', resolve)
  })
  const port = probe.address().port
  await new Promise((resolve, reject) => probe.close(error => error ? reject(error) : resolve()))
  return port
}

test('development server listens only on loopback and keeps its local API proxy', { timeout: 15000 }, async () => {
  // Vite 对 port: 0 会回退默认端口；测试使用临时端口，避免打断正在运行的开发服务。
  const server = await createServer({ root, configFile, server: { port: await availablePort() }, logLevel: 'silent' })
  try {
    await server.listen()
    const address = server.httpServer.address()
    assert.equal(address.address, '127.0.0.1')
    assert.equal(server.config.server.proxy['/api'].target, 'http://127.0.0.1:8123')
    const response = await fetch(`http://127.0.0.1:${address.port}/`)
    assert.equal(response.status, 200)
    assert.match(await response.text(), /id="app"/)
    const longUrl = `http://127.0.0.1:${address.port}/?message=${encodeURIComponent('😀'.repeat(4000))}`
    assert.equal((await fetch(longUrl)).status, 200, '4000 个 Unicode 字符不能先被前端服务器拒绝')
  } finally {
    await server.close()
  }
})

test('preview server listens only on loopback and inherits the local API proxy', { timeout: 15000 }, async () => {
  const output = await mkdtemp(join(tmpdir(), 'juzi-local-preview-'))
  let server
  try {
    await writeFile(join(output, 'index.html'), '<html><body>local preview</body></html>')
    server = await preview({ root, configFile, build: { outDir: output }, preview: { port: 0 }, logLevel: 'silent' })
    const address = server.httpServer.address()
    assert.equal(address.address, '127.0.0.1')
    assert.equal(server.config.preview.proxy['/api'].target, 'http://127.0.0.1:8123')
    const response = await fetch(`http://127.0.0.1:${address.port}/`)
    assert.equal(response.status, 200)
    assert.match(await response.text(), /local preview/)
    const longUrl = `http://127.0.0.1:${address.port}/?message=${encodeURIComponent('😀'.repeat(4000))}`
    assert.equal((await fetch(longUrl)).status, 200, '预览服务器同样支持默认输入边界')
  } finally {
    if (server) {
      await new Promise((resolve, reject) => server.httpServer.close(error => error ? reject(error) : resolve()))
    }
    await rm(output, { recursive: true, force: true })
  }
})
