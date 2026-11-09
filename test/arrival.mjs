// The arrival flow: the lobby gate into the station, and the welcome inside it. Two small made-up servers on
// 127.0.0.1:25653, one after the other: fresh Paper with Multiverse-Core and LuckPerms, first without Latchkey (the lobby
// comes from Waystation's own config) and then with Latchkey 0.3.0 (the lobby comes from Latchkey, read by name).
// No copy of a real server.
//
// WAYSTATION_ARRIVAL_VERSION is 26.1.2 (default), 26.2 or 26.3. LATCHKEY_JAR is a Latchkey 0.3.0 jar.
// ./gradlew clean jar, then:
// WAYSTATION_TEST_JARS=<folder> WAYSTATION_TEST_EULA=accept WAYSTATION_TEST_JAVA=<java 25> LATCHKEY_JAR=<jar> node test/arrival.mjs
import { JAR, freshServer, writeConfig, pluginJar, Server, bot, sleep, check, summary, BASE } from './return-harness.mjs'
import { existsSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'

const V = process.env.WAYSTATION_ARRIVAL_VERSION ?? '26.1.2'
if (!['26.1.2', '26.2', '26.3'].includes(V)) throw new Error('WAYSTATION_ARRIVAL_VERSION must be 26.1.2, 26.2 or 26.3')
const LATCHKEY = process.env.LATCHKEY_JAR
if (!LATCHKEY || !existsSync(LATCHKEY)) throw new Error('Set LATCHKEY_JAR to a Latchkey 0.3.0 jar')
const PORT = 25653, LABEL = '<gold><bold>Central Station</bold>'

/** One gate from gates.yml, read as plain text, so the test doesn't trust the plugin's own reader. */
function gate(folder, id) {
  const f = path.join(folder, 'plugins/Waystation/gates.yml')
  if (!existsSync(f)) return null
  const lines = readFileSync(f, 'utf8').split('\n')
  const i = lines.findIndex(l => l === `  ${id}:`)
  if (i < 0) return null
  const g = { id }
  for (let j = i + 1; j < lines.length && lines[j].startsWith('    '); j++) {
    const m = /^    ([a-z-]+): (.*)$/.exec(lines[j])
    if (m) g[m[1]] = m[2].replace(/^'(.*)'$/, '$1')
  }
  for (const k of ['x', 'y', 'z']) g[k] = Number(g[k])
  return g
}
const dist = (g, x, z) => Math.hypot(g.x + 0.5 - x, g.z + 0.5 - z)
async function until(fn, ms = 45000) { const end = Date.now() + ms; while (Date.now() < end) { const v = fn(); if (v) return v; await sleep(500) } return fn() }
async function setup(s) {
  for (const p of ['multiverse.access.world', 'multiverse.access.waystation']) await s.ask(`lp group default permission set ${p} true`, 400)
}

console.log(`Arrival suite on Paper ${V}, port ${PORT}; servers in ${BASE}`)

// --- without Latchkey: the lobby is Waystation's own setting ---
{
  const folder = freshServer('arrival', { version: V, port: PORT, plugins: [JAR, pluginJar('multiverse-core')] })
  writeConfig(folder, [['lobby-world: ""', 'lobby-world: world'], ['# lobby-spot: {x: 0, y: 65, z: 0}', 'lobby-spot: {x: 60.5, y: -60, z: 60.5}']])
  const s = new Server(folder, V, PORT)
  try {
    await s.start()
    await setup(s)
    const g = await until(() => { const x = gate(folder, 'return-world'); return x && x.label === LABEL ? x : null })
    check('without Latchkey, the configured lobby world gets a lobby gate with the lobby label', !!g, JSON.stringify(g))
    check('it stands by the lobby spot, not the world spawn', g && dist(g, 60.5, 60.5) <= 24 && dist(g, 0.5, 0.5) > 24, g && `${g.x},${g.z}`)
    check('it leads to the station', g?.destination === 'waystation')

    const b = await bot('Newcomer', { port: PORT, physics: V !== '26.3' })
    await s.ask('mvtp Newcomer waystation', 2500)
    const welcome = b.chat_log.filter(m => /Central Station|reach|waygate/.test(m))
    check('walking into the station for the first time, they get the welcome', welcome.some(m => m.includes('Welcome to Central Station.')), welcome.join(' | '))
    check('it counts what this player can reach', welcome.some(m => /From here you can reach (no worlds yet|\d+ worlds?) on (no platforms yet|\d+ platforms?)\./.test(m)), welcome.join(' | '))
    const before = b.chat_log.length
    await s.ask('mvtp Newcomer world', 2000)
    await s.ask('mvtp Newcomer waystation', 2500)
    check('coming back soon after, they aren\'t welcomed again', !b.chat_log.slice(before).some(m => m.includes('Welcome to Central Station.')))
    b.quit(); await sleep(1500)
    const b2 = await bot('Newcomer', { port: PORT, physics: V !== '26.3' })
    await sleep(1500)
    check('nor when they log back in inside the station', !b2.chat_log.some(m => m.includes('Welcome to Central Station.')))
    b2.quit(); await sleep(1000)

    const cfg = path.join(folder, 'plugins/Waystation/config.yml')
    writeFileSync(cfg, readFileSync(cfg, 'utf8').replace('lobby-spot: {x: 60.5, y: -60, z: 60.5}', 'lobby-spot: {x: -150.5, y: -60, z: 40.5}'))
    await s.ask('gate reload', 1500)
    const moved = await until(() => { const x = gate(folder, 'return-world'); return x && dist(x, -150.5, 40.5) <= 24 ? x : null })
    check('moving the lobby spot and reloading puts the gate by the new spot', !!moved, JSON.stringify(gate(folder, 'return-world')))
    check('no Waystation errors', s.errors().length === 0, s.errors().slice(0, 2).join(' | '))
  } catch (e) { check('the run without Latchkey finished', false, e.message) } finally { await s.stop(); rmSync(folder, { recursive: true, force: true }) }
}

// --- with Latchkey: its lobby wins, and its arrival event moves the gate when the lobby moves ---
{
  const folder = freshServer('arrival-lk', { version: V, port: PORT, plugins: [JAR, pluginJar('multiverse-core'), LATCHKEY] })
  writeConfig(folder, [['lobby-world: ""', 'lobby-world: world']]) // Latchkey's lobby must win over this
  mkdirSync(path.join(folder, 'plugins/Latchkey'), { recursive: true })
  const lk = path.join(folder, 'plugins/Latchkey/config.yml')
  const lkConfig = spot => {
    const shipped = readFileSync(path.join(path.dirname(LATCHKEY), '../../src/main/resources/config.yml'), 'utf8')
    writeFileSync(lk, shipped.slice(0, shipped.indexOf('\nlobby:')) + `\nlobby:\n  world: hub\n  spawn: {x: ${spot[0]}, y: -60, z: ${spot[1]}}\n  send: always\n`)
  }
  lkConfig([30.5, 30.5])
  const s = new Server(folder, V, PORT)
  try {
    await s.start()
    await setup(s)
    check('Waystation found Latchkey\'s lobby by name', s.log.some(l => /Using Latchkey's lobby for the lobby gate/.test(l)))
    await s.ask('mv create hub normal --world-type flat --no-structures', 10000)
    await s.ask('lp group default permission set multiverse.access.hub true', 400)
    await s.ask('latchkey reload', 1500)
    // A join fires Latchkey's PlayerArrivedEvent, which Waystation reads by name and acts on.
    const b = await bot('Visitor', { port: PORT, physics: V !== '26.3' })
    const g = await until(() => { const x = gate(folder, 'return-hub'); return x && x.label === LABEL ? x : null }, 60000)
    check('with Latchkey, its lobby world gets the lobby gate', !!g, JSON.stringify(gate(folder, 'return-hub')))
    check('by Latchkey\'s lobby spot', g && dist(g, 30.5, 30.5) <= 24, g && `${g.x},${g.z}`)
    const w = gate(folder, 'return-world')
    check('and Waystation\'s own lobby-world setting gives way to it', !w || w.label !== LABEL, JSON.stringify(w))
    b.quit(); await sleep(1500)

    lkConfig([-120.5, 90.5])
    await s.ask('latchkey reload', 1500)
    const b2 = await bot('Visitor', { port: PORT, physics: V !== '26.3' })
    const moved = await until(() => { const x = gate(folder, 'return-hub'); return x && dist(x, -120.5, 90.5) <= 24 ? x : null }, 60000)
    check('when Latchkey\'s lobby moves, the next arrival puts the gate by the new spot', !!moved, JSON.stringify(gate(folder, 'return-hub')))
    b2.quit(); await sleep(1000)
    check('no Waystation errors', s.errors().length === 0, s.errors().slice(0, 2).join(' | '))
  } catch (e) { check('the run with Latchkey finished', false, e.message) } finally { await s.stop(); rmSync(folder, { recursive: true, force: true }) }
}

summary()
