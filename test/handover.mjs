// The hand-over: a world whose own plugin takes players in (worlds.<name>.hand-over: true), and LandingService, which
// tells that plugin where to put them back. One small made-up server on 127.0.0.1:25651: fresh Paper, Multiverse-Core,
// Multiverse-Inventories, LuckPerms and the test hook plugin, which stands in for a OneBlock add-on. No copy of a
// real server. Worlds: the main world and the station share a "Lobby" inventory group; "skyblock", made here, is its
// own group and hands over.
//
// WAYSTATION_HANDOVER_VERSION is 26.1.2 (default), 26.2 or 26.3. On 26.3 a bot behind ViaVersion is disconnected when
// it walks, so there the walk-through checks are skipped and said so; everything else runs.
//
// ./gradlew jar hookJar, then:
// WAYSTATION_TEST_JARS=<folder> WAYSTATION_TEST_EULA=accept WAYSTATION_TEST_JAVA=<java 25> node test/handover.mjs
import { JAR, freshServer, writeConfig, pluginJar, Server, bot, settle, sleep, check, summary, BASE } from './return-harness.mjs'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const V = process.env.WAYSTATION_HANDOVER_VERSION ?? '26.1.2'
if (!['26.1.2', '26.2', '26.3'].includes(V)) throw new Error('WAYSTATION_HANDOVER_VERSION must be 26.1.2, 26.2 or 26.3')
const PORT = 25651, PHYS = V !== '26.3'
const HOOK = path.join(path.dirname(JAR), '../test-hook/DenyHook.jar')
if (!existsSync(HOOK)) throw new Error('Run ./gradlew hookJar first')
const W = 'skyblock' // the made-up hand-over world
const dir = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }
const yawOf = { south: 0, west: 90, north: 180, east: 270 } // Minecraft yaw for facing that way
const SHARES = ['inventory', 'armor', 'off_hand', 'ender_chest', 'hunger', 'exp', 'health', 'potions']
const group = (name, worlds) => `  ${name}:\n    worlds:\n${worlds.map(w => `    - ${w}\n`).join('')}    shares:\n${SHARES.map(s => `    - ${s}\n`).join('')}`

console.log(`Hand-over suite on Paper ${V}, port ${PORT}, bots ${PHYS ? 'walking' : 'standing still (26.3)'}; servers in ${BASE}`)
const folder = freshServer('handover', { version: V, port: PORT, plugins: [JAR, pluginJar('multiverse-core'), pluginJar('multiverse-inventories'), pluginJar('Vault'), HOOK] })
mkdirSync(path.join(folder, 'plugins/Multiverse-Inventories'), { recursive: true })
writeFileSync(path.join(folder, 'plugins/Multiverse-Inventories/groups.yml'), 'groups:\n' + group('Lobby', ['world', 'waystation']) + group('Skyblock', [W]))
const SETTINGS = [['worlds: {}', `worlds:\n  ${W}: {label: "<aqua>Skyblock", hand-over: true}`]]
writeConfig(folder, SETTINGS)
const cfgFile = path.join(folder, 'plugins/Waystation/config.yml')

const s = new Server(folder, V, PORT)
await s.start()
const made = (await s.ask(`mv create ${W} normal --world-type flat`, 12000)).join(' ')
console.log('  mv create: ' + made.slice(0, 160))
for (const p of ['multiverse.access.world', 'multiverse.access.waystation', `multiverse.access.${W}`]) await s.ask(`lp group default permission set ${p} true`, 400)
for (const r of ['keepInventory true', 'keep_inventory true']) s.send(`execute in ${W} run gamerule ${r}`)

async function gates() {
  const map = {}
  for (const l of await s.ask('gate list', 900)) {
    const m = l.match(/(\S+): (\S+) (-?\d+) (-?\d+) (-?\d+) (\w+) -> (\S+)/)
    if (m) map[m[1]] = { id: m[1], world: m[2], x: +m[3], y: +m[4], z: +m[5], facing: m[6], dest: m[7] }
  }
  return map
}
let g = {}
for (let i = 0; i < 30 && !g[`station-${W}`]; i++) { g = await gates(); if (!g[`station-${W}`]) await sleep(3000) } // new worlds get gates within 30 s
const gate = g[`station-${W}`]

console.log('\n# Gates')
check('the hand-over world gets a station gate', !!gate, Object.keys(g).join(', '))
check('and no return gate in it: its own plugin brings players back', !g[`return-${W}`])
check('positive control: an ordinary world still gets its return gate', !!g['return-world'], Object.keys(g).join(', '))
if (!gate) { await s.stop(); summary(); process.exit(1) }

const inWorld = async (who, world, ms = 6000) => {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if ((await s.ask(`execute in ${world} if entity @a[name=${who},distance=0..]`, 300)).some(l => /Test passed/.test(l))) return true
    await sleep(300)
  }
  return false
}
const count = (b, item) => b.inventory.items().filter(i => i.name === item).reduce((n, i) => n + i.count, 0)
const logged = (from, re) => s.since(from).filter(l => re.test(l))
async function landing(world) {
  const from = s.log.length
  await s.ask(`hooklanding ${world}`, 600)
  const line = logged(from, /LANDING /)[0] ?? ''
  const m = line.match(/LANDING \S+ (\S+) (\S+) (\S+) (\S+) (\S+) (\S+)/)
  return m ? { world: m[1], x: +m[2], y: +m[3], z: +m[4], yaw: +m[5], pitch: +m[6] } : line.replace(/^.*LANDING \S+ /, '')
}
/** Stands the bot three blocks out from the gate, then walks it in by its own physics. */
async function walkThrough(b) {
  const [dx, dz] = dir[gate.facing]
  await s.ask(`execute in waystation run tp ${b.username} ${gate.x + 0.5 + dx * 3} ${gate.y} ${gate.z + 0.5 + dz * 3}`, 900)
  await settle(b)
  const end = Date.now() + 5000
  b.setControlState('forward', true)
  try {
    await sleep(300)
    let last = b.entity.position.clone()
    while (Date.now() < end) {
      const p = b.entity.position
      if (Math.hypot(p.x - (gate.x + 0.5), p.z - (gate.z + 0.5)) < 0.4 || p.distanceTo(last) > 4) return
      last = p.clone()
      await b.lookAt(p.offset(gate.x + 0.5 - p.x, 1.6, gate.z + 0.5 - p.z), true)
      await sleep(50)
    }
  } finally { b.setControlState('forward', false) }
}

console.log('\n# landing()')
const spot = await landing(W)
const [dx, dz] = dir[gate.facing]
check('it answers for the hand-over world', typeof spot === 'object', JSON.stringify(spot))
if (typeof spot === 'object') {
  check('two blocks in front of the gate, in the station', spot.world === 'waystation' && spot.x === gate.x + 0.5 + dx * 2 && spot.z === gate.z + 0.5 + dz * 2 && spot.y === gate.y,
    `gate ${gate.x} ${gate.y} ${gate.z} ${gate.facing}, spot ${spot.x} ${spot.y} ${spot.z}`)
  check('facing away from the gate', ((spot.yaw % 360) + 360) % 360 === yawOf[gate.facing], `yaw ${spot.yaw}, gate faces ${gate.facing}`)
}
check('empty for a world that has no gate', (await landing('nowhere')) === 'none')
check('empty for the station itself, which has no gate to itself', (await landing('waystation')) === 'none')

const ana = await bot('ana', { port: PORT, physics: PHYS })
await s.ask('mvtp ana w:waystation', 2500); await s.ask('clear ana', 300)
await s.ask('give ana apple 3', 800)
if (typeof spot === 'object') {
  const from = s.log.length
  await s.ask(`execute in waystation run tp ana ${spot.x} ${spot.y} ${spot.z} ${spot.yaw} 0`, 1000)
  await settle(ana); await sleep(3000)
  check('standing on the landing spot doesn\'t trigger the gate', await inWorld('ana', 'waystation', 1500) && logged(from, /HANDOVER ana/).length === 0)
}

console.log('\n# Walking through')
if (PHYS) {
  ana.chat_log = []
  let from = s.log.length
  await walkThrough(ana); await sleep(2500)
  const seen = logged(from, /HANDOVER ana/)
  check('positive control: walking in fires the hand-over', seen.length >= 1, seen[0])
  check('with the gate and world it was for', /gate station-skyblock dest skyblock/.test(seen[0] ?? ''), seen[0])
  check('unhandled at first, and the inventory screen closed', /handled-before false screen-open false/.test(seen[0] ?? ''), seen[0])
  check('nobody handles it: the player stays in the station', await inWorld('ana', 'waystation', 1500))
  check('and is told the world isn\'t open', ana.chat_log.some(m => /isn't open right now/.test(m)), ana.chat_log.at(-1))
  check('and the console says why', logged(from, new RegExp(`Nothing took ana into ${W}`)).length > 0)
  check('nothing changed in their inventory', count(ana, 'apple') === 3)

  await s.ask('hookhandover on', 400)
  await s.ask(`execute in waystation run tp ana ${spot.x} ${spot.y} ${spot.z}`, 2500) // wait out the gate's cooldown
  from = s.log.length
  await walkThrough(ana); await sleep(3000)
  const handled = logged(from, /HANDOVER ana/)
  check('handled: the other plugin takes them in', await inWorld('ana', W, 3000), handled[0])
  check('one walk, one hand-over', handled.length === 1, `${handled.length} events`)
  check('no "isn\'t open" message when it was handled', !logged(from, /Nothing took ana/).length)
  check('into the hand-over world\'s own inventory', count(ana, 'apple') === 0, `apples ${count(ana, 'apple')}`)
} else {
  console.log('SKIP  walking through the gate: Paper 26.3 disconnects a bot that walks (ViaVersion), so the hand-over event,')
  console.log('      the unhandled message and the handled trip aren\'t tested on 26.3. Going in with /mvtp instead.')
  console.log('      The death below is done by the test plugin there: /kill and the bot\'s respawn request don\'t get through.')
  await s.ask(`mvtp ana w:${W}`, 3000)
  check('in the hand-over world by /mvtp', await inWorld('ana', W, 3000))
  check('with that world\'s own inventory', count(ana, 'apple') === 0, `apples ${count(ana, 'apple')}`)
}

console.log('\n# A death in the hand-over world')
await s.ask('give ana emerald 2', 800)
await s.ask(`execute in ${W} run spawnpoint ana 3 -60 3`, 500) // a spawn point there, which the landing spot overrides
await s.ask(`hookrespawn ${W}`, 400)
// On 26.3, behind ViaVersion, /kill leaves the bot alive and its respawn request never arrives, so the test plugin
// sets its health to 0 and respawns it from the server side. Paper fires the same death and respawn events.
if (!PHYS) await s.ask('hookforcerespawn on', 400)
const before = s.log.length
await s.ask(PHYS ? 'kill ana' : 'hookkill ana', 2000) // on 26.3 /kill doesn't take, so the test plugin sets health to 0
ana.respawn() // mineflayer misses the death screen through ViaVersion on 26.3; asking again when alive does nothing
await sleep(2000); await settle(ana); await sleep(1500)
const after = ana.entity.position
const respawned = check('respawns in the station', await inWorld('ana', 'waystation', 3000), logged(before, /RESPAWN ana/)[0])
if (!respawned) console.log('  server, since the kill:\n    ' + [...s.since(before), ...(await s.ask('list', 600))].filter(l => /ana|RESPAWN|DEATH|DenyHook|Killed|died|players online/i.test(l)).slice(0, 20).join('\n    '))
if (typeof spot === 'object') check('on the landing spot, despite the spawn point', Math.hypot(after.x - spot.x, after.z - spot.z) < 0.6, after.toString())
check('with the station\'s inventory, as it was', count(ana, 'apple') === 3, `apples ${count(ana, 'apple')}`)
check('and nothing from the hand-over world', count(ana, 'emerald') === 0, `emeralds ${count(ana, 'emerald')}`)
await s.ask(`mvtp ana w:${W}`, 3000)
check('back in the hand-over world, its inventory stayed there', count(ana, 'emerald') === 2 && count(ana, 'apple') === 0,
  `emeralds ${count(ana, 'emerald')} apples ${count(ana, 'apple')}`)
await s.ask('hookrespawn off', 300)

console.log('\n# Config')
const good = readFileSync(cfgFile, 'utf8')
writeFileSync(cfgFile, good.replace(`hand-over: true}`, `hand-over: true, enter-command: "spawn"}`))
const bad = (await s.ask('gate reload', 1500)).join(' ')
check('hand-over with enter-command is refused, saying why', /has a problem|both hand-over and enter-command/.test(bad + s.log.slice(-20).join(' ')), bad.slice(0, 160))
check('and the running settings stay', typeof (await landing(W)) === 'object')
writeFileSync(cfgFile, good); await s.ask('gate reload', 1500)

check('no Waystation errors', s.errors().length === 0, s.errors()[0]?.slice(0, 200))
ana.end()
await s.stop()
rmSync(folder, { recursive: true, force: true, maxRetries: 10, retryDelay: 500 })
process.exit(summary() ? 0 : 1)
