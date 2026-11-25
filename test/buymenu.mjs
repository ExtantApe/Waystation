// The buying menu (D284): a player clicks the Trails board, clicks a gate, then clicks Yes. Nobody types a command.
//
// Safety rules, each with a positive control next to it:
//   - nothing is charged until the second click, on Yes, and no other square buys,
//   - a gate the player's group can't see is not on the menu, and cannot be bought (even by /buygate, the hidden fallback),
//   - a price in money with no Vault and economy plugin is not on the menu,
//   - a player who can't afford it is told so and charged nothing,
//   - nobody can open another player's board.
// Needs WAYCAVE_TEST_BASE (made by test/prepare-small.mjs, with a Vault jar in WAYCAVE_TEST_JARS/plugins) and the
// Waycave and hook jars. The bots stand still (physics off), so this runs the same on 26.1.2, 26.2 and 26.3, and uses
// no block use, which bots can't do on 26.3. Bots can't see a real client's screen: item names and lore are read from
// the packets, and what the chat says is read from the chat log.
import { copyFileSync, existsSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { Server, freshCopy, installJar, sleep, bot as joinBot, check, summary, DIR, RUN, HOOK_JAR } from './harness.mjs'

const bot = name => joinBot(name, { physics: false })
const s = new Server()
const cfg = DIR + '/plugins/Waycave/config.yml'
const has = (b, re) => b.chat_log.some(m => re.test(m))
const balance = async who => Number((await s.ask(`fakebalance ${who}`, 500)).join(' ').match(/BALANCE \S+ ([\d.]+)/)?.[1] ?? NaN)
const granted = async (who, world) => (await s.ask(`lp user ${who} permission check multiverse.access.${world}`, 700)).join(' ').includes('true')
const tpToBoard = who => s.ask(`execute in waycave run tp ${who} 0.5 64 2.5`, 600)
const boardOf = b => Object.values(b.entities).find(e => e.name === 'interaction')
const nameOf = it => String(it?.customName ? JSON.stringify(it.customName) : it?.displayName ?? '')
const plain = t => String(t).replace(/\\u00a7./g, '').replace(/[{}"\\]|text:|extra:|color:\w+|italic:\w+|bold:\w+/g, ' ')

/** Waits for a window to open on this bot and returns it. */
async function window(b, ms = 4000) {
  if (b.currentWindow) return b.currentWindow
  return await Promise.race([new Promise(r => b.once('windowOpen', r)), sleep(ms).then(() => null)])
}
async function click(b, slot) { await Promise.race([b.clickWindow(slot, 0, 0).catch(() => {}), sleep(1500)]); await sleep(600) }
async function nextWindow(b, ms = 4000) { // after a click that replaces the window
  const old = b.currentWindow
  const end = Date.now() + ms
  while (Date.now() < end) { if (b.currentWindow && b.currentWindow !== old) return b.currentWindow; await sleep(150) }
  return b.currentWindow
}
const slotsOf = w => (w?.slots ?? []).slice(0, w.inventoryStart ?? 0)
const itemsIn = w => slotsOf(w).filter(Boolean)

freshCopy()
writeFileSync(path.join(DIR, 'server.properties'), readFileSync(path.join(DIR, 'server.properties'), 'utf8') + 'allow-flight=true\n')
copyFileSync(HOOK_JAR, DIR + '/plugins/DenyHook.jar')
installJar()
// legacy costs money; creative costs XP levels, so one offer needs Vault and the other doesn't.
let text = readFileSync(cfg, 'utf8')
text = text.replace(/^  legacy: +\{enabled: true, label: "<gold>Legacy"\}/m, '  legacy: {enabled: true, label: "<gold>Legacy", price: {money: 40}}')
text = text.replace(/^  creative: +\{enabled: true, label: "<light_purple>Creative"\}/m, '  creative: {enabled: true, label: "<light_purple>Creative", price: {xp-levels: 3}}')
if (!/price: \{money: 40\}/.test(text) || !/price: \{xp-levels: 3\}/.test(text)) throw new Error('The test config no longer has the legacy and creative lines')
writeFileSync(cfg, text)
await s.start()
await s.waitFor(/The cave is built|failed its walk-through|plan has a flaw/, 240000)
await sleep(8000)
check('control: Vault sees the stand-in economy', s.log.some(l => /FakeEconomy|Vault/i.test(l)) && Number.isFinite(await balance('nobody')))

console.log('\n# Click the board, choose, confirm')
const dora = await bot('dora'), finn = await bot('finn')
await s.ask('lp user finn permission set waycave.platform.Legacy false', 500) // finn's group can't see the Legacy line
await s.ask('mvtp dora w:waycave', 2500); await s.ask('mvtp finn w:waycave', 2500)
await tpToBoard('dora'); await tpToBoard('finn'); await sleep(3500)
const hit = boardOf(dora)
check('the board has a click spot for dora', !!hit)
check('and a stranger\'s click spot is not sent to finn: he has his own, one each', !!boardOf(finn) && boardOf(finn).id !== hit?.id)

dora.chat_log.length = 0
await dora.activateEntity(hit); const list = await window(dora)
check('clicking the board opens the menu', !!list && /Buy a trail/.test(plain(JSON.stringify(list.title))), JSON.stringify(list?.title))
const names = itemsIn(list).map(nameOf).join(' | ')
check('it lists Legacy and Creative, the two gates dora could buy', /Legacy/.test(names) && /Creative/.test(names), names)
const lore = JSON.stringify(slotsOf(list).filter(Boolean).map(i => i.components ?? i.nbt ?? i))
check('each shows its price and what she has', /\$40/.test(lore) && /3 XP levels/.test(lore) && /\$100/.test(lore) && /0 XP levels/.test(lore), lore.slice(0, 300))
check('the chat says the same in words, with a name to click', has(dora, /Legacy.*costs \$40.*You have \$100/) && has(dora, /Creative.*costs 3 XP levels.*You have 0 XP levels/) && has(dora, /\[Choose Legacy\]/), dora.chat_log.join(' | ').slice(0, 300))
check('nothing is charged by opening it', await balance('dora') === 100)

const legacySlot = slotsOf(list).findIndex(i => i && /Legacy/.test(nameOf(i)))
await click(dora, legacySlot); const sure = await nextWindow(dora)
check('one click only asks: a "Buy Legacy?" screen with Yes and No', /Buy Legacy\?/.test(plain(JSON.stringify(sure?.title))) && /Yes, buy Legacy for \$40/.test(JSON.stringify(sure?.slots?.[11] ?? '')) && /No, go back/.test(JSON.stringify(sure?.slots?.[15] ?? '')), JSON.stringify(sure?.title))
check('and still nothing is charged or granted', await balance('dora') === 100 && !(await granted('dora', 'legacy')))
await click(dora, 13); await click(dora, 0); await click(dora, 26)
check('the picture of the gate and the empty squares buy nothing', await balance('dora') === 100 && !(await granted('dora', 'legacy')))
await click(dora, 15); const back = await nextWindow(dora)
check('No goes back to the list, still nothing charged', /Buy a trail/.test(plain(JSON.stringify(back?.title))) && await balance('dora') === 100)
await click(dora, slotsOf(back).findIndex(i => i && /Legacy/.test(nameOf(i)))); await nextWindow(dora)
dora.chat_log.length = 0
await click(dora, 11); await sleep(3500)
check('positive control: the second click, on Yes, buys it', has(dora, /The line to legacy is open for you/), dora.chat_log.slice(-3).join(' | '))
check('charged exactly $40, once', await balance('dora') === 60)
check('and the access was granted', await granted('dora', 'legacy'))

console.log('\n# Cannot afford, and a left click')
dora.chat_log.length = 0
dora.activateEntity(boardOf(dora)); const l2 = await window(dora)
await sleep(300)
// The window is the first one if the earlier one closed; take the list as it is now.
const names2 = itemsIn(dora.currentWindow).map(nameOf).join(' | ')
check('Legacy is no longer for sale to her, and Creative still is', !/Legacy/.test(names2) && /Creative/.test(names2), names2)
await click(dora, slotsOf(dora.currentWindow).findIndex(i => i && /Creative/.test(nameOf(i)))); const short = await nextWindow(dora)
check('with 0 levels the confirm screen says "Not enough yet" and tells her in chat', /Not enough yet/.test(JSON.stringify(short?.slots?.[11] ?? '')) && has(dora, /don't have enough for Creative/), JSON.stringify(short?.slots?.[11]).slice(0, 120))
dora.chat_log.length = 0
await click(dora, 11)
check('clicking it buys nothing, and says so: no levels taken, nothing granted', has(dora, /don't have enough/) && dora.experience.level === 0 && !(await granted('dora', 'creative')))
if (dora.currentWindow) dora.closeWindow(dora.currentWindow)
await sleep(500)
await s.ask('xp set dora 5 levels', 500); await sleep(500)
dora.attack(boardOf(dora)); const l3 = await window(dora)
check('a left click on the board opens the menu too', !!l3 && /Buy a trail/.test(plain(JSON.stringify(l3.title))))
await click(dora, slotsOf(l3).findIndex(i => i && /Creative/.test(nameOf(i)))); await nextWindow(dora)
dora.chat_log.length = 0
await click(dora, 11); await sleep(3500)
check('with 5 levels she buys Creative for 3 levels (XP prices work with no economy)', has(dora, /is open for you/) && dora.experience.level === 2 && await granted('dora', 'creative'), dora.chat_log.slice(-2).join(' | '))

console.log('\n# A gate the group can\'t see can\'t be bought')
finn.chat_log.length = 0
finn.activateEntity(boardOf(finn)); await window(finn)
const fnames = itemsIn(finn.currentWindow).map(nameOf).join(' | ')
check('finn\'s menu has Creative but not Legacy, which dora\'s had (positive control above)', /Creative/.test(fnames) && !/Legacy/.test(fnames), fnames)
check('and the chat never names Legacy to him', !has(finn, /Legacy/), finn.chat_log.join(' | ').slice(0, 200))
if (finn.currentWindow) finn.closeWindow(finn.currentWindow)
await sleep(400)
finn.chat_log.length = 0
finn.chat('/buygate legacy'); await sleep(2000)
check('the hidden /buygate refuses it too, with nothing charged or granted', has(finn, /isn't for sale/) && await balance('finn') === 100 && !(await granted('finn', 'legacy')), finn.chat_log.join(' | '))
// The same gate bought through the fallback by someone who can see it: the fallback still works.
const gus = await bot('gus'); await s.ask('mvtp gus w:waycave', 2500)
gus.chat_log.length = 0; gus.chat('/buygate legacy'); await sleep(3500)
check('positive control: /buygate still works as the fallback for a player who can see the gate', has(gus, /is open for you/) && await granted('gus', 'legacy'), gus.chat_log.join(' | '))

console.log('\n# Nobody opens another player\'s board')
await tpToBoard('finn'); await sleep(1500)
finn.chat_log.length = 0
// finn sends a right click and a left click for dora's click spot, by its id
finn.activateEntity(hit); await sleep(300); finn.attack(hit)
await sleep(1500)
check('a click sent for dora\'s board opens nothing for finn', !finn.currentWindow, finn.currentWindow ? JSON.stringify(finn.currentWindow.title) : '')

console.log('\n# Without a permission to buy')
await s.ask('lp user finn permission set waycave.buy false', 500)
finn.chat_log.length = 0
finn.activateEntity(boardOf(finn)); await sleep(1500)
check('waycave.buy false: clicking the board says so and opens nothing', !finn.currentWindow && has(finn, /can't buy trails/), finn.chat_log.join(' | '))
await s.ask('lp user finn permission unset waycave.buy', 500)
dora.end(); finn.end(); gus.end()
check('no errors from Waycave so far', s.errors().length === 0, s.errors().slice(0, 2).join(' | '))
await s.stop()

console.log('\n# No Vault or economy: a price in money is not offered')
for (const f of readdirSync(path.join(DIR, 'plugins')).filter(f => /^vault.*\.jar$/i.test(f))) rmSync(path.join(DIR, 'plugins', f), { force: true })
rmSync(path.join(DIR, 'plugins/DenyHook.jar'), { force: true }) // it needs Vault
await s.start(); await s.waitFor(/The cave is built|Waycave.*enabled|Enabling Waycave/, 60000).catch(() => {}); await sleep(8000)
const erin = await bot('erin'); await s.ask('xp set erin 0 levels', 300); await s.ask('mvtp erin w:waycave', 2500); await tpToBoard('erin'); await sleep(3500)
erin.chat_log.length = 0
erin.activateEntity(boardOf(erin)); await window(erin)
const enames = itemsIn(erin.currentWindow).map(nameOf).join(' | ')
check('with no economy plugin, Creative (XP) is offered and Legacy (money) is not', /Creative/.test(enames) && !/Legacy/.test(enames), enames)
check('and the chat never names Legacy', !has(erin, /Legacy/), erin.chat_log.join(' | ').slice(0, 200))
if (erin.currentWindow) erin.closeWindow(erin.currentWindow)
await sleep(400)
erin.chat_log.length = 0; erin.chat('/buygate legacy'); await sleep(2500)
check('the hidden /buygate refuses it as well: nothing granted', has(erin, /don't have enough/) && !(await granted('erin', 'legacy')), erin.chat_log.join(' | '))
check('the board itself doesn\'t name a gate that can\'t be bought', !Object.values(erin.entities).filter(e => e.name === 'text_display').some(e => /Legacy[^"]*for sale/.test(JSON.stringify(e.metadata))))
erin.end()
check('no errors from Waycave', s.errors().length === 0, s.errors().slice(0, 2).join(' | '))
await s.stop()
rmSync(RUN, { recursive: true, force: true })
check('clean-up: the run folder this test made is gone', !existsSync(RUN))
summary()
