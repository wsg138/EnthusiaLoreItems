const moduleRoot = '/tmp/wp05-tracking-contract/bot/node_modules'
const mineflayer = require(`${moduleRoot}/mineflayer`)
const fs = require('fs')
const Vec3 = require(`${moduleRoot}/vec3`).Vec3

const root = '/tmp/wp05-tracking-contract'
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = message => {
  const line = `${new Date().toISOString()} ${message}`
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

async function verifyShulkerRestriction(bot) {
  bot.chat('/tp Wp05RestrictBot 0 71 0')
  bot.chat('/setblock 1 70 0 minecraft:shulker_box')
  await sleep(500)
  const block = await waitBlock(bot, 1, 70, 0, 'shulker_box')
  const container = await bot.openContainer(block)
  await sleep(400)

  const trackedSlot = windowSlot(bot, 'diamond_sword')
  await bot.clickWindow(trackedSlot, 0, 0)
  await sleep(250)
  await bot.clickWindow(0, 0, 0)
  await sleep(800)

  const inserted = bot.currentWindow && bot.currentWindow.slots[0]
  if (inserted && inserted.name === 'diamond_sword') {
    throw new Error('restricted shulker accepted the tracked item')
  }
  container.close()
  await sleep(700)
  await waitItem(bot, 'diamond_sword', 'restricted shulker retained tracked item')
  log('TRACK2 restricted shulker insertion rejected by live server')
}

async function verifyBundleRestriction(bot) {
  bot.chat('/give Wp05RestrictBot minecraft:bundle 1')
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
  bot.chat('/clear Wp05RestrictBot minecraft:bundle')
  await sleep(300)
  log('TRACK2 restricted bundle insertion rejected by live server')
}

;(async () => {
  try {
    const bot = mineflayer.createBot({
      host: '127.0.0.1', port: 25565, username: 'Wp05RestrictBot', version: '1.21.11', auth: 'offline'
    })
    bot.on('message', message => log(`CHAT ${message.toString()}`))
    bot.on('error', error => log(`ERROR ${error.stack || error}`))
    await new Promise((resolve, reject) => { bot.once('spawn', resolve); bot.once('error', reject) })
    await bot.waitForChunksToLoad()
    fs.writeFileSync(`${root}/restrict-bot.uuid`, `${bot.player.uuid}\n`)
    log(`SPAWN uuid=${bot.player.uuid}`)

    await waitFile('go-restricted')
    bot.chat('/loreitems give acc_track_nested')
    await waitItem(bot, 'diamond_sword', 'restricted tracked delivery')
    await verifyShulkerRestriction(bot)
    await verifyBundleRestriction(bot)

    fs.appendFileSync(`${root}/evidence/case-results.txt`,
      'PASS ACC-TRACK-002 restricted-mode: real client shulker and bundle insertion rejected without item loss\n')
    fs.writeFileSync(`${root}/restricted-done`, 'ok\n')
    bot.quit('restricted tracking acceptance complete')
  } catch (error) {
    log(`FATAL ${error.stack || error}`)
    fs.writeFileSync(`${root}/restricted-bot.failed`, String(error.stack || error))
    process.exit(1)
  }
})()
