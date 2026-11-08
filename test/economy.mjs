// Buying a gate with a real economy plugin behind Vault, not the test stand-in. Needs
// WAYSTATION_TEST_ECONOMY_JAR: an economy plugin jar such as EssentialsX.
import { copyFileSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { Server, freshCopy, installJar, sleep, bot, check, summary, DIR } from './harness.mjs'

const eco = process.env.WAYSTATION_TEST_ECONOMY_JAR
if (!eco) throw new Error('Set WAYSTATION_TEST_ECONOMY_JAR')
const s = new Server()
const cfg = DIR + '/plugins/Waystation/config.yml'
const has = (b, re) => b.chat_log.some(m => re.test(m))
const balance = async name => { const out = (await s.ask(`balance ${name}`, 800)).join(' '); return Number(out.match(/\$([\d,]+(?:\.\d+)?)/)?.[1].replace(/,/g, '') ?? NaN) }

freshCopy(); copyFileSync(eco, path.join(DIR, 'plugins', path.basename(eco))); installJar()
writeFileSync(cfg, readFileSync(cfg, 'utf8').replace(/^  legacy: +\{enabled: true, label: "<gold>Legacy"\}/m, '  legacy: {enabled: true, label: "<gold>Legacy", price: {money: 40}}'))
await s.start(); await sleep(12000)
await s.ask('lp group default permission set multiverse.access.waystation true', 300)
check('control: Vault sees the real economy', s.log.some(l => /Essentials/.test(l) && /Vault|economy/i.test(l)) || (await s.ask('vault-info', 800)).join(' ').includes('Essentials'))

const buyer = await bot('buyer'); await s.ask('mvtp buyer w:waystation', 2500)
await s.ask('eco set buyer 30', 500)
check('control: the economy reads the balance it was given', await balance('buyer') === 30)
buyer.chat('/buygate legacy'); await sleep(2000)
check("can't afford: refused, nothing charged", has(buyer, /don't have enough/) && await balance('buyer') === 30, buyer.chat_log.at(-1))
await s.ask('eco set buyer 100', 500); buyer.chat_log.length = 0
buyer.chat('/buygate legacy'); await sleep(3500)
check('positive control: buying works', has(buyer, /is open for you/), buyer.chat_log.at(-1))
check('charged exactly 40', await balance('buyer') === 60)
check('and access was granted', (await s.ask('lp user buyer permission check multiverse.access.legacy', 800)).join(' ').includes('true'))

console.log('\n# A grant that fails is refunded')
writeFileSync(cfg, readFileSync(cfg, 'utf8').replace('    - "lp user <player> permission set multiverse.access.<world> true"', '    - "say no grant for <player>"'))
await s.ask('gate reload', 1500)
const buyer2 = await bot('buyer2'); await s.ask('mvtp buyer2 w:waystation', 2500)
await s.ask('eco set buyer2 100', 500)
buyer2.chat('/buygate legacy'); await sleep(4500)
check('grant fails: told and refunded in full', has(buyer2, /paid back/) && await balance('buyer2') === 100, buyer2.chat_log.at(-1))
check('no Waystation errors', s.errors().length === 0, s.errors()[0]?.slice(0, 160))
await s.stop()
summary()
