// A hop between two throwaway servers through fargates, there and back. The bot reconnects the way a
// client does after a transfer (handshake intent 3). What a real client shows on the way is still untested.
import { readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { Server, freshCopy, installJar, smallServer, sleep, bot, walkTo, check, summary, DIR, PORT, BASE, JAR } from './harness.mjs'

const B_PORT = PORT + 1
const g = { x: 30, y: -60, z: 30 }
const setTransfers = (dir, v) => {
  const p = path.join(dir, 'server.properties')
  writeFileSync(p, readFileSync(p, 'utf8').replace(/^accepts-transfers=.*$/m, 'accepts-transfers=' + v))
}
const addDestination = (dir, name, port) => {
  const p = path.join(dir, 'plugins/Waystation/config.yml')
  writeFileSync(p, readFileSync(p, 'utf8') + `\nfargate-destinations:\n  ${name}:\n    label: "${name}"\n    host: 127.0.0.1\n    port: ${port}\n    protocols: [774]\n`)
}
const watch = b => { b.transfers = []; b._client.on('transfer', d => b.transfers.push(d)); return b }
const walkIn = async (server, b) => {
  await server.ask(`execute in waystation run tp ${b.username} ${g.x + .5} ${g.y} ${g.z + 3.5}`, 900)
  await walkTo(b, g.x + .5, g.z + .5, 3000)
  await sleep(1000)
}
const plugin = name => path.join(BASE, 'server/plugins', name)

// Server A: the usual copy, accepting transfers.
freshCopy(); installJar(); setTransfers(DIR, true)
const a = new Server(); await a.start(); await sleep(12000)
addDestination(DIR, 'serverb', B_PORT); await a.ask('gate reload', 1500)
await a.ask(`gate fargate ta serverb waystation ${g.x} ${g.y} ${g.z} south`, 1000)
await a.ask('lp group default permission set multiverse.access.waystation true', 300)

// Server B: a small second server with Waystation, refusing transfers at first.
const bDir = smallServer('serverb', { port: B_PORT, from: path.join(BASE, 'server'), transfers: false,
  plugins: [JAR, plugin('multiverse-core-5.6.2.jar'), plugin('LuckPerms-Bukkit-5.5.0.jar')] })
const b = new Server({ dir: bDir, memory: '2G' }); await b.start(); await sleep(12000)
addDestination(bDir, 'servera', PORT); await b.ask('gate reload', 1500)
await b.ask(`gate fargate tb servera waystation ${g.x} ${g.y} ${g.z} south`, 1000)
await b.ask('lp group default permission set multiverse.access.waystation true', 300)

console.log('\n# Leaving server A')
const h = watch(await bot('hopper'))
await a.ask('lp user hopper permission set waystation.fargate.serverb true', 500)
await a.ask('give hopper diamond 5', 300); await a.ask('xp set hopper 4 levels', 300)
await a.ask('mvtp hopper w:waystation', 2500); await sleep(2000)
await walkIn(a, h)
const out = h.transfers[0]
if (!check('A sends the player to B', h.transfers.length === 1 && out.host === '127.0.0.1' && out.port === B_PORT, JSON.stringify(out))) {
  await a.stop(); await b.stop(); summary(); process.exit(1)
}
h.end(); await sleep(2000)

console.log('\n# Arriving on server B')
let refusal = ''
try { (await bot('hopper', { port: out.port, transferred: true })).end() } catch (e) { refusal = e.message }
check('control: B refuses the transfer while accepts-transfers=false', /disconnected before spawning/.test(refusal), refusal)
await b.stop(); await sleep(1500); setTransfers(bDir, true); await b.start(); await sleep(8000)
const hb = watch(await bot('hopper', { port: out.port, transferred: true }))
await b.ask('lp user hopper permission set waystation.fargate.servera true', 500)
check('B accepts the transfer once it allows them', !!hb.entity)
check('B keeps its own inventory: none of the diamonds came along', !hb.inventory.items().some(i => i.name === 'diamond'))
await b.ask('mvtp hopper w:waystation', 2500); await sleep(2000)
check("B's fargate back is shown to the permitted player", (await b.ask('gate test tb hopper', 700)).join(' ').includes('can enter true'))
await walkIn(b, hb)
const back = hb.transfers[0]
check('B sends the player back to A', hb.transfers.length === 1 && back.port === PORT, JSON.stringify(back))
hb.end(); await sleep(2000)

console.log('\n# Back on server A')
const ha = watch(await bot('hopper', { port: back.port, transferred: true })); await sleep(2500)
const p = ha.entity.position
check('back where they left A, outside the gate', Math.abs(p.x - (g.x + .5)) < 1.5 && p.z > g.z + 1 - .01 && p.z < g.z + 3.6, p.toString())
check('inventory intact on A', ha.inventory.items().filter(i => i.name === 'diamond').reduce((n, i) => n + i.count, 0) === 5)
check('XP intact on A', ha.experience.level === 4, `level ${ha.experience.level}`)
check('no loop: not sent away again on arrival', ha.transfers.length === 0)
check('no Waystation errors on A', a.errors().length === 0, a.errors()[0]?.slice(0, 160))
check('no Waystation errors on B', b.errors().length === 0, b.errors()[0]?.slice(0, 160))
ha.end(); await a.stop(); await b.stop()
summary()
