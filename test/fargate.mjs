// Fargates. Bots can't follow a real transfer, so this checks what the server sends and what it
// does when the player comes back. Hopping between two servers needs a real client.
import { Server, freshCopy, installJar, sleep, bot, near, walkTo, check, summary, DIR, PORT, CLIENT_PROTOCOL } from './harness.mjs'
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import mineflayer from 'mineflayer'

const props = DIR + '/server.properties'
const setTransfers = v => writeFileSync(props, readFileSync(props, 'utf8').replace(/^accepts-transfers=.*$/m, 'accepts-transfers=' + v))
const s = new Server()
const g = { x: 30, y: -60, z: 30 }
const watch = b => { b.transfers = []; b._client.on('transfer', d => b.transfers.push(d)); return b }
const walkIn = async b => {
  await s.ask(`execute in waycave run tp ${b.username} ${g.x + .5} ${g.y} ${g.z + 3.5}`, 900)
  await walkTo(b, g.x + .5, g.z + .5, 3000)
  await sleep(800)
}
const has = (b, re) => b.chat_log.some(m => re.test(m))

freshCopy(); installJar(); setTransfers(true)
await s.start(); await sleep(12000)
const cfg = DIR + '/plugins/Waycave/config.yml'
writeFileSync(cfg, readFileSync(cfg, 'utf8') + `\nfargate-destinations:\n  testserver:\n    label: "Test Server"\n    host: 127.0.0.1\n    port: 25600\n    check-port: ${PORT}\n    protocols: [${CLIENT_PROTOCOL}]\n`) // the status check asks this server itself, which answers, since nothing listens on 25600
await s.ask('gate reload', 1500)
console.log((await s.ask(`gate fargate tg testserver waycave ${g.x} ${g.y} ${g.z} south`, 1000)).join(' '))
await s.ask('lp group default permission set multiverse.access.waycave true', 300)
await s.ask('lp group default permission set multiverse.access.lobby true', 300)

const yes = watch(await bot('yes')), no = watch(await bot('nope'))
await s.ask('lp user yes permission set waycave.fargate.testserver true', 400)
await s.ask('lp user yes parent add family', 400)
for (const n of ['yes', 'nope']) await s.ask(`mvtp ${n} w:waycave`, 1500)
await sleep(2500)

console.log('\n# Who can see it')
check('permitted, matching client: sees the fargate', near(yes, g.x + .5, g.z + .5, 3).length > 0, `${near(yes, g.x + .5, g.z + .5, 3).length} entities`)
check('no permission: sent no entities for it', near(no, g.x + .5, g.z + .5, 3).length === 0)
const test = (await s.ask('gate test tg yes', 700)).join(' ')
check('it sits on no platform', /platform none/.test(test), test.slice(0, 110))
check('and is on no chamber list', !(await s.ask('gate list', 800)).some(l => /^Chamber.*\btg\b/.test(l)))

console.log('\n# No permission does nothing')
no.chat_log.length = 0
await walkIn(no)
check('walking in sends no transfer', no.transfers.length === 0)
check('and says nothing at all', no.chat_log.length === 0, no.chat_log.join('|').slice(0, 80))
check('and they stay online', !!no.entity && (await s.ask('list', 400)).join(' ').includes('nope'))

console.log('\n# Client version must match')
// An older client can only reach this server through ViaVersion and ViaBackwards, which only 26.2 and later have in the small copy.
if (!readdirSync(DIR + '/plugins').some(f => /^ViaVersion/i.test(f))) console.log('  skipped: no ViaVersion on this server, so an older client cannot join it (run this on 26.2 or 26.3)')
else {
  const old = watch(mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: 'oldclient', version: '1.21.8', auth: 'offline' }))
  old.chat_log = []; old.on('messagestr', m => old.chat_log.push(m)); old.on('error', e => console.log('  [old] ' + e.message))
  await new Promise((res, rej) => { old.once('spawn', res); setTimeout(() => rej(new Error('old client spawn timeout')), 60000) })
  await sleep(1500)
  await s.ask('lp user oldclient permission set waycave.fargate.testserver true', 400)
  await s.ask('mvtp oldclient w:waycave', 2500); await sleep(2000)
  const oldTest = (await s.ask('gate test tg oldclient', 700)).join(' ')
  console.log('  ' + oldTest.slice(-70))
  check('permitted but older client: not offered the gate', /can enter false/.test(oldTest) && near(old, g.x + .5, g.z + .5, 3).length === 0)
  await s.ask(`execute in waycave run tp oldclient ${g.x + .5} ${g.y} ${g.z + 3.5}`, 900)
  old.setControlState('forward', true); await old.lookAt(old.entity.position.offset(0, 1.6, -5), true); await sleep(1500); old.setControlState('forward', false)
  check('and walking in does nothing', old.transfers.length === 0)
  old.end()
}

console.log('\n# Leaving and coming back')
await s.ask('give yes diamond 9', 300); await s.ask('xp set yes 6 levels', 800)
await s.ask('lp user yes permission set multiverse.access.waycave true', 300)
yes.chat_log.length = 0
await walkIn(yes)
await sleep(1000)
check('positive control: the permitted player is sent to the destination', yes.transfers.length === 1 && yes.transfers[0].host === '127.0.0.1' && yes.transfers[0].port === 25600, JSON.stringify(yes.transfers[0]))
check('warned "Leaving this server"', has(yes, /Leaving this server/), yes.chat_log.at(-1))
const spot = readFileSync(DIR + '/plugins/Waycave/players.yml', 'utf8').match(/fargate-return: (.*)/)?.[1]
console.log('  return spot: ' + spot)
yes.end(); await sleep(2500)
const back = watch(await bot('yes')); await sleep(2500)
const p = back.entity.position
check('back on the server: returned where they left, outside the gate', Math.abs(p.x - (g.x + .5)) < 1.5 && p.z > g.z + 1 - .01 && p.z < g.z + 3.6, p.toString())
check('inventory intact', back.inventory.items().filter(i => i.name === 'diamond').reduce((n, i) => n + i.count, 0) === 9)
check('XP intact', back.experience.level === 6, `level ${back.experience.level}`)
check('no loop: not sent away again on arrival', back.transfers.length === 0)
check('the return spot is used once', !/fargate-return/.test(readFileSync(DIR + '/plugins/Waycave/players.yml', 'utf8')))

console.log('\n# Regular waygates alongside')
const list = await s.ask('gate list', 800)
const lobby = list.join('\n').match(/station-lobby: waycave (-?\d+) (-?\d+) (-?\d+) (north|south|east|west)/)
// Three blocks in front of the gate, on the side it faces: the cave's gates don't all face south.
const [fx, fz] = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }[lobby[4]]
await s.ask(`execute in waycave run tp yes ${+lobby[1] + .5 + fx * 3} ${lobby[2]} ${+lobby[3] + .5 + fz * 3}`, 1000)
check('control: she stands in the cave in front of the lobby gate', (await s.ask('execute in waycave if entity @a[name=yes,distance=0..]', 500)).some(l => /Test passed/.test(l)))
await walkTo(back, +lobby[1] + .5, +lobby[3] + .5, 4000); await sleep(2500)
// The gate leads out of the cave: wait until the server no longer has the player in it.
let leftCave = false
for (let i = 0; i < 20 && !leftCave; i++) { leftCave = !(await s.ask('execute in waycave if entity @a[name=yes,distance=0..]', 300)).some(l => /Test passed/.test(l)); if (!leftCave) await sleep(300) }
check('a waygate still works, and sends no transfer', leftCave && back.transfers.length === 0, `gate ${lobby.slice(1).join(' ')}; bot at ${back.entity?.position} in ${back.game?.dimension}; transfers ${back.transfers.length}`)
back.chat_log.length = 0; back.chat('/waygate'); await sleep(800)
check('/waygate gives a waygate, not the fargate', has(back, /Nearest waygate/) && !has(back, /Leaving/), back.chat_log.at(-1))

console.log('\n# A broken destination is refused')
const good = readFileSync(cfg, 'utf8')
const at = good.lastIndexOf(`    protocols: [${CLIENT_PROTOCOL}]\n`) // the last one: the default config has a commented example too
writeFileSync(cfg, good.slice(0, at) + good.slice(at + `    protocols: [${CLIENT_PROTOCOL}]\n`.length))
const refused = (await s.ask('gate reload', 1500)).join(' ')
check('no protocols: config refused', refused.includes('needs protocols'), refused.slice(0, 140))
writeFileSync(cfg, good); await s.ask('gate reload', 1000)

console.log('\n# This server must accept transfers')
check('no Waycave errors', s.errors().length === 0, s.errors()[0]?.slice(0, 150))
await s.stop(); await sleep(1500)
setTransfers(false)
await s.start(); await sleep(10000)
const closed = watch(await bot('yes')); await s.ask('mvtp yes w:waycave', 2500); await sleep(2000)
check('accepts-transfers=false: a warning is logged', s.log.some(l => /Fargates stay hidden/.test(l)))
check('accepts-transfers=false: gate hidden even with permission', near(closed, g.x + .5, g.z + .5, 3).length === 0)
await walkIn(closed)
check('accepts-transfers=false: walking in does nothing', closed.transfers.length === 0)
await s.stop()
process.exit(summary() ? 0 : 1)
