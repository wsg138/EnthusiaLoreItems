/* Disposable real Mineflayer client. Never use this on an SMP server. */
'use strict'
const fs = require('fs')
const ROOT = '/tmp/acc-track-phase2'
const mineflayer = require('/tmp/acc-track-phase2/bot/node_modules/mineflayer')
const Vec3 = require('/tmp/acc-track-phase2/bot/node_modules/vec3').Vec3
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function log(line) {
  const entry = new Date().toISOString() + ' ' + line
  console.log(entry)
  fs.appendFileSync(ROOT + '/evidence/bot.log', entry + '\n')
}

async function waitFile(name) {
  if (!/^(go-(xminus2|zminus2)(-seam26|-seam27|-move|-return)?|bot\.stop)$/.test(name)) {
    throw new Error('invalid acceptance host checkpoint')
  }
  for (let count = 0; count < 2000; count++) {
    var listed = fs.readdirSync(ROOT)
    if (listed.includes(name)) return
    if (listed.includes('bot.stop')) throw new Error('host aborted ' + name)
    await sleep(100)
  }
  throw new Error('timed out waiting for ' + name)
}

function mark(name) {
  function markX() {
    if (name === 'xminus2-deposited') return fs.promises.writeFile('/tmp/acc-track-phase2/xminus2-deposited', 'ok\n')
    if (name === 'xminus2-seam26') return fs.promises.writeFile('/tmp/acc-track-phase2/xminus2-seam26', 'ok\n')
    if (name === 'xminus2-seam27') return fs.promises.writeFile('/tmp/acc-track-phase2/xminus2-seam27', 'ok\n')
    if (name === 'xminus2-moved') return fs.promises.writeFile('/tmp/acc-track-phase2/xminus2-moved', 'ok\n')
    if (name === 'xminus2-returned') return fs.promises.writeFile('/tmp/acc-track-phase2/xminus2-returned', 'ok\n')
    throw new Error('unlisted acceptance result checkpoint')
  }
  function markZ() {
    if (name === 'zminus2-deposited') return fs.promises.writeFile('/tmp/acc-track-phase2/zminus2-deposited', 'ok\n')
    if (name === 'zminus2-seam26') return fs.promises.writeFile('/tmp/acc-track-phase2/zminus2-seam26', 'ok\n')
    if (name === 'zminus2-seam27') return fs.promises.writeFile('/tmp/acc-track-phase2/zminus2-seam27', 'ok\n')
    if (name === 'zminus2-moved') return fs.promises.writeFile('/tmp/acc-track-phase2/zminus2-moved', 'ok\n')
    if (name === 'zminus2-returned') return fs.promises.writeFile('/tmp/acc-track-phase2/zminus2-returned', 'ok\n')
    throw new Error('unlisted acceptance result checkpoint')
  }
  if (!/^(bot\.ready|(xminus2|zminus2)-(deposited|seam26|seam27|moved|returned))$/.test(name)) {
    throw new Error('invalid acceptance result checkpoint')
  }
  var writing
  if (name === 'bot.ready') {
    writing = fs.promises.writeFile('/tmp/acc-track-phase2/bot.ready', 'ok\n')
  } else if (name.startsWith('xminus2-')) {
    writing = markX()
  } else if (name.startsWith('zminus2-')) {
    writing = markZ()
  } else {
    throw new Error('unlisted acceptance result checkpoint')
  }
  return writing.then(function () { log('MARK ' + name) })
}

async function waitSpawn(bot) {
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('spawn timed out')), 45000)
    bot.once('spawn', () => { clearTimeout(timeout); resolve() })
    bot.once('error', error => { clearTimeout(timeout); reject(error) })
  })
  await bot.waitForChunksToLoad()
}

async function waitBlock(bot, x, z, label) {
  const position = new Vec3(x, 70, z)
  for (let attempt = 0; attempt < 100; attempt++) {
    const block = bot.blockAt(position)
    if (block && block.name === 'chest') return block
    await sleep(100)
  }
  throw new Error(label + ' chest not observable at ' + x + ',' + z)
}

async function teleport(bot, x, z, label) {
  bot.chat('/effect give Wp05Phase2Bot minecraft:slow_falling 30 0 true')
  bot.chat('/tp Wp05Phase2Bot ' + x + ' 151 ' + z)
  await sleep(1400)
  await bot.waitForChunksToLoad()
  bot.chat('/setblock ' + x + ' 70 ' + z + ' minecraft:stone')
  bot.chat('/setblock ' + x + ' 71 ' + z + ' minecraft:air')
  bot.chat('/setblock ' + x + ' 72 ' + z + ' minecraft:air')
  await sleep(450)
  bot.chat('/tp Wp05Phase2Bot ' + x + ' 71 ' + z)
  for (let i = 0; i < 100; i++) {
    const pos = bot.entity.position
    if (Math.abs(pos.x - x) < 1.5 && Math.abs(pos.z - z) < 1.5) {
      await bot.waitForChunksToLoad()
      log(label + ' player x=' + pos.x.toFixed(2) + ' z=' + pos.z.toFixed(2))
      return
    }
    await sleep(100)
  }
  throw new Error(label + ' player never arrived')
}

async function openChest(bot, spec, half, label) {
  const block = await waitBlock(bot, half.x, half.z, label)
  const gui = await bot.openContainer(block)
  const size = Number.isInteger(gui.inventoryStart)
    ? gui.inventoryStart : gui.slots.length - 36
  if (size !== 54) throw new Error(label + ' not a real 54-slot double chest: ' + size)
  log(label + ' open half=' + half.x + ',' + half.z + ' size=' + size)
  return gui
}

async function prepare(bot, spec) {
  await teleport(bot, spec.approach.x, spec.approach.z, spec.name)
  bot.chat('/fill ' + spec.floor + ' minecraft:stone')
  for (const half of [spec.first, spec.second]) {
    bot.chat('/setblock ' + half.x + ' 71 ' + half.z + ' minecraft:air')
  }
  const first = spec.first
  const second = spec.second
  bot.chat('/setblock ' + first.x + ' 70 ' + first.z
    + ' minecraft:chest[facing=' + spec.facing + ',type=left]')
  bot.chat('/setblock ' + second.x + ' 70 ' + second.z
    + ' minecraft:chest[facing=' + spec.facing + ',type=right]')
  await sleep(1200)
}

async function deposit(bot, spec) {
  const items = bot.inventory.items().filter(item => item.name === 'diamond_sword')
  if (items.length !== 1 || items[0].count !== 1) {
    throw new Error(spec.name + ' did not receive exactly one tracked sword')
  }
  const gui = await openChest(bot, spec, spec.first, spec.name + ' deposit')
  if (gui.slots.some((item, index) => index < 54 && item)) {
    throw new Error(spec.name + ' chest not empty before deposit')
  }
  await gui.deposit(items[0].type, null, 1)
  await sleep(900)
  if (gui.slots[0]?.name !== 'diamond_sword') {
    throw new Error(spec.name + ' real GUI deposit not acknowledged in slot0')
  }
  gui.close()
  await sleep(700)
  if (bot.inventory.items().some(item => item.name === 'diamond_sword')) {
    throw new Error(spec.name + ' sword still in player inventory')
  }
  await mark(spec.name + '-deposited')
}

async function clickMove(bot, spec, from, to, resultName) {
  const gui = await openChest(bot, spec, spec.second, spec.name + ' ' + resultName)
  if (gui.slots[from]?.name !== 'diamond_sword' || gui.slots[to]) {
    throw new Error(spec.name + ' wrong starting GUI inventory for ' + resultName)
  }
  await bot.clickWindow(from, 0, 0)
  await sleep(400)
  await bot.clickWindow(to, 0, 0)
  await sleep(1100)
  if (gui.slots[from] || gui.slots[to]?.name !== 'diamond_sword') {
    throw new Error(spec.name + ' GUI move was not acknowledged: ' + from + '->' + to)
  }
  gui.close()
  await sleep(700)
  await mark(spec.name + '-' + resultName)
}

async function runCase(bot, spec) {
  await waitFile('go-' + spec.name)
  await prepare(bot, spec)
  await deposit(bot, spec)
  await waitFile('go-' + spec.name + '-seam26')
  await clickMove(bot, spec, 0, 26, 'seam26')
  await waitFile('go-' + spec.name + '-seam27')
  await clickMove(bot, spec, 26, 27, 'seam27')
  await waitFile('go-' + spec.name + '-move')
  await clickMove(bot, spec, 27, 53, 'moved')
  await waitFile('go-' + spec.name + '-return')
  await clickMove(bot, spec, 53, 0, 'returned')
}

async function main() {
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25565, username: 'Wp05Phase2Bot',
    auth: 'offline', version: '1.21.11'
  })
  bot.on('message', value => log('CHAT ' + value.toString()))
  bot.on('error', error => log('ERROR ' + error.message))
  await waitSpawn(bot)
  await mark('bot.ready')
  await runCase(bot, {
    name: 'xminus2', approach: {x: -18, z: 8},
    floor: '-19 69 6 -14 69 10',
    first: {x: -17, z: 8}, second: {x: -16, z: 8}, facing: 'north'
  })
  await runCase(bot, {
    name: 'zminus2', approach: {x: 7, z: -17},
    floor: '6 69 -19 10 69 -14',
    first: {x: 8, z: -17}, second: {x: 8, z: -16}, facing: 'east'
  })
  await waitFile('bot.stop')
  bot.quit('phase 2 paper acceptance complete')
}

main().catch(error => {
  log('FATAL ' + (error.stack || String(error)))
  fs.writeFileSync(ROOT + '/bot.failed', String(error.stack || error) + '\n')
  process.exitCode = 1
  process.exit()
})
