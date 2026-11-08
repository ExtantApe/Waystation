// Loading on Paper 26.x, on a small throwaway server. Bots stop at 26.1, so this is checked from the
// console only: nobody walks through a gate here. Needs WAYSTATION_TEST_PAPER26, a server folder
// holding a Paper 26.x paper.jar with its libraries, and WAYSTATION_TEST_JAVA, a Java it accepts.
import { execSync } from 'node:child_process'
import { rmSync } from 'node:fs'
import path from 'node:path'
import { Server, smallServer, sleep, check, summary, BASE, PORT, JAR } from './harness.mjs'

const from = process.env.WAYSTATION_TEST_PAPER26, java = process.env.WAYSTATION_TEST_JAVA
if (!from || !java) throw new Error('Set WAYSTATION_TEST_PAPER26 and WAYSTATION_TEST_JAVA')
const dir = smallServer('paper26', { port: PORT + 2, from, plugins: [JAR, path.join(BASE, 'server/plugins/multiverse-core-5.6.2.jar')] })
const s = new Server({ dir, java, memory: '2G' })
await s.start(); await sleep(15000)
const version = (await s.ask('version', 1500)).join(' ')
console.log('  ' + version.slice(0, 160))
check('control: Multiverse-Core enabled', s.log.some(l => /Enabling Multiverse-Core/.test(l)) && !s.log.some(l => /Multiverse-Core.*(disabled|Error occurred)/i.test(l)))
check('Waystation enabled', s.log.some(l => /Enabling Waystation/.test(l)) && !s.log.some(l => /Could not load.*Waystation|Error occurred while enabling Waystation/.test(l)))
const worlds = (await s.ask('mv list', 1200)).join(' ')
check("the station's world was made", /waystation/.test(worlds), worlds.slice(0, 160))
const list = (await s.ask('gate list', 1000)).join('\n')
check('the world got its gates', /station-world:/.test(list) && /return-world:/.test(list), list.slice(0, 200))
check('no Waystation errors', s.errors().length === 0, s.errors()[0]?.slice(0, 200))
await s.stop(); await sleep(1500); await s.start(); await sleep(8000)
check('a restart keeps the gates', /station-world:/.test((await s.ask('gate list', 1000)).join('\n')))
check('no Waystation errors after a restart', s.errors().length === 0, s.errors()[0]?.slice(0, 200))
await s.stop()

console.log('\n# Upgrading a 1.21.11 server, which moves every world folder on first boot')
const old = smallServer('upgrade', { port: PORT + 2, from: path.join(BASE, 'server'), plugins: [JAR, path.join(BASE, 'server/plugins/multiverse-core-5.6.2.jar')] })
const u = new Server({ dir: old, memory: '2G' })
await u.start(); await sleep(12000)
await u.ask('mv create extra normal', 15000); await sleep(8000)
const gatesOf = async server => (await server.ask('gate list', 1000)).filter(l => /^(station|return)-\S+: /.test(l)).sort()
const before = await gatesOf(u)
check('control: on 1.21.11 the extra world has its gates', before.some(l => /^station-extra: /.test(l)) && before.some(l => /^return-extra: /.test(l)), before.join(' | ').slice(0, 200))
await u.stop(); await sleep(1500)
for (const f of ['paper.jar', 'libraries', 'versions', 'cache']) {
  rmSync(path.join(old, f), { recursive: true, force: true })
  execSync(`cp -cR "${path.join(from, f)}" "${path.join(old, f)}"`)
}
const up = new Server({ dir: old, java, memory: '2G' })
await up.start(); await sleep(15000)
const after = await gatesOf(up)
const { existsSync, readdirSync } = await import('node:fs')
const moved = existsSync(path.join(old, 'extra')) ? [] : readdirSync(old, { recursive: true }).filter(f => f.endsWith(path.join('minecraft', 'extra')))
check('control: Paper really moved the extra world', !existsSync(path.join(old, 'extra', 'level.dat')) && moved.length > 0, moved.join(', ') || 'still at extra/')
check('after the upgrade every gate is where it was', after.length === before.length && after.every((l, i) => l === before[i]), after.join(' | ').slice(0, 200))
check('and no gate has gone dark', !after.some(l => /dead|closed/i.test(l)))
check('no Waystation errors after the upgrade', up.errors().length === 0, up.errors()[0]?.slice(0, 200))
await up.stop()
summary()
