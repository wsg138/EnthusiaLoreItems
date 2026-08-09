const mineflayer = require('mineflayer')
const fs = require('fs')
const Vec3 = require('vec3').Vec3

const root = '/tmp/wp05-tracking-contract'
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = message => {
  const line = `${new Date().toISOString()} ${message}`
  fs.appendFileSync(`${root}/evidence/bot.log`, `${line}\n`)
  console.log(message)
}

async function waitFile(name) {
  for (let i = 0; i < 400; i++) {
    if (fs.existsSync(`${root}/${name}`)) return
    await sleep(100)
  }
  throw new Error(`missing marker ${name}`)
}

const near = (position, x, y, z) =>
  Math.abs(position.x - x) < 1.5 && Math.abs(position.y - y) < 2 && Math.abs(position.z - z) < 1.5

async function waitPosition(bot, x, y, z, label) {
  for (let i = 0; i < 160; i++) {
    if (near(bot.entity.position, x, y, z)) {
      await bot.waitForChunksToLoad()
      const settled = bot.entity.position
      if (near(settled, x, y, z)) {
        log(`${label} position=${settled.x.toFixed(2)},${settled.y.toFixed(2)},${settled.z.toFixed(2)}`)
        return
      }
    }
    await sleep(100)
  }
  throw new Error(`${label} teleport not stably observed`)
}

async function teleport(bot, x, y, z, label) {
  bot.chat(`/setblock ${x} ${y - 1} ${z} minecraft:stone`)
  await sleep(250)
  bot.chat(`/tp Wp05TrackBot ${x} ${y} ${z}`)
  await waitPosition(bot, x, y, z, label)
}

async function waitBlockAt(bot, x, y, z, name, label) {
  const target = new Vec3(x, y, z)
  for (let i = 0; i < 150; i++) {
    const block = bot.blockAt(target)
    if (block && block.name === name) return block
    await sleep(100)
  }
  const seen = bot.blockAt(target)
  throw new Error(`${label} block not loaded at ${x},${y},${z}; observed=${seen && seen.name}`)
}

function tracked(bot) {
  const items = bot.inventory.items()
  if (items.length !== 1) throw new Error(`expected exactly one tracked inventory item, got ${items.length}`)
  return items[0]
}

async function phaseOne() {
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25565, username: 'Wp05TrackBot', version: '1.21.11', auth: 'offline'
  })
  bot.on('message', message => log(`CHAT ${message.toString()}`))
  bot.on('error', error => log(`ERROR ${error.stack || error}`))
  await new Promise((resolve, reject) => { bot.once('spawn', resolve); bot.once('error', reject) })
  await bot.waitForChunksToLoad()
  log(`SPAWN1 uuid=${bot.player.uuid}`)
  fs.writeFileSync(`${root}/bot.uuid`, `${bot.player.uuid}\n`)

  await waitFile('go-track1')
  let item = tracked(bot)
  await bot.equip(item, 'off-hand'); await sleep(700); log('TRACK1 offhand')
  await bot.unequip('off-hand'); await sleep(700); log('TRACK1 storage-after-offhand')
  item = tracked(bot)
  await bot.equip(item, 'head'); await sleep(700); log('TRACK1 armor-head')
  await bot.unequip('head'); await sleep(700); log('TRACK1 storage-after-armor')
  item = tracked(bot)
  await bot.clickWindow(item.slot, 0, 0); await sleep(700); log('TRACK1 cursor')
  const empty = bot.inventory.firstEmptyInventorySlot()
  if (empty == null) throw new Error('no empty inventory slot')
  await bot.clickWindow(empty, 0, 0); await sleep(700); log('TRACK1 storage-after-cursor')

  await teleport(bot, 0, 71, 0, 'TRACK1 ender-area')
  bot.chat('/setblock 1 70 0 minecraft:ender_chest')
  bot.chat('/setblock 1 71 0 minecraft:air')
  const block = await waitBlockAt(bot, 1, 70, 0, 'ender_chest', 'ender chest')
  const chest = await bot.openContainer(block)
  await sleep(500)
  item = tracked(bot)
  await chest.deposit(item.type, null, 1); await sleep(800); log('TRACK1 ender-deposit')
  await chest.withdraw(item.type, null, 1); await sleep(800); log('TRACK1 ender-withdraw')
  chest.close(); await sleep(500)

  fs.writeFileSync(`${root}/track1-online-done`, 'ok\n')
  bot.quit('offline tracking checkpoint')
}

async function phaseTwo() {
  await waitFile('go-reconnect')
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25565, username: 'Wp05TrackBot', version: '1.21.11', auth: 'offline'
  })
  bot.on('message', message => log(`CHAT2 ${message.toString()}`))
  bot.on('error', error => log(`ERROR2 ${error.stack || error}`))
  await new Promise((resolve, reject) => { bot.once('spawn', resolve); bot.once('error', reject) })
  await bot.waitForChunksToLoad(); await sleep(500)
  log('TRACK1 reconnected')
  fs.writeFileSync(`${root}/track1-reconnected`, 'ok\n')

  await waitFile('go-track2-chest')
  await teleport(bot, 10, 71, 0, 'TRACK2 chest-area')
  bot.chat('/setblock 11 70 0 minecraft:chest')
  bot.chat('/setblock 11 71 0 minecraft:air')
  let item = tracked(bot)
  let block = await waitBlockAt(bot, 11, 70, 0, 'chest', 'chest')
  let container = await bot.openContainer(block)
  await container.deposit(item.type, null, 1); await sleep(900); log('TRACK2 chest-deposit')
  await container.withdraw(item.type, null, 1); await sleep(700); container.close(); log('TRACK2 chest-withdraw')
  fs.writeFileSync(`${root}/track2-chest-done`, 'ok\n')

  await waitFile('go-track2-hopper')
  await teleport(bot, 20, 71, 0, 'TRACK2 hopper-area')
  item = tracked(bot)
  bot.chat('/setblock 20 69 0 minecraft:chest')
  bot.chat('/setblock 20 70 0 minecraft:hopper')
  bot.chat('/setblock 20 71 0 minecraft:air')
  block = await waitBlockAt(bot, 20, 70, 0, 'hopper', 'hopper')
  container = await bot.openContainer(block)
  await container.deposit(item.type, null, 1); await sleep(2500); container.close(); log('TRACK2 hopper-transfer')
  fs.writeFileSync(`${root}/track2-hopper-done`, 'ok\n')

  await waitFile('go-track2-shulker')
  await teleport(bot, 13, 71, 0, 'TRACK2 shulker-area')
  block = await waitBlockAt(bot, 14, 70, 0, 'chest', 'nested shulker chest')
  container = await bot.openContainer(block); await sleep(700); container.close(); await sleep(700)
  log('TRACK2 shulker-access')
  fs.writeFileSync(`${root}/track2-shulker-done`, 'ok\n')

  await waitFile('go-track2-bundle')
  await teleport(bot, 15, 71, 0, 'TRACK2 bundle-area')
  block = await waitBlockAt(bot, 16, 70, 0, 'chest', 'nested bundle chest')
  container = await bot.openContainer(block); await sleep(700); container.close(); await sleep(700)
  log('TRACK2 bundle-access')
  fs.writeFileSync(`${root}/track2-bundle-done`, 'ok\n')

  await waitFile('go-track2-away')
  await teleport(bot, 256, 71, 0, 'TRACK2 unload-area')
  fs.writeFileSync(`${root}/track2-away-done`, 'ok\n')
  await waitFile('go-track2-return')
  await teleport(bot, 15, 71, 0, 'TRACK2 reload-area')
  for (const [x, label] of [[14, 'shulker'], [16, 'bundle']]) {
    block = await waitBlockAt(bot, x, 70, 0, 'chest', `reloaded ${label} chest`)
    container = await bot.openContainer(block); await sleep(500); container.close(); await sleep(500)
  }
  log('TRACK2 reloaded-and-reopened')
  fs.writeFileSync(`${root}/track2-return-done`, 'ok\n')

  await waitFile('go-track3-drop')
  await teleport(bot, 30, 71, 0, 'TRACK3 drop-area')
  item = tracked(bot)
  await bot.tossStack(item); await sleep(1300); log('TRACK3 normal-drop')
  fs.writeFileSync(`${root}/track3-drop-done`, 'ok\n')
  await waitFile('go-track3-pickup')
  await teleport(bot, 32, 71, 0, 'TRACK3 pickup-area'); await sleep(1500)
  log('TRACK3 pickup-wait')
  fs.writeFileSync(`${root}/track3-pickup-done`, 'ok\n')

  await waitFile('go-track3-away')
  await teleport(bot, 256, 71, 0, 'TRACK3 unload-area')
  fs.writeFileSync(`${root}/track3-away-done`, 'ok\n')
  await waitFile('go-track3-return')
  await teleport(bot, 10, 71, 0, 'TRACK3 reload-area'); await sleep(1000)
  log('TRACK3 reloaded')
  fs.writeFileSync(`${root}/track3-return-done`, 'ok\n')

  await waitFile('bot.stop')
  bot.quit('tracking acceptance complete')
}

;(async () => {
  try {
    await phaseOne()
    await phaseTwo()
  } catch (error) {
    log(`FATAL ${error.stack || error}`)
    fs.writeFileSync(`${root}/bot.failed`, String(error.stack || error))
    process.exit(1)
  }
})()
