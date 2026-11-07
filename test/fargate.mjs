// Fargates. Bots can't follow a real transfer, so this checks what the server sends and what it
// does when the player comes back. Hopping between two servers needs a real client.
import { Server, freshCopy, installJar, sleep, bot, near, walkTo, check, summary, DIR, PORT } from './harness.mjs'
import { readFileSync, writeFileSync } from 'node:fs'
import mineflayer from 'mineflayer'

const props = DIR + '/server.properties'
const setTransfers = v => writeFileSync(props, readFileSync(props, 'utf8').replace(/^accepts-transfers=.*$/m, 'accepts-transfers=' + v))
const s = new Server()
const g = { x: 30, y: -60, z: 30 }
const watch = b => { b.transfers = []; b._client.on('transfer', d => b.transfers.push(d)); return b }
const walkIn = async b => {
  await s.ask(`execute in waystation run tp ${b.username} ${g.x + .5} ${g.y} ${g.z + 3.5}`, 900)
  await walkTo(b, g.x + .5, g.z + .5, 3000)
  await sleep(800)
}
const has = (b, re) => b.chat_log.some(m => re.test(m))

freshCopy(); installJar(); setTransfers(true)
await s.start(); await sleep(12000)
const cfg = DIR + '/plugins/Waystation/config.yml'
writeFileSync(cfg, readFileSync(cfg, 'utf8') + '\nfargate-destinations:\n  testserver:\n    label: "Test Server"\n    host: 127.0.0.1\n    port: 25600\n    protocols: [774]\n')
await s.ask('gate reload', 1500)
console.log((await s.ask(`gate fargate tg testserver waystation ${g.x} ${g.y} ${g.z} south`, 1000)).join(' '))
await s.ask('lp group default permission set multiverse.access.waystation true', 300)
await s.ask('lp group default permission set multiverse.access.lobby true', 300)

const yes = watch(await bot('yes')), no = watch(await bot('nope'))
await s.ask('lp user yes permission set waystation.fargate.testserver true', 400)
await s.ask('lp user yes parent add family', 400)
for (const n of ['yes', 'nope']) await s.ask(`mvtp ${n} w:waystation`, 1500)
await sleep(2500)

console.log('\n# Who can see it')
check('permitted, matching client: sees the fargate', near(yes, g.x + .5, g.z + .5, 3).length > 0, `${near(yes, g.x + .5, g.z + .5, 3).length} entities`)
check('no permission: sent no entities for it', near(no, g.x + .5, g.z + .5, 3).length === 0)
const test = (await s.ask('gate test tg yes', 700)).join(' ')
check('it sits on no platform', /platform none/.test(test), test.slice(0, 110))
check('and is not on the platform list', !(await s.ask('gate list', 800)).some(l => /^Platform.*\btg\b/.test(l)))

console.log('\n# No permission does nothing')
no.chat_log.length = 0
await walkIn(no)
check('walking in sends no transfer', no.transfers.length === 0)
check('and says nothing at all', no.chat_log.length === 0, no.chat_log.join('|').slice(0, 80))
check('and they stay online', !!no.entity && (await s.ask('list', 400)).join(' ').includes('nope'))

console.log('\n# Client version must match')
const old = watch(mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: 'oldclient', version: '1.21.8', auth: 'offline' }))
old.chat_log = []; old.on('messagestr', m => old.chat_log.push(m)); old.on('error', e => console.log('  [old] ' + e.message))
await new Promise((res, rej) => { old.once('spawn', res); setTimeout(() => rej(new Error('old client spawn timeout')), 60000) })
await sleep(1500)
await s.ask('lp user oldclient permission set waystation.fargate.testserver true', 400)
await s.ask('mvtp oldclient w:waystation', 2500); await sleep(2000)
const oldTest = (await s.ask('gate test tg oldclient', 700)).join(' ')
console.log('  ' + oldTest.slice(-70))
check('permitted but older client: not offered the gate', /can enter false/.test(oldTest) && near(old, g.x + .5, g.z + .5, 3).length === 0)
await s.ask(`execute in waystation run tp oldclient ${g.x + .5} ${g.y} ${g.z + 3.5}`, 900)
old.setControlState('forward', true); await old.lookAt(old.entity.position.offset(0, 1.6, -5), true); await sleep(1500); old.setControlState('forward', false)
check('and walking in does nothing', old.transfers.length === 0)
old.end()

console.log('\n# Leaving and coming back')
await s.ask('give yes diamond 9', 300); await s.ask('xp set yes 6 levels', 800)
await s.ask('lp user yes permission set multiverse.access.waystation true', 300)
yes.chat_log.length = 0
await walkIn(yes)
await sleep(1000)
check('positive control: the permitted player is sent to the destination', yes.transfers.length === 1 && yes.transfers[0].host === '127.0.0.1' && yes.transfers[0].port === 25600, JSON.stringify(yes.transfers[0]))
check('warned "Leaving this server"', has(yes, /Leaving this server/), yes.chat_log.at(-1))
const spot = readFileSync(DIR + '/plugins/Waystation/players.yml', 'utf8').match(/fargate-return: (.*)/)?.[1]
console.log('  return spot: ' + spot)
yes.end(); await sleep(2500)
const back = watch(await bot('yes')); await sleep(2500)
const p = back.entity.position
check('back on the server: returned where they left, outside the gate', Math.abs(p.x - (g.x + .5)) < 1.5 && p.z > g.z + 1 - .01 && p.z < g.z + 3.6, p.toString())
check('inventory intact', back.inventory.items().filter(i => i.name === 'diamond').reduce((n, i) => n + i.count, 0) === 9)
check('XP intact', back.experience.level === 6, `level ${back.experience.level}`)
check('no loop: not sent away again on arrival', back.transfers.length === 0)
check('the return spot is used once', !/fargate-return/.test(readFileSync(DIR + '/plugins/Waystation/players.yml', 'utf8')))

console.log('\n# Regular waygates alongside')
const list = await s.ask('gate list', 800)
const lobby = list.join('\n').match(/station-lobby: waystation (-?\d+) (-?\d+) (-?\d+)/)
await s.ask(`execute in waystation run tp yes ${+lobby[1] + .5} ${lobby[2]} ${+lobby[3] + 3.5}`, 1000)
await walkTo(back, +lobby[1] + .5, +lobby[3] + .5, 4000); await sleep(2500)
check('a waygate still works, and sends no transfer', (await s.ask('execute in lobby if entity @a[name=yes,distance=0..]', 500)).some(l => /Test passed/.test(l)) && back.transfers.length === 0)
back.chat_log.length = 0; back.chat('/waygate'); await sleep(800)
check('/waygate gives a waygate, not the fargate', has(back, /Nearest waygate/) && !has(back, /Leaving/), back.chat_log.at(-1))

console.log('\n# A broken destination is refused')
const good = readFileSync(cfg, 'utf8')
const at = good.lastIndexOf('    protocols: [774]\n') // the last one: the default config has a commented example too
writeFileSync(cfg, good.slice(0, at) + good.slice(at + '    protocols: [774]\n'.length))
const refused = (await s.ask('gate reload', 1500)).join(' ')
check('no protocols: config refused', refused.includes('needs protocols'), refused.slice(0, 140))
writeFileSync(cfg, good); await s.ask('gate reload', 1000)

console.log('\n# This server must accept transfers')
check('no Waystation errors', s.errors().length === 0, s.errors()[0]?.slice(0, 150))
await s.stop(); await sleep(1500)
setTransfers(false)
await s.start(); await sleep(10000)
const closed = watch(await bot('yes')); await s.ask('mvtp yes w:waystation', 2500); await sleep(2000)
check('accepts-transfers=false: a warning is logged', s.log.some(l => /Fargates stay hidden/.test(l)))
check('accepts-transfers=false: gate hidden even with permission', near(closed, g.x + .5, g.z + .5, 3).length === 0)
await walkIn(closed)
check('accepts-transfers=false: walking in does nothing', closed.transfers.length === 0)
await s.stop()
process.exit(summary() ? 0 : 1)
