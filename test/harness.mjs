// Test harness: runs a throwaway clone of the prepared server copy and drives it from the console,
// with mineflayer bots as players. It refuses to run against anything but a prepared copy on 127.0.0.1.
import { spawn, execSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, writeFileSync, copyFileSync, mkdirSync, rmSync } from 'node:fs'
import path from 'node:path'
import mineflayer from 'mineflayer'

export const BASE = process.env.WAYSTATION_TEST_BASE
if (!BASE || !existsSync(path.join(BASE, 'server/server.properties'))) {
  throw new Error('Set WAYSTATION_TEST_BASE to the folder holding the prepared throwaway copy in server/')
}
export const RUN = path.join(BASE, 'run')
export const DIR = path.join(RUN, 'server')
export const PORT = Number(process.env.WAYSTATION_TEST_PORT ?? 25599) // must match the prepared copy
// test/run.mjs runs a generated copy of the suites, in test/.run.
const ROOT = new URL(path.basename(path.dirname(new URL(import.meta.url).pathname)) === '.run' ? '../../' : '../', import.meta.url).pathname
const LIBS = path.join(ROOT, 'build/libs/')
const built = existsSync(LIBS) ? readdirSync(LIBS).filter(f => /^Waystation-.*\.jar$/.test(f)) : []
if (built.length !== 1) throw new Error(`Run ./gradlew clean jar hookJar first: expected one Waystation jar in build/libs, found ${built.length}`)
export const JAR = path.join(LIBS, built[0])
export const HOOK_JAR = path.join(ROOT, 'build/test-hook/DenyHook.jar')
export const PLUGIN_JAR = 'Waystation.jar'

const props = readFileSync(path.join(BASE, 'server/server.properties'), 'utf8')
if (!/^server-ip=127\.0\.0\.1$/m.test(props) || !new RegExp(`^server-port=${PORT}$`, 'm').test(props)) {
  throw new Error('The prepared copy must listen on 127.0.0.1:' + PORT)
}

export const sleep = ms => new Promise(r => setTimeout(r, ms))

export function freshCopy() {
  rmSync(RUN, { recursive: true, force: true, maxRetries: 10, retryDelay: 1000 })
  mkdirSync(RUN, { recursive: true })
  execSync(`cp -cR "${path.join(BASE, 'server')}" "${DIR}"`)
}

/**
 * A second, small throwaway server in its own folder under RUN: flat world, 127.0.0.1 only. Its Paper
 * jar and the files Paper would otherwise download are cloned from a server folder you already have.
 */
export function smallServer(name, { port, from, plugins = [], transfers = false }) {
  const dir = path.join(RUN, name)
  rmSync(dir, { recursive: true, force: true }); mkdirSync(path.join(dir, 'plugins'), { recursive: true })
  for (const f of ['paper.jar', 'libraries', 'versions', 'cache'].filter(f => existsSync(path.join(from, f)))) execSync(`cp -cR "${path.join(from, f)}" "${path.join(dir, f)}"`)
  for (const p of plugins) copyFileSync(p, path.join(dir, 'plugins', path.basename(p)))
  // The EULA is the owner's to accept: use the answer already given in the folder this comes from.
  if (!/^eula=true$/m.test(readFileSync(path.join(from, 'eula.txt'), 'utf8'))) throw new Error('The EULA isn\'t accepted in ' + from)
  copyFileSync(path.join(from, 'eula.txt'), path.join(dir, 'eula.txt'))
  writeFileSync(path.join(dir, 'server.properties'), [`server-ip=127.0.0.1`, `server-port=${port}`, 'online-mode=false',
    `accepts-transfers=${transfers}`, 'level-type=minecraft\\:flat', 'spawn-protection=0', 'enable-query=false', 'enable-rcon=false'].join('\n') + '\n')
  return dir
}

// The suites run on this: the default config with the worlds and groups they expect filled in.
const TEST_SETTINGS = [
  ['worlds: {}', `worlds:
  lobby:            {enabled: true, label: "<white>Lobby"}
  oneblock:         {enabled: true, label: "<aqua>OneBlock", enter-command: "ob join"}
  meadow:           {enabled: true, label: "<green>Meadow"}
  survival:         {enabled: true, label: "<dark_green>Survival"}
  creative:         {enabled: true, label: "<light_purple>Creative"}
  flatlands:        {enabled: true, label: "<aqua>Flatlands"}
  legacy:           {enabled: true, label: "<gold>Legacy"}
  kids_survival:    {enabled: true, label: "<yellow>Kids Survival"}
  kids_flatlands:   {enabled: true, label: "<yellow>Kids Flatlands"}
  friends_survival:  {enabled: true, label: "<red>Friends Survival"}
  friends_flatlands: {enabled: true, label: "<red>Friends Flatlands"}`],
  ['  platforms: {}', `  platforms:
    Lobby:           {label: "Lobby"}
    Survival:        {label: "Survival Line"}
    Creative:        {label: "Creative Line"}
    Legacy:          {label: "Legacy Line"}
    Kids_Survival:   {label: "Kids Survival Line",  design: toy, theme: toybox, frame-blocks: theme}
    Kids_Creative:   {label: "Kids Creative Line",  design: toy, theme: candy,  frame-blocks: theme}
    Friends_Survival: {label: "Friends Survival Line"}
    Friends_Creative: {label: "Friends Creative Line"}
    Quiet_Survival:  {label: "Quiet Line"}
    Quiet_Creative:  {label: "Quiet Creative Line"}`],
  ['hide-groups: []', 'hide-groups: [Quiet_Survival, Quiet_Creative]'],
  ['gentle-worlds: []', 'gentle-worlds: [kids_survival]'],
  ['allowed-worlds: []', 'allowed-worlds: [meadow, survival, kids_survival, friends_survival, quiet_survival] #'],
]

export function installJar({ testConfig = true } = {}) {
  copyFileSync(JAR, path.join(DIR, 'plugins', PLUGIN_JAR))
  const cfg = path.join(DIR, 'plugins/Waystation/config.yml')
  if (!testConfig || existsSync(cfg)) return
  let text = readFileSync(path.join(ROOT, 'src/main/resources/config.yml'), 'utf8')
  for (const [from, to] of TEST_SETTINGS) {
    if (!text.includes(from)) throw new Error('The default config no longer has: ' + from)
    text = text.replace(from, to)
  }
  mkdirSync(path.dirname(cfg), { recursive: true })
  writeFileSync(cfg, text)
}

export class Server {
  log = []
  /** dir and java default to the main copy; a second, smaller server passes its own. */
  constructor({ dir = DIR, java = 'java', memory = '3G' } = {}) { Object.assign(this, { dir, java, memory }) }
  async start() {
    const from = this.log.length
    this.proc = spawn(this.java, [`-Xms${this.memory}`, `-Xmx${this.memory}`, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Dwaystation.test.deny=meadow', '-jar', 'paper.jar', 'nogui'],
      { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] })
    this.exited = new Promise(r => this.proc.on('exit', r))
    const proc = this.proc; process.on('exit', () => { if (proc.exitCode === null) proc.kill('SIGKILL') }) // never leave a server behind
    const add = d => { for (const l of d.toString().split('\n')) if (l.trim()) this.log.push(l) }
    this.proc.stdout.on('data', add)
    this.proc.stderr.on('data', add)
    await this.waitFor(/Done \(\d/, 180000, from)
    await sleep(3000)
  }
  async waitFor(re, ms = 15000, from = 0) {
    const end = Date.now() + ms
    while (Date.now() < end) {
      const hit = this.log.slice(from).find(l => re.test(l))
      if (hit) return hit
      await sleep(100)
    }
    throw new Error('Timed out waiting for ' + re)
  }
  send(cmd) { this.proc.stdin.write(cmd + '\n') }
  /** Sends a console command and returns the log lines it produced. */
  async ask(cmd, ms = 700) {
    const from = this.log.length
    this.send(cmd)
    await sleep(ms)
    return this.log.slice(from).map(l => l.replace(/^\[[\d:]+ \w+\]: /, ''))
  }
  async count(selector, world) {
    const out = await this.ask(`execute in ${world} if entity ${selector}`, 400)
    const hit = out.find(l => /Test passed|Test failed/.test(l))
    const m = hit && hit.match(/count: (\d+)/)
    return m ? Number(m[1]) : 0
  }
  errors() {
    return this.log.filter(l => /Waystation/.test(l) && /(ERROR|Exception|SEVERE)/.test(l))
  }
  async stop() {
    this.send('stop')
    const done = await Promise.race([this.exited.then(() => true), sleep(90000).then(() => false)])
    if (!done) { console.log('  server stuck on stop: killing it'); await this.kill9() }
  }
  kill9() { this.proc.kill('SIGKILL'); return this.exited }
}

/** A bot that keeps score of chat and of which tagged display entities it was sent. */
export async function bot(username, { port = PORT, transferred = false } = {}) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port, username, version: '1.21.11', auth: 'offline', viewDistance: 'normal' })
  if (transferred) { // connect the way a client does after a transfer: handshake intent 3, not 2
    const write = b._client.write.bind(b._client)
    b._client.write = (name, data) => write(name, name === 'set_protocol' ? { ...data, nextState: 3 } : data)
  }
  b.chat_log = []
  b.on('messagestr', m => b.chat_log.push(m))
  b.on('error', e => console.log(`  [${username}] error: ${e.message}`))
  b.on('kicked', r => console.log(`  [${username}] kicked: ${JSON.stringify(r)}`))
  await new Promise((resolve, reject) => {
    b.once('spawn', resolve)
    b.once('end', () => reject(new Error(username + ' disconnected before spawning')))
    setTimeout(() => reject(new Error(username + ' spawn timeout')), 60000)
  })
  await sleep(1500)
  return b
}

export const displays = b => Object.values(b.entities).filter(e => e.name === 'block_display' || e.name === 'text_display')
export const near = (b, x, z, r = 12) => displays(b).filter(e => Math.hypot(e.position.x - x, e.position.z - z) < r)

/** Walks a bot in a straight line to a point, by its real physics. */
export async function walkTo(b, x, z, ms = 12000) {
  const end = Date.now() + ms
  b.setControlState('forward', true)
  b.setControlState('sprint', false)
  try {
    await sleep(400) // let any teleport we were just given land, so it isn't taken for arriving
    let last = b.entity.position.clone()
    while (Date.now() < end) {
      const p = b.entity.position
      if (Math.hypot(p.x - x, p.z - z) < 0.4) return true
      if (p.distanceTo(last) > 4) return true // teleported: stop walking at once
      last = p.clone()
      await b.lookAt(p.offset(x - p.x, 0, z - p.z).offset(0, 1.6, 0), true)
      await sleep(50)
    }
    return false
  } finally {
    b.setControlState('forward', false)
  }
}

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
