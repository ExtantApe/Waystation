// Makes a small, made-up stand-in for the prepared server copy, so the main suites can run without copying a real
// server: fresh Paper, the same plugins, and flat, nearly empty worlds with the world and group names the suites use.
// It never reads a real server. The result goes where prepare-copy.sh would put a copy: <base>/server.
//
// Usage: node test/prepare-small.mjs <base folder under a temp folder> <version: 26.1.2|26.2|26.3>
// Needs WAYSTATION_TEST_JARS (paper-<version>.jar and plugins/ with multiverse-core, multiverse-inventories, LuckPerms,
// GriefPrevention, bluemap and, on 26.2+, ViaVersion and ViaBackwards), WAYSTATION_TEST_EULA=accept and
// WAYSTATION_TEST_JAVA. The suites then run with WAYSTATION_TEST_BASE=<base>.
//
// What it can't stand in for: real terrain (trees, water, cliffs and builds near spawn), real players' saved spots and
// inventories, and a big world's load. docs/test-audit.md lists the cases that still need the real copy.
import { spawn } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const [BASE, V] = process.argv.slice(2)
if (!BASE || !['26.1.2', '26.2', '26.3'].includes(V)) throw new Error('Usage: node test/prepare-small.mjs <base folder> <26.1.2|26.2|26.3>')
const base = path.resolve(BASE)
if (![os.tmpdir(), '/tmp', '/private/tmp', '/var/folders'].map(p => path.resolve(p)).some(t => base.startsWith(t + path.sep)))
  throw new Error('The base folder must be inside a temp folder, not ' + base)
const JARS = process.env.WAYSTATION_TEST_JARS, JAVA = process.env.WAYSTATION_TEST_JAVA ?? 'java'
if (process.env.WAYSTATION_TEST_EULA !== 'accept') throw new Error('Set WAYSTATION_TEST_EULA=accept to accept the Minecraft EULA for this throwaway server')
const PORT = Number(process.env.WAYSTATION_TEST_PORT ?? 25599)
const jar = start => {
  const dir = path.join(JARS, 'plugins'), hit = readdirSync(dir).find(f => f.toLowerCase().startsWith(start.toLowerCase()) && f.endsWith('.jar'))
  if (!hit) throw new Error(`No ${start}*.jar in ${dir}`)
  return path.join(dir, hit)
}
const sleep = ms => new Promise(r => setTimeout(r, ms))

// The worlds the suites name, with their environment. The main world is the lobby.
const WORLDS = {
  oneblock: 'normal', meadow: 'normal', meadow_nether: 'nether', meadow_the_end: 'the_end', survival: 'normal',
  survival_the_end: 'the_end', creative: 'normal', flatlands: 'normal', legacy: 'normal', kids_survival: 'normal',
  kids_flatlands: 'normal', friends_survival: 'normal', friends_flatlands: 'normal', quiet_survival: 'normal',
  quiet_flatlands: 'normal', TARDIS_TimeVortex: 'normal',
}
// Multiverse-Inventories groups, matching the platforms in the suites' test settings.
const GROUPS = {
  Lobby: ['lobby'], Survival: ['meadow', 'meadow_nether', 'meadow_the_end', 'survival', 'survival_the_end'],
  Creative: ['creative', 'flatlands'], Legacy: ['legacy'], Kids_Survival: ['kids_survival'], Kids_Creative: ['kids_flatlands'],
  Friends_Survival: ['friends_survival'], Friends_Creative: ['friends_flatlands'], Quiet_Survival: ['quiet_survival'],
  Quiet_Creative: ['quiet_flatlands'], OneBlock: ['oneblock'],
}
const SHARES = ['inventory', 'armor', 'off_hand', 'ender_chest', 'hunger', 'exp', 'health', 'potions']

const dir = path.join(base, 'server')
rmSync(path.join(base, 'server'), { recursive: true, force: true }); rmSync(path.join(base, 'run'), { recursive: true, force: true })
mkdirSync(path.join(dir, 'plugins'), { recursive: true })
copyFileSync(path.join(JARS, `paper-${V}.jar`), path.join(dir, 'paper.jar'))
// GriefPrevention 16.18.8 is built for 26.3's API only, so older servers get 16.18.7.
const gp = V === '26.3' ? 'GriefPrevention-16.18.8' : 'GriefPrevention-16.18.7'
const plugins = ['multiverse-core', 'multiverse-inventories', 'LuckPerms', gp, 'bluemap', ...(V.startsWith('26.1') ? [] : ['ViaVersion', 'ViaBackwards'])]
for (const p of plugins) copyFileSync(jar(p), path.join(dir, 'plugins', path.basename(jar(p))))
writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n')
writeFileSync(path.join(dir, 'server.properties'), [
  'server-ip=127.0.0.1', `server-port=${PORT}`, 'online-mode=false', 'accepts-transfers=true', 'level-name=lobby',
  'level-type=minecraft\\:flat', 'generate-structures=false', 'spawn-protection=0', 'difficulty=normal', 'spawn-monsters=false',
  'white-list=false', 'enforce-whitelist=false', 'enable-query=false', 'enable-rcon=false', 'view-distance=6', 'simulation-distance=6',
  'max-players=40', 'require-resource-pack=false'].join('\n') + '\n')
mkdirSync(path.join(dir, 'plugins/LuckPerms'), { recursive: true })
writeFileSync(path.join(dir, 'plugins/LuckPerms/config.yml'), 'server: global\nstorage-method: yaml\nwatch-files: false\n')

// One boot to make the worlds and LuckPerms' defaults, then stop.
const log = []
const proc = spawn(JAVA, ['-Xms2G', '-Xmx2G', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'paper.jar', 'nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] })
process.on('exit', () => { if (proc.exitCode === null) proc.kill('SIGKILL') })
const add = d => { for (const l of d.toString().split('\n')) if (l.trim()) log.push(l) }
proc.stdout.on('data', add); proc.stderr.on('data', add)
const exited = new Promise(r => proc.on('exit', r))
const waitFor = async (re, ms) => { const end = Date.now() + ms; while (Date.now() < end) { if (log.some(l => re.test(l))) return; if (proc.exitCode !== null) throw new Error('Server exited:\n' + log.slice(-5).join('\n')); await sleep(200) } throw new Error('Timed out waiting for ' + re) }
await waitFor(/Done \(\d/, 300000)
for (const [w, env] of Object.entries(WORLDS)) {
  const from = log.length
  proc.stdin.write(`mv create ${w} ${env}${env === 'normal' ? ' --world-type flat' : ''} --no-structures\n`)
  const end = Date.now() + 60000
  while (Date.now() < end && !log.slice(from).some(l => /Created world|already exists|Complete!|loaded|World .* created/i.test(l))) await sleep(300)
  await sleep(800)
}
// Access as the suites expect it: everyone reaches the lobby and the station, each LuckPerms group its own worlds,
// nobody owns legacy (it's for sale) and quiet has no members. Everyone may /mvtp themselves until the gates-only
// set-up takes it away; admins may send others.
const ACCESS = {
  default: ['lobby', 'waystation'],
  family: ['meadow', 'meadow_nether', 'meadow_the_end', 'survival', 'survival_the_end', 'creative', 'flatlands', 'oneblock', 'TARDIS_TimeVortex'],
  friends: ['friends_survival', 'friends_flatlands'], kids: ['kids_survival', 'kids_flatlands'], quiet: ['quiet_survival', 'quiet_flatlands'],
}
const lp = [...Object.keys(ACCESS).filter(g => g !== 'default').map(g => `lp creategroup ${g}`), 'lp creategroup admin',
  ...Object.entries(ACCESS).flatMap(([g, ws]) => ws.map(w => `lp group ${g} permission set multiverse.access.${w} true`)),
  'lp group default permission set multiverse.teleport.self.* true', 'lp group admin permission set multiverse.teleport.* true',
  // Admins outrank the gates-only group the suites add (weight 100), as on a real server.
  'lp group admin setweight 200',
  // The creative worlds are in creative mode, which decides where a gate lands you.
  ...['creative', 'flatlands', 'kids_flatlands', 'friends_flatlands', 'quiet_flatlands'].map(w => `mv modify ${w} set gamemode creative`),
  // Flat worlds spawn slimes, which the real worlds don't: no mobs anywhere, so nothing kills a bot mid-test.
  ...['lobby', ...Object.keys(WORLDS)].flatMap(w => [`execute in ${w} run gamerule spawn_mobs false`, `execute in ${w} run gamerule doMobSpawning false`])]
for (const c of lp) { proc.stdin.write(c + '\n'); await sleep(150) }
await sleep(2500)
proc.stdin.write('stop\n')
await Promise.race([exited, sleep(90000).then(() => proc.kill('SIGKILL'))])

const made = Object.keys(WORLDS).filter(w => existsSync(path.join(dir, 'lobby/dimensions/minecraft', w)))
const missing = Object.keys(WORLDS).filter(w => !made.includes(w))
if (missing.length) throw new Error('These worlds weren\'t made: ' + missing.join(', ') + '\n' + log.slice(-10).join('\n'))

// Multiverse-Inventories groups, the station's world in the Lobby group as the install steps say.
GROUPS.Lobby.push('waystation')
mkdirSync(path.join(dir, 'plugins/Multiverse-Inventories'), { recursive: true })
writeFileSync(path.join(dir, 'plugins/Multiverse-Inventories/groups.yml'), 'groups:\n' + Object.entries(GROUPS).map(([g, ws]) =>
  `  ${g}:\n    worlds:\n${ws.map(w => `    - ${w}\n`).join('')}    shares:\n${SHARES.map(s => `    - ${s}\n`).join('')}`).join(''))
// GriefPrevention and BlueMap as prepare-copy.sh sets them on a real copy.
mkdirSync(path.join(dir, 'plugins/GriefPreventionData'), { recursive: true })
writeFileSync(path.join(dir, 'plugins/GriefPreventionData/config.yml'), 'GriefPrevention:\n  MaxPlayersPerIpAddress: 1000\n  Spam:\n    LoginCooldownSeconds: 0\n  Claims:\n    Mode:\n      flatlands: Disabled\n')
const bm = path.join(dir, 'plugins/BlueMap')
if (existsSync(path.join(bm, 'core.conf'))) {
  const core = path.join(bm, 'core.conf')
  writeFileSync(core, readFileSync(core, 'utf8').replace(/^accept-download: false/m, 'accept-download: true').replace(/^metrics: true/m, 'metrics: false').replace(/^render-thread-count: .*/m, 'render-thread-count: 1'))
  const web = path.join(bm, 'webserver.conf')
  writeFileSync(web, readFileSync(web, 'utf8').replace(/^port: .*/m, 'port: 8199') + '\nip: "127.0.0.1"\n')
  mkdirSync(path.join(bm, 'maps'), { recursive: true })
  const conf = new URL('../setup/bluemap-waystation.conf', import.meta.url).pathname
  writeFileSync(path.join(bm, 'maps/waystation.conf'), readFileSync(conf, 'utf8').replace(/^world: "world"/m, 'world: "lobby"'))
}
// The set-up files name their admin group admin-family; here it's admin. run.mjs swaps names from this file.
writeFileSync(path.join(base, 'names.json'), JSON.stringify({ 'admin-family': 'admin' }) + '\n')
console.log(`Prepared a small stand-in in ${dir}: ${made.length} worlds plus the lobby, ${Object.keys(GROUPS).length} inventory groups.`)
console.log(`Run a suite with WAYSTATION_TEST_BASE=${base} WAYSTATION_TEST_NAMES=${path.join(base, 'names.json')} node test/run.mjs <suite>`)
