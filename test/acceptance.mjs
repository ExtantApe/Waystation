// Acceptance tests. Bots on a throwaway clone, 127.0.0.1 only. Each "blocked" result has a positive control.
import { Server, freshCopy, installJar, sleep, bot, near, displays, walkTo, check, summary, DIR, BASE, HOOK_JAR, PLUGIN_JAR } from './harness.mjs'
import { readFileSync, writeFileSync, copyFileSync, rmSync, readdirSync } from 'node:fs'
import { Vec3 } from 'vec3'

const s = new Server()
const has = (b, re) => b.chat_log.some(m => re.test(m))
const clearChat = b => { b.chat_log.length = 0 }
const worldOf = b => b.game.dimension

async function gates() {
  const out = await s.ask('gate list', 900)
  const map = {}
  for (const l of out) {
    const m = l.match(/(\S+): (\S+) (-?\d+) (-?\d+) (-?\d+) (\w+) -> (\S+)(.*)/)
    if (m) map[m[1]] = { id: m[1], world: m[2], x: +m[3], y: +m[4], z: +m[5], facing: m[6], dest: m[7], rest: m[8] }
  }
  map._lines = out
  return map
}
const sees = async (b, g) => (await s.ask(`gate test ${g.id} ${b.username}`, 500)).join(' ').includes('sees it true')
const labelsNear = (b, g) => JSON.stringify(near(b, g.x, g.z, 3).map(e => e.metadata))
const dir = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }

/** Stands the bot in front of a gate, then walks it through by physics. */
async function walkThrough(b, g) {
  const [dx, dz] = dir[g.facing]
  await s.ask(`execute in ${g.world} run tp ${b.username} ${g.x + 0.5 + dx * 3} ${g.y} ${g.z + 0.5 + dz * 3}`, 900)
  return walkTo(b, g.x + 0.5, g.z + 0.5, 5000)
}
/** Waits until the bot's server position has moved to the named Multiverse world, per /gate-independent data. */
async function inWorld(name, ms = 8000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const out = await s.ask(`execute in ${name} if entity @a[name=${inWorld.who},distance=0..]`, 300)
    if (out.some(l => /Test passed/.test(l))) return true
    await sleep(300)
  }
  return false
}
const isIn = async (b, world, ms) => { inWorld.who = b.username; return inWorld(world, ms) }
const cnt0 = (b, name) => b.inventory.items().filter(i => i.name === name).reduce((n, i) => n + i.count, 0)
const invCount = (b, name) => b.inventory.items().filter(i => i.name === name).reduce((n, i) => n + i.count, 0)
function hashArea(b, cx, cz, y0, y1, chunks = 0) {
  const out = []
  for (let x = (cx - chunks) << 4; x < (cx + chunks + 1) << 4; x++) for (let z = (cz - chunks) << 4; z < (cz + chunks + 1) << 4; z++) for (let y = y0; y <= y1; y++) {
    const block = b.blockAt(new Vec3(x, y, z))
    out.push(block ? block.stateId : -1)
  }
  return out
}
// Grass and dirt flip by themselves between boots, with no plugin installed: measured, so ignored here.
const NATURAL = new Set([8, 9, 10])
const hashDiff = (a, b) => { let d = 0; for (let i = 0; i < a.length; i++) if (a[i] !== b[i] && a[i] !== -1 && b[i] !== -1 && !(NATURAL.has(a[i]) && NATURAL.has(b[i]))) d++; return d }
const reliable = async (cmd, ms = 500) => s.ask(cmd, ms)

// ---------- Phase 0: baseline with no plugin ----------
console.log('\n# Phase 0: baseline, plugin not installed')
freshCopy()
// Multiverse-Inventories group for a world made later in the test, to prove new worlds join their group.
const groupsPath = DIR + '/plugins/Multiverse-Inventories/groups.yml'
writeFileSync(groupsPath, readFileSync(groupsPath, 'utf8').replace('    - meadow_the_end\n', '    - meadow_the_end\n    - newsurvival\n'))
await s.start()
let b0 = await bot('scout')
await s.ask('mvtp scout w:lobby', 3000); await sleep(3000)
const lobbyBefore = hashArea(b0, 0, 0, -64, -40, 2)
await s.ask('mvtp scout w:flatlands', 3000); await sleep(3000)
const flatPos = b0.entity.position.floored()
const flatBefore = hashArea(b0, flatPos.x >> 4, flatPos.z >> 4, -64, -40, 2)
check('baseline reads real terrain', lobbyBefore.some(v => v > 0) && flatBefore.some(v => v > 0), `${lobbyBefore.length} blocks`)
b0.end(); await s.stop()
copyFileSync(HOOK_JAR, DIR + '/plugins/DenyHook.jar')
installJar()

// ---------- Phase 1 ----------
console.log('\n# Phase 1: generation')
await s.start()
let g = await gates()
for (let i = 0; i < 40 && (!g._lines.some(l => /^Platform/.test(l)) || !g['return-quiet_survival']); i++) { await sleep(3000); g = await gates() }
const live = ['lobby', 'quiet_survival', 'quiet_flatlands', 'meadow', 'survival', 'creative', 'flatlands', 'legacy', 'kids_survival', 'kids_flatlands', 'friends_survival', 'friends_flatlands']
check('every world has a station gate', live.every(w => g['station-' + w]), Object.keys(g).filter(k => k.startsWith('station-')).join(','))
check('every world has a return gate', live.every(w => g['return-' + w]), Object.keys(g).filter(k => k.startsWith('return-')).join(','))
check('nether, End and TARDIS worlds have no gate', !g['station-meadow_nether'] && !g['station-TARDIS_TimeVortex'] && !g['station-survival_the_end'])
const platformLines = g._lines.filter(l => /^Platform \d+/.test(l))
console.log('  ' + platformLines.join('\n  '))
const groupsWithGates = new Set()
{ const text = readFileSync(groupsPath, 'utf8'); let group = null
  for (const line of text.split('\n')) {
    const m = line.match(/^  (\w+):$/); if (m) group = m[1]
    const w = line.match(/^    - (\w+)$/); if (w && group && g['station-' + w[1]]) groupsWithGates.add(group)
  } }
check('one platform per world group', platformLines.length === groupsWithGates.size, `${platformLines.length} platforms`)
check('a group shares a platform', platformLines.some(l => /\(Survival\).*station-meadow.*station-survival|\(Survival\).*station-survival.*station-meadow/.test(l)))
console.log('  ' + g._lines.find(l => /^Station seed/.test(l)))
const sameWall = (p, q) => (p.x === q.x && Math.abs(p.z - q.z) <= 18) || (p.z === q.z && Math.abs(p.x - q.x) <= 18)
check('group members stand next to each other', sameWall(g['station-meadow'], g['station-survival']))

// bots
const alice = await bot('alice'), amy = await bot('amy'), nina = await bot('nina'), carl = await bot('carl'), boss = await bot('boss'), oppy = await bot('oppy'), hooky = await bot('hooky')
for (const c of ['lp user alice parent add family', 'lp user carl parent add family', 'lp user amy parent add friends', 'lp user nina parent add kids',
  'lp user hooky parent add family', 'lp user boss permission set waystation.admin true', 'lp user boss parent add family', 'op oppy', 'lp group default permission set multiverse.access.waystation true']) await s.ask(c, 300)
await sleep(1500)

console.log('\n# Terrain untouched')
const scout = await bot('scout')
await s.ask('mvtp scout w:lobby', 3000); await sleep(3000)
{ const d = hashDiff(hashArea(scout, 0, 0, -64, -40, 2), lobbyBefore); check('entry gate changed no block in the lobby', d === 0, `${d} differ`) }
await s.ask('mvtp scout w:flatlands', 3000); await sleep(3000)
{
  const now = hashArea(scout, flatPos.x >> 4, flatPos.z >> 4, -64, -40, 2)
  const cx0 = (flatPos.x >> 4) - 2, cz0 = (flatPos.z >> 4) - 2, h = 25, side = 80
  const seen = []
  for (let i = 0; i < now.length; i++) if (hashDiff([now[i]], [flatBefore[i]])) {
    const y = -64 + (i % h), z = (cz0 << 4) + (Math.floor(i / h) % side), x = (cx0 << 4) + Math.floor(i / h / side)
    seen.push(`${x},${y},${z} ${scout.registry.blocksByStateId[flatBefore[i]]?.name}->${scout.registry.blocksByStateId[now[i]]?.name}`)
  }
  const rf = g['return-flatlands']
  const inZone = seen.filter(t => { const [x, , z] = t.split(' ')[0].split(',').map(Number); return Math.abs(x - rf.x) <= 3 && Math.abs(z - rf.z) <= 3 })
  console.log('  differences:', seen.join('; ') || 'none')
  check('return gate changed no block in the world', inZone.length === 0 && seen.every(t => !/->(stone|air|glass)/.test(t)), `${seen.length} natural, ${inZone.length} at the gate`)
}
scout.end()

// ---------- visibility ----------
console.log('\n# Visibility and access')
for (const b of [alice, amy, nina, carl, boss, hooky]) await s.ask(`mvtp ${b.username} w:waystation`, 1200)
await sleep(3000)
const sd = g['station-meadow'], sa = g['station-friends_survival'], sc = g['station-creative']
check('positive control: permitted player sees the meadow gate', await sees(alice, sd), `${near(alice, sd.x, sd.z, 5).length} entities`)
check('no access: nothing at all is sent for that gate', !(await sees(amy, sd)) && near(amy, sd.x, sd.z, 3).length === 0, `${near(amy, sd.x, sd.z, 3).length} entities`)
check('positive control: the same gate reaches someone with access', near(alice, sd.x, sd.z, 3).length > 0)
const sam = g['station-quiet_survival']
check('Quiet gates are invisible to a non-member', !(await sees(alice, sam)) && near(alice, sam.x, sam.z, 3).length === 0)
check('positive control: amy sees her own gate', await sees(amy, sa))
check('alice is sent no entities for an friends gate', !(await sees(alice, sa)))

clearChat(amy)
await walkThrough(amy, sd); await sleep(1000)
check('walking into a gate you can\'t see does nothing and says nothing', !has(amy, /waygate/i), amy.chat_log.join('|').slice(0, 100))
check('and stays in the station', await isIn(amy, 'waystation', 1500))

await s.ask('lp user amy permission set multiverse.access.meadow true', 2500)
check('permission added: gate appears with no rejoin', await sees(amy, sd))
await s.ask('lp user amy permission unset multiverse.access.meadow', 2500)
check('permission removed: gate disappears', !(await sees(amy, sd)))
await s.ask('lp user amy permission set multiverse.access.meadow true world=waystation', 2500)
check('LuckPerms world context is respected: visible in the station', await sees(amy, sd))
await s.ask('lp user amy permission unset multiverse.access.meadow world=waystation', 2500)
await s.ask('lp user amy permission set multiverse.access.meadow true world=creative', 2500)
check('LuckPerms world context is respected: hidden with other context', !(await sees(amy, sd)))
await s.ask('lp user amy permission unset multiverse.access.meadow world=creative', 800)

// ---------- hooks ----------
console.log('\n# Role and player hooks')
const hookCases = [['waystation.use.meadow false', 'waystation.use.meadow'], ['waystation.platform.Survival false', 'waystation.platform.Survival'], ['waystation.gate.station-meadow false', 'waystation.gate.station-meadow']]
for (const [set, node] of hookCases) {
  await s.ask(`lp user carl permission set ${set}`, 2500)
  const hidden = !(await sees(carl, sd))
  clearChat(carl); await walkThrough(carl, sd); await sleep(1000)
  check(`${node} false hides and refuses despite Multiverse access`, hidden && await isIn(carl, 'waystation', 1500))
  await s.ask(`lp user carl permission unset ${node}`, 2500)
  check(`positive control: unset, ${node} no longer blocks`, await sees(carl, sd))
}
await s.ask('lp group family permission set waystation.gate.station-creative false', 2500)
check('a role (group) can close one gate', !(await sees(alice, sc)))
await s.ask('lp group family permission unset waystation.gate.station-creative', 2500)
check('positive control: role node removed, gate back', await sees(alice, sc))
check('plugin event hook vetoes the gate', !(await sees(hooky, sd)))
check('positive control: hook leaves other gates alone', await sees(hooky, sc))
clearChat(alice); alice.chat('/gate list'); await sleep(800)
check('no admin permission: /gate refused', has(alice, /don't have permission/))
clearChat(boss); boss.chat('/gate list'); await sleep(800)
check('positive control: admin may use /gate', boss.chat_log.some(m => /station-meadow/.test(m)))
await s.ask('lp user alice permission set waystation.admin.list true', 800)
clearChat(alice); alice.chat('/gate list'); await sleep(800); const listOk = alice.chat_log.some(m => /station-meadow/.test(m))
clearChat(alice); alice.chat('/gate reload'); await sleep(800)
check('narrow admin role: list allowed, reload refused', listOk && has(alice, /don't have permission/))
await s.ask('lp user alice permission unset waystation.admin.list', 500)

// ---------- /waygate ----------
console.log('\n# /waygate')
clearChat(alice); await s.ask('execute in waystation run tp alice 0 -60 0', 800); alice.chat('/waygate'); await sleep(800)
console.log('  alice:', alice.chat_log.at(-1))
const am = alice.chat_log.join(' ').match(/at (-?\d+) (-?\d+) (-?\d+), (\d+) blocks (\w+)/)
// Every station gate she can see, OneBlock included: the random layout decides which is nearest.
const canSeeIds = []
for (const x of Object.values(g)) if (x?.world === 'waystation' && x.id && await sees(alice, x)) canSeeIds.push(x)
console.log('  alice sees:', canSeeIds.map(x => x.id).join(', '))
const expected = canSeeIds.map(x => Math.hypot(x.x + .5 - .5, x.z + .5 - .5)).sort((a, b) => a - b)[0]
check('waygate gives coordinates of the nearest gate she can use', am && Math.abs(+am[4] - expected) <= 2, `says ${am && am[4]}, nearest ${expected?.toFixed(1)}`)
clearChat(amy); await s.ask('execute in waystation run tp amy 0 -60 0', 800); amy.chat('/waygate'); await sleep(800)
console.log('  amy:', amy.chat_log.at(-1))
// The gate at the coordinates she's given must be one she can use; the random layout decides which.
const amyAt = amy.chat_log.join(' ').match(/at (-?\d+) (-?\d+) (-?\d+),/)
const amyGate = amyAt && Object.values(g).find(x => x?.world === 'waystation' && x.x === +amyAt[1] && x.z === +amyAt[3])
const amyHidden = []
for (const x of Object.values(g)) if (x?.world === 'waystation' && x.id && !(await sees(amy, x))) amyHidden.push(x.id)
check('control: some station gates are hidden from amy', amyHidden.length > 0, amyHidden.join(', '))
check("waygate never reveals a gate you can't use", !!amyGate && await sees(amy, amyGate), amyGate?.id ?? 'no gate at those coordinates')
await s.ask('mvtp alice w:meadow_nether', 2500); clearChat(alice); alice.chat('/waygate'); await sleep(800)
check('waygate in a world with no gates says so and offers the way out', has(alice, /no waygate you can use/) && has(alice, /\/station/), alice.chat_log.at(-1))
await s.ask('mvtp alice w:waystation', 2000)

// ---------- travel, inventories, XP, last spot ----------
console.log('\n# Travel, inventories, XP, last spot')
await walkThrough(alice, sd)
check('positive control: permitted player walks through the gate', await isIn(alice, 'meadow', 8000))
await sleep(2500)
await s.ask('clear alice', 300); await s.ask('give alice diamond 7', 300); await s.ask('xp set alice 11 levels', 300); await sleep(800)
check('items arrive in Survival', invCount(alice, 'diamond') === 7)
const rd = g['return-meadow']
await walkThrough(alice, rd)
check('return gate leads back to the station', await isIn(alice, 'waystation', 8000))
await sleep(2500)
check('Survival items do not follow into the station', invCount(alice, 'diamond') === 0, `diamonds ${invCount(alice, 'diamond')}`)
await s.ask('give alice apple 3', 500); await sleep(500)
await walkThrough(alice, sc)
check('walks to Creative', await isIn(alice, 'creative', 8000))
await sleep(2500)
check('Creative group starts its own inventory', invCount(alice, 'diamond') === 0 && invCount(alice, 'apple') === 0)
await s.ask('give alice emerald 2', 500); await sleep(500)
const rc = g['return-creative']
await walkThrough(alice, rc)
check('back to the station from Creative', await isIn(alice, 'waystation', 8000))
await sleep(2500)
check('station inventory intact', invCount(alice, 'apple') === 3 && invCount(alice, 'emerald') === 0 && invCount(alice, 'diamond') === 0)
await walkThrough(alice, sd)
check('back to Meadow', await isIn(alice, 'meadow', 8000))
await sleep(3000)
check('Survival inventory is back exactly', invCount(alice, 'diamond') === 7 && invCount(alice, 'apple') === 0 && invCount(alice, 'emerald') === 0, `diamonds ${invCount(alice, 'diamond')}`)
check('XP is back', alice.experience.level === 11, `level ${alice.experience.level}`)
const p = alice.entity.position
const dist = Math.hypot(p.x - (rd.x + .5), p.z - (rd.z + .5))
console.log(`  arrived at ${p.x.toFixed(1)} ${p.y.toFixed(1)} ${p.z.toFixed(1)}, gate at ${rd.x} ${rd.y} ${rd.z}`)
check('survival world: you arrive at its gate', dist < 3, `${dist.toFixed(1)} from the gate`)
check('no teleport loop: she stays in Meadow', await isIn(alice, 'meadow', 1500))
await s.ask(`execute in meadow run tp alice ${rd.x + 8} ${rd.y + 2} ${rd.z + 8}`, 2500)
await s.ask('mvtp alice w:waystation', 2500); await sleep(2500)
await walkThrough(alice, sd); await sleep(2500)
const backAtGate = Math.hypot(alice.entity.position.x - (rd.x + .5), alice.entity.position.z - (rd.z + .5))
check('survival world: went back', await isIn(alice, 'meadow', 1500))
check('survival world: still the gate, wherever you left from', backAtGate < 3, `${backAtGate.toFixed(1)} from the gate`)
await s.ask('mvtp alice w:creative', 2500)
const farX = rc.x + 25, farZ = rc.z + 12
await s.ask(`execute in creative run tp alice ${farX} ${rc.y + 3} ${farZ}`, 2000)
await s.ask('mvtp alice w:waystation', 2500); await sleep(800)
await walkThrough(alice, sc); await sleep(2500)
const pc = alice.entity.position
check('creative world: you return to your last spot', Math.hypot(pc.x - farX, pc.z - farZ) < 3, `${Math.hypot(pc.x - farX, pc.z - farZ).toFixed(1)} blocks off`)
// Leave only once she's landed: a spot in mid-air isn't safe, so it would rightly send her to spawn.
for (let i = 0; i < 40 && !alice.entity.onGround; i++) await sleep(100)
await sleep(500)
await s.ask('mvtp alice w:meadow', 2500)
await sleep(800)

const npos = { x: pc.x, z: pc.z }
console.log('\n# Restart')
for (const b of [alice, amy, nina, carl, boss, oppy, hooky]) b.removeAllListeners('end')
await s.stop(); await sleep(1500)
await s.start(); await sleep(8000)
const alice2 = await bot('alice')
await sleep(3000)
check('after restart: inventory intact', invCount(alice2, 'diamond') === 7 && invCount(alice2, 'apple') === 0)
check('after restart: XP intact', alice2.experience.level === 11)
await s.ask('mvtp alice w:waystation', 3000)
await sleep(2000); await walkThrough(alice2, sc)
await sleep(3000)
check('after restart: went to Creative', await isIn(alice2, 'creative', 2000))
await sleep(1500) // the server has moved her; let the bot hear about it
const p2 = alice2.entity.position
check('after restart: last spot remembered', Math.hypot(p2.x - npos.x, p2.z - npos.z) < 3, `${Math.hypot(p2.x - npos.x, p2.z - npos.z).toFixed(1)} blocks off`)
await s.ask('mvtp alice w:waystation', 2500); await sleep(800)
await walkThrough(alice2, sd)
check('after restart: still walks through', await isIn(alice2, 'meadow', 8000))
await sleep(3000)


const alicePhase2 = alice2

// ---------- tribute chest ----------
console.log('\n# The tribute chest')
const a = alice2
const cfgMove = DIR + '/plugins/Waystation/config.yml'
const carl2 = await bot('carl')
await s.ask('mvtp carl w:meadow', 2500)
const setMove = async ({ rest = 0, search = 0 } = {}) => {
  const text = readFileSync(cfgMove, 'utf8').replace(/rest-seconds: \d+/, `rest-seconds: ${rest}`).replace(/search-chunks: \d+/, `search-chunks: ${search}`)
  writeFileSync(cfgMove, text)
  const out = await s.ask('gate reload', 1200)
  if (!out.join(' ').includes('reloaded')) console.log('  reload said: ' + out.join(' ').slice(0, 200))
}
const rdNow = () => gates().then(x => x['return-meadow'])
const flat = async ([cx0, cz0], y = 100) => {
  const x0 = cx0 << 4, z0 = cz0 << 4
  await s.ask(`execute in meadow run forceload add ${x0} ${z0}`, 700)
  await s.ask(`execute in meadow run fill ${x0} ${y} ${z0} ${x0 + 15} ${y} ${z0 + 15} stone`, 500)
  await s.ask(`execute in meadow run fill ${x0} ${y + 1} ${z0} ${x0 + 15} ${y + 60} ${z0 + 15} air`, 700)
  return { x: x0 + 8, y: y + 1, z: z0 + 8 }
}
const bedAt = (b, at) => s.ask(`execute in meadow run spawnpoint ${b.username} ${at.x} ${at.y} ${at.z}`, 500)
const heal = async b => { await s.ask(`effect give ${b.username} minecraft:instant_health 1 5`, 900); await s.ask(`effect clear ${b.username}`, 100) }
const hurtTo = async (b, hp) => { await heal(b); await s.ask(`damage ${b.username} ${20 - hp}`, 900) }
/** Walks up to this world's gate and clicks its chest. Resolves to the menu, or null if none opened. */
async function clickChest(b) {
  const g0 = await rdNow(), [dx, dz] = dir[g0.facing]
  await s.ask(`execute in meadow run tp ${b.username} ${g0.x + .5 + dx * 2} ${g0.y} ${g0.z + .5 + dz * 2}`, 1500)
  const chest = Object.values(b.entities).find(e => e.name === 'interaction' && Math.hypot(e.position.x - g0.x, e.position.z - g0.z) < 5)
  if (!chest) { console.log('  no chest seen by ' + b.username); return null }
  const opened = new Promise(r => b.once('windowOpen', r))
  await b.activateEntity(chest)
  return Promise.race([opened, sleep(3500).then(() => null)])
}
const menuItems = win => win.slots.slice(0, win.inventoryStart).filter(Boolean)
const realHealth = async b => Number((await s.ask(`data get entity ${b.username} Health`, 400)).join(' ').match(/([\d.]+)f/)?.[1]) // the client's number is scaled by AuraSkills
const pick = async (b, win, name, wait = 2200) => { const it = menuItems(win).find(i => i.name === name); if (!it) return false; await b.clickWindow(it.slot, 0, 0); await sleep(wait); return true }
const pos0 = await rdNow()
const cx = pos0.x >> 4, cz = pos0.z >> 4

for (const w of ['meadow', 'kids_survival']) await s.ask(`execute in ${w} run gamerule natural_health_regeneration false`, 200) // so hearts count exactly
await setMove()
await heal(a); clearChat(a)
const nobed = await clickChest(a); await sleep(500)
check('no bed in this world: told, and no chest opens', !nobed && has(a, /Sleep somewhere in this world/), a.chat_log.at(-1))

const bad = [cx + 3, cz + 3], bx = bad[0] << 4, bz = bad[1] << 4
await s.ask(`execute in meadow run forceload add ${bx} ${bz}`, 700)
await s.ask(`execute in meadow run fill ${bx} 40 ${bz} ${bx + 15} 140 ${bz + 15} stone`, 1000)
await s.ask(`execute in meadow run fill ${bx} 141 ${bz} ${bx + 15} 200 ${bz + 15} water`, 1000)
await s.ask(`execute in meadow run fill ${bx} -60 ${bz} ${bx + 15} 39 ${bz + 15} stone`, 1000)
await bedAt(a, { x: bx + 8, y: 201, z: bz + 8 })
clearChat(a); const h0 = a.health
const noSpot = await clickChest(a); await sleep(500)
check('no safe ground near your bed: told, no chest, nothing taken', !noSpot && has(a, /no safe open ground/) && a.health === h0, a.chat_log.at(-1))

const good = [cx - 5, cz + 3]
const goodAt = await flat(good)
await bedAt(a, goodAt)
let win = await clickChest(a)
check('with a bed and safe ground, the chest opens', !!win)
if (win) { a.closeWindow(win); await sleep(800) }
check('closing it takes nothing and moves nothing', a.health === h0 && (await rdNow()).x === pos0.x)

// Someone else's claim, real GriefPrevention.
const claimChunk = [cx - 3, cz - 3]
const claimAt = await flat(claimChunk)
await s.ask(`execute in meadow run tp carl ${claimAt.x} ${claimAt.y} ${claimAt.z}`, 2000)
await s.ask('adjustbonusclaimblocks carl 5000', 400)
await s.ask('give carl golden_shovel', 800); carl2.setQuickBarSlot(carl2.inventory.items().find(i => i.name === 'golden_shovel').slot - 36); await sleep(400)
clearChat(carl2); carl2.chat('/claim 24'); await sleep(1500)
console.log('  carl claims: ' + carl2.chat_log.at(-1))
await bedAt(a, claimAt)
clearChat(a)
const claimed = await clickChest(a); await sleep(500)
check("bed on someone else's claim: refused, nothing taken", !claimed && has(a, /someone else's claim/) && a.health === h0, a.chat_log.at(-1))
await s.ask(`execute in meadow run tp carl ${claimAt.x} ${claimAt.y} ${claimAt.z}`, 1200); carl2.chat('/trust alice'); await sleep(1000)
const trusted = await clickChest(a)
check('positive control: once the owner trusts you, it opens', !!trusted)
if (trusted) { a.closeWindow(trusted); await sleep(800) }
await heal(carl2); await bedAt(carl2, claimAt)
win = await clickChest(carl2); clearChat(carl2)
if (win) await pick(carl2, win, 'redstone')
check('positive control: the owner can move it there', has(carl2, /tribute is accepted/), carl2.chat_log.at(-1))
const movedC = await rdNow()
check('the gate moved to the bed\'s chunk', movedC.x >> 4 === claimChunk[0] && movedC.z >> 4 === claimChunk[1], `${movedC.x} ${movedC.z}`)

console.log('\n# Paying in hearts')
await setMove({ rest: 300 })
await heal(a); await bedAt(a, goodAt)
await s.ask(`execute in meadow run tp carl ${goodAt.x} ${goodAt.y} ${goodAt.z}`, 2000)
const h1 = hashArea(carl2, good[0], good[1], -64, 200)
win = await clickChest(a); clearChat(a); clearChat(carl2)
const hpBefore = await realHealth(a)
check('with health to spare, the chest offers hearts', !!win && menuItems(win).length === 1 && menuItems(win)[0].name === 'redstone')
if (win) await pick(a, win, 'redstone', 2500)
check('hearts paid, gate moved', has(a, /tribute is accepted/), a.chat_log.at(-1))
const hpAfter = await realHealth(a)
check('exactly 6 hearts taken', Math.abs(hpBefore - 12 - hpAfter) < 0.01, `${hpBefore} -> ${hpAfter}`)
await s.ask(`execute in meadow run tp carl ${goodAt.x} ${goodAt.y} ${goodAt.z}`, 2000)
const h2 = hashArea(carl2, good[0], good[1], -64, 200)
check('a move changes no block', hashDiff(h1, h2) === 0, `${hashDiff(h1, h2)} differ of ${h1.length}`)
check('others in the world are told it moved, not where', carl2.chat_log.some(m => /has moved. Nobody is saying where/.test(m)))
const movedA = await rdNow()
check('it moved to the bed\'s chunk', movedA.x >> 4 === good[0] && movedA.z >> 4 === good[1])
await sleep(1500)
check('the chest moved with it', Object.values(carl2.entities).some(e => e.name === 'interaction' && Math.hypot(e.position.x - movedA.x, e.position.z - movedA.z) < 5))
await s.ask(`execute in meadow run setblock ${goodAt.x - 6} ${goodAt.y + 12} ${goodAt.z - 6} stone`, 800)
check('positive control: the block check notices a real change', hashDiff(h1, hashArea(carl2, good[0], good[1], -64, 200)) > 0)
await heal(carl2); clearChat(carl2); const rested = await clickChest(carl2); await sleep(500)
check('the gate rests: another player can\'t move it straight away', !rested && has(carl2, /moved recently/), carl2.chat_log.at(-1))
await heal(a); await s.ask('mvtp alice w:flatlands', 2500); await s.ask('mvtp alice w:meadow', 2500)
await walkThrough(a, movedA)
check('the moved gate still works', await isIn(a, 'waystation', 8000))
await s.ask('mvtp alice w:meadow', 2500)

console.log('\n# Not enough hearts')
await setMove()
const unrest = async () => { const r = await rdNow(); await s.ask(`gate move return-meadow meadow ${r.x} ${r.y} ${r.z}`, 900) }
await unrest()
const wearer = await bot('armorer')
await s.ask('lp user armorer parent add family', 300); await s.ask('mvtp armorer w:meadow', 2500); await s.ask('clear armorer', 200); await bedAt(wearer, goodAt)
await hurtTo(wearer, 12); clearChat(wearer)
const exact = await clickChest(wearer); await sleep(400)
check('6 hearts would kill you, and you wear nothing: refused, nothing taken', !exact && has(wearer, /Heal up, or wear something plain/) && wearer.health === 12, `${wearer.health} ${wearer.chat_log.at(-1)}`)
await s.ask('item replace entity armorer armor.chest with iron_chestplate', 200)
await s.ask('item replace entity armorer armor.head with diamond_helmet[enchantments={protection:1}]', 200)
await s.ask('item replace entity armorer armor.feet with iron_boots[custom_name="Lucky"]', 200)
await s.ask('item replace entity armorer armor.legs with leather_leggings', 800)
win = await clickChest(wearer)
const offered = win ? menuItems(win).map(i => i.name).sort().join(',') : ''
check('low on health: offers only plain worn pieces', offered === 'iron_chestplate,leather_leggings', offered)
const wornChest = () => wearer.inventory.slots[6]?.name, hpWearer = wearer.health
clearChat(wearer); if (win) await pick(wearer, win, 'iron_chestplate', 2500)
check('that piece pays', has(wearer, /tribute is accepted/), wearer.chat_log.at(-1))
check('it\'s taken, and only it', !wornChest() && wearer.inventory.slots[7]?.name === 'leather_leggings' && wearer.inventory.slots[5]?.name === 'diamond_helmet' && wearer.inventory.slots[8]?.name === 'iron_boots', [5, 6, 7, 8].map(i => wearer.inventory.slots[i]?.name).join(','))
check('and no hearts are taken', wearer.health === hpWearer, `${hpWearer} -> ${wearer.health}`)
await s.ask('item replace entity armorer armor.legs with air', 800); clearChat(wearer)
const onlyFancy = await clickChest(wearer); await sleep(400)
check('only named or enchanted pieces left: refused, and they stay', !onlyFancy && has(wearer, /Heal up, or wear something plain/) && wearer.inventory.slots[5]?.name === 'diamond_helmet', wearer.chat_log.at(-1))
await heal(wearer); const backFull = await clickChest(wearer)
check('positive control: healed up, it offers hearts again', !!backFull && menuItems(backFull)[0]?.name === 'redstone')
if (backFull) { wearer.closeWindow(backFull); await sleep(500) }

console.log('\n# Avoiding builds')
await heal(a)
const near1 = [cx - 7, cz + 3], at1 = await flat(near1), x1 = near1[0] << 4, z1 = near1[1] << 4
for (const [dx, dz] of [[4, 4], [4, 11], [11, 4]]) await s.ask(`execute in meadow run setblock ${x1 + dx} ${at1.y} ${z1 + dz} torch`, 150)
await bedAt(a, at1)
win = await clickChest(a); if (win) await pick(a, win, 'redstone', 2500)
let spotB = await rdNow()
const clearOfTorches = [[4, 4], [4, 11], [11, 4]].every(([dx, dz]) => Math.hypot(spotB.x - (x1 + dx), spotB.z - (z1 + dz)) > 5)
check('it picks ground away from signs of building', spotB.x >> 4 === near1[0] && clearOfTorches, `${spotB.x} ${spotB.z}`)
await heal(a)
const near2 = [cx - 9, cz + 3], at2 = await flat(near2), x2 = near2[0] << 4, z2 = near2[1] << 4
for (let dx = 1; dx < 16; dx += 4) for (let dz = 1; dz < 16; dz += 4) await s.ask(`execute in meadow run setblock ${x2 + dx} ${at2.y} ${z2 + dz} torch`, 60)
await bedAt(a, at2)
win = await clickChest(a); clearChat(a); if (win) await pick(a, win, 'redstone', 2500)
spotB = await rdNow()
check('but settles for open ground if that\'s all there is', spotB.x >> 4 === near2[0] && has(a, /tribute is accepted/), a.chat_log.at(-1))

console.log('\n# Kids\' worlds')
const nb = await bot('niner'); await s.ask('lp user niner parent add kids', 300); await s.ask('mvtp niner w:kids_survival', 2500); await sleep(800)
const ns = (await gates())['return-kids_survival']
await s.ask(`execute in kids_survival run spawnpoint niner ${ns.x + 2} ${ns.y} ${ns.z + 2}`, 400)
await s.ask(`execute in kids_survival run tp niner ${ns.x + .5 + dir[ns.facing][0] * 2} ${ns.y} ${ns.z + .5 + dir[ns.facing][1] * 2}`, 1500)
const nc = Object.values(nb.entities).find(e => e.name === 'interaction' && Math.hypot(e.position.x - ns.x, e.position.z - ns.z) < 5)
const nOpen = new Promise(r => nb.once('windowOpen', r)); if (nc) await nb.activateEntity(nc)
const nWin = await Promise.race([nOpen, sleep(3500).then(() => null)])
const hpKid = await realHealth(nb); clearChat(nb); if (nWin) await pick(nb, nWin, 'redstone', 2500)
const hpKidAfter = await realHealth(nb)
check('kids\' worlds ask for 3 hearts', Math.abs(hpKid - 6 - hpKidAfter) < 0.01 && nb.chat_log.some(m => /tribute is accepted/.test(m)), `${hpKid} -> ${hpKidAfter}`)
nb.end()

// ---------- durability ----------
console.log('\n# Durability')
const live2 = async (id) => { const out = await s.ask(`gate test ${id}`, 700); const m = out.join(' ').match(/entities (\d+)/); return m ? +m[1] : -1 }
const gg = await gates()
const wg = gg['return-flatlands'], wx = wg.x, wy = wg.y, wz = wg.z
const watcher = await bot('watcher'); await s.ask('mvtp watcher w:flatlands', 2500) // a player there, as in real play
await s.ask(`execute in flatlands run forceload add ${wx} ${wz}`, 1200); await sleep(1500)
const liveBefore = await live2('return-flatlands')
check('world gate is drawn', liveBefore > 3, `${liveBefore} entities`)
const groundAt = (x, y, z) => s.ask(`execute in flatlands if block ${x} ${y} ${z} minecraft:air`, 400).then(o => o.some(l => /Test passed/.test(l)))
await s.ask('execute in flatlands run gamerule tnt_explodes true', 300)
await s.ask(`execute in flatlands run summon tnt ${wx + 1} ${wy} ${wz + 1} {fuse:1}`, 2500)
check('survives TNT', (await live2('return-flatlands')) === liveBefore)
check('protected ground under it intact after TNT', !(await groundAt(wx, wy - 1, wz)))
await s.ask(`execute in flatlands run summon tnt ${wx + 20} ${wy} ${wz} {fuse:1}`, 2500)
check('positive control: the same TNT out of the zone makes a crater', await groundAt(wx + 20, wy - 1, wz))
await s.ask(`execute in flatlands run setblock ${wx + 1} ${wy} ${wz + 1} fire`, 2500)
check('survives fire', (await live2('return-flatlands')) === liveBefore)
await s.ask(`execute in flatlands run setblock ${wx + 1} ${wy} ${wz + 1} air`, 300)
// Piston: pushes a block into the zone. Positive control the same machine elsewhere.
async function piston(px, label) {
  await s.ask(`execute in flatlands run forceload add ${px} ${wz}`, 1000)
  await s.ask(`execute in flatlands run fill ${px} ${wy} ${wz - 8} ${px} ${wy + 1} ${wz + 8} air`, 200)
  await s.ask(`execute in flatlands run setblock ${px} ${wy} ${wz + 4} sticky_piston[facing=north]`, 200)
  await s.ask(`execute in flatlands run setblock ${px} ${wy} ${wz + 3} stone`, 300)
  await s.ask(`execute in flatlands run setblock ${px} ${wy} ${wz + 5} redstone_block`, 1500)
  const moved = !(await groundAt(px, wy, wz + 2))
  await s.ask(`execute in flatlands run setblock ${px} ${wy} ${wz + 5} air`, 800)
  return moved
}
check('piston can\'t push a block into a gate', !(await piston(wx, 'zone')))
check('survives pistons', (await live2('return-flatlands')) === liveBefore)
check('positive control: the same piston out of the zone pushes', await piston(wx + 14, 'control'))
await s.ask(`execute in flatlands run fill ${wx - 3} ${wy} ${wz - 3} ${wx + 3} ${wy + 5} ${wz + 3} stone`, 800)
check('survives console /fill', (await live2('return-flatlands')) === liveBefore)
await s.ask(`execute in flatlands run fill ${wx - 3} ${wy} ${wz - 3} ${wx + 3} ${wy + 5} ${wz + 3} air`, 800)
await s.ask('kill @e[tag=waystation]', 2500)
check('/kill is undone within seconds', (await live2('return-flatlands')) === liveBefore)
await s.ask(`execute as @e[tag=waystation] run tp @s ${wx + 60} ${wy} ${wz}`, 2500)
check('shoving entities is undone within seconds', (await live2('return-flatlands')) === liveBefore)

// ---------- protection, operators ----------
console.log('\n# Nobody can edit: operators too')
const opb = await bot('oppy')
await s.ask('mvtp oppy w:waystation', 2500); await s.ask('gamemode creative oppy', 800)
await s.ask('execute in waystation run tp oppy 0 -60 0', 1500)
const floor = opb.blockAt(new Vec3(1, -61, 1))
await opb.lookAt(floor.position.offset(.5, .5, .5), true)
await opb.dig(floor, 'ignore').catch(() => {}); await sleep(1000)
// The server's view, not the bot's: a refused dig can still show as air on the client until the correction arrives.
const floorGone = (await s.ask('execute in waystation if block 1 -61 1 minecraft:air', 800)).join(' ')
check('op in creative can\'t break the station floor', /Test failed/.test(floorGone), floorGone.slice(0, 120))
clearChat(opb); opb.chat('/setblock 1 -60 1 stone'); await sleep(800)
check('op /setblock in the station refused', has(opb, /Nothing can be changed|cannot/i) || (await s.ask('execute in waystation if block 1 -60 1 minecraft:air', 400)).some(l => /Test passed/.test(l)))
opb.chat('/fill -5 -60 -5 5 -58 5 stone'); await sleep(800)
check('op /fill in the station refused', (await s.ask('execute in waystation if block 2 -59 2 minecraft:air', 400)).some(l => /Test passed/.test(l)))
await s.ask('mvtp oppy w:flatlands', 2500); await s.ask('gamemode creative oppy', 800)
await s.ask(`execute in flatlands run tp oppy ${wx + 3} ${wy} ${wz}`, 1500)
const zb = opb.blockAt(new Vec3(wx, wy - 1, wz))
await opb.lookAt(zb.position.offset(.5, .5, .5), true); await opb.dig(zb, 'ignore').catch(() => {}); await sleep(1000)
check('op can\'t break ground at a world gate', opb.blockAt(new Vec3(wx, wy - 1, wz)).name !== 'air')
opb.chat(`/setblock ${wx} ${wy} ${wz} stone`); await sleep(800)
check('op /setblock on a world gate refused', await groundAt(wx, wy, wz))
const far = opb.blockAt(new Vec3(wx + 3, wy - 1, wz + 12))
await s.ask(`execute in flatlands run tp oppy ${wx + 3} ${wy} ${wz + 10}`, 1500)
const fb = opb.blockAt(new Vec3(wx + 3, wy - 1, wz + 12))
await opb.lookAt(fb.position.offset(.5, .5, .5), true); await opb.dig(fb, 'ignore').catch(() => {}); await sleep(1000)
check('positive control: the same op breaks a block away from the gate', opb.blockAt(new Vec3(wx + 3, wy - 1, wz + 12)).name === 'air')
opb.chat(`/setblock ${wx + 3} ${wy + 3} ${wz + 12} stone`); await sleep(800)
check('positive control: the same op /setblock away from the gate', !(await groundAt(wx + 3, wy + 3, wz + 12)))

// ---------- /mvtp ----------
console.log('\n# Gates replace /mvtp')
const nina2 = await bot('nina'), carl3 = carl2
await s.ask('mvtp alice w:waystation', 2500)
a.chat('/mvtp w:flatlands'); await sleep(2000)
check('positive control: before the setup, /mvtp works', await isIn(a, 'flatlands', 2000), a.chat_log.at(-1))
const chief = await bot('chief')
await s.ask('lp user chief parent add admin', 500); await s.ask('lp user chief parent add family', 500); await s.ask('mvtp chief w:waystation', 2000)
chief.chat('/mvtp alice w:creative'); await sleep(2000)
check('positive control: before the setup, an admin can /mvtp someone else', await isIn(a, 'creative', 2000))
for (const line of readFileSync(new URL('./luckperms-gates-only.txt', import.meta.url), 'utf8').split('\n')) {
  if (line.trim() && !line.startsWith('#')) await s.ask(line, 120)
}
await sleep(1000)
chief.chat('/mvtp w:creative'); await sleep(2000)
check('admins keep /mvtp', await isIn(chief, 'creative', 1500), chief.chat_log.at(-1))
chief.chat('/mvtp alice w:flatlands'); await sleep(2000)
check('and can still send someone else', await isIn(a, 'flatlands', 1500))
await s.ask('mvtp alice w:waystation', 2500)
a.chat('/mvtp w:flatlands'); await sleep(2000)
check('a regular adult has no /mvtp', await isIn(a, 'waystation', 1000), a.chat_log.at(-1))
await s.ask('mvtp nina w:waystation', 2500)
clearChat(nina2); nina2.chat('/mvtp w:kids_flatlands'); await sleep(1500)
check('a kid has no /mvtp', await isIn(nina2, 'waystation', 1000), nina2.chat_log.at(-1))
await walkThrough(nina2, gg['station-kids_flatlands'])
check('and the gate to that same world still works', await isIn(nina2, 'kids_flatlands', 8000))
await sleep(2000)
await s.ask('mvtp carl w:creative', 2500)
carl3.chat('/mvtp e:creative:10,80,10'); await sleep(1500)
const carlPos = carl3.entity.position
check('creative: /mvtp e: is refused too', Math.hypot(carlPos.x - 10, carlPos.z - 10) > 2)
carl3.chat('/tp 40 80 40'); await sleep(1500)
check('creative: vanilla /tp still works', Math.hypot(carl3.entity.position.x - 40, carl3.entity.position.z - 40) < 2)
await s.ask('lp user carl permission check tardis.timetravel world=meadow', 300)
const tardisCheck = await s.ask('lp user carl permission check tardis.use', 600)
await s.ask('mvtp carl w:meadow', 2500)
const [nx, ny, nz] = [carl3.entity.position.x, carl3.entity.position.y, carl3.entity.position.z].map(Math.floor)
await s.ask(`execute in meadow run setblock ${nx} ${ny} ${nz} nether_portal[axis=x]`, 200)
await s.ask(`execute in meadow run fill ${nx} ${ny} ${nz} ${nx} ${ny} ${nz} nether_portal[axis=x]`, 200)
await sleep(5500)
check('nether portals still work', await isIn(carl3, 'meadow_nether', 9000))
const tardisRegistered = (await s.ask('tardis version', 400)).length >= 0
if (s.log.some(l => /Enabling TARDIS/.test(l))) check('TARDIS still running', true)
else console.log('  NOTE: TARDIS is not installed on this copy, so it could not be checked.')
check('the setup touches no TARDIS permission', (await s.ask('lp group gates-only permission info', 400)).every(l => !/tardis/.test(l)))
await s.ask('mvtp nina w:kids_flatlands', 1200)
check('console can always move a player', await isIn(nina2, 'kids_flatlands', 1500))

// ---------- escape ----------
console.log('\n# Escape')
clearChat(nina2); nina2.chat('/station'); await sleep(2500)
check('/station takes you to the station', await isIn(nina2, 'waystation', 1500))
await s.ask('mvtp nina w:kids_flatlands', 2000)
nina2.chat('/hub'); await sleep(2500)
check('/hub alias works too', await isIn(nina2, 'waystation', 1500))
const cfg = DIR + '/plugins/Waystation/config.yml'
const originalCfg = readFileSync(cfg, 'utf8')
writeFileSync(cfg, originalCfg.replace(/escape:\n  enabled: true/, 'escape:\n  enabled: false'))
await s.ask('gate reload', 1500)
await s.ask('mvtp nina w:kids_flatlands', 2000); nina2.chat('/station'); await sleep(2000)
check('turned off: /station is gone', await isIn(nina2, 'kids_flatlands', 1000))
writeFileSync(cfg, originalCfg.replace('escape:\n  enabled: true', 'escape:\n  enabled: true') + '\nbad: [yaml')
const badOut = await s.ask('gate reload', 1200)
check('a broken config is refused and keeps running', badOut.join(' ').includes('has a problem'))
writeFileSync(cfg, originalCfg); await s.ask('gate reload', 1500)
nina2.chat('/station'); await sleep(2500)
check('turned back on: works', await isIn(nina2, 'waystation', 1500))

// ---------- worlds appear and die ----------
console.log('\n# Worlds appear and die')
await s.ask('mv create newsurvival normal --world-type flat', 8000)
await sleep(5000)
let g2 = await gates()
check('a new world gets a pair by itself', !!g2['station-newsurvival'] && !!g2['return-newsurvival'], Object.keys(g2).length)
const pl = g2._lines.filter(l => /^Platform/.test(l))
console.log('  ' + pl.join('\n  '))
check('it joins its group\'s platform', pl.some(l => /\(Survival\).*station-newsurvival/.test(l)))
check('it stands with its group', sameWall(g2['station-newsurvival'], g2['station-meadow']))
check('the station made room: no gates overlap', Object.values(g2).filter(x => x.world === 'waystation').every((p, i, all) => all.every((q, j) => i === j || Math.hypot(p.x - q.x, p.z - q.z) >= 7)))
await s.ask('mv create elsewhere normal --world-type flat', 8000); await sleep(5000)
let g3 = await gates()
check('a world in no group gets its own platform', g3._lines.some(l => /^Platform \d+ \(Other\).*station-elsewhere/.test(l)))
await s.ask('lp user boss permission set multiverse.access.newsurvival true', 500)
const boss2 = await bot('boss'); await s.ask('mvtp boss w:waystation', 2500); await sleep(1500)
const sn = g3['station-newsurvival']
check('new gate visible to someone with access', await sees(boss2, sn))
const entitiesLive = near(boss2, sn.x, sn.z, 5).length
const confirmDelete = async world => {
  const out = await s.ask(`mv delete ${world}`, 1000)
  const code = out.join(' ').match(/mv confirm (\d+)/)?.[1]
  await s.ask(`mv confirm ${code ?? ''}`, 6000); await sleep(4000)
}
await confirmDelete('newsurvival')
let g4 = await gates()
check('deleted world: the gate stays', !!g4['station-newsurvival'], g4['station-newsurvival']?.rest)
check('deleted world: it reports dead', /DEAD/.test(g4['station-newsurvival']?.rest ?? ''))
check('deleted world: the return gate went with it', !g4['return-newsurvival'])
await sleep(1500)
const deadLive = near(boss2, sn.x, sn.z, 5).length
check('dead gate still drawn, without its opening', deadLive > 0 && deadLive < entitiesLive, `${entitiesLive} -> ${deadLive}`)
clearChat(boss2); await walkThrough(boss2, g4['station-newsurvival']); await sleep(1200)
check('dead gate goes nowhere and says why', has(boss2, /line has closed/) && await isIn(boss2, 'waystation', 1000), boss2.chat_log.at(-1))
await s.stop(); await s.start(); await sleep(8000)
let g5 = await gates()
check('dead gate survives a restart', /DEAD/.test(g5['station-newsurvival']?.rest ?? ''))
const out = await s.ask('gate remove station-newsurvival', 2500)
let g6 = await gates()
check('admin /gate remove clears it', !g6['station-newsurvival'], out.join(' '))
check('the station closes the gap', g6['station-survival'].x !== undefined && JSON.stringify(g6['station-newsurvival']) === undefined)

// ---------- admin commands ----------
console.log('\n# Admin commands')
await s.ask('gate create testgate lobby waystation 40 -60 40 north', 1200)
let g7 = await gates()
check('create', !!g7.testgate)
await s.ask('gate move testgate waystation 45 -60 40', 1000); g7 = await gates()
check('move', g7.testgate.x === 45)
await s.ask('gate create testb creative creative 100 80 100 north', 1000)
await s.ask('gate link testgate testb', 1000); g7 = await gates()
check('link', g7.testgate.dest === 'creative' && g7.testb.dest === 'waystation')
const testOut = (await s.ask('gate test station-meadow', 800)).join(' ')
check('test reports state', /destination meadow/.test(testOut) && /entities/.test(testOut), testOut.slice(0, 100))
await s.ask('gate remove testgate', 800); await s.ask('gate remove testb', 800); g7 = await gates()
check('remove', !g7.testgate && !g7.testb)
// ---------- economy ----------
console.log('\n# Buying a gate')
writeFileSync(cfg, readFileSync(cfg, 'utf8').replace(/^  legacy: +\{enabled: true, label: "<gold>Legacy"\}/m, '  legacy: {enabled: true, label: "<gold>Legacy", price: {xp-levels: 3, money: 40}}'))
await s.ask('gate reload', 2000)
const buyer = await bot('buyer')
await s.ask('mvtp buyer w:waystation', 2500); await sleep(2000)
const sl = (await gates())['station-legacy']
check('priced gate shows as locked to someone without access', await sees(buyer, sl))
clearChat(buyer); await walkThrough(buyer, sl); await sleep(1000)
check('walking in says the price and how to buy', has(buyer, /costs .*\/buygate legacy/) && await isIn(buyer, 'waystation', 800), buyer.chat_log.at(-1))
clearChat(buyer); buyer.chat('/buygate legacy'); await sleep(1500)
check("can't afford: refused, nothing charged", has(buyer, /don't have enough/) && (await s.ask('fakebalance buyer', 400)).join(' ').includes('100.0'))
await s.ask('xp set buyer 10 levels', 800); clearChat(buyer); buyer.chat('/buygate legacy'); await sleep(3500)
check('positive control: buying works', has(buyer, /is open for you/), buyer.chat_log.at(-1))
check('charged exactly', buyer.experience.level === 7 && (await s.ask('fakebalance buyer', 400)).join(' ').includes('60.0'))
await walkThrough(buyer, sl)
check('and the gate now lets them through', await isIn(buyer, 'legacy', 8000))
const noperm = await bot('buyer2'); await s.ask('lp group default permission set multiverse.access.waystation true', 300)
await s.ask('mvtp buyer2 w:waystation', 2500); await s.ask('xp set buyer2 10 levels', 500)
const beforeBreak = readFileSync(cfg, 'utf8')
writeFileSync(cfg, beforeBreak.replace('lp user <player> permission set multiverse.access.<world> true', 'say grant broken'))
await s.ask('gate reload', 1500)
clearChat(noperm); noperm.chat('/buygate legacy'); await sleep(3500)
check('grant fails: full refund', has(noperm, /paid back/) && noperm.experience.level === 10 && (await s.ask('fakebalance buyer2', 400)).join(' ').includes('100.0'), noperm.chat_log.at(-1))

const addOut = (await s.ask('gate addworld elsewhere "New Label"', 1500))
check('addworld', addOut.join(' ').includes('Other'))

writeFileSync(cfg, readFileSync(cfg, 'utf8'))
// ---------- BlueMap ----------
console.log('\n# BlueMap (real plugin, on the throwaway copy)')
await s.ask('save-all', 3000); await s.ask('bluemap reload', 12000)
await s.ask('bluemap stop', 1000)
async function markers(map) {
  try {
    const r = await fetch(`http://127.0.0.1:8199/maps/${map}/live/markers.json`)
    if (!r.ok) return null
    return await r.json()
  } catch { return null }
}
let md = null; for (let i = 0; i < 30 && !md?.['waystation-gates']; i++) { md = await markers('meadow'); await sleep(1000) }
check('world gate is a BlueMap marker', !!md?.['waystation-gates']?.markers?.['return-meadow'], JSON.stringify(md?.['waystation-gates']?.markers?.['return-meadow'])?.slice(0, 120))
// BlueMap writes markers on its own schedule, so give the station's map a little time.
let ms = null; for (let i = 0; i < 20 && Object.keys(ms?.['waystation-gates']?.markers ?? {}).length < 9; i++) { ms = await markers('waystation'); await sleep(1000) }
console.log('  station map markers:', Object.keys(ms?.['waystation-gates']?.markers ?? {}).join(', ') || 'none')
check('Quiet never appears on the map', !Object.keys(ms?.['waystation-gates']?.markers ?? {}).some(k => /quiet/.test(k)))
check('the station world has its own map with its gates', Object.keys(ms?.['waystation-gates']?.markers ?? {}).length >= 9, `${Object.keys(ms?.['waystation-gates']?.markers ?? {}).length} markers`)
await confirmDelete('elsewhere'); await sleep(2000)
const deadMarkers = await markers('waystation')
check('dead gates are labelled on the map', /line closed/.test(deadMarkers?.['waystation-gates']?.markers?.['station-elsewhere']?.label ?? ''), deadMarkers?.['waystation-gates']?.markers?.['station-elsewhere']?.label)

// ---------- shutdown ----------
console.log('\n# OneBlock')
// The OneBlock checks need a real OneBlock plugin, which the small stand-in (test/prepare-small.mjs) doesn't have.
const hasOneBlock = readdirSync(DIR + '/plugins').some(f => /oneblock/i.test(f) && f.endsWith('.jar'))
if (!hasOneBlock) console.log('  skipped: no OneBlock plugin on this copy. These checks need the real copy.')
else {
  const ob = await gates(); const sob = ob['station-oneblock']
  check('OneBlock has a station gate', !!sob)
  check('and needs no return gate', !ob['return-oneblock'])
  check('and no warning about one', !s.log.some(l => /No good spot.*oneblock/.test(l)))
  const obA = await bot('obann'), obB = await bot('obcal')
  for (const b of [obA, obB]) { await s.ask(`mvtp ${b.username} w:waystation`, 1800); await s.ask(`clear ${b.username}`, 200) }
  await s.ask('give obann diamond 3', 500)
  const inWorldObA = () => isIn(obA, 'oneblock', 1500)
  await walkThrough(obA, sob); await sleep(3000)
  const pa = obA.entity.position
  check('walking through lands you on your own island', await inWorldObA() && pa.y > 80, pa.toString())
  await walkThrough(obB, sob); await sleep(3000)
  const pb = obB.entity.position
  check('a second player gets a different island', await isIn(obB, 'oneblock', 1500) && Math.hypot(pa.x - pb.x, pa.z - pb.z) > 400, pb.toString())
  check('inventories stay separate going in', cnt0(obA, 'diamond') === 0, `diamonds ${cnt0(obA, 'diamond')}`)
  await s.ask('give obann emerald 2', 500)
  await sleep(2500)
  const postOf = b => Object.values(b.entities).find(e => e.name === 'interaction' && Math.hypot(e.position.x - b.entity.position.x, e.position.z - b.entity.position.z) < 8)
  check('each island gets a post', !!postOf(obA) && !!postOf(obB))
  const blockAtIsland = obA.blockAt(pa.offset(2, -1, 0).floored())
  check('the post places no block', !blockAtIsland || blockAtIsland.name === 'air', blockAtIsland?.name)
  clearChat(obA); await obA.activateEntity(postOf(obA)); await sleep(1500)
  check('one click only asks', has(obA, /Click the post again/) && await inWorldObA(), obA.chat_log.at(-1))
  await obA.activateEntity(postOf(obA)); await sleep(3500)
  const back = obA.entity.position
  check('a second click takes you to the station', await isIn(obA, 'waystation', 2000))
  check('beside the OneBlock gate', Math.hypot(back.x - (sob.x + .5), back.z - (sob.z + .5)) < 4, back.toString())
  check('the station inventory is its own', cnt0(obA, 'emerald') === 0 && cnt0(obA, 'diamond') === 3, `emeralds ${cnt0(obA, 'emerald')} diamonds ${cnt0(obA, 'diamond')}`)
  await walkThrough(obA, sob); await sleep(3000)
  check('going back returns you to your own island', Math.hypot(obA.entity.position.x - pa.x, obA.entity.position.z - pa.z) < 5 && cnt0(obA, 'emerald') === 2, `${obA.entity.position} emeralds ${cnt0(obA, 'emerald')}`)
  obA.chat('/oneblock leave'); await sleep(2500)
  check('/oneblock leave still works', await isIn(obA, 'lobby', 1500))
  obB.chat('/station'); await sleep(2500)
  check('/station works from an island', await isIn(obB, 'waystation', 1500))
  await sleep(2000)
  await s.ask('execute in oneblock run forceload add 0 0', 800)
  check('the post is gone once nobody is there', (await s.ask('execute in oneblock if entity @e[tag=waystation-island-post]', 500)).join(' ').includes('failed'))
  await s.ask('execute in oneblock run forceload remove 0 0', 200)
  obA.end(); obB.end(); await sleep(400)
}

console.log('\n# A crash with the tribute chest open')
const cr = await bot('cracker')
await s.ask('lp user cracker parent add family', 300); await s.ask('mvtp cracker w:meadow', 2500)
await s.ask('clear cracker', 200); await s.ask('item replace entity cracker armor.chest with iron_chestplate', 300)
const crAt = await flat([cx - 11, cz + 3]); await s.ask(`execute in meadow run spawnpoint cracker ${crAt.x} ${crAt.y} ${crAt.z}`, 400)
// Without a finished save a crash rolls back anything gained since the last one, Waystation or not.
await s.ask('save-all flush', 4000)
const rNow = await rdNow(); const [cdx, cdz] = dir[rNow.facing]
await s.ask(`execute in meadow run tp cracker ${rNow.x + .5 + cdx * 2} ${rNow.y} ${rNow.z + .5 + cdz * 2}`, 1500)
const crChest = Object.values(cr.entities).find(e => e.name === 'interaction' && Math.hypot(e.position.x - rNow.x, e.position.z - rNow.z) < 5)
const crOpened = new Promise(r => cr.once('windowOpen', r)); await cr.activateEntity(crChest)
const crWin = await Promise.race([crOpened, sleep(4000).then(() => null)])
check('chest opened for the crash test', !!crWin)
await s.kill9(); await sleep(2000)
await s.start(); await sleep(6000)
const cr2 = await bot('cracker'); await sleep(2500)
check('after kill -9: the worn piece is still worn', cr2.inventory.slots[6]?.name === 'iron_chestplate', cr2.inventory.slots[6]?.name)
check('and the gate did not move', (await rdNow()).x === rNow.x)
check('no Waystation errors after the crash', s.errors().length === 0, s.errors()[0]?.slice(0, 160))
cr2.end(); await sleep(500)

console.log('\n# Shutdown leaves nothing behind')
const wgg = await gates()
check('no Waystation errors in the log', s.errors().length === 0, s.errors()[0]?.slice(0, 160))
await s.stop(); await sleep(1500)
rmSync(DIR + '/plugins/' + PLUGIN_JAR, { force: true }); rmSync(DIR + '/plugins/DenyHook.jar', { force: true })
rmSync(DIR + '/plugins/.paper-remapped/' + PLUGIN_JAR, { force: true, recursive: true }); rmSync(DIR + '/plugins/.paper-remapped/DenyHook.jar', { force: true, recursive: true })
await s.start()
for (const [world, gate] of [['waystation', wgg['station-meadow']], ['flatlands', wgg['return-flatlands']]]) {
  await s.ask(`execute in ${world} run forceload add ${gate.x - 32} ${gate.z - 32} ${gate.x + 32} ${gate.z + 32}`, 5000)
  await sleep(2000)
  const n1 = (await s.ask(`execute in ${world} run kill @e[type=minecraft:block_display]`, 800)).join(' ')
  const n2 = (await s.ask(`execute in ${world} run kill @e[type=minecraft:text_display]`, 800)).join(' ')
  check(`clean shutdown: no display entities left in ${world}`, /No entity was found/.test(n1) && /No entity was found/.test(n2), `${n1.slice(0, 50)} / ${n2.slice(0, 50)}`)
}
await s.stop()
// Crash test: kill -9 with the plugin in, then look with it out.
installJar(); await s.start(); await sleep(8000)
const crashBot = await bot('nina'); await s.ask('mvtp nina w:waystation', 3000); await sleep(3000)
await s.kill9(); await sleep(1500)
rmSync(DIR + '/plugins/' + PLUGIN_JAR, { force: true }); rmSync(DIR + '/plugins/.paper-remapped/' + PLUGIN_JAR, { force: true, recursive: true })
await s.start()
await s.ask(`execute in waystation run forceload add -32 -32 32 32`, 5000); await sleep(2000)
const after9 = (await s.ask('execute in waystation run kill @e[type=minecraft:block_display]', 800)).join(' ')
check('after kill -9: no display entities left', /No entity was found/.test(after9), after9.slice(0, 60))
await s.stop()
console.log('\nWaystation warnings seen:')
console.log([...new Set(s.log.filter(l => /WARN.*\[Waystation\]/.test(l)).map(l => l.replace(/^\[[\d:]+ \w+\]: /, '').slice(0, 170)))].join('\n') || '  none')
const ok = summary()
process.exit(ok ? 0 : 1)
