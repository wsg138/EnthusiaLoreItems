const moduleRoot = '/tmp/wp05-tracking-contract/bot/node_modules'
const mineflayer = require(`${moduleRoot}/mineflayer`)
const fs = require('fs')
const Vec3 = require(`${moduleRoot}/vec3`).Vec3

const root = '/tmp/wp05-tracking-contract'
const user = process.env.BOT_USER || 'Wp05RestrictBot'
const policyMode = process.env.POLICY_MODE || 'restricted'
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = message => {
  const line = `${new Date().toISOString()} ${user} ${message}`
  fs.appendFileSync(`${root}/evidence/restricted-bot.log`, `${line}\n`)
  console.log(message)
}

async function waitFile(name) {
  for (let i = 0; i < 400; i++) {
    if (fs.existsSync(`${root}/${name}`)) return
    await sleep(100)
  }
  throw new Error(`missing marker ${name}`)
}

async function waitItem(bot, name, label) {
  for (let i = 0; i < 160; i++) {
    const item = bot.inventory.items().find(candidate => candidate.name === name)
    if (item) {
      log(`${label} item=${item.name} slot=${item.slot}`)
      return item
    }
    await sleep(100)
  }
  throw new Error(`${label} never appeared in player inventory; items=${bot.inventory.items().map(item => item.name).join(',')}`)
}

async function waitBlock(bot, x, y, z, name) {
  const position = new Vec3(x, y, z)
  for (let i = 0; i < 120; i++) {
    const block = bot.blockAt(position)
    if (block && block.name === name) return block
    await sleep(100)
  }
  throw new Error(`expected ${name} at ${x},${y},${z}`)
}

function windowSlot(bot, name) {
  const window = bot.currentWindow
  if (!window) throw new Error('expected an open container window')
  const slot = window.slots.findIndex(item => item && item.name === name)
  if (slot < 0) throw new Error(`could not find ${name} in open window`)
  return slot
}

function emptyPlayerWindowSlot(bot) {
  const window = bot.currentWindow
  if (!window) throw new Error('expected an open container window')
  const start = Number.isInteger(window.inventoryStart) ? window.inventoryStart : Math.max(0, window.slots.length - 36)
  const end = Number.isInteger(window.inventoryEnd) ? window.inventoryEnd : window.slots.length
  for (let slot = start; slot < end; slot++) {
    if (!window.slots[slot]) return slot
  }
  throw new Error(`no empty player inventory slot is visible in the open window start=${start} end=${end}`)
}

async function refreshShulkerFixture(bot, x, y, z) {
  bot.chat(`/setblock ${x} ${y} ${z} minecraft:air`)
  await sleep(350)
  bot.chat(`/setblock ${x} ${y} ${z} minecraft:shulker_box`)
  await sleep(700)
  await bot.waitForChunksToLoad()
  return waitBlock(bot, x, y, z, 'shulker_box')
}

async function openContainerWithRetry(bot, x, y, z) {
  let lastError
  for (let attempt = 1; attempt <= 3; attempt++) {
    const block = attempt === 1
      ? await waitBlock(bot, x, y, z, 'shulker_box')
      : await refreshShulkerFixture(bot, x, y, z)
    try {
      const container = await bot.openContainer(block)
      log(`shulker open attempt ${attempt} succeeded`)
      return container
    } catch (error) {
      lastError = error
      log(`shulker open attempt ${attempt} failed: ${error.stack || error}`)
      if (bot.currentWindow) {
        try { bot.closeWindow(bot.currentWindow) } catch (_) {}
      }
      await sleep(750)
      await bot.waitForChunksToLoad()
    }
  }
  throw lastError || new Error('shulker window did not open')
}

async function verifyShulkerPolicy(bot) {
  bot.chat(`/tp ${user} 0 71 0`)
  // Paper can acknowledge a teleport before the destination chunk has completed loading.
  // Wait for the client to receive the destination before asking the server to place the fixture.
  await sleep(500)
  await bot.waitForChunksToLoad()
  bot.chat('/setblock 1 70 0 minecraft:shulker_box')
  await sleep(500)
  const container = await openContainerWithRetry(bot, 1, 70, 0)
  await sleep(400)

  const trackedSlot = windowSlot(bot, 'diamond_sword')
  await bot.clickWindow(trackedSlot, 0, 0)
  await sleep(250)
  await bot.clickWindow(0, 0, 0)
  await sleep(800)

  const inserted = bot.currentWindow && bot.currentWindow.slots[0]
  if (policyMode === 'allowed') {
    if (!inserted || inserted.name !== 'diamond_sword') {
      throw new Error('allowed shared-container policy rejected the tracked shulker insertion')
    }
    await bot.clickWindow(0, 0, 0)
    await sleep(250)
    const destination = emptyPlayerWindowSlot(bot)
    await bot.clickWindow(destination, 0, 0)
    await sleep(600)
    container.close()
    await sleep(700)
    await waitItem(bot, 'diamond_sword', 'allowed shulker returned tracked item')
    log('TRACK2 allowed shulker insertion accepted by live server')
    return
  }

  if (inserted && inserted.name === 'diamond_sword') {
    throw new Error('restricted shulker accepted the tracked item')
  }
  container.close()
  await sleep(700)
  await waitItem(bot, 'diamond_sword', 'restricted shulker retained tracked item')
  log('TRACK2 restricted shulker insertion rejected by live server')
}

async function verifyBundleRestriction(bot) {
  bot.chat(`/give ${user} minecraft:bundle 1`)
  await waitItem(bot, 'bundle', 'bundle fixture')
  const trackedSlot = bot.inventory.slots.findIndex(item => item && item.name === 'diamond_sword')
  const bundleSlot = bot.inventory.slots.findIndex(item => item && item.name === 'bundle')
  if (trackedSlot < 0 || bundleSlot < 0) {
    throw new Error(`missing restricted bundle fixture slots sword=${trackedSlot} bundle=${bundleSlot}`)
  }

  await bot.clickWindow(trackedSlot, 0, 0)
  await sleep(250)
  await bot.clickWindow(bundleSlot, 1, 0)
  await sleep(800)
  // A cancelled bundle insertion leaves the tracked item on the cursor. Returning it to its old
  // slot proves the item was not consumed into the bundle. If insertion was accepted, this click
  // cannot recreate a standalone sword and the assertion below fails closed.
  await bot.clickWindow(trackedSlot, 0, 0)
  await sleep(600)
  await waitItem(bot, 'diamond_sword', 'restricted bundle retained tracked item')
  bot.chat(`/clear ${user} minecraft:bundle`)
  await sleep(300)
  log('TRACK2 restricted bundle insertion rejected by live server')
}

;(async () => {
  try {
    if (!['allowed', 'restricted'].includes(policyMode)) {
      throw new Error(`unsupported POLICY_MODE=${policyMode}`)
    }
    const bot = mineflayer.createBot({
      host: '127.0.0.1', port: 25565, username: user, version: '1.21.11', auth: 'offline'
    })
    bot.on('message', message => log(`CHAT ${message.toString()}`))
    bot.on('error', error => log(`ERROR ${error.stack || error}`))
    await new Promise((resolve, reject) => { bot.once('spawn', resolve); bot.once('error', reject) })
    await bot.waitForChunksToLoad()
    fs.writeFileSync(`${root}/restrict-bot.uuid`, `${bot.player.uuid}\n`)
    log(`SPAWN uuid=${bot.player.uuid} policy=${policyMode}`)

    await waitFile('go-restricted')
    bot.chat('/loreitems give acc_track_nested')
    await waitItem(bot, 'diamond_sword', `${policyMode} tracked delivery`)
    await verifyShulkerPolicy(bot)
    if (policyMode === 'restricted') {
      await verifyBundleRestriction(bot)
      fs.appendFileSync(`${root}/evidence/case-results.txt`,
        'PASS ACC-TRACK-002 restricted-mode: real client shulker and bundle insertion rejected without item loss\n')
    } else {
      fs.appendFileSync(`${root}/evidence/case-results.txt`,
        'PASS ACC-LIFE-001 pre-reload baseline: real client shulker insertion accepted while shared containers were allowed\n')
    }
    fs.writeFileSync(`${root}/restricted-done`, 'ok\n')
    bot.quit(`${policyMode} tracking acceptance complete`)
  } catch (error) {
    log(`FATAL ${error.stack || error}`)
    fs.writeFileSync(`${root}/restricted-bot.failed`, String(error.stack || error))
    process.exit(1)
  }
})()
