#!/usr/bin/env node
/**
 * preload-check.js — 在没有 Android 工具链的机器上验证 lx 音源桥接层。
 *
 * 背景：`user-api-preload.js` 是脚本与原生之间的唯一桥。它有几个坑（能力白名单、
 * action 分支、Promise 错误路径）都只能在运行时暴露，而项目往往拿不到真机。
 * 这个脚本用 Node 的 vm 模块把 preload 原样跑起来，模拟原生侧，从而在桌面
 * 上就能发现「在线搜索永远为空」这类问题。
 *
 * 用法：
 *   node tools/preload-check.js [path/to/user-api-preload.js]
 *
 * 退出码 0 = 全部通过；1 = 有失败项。可直接接进 CI。
 *
 * 注意：preload 结尾会冻结整个 globalThis（含 Array.prototype），因此每次
 * 场景都必须用全新的 vm context，且所有捕获都在宿主机侧完成。
 */
'use strict'

const fs = require('fs')
const path = require('path')
const vm = require('vm')

const DEFAULT_PRELOAD = path.join(
  __dirname, '..', 'app', 'src', 'main', 'assets', 'lxmusic', 'user-api-preload.js'
)

/**
 * 在一个全新的沙箱里加载 preload，执行 setup，再驱动一次原生请求。
 * @param {string} preloadSource
 * @param {{sources: object, handler: string}} opts handler 为 JS 源码片段，
 *        需返回一个函数 ({source, action, info}) => Promise
 * @param {{requestKey: string, data: object}} drive
 * @param {(calls: Array) => object|null} pick 选出要断言的那次原生调用
 */
async function runScenario (preloadSource, opts, drive, waitMs = 80) {
  const calls = []
  const sandbox = { console }

  sandbox.__lx_native_call__ = (key, action, data) => {
    calls.push({ action, data: data == null ? null : JSON.parse(data) })
  }
  for (const n of [
    'set_timeout', 'utils_str2b64', 'utils_b642buf',
    'utils_str2md5', 'utils_aes_encrypt', 'utils_rsa_encrypt',
  ]) {
    sandbox['__lx_native_call__' + n] = () => '[]'
  }

  vm.createContext(sandbox)
  vm.runInContext(preloadSource, sandbox, { filename: 'user-api-preload.js' })

  vm.runInContext(`
    globalThis.lx_setup('KEY', 'script-id', 'Check Script', 'desc', '1.0.0', 'author', '', '')
    const { EVENT_NAMES, on, send } = globalThis.lx
    on(EVENT_NAMES.request, (${opts.handler}))
    send(EVENT_NAMES.inited, { sources: ${JSON.stringify(opts.sources)} })
  `, sandbox)

  // 记录 init 上报的能力
  const initCall = calls.find(c => c.action === 'init')

  const before = calls.length
  vm.runInContext(
    `globalThis.__lx_native__('KEY','request', ${JSON.stringify(JSON.stringify(drive))})`,
    sandbox
  )

  await new Promise(r => setTimeout(r, waitMs))

  const response = calls.slice(before).find(c => c.action === 'response')
  return { initCall, response, calls }
}

const results = []
function check (name, fn) {
  results.push({ name, fn })
}

// ---------------------------------------------------------------------------
// 场景定义
// ---------------------------------------------------------------------------

const MUSIC_URL_HANDLER = `({ source, action, info }) => {
  if (action === 'search') return Promise.resolve({ data: [
    { songmid: 'abc123', name: info.name, singer: 'Tester', albumName: 'A' }
  ] })
  if (action === 'lyric') return Promise.resolve({ lyric: '[00:00.00]hi', tlyric: '[00:00.00]hi2' })
  if (action === 'pic') return Promise.resolve('https://example.com/cover.jpg')
  return Promise.resolve('https://example.com/song.mp3')
}`

const ALL_SOURCES_FOR_TEST = {
  kg: { type: 'music', actions: ['musicUrl', 'search', 'lyric'], qualitys: ['128k', '320k'] },
}

check('能力上报不做静默裁剪：脚本声明的 search 必须保留', async (preload) => {
  // 这是历史 bug 的回归点：supportActions 白名单里若漏了 'search'，
  // 这里上报的 actions 会退化成 ['musicUrl']，Kotlin 侧 supportsAction() 随之为 false，
  // LxRepository.search() 直接 return emptyList()，在线搜索永远搜不出东西。
  const { initCall } = await runScenario(
    preload,
    { sources: ALL_SOURCES_FOR_TEST, handler: MUSIC_URL_HANDLER },
    { requestKey: 'r0', data: { source: 'kg', action: 'musicUrl', info: { songmid: 'x', type: '128k' } } }
  )
  const reported = initCall?.data?.info?.sources?.kg?.actions ?? []
  if (!reported.includes('search')) {
    throw new Error(`上报的 actions = ${JSON.stringify(reported)}，缺少 'search'`)
  }
  if (!reported.includes('musicUrl')) {
    throw new Error(`上报的 actions = ${JSON.stringify(reported)}，缺少 'musicUrl'`)
  }
})

check('search 请求能把脚本结果透传给原生', async (preload) => {
  const { response } = await runScenario(
    preload,
    { sources: ALL_SOURCES_FOR_TEST, handler: MUSIC_URL_HANDLER },
    {
      requestKey: 'r1',
      data: { source: 'kg', action: 'search', info: { name: '周杰伦', singer: '', page: 1, type: 'music' } },
    }
  )
  if (!response) throw new Error('脚本没有产生任何 response 回调')
  if (response.data.status !== true) throw new Error(`status=${response.data.status}，errorMessage=${response.data.errorMessage}`)
  const result = response.data.result
  if (!result) throw new Error('response.result 为空 —— 原生侧拿不到任何数据（action 分支缺失？）')
  if (!Array.isArray(result.data) || result.data.length === 0) {
    throw new Error(`result.data 不是非空数组：${JSON.stringify(result.data)}`)
  }
  if (result.data[0].songmid !== 'abc123') {
    throw new Error(`songmid 未透传：${JSON.stringify(result.data[0])}`)
  }
})

check('musicUrl 请求能返回直链', async (preload) => {
  const { response } = await runScenario(
    preload,
    { sources: ALL_SOURCES_FOR_TEST, handler: MUSIC_URL_HANDLER },
    {
      requestKey: 'r2',
      data: { source: 'kg', action: 'musicUrl', info: { songmid: 'x', type: '320k' } },
    }
  )
  if (!response || response.data.status !== true) {
    throw new Error(`失败：${response ? response.data.errorMessage : '无 response'}`)
  }
  const url = response.data.result?.data?.url
  if (url !== 'https://example.com/song.mp3') throw new Error(`url 异常：${url}`)
})

check('lyric 请求返回原文+翻译', async (preload) => {
  const { response } = await runScenario(
    preload,
    { sources: ALL_SOURCES_FOR_TEST, handler: MUSIC_URL_HANDLER },
    { requestKey: 'r3', data: { source: 'kg', action: 'lyric', info: { songmid: 'x' } } }
  )
  const d = response?.data?.result?.data
  if (!d || d.lyric !== '[00:00.00]hi') throw new Error(`lyric 异常：${JSON.stringify(d)}`)
  if (d.tlyric !== '[00:00.00]hi2') throw new Error(`tlyric 异常：${JSON.stringify(d)}`)
})

check('脚本抛错时回 status=false 而不是挂死', async (preload) => {
  const { response } = await runScenario(
    preload,
    {
      sources: ALL_SOURCES_FOR_TEST,
      handler: `({ action }) => { if (action === 'search') return Promise.reject(new Error('boom')); return Promise.resolve('https://e.com/a.mp3') }`,
    },
    { requestKey: 'r4', data: { source: 'kg', action: 'search', info: { name: 'x' } } }
  )
  if (!response) throw new Error('脚本 rejected 后没有 response —— 原生会等到超时')
  if (response.data.status !== false) throw new Error('应当回 status=false')
  if (!response.data.errorMessage) throw new Error('缺少 errorMessage')
})

check('非法 url 被拒绝（安全校验未失效）', async (preload) => {
  const { response } = await runScenario(
    preload,
    {
      sources: ALL_SOURCES_FOR_TEST,
      handler: `() => Promise.resolve('file:///etc/passwd')`,
    },
    { requestKey: 'r5', data: { source: 'kg', action: 'musicUrl', info: { songmid: 'x', type: '128k' } } }
  )
  if (!response) throw new Error('无 response')
  if (response.data.status !== false) throw new Error('非 http(s) 地址本应被拒绝')
})

// ---------------------------------------------------------------------------

async function main () {
  const target = process.argv[2] || DEFAULT_PRELOAD
  if (!fs.existsSync(target)) {
    console.error(`找不到 preload：${target}`)
    process.exit(2)
  }
  const preload = fs.readFileSync(target, 'utf8')
  console.log(`preload: ${target}\n`)

  let failed = 0
  for (const { name, fn } of results) {
    try {
      await fn(preload)
      console.log(`  PASS  ${name}`)
    } catch (e) {
      failed++
      console.log(`  FAIL  ${name}`)
      console.log(`        ${e.message}`)
    }
  }

  console.log(`\n${results.length - failed}/${results.length} 通过`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch(e => { console.error(e); process.exit(2) })
