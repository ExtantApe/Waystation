// Harness for the return suite: fresh, empty Paper servers in a temp folder on 127.0.0.1, driven from their consoles,
// with mineflayer bots as players. Unlike harness.mjs it needs no copy of a real server, since the return command
// is for a one-world server without Multiverse. It never touches any folder it didn't create.
// Adapted from ReturnGate's harness, which this suite replaces.
import { spawn, execSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, writeFileSync, copyFileSync, mkdirSync, rmSync, mkdtempSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import net from 'node:net'
import mineflayer from 'mineflayer'

export const sleep = ms => new Promise(r => setTimeout(r, ms))

// test/run.mjs runs a generated copy of the suites, in test/.run.
const ROOT = new URL(path.basename(path.dirname(new URL(import.meta.url).pathname)) === '.run' ? '../../' : '../', import.meta.url).pathname
const LIBS = path.join(ROOT, 'build/libs/')
const built = existsSync(LIBS) ? readdirSync(LIBS).filter(f => /^Waystation-.*\.jar$/.test(f)) : []
if (built.length !== 1) throw new Error(`Run ./gradlew clean shadowJar first: expected one Waystation jar in build/libs, found ${built.length}`)
export const JAR = path.join(LIBS, built[0])

// A folder holding paper-<version>.jar for each version, optionally mojang_<version>.jar so Paper needn't download it,
// and plugins/ with ViaVersion, ViaBackwards and LuckPerms jars.
export const JARS = process.env.WAYSTATION_TEST_JARS
if (!JARS || !existsSync(path.join(JARS, 'plugins'))) throw new Error('Set WAYSTATION_TEST_JARS to a folder with paper-<version>.jar files and plugins/')
// Starting a server means accepting Mojang's EULA. That's the owner's call, so it has to be said out loud.
if (process.env.WAYSTATION_TEST_EULA !== 'accept') throw new Error('Set WAYSTATION_TEST_EULA=accept to accept the Minecraft EULA for these throwaway test servers')
export const JAVA = process.env.WAYSTATION_TEST_JAVA ?? 'java'
/** The bots' game version. 26.1 joins a 26.1 server directly; newer servers let them in through ViaVersion. */
export const CLIENT = process.env.WAYSTATION_TEST_CLIENT ?? '26.1'

export const BASE = process.env.WAYSTATION_TEST_DIR ?? mkdtempSync(path.join(os.tmpdir(), 'waystation-return-test-'))
mkdirSync(BASE, { recursive: true })

/** A fresh server folder: flat world, 127.0.0.1 only, offline mode, with ViaVersion and ViaBackwards. */
export function freshServer(name, { version, port, transfers = true, plugins = [] }) {
  const dir = path.join(BASE, name)
  rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 500 })
  mkdirSync(path.join(dir, 'plugins'), { recursive: true })
  // Reuse the libraries Paper downloaded on an earlier run of the same version, if there was one.
  const primed = path.join(BASE, `.primed-${version}`)
  if (existsSync(primed)) for (const f of ['libraries', 'versions', 'cache']) if (existsSync(path.join(primed, f))) execSync(`cp -cR "${path.join(primed, f)}" "${path.join(dir, f)}"`)
  copyFileSync(path.join(JARS, `paper-${version}.jar`), path.join(dir, 'paper.jar'))
  const mojang = path.join(JARS, `mojang_${version}.jar`)
  if (existsSync(mojang) && !existsSync(path.join(dir, 'cache'))) { mkdirSync(path.join(dir, 'cache')); copyFileSync(mojang, path.join(dir, 'cache', path.basename(mojang))) }
  for (const p of [pluginJar('ViaVersion-'), pluginJar('ViaBackwards-'), pluginJar('LuckPerms-'), ...plugins]) copyFileSync(p, path.join(dir, 'plugins', path.basename(p)))
  writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n')
  writeFileSync(path.join(dir, 'server.properties'), [
    'server-ip=127.0.0.1', `server-port=${port}`, 'online-mode=false', `accepts-transfers=${transfers}`,
    'level-type=minecraft\\:flat', 'spawn-protection=0', 'spawn-monsters=false', 'difficulty=normal', 'pvp=true',
    'white-list=false', 'enforce-whitelist=false', 'enable-query=false', 'enable-rcon=false', 'view-distance=4', 'simulation-distance=4', 'max-players=20',
  ].join('\n') + '\n')
  return dir
}

/** A plugin jar from WAYSTATION_TEST_JARS/plugins, found by the start of its name. */
export function pluginJar(start) {
  const dir = path.join(JARS, 'plugins'), hit = readdirSync(dir).find(f => f.toLowerCase().startsWith(start.toLowerCase()) && f.endsWith('.jar'))
  if (!hit) throw new Error(`No ${start}*.jar in ${dir}`)
  return path.join(dir, hit)
}

export function setProperty(dir, key, value) {
  const p = path.join(dir, 'server.properties')
  writeFileSync(p, readFileSync(p, 'utf8').replace(new RegExp(`^${key}=.*$`, 'm'), `${key}=${value}`))
}

/** Waystation's shipped config.yml with these replacements, so every other setting stays as a new server gets it. */
export function writeConfig(dir, replacements) {
  let text = readFileSync(path.join(ROOT, 'src/main/resources/config.yml'), 'utf8')
  for (const [from, to] of replacements) {
    if (!text.includes(from)) throw new Error('config.yml has no ' + JSON.stringify(from))
    text = text.replace(from, to)
  }
  mkdirSync(path.join(dir, 'plugins/Waystation'), { recursive: true })
  writeFileSync(path.join(dir, 'plugins/Waystation/config.yml'), text)
}

export class Server {
  log = []
  constructor(dir, version, port) { Object.assign(this, { dir, version, port }) }
  async start() {
    const from = this.log.length
    this.proc = spawn(JAVA, ['-Xms1G', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'paper.jar', 'nogui'],
      { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] })
    this.exited = new Promise(r => this.proc.on('exit', r))
    const proc = this.proc; process.on('exit', () => { if (proc.exitCode === null) proc.kill('SIGKILL') }) // never leave a server behind
    const add = d => { for (const l of d.toString().split('\n')) if (l.trim()) this.log.push(l) }
    this.proc.stdout.on('data', add)
    this.proc.stderr.on('data', add)
    await this.waitFor(/Done \(\d/, 300000, from)
    // Keep the libraries Paper downloaded, so the next fresh folder of this version doesn't fetch them again.
    const primed = path.join(BASE, `.primed-${this.version}`)
    if (!existsSync(primed)) {
      mkdirSync(primed)
      for (const f of ['libraries', 'versions', 'cache']) if (existsSync(path.join(this.dir, f))) execSync(`cp -cR "${path.join(this.dir, f)}" "${path.join(primed, f)}"`)
    }
    for (const c of ['time set day', 'weather clear', 'gamerule advance_time false', 'gamerule doDaylightCycle false',
      'gamerule spawn_mobs false', 'gamerule doMobSpawning false', 'gamerule advance_weather false', 'gamerule doWeatherCycle false',
      'gamerule natural_health_regeneration false', 'gamerule naturalRegeneration false']) this.send(c)
    await sleep(1500)
    return this
  }
  async waitFor(re, ms = 15000, from = 0) {
    const end = Date.now() + ms
    while (Date.now() < end) {
      const hit = this.log.slice(from).find(l => re.test(l))
      if (hit) return hit
      if (this.proc.exitCode !== null) throw new Error(`Server ${this.port} exited while waiting for ${re}`)
      await sleep(100)
    }
    throw new Error('Timed out waiting for ' + re)
  }
  send(cmd) { this.proc.stdin.write(cmd + '\n') }
  async ask(cmd, ms = 700) {
    const from = this.log.length
    this.send(cmd)
    await sleep(ms)
    return this.log.slice(from).map(l => l.replace(/^\[[\d:]+ \w+\]: /, ''))
  }
  errors() {
    return this.log.filter(l => /(ERROR|SEVERE|Exception)/.test(l) && /Waystation|waystation/.test(l))
  }
  since(from) { return this.log.slice(from) }
  async stop() {
    if (this.proc.exitCode !== null) return
    this.send('stop')
    const done = await Promise.race([this.exited.then(() => true), sleep(90000).then(() => false)])
    if (!done) { console.log('  server stuck on stop: killing it'); await this.kill9() }
  }
  kill9() { this.proc.kill('SIGKILL'); return this.exited }
}

/**
 * A bot that keeps chat, action bar and the commands it was sent, and records any transfer packet
 * instead of following it. transferred: connect the way a client does after a transfer (handshake intent 3).
 *
 * physics: false stops the bot sending movement packets of its own. On Paper 26.3 behind ViaVersion 5.12, a
 * 26.1 bot is disconnected for "invalid player movement" when it moves. So on 26.3 the bots stand still.
 */
export async function bot(username, { port, transferred = false, physics = true, cookies = null } = {}) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port, username, version: CLIENT, auth: 'offline', viewDistance: 'tiny', physicsEnabled: physics })
  if (cookies) { // as a real client keeps transfer cookies across connections
    b._client.on('store_cookie', d => cookies.set(d.key, d.value))
    b._client.on('cookie_request', d => b._client.write('cookie_response', { key: d.cookie, value: cookies.get(d.cookie) }))
  }
  b.physicsOn = physics
  // Raw packet writer for the tests' own movement packets.
  b.send = b._client.write.bind(b._client)
  if (!physics) { // mineflayer still answers each teleport with a position packet: drop it, the teleport confirm is enough
    b._client.write = (name, data) => (/^(position|position_look|look|flying)$/.test(name) ? undefined : b.send(name, data))
  }
  if (transferred) {
    const write = b._client.write.bind(b._client)
    b._client.write = (name, data) => write(name, name === 'set_protocol' ? { ...data, nextState: 3 } : data)
  }
  b.chat_log = []
  b.bar_log = []
  b.transfers = []
  b.sounds = []
  b.commands = new Set()
  b.on('messagestr', (m, position) => (position === 'game_info' ? b.bar_log : b.chat_log).push(m))
  b.on('actionBar', m => b.bar_log.push(m.toString()))
  // Raw packets too, in case mineflayer doesn't turn them into events: the text is somewhere in the JSON.
  b._client.on('action_bar', d => b.bar_log.push(JSON.stringify(d)))
  b._client.on('system_chat', d => { if (d.isActionBar) b.bar_log.push(JSON.stringify(d)) })
  b._client.on('transfer', d => b.transfers.push(d))
  for (const name of ['sound_effect', 'entity_sound_effect']) b._client.on(name, d => b.sounds.push(d))
  b._client.on('declare_commands', p => {
    const root = p.nodes[p.rootIndex]
    b.commands = new Set(root.children.map(i => p.nodes[i]?.extraNodeData?.name).filter(Boolean))
  })
  b.ended = new Promise(r => b.once('end', r))
  b.on('error', e => console.log(`  [${username}] error: ${e.message}`))
  b.on('kicked', r => { b.kick = typeof r === 'string' ? r : JSON.stringify(r) })
  await new Promise((resolve, reject) => {
    b.once('spawn', resolve)
    b.once('end', () => reject(new Error(username + ' disconnected before spawning')))
    setTimeout(() => reject(new Error(username + ' spawn timeout')), 60000)
  })
  await settle(b)
  return b
}

/** Waits until the bot stands on the ground and has stopped moving. */
export async function settle(b, ms = 8000) {
  if (!b.physicsOn) return sleep(500)
  const end = Date.now() + ms
  let last = b.entity.position.clone()
  await sleep(500)
  while (Date.now() < end) {
    const p = b.entity.position
    if (b.entity.onGround && p.distanceTo(last) < 0.001) return
    last = p.clone()
    await sleep(250)
  }
}

export const said = (b, re) => b.chat_log.some(m => re.test(m))
export const saidCount = (b, re) => b.chat_log.filter(m => re.test(m)).length

export const results = []
export function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
  return ok
}
export function summary() {
  const failed = results.filter(r => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} passed`)
  for (const f of failed) console.log('  FAILED: ' + f.name + (f.detail ? ' (' + f.detail + ')' : ''))
  if (failed.length) process.exitCode = 1
  return failed.length === 0
}

const varInt = n => { const out = []; do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; out.push(b) } while (n); return Buffer.from(out) }

/**
 * Stands in for the destination server's status check: answers a server list ping the way a Minecraft
 * server does, so one real server is enough. stop() makes the destination look offline.
 */
export class FakeStatus {
  sockets = new Set()
  constructor(port) { this.port = port }
  async start() {
    const json = Buffer.from(JSON.stringify({ version: { name: 'fake', protocol: 775 }, players: { max: 1, online: 0 }, description: { text: 'fake' } }))
    const body = Buffer.concat([varInt(0), varInt(json.length), json])
    this.server = net.createServer(s => {
      this.sockets.add(s); s.on('close', () => this.sockets.delete(s)); s.on('error', () => {})
      s.once('data', () => s.end(Buffer.concat([varInt(body.length), body])))
    })
    await new Promise((resolve, reject) => { this.server.once('error', reject); this.server.listen(this.port, '127.0.0.1', resolve) })
    return this
  }
  async stop() {
    if (!this.server?.listening) return
    for (const s of this.sockets) s.destroy()
    await new Promise(r => this.server.close(r))
  }
  kill9() { return this.stop() }
  errors() { return [] }
}
