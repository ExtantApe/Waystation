// The Waycave: what a player sees and where they land, on a throwaway server on 127.0.0.1.
//
// Each safety rule has a positive control (the thing that must happen next to the thing that must not):
//   - each bot sees only its own gates and trails, with two bots looking at the same blocks,
//   - bots outside each other's Posse circles don't see each other in the cave, and without Posse nobody does,
//   - a lost access closes the trail and the board says (trail closed), and a regained access opens it again,
//   - nobody can change a block in the cave,
//   - arrival is at the end of the last trail, or the start chamber once that gate is gone,
//   - a group added later grows a new chamber and moves nothing,
//   - the Wayport gate is off (D301): with Wayport installed, nobody is sent it and nothing runs Wayport's command.
// Needs WAYCAVE_TEST_BASE (made by test/prepare-small.mjs), the Waycave jar, and WAYCAVE_TEST_POSSE_JAR and
// WAYCAVE_TEST_WAYPORT_JAR for the two optional plugins. Bots can't see crouching or use blocks on 26.3; neither is used.
import { copyFileSync, existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { Vec3 } from 'vec3'
import { Server, freshCopy, installJar, sleep, bot as joinBot, near, walkTo, check, summary, DIR, RUN } from './harness.mjs'

// On 26.2 and 26.3 a 26.1 bot that moves is disconnected (ViaBackwards), so there the bots stand still, with physics off, and
// the two checks that need a walk (arrival) are skipped. Set WAYCAVE_TEST_NOWALK=1.
const NOWALK = process.env.WAYCAVE_TEST_NOWALK === '1'
const bot = name => joinBot(name, { physics: !NOWALK })

const POSSE = process.env.WAYCAVE_TEST_POSSE_JAR, WAYPORT = process.env.WAYCAVE_TEST_WAYPORT_JAR
if (!POSSE || !existsSync(POSSE)) throw new Error('Set WAYCAVE_TEST_POSSE_JAR to a Posse jar')
if (!WAYPORT || !existsSync(WAYPORT)) throw new Error('Set WAYCAVE_TEST_WAYPORT_JAR to a Wayport jar')

const s = new Server()
const dir = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }
const has = (b, re) => b.chat_log.some(m => re.test(m))

async function gates() {
  const out = await s.ask('gate list', 1200), map = {}
  for (const l of out) {
    const m = l.match(/(\S+): (\S+) (-?\d+) (-?\d+) (-?\d+) (\w+) -> (\S+)(.*)/)
    if (m) map[m[1]] = { id: m[1], world: m[2], x: +m[3], y: +m[4], z: +m[5], facing: m[6], dest: m[7] }
  }
  return map
}
const caveGates = async () => Object.values(await gates()).filter(g => g.world === 'waycave')
const frontOf = g => { const [dx, dz] = dir[g.facing]; return [g.x + dx * 2, g.y, g.z + dz * 2] }
const tpTo = (who, x, y, z) => s.ask(`execute in waycave run tp ${who} ${x + 0.5} ${y} ${z + 0.5}`, 600)
const isIn = async (who, world, ms = 8000) => {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if ((await s.ask(`execute in ${world} if entity @a[name=${who},distance=0..]`, 300)).some(l => /Test passed/.test(l))) return true
    await sleep(300)
  }
  return false
}
const blockName = (b, x, y, z) => b.blockAt(new Vec3(x, y, z))?.name ?? 'unloaded'
const realBlock = async (name, x, y, z) => (await s.ask(`execute in waycave if block ${x} ${y} ${z} minecraft:${name}`, 300)).some(l => /Test passed/.test(l))
const allWorlds = ['lobby', 'meadow', 'survival', 'creative', 'flatlands', 'oneblock']
const kidsWorlds = ['lobby', 'kids_survival', 'kids_flatlands']

/** Does this bot have gate displays close to the gate? Stands next to it first, so its chunk is in range. */
async function seesGate(b, g) {
  const [fx, fy, fz] = frontOf(g)
  await tpTo(b.username, fx, fy, fz); await sleep(2600)
  return near(b, g.x + 0.5, g.z + 0.5, 4).length > 0
}
/** The block in front of a gate, as this bot's game shows it. */
async function frontBlock(b, g) {
  const [fx, fy, fz] = frontOf(g)
  await tpTo(b.username, fx + 3 * dir[g.facing][0], fy, fz + 3 * dir[g.facing][1]); await sleep(2600)
  return blockName(b, fx, fy, fz)
}

// ---------- Boot: Waycave, Posse, no Wayport ----------
console.log('\n# Boot: Waycave and Posse on a small made-up server, no Wayport')
freshCopy()
// A bot with physics off floats, which a survival-mode player is kicked for. This copy is throwaway and 127.0.0.1 only.
if (NOWALK) writeFileSync(path.join(DIR, 'server.properties'), readFileSync(path.join(DIR, 'server.properties'), 'utf8') + 'allow-flight=true\n')
copyFileSync(POSSE, path.join(DIR, 'plugins', 'Posse.jar'))
installJar()
await s.start()
await s.waitFor(/The cave is built|failed its walk-through|plan has a flaw/, 240000)
check('the cave built, and a walk over its real blocks reached every chamber and gate place', s.log.some(l => /The cave is built and every chamber and gate can be walked to/.test(l)))
await sleep(8000) // gates and return gates are placed once the cave stands
await s.ask('lp group family permission set posse.circle.family true', 300)
await s.ask('lp group kids permission set posse.circle.kids true', 300)
const posseConfig = path.join(DIR, 'plugins/Posse/config.yml')
writeFileSync(posseConfig, readFileSync(posseConfig, 'utf8').replace(/^circles: \[\]/m, 'circles: [family, kids]').replace(/^circle-only-worlds: \[\]/m, 'circle-only-worlds: [waycave]'))
await s.ask('posse reload', 800)
check('Posse reads the cave as a circle-only world', (await s.ask('posse status', 800)).join(' ').includes('family'))

let G = await caveGates()
const station = G.filter(g => g.id.startsWith('station-'))
console.log(`  ${station.length} gates in the cave: ${station.map(g => g.id).join(', ')}`)
check('every world got a gate in the cave', station.length >= 7, `${station.length}`)
check('no Wayport, so no gate to another server', !G.some(g => g.id === 'wayport'))
const planText = (await s.ask('gate station', 900)).join(' ')
check('the cave has chambers for every group and says so', /cave of \d+ chambers and \d+ tunnels/.test(planText), planText.slice(0, 150))
const chambersBefore = Number(planText.match(/cave of (\d+) chambers/)?.[1])
const caveYmlBefore = readFileSync(path.join(DIR, 'plugins/Waycave/cave.yml'), 'utf8')

// ---------- Players ----------
// LuckPerms only knows a player once they've joined, so the groups are added after.
const alice = await bot('alice'), bob = await bot('bob'), carol = await bot('carol')
await s.ask('lp user alice parent add family', 400)
await s.ask('lp user carol parent add family', 400)
await s.ask('lp user bob parent add kids', 400)
await sleep(1500)
for (const w of ['alice', 'bob', 'carol']) await s.ask(`mvtp ${w} w:waycave`, 2500)
check('everyone arrives in the cave', (await isIn('alice', 'waycave')) && (await isIn('bob', 'waycave')) && (await isIn('carol', 'waycave')))
await sleep(2500)

// ---------- Own gates ----------
console.log('\n# Each player sees only their own gates')
let wrong = [], right = 0
for (const g of station) {
  const world = g.dest
  for (const [b, mine] of [[alice, allWorlds], [bob, kidsWorlds]]) {
    const sees = await seesGate(b, g), should = mine.includes(world)
    if (sees === should) right++; else wrong.push(`${b.username} ${sees ? 'sees' : 'misses'} ${g.id}`)
  }
}
check('alice sees the gates of her worlds and no others, bob the kids\' ones, at the same coordinates', wrong.length === 0, wrong.join('; ') || `${right} checks`)
check('the controls exist: alice has gates she should see, and bob has gates alice can\'t', station.some(g => allWorlds.includes(g.dest)) && station.some(g => kidsWorlds.includes(g.dest)))

console.log('\n# Each player sees only their own trails')
wrong = []; right = 0
const trailCells = {}
for (const g of station) {
  for (const [b, mine] of [[alice, allWorlds], [bob, kidsWorlds]]) {
    const name = await frontBlock(b, g), should = mine.includes(g.dest)
    if ((name === 'moss_carpet') === should) right++; else wrong.push(`${b.username} sees ${name} in front of ${g.id}`)
  }
  trailCells[g.id] = frontOf(g)
}
check('the end of a trail is moss carpet for the player who can use the gate and plain floor for the other', wrong.length === 0, wrong.join('; ') || `${right} checks`)
const someMine = station.find(g => allWorlds.includes(g.dest) && !kidsWorlds.includes(g.dest) && g.dest !== "oneblock"), someKids = station.find(g => kidsWorlds.includes(g.dest) && g.dest !== "lobby")
check('nothing is placed in the world: the real block under both is not carpet', !(await realBlock('moss_carpet', ...frontOf(someMine))) && !(await realBlock('moss_carpet', ...frontOf(someKids))))
// A trail is more than its last block: walk back along one and count carpet.
{
  const g = someMine, [fx, fy, fz] = frontOf(g)
  await tpTo('alice', fx, fy, fz); await sleep(2600)
  let carpet = 0
  for (let x = fx - 12; x <= fx + 12; x++) for (let z = fz - 12; z <= fz + 12; z++) if (blockName(alice, x, fy, z) === 'moss_carpet') carpet++
  let edge = 0
  for (let x = fx - 12; x <= fx + 12; x++) for (let z = fz - 12; z <= fz + 12; z++) if (blockName(alice, x, fy, z) === 'glow_lichen') edge++
  check('the trail is a track of moss carpet with glow lichen beside it', carpet >= 10 && edge >= 2, `${carpet} carpet, ${edge} lichen near the gate`)
  await tpTo('bob', fx, fy, fz); await sleep(2600)
  // Trails share the tunnels, so bob may have carpet of his own further back. At the end of alice's trail, he has none.
  const near2 = who => { let n = 0; for (let x = fx - 2; x <= fx + 2; x++) for (let z = fz - 2; z <= fz + 2; z++) if (['moss_carpet', 'glow_lichen'].includes(blockName(who, x, fy, z))) n++; return n }
  check('bob, on the same spot, sees none of it where her trail ends', near2(bob) === 0 && near2(alice) > 0, `bob ${near2(bob)}, alice ${near2(alice)}`)
}

// ---------- Who sees whom ----------
console.log('\n# Seeing other players follows Posse')
await tpTo('alice', 0, 64, -9); await tpTo('carol', 3, 64, -9); await tpTo('bob', -3, 64, -9)
await sleep(3500)
const seen = (viewer, other) => Object.values(viewer.entities).some(e => e.type === 'player' && e.username === other)
check('alice and carol share a circle, so each sees the other (positive control)', seen(alice, 'carol') && seen(carol, 'alice'))
check('bob shares no circle with them, so neither side sees the other', !seen(bob, 'alice') && !seen(bob, 'carol') && !seen(alice, 'bob') && !seen(carol, 'bob'))
await s.ask('lp user bob permission set posse.circle.family true', 300); await sleep(3500)
check('once bob joins their circle they see each other', seen(bob, 'alice') && seen(alice, 'bob'))
await s.ask('lp user bob permission unset posse.circle.family', 300); await sleep(3500)
check('and when he leaves it they stop', !seen(bob, 'alice') && !seen(alice, 'bob'))

// ---------- Closed trail ----------
console.log('\n# A lost access closes the trail')
const meadow = station.find(g => g.dest === 'meadow')
const board = b => Object.values(b.entities).filter(e => e.name === 'text_display' && Math.hypot(e.position.x - 0.5, e.position.z - 5.5) < 2).map(e => JSON.stringify(e.metadata)).join(' ')
await tpTo('alice', 0, 64, -9); await sleep(3000)
check('the Trails board shows meadow while alice can use it', /Meadow/.test(board(alice)) && /Trails/.test(board(alice)), board(alice).slice(0, 200))
await s.ask('lp user alice permission set multiverse.access.meadow false', 300); await sleep(3500)
let text = board(alice)
check('after she loses it the board says (trail closed) next to Meadow', /Meadow[^"]*trail closed/.test(text.replace(/\\u00a7./g, '')) || /trail closed/.test(text), text.slice(0, 300))
check('and bob\'s board never mentions Meadow, which he never had', !/Meadow/.test(board(bob)) || !(await seesGate(bob, meadow)))
await frontBlock(alice, meadow)
check('the trail to meadow is gone for alice', blockName(alice, ...frontOf(meadow)) !== 'moss_carpet', blockName(alice, ...frontOf(meadow)))
{
  // The server's own answer (Renderer.seenBy) is the check on 26.2 and 26.3. A still bot there keeps display entities it was
  // once sent, after the server has hidden them from it and sent nothing more (seen: the server says hidden, the bot's list
  // still holds the four entities), so what the bot has in its list says nothing on those versions. On 26.1.2 the bot is the check.
  const server = (await s.ask('gate test station-meadow alice', 500)).join(' ').match(/sees it (\w+)/)?.[1]
  const botSees = await seesGate(alice, meadow)
  check('and so is its gate', NOWALK ? server === 'false' : !botSees && server === 'false', `server says seenBy=${server}; bot sees ${botSees ? 'entities' : 'none'}${NOWALK ? ' (not used on this version)' : ''}`)
}
await s.ask('lp user alice permission unset multiverse.access.meadow', 300); await sleep(3500)
check('give it back and the trail returns (positive control)', (await frontBlock(alice, meadow)) === 'moss_carpet')
await tpTo('alice', 0, 64, -9); await sleep(3000)
check('and the board lists Meadow as open again', /Meadow/.test(board(alice)) && !/Meadow[^"]*trail closed/.test(board(alice)))

// ---------- Protection ----------
console.log('\n# Nobody can change the cave')
{
  const [fx, fy, fz] = [0, 64, -12]
  await tpTo('alice', fx, fy, fz); await sleep(1500)
  await s.ask('gamemode survival alice', 300)
  const under = blockName(alice, fx, fy - 1, fz)
  try { await Promise.race([alice.dig(alice.blockAt(new Vec3(fx, fy - 1, fz)), true), sleep(6000)]) } catch { /* refused: expected */ }
  await sleep(800)
  check('a block under her feet in the cave is still there after she tries to break it', await realBlock(under, fx, fy - 1, fz), under)
  await s.ask('mvtp alice w:survival', 2500); await isIn('alice', 'survival'); await sleep(2500)
  const p = alice.entity.position.floored(), ground = alice.blockAt(p.offset(0, -1, 0))
  try { await Promise.race([alice.dig(ground, true), sleep(8000)]) } catch { /* ignored */ }
  await sleep(800)
  check('positive control: the same breaking works in a survival world', alice.blockAt(p.offset(0, -1, 0))?.name !== ground.name)
  await s.ask('gamemode adventure alice', 300)
}

// ---------- Arrival ----------
console.log('\n# Arrival at the end of the last trail')
async function walkThrough(b, g) {
  const [dx, dz] = dir[g.facing]
  await s.ask(`execute in ${g.world} run tp ${b.username} ${g.x + 0.5 + dx * 3} ${g.y} ${g.z + 0.5 + dz * 3}`, 900)
  return walkTo(b, g.x + 0.5, g.z + 0.5, 5000)
}
if (NOWALK) console.log('  skipped on this version: arrival needs a bot that walks through gates')
else {
  const all = await gates(), mine = all['station-survival'], back = all['return-survival']
  await s.ask('mvtp alice w:waycave', 2500); await sleep(1500)
  await walkThrough(alice, mine)
  check('alice walks through the survival gate and is in survival', await isIn('alice', 'survival', 10000))
  await sleep(1500)
  await s.ask('mvtp bob w:waycave', 2500)
  await walkThrough(alice, back)
  check('she walks back through the return gate and is in the cave', await isIn('alice', 'waycave', 10000))
  await sleep(2000)
  const [fx, , fz] = frontOf(mine), at = alice.entity.position
  check('and lands two blocks in front of the survival gate, where her trail ended', Math.hypot(at.x - (fx + 0.5), at.z - (fz + 0.5)) < 1.2, `${at.x.toFixed(1)} ${at.z.toFixed(1)} vs ${fx + 0.5} ${fz + 0.5}`)
  // Remove the gate she used: next time she arrives in the start chamber.
  await s.ask('gate remove station-survival', 1200)
  await sleep(3000)
  await s.ask('mvtp alice w:survival', 2500); await isIn('alice', 'survival'); await sleep(2000)
  await walkThrough(alice, (await gates())['return-survival'])
  check('with that gate removed she is back in the cave', await isIn('alice', 'waycave', 10000))
  await sleep(2000)
  const p = alice.entity.position
  check('and arrives in the start chamber', Math.hypot(p.x - 0.5, p.z + 8.5) < 6, `${p.x.toFixed(1)} ${p.z.toFixed(1)}`)
  await s.ask('gate addworld survival', 4000); await sleep(4000)
}

// ---------- A new group grows a new chamber ----------
console.log('\n# A group added later grows a chamber and moves nothing')
{
  const before = await gates()
  // More spots than needed: some land in a block type that isn't in the list below, whatever the seed makes.
  const floorSample = [[0, 63, 0], [20, 63, 3], [-30, 62, 20], [40, 64, -10], [5, 70, 5], [0, 63, -12], [-9, 63, 0], [9, 63, 9], [0, 66, 0], [12, 62, -20], [-20, 63, -9], [30, 63, 20]]
  const samples = []
  const names = ['moss_block', 'rooted_dirt', 'stone', 'tuff', 'dripstone_block', 'clay', 'amethyst_block', 'air']
  for (const [x, y, z] of floorSample) { for (const n of names) if (await realBlock(n, x, y, z)) { samples.push([x, y, z, n]); break } }
  await s.ask('mv create zeta normal --world-type flat --no-structures', 6000)
  await s.ask('lp group default permission set multiverse.access.zeta true', 300)
  await s.waitFor(/The cave is built/, 90000, Math.max(0, s.log.length - 40)).catch(() => {})
  // The world is made and the cave grows in the background; how long that takes differs by version, so wait for the new gate (up to 2 minutes).
  let after = await gates()
  for (let i = 0; i < 40 && !after['station-zeta']; i++) { await sleep(3000); after = await gates() }
  await sleep(3000)
  after = await gates()
  const moved = Object.values(before).filter(g => g.world === 'waycave').filter(g => { const a = after[g.id]; return !a || a.x !== g.x || a.y !== g.y || a.z !== g.z })
  check('every gate that was in the cave is where it was', moved.length === 0, moved.map(g => g.id).join(','))
  check('the new world has a gate in the cave', !!after['station-zeta'] && after['station-zeta'].world === 'waycave')
  const chambersAfter = Number((await s.ask('gate station', 900)).join(' ').match(/cave of (\d+) chambers/)?.[1])
  check('the cave has one chamber more', chambersAfter === chambersBefore + 1, `${chambersBefore} -> ${chambersAfter}`)
  let same = true
  for (const [x, y, z, name] of samples) same &&= await realBlock(name, x, y, z)
  check('the blocks already built are the same blocks', same && samples.length >= 4, `${samples.length} sampled`)
  const ymlAfter = readFileSync(path.join(DIR, 'plugins/Waycave/cave.yml'), 'utf8')
  const sectionOf = (t, key) => t.slice(t.indexOf(key), t.indexOf(key) + 50)
  check('the saved plan keeps the old chambers first', ymlAfter.includes('seed:') && ymlAfter.indexOf('chambers:') > 0 && sectionOf(ymlAfter, 'seed:') === sectionOf(caveYmlBefore, 'seed:'))
}

console.log('\n# Errors and leftovers')
check('no errors from Waycave in the log', s.errors().length === 0, s.errors().slice(0, 3).join(' | '))
for (const b of [alice, bob, carol]) b.end()
await s.stop()

// ---------- Without Posse, nobody sees anyone ----------
console.log('\n# Without Posse nobody sees anyone')
rmSync(path.join(DIR, 'plugins/Posse.jar'))
await s.start(); await sleep(8000)
const a2 = await bot('alice'), c2 = await bot('carol')
await s.ask('mvtp alice w:waycave', 2500); await s.ask('mvtp carol w:waycave', 2500)
await tpTo('alice', 0, 64, -9); await tpTo('carol', 3, 64, -9); await sleep(4000)
check('alice and carol, who shared a circle, now see nobody: Posse can\'t answer, so the answer is no', !seen(a2, 'carol') && !seen(c2, 'alice'))
await s.ask('mvtp carol w:lobby', 2500); await sleep(2500)
check('control: carol is still online, just not seen', (await s.ask('list', 400)).join(' ').includes('carol'))
a2.end(); c2.end()
await s.stop()

// ---------- An older install's gates ----------
console.log('\n# Gates from before the cave was in this world are put right')
{
  // As an upgraded install has them: a cave gate in a world that's gone, and a return gate leading there.
  const file = path.join(DIR, 'plugins/Waycave/gates.yml')
  const lines = readFileSync(file, 'utf8').split('\n')
  let block = ''
  for (let i = 0; i < lines.length; i++) {
    const head = lines[i].match(/^ {2}(\S+):$/)
    if (head) block = head[1]
    if (block === 'station-meadow' && /^ {4}world: waycave$/.test(lines[i])) lines[i] = '    world: oldcave'
    if (block === 'return-meadow' && /^ {4}destination: waycave$/.test(lines[i])) lines[i] = '    destination: oldcave'
  }
  writeFileSync(file, lines.join('\n'))
}

// ---------- Wayport ----------
console.log('\n# The Wayport gate stays off (D301)')
copyFileSync(WAYPORT, path.join(DIR, 'plugins', 'Wayport.jar'))
await s.start(); await sleep(14000)
G = await caveGates()
{
  const all = await gates()
  check('the return gate that led to the old cave now leads to this one', all['return-meadow']?.dest === 'waycave', all['return-meadow']?.dest)
  check('and the cave gate that stood in the old world is placed again in this one', all['station-meadow']?.world === 'waycave', all['station-meadow']?.world)
}
// The gate to another server is off in 0.8.0 (D301): with Wayport installed and a player holding wayport.use and
// wayport.admin, there is still no gate, none is sent to anyone, and nothing runs Wayport's command.
check('with Wayport installed there is still no gate to another server', !G.some(g => g.id === 'wayport') && !(await gates()).wayport)
check('and none is written to gates.yml', !/wayport/.test(readFileSync(path.join(DIR, 'plugins/Waycave/gates.yml'), 'utf8')))
{
  await s.ask('lp user alice permission set wayport.use true', 300)
  await s.ask('lp user alice permission set wayport.admin true', 300)
  const a3 = await bot('alice')
  a3.chat_log.length = 0
  await s.ask('mvtp alice w:waycave', 2500); await sleep(4000)
  const sees = JSON.stringify(Object.values(a3.entities).map(e => e.metadata ?? null))
  check('a player allowed to use Wayport, even an admin of it, is sent no gate to another server', !/another server/i.test(sees))
  const test = (await s.ask('gate test wayport alice', 700)).join(' ')
  check('gate test finds no such gate', !/can enter true/.test(test), test.slice(0, 100))
  check('nothing ran Wayport\'s command for her', !a3.chat_log.some(m => /Wayport|isn't set up|Going to/i.test(m)), a3.chat_log.join(' | ').slice(0, 120))
  a3.end()
}
check('no errors from Waycave in the log (Wayport run)', s.errors().length === 0, s.errors().slice(0, 3).join(' | '))
await s.stop()
rmSync(RUN, { recursive: true, force: true })
check('clean-up: the run folder this test made is gone', !existsSync(RUN))
summary()
