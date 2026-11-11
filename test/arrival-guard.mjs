// The arrival guard (owner, 2026-11-10, D42), and fargates checking their destination answers first (K366). Two fresh
// Paper servers on 127.0.0.1, one at a time where it matters: A (25617) sends players through a fargate and the return
// command with a signed pass; B (25618) has the arrival guard on and lets in only transfers carrying a valid one.
// Both share a made-up key in plugins/Waystation/transfer.key. Nothing here is a copy of a real server.
//
// The bots are 26.1 clients and keep transfer cookies across connections, as a real client does. A bot can't follow
// a transfer by itself, so it records the transfer packet and reconnects with intent 3.
//
// ./gradlew shadowJar, then:
// WAYSTATION_TEST_JARS=<folder> WAYSTATION_TEST_EULA=accept WAYSTATION_TEST_JAVA=<java 25> node test/arrival-guard.mjs
import { freshServer, writeConfig, Server, bot, sleep, said, check, summary, BASE, JAR } from './return-harness.mjs'
import { copyFileSync, writeFileSync, chmodSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import minecraftData from 'minecraft-data'
import path from 'node:path'

const V = '26.1.2', A_PORT = 25617, B_PORT = 25618
const PROTOCOL = minecraftData('26.1').version.version
const KEY = randomBytes(32)
const PASS = 'waystation:pass'
const g = { x: 20, y: -60, z: 0 }

function setUp(dir, replacements) {
  writeConfig(dir, replacements)
  copyFileSync(JAR, path.join(dir, 'plugins', path.basename(JAR)))
  const key = path.join(dir, 'plugins/Waystation/transfer.key')
  writeFileSync(key, KEY)
  chmodSync(key, 0o600)
}
const destination = `fargate-destinations:\n  second:\n    label: "Test Second"\n    host: "127.0.0.1"\n    port: ${B_PORT}\n    protocols: [${PROTOCOL}]\n    pass: true\n# fargate-destinations:\n`

const aDir = freshServer('guard-a', { version: V, port: A_PORT })
setUp(aDir, [['# fargate-destinations:\n', destination]])
const bDir = freshServer('guard-b', { version: V, port: B_PORT })
setUp(bDir, [['arrival-guard:\n  enabled: false\n  # This server\'s name, the same as its entry under the other server\'s fargate-destinations.\n  name: ""',
  'arrival-guard:\n  enabled: true\n  # This server\'s name, the same as its entry under the other server\'s fargate-destinations.\n  name: "second"']])

const a = await new Server(aDir, V, A_PORT).start()
let b = await new Server(bDir, V, B_PORT).start()
await sleep(2000)
check('B says the arrival guard is on', b.log.some(l => /Arrival guard on: only players coming through a fargate to "second"/.test(l)))
await a.ask(`gate fargate fm second world ${g.x} ${g.y} ${g.z} south`, 1200)
await a.ask('lp group default permission set waystation.fargate.second true', 600)

/** Tries to join B. Resolves to the bot if it spawned, or null if it was refused. */
async function join(name, opts) {
  try { return await bot(name, { port: B_PORT, ...opts }) } catch { return null }
}
const refusedFor = (name, re) => b.log.some(l => new RegExp(`Refused ${name} at arrival: ${re.source}`).test(l))

console.log('\n# Nobody gets onto B except through the fargate')
check('a direct join is refused', !(await join('direct', { cookies: new Map() })) && refusedFor('direct', /a direct join/))
check('a transfer with no pass is refused', !(await join('nopass', { transferred: true, cookies: new Map() })) && refusedFor('nopass', /no pass|pass malformed/))

console.log('\n# Through the fargate: A pings B, gives a pass, and B lets them in')
const jar = new Map()
const w = await bot('walker', { port: A_PORT, cookies: jar })
await a.ask(`tp walker ${g.x + .5} ${g.y} ${g.z + 3.5} 180 0`, 1200)
w.setControlState('forward', true); await sleep(1600); w.setControlState('forward', false); await sleep(2500)
check('A sends them to B', w.transfers.length === 1 && w.transfers[0].port === B_PORT, JSON.stringify(w.transfers))
check('with a pass stored on the client', jar.has(PASS) && jar.get(PASS).length > 40, String(jar.get(PASS)?.length))
const usedPass = jar.get(PASS) && Buffer.from(jar.get(PASS))
w.quit(); await sleep(1500)
const inB = await join('walker', { transferred: true, cookies: jar })
check('B lets them in with it', !!inB && !refusedFor('walker', /./))
check('and B overwrites the pass on the client, so it is spent there too', jar.get(PASS)?.length === 0, String(jar.get(PASS)?.length))
inB?.quit(); await sleep(1500)

console.log('\n# A pass works once, for one player')
check('the same pass again is refused', !(await join('walker', { transferred: true, cookies: new Map([[PASS, usedPass]]) })) && refusedFor('walker', /pass already used/))
const fresh = new Map()
const w2 = await bot('walker', { port: A_PORT, cookies: fresh })
await a.ask(`tp walker ${g.x + .5} ${g.y} ${g.z + 3.5} 180 0`, 1200)
w2.setControlState('forward', true); await sleep(1600); w2.setControlState('forward', false); await sleep(2500)
w2.quit(); await sleep(1500)
check("someone else's pass is refused", !(await join('borrower', { transferred: true, cookies: fresh })) && refusedFor('borrower', /pass wrong player/))

console.log('\n# Put next to a gate by a teleport, a player must step clear before it fires')
const s1 = await bot('stepper', { port: A_PORT, cookies: new Map() })
// Half a block in front of the gate's opening: what Multiverse-Inventories' last location did on a real server.
await a.ask(`tp stepper ${g.x + .5} ${g.y} ${g.z + 1.2} 180 0`, 1500)
s1.setControlState('forward', true); await sleep(900); s1.setControlState('forward', false); await sleep(2500)
check('walking straight in from where a teleport put them does nothing', s1.transfers.length === 0, JSON.stringify(s1.transfers))
s1.setControlState('back', true); await sleep(1200); s1.setControlState('back', false); await sleep(800)
s1.setControlState('forward', true); await sleep(1600); s1.setControlState('forward', false); await sleep(2500)
check('control: once they have stepped clear, the gate works', s1.transfers.length >= 1, JSON.stringify(s1.transfers))
s1.quit(); await sleep(1000)

console.log('\n# K366: a fargate whose destination is down sends nobody')
await b.stop(); await sleep(1500)
const w3 = await bot('walker', { port: A_PORT, cookies: new Map() })
await a.ask(`tp walker ${g.x + .5} ${g.y} ${g.z + 3.5} 180 0`, 1200)
w3.setControlState('forward', true); await sleep(1600); w3.setControlState('forward', false); await sleep(3500)
check('with B down, nobody is transferred', w3.transfers.length === 0, JSON.stringify(w3.transfers))
check('and they are told, kindly', said(w3, /Test Second isn't answering right now/), w3.chat_log.join(' | '))
w3.quit()

check('no Waystation errors on A', a.errors().length === 0, a.errors()[0]?.slice(0, 200))
check('no Waystation errors on B', b.errors().length === 0, b.errors()[0]?.slice(0, 200))
await a.stop()
summary()
console.log('servers were in ' + BASE)
