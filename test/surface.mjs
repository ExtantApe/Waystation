// Return gates go on the surface. One small made-up server: fresh Paper, Multiverse-Core and LuckPerms, with terrain
// built from the console. No copy of a real server. Only one server runs at a time, on 127.0.0.1.
//
// The worlds, each with its spawn at x 0, z 0:
//   hill     the case seen in real use: spawn is powder snow on top of thick ground with a big cave under it
//   strip    spawn on a 1-wide path over powder snow, with a cave 11 blocks below (inside the old search's 12)
//   sealed   powder snow over the whole top, a cave under it: there is no surface spot
//   canopy   flat ground under leaves and branches: still the surface
//   house    spawn inside a small roofed house with a doorway
//   island   a platform in the air, with spawn above it
//   under    a Nether world with a roof: no sky, so a gate goes under the ceiling
//   hall     the lobby, indoors: the lobby spot is inside a big roofed hall
//   world    plain flat ground
//
// With WAYCAVE_SURFACE_OLD_JAR (a Waycave 0.9.0 jar) it first shows 0.9.0 putting gates underground, then upgrades
// that same server and checks no gate moves, the log names the covered ones, and an admin can place them again.
//
// ./gradlew shadowJar, then:
// WAYCAVE_TEST_JARS=<folder> WAYCAVE_TEST_EULA=accept WAYCAVE_TEST_JAVA=<java 25> WAYCAVE_SURFACE_PORT=<free port> \
//   WAYCAVE_SURFACE_VERSION=26.1.2|26.2|26.3 [WAYCAVE_SURFACE_OLD_JAR=<jar>] node test/surface.mjs
import { JAR, freshServer, writeConfig, pluginJar, Server, bot, sleep, check, summary, BASE } from './return-harness.mjs'
import { copyFileSync, existsSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const V = process.env.WAYCAVE_SURFACE_VERSION ?? '26.1.2'
if (!['26.1.2', '26.2', '26.3'].includes(V)) throw new Error('WAYCAVE_SURFACE_VERSION must be 26.1.2, 26.2 or 26.3')
const PORT = Number(process.env.WAYCAVE_SURFACE_PORT ?? 25652)
const OLD = process.env.WAYCAVE_SURFACE_OLD_JAR
if (OLD && !existsSync(OLD)) throw new Error('No jar at WAYCAVE_SURFACE_OLD_JAR')

const G = -61 // the top block of a flat world's own ground
const TOP = -30 // the top block of the built-up ground in hill, strip and sealed
const SPAWNS = {
  hill: [0, TOP, 0], strip: [0, TOP + 1, 0], sealed: [0, TOP, 0], canopy: [0, G + 1, 0], house: [0, G + 1, 0],
  island: [0, 6, 0], under: [0, 100, 0], hall: [0, G + 1, 0],
}
const WORLDS = Object.keys(SPAWNS)

console.log(`Surface suite on Paper ${V}, port ${PORT}; server in ${BASE}`)
const folder = freshServer('surface', { version: V, port: PORT, plugins: [pluginJar('multiverse-core')] })
const data = path.join(folder, 'plugins/Waycave')
const jarAt = path.join(folder, 'plugins/Waycave.jar')
let s = new Server(folder, V, PORT)

async function restart(jar) {
  await s.stop()
  rmSync(jarAt, { force: true })
  if (jar) copyFileSync(jar, jarAt)
  s = new Server(folder, V, PORT)
  await s.start()
}

const run = (w, cmd, ms = 350) => s.ask(`execute in minecraft:${w} run ${cmd}`, ms)
const fill = (w, x1, y1, z1, x2, y2, z2, block) => run(w, `fill ${x1} ${y1} ${z1} ${x2} ${y2} ${z2} ${block}`, 250)
/** A square slab of side 2r+1, one layer per command so no fill is over the server's limit. */
async function slab(w, r, y1, y2, block) {
  for (let y = y1; y <= y2; y++) await fill(w, -r, y, -r, r, y, r, block)
}

/** Loads the chunks around a spot: the server can't answer about blocks in a chunk that isn't loaded. */
const load = (w, x, z) => run(w, `forceload add ${x - 8} ${z - 8} ${x + 8} ${z + 8}`, 1500)

/** Whether the block at a spot is this one, asked of the server. */
async function isBlock(w, x, y, z, block) {
  await load(w, x, z)
  return (await run(w, `execute if block ${x} ${y} ${z} ${block}`, 400)).some(l => /Test passed/.test(l))
}

async function build(w) {
  await run(w, 'forceload add -80 -80 79 79', 6000)
  if (w === 'hill' || w === 'strip' || w === 'sealed') {
    await slab(w, 72, G + 1, TOP - 1, 'stone')
    await slab(w, 72, TOP, TOP, w === 'hill' ? 'grass_block' : 'powder_snow')
    const [floor, roof] = w === 'strip' ? [TOP - 10, TOP - 6] : [TOP - 20, TOP - 16] // 5 high: room for a gate
    await slab(w, 30, floor, roof, 'air')
    if (w === 'hill') await fill(w, 0, TOP, 0, 0, TOP, 0, 'powder_snow')
    if (w === 'strip') await fill(w, -40, TOP, 0, 40, TOP, 0, 'stone')
  } else if (w === 'canopy') {
    await slab(w, 72, G + 7, G + 7, 'oak_leaves[persistent=true]')
    for (let x = -72; x <= 72; x += 4) await fill(w, x, G + 8, -72, x, G + 8, 72, 'oak_log[axis=z]')
  } else if (w === 'house') {
    await fill(w, -6, G + 1, -6, 6, G + 5, 6, 'stone_bricks hollow')
    await fill(w, -6, G, -6, 6, G, 6, 'grass_block')
    await fill(w, 6, G + 1, 0, 6, G + 2, 0, 'air')
  } else if (w === 'island') {
    await slab(w, 20, 0, 0, 'stone')
  } else if (w === 'under') {
    await slab(w, 48, 99, 99, 'netherrack')
    await slab(w, 48, 100, 105, 'air')
    await slab(w, 48, 106, 106, 'netherrack')
  } else if (w === 'hall') {
    for (let y = G + 1; y <= G + 9; y++) {
      for (const [x1, z1, x2, z2] of [[-30, -30, 30, -30], [-30, 30, 30, 30], [-30, -30, -30, 30], [30, -30, 30, 30]]) await fill(w, x1, y, z1, x2, y, z2, 'stone_bricks')
    }
    await slab(w, 30, G + 10, G + 10, 'stone_bricks')
  }
}

/** Sets each world's spawn in Multiverse's file, with the server stopped, so nothing nudges it somewhere "safer". */
function setSpawns() {
  const file = path.join(folder, 'plugins/Multiverse-Core/worlds.yml')
  let current = null, inSpawn = false
  const done = new Set()
  const out = readFileSync(file, 'utf8').split('\n').map(line => {
    const top = line.match(/^'?(?:minecraft:)?([\w]+)'?:\s*$/)
    if (top) { current = top[1]; inSpawn = false; return line }
    if (!SPAWNS[current]) return line
    if (/^  adjust-spawn:/.test(line)) return '  adjust-spawn: false'
    if (/^  [\w-]+:/.test(line)) inSpawn = /^  spawn-location:/.test(line)
    const m = inSpawn && line.match(/^    ([xyz]):/)
    if (!m) return line
    done.add(current)
    const at = SPAWNS[current]['xyz'.indexOf(m[1])]
    return `    ${m[1]}: ${m[1] === 'y' ? at.toFixed(1) : (at + 0.5).toFixed(1)}`
  }).join('\n')
  writeFileSync(file, out)
  const missed = WORLDS.filter(w => !done.has(w))
  if (missed.length) throw new Error('No spawn-location in worlds.yml for ' + missed.join(', '))
}

async function gates() {
  const map = {}
  for (const l of await s.ask('gate list', 900)) {
    const m = l.match(/(\S+): (\S+) (-?\d+) (-?\d+) (-?\d+) (\w+) -> (\S+?)(?: \(\w+\))?(?: link (\S+))?$/)
    if (m) map[m[1]] = { id: m[1], world: m[2], x: +m[3], y: +m[4], z: +m[5], facing: m[6], dest: m[7], link: m[8] }
  }
  return map
}
const where = g => (g ? `${g.x} ${g.y} ${g.z} facing ${g.facing}` : 'no gate')
const logged = (from, re) => s.since(from).filter(l => re.test(l))
const noSpot = (from, w) => logged(from, new RegExp(`No good spot for a (return gate near spawn|lobby gate near the lobby) in ${w} `))

/** Waits until every world either has its return gate or has been given up on. */
async function placed(from) {
  let g = {}
  for (let i = 0; i < 80; i++) {
    g = await gates()
    if ([...WORLDS, 'world'].every(w => g[`return-${w}`] || noSpot(from, w).length)) break
    await sleep(2000)
  }
  await sleep(1500)
  return gates()
}

/** Every column of a gate's footprint (5 along it, 3 across) has this block's floor under it. */
async function floorIs(g, block) {
  const alongX = g.facing === 'north' || g.facing === 'south'
  for (const a of [-2, 0, 2]) {
    for (const b of [-1, 1]) {
      if (!(await isBlock(g.world, g.x + (alongX ? a : b), g.y - 1, g.z + (alongX ? b : a), block))) return false
    }
  }
  return true
}

/** What must be true of the return gates 0.9.1 places by itself. */
async function surfaceRules(g, from) {
  const hill = g['return-hill']
  check('hill: powder snow spawn over a cave, and the gate is on top of the ground', hill?.y === TOP + 1, where(hill))
  check('hill: on grass, not on the cave floor', !!hill && await floorIs(hill, 'grass_block'), where(hill))
  check('strip: a cave 11 below the walk gets no gate', !g['return-strip'], where(g['return-strip']))
  check('strip: the log says there is no spot and how to place one by hand',
    noSpot(from, 'strip').some(l => /on the surface, under open sky or trees\. Place one by hand: \/gate create return-strip waycave/.test(l)), noSpot(from, 'strip')[0])
  check('sealed: no surface at all, so no gate, never one in the cave', !g['return-sealed'], where(g['return-sealed']))
  check('sealed: the by-hand message', noSpot(from, 'sealed').some(l => /Place one by hand: \/gate create return-sealed waycave/.test(l)), noSpot(from, 'sealed')[0])
  const canopy = g['return-canopy']
  check('canopy: leaves and branches overhead still count as the surface', canopy?.y === G + 1, where(canopy))
  check('canopy: positive control, the leaves are over that gate', !!canopy && await isBlock('canopy', canopy.x, G + 7, canopy.z, 'oak_leaves'))
  const house = g['return-house']
  check('house: spawn is indoors, the gate is outside on the ground',
    !!house && house.y === G + 1 && (Math.abs(house.x) > 6 || Math.abs(house.z) > 6), where(house))
  if (house) await load('house', house.x, house.z)
  check('house: nothing of the roof is over the gate', !!house && (await s.ask('gate test return-house', 900)).some(l => /sky open/.test(l)))
  const island = g['return-island']
  check('island: a platform in the air, spawn above it, gate on the platform', island?.y === 1, where(island))
  const under = g['return-under']
  check('Nether: no sky, so the gate stands under the ceiling as before', under?.y === 100, where(under))
  check('Nether: positive control, there is a ceiling over it', !!under && await isBlock('under', under.x, 106, under.z, 'netherrack'))
  const hall = g['return-hall']
  check('indoor lobby: its gate is placed inside the hall', !!hall && hall.y === G + 1 && Math.abs(hall.x) < 30 && Math.abs(hall.z) < 30, where(hall))
  check('flat world: gate on the ground', g['return-world']?.y === G + 1, where(g['return-world']))
  check('every placed return gate is linked with its cave gate',
    Object.values(g).filter(x => x.id.startsWith('return-')).every(x => x.link === x.id.replace('return-', 'station-') && g[x.link]?.link === x.id))
}

const warnings = (from, w) => logged(from, new RegExp(`return-${w} at ${w} -?\\d+ -?\\d+ -?\\d+ is under cover`))

try {
  // ---------- the made-up terrain, before Waycave is ever installed ----------
  await s.start()
  for (const w of WORLDS) {
    await s.ask(`mv create ${w} ${w === 'under' ? 'nether' : 'normal'} --world-type flat --no-structures`, 5000)
    await build(w)
    await run(w, 'gamerule spawn_mobs false') // a creeper must not end a tribute check
  }
  check('the made-up terrain is there: powder snow spawn, ground under it, a cave below',
    await isBlock('hill', 0, TOP, 0, 'powder_snow') && await isBlock('hill', 0, TOP - 5, 0, 'stone') && await isBlock('hill', 0, TOP - 18, 0, 'air'))
  for (const w of WORLDS) await run(w, 'forceload remove all', 300)
  for (const p of ['multiverse.access.*', 'multiverse.teleport.*']) await s.ask(`lp group default permission set ${p} true`, 400)
  await s.stop()
  setSpawns()
  writeConfig(folder, [
    ['worlds: {}', 'worlds:\n  under: {label: "<red>Under"}'],
    ['lobby-world: ""', 'lobby-world: "hall"'],
    ['allowed-worlds: []', 'allowed-worlds: [hill, sealed]'],
    ['rest-seconds: 86400', 'rest-seconds: 0'],
    ['seed: 0', 'seed: 4242'],
  ])

  if (OLD) {
    // ---------- 0.9.0: the cause ----------
    console.log('\n# Waycave 0.9.0 on this terrain (reproducing the cause)')
    await restart(OLD)
    const old = await placed(0)
    check('0.9.0 walks down through solid ground from a powder snow spawn and puts the gate in the cave',
      old['return-hill']?.y === TOP - 20, where(old['return-hill']))
    check('0.9.0 walks the surface but its search 12 up or down puts the gate in the cave 11 below',
      old['return-strip']?.y === TOP - 10, where(old['return-strip']))
    check('0.9.0 puts a gate underground when there is no surface spot at all', old['return-sealed']?.y === TOP - 20, where(old['return-sealed']))

    // ---------- upgrading ----------
    console.log('\n# Upgrading that server')
    const before = old
    await s.stop()
    let from = 0
    await restart(JAR)
    await sleep(8000)
    const after = await gates()
    const moved = Object.keys(before).filter(id => where(before[id]) !== where(after[id]))
    check('no gate moves or goes on upgrade', Object.keys(before).length > 8 && moved.length === 0, moved.join(', ') || `${Object.keys(before).length} gates`)
    const added = Object.keys(after).filter(id => !before[id])
    // 0.9.0 gave up on some worlds (the house: it only walked from inside). Those have no gate to move, and get one now.
    check('the only gates added are for worlds that had no return gate, and none is underground',
      added.every(id => id.startsWith('return-') && !before[id]) && (!after['return-house'] || after['return-house'].y === G + 1),
      added.map(id => `${id} ${where(after[id])}`).join(', ') || 'none added')
    for (const w of ['hill', 'strip', 'sealed']) {
      check(`the log names ${w}'s underground return gate and how to place it again`,
        warnings(from, w).some(l => l.includes(`/gate remove return-${w}, then /gate addworld ${w}`) && /hasn't moved it/.test(l)), warnings(from, w)[0])
    }
    check('once each', warnings(from, 'hill').length === 1, `${warnings(from, 'hill').length}`)
    for (const w of ['world', 'canopy', 'island', 'under', 'hall']) check(`no warning for ${w}`, warnings(from, w).length === 0, warnings(from, w)[0])
    await load('hill', before['return-hill'].x, before['return-hill'].z)
    check('/gate test says it is under cover', (await s.ask('gate test return-hill', 900)).some(l => /under cover: \S+ at y -?\d+ is over it/.test(l)))

    console.log('\n# Placing one again')
    const said = (await s.ask('gate remove return-hill', 1200)).join(' ')
    check('/gate remove says where it stood and the right command to place it again',
      said.includes(`it stood at hill ${before['return-hill'].x} ${before['return-hill'].y}`) && said.includes('/gate addworld hill'), said.slice(-200))
    await sleep(33000)
    check('removing alone is not enough: the 30 second resync leaves it gone', !(await gates())['return-hill'])
    await s.ask('gate addworld hill', 8000)
    let now = await gates()
    check('/gate addworld places it again on the surface', now['return-hill']?.y === TOP + 1, where(now['return-hill']))
    check('linked both ways again', now['return-hill']?.link === 'station-hill' && now['station-hill']?.link === 'return-hill',
      `${now['return-hill']?.link} ${now['station-hill']?.link}`)
    check('the cave gate for it did not move', where(now['station-hill']) === where(before['station-hill']))
    const others = Object.keys(before).filter(id => id !== 'return-hill' && where(before[id]) !== where(now[id]))
    check('no other gate moved', others.length === 0, others.join(', '))
    from = s.log.length
    await s.ask('gate remove return-sealed', 1200)
    const answer = (await s.ask('gate addworld sealed', 8000)).join(' ')
    check('where there is no surface spot, placing again fails out loud with the by-hand message',
      !(await gates())['return-sealed'] && /No good spot[^]*Place one by hand: \/gate create return-sealed/.test(answer), answer.slice(-300))
    await s.stop()
  } else {
    console.log('\nSKIP  0.9.0 and upgrading: set WAYCAVE_SURFACE_OLD_JAR to run them.')
  }

  // ---------- a first install ----------
  console.log('\n# A first install of this build')
  if (s.proc && s.proc.exitCode === null) await s.stop()
  rmSync(path.join(data, 'gates.yml'), { force: true })
  await restart(JAR)
  let g = await placed(0)
  await surfaceRules(g, 0)

  console.log('\n# A gate an admin puts underground')
  const surface = g['return-hill']
  const placedBy = (await s.ask(`gate move return-hill hill 20 ${TOP - 20} 20 north`, 1500)).join(' ')
  await sleep(33000)
  check('an admin may still put a gate underground, and it stays there', (await gates())['return-hill']?.y === TOP - 20, placedBy.slice(-120))
  check('and the log says so at the next resync', warnings(0, 'hill').length === 1, warnings(0, 'hill')[0])
  await s.ask(`gate move return-hill hill ${surface?.x} ${surface?.y} ${surface?.z} ${surface?.facing}`, 1500)

  console.log('\n# Tributes (a waygate moved to near a bed)')
  await s.ask(`gate create return-sealed waycave sealed 20 ${TOP - 20} 20 north`, 1500)
  await tribute('sealed')
  await tribute('hill')

  check('no Waycave errors in the log', s.errors().length === 0, s.errors()[0])
} finally {
  await s.stop()
  summary()
}
process.exit(process.exitCode ?? 0) // a bot that was kicked can leave timers behind: the run is over

/** A bot whose bed is in the cave clicks the world's tribute chest. */
async function tribute(w) {
  const name = `bed_${w}`
  const b = await bot(name, { port: PORT, physics: false })
  try {
    await s.ask(`gamemode survival ${name}`, 300)
    await s.ask(`mvtp ${name} w:${w}`, 3000)
    await s.ask(`gamemode survival ${name}`, 300)
    await run(w, 'forceload add -48 -48 63 63', 3000)
    await run(w, `spawnpoint ${name} 10 ${TOP - 20} 10`, 600)
    const before = (await gates())[`return-${w}`]
    if (!check(`${w}: there is a waygate to pay at`, !!before)) return
    const [dx, dz] = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }[before.facing]
    let chest = null
    for (let i = 0; i < 3 && !chest; i++) {
      await run(w, `tp ${name} ${before.x + 0.5 + dx * 2} ${before.y} ${before.z + 0.5 + dz * 2}`, 2500)
      chest = Object.values(b.entities).find(e => e.name === 'interaction' && Math.hypot(e.position.x - before.x, e.position.z - before.z) < 5)
    }
    if (!check(`${w}: the bot is sent the tribute chest`, !!chest)) return
    b.chat_log.length = 0
    const opened = new Promise(r => b.once('windowOpen', r))
    await b.activateEntity(chest).catch(() => {})
    const win = await Promise.race([opened, sleep(8000).then(() => null)])
    if (w === 'sealed') {
      check('sealed: a tribute never takes the gate into the cave under a bed: no chest menu opens', !win, win ? 'a menu opened' : '')
      check('sealed: and the player is told there is no safe open ground', b.chat_log.some(m => /no safe open ground near your bed/i.test(m)), b.chat_log.at(-1))
      check('sealed: the gate did not move', where((await gates())[`return-${w}`]) === where(before))
      return
    }
    if (!check('hill: positive control, a bed in the cave still gets an offer', !!win, b.chat_log.at(-1))) return
    const heart = win.slots.slice(0, win.inventoryStart).filter(Boolean).find(i => i.name === 'redstone')
    if (!check('hill: for hearts', !!heart)) return
    await b.clickWindow(heart.slot, 0, 0).catch(() => {})
    await sleep(3000)
    const moved = (await gates())[`return-${w}`]
    check('hill: the paid-for gate lands on the surface above the bed, not in the cave', moved?.y === TOP + 1 && where(moved) !== where(before), where(moved))
  } finally {
    b.quit()
    await run(w, 'forceload remove all', 300)
    await sleep(1000)
  }
}
