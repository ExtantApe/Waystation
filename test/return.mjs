// The return command, and Waystation on a one-world server without Multiverse-Core. Bots on one fresh, empty Paper
// server on 127.0.0.1:25617, the version in WAYSTATION_RETURN_SOURCE (26.1.2, 26.2 or 26.3; default 26.1.2).
//
// One server: the return command sends players back to the same server, and a fake status responder on
// 127.0.0.1:25628 stands in for the destination's server list ping, so it can be made to look up or down.
// WAYSTATION_TWO_SERVERS=1 runs a real destination as well, on 127.0.0.1:25618: 26.3, or 26.2 when the source is 26.3.
// That proves a hop between versions, but runs two servers at once.
//
// The bots are 26.1 clients: on 26.2 and 26.3 they come in through ViaVersion and ViaBackwards. A bot can't follow a
// transfer by itself, so it records the transfer packet and reconnects the way a client does after one (intent 3).
// Ported from ReturnGate's acceptance suite, which this replaces.
//
// WAYSTATION_TEST_JARS=<folder> WAYSTATION_TEST_EULA=accept WAYSTATION_TEST_JAVA=<java 25> node test/return.mjs
import { JAR, freshServer, writeConfig, setProperty, Server, FakeStatus, bot, settle, sleep, said, saidCount, check, summary, BASE, CLIENT } from './return-harness.mjs'
import minecraftData from 'minecraft-data'
import { copyFileSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const SRC_PORT = 25617, DEST_PORT = 25618, FAKE_PORT = 25628
const TWO = process.env.WAYSTATION_TWO_SERVERS === '1'
const TRANSFER_PORT = TWO ? DEST_PORT : SRC_PORT
const SRC_V = process.env.WAYSTATION_RETURN_SOURCE ?? '26.1.2', DEST_V = SRC_V === '26.3' ? '26.2' : '26.3'
if (!['26.1.2', '26.2', '26.3'].includes(SRC_V)) throw new Error('WAYSTATION_RETURN_SOURCE must be 26.1.2, 26.2 or 26.3')
const PROTOCOL = minecraftData(CLIENT).version.version
// On 26.3, behind ViaVersion, a bot that moves is disconnected, so there the bots stand still and a piston moves them.
const physicsOn = port => (port === SRC_PORT ? SRC_V : DEST_V) !== '26.3'
const PHYS = physicsOn(SRC_PORT)
/** Pushes the bot one block east with a piston: the server moves the player, not the client. */
const pistonPush = b => {
  const p = b.entity.position.floored()
  src.send(`setblock ${p.x - 1} ${p.y} ${p.z} minecraft:piston[facing=east]`)
  src.send(`setblock ${p.x - 2} ${p.y} ${p.z} minecraft:redstone_block`)
}
const LABEL = '<green>Test Destination</green>'
/** Waystation's config with a destination and the return command turned on. */
const config = ({ host = '127.0.0.1', port = TRANSFER_PORT, command = 'returngate', aliases = [], destination = 'home', enabled = true, protocols = [PROTOCOL] } = {}) => [
  ['return:\n  enabled: false\n  destination: ""        # a name from fargate-destinations\n  command: return ',
    `return:\n  enabled: ${enabled}\n  destination: "${destination}"\n  command: ${command} `],
  ['  aliases: []\n  permission: waystation.return', `  aliases: [${aliases.join(', ')}]\n  permission: waystation.return`],
  ['  check-port: 0\n', `  check-port: ${!TWO && host === '127.0.0.1' && port === TRANSFER_PORT ? FAKE_PORT : 0}\n`],
  ['# fargate-destinations:\n', `fargate-destinations:\n  home:\n    label: "${LABEL}"\n    host: "${host}"\n    port: ${port}\n    protocols: [${protocols.join(', ')}]\n# fargate-destinations:\n`],
]
const STARTED = /Going to Test Destination in 5 seconds/
const OFFLINE = /Test Destination isn't answering right now/
const transferredTo = (b, port) => b.transfers.length === 1 && b.transfers[0].host === '127.0.0.1' && b.transfers[0].port === port
const T = { countdown: 5000, check: 3000 }
const AFTER = T.countdown + T.check + 1500 // long enough for any countdown started now to have finished

console.log(`Test servers in ${BASE}: Waystation on ${SRC_V} without Multiverse, destination ${TWO ? DEST_V : 'the same server, with a fake status check'}, `
  + `bots ${CLIENT} (protocol ${PROTOCOL}), physics ${PHYS ? 'on' : 'off'}`)
let x = 0 // each bot gets its own spot, far enough from the others that nobody pushes anybody
/** A player at their own spot. allowed: given the return command's permission through LuckPerms. */
async function player(name, { allowed = true, admin = false, port = SRC_PORT, server = src } = {}) {
  const b = await bot(name, { port, physics: physicsOn(port) })
  if (allowed) await server.ask(`lp user ${name} permission set waystation.return true`, 600)
  if (admin) await server.ask(`lp user ${name} permission set waystation.admin true`, 600)
  x += 12
  server.send(`tp ${name} ${x}.5 ${Math.floor(b.entity.position.y)} 0.5 0 0`)
  await sleep(1200)
  await settle(b)
  b.chat_log.length = 0
  return b
}
async function start(b, cmd = '/returngate') {
  b.chat(cmd)
  const end = Date.now() + 3000
  while (Date.now() < end && !said(b, STARTED)) await sleep(50)
  return said(b, STARTED)
}
async function waitFor(fn, ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (fn()) return true; await sleep(50) }
  return fn()
}
const blockPos = b => b.entity.position.floored()

// --- Waystation with no Multiverse-Core -----------------------------------------------------------------------
const srcDir = freshServer('source', { version: SRC_V, port: SRC_PORT })
const destDir = TWO ? freshServer('destination', { version: DEST_V, port: DEST_PORT }) : null
const startDest = () => (TWO ? new Server(destDir, DEST_V, DEST_PORT) : new FakeStatus(FAKE_PORT)).start()
writeConfig(srcDir, config())
copyFileSync(JAR, path.join(srcDir, 'plugins', path.basename(JAR)))
let src = await new Server(srcDir, SRC_V, SRC_PORT).start()
let dest = await startDest()
await sleep(3000)
check('Waystation loads on Paper ' + SRC_V + ' without Multiverse-Core', src.log.some(l => /Waystation.*Enabling Waystation|\[Waystation\] Enabling/.test(l)) && src.errors().length === 0, src.errors()[0]?.slice(0, 160))
check('it says plainly that there is no station without Multiverse', src.log.some(l => /Multiverse-Core isn't installed, so there's no station/.test(l)))
check('and makes no station world', !(await src.ask('gate list', 800)).some(l => /station-/.test(l)))

// Permission, and which commands players see
{
  const b = await player('noperm', { allowed: false })
  await sleep(1000)
  check("a player without waystation.return doesn't see the command", !b.commands.has('returngate'), [...b.commands].filter(c => /gate|return|station/.test(c)).join(', '))
  check('there is no /station without a station to go to', !b.commands.has('station') && !b.commands.has('hub'))
  b.chat('/returngate')
  await sleep(AFTER)
  check('a player without the permission is refused', !said(b, STARTED) && b.transfers.length === 0, b.chat_log.join(' | '))
  b.quit()
}

// Success, with head turns along the way
{
  const b = await player('turner')
  check('a permitted player sees the command', b.commands.has('returngate'))
  const ok = await start(b)
  const turning = PHYS ? setInterval(() => b.look(Math.random() * Math.PI * 2, (Math.random() - 0.5) * 1.5, true), 150) : null
  await waitFor(() => b.transfers.length > 0, AFTER)
  clearInterval(turning)
  check('counts down and sends the transfer packet to the destination', ok && transferredTo(b, TRANSFER_PORT), JSON.stringify(b.transfers))
  if (PHYS) check("repeated head turns don't cancel it", !said(b, /Cancelled/))
  check('the countdown shows in the action bar', b.bar_log.some(m => /Going to/.test(m) && /Test Destination/.test(m) && /\b3\b/.test(m)), b.bar_log.slice(-3).join(' | '))
  check("says where it's sending you", said(b, /Sending you to Test Destination/))
  const ticks = b.sounds.length
  check('plays a sound each second and when it sends you', ticks >= 6, `${ticks} sounds`)
  b.quit(); await sleep(500)
  let arrived = false, err = ''
  try { const h = await bot('turner', { port: TRANSFER_PORT, transferred: true, physics: physicsOn(TRANSFER_PORT) }); arrived = !!h.entity; h.quit() } catch (e) { err = e.message }
  check(TWO ? `the bot follows the transfer into the ${DEST_V} server` : 'the bot follows the transfer (back into the same server here)', arrived, err)
}

// Moving
{
  const b = await player('walker')
  await start(b)
  await sleep(1200)
  if (PHYS) { b.setControlState('forward', true); await sleep(600); b.setControlState('forward', false) } else pistonPush(b)
  await sleep(AFTER)
  check(PHYS ? 'walking cancels it' : 'being moved a block by a piston cancels it', said(b, /Cancelled: you moved/) && b.transfers.length === 0, b.chat_log.join(' | '))
  check('the cancel message says how to try again, with the configured command', said(b, /Stand still and run \/returngate again/))
  b.quit()
}
if (PHYS) {
  const b = await player('jumper')
  await start(b)
  await sleep(1000)
  b.setControlState('jump', true); await sleep(300); b.setControlState('jump', false)
  await sleep(AFTER)
  check('jumping cancels it', said(b, /Cancelled: you moved/) && b.transfers.length === 0, b.chat_log.join(' | '))
  b.quit()
}

// Repeating the command
{
  const b = await player('repeater')
  b.chat('/returngate'); await sleep(300); b.chat('/returngate'); await sleep(1500); b.chat('/returngate')
  await sleep(AFTER)
  check('running it again gives one countdown and one transfer',
    saidCount(b, STARTED) === 1 && saidCount(b, /already on your way/) === 2 && b.transfers.length === 1, `${saidCount(b, STARTED)} started, ${b.transfers.length} transfers`)
  b.quit()
}

// Damage
async function damageCase(name, label, act, re = /Cancelled: you took damage/) {
  const b = await player(name)
  await start(b)
  await sleep(800)
  await act(b)
  await sleep(AFTER)
  check(label, re.test(b.chat_log.join('\n')) && b.transfers.length === 0, b.chat_log.join(' | '))
  return b
}
{
  const b = await damageCase('mobbed', 'a mob hitting you cancels it', async b => {
    const p = b.entity.position
    src.send(`summon minecraft:zombie ${p.x + 1.2} ${p.y} ${p.z}`)
  })
  src.send('kill @e[type=minecraft:zombie]'); b.quit()
}
{
  const victim = await player('victim')
  const attacker = await player('attacker', { allowed: false })
  src.send(`tp attacker ${victim.entity.position.x + 2} ${victim.entity.position.y} ${victim.entity.position.z}`)
  await sleep(1000); await settle(victim)
  victim.chat_log.length = 0
  await start(victim)
  await sleep(800)
  const target = attacker.nearestEntity(e => e.username === 'victim')
  await attacker.lookAt(target.position.offset(0, 1.5, 0), true)
  attacker.attack(target)
  await sleep(AFTER)
  check('another player hitting you cancels it', said(victim, /Cancelled: you took damage/) && victim.transfers.length === 0, victim.chat_log.join(' | '))
  victim.quit(); attacker.quit()
}
;(await damageCase('faller', 'fall damage cancels it (applied with /damage: a real fall needs moving, which cancels first)',
  async () => src.send('damage faller 2 minecraft:fall'))).quit()
{
  const b = await damageCase('burner', 'standing in fire cancels it', async b => {
    const p = blockPos(b)
    src.send(`setblock ${p.x} ${p.y} ${p.z} minecraft:fire`)
  })
  const p = blockPos(b); src.send(`fill ${p.x - 2} ${p.y} ${p.z - 2} ${p.x + 2} ${p.y + 1} ${p.z + 2} minecraft:air replace minecraft:fire`); b.quit()
}
;(await damageCase('poisoned', 'a harmful potion effect (poison) cancels it',
  async () => src.send('effect give poisoned minecraft:poison 10 1'), /Cancelled: you (were hit with a harmful effect|took damage)/)).quit()
;(await damageCase('harmed', 'an instant damage potion cancels it',
  async () => src.send('effect give harmed minecraft:instant_damage 1 0'), /Cancelled: you (were hit with a harmful effect|took damage)/)).quit()
{
  const b = await player('sped')
  await start(b)
  await sleep(800)
  src.send('effect give sped minecraft:speed 30 1')
  await waitFor(() => b.transfers.length > 0, AFTER)
  check("a helpful effect (speed) doesn't cancel it", transferredTo(b, TRANSFER_PORT) && !said(b, /Cancelled/), b.chat_log.join(' | '))
  b.quit()
}

// Teleported, killed, logging out
{
  const b = await player('ported')
  await start(b); await sleep(800)
  src.send(`tp ported ${x + 100} ${Math.floor(b.entity.position.y)} 50`)
  await sleep(AFTER)
  check('being teleported cancels it', said(b, /Cancelled: you were teleported/) && b.transfers.length === 0, b.chat_log.join(' | '))
  b.quit()
}
{
  const b = await player('killed')
  await start(b); await sleep(800)
  src.send('kill killed')
  await sleep(AFTER)
  check('being killed cancels it, with no later transfer', said(b, /Cancelled/) && b.transfers.length === 0, b.chat_log.join(' | '))
  b.quit()
}
{
  const b = await player('leaver')
  await start(b); await sleep(1000)
  b.quit()
  await sleep(AFTER)
  const back = await bot('leaver', { port: SRC_PORT, physics: PHYS })
  await sleep(AFTER)
  check('logging out cancels it: no transfer on rejoining', back.transfers.length === 0 && !src.log.some(l => /Sending leaver/.test(l)))
  check('and nothing is left stuck: a new countdown starts', await start(back), back.chat_log.join(' | '))
  back.quit()
}

// Riding
{
  const b = await player('rider')
  src.send(`summon minecraft:minecart ${b.entity.position.x} ${b.entity.position.y} ${b.entity.position.z}`)
  await sleep(800)
  src.send('ride rider mount @e[type=minecraft:minecart,limit=1,sort=nearest]')
  await sleep(1000)
  b.chat('/returngate'); await sleep(AFTER)
  check('riding something: told to hop off, nothing starts', said(b, /Hop off your ride/) && !said(b, STARTED) && b.transfers.length === 0, b.chat_log.join(' | '))
  src.send('kill @e[type=minecraft:minecart]'); b.quit()
}

// Console
{
  const out = await src.ask('returngate')
  check("the console is told it's for players only", out.some(l => /Only players can use this/.test(l)), out.join(' | '))
}

// Destination down mid-countdown
{
  const b = await player('midway')
  await start(b)
  await sleep(1500) // the first check has passed by now
  await dest.kill9()
  await sleep(AFTER)
  check('the destination going down mid-countdown: says so, no transfer', said(b, OFFLINE) && b.transfers.length === 0, b.chat_log.join(' | '))
  b.quit()
}

// Destination offline, with the player's state compared before and after
const snapshot = b => JSON.stringify({
  pos: b.entity.position.toArray().map(n => n.toFixed(3)),
  health: b.health, food: b.food,
  items: b.inventory.items().map(i => `${i.name}x${i.count}@${i.slot}`).sort(),
  effects: Object.keys(b.entity.effects).sort(),
})
{
  const b = await player('keeper')
  src.send('give keeper minecraft:diamond 5'); src.send('give keeper minecraft:oak_log 12')
  src.send('effect give keeper minecraft:speed 600 0'); src.send('damage keeper 3 minecraft:generic')
  await sleep(2500); await settle(b)
  b.chat_log.length = 0
  const before = snapshot(b)
  const ok = await start(b)
  const fast = await waitFor(() => said(b, OFFLINE), 4000)
  await sleep(AFTER)
  check('destination offline: says so, no transfer', ok && fast && b.transfers.length === 0, b.chat_log.join(' | '))
  check('it says so once', saidCount(b, OFFLINE) === 1)
  check('inventory, position, health and effects are unchanged after a failed attempt', snapshot(b) === before, `${before} -> ${snapshot(b)}`)
  b.quit()
}

// Wrong port, wrong host, a wrong client version, and no destination at all
async function restartSource(replacements) {
  await src.stop()
  writeConfig(srcDir, replacements)
  src = await new Server(srcDir, SRC_V, SRC_PORT).start()
}
dest = await startDest()
await restartSource(config({ port: 25619 }))
{
  const b = await player('wrongport')
  await start(b); await sleep(AFTER)
  check('a wrong port: says so, no transfer', said(b, OFFLINE) && b.transfers.length === 0, b.chat_log.join(' | '))
  b.quit()
}
await restartSource(config({ host: 'no-such-host.invalid' }))
{
  const b = await player('wronghost')
  await start(b); await sleep(AFTER)
  check('a wrong host: says so, no transfer', said(b, OFFLINE) && b.transfers.length === 0, b.chat_log.join(' | '))
  b.quit()
}
await restartSource(config({ protocols: [1] }))
{
  const b = await player('oldgame')
  b.chat('/returngate'); await sleep(2500)
  check("a client version the destination doesn't take: told so, nothing starts", said(b, /Your game version can't join Test Destination/) && !said(b, STARTED) && b.transfers.length === 0, b.chat_log.join(' | '))
  b.quit()
}
await restartSource(config({ destination: 'nowhere' }))
{
  check('a destination that isn\'t defined is logged at startup', src.log.some(l => /\[Waystation\] The return command isn't ready: return\.destination "nowhere" isn't under fargate-destinations/.test(l)))
  const admin = await player('admin', { admin: true })
  admin.chat('/returngate'); await sleep(2000)
  check('an admin is told exactly what to fix', said(admin, /The return command isn't set up: return\.destination "nowhere" isn't under fargate-destinations\. Fix it in plugins\/Waystation\/config\.yml/) && !said(admin, STARTED), admin.chat_log.join(' | '))
  admin.quit()
  const plain = await player('plain')
  plain.chat('/returngate'); await sleep(2000)
  check('a player is told it isn\'t set up, without the details', said(plain, /This command isn't set up yet\. Let an admin know/) && !said(plain, /fargate-destinations/), plain.chat_log.join(' | '))
  plain.quit()
}

// Other names
await restartSource(config({ command: 'hop', aliases: ['hopback'] }))
{
  const b = await player('renamed')
  await sleep(1000)
  check('the command name and aliases come from config', b.commands.has('hop') && b.commands.has('hopback') && !b.commands.has('returngate'), [...b.commands].filter(c => /hop|return/.test(c)).join(', '))
  const ok = await start(b, '/hopback')
  await waitFor(() => b.transfers.length > 0, AFTER)
  check('an alias works', ok && transferredTo(b, TRANSFER_PORT))
  b.quit()
}

// An older config.yml, without the return messages, still gets readable text
{
  const file = path.join(srcDir, 'plugins/Waystation/config.yml')
  writeFileSync(file, readFileSync(file, 'utf8').replace(/^  return-[a-z-]+: .*\n/gm, '').replace(/^  escape-[a-z-]+: .*\n/gm, ''))
  await src.stop(); src = await new Server(srcDir, SRC_V, SRC_PORT).start()
  const b = await player('oldconfig')
  await start(b, '/hop')
  await waitFor(() => b.transfers.length > 0, AFTER)
  check('a config.yml from before the return command still shows the shipped messages', said(b, STARTED) && !said(b, /^return-/), b.chat_log.join(' | '))
  b.quit()
}
await restartSource(config())

// A fargate still works on a one-world server
if (PHYS) {
  await src.ask(`gate fargate tg home world 40 -60 40 south`, 1200)
  await src.ask('lp group default permission set waystation.fargate.home true', 600)
  const b = await player('gatewalker', { allowed: false })
  src.send('tp gatewalker 40.5 -60 43.5 180 0'); await sleep(1500); await settle(b)
  b.setControlState('forward', true)
  await waitFor(() => b.transfers.length > 0, 4000)
  b.setControlState('forward', false)
  check('a fargate placed with /gate fargate sends a walking player to the destination', transferredTo(b, TRANSFER_PORT), JSON.stringify(b.transfers))
  b.quit()
}

// Reload and restart during a countdown
{
  const b = await player('reloader')
  await start(b); await sleep(1000)
  const out = await src.ask('bukkit:reload confirm', 8000)
  const reloaded = out.some(l => /Reload complete/i.test(l))
  await sleep(AFTER)
  if (reloaded) {
    check('a plugin reload during a countdown cancels it and says so', said(b, /Cancelled: this server is restarting/) && b.transfers.length === 0, b.chat_log.join(' | '))
    b.quit(); await sleep(1000)
    const again = await bot('reloader', { port: SRC_PORT, physics: PHYS })
    check('after the reload a new countdown starts', await start(again), again.chat_log.join(' | '))
    await waitFor(() => again.transfers.length > 0, AFTER)
    check('and transfers once', again.transfers.length === 1)
    again.quit()
  } else {
    console.log(`NOTE  Bukkit's plugin reload isn't available on ${SRC_V}: ${out.slice(0, 2).join(' | ')}`)
  }
  b.quit()
}
{
  const b = await player('datapacks')
  await start(b); await sleep(1000)
  await src.ask('minecraft:reload', 4000)
  await waitFor(() => b.transfers.length > 0, AFTER)
  check('a data pack /reload during a countdown leaves it running: one transfer', b.transfers.length === 1 && !said(b, /Cancelled/), b.chat_log.join(' | '))
  b.quit()
}
{
  const b = await player('restarter')
  await start(b); await sleep(1000)
  await src.stop()
  await b.ended
  check('a server stop during a countdown sends no transfer', b.transfers.length === 0, b.kick ?? '')
  src = await new Server(srcDir, SRC_V, SRC_PORT).start()
  const back = await bot('restarter', { port: SRC_PORT, physics: PHYS })
  await sleep(AFTER)
  check('after a restart: no transfer on rejoining', back.transfers.length === 0)
  check('after a restart: a new countdown starts', await start(back))
  back.quit()
}

// Control: a destination with accepts-transfers=false refuses. Waystation can't see that setting from here.
if (TWO) {
  await dest.stop()
  setProperty(destDir, 'accepts-transfers', 'false')
  dest = await new Server(destDir, DEST_V, DEST_PORT).start()
  let refused = ''
  try { (await bot('refused', { port: DEST_PORT, transferred: true, physics: physicsOn(DEST_PORT) })).quit() } catch (e) { refused = e.message }
  check('control: a destination with accepts-transfers=false refuses a transferred player', /disconnected before spawning/.test(refused), refused)
}

const errs = [...src.errors(), ...dest.errors()]
check('no Waystation errors in either log', errs.length === 0, errs.slice(0, 3).join(' | '))
await src.stop(); await dest.stop()
summary()
process.exit()
