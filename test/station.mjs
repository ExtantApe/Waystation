// The generator: every floor plan must be walkable and non-overlapping, seeds must repeat exactly,
// and the room must not cost the server anything noticeable.
import { readFileSync, writeFileSync } from 'node:fs'
import { Server, freshCopy, installJar, sleep, bot, walkTo, check, summary, DIR } from './harness.mjs'
const s = new Server()
const dir = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }
async function read() {
  const out = await s.ask('gate list', 1000), m = {}
  for (const l of out) { const x = l.match(/(station-\S+): waystation (-?\d+) (-?\d+) (-?\d+) (\w+)/); if (x) m[x[1]] = { x: +x[2], y: +x[3], z: +x[4], f: x[5] } }
  return { gates: m, header: out.find(l => /^Station seed/.test(l)) ?? '' }
}
const avgTick = async () => { const o = (await s.ask('tick query', 500)).join(' '); return Number(o.match(/Average time per tick: ([\d.]+)ms/)?.[1] ?? NaN) }

freshCopy(); await s.start()
const base = await bot('walker'); await s.ask('mvtp walker w:lobby', 2000); await sleep(6000)
const before = await avgTick(); console.log('no plugin: average tick ' + before + 'ms'); base.end(); await s.stop()
installJar(); await s.start(); await sleep(12000)
await s.ask('lp group default permission set multiverse.access.waystation true', 300)
const b = await bot('walker'); await s.ask('lp user walker parent add family', 400); await s.ask('mvtp walker w:waystation', 2500)
const plans = new Set(), shapes = new Set()
for (let seed = 1; seed <= 24 && plans.size < 5; seed++) {
  await s.ask(`gate reseed ${seed}`, 3500)
  const { gates, header } = await read()
  const plan = header.match(/plan (\w+)/)?.[1]
  if (plans.has(plan)) continue
  plans.add(plan)
  const list = Object.values(gates)
  console.log(`\n# seed ${seed}: ${plan}, ${list.length} gates`)
  check(`${plan}: every station gate placed`, list.length >= 12, Object.keys(gates).join(','))
  let tooClose = 0
  list.forEach((p, i) => list.forEach((q, j) => { if (i < j && Math.hypot(p.x - q.x, p.z - q.z) < 7) tooClose++ }))
  check(`${plan}: no gates overlap`, tooClose === 0)
  check(`${plan}: nothing stands on the spawn`, list.every(p => Math.hypot(p.x, p.z) > 4))
  check(`${plan}: all inside the border`, list.every(p => Math.abs(p.x) < 145 && Math.abs(p.z) < 145))
  const g = gates['station-flatlands']
  const [dx, dz] = dir[g.f]
  await s.ask(`execute in waystation run tp walker ${g.x + .5 + dx * 3} ${g.y} ${g.z + .5 + dz * 3}`, 1200)
  await walkTo(b, g.x + .5, g.z + .5, 5000); await sleep(2200)
  const through = (await s.ask('execute in flatlands if entity @a[name=walker,distance=0..]', 500)).some(l => /Test passed/.test(l))
  check(`${plan}: a real walk through a gate works`, through)
  await s.ask('mvtp walker w:waystation', 2500)
  const n = (await s.ask('execute in waystation if entity @e[tag=waystation]', 500)).join(' ').match(/Count: (\d+)/)?.[1]
  await s.ask('execute in waystation run tp walker 0.5 -60 0.5', 1000); await sleep(7000)
  const tick = await avgTick()
  console.log(`  ${n} entities near the player, average tick ${tick}ms`)
  check(`${plan}: under 900 entities`, +n > 20 && +n < 900, `${n}`)
  check(`${plan}: tick time stays easy`, tick < Math.max(before * 3, 15), `${tick}ms vs ${before}ms without the plugin`)
}
check('all five floor plans came up', plans.size === 5, [...plans].join(', '))
console.log('\n# A gate with nowhere to stand')
await s.ask('op chief', 200)
const chief = await bot('chief'); await s.ask('lp user chief permission set waystation.admin true', 400)
await s.ask('gate reseed 4242', 3500)
const clean = (await read()).gates, spot = clean['station-meadow']
const water = (block) => s.ask(`execute in waystation run fill ${spot.x - 3} -61 ${spot.z - 3} ${spot.x + 3} -60 ${spot.z + 3} ${block}`, 800)
await s.ask(`execute in waystation run forceload add ${spot.x} ${spot.z}`, 800)
await water('water')
chief.chat_log.length = 0
const told = (await s.ask('gate relayout', 3500)).join(' ')
check('the console is told', /No safe floor for station-meadow/.test(told) || s.log.some(l => /No safe floor for station-meadow/.test(l)), told.slice(0, 120))
await sleep(800)
check('an admin online is asked what to do', chief.chat_log.some(m => /gate enlarge/.test(m) && /gate reseed/.test(m)), chief.chat_log.at(-1))
const plain = await bot('plainone'); await sleep(800)
check('a non-admin is not bothered with it', !plain.chat_log.some(m => /gate enlarge/.test(m)))
plain.end()
chief.chat_log.length = 0; const relog = await s.ask('gate list', 800)
check('it shows in /gate list until solved', relog.some(l => /No safe floor for: station-meadow/.test(l)))
await s.ask('gate enlarge', 3500)
let big = await read()
let grew = !big.header.includes('enlarged 0') && big.gates['station-meadow'].x !== undefined
const stillBlocked = (await s.ask('gate list', 800)).some(l => /No safe floor/.test(l))
console.log('  after enlarge: ' + (await s.ask('gate list', 800)).filter(l => /No safe floor|station-meadow/.test(l)).join(' | ').slice(0, 220))
check('/gate enlarge keeps this station with more room', grew && !stillBlocked && big.header.includes('seed 4242'), big.header)
const span = Math.max(...Object.values(big.gates).flatMap(p => [Math.abs(p.x), Math.abs(p.z)]))
const border = (await s.ask('execute in waystation run worldborder get', 500)).join(' ').match(/(\d+) block/)?.[1]
check('the border still contains it', +border / 2 > span, `border ${border}, reach ${span}`)
check('a walk through a moved gate still works', await (async () => { const g = big.gates['station-flatlands']; const [dx, dz] = dir[g.f]; await s.ask(`execute in waystation run tp walker ${g.x + .5 + dx * 3} ${g.y} ${g.z + .5 + dz * 3}`, 1200); await walkTo(b, g.x + .5, g.z + .5, 5000); await sleep(2200); const ok = (await s.ask('execute in flatlands if entity @a[name=walker,distance=0..]', 500)).some(l => /Test passed/.test(l)); await s.ask('mvtp walker w:waystation', 2500); return ok })())
await s.ask('gate reseed 4242', 3500)
const blockedAgain = (await s.ask('gate list', 800)).some(l => /No safe floor/.test(l))
let reseeded = true
for (const seed of [5353, 5454, 5555, 5656]) { // any given seed might land on the water too
  await s.ask(`gate reseed ${seed}`, 3500)
  reseeded = (await s.ask('gate list', 800)).some(l => /No safe floor/.test(l))
  if (!reseeded) break
}
check('/gate reseed also solves it', blockedAgain && !reseeded, `blocked with the old seed ${blockedAgain}, with the new ${reseeded}`)
await water('grass_block'); await s.ask(`execute in waystation run fill ${spot.x - 3} -60 ${spot.z - 3} ${spot.x + 3} -60 ${spot.z + 3} air`, 800)
await s.ask(`execute in waystation run forceload remove ${spot.x} ${spot.z}`, 300)
chief.end()

console.log('\n# Readable labels')
// Every floating label gets a shadow and a dark plate (75% black, 0xBF000000) unless the config says otherwise.
const PLATE = -1090519040 // 0xBF000000 as Minecraft stores it, a signed int
const labels = async nbt => Number((await s.ask(`execute in waystation if entity @e[type=text_display,tag=waystation${nbt}]`, 500)).join(' ').match(/Count: (\d+)/)?.[1] ?? 0)
await s.ask('execute in waystation run tp walker 0.5 -60 0.5', 1000); await sleep(3000)
const allLabels = await labels(''), plated = await labels(`,nbt={shadow:1b,background:${PLATE}}`)
check('every label has a shadow and the dark plate', allLabels > 0 && plated === allLabels, `${plated} of ${allLabels}`)
const cfgFile = DIR + '/plugins/Waystation/config.yml', cfgText = readFileSync(cfgFile, 'utf8')
writeFileSync(cfgFile, cfgText.replace(/^label-backing:.*$/m, '') + '\nlabel-backing: 0\n'); await s.ask('gate reload', 2500); await sleep(2000)
const labelsOff = await labels(''), platedOff = await labels(`,nbt={background:${PLATE}}`)
const unshadowed = [await labels(',nbt=!{shadow:1b}')]; await sleep(2000); unshadowed.push(await labels(',nbt=!{shadow:1b}'))
if (unshadowed.some(n => n > 0)) console.log('  a label without a shadow: ' + (await s.ask('execute in waystation run data get entity @e[type=text_display,tag=waystation,nbt=!{shadow:1b},limit=1]', 1500)).join(' ').slice(0, 900))
check('positive control: label-backing 0 keeps Minecraft\'s own background, and the shadow', labelsOff > 0 && platedOff === 0 && unshadowed.every(n => n === 0), `${labelsOff} labels, ${platedOff} plated, unshadowed ${unshadowed.join(' then ')}`)
// Labels are drawn once and left alone. One redrawn every second would churn entities for nothing.
await s.ask('execute in waystation run tag @e[type=text_display,tag=waystation] add stayput', 800)
const marked = await labels(',tag=stayput'); await sleep(3000)
const stillMarked = await labels(',tag=stayput'), nowLabels = await labels('')
check('labels stay put between passes (none redrawn every second)', marked > 0 && stillMarked === marked && nowLabels === marked, `${marked} marked, ${stillMarked} still there 3 s later, ${nowLabels} labels now`)
writeFileSync(cfgFile, cfgText); await s.ask('gate reload', 2500); await sleep(2000)

console.log('\n# Seeds')
await s.ask('gate reseed 7777', 3500); const one = JSON.stringify((await read()).gates)
await s.ask('gate reseed 8888', 3500); const two = JSON.stringify((await read()).gates)
await s.ask('gate reseed 7777', 3500); const again = JSON.stringify((await read()).gates)
check('a seed repeats exactly', one === again)
check('another seed gives another station', one !== two)
await s.stop(); await sleep(1500); await s.start(); await sleep(8000)
check('the station stays the same after a restart', JSON.stringify((await read()).gates) === again)
check('no Waystation errors', s.errors().length === 0, s.errors()[0]?.slice(0, 160))
await s.stop()
process.exit(summary() ? 0 : 1)
