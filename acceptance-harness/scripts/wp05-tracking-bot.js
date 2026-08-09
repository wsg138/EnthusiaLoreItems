const moduleRoot = '/tmp/wp05-tracking-contract/bot/node_modules'
const mineflayer = require(`${moduleRoot}/mineflayer`)
const fs = require('fs')
const Vec3 = require(`${moduleRoot}/vec3`).Vec3

const root = '/tmp/wp05-tracking-contract'
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = message => {
  const line = `${new Date().toISOString()} ${message}`
  fs.appendFileSync(`${root}/evidence/bot.log`, `${line}\n`)
  console.log(message)
}

async function waitFile(name) {
  for (let i = 0; i < 800; i++) {
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

async function ensureDestinationLoaded(bot, x, y, z, label) {
  if (bot.blockAt(new Vec3(x, y - 1, z))) return
  bot.chat(`/tp Wp05TrackBot ${x} ${y + 80} ${z}`)
  for (let i = 0; i < 160; i++) {
    const position = bot.entity.position
    if (Math.abs(position.x - x) < 1.5 && Math.abs(position.z - z) < 1.5) {
      await bot.waitForChunksToLoad()
      log(`${label} destination-loaded position=${bot.entity.position.x.toFixed(2)},${bot.entity.position.y.toFixed(2)},${bot.entity.position.z.toFixed(2)}`)
      return
    }
    await sleep(100)
  }
  throw new Error(`${label} destination did not load from ordinary player presence`)
}

async function teleport(bot, x, y, z, label) {
  await ensureDestinationLoaded(bot, x, y, z, label)
  bot.chat(`/setblock ${x} ${y - 1} ${z} minecraft:stone`)
  bot.chat(`/setblock ${x} ${y} ${z} minecraft:air`)
  bot.chat(`/setblock ${x} ${y + 1} ${z} minecraft:air`)
  await sleep(250)
  bot.chat(`/tp Wp05TrackBot ${x} ${y} ${z}`)
  await waitPosition(bot, x, y, z, label)
}

async function waitBlockAt(bot, x, y, z, name, label) {
  if (name === 'chest' || name === 'ender_chest' || name === 'hopper') {
    bot.chat(`/setblock ${x} ${y + 1} ${z} minecraft:air`)
    await sleep(250)
  }
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

function nearestDroppedItem(bot) {
  return bot.nearestEntity(entity => entity && (
    entity.name === 'item' || entity.objectType === 'Item' || entity.displayName === 'Item'
  ))
}

async function waitForNaturalPickup(bot) {
  for (let i = 0; i < 120; i++) {
    const items = bot.inventory.items()
    if (items.length === 1) {
      log(`TRACK3 natural-pickup inventory=${items[0].name} slot=${items[0].slot}`)
      return items[0]
    }
    const dropped = nearestDroppedItem(bot)
    if (dropped) {
      const position = dropped.position
      bot.chat(`/tp Wp05TrackBot ${position.x.toFixed(3)} ${position.y.toFixed(3)} ${position.z.toFixed(3)}`)
      await sleep(400)
    } else {
      await sleep(150)
    }
  }
  throw new Error(`TRACK3 natural pickup did not return the dropped item; inventory=${bot.inventory.items().map(item => item.name).join(',')}`)
}

function displayMatches(entity, kind) {
  if (!entity) return false
  const name = String(entity.name || '').toLowerCase()
  const display = String(entity.displayName || '').toLowerCase().replaceAll(' ', '_')
  if (kind === 'item_frame') return name === 'item_frame' || display === 'item_frame'
  if (kind === 'glow_item_frame') return name === 'glow_item_frame' || display === 'glow_item_frame'
  if (kind === 'armor_stand') return name === 'armor_stand' || display === 'armor_stand'
  return false
}

async function waitDisplayEntity(bot, kind, x, y, z, label) {
  for (let i = 0; i < 120; i++) {
    const entity = Object.values(bot.entities).find(candidate =>
      displayMatches(candidate, kind)
      && candidate.position.distanceTo(new Vec3(x, y, z)) < 3)
    if (entity) {
      log(`${label} entity=${kind} id=${entity.id} position=${entity.position.x.toFixed(2)},${entity.position.y.toFixed(2)},${entity.position.z.toFixed(2)}`)
      return entity
    }
    await sleep(100)
  }
  throw new Error(`${label} ${kind} fixture not visible near ${x},${y},${z}`)
}

async function placeTrackedIntoDisplay(bot, kind, x, y, z, label) {
  await teleport(bot, x - 1, y, z, `${label} approach`)
  const item = tracked(bot)
  await bot.equip(item, 'hand')
  await sleep(250)
  const entity = await waitDisplayEntity(bot, kind, x, y, z, label)
  if (kind === 'armor_stand') {
    await bot.activateEntityAt(entity, entity.position.offset(0, 1, 0))
  } else {
    await bot.activateEntity(entity)
  }
  for (let i = 0; i < 80; i++) {
    if (bot.inventory.items().length === 0) {
      log(`${label} natural-player-placement complete entity=${kind}`)
      return
    }
    await sleep(100)
  }
  throw new Error(`${label} real client interaction did not move tracked item into ${kind}; inventory=${bot.inventory.items().map(candidate => candidate.name).join(',')}`)
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
  bot.chat('/fill 27 70 -3 33 70 3 minecraft:stone')
  bot.chat('/fill 27 71 -3 33 74 3 minecraft:air')
  await sleep(500)
  item = tracked(bot)
  await bot.tossStack(item); await sleep(1300); log('TRACK3 normal-drop')
  fs.writeFileSync(`${root}/track3-drop-done`, 'ok\n')
  await waitFile('go-track3-pickup')
  await teleport(bot, 30, 71, 0, 'TRACK3 pickup-area')
  await waitForNaturalPickup(bot)

  // Display fixtures are in a dedicated chunk. Only empty entities/support blocks are created by
  // the shell; the tracked items themselves move via these real client interactions so Paper's
  // ordinary player frame/armor-stand events authoritatively observe each transition. World spawn
  // is managed by the shell and deliberately kept outside this chunk.
  await teleport(bot, 64, 71, 0, 'TRACK3 display-fixture-area')
  log('TRACK3 pickup-confirmed-and-display-fixture-ready')
  fs.writeFileSync(`${root}/track3-pickup-done`, 'ok\n')

  await waitFile('go-track3-frame')
  await placeTrackedIntoDisplay(bot, 'item_frame', 72, 71, 0, 'TRACK3 item-frame')
  fs.writeFileSync(`${root}/track3-frame-done`, 'ok\n')

  await waitFile('go-track3-glowframe')
  await placeTrackedIntoDisplay(bot, 'glow_item_frame', 74, 71, 0, 'TRACK3 glow-item-frame')
  fs.writeFileSync(`${root}/track3-glowframe-done`, 'ok\n')

  await waitFile('go-track3-armorstand')
  await placeTrackedIntoDisplay(bot, 'armor_stand', 76, 71, 0, 'TRACK3 armor-stand')
  fs.writeFileSync(`${root}/track3-armorstand-done`, 'ok\n')

  await waitFile('go-track3-away')
  await teleport(bot, 256, 71, 0, 'TRACK3 unload-area')
  fs.writeFileSync(`${root}/track3-away-done`, 'ok\n')
  await waitFile('go-track3-return')
  await teleport(bot, 64, 71, 0, 'TRACK3 reload-area'); await sleep(1000)
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