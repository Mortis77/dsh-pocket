// Minimal static server for the in-app updater: only these two names are served.
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..', 'app')
const allowed = new Set(['version.json', 'dsh-pocket.apk'])
const types = {
  'version.json': 'application/json; charset=utf-8',
  'dsh-pocket.apk': 'application/vnd.android.package-archive',
}

createServer(async (req, res) => {
  const name = decodeURIComponent((req.url ?? '/').split('?')[0].replace(/^\//, '')) || 'version.json'
  if (!allowed.has(name)) {
    res.writeHead(404).end('not found')
    return
  }
  try {
    const body = await readFile(join(root, name))
    res.writeHead(200, { 'content-type': types[name], 'content-length': body.length, 'cache-control': 'no-store' })
    res.end(body)
  } catch {
    res.writeHead(500).end('error')
  }
}).listen(Number(process.env.PORT ?? 8897), '0.0.0.0', () => {
  console.log('serving', root, 'on', process.env.PORT ?? 8897)
})
