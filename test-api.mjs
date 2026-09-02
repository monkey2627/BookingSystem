#!/usr/bin/env node
/**
 * MHP 档期预约平台 — API 自动化测试
 *
 * 运行: node test-api.mjs
 * 要求: Node 18+（内置 fetch），后端 Gateway 在 localhost:8080
 *
 * 覆盖 12 个模块，约 95 个用例，全部账号自动切换，重复运行安全（幂等注册 + 动态日期）。
 * 退出码：全部通过 0，有失败 1（可接入 CI）。
 */

const BASE = 'http://localhost:8080/api'

// ── 测试账号 ─────────────────────────────────────────────────
const ACCOUNTS = {
  user_a:     { phone: '13800000001', nickname: '自动化_客人A', password: 'Test1234' },
  user_b:     { phone: '13800000002', nickname: '自动化_客人B', password: 'Test1234' },
  merchant_c: { phone: '13800000003', nickname: '自动化_商家C', password: 'Test1234' },
  merchant_d: { phone: '13800000004', nickname: '自动化_商家D', password: 'Test1234' },
}

// 运行时上下文（setup 阶段填充基础字段，各 suite 逐步填充其余）
const ctx = {
  tokens:           {},   // key → token string
  userIds:          {},   // key → userId number
  merchantIds:      {},   // 'merchant_c'|'merchant_d' → merchantId
  scheduleId:       null, // D1 直接预约档期 ID
  rushScheduleId:   null, // D3 抢档期 ID
  bookingScheduleId: null, // D4 预约流程专用档期 ID
  bookingId:        null,
  completeBookingId: null,
  postId:           null,
}

// ── 日期工具 ─────────────────────────────────────────────────
function futureDate(daysAhead) {
  const d = new Date()
  d.setDate(d.getDate() + daysAhead)
  return d.toISOString().slice(0, 10)
}

function pastDatetime(daysAgo = 1) {
  const d = new Date()
  d.setDate(d.getDate() - daysAgo)
  return d.toISOString().slice(0, 19)
}

function futureDatetime(daysAhead) {
  const d = new Date()
  d.setDate(d.getDate() + daysAhead)
  return d.toISOString().slice(0, 19)
}

// ── HTTP helpers ──────────────────────────────────────────────
async function req(method, path, body, token, params) {
  const url = new URL(`${BASE}${path}`)
  if (params) {
    Object.entries(params).forEach(([k, v]) => {
      if (v != null) url.searchParams.set(k, v)
    })
  }
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.token = token
  let res
  try {
    res = await fetch(url, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
  } catch (e) {
    return { code: -1, message: `网络错误: ${e.message}`, data: null }
  }
  try { return await res.json() }
  catch { return { code: res.status, message: `HTTP ${res.status}（非 JSON）`, data: null } }
}

const get  = (p, token, params)       => req('GET',    p, undefined, token, params)
const post = (p, body, token)         => req('POST',   p, body,      token)
const put  = (p, body, token, params) => req('PUT',    p, body,      token, params)
const del  = (p, token)               => req('DELETE', p, undefined, token)

// ── 断言 ─────────────────────────────────────────────────────
function assert(cond, msg) {
  if (!cond) throw new Error(msg || 'assertion failed')
}
function assertOk(res, hint = '') {
  assert(res.code === 200,
    `${hint ? hint + ' — ' : ''}期望 code=200，实际 code=${res.code}，msg="${res.message}"`)
}
function assertFail(res, hint = '') {
  assert(res.code !== 200,
    `${hint ? hint + ' — ' : ''}期望失败，但返回了 code=200`)
}

// ── 测试 runner ───────────────────────────────────────────────
let passed = 0, failed = 0
const failures = []

async function test(name, fn) {
  try {
    await fn()
    process.stdout.write(`    \x1b[32m✓\x1b[0m ${name}\n`)
    passed++
  } catch (e) {
    process.stdout.write(`    \x1b[31m✗\x1b[0m ${name}\n      └─ ${e.message}\n`)
    failed++
    failures.push({ name, reason: e.message })
  }
}

async function suite(name, fn) {
  process.stdout.write(`\n\x1b[36m▶ ${name}\x1b[0m\n`)
  await fn()
}

// ════════════════════════════════════════════════════════════════
// 连通性检查
// ════════════════════════════════════════════════════════════════
async function preflight() {
  process.stdout.write('  检查 Gateway 连通性... ')
  try {
    const ctrl = new AbortController()
    setTimeout(() => ctrl.abort(), 5000)
    const res = await fetch(`${BASE}/user/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ phone: '00000000000', password: 'x' }),
      signal: ctrl.signal,
    })
    process.stdout.write(`\x1b[32m✓\x1b[0m HTTP ${res.status}\n`)
  } catch (e) {
    process.stderr.write(`\x1b[31m✗ 无法连接到 ${BASE}\x1b[0m\n`)
    process.stderr.write(`  错误: ${e.message}\n`)
    process.stderr.write('  请确认 Gateway 已在 localhost:8080 启动\n')
    process.exit(1)
  }
}

// ════════════════════════════════════════════════════════════════
// SETUP：注册 + 登录 + 开通商家
// ════════════════════════════════════════════════════════════════
async function setup() {
  process.stdout.write('\x1b[33m═══ 环境准备（注册 / 登录 / 开通商家）═══\x1b[0m\n')

  for (const [key, acc] of Object.entries(ACCOUNTS)) {
    await post('/user/register', acc)  // 已存在时忽略，幂等
    const r = await post('/user/login', { phone: acc.phone, password: acc.password })
    if (r.code !== 200 || !r.data?.token) {
      throw new Error(`[Setup] 账号 ${key} 登录失败: code=${r.code} "${r.message}"`)
    }
    ctx.tokens[key]  = r.data.token
    ctx.userIds[key] = r.data.userInfo?.id
    process.stdout.write(`  ✓ ${key} uid=${ctx.userIds[key]}\n`)
  }

  for (const key of ['merchant_c', 'merchant_d']) {
    const upd = await put('/merchant/info', {
      serviceTypes: [1, 2], city: '上海', intro: '自动化测试商家，请勿打扰'
    }, ctx.tokens[key])
    if (upd.code !== 200) {
      throw new Error(`[Setup] 商家 ${key} 资料更新失败: ${upd.message}`)
    }
    const r = await get('/merchant/my', ctx.tokens[key])
    if (r.code !== 200 || !r.data?.id) {
      throw new Error(`[Setup] 商家 ${key} 获取 ID 失败`)
    }
    ctx.merchantIds[key] = r.data.id
    process.stdout.write(`  ✓ 商家 ${key} merchantId=${ctx.merchantIds[key]}\n`)
  }
}

// ════════════════════════════════════════════════════════════════
// Suite 1：用户认证（测试清单 #1-8）
// ════════════════════════════════════════════════════════════════
async function suiteAuth() {
  await suite('1. 用户认证', async () => {
    await test('重复注册同一手机号返回错误（#2）', async () => {
      const r = await post('/user/register', ACCOUNTS.user_a)
      assertFail(r, '重复注册')
    })

    await test('手机号格式非法返回校验错误（#3）', async () => {
      const r = await post('/user/register', { phone: '123', password: 'Test1234', nickname: 'x' })
      assertFail(r, '非法手机号')
    })

    await test('密码错误登录返回错误（#6）', async () => {
      const r = await post('/user/login', { phone: ACCOUNTS.user_a.phone, password: 'WrongPwd!' })
      assertFail(r, '密码错误')
    })

    await test('未注册手机号登录返回错误（#7）', async () => {
      const r = await post('/user/login', { phone: '19999999999', password: 'Test1234' })
      assertFail(r, '未注册手机号')
    })

    await test('无 token 访问受保护接口返回非 200（#4 / #89）', async () => {
      const r = await get('/booking/my')
      assert(r.code !== 200, `期望鉴权失败，实际 code=${r.code}`)
    })

    await test('正常登录返回 token 和 userInfo 结构（#5）', async () => {
      const r = await post('/user/login', {
        phone: ACCOUNTS.user_a.phone, password: ACCOUNTS.user_a.password
      })
      assertOk(r)
      assert(typeof r.data?.token === 'string' && r.data.token.length > 10, 'token 长度异常')
      assert(typeof r.data?.userInfo?.id === 'number', 'userInfo.id 应为数字')
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 2：商家管理（测试清单 #13-22）
// ════════════════════════════════════════════════════════════════
async function suiteMerchant() {
  await suite('2. 商家管理 & 搜索', async () => {
    await test('GET /merchant/my 返回当前商家信息（#13）', async () => {
      const r = await get('/merchant/my', ctx.tokens.merchant_c)
      assertOk(r)
      assert(r.data.id === ctx.merchantIds.merchant_c, 'merchantId 不匹配')
    })

    await test('PUT /merchant/info 修改简介后缓存失效（#14）', async () => {
      const intro = `自动化测试_${Date.now()}`
      const r = await put('/merchant/info', { intro }, ctx.tokens.merchant_c)
      assertOk(r)
      const detail = await get(`/merchant/${ctx.merchantIds.merchant_c}`, ctx.tokens.user_a)
      assertOk(detail)
      assert(detail.data.intro === intro, `简介未更新: "${detail.data.intro}"`)
    })

    await test('GET /merchant/{id} 两次请求结果一致（缓存命中，#15）', async () => {
      const r1 = await get(`/merchant/${ctx.merchantIds.merchant_c}`, ctx.tokens.user_a)
      const r2 = await get(`/merchant/${ctx.merchantIds.merchant_c}`, ctx.tokens.user_a)
      assertOk(r1); assertOk(r2)
      assert(r1.data.id === r2.data.id, '两次结果 id 不一致')
    })

    await test('访问不存在商家 ID 返回业务错误（带 token，非 401，#16）', async () => {
      const r = await get('/merchant/999999999', ctx.tokens.user_a)
      assertFail(r, '不存在商家')
      assert(r.code !== 401 && r.code !== 10001,
        `应返回业务错误而非鉴权错误，实际 code=${r.code}`)
    })

    await test('搜索 keyword，返回 records 数组和 total（#17）', async () => {
      const r = await get('/merchant/search', ctx.tokens.user_a,
        { keyword: '自动化', page: 1, size: 10 })
      assertOk(r)
      assert(Array.isArray(r.data?.records),
        `records 应为数组，实际: ${JSON.stringify(r.data)}`)
      assert(typeof r.data?.total === 'number', 'total 应为数字')
    })

    await test('搜索 city=上海，结果中城市一致（#18）', async () => {
      const r = await get('/merchant/search', ctx.tokens.user_a,
        { city: '上海', page: 1, size: 10 })
      assertOk(r)
      assert(Array.isArray(r.data?.records), 'records 应为数组')
      if (r.data.records.length > 0) {
        const wrong = r.data.records.filter(m => m.city && m.city !== '上海')
        assert(wrong.length === 0,
          `city=上海 结果中存在其他城市: ${wrong.map(m => m.city)}`)
      }
    })

    await test('搜索 serviceType=1，返回分页结构（#19）', async () => {
      const r = await get('/merchant/search', ctx.tokens.user_a,
        { serviceType: 1, page: 1, size: 10 })
      assertOk(r)
      assert(Array.isArray(r.data?.records), 'records 应为数组')
    })

    await test('PUT /merchant/status 关闭后恢复营业（#）', async () => {
      const off = await put('/merchant/status', undefined, ctx.tokens.merchant_c, { status: 0 })
      assertOk(off, '关闭店铺')
      const on  = await put('/merchant/status', undefined, ctx.tokens.merchant_c, { status: 1 })
      assertOk(on, '恢复营业')
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 3：档期管理（测试清单 #23-32）
// ════════════════════════════════════════════════════════════════
async function suiteSchedule() {
  const MONTH = (d) => d.slice(0, 7)
  // D3=45 天后，确保在 2 个月范围内（抢档大厅查询窗口）
  const D1 = futureDate(60)   // 直接预约档期
  const D2 = futureDate(61)   // 全天档期
  const D3 = futureDate(45)   // 抢档期（已开放）
  const D4 = futureDate(90)   // 预约流程专用

  // 幂等创建辅助：已存在时必须是"重复"错误，否则抛出
  function assertCreateOrDup(r, hint) {
    if (r.code !== 200) {
      assert(
        r.message?.includes('已存在') || r.message?.includes('重复') ||
        r.message?.toLowerCase().includes('duplic'),
        `${hint}创建失败（非重复原因）: code=${r.code} "${r.message}"`
      )
    }
  }

  await suite('3. 档期管理', async () => {
    await test('创建直接预约档期（bookType=0，#23）', async () => {
      const r = await post('/schedule', {
        date: D1, timeSlot: '10:00-12:00', bookType: 0, serviceType: 1
      }, ctx.tokens.merchant_c)
      assertCreateOrDup(r, 'D1 ')
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: MONTH(D1) })
      assertOk(lr)
      const s = lr.data?.find(s => s.date === D1 && s.timeSlot === '10:00-12:00')
      assert(s, `月历中找不到 ${D1} 10:00-12:00 档期`)
      ctx.scheduleId = s.id
    })

    await test('创建全天档期（timeSlot=null，#113）', async () => {
      const r = await post('/schedule', {
        date: D2, timeSlot: null, bookType: 0, serviceType: 2
      }, ctx.tokens.merchant_c)
      assertCreateOrDup(r, 'D2 ')
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: MONTH(D2) })
      assertOk(lr)
      const s = lr.data?.find(s => s.date === D2 && s.timeSlot == null)
      assert(s, `月历中找不到 ${D2} 全天档期`)
    })

    await test('同日期同时间段重复创建返回错误（#107 数据库唯一索引兜底）', async () => {
      const r = await post('/schedule', {
        date: D1, timeSlot: '10:00-12:00', bookType: 0, serviceType: 1
      }, ctx.tokens.merchant_c)
      assertFail(r, '重复档期')
    })

    await test('缺少必填字段 date 返回校验错误（#25）', async () => {
      const r = await post('/schedule', {
        timeSlot: '10:00-12:00', bookType: 0, serviceType: 1
      }, ctx.tokens.merchant_c)
      assertFail(r, 'date 缺失')
    })

    await test('timeSlot 为空字符串返回错误', async () => {
      const r = await post('/schedule', {
        date: futureDate(201), timeSlot: '', bookType: 0, serviceType: 1
      }, ctx.tokens.merchant_c)
      assertFail(r, '空字符串 timeSlot')
    })

    await test('创建抢档期（bookType=1，rushOpenTime 已过 → 立即开放，#24）', async () => {
      const r = await post('/schedule', {
        date: D3, timeSlot: '14:00-16:00', bookType: 1,
        serviceType: 1, rushOpenTime: pastDatetime(1), maxQueueSize: 2
      }, ctx.tokens.merchant_c)
      assertCreateOrDup(r, 'D3 ')
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: MONTH(D3) })
      assertOk(lr)
      const s = lr.data?.find(s => s.date === D3 && s.bookType === 1)
      assert(s, `月历中找不到 ${D3} 抢档期`)
      ctx.rushScheduleId = s.id
    })

    await test('批量创建档期（weekdays=[1,3,5] 限定星期，#27）', async () => {
      const r = await post('/schedule/batch', {
        startDate: futureDate(100), endDate: futureDate(120),
        weekdays: [1, 3, 5], timeSlot: '09:00-11:00', bookType: 0, serviceType: 1
      }, ctx.tokens.merchant_c)
      assertOk(r)
    })

    await test('批量创建档期（weekdays=[] 全部日期，#114）', async () => {
      const r = await post('/schedule/batch', {
        startDate: futureDate(130), endDate: futureDate(132),
        weekdays: [], timeSlot: '15:00-17:00', bookType: 0, serviceType: 2
      }, ctx.tokens.merchant_c)
      assertOk(r)
    })

    await test('批量创建全天档期（timeSlot=null，#113）', async () => {
      const r = await post('/schedule/batch', {
        startDate: futureDate(140), endDate: futureDate(141),
        weekdays: [], timeSlot: null, bookType: 0, serviceType: 1
      }, ctx.tokens.merchant_c)
      assertOk(r)
    })

    await test('merchant_d 删除 merchant_c 的档期返回错误（跨商家权限，#32）', async () => {
      const r = await del(`/schedule/${ctx.scheduleId}`, ctx.tokens.merchant_d)
      assertFail(r, '越权删除档期')
    })

    await test('为预约流程准备专用档期（D4）', async () => {
      const r = await post('/schedule', {
        date: D4, timeSlot: '10:00-12:00', bookType: 0, serviceType: 1
      }, ctx.tokens.merchant_c)
      assertCreateOrDup(r, 'D4 ')
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: MONTH(D4) })
      assertOk(lr)
      let s = lr.data?.find(s => s.date === D4 && s.timeSlot === '10:00-12:00' && s.status === 0)
      if (!s) {
        // 该时间段已被预约，换备用时间段
        const r2 = await post('/schedule', {
          date: D4, timeSlot: '13:00-15:00', bookType: 0, serviceType: 1
        }, ctx.tokens.merchant_c)
        assertOk(r2, 'D4 备用档期创建')
        const lr2 = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
          ctx.tokens.merchant_c, { month: MONTH(D4) })
        s = lr2.data?.find(s => s.date === D4 && s.status === 0)
        assert(s, `D4(${D4}) 找不到可用空闲档期`)
      }
      ctx.bookingScheduleId = s.id
    })

    await test('删除已被预约的档期返回错误（status≠0 不可删，#31）', async () => {
      const date = futureDate(75)
      await post('/schedule', { date, timeSlot: '20:00-22:00', bookType: 0, serviceType: 1 },
        ctx.tokens.merchant_c)
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: date.slice(0, 7) })
      const s = lr.data?.find(sc => sc.date === date && sc.status === 0)
      if (!s) throw new Error('找不到用于"删除已预约档期"测试的空闲档期')

      const br = await post('/booking', { scheduleId: s.id, remark: 'del-when-booked' },
        ctx.tokens.user_a)
      assertOk(br, '预约档期')

      const dr = await del(`/schedule/${s.id}`, ctx.tokens.merchant_c)
      assertFail(dr, '删除已预约档期')

      // 清理：取消预约，恢复档期状态
      const blr = await get('/booking/my', ctx.tokens.user_a, { size: 20 })
      const b = blr.data?.list?.find(b => b.remark === 'del-when-booked')
      if (b) await put(`/booking/${b.id}/cancel`, undefined, ctx.tokens.user_a)
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 4：预约流程（测试清单 #33-47）
// ════════════════════════════════════════════════════════════════
async function suiteBooking() {
  await suite('4. 预约流程', async () => {
    await test('user_a 发起预约（#33）', async () => {
      const r = await post('/booking', {
        scheduleId: ctx.bookingScheduleId, remark: 'autotest-remark'
      }, ctx.tokens.user_a)
      assertOk(r, '发起预约')
      const lr = await get('/booking/my', ctx.tokens.user_a, { size: 20 })
      assertOk(lr)
      const b = lr.data?.list?.find(b => b.remark === 'autotest-remark')
      assert(b, '在 myBooking 中找不到刚创建的预约')
      ctx.bookingId = b.id
    })

    await test('同一档期重复预约返回错误（#34）', async () => {
      const r = await post('/booking', { scheduleId: ctx.bookingScheduleId }, ctx.tokens.user_a)
      assertFail(r, '重复预约')
    })

    await test('user_b 预约同一已占用档期返回错误（#35）', async () => {
      const r = await post('/booking', { scheduleId: ctx.bookingScheduleId }, ctx.tokens.user_b)
      assertFail(r, 'user_b 预约已占用档期')
    })

    await test('客人调用 confirm 返回错误（非商家不能确认，#39）', async () => {
      const r = await put(`/booking/${ctx.bookingId}/confirm`, undefined, ctx.tokens.user_a)
      assertFail(r, '客人调 confirm')
    })

    await test('merchant_d 操作 merchant_c 的预约返回错误（#44）', async () => {
      const r = await put(`/booking/${ctx.bookingId}/confirm`, undefined, ctx.tokens.merchant_d)
      assertFail(r, 'merchant_d 越权')
    })

    await test('merchant_c 确认预约 → status 变 2（#38）', async () => {
      const r = await put(`/booking/${ctx.bookingId}/confirm`, undefined, ctx.tokens.merchant_c)
      assertOk(r, '确认预约')
      const lr = await get('/booking/received', ctx.tokens.merchant_c, { size: 20, status: 2 })
      assertOk(lr)
      const b = lr.data?.list?.find(b => b.id === ctx.bookingId)
      assert(b, '已定档列表找不到该预约')
      assert(b.status === 2, `status 应为 2，实际 ${b.status}`)
    })

    await test('merchant_c 完成预约 → status 变 3（#40）', async () => {
      const r = await put(`/booking/${ctx.bookingId}/complete`, undefined, ctx.tokens.merchant_c)
      assertOk(r, '完成预约')
      ctx.completeBookingId = ctx.bookingId
    })

    await test('已完成预约不能再取消（#43）', async () => {
      const r = await put(`/booking/${ctx.bookingId}/cancel`, undefined, ctx.tokens.user_a)
      assertFail(r, '取消已完成预约')
    })

    await test('商家按 status=3 查预约包含已完成记录（#46）', async () => {
      const r = await get('/booking/received', ctx.tokens.merchant_c, { size: 20, status: 3 })
      assertOk(r)
      assert(r.data?.list?.some(b => b.id === ctx.completeBookingId),
        '已完成预约不在 status=3 列表中')
    })

    await test('游标分页结构正确（hasMore + nextCursor，#45）', async () => {
      const r = await get('/booking/my', ctx.tokens.user_a, { size: 5 })
      assertOk(r)
      assert(typeof r.data?.hasMore === 'boolean', 'hasMore 不是 boolean')
      assert('nextCursor' in r.data, '缺少 nextCursor 字段')
    })

    await test('游标翻页结果不重复（nextCursor 作为 lastId，#47）', async () => {
      const page1 = await get('/booking/received', ctx.tokens.merchant_c, { size: 2 })
      assertOk(page1)
      if (page1.data?.hasMore) {
        const page2 = await get('/booking/received', ctx.tokens.merchant_c,
          { size: 2, lastId: page1.data.nextCursor })
        assertOk(page2)
        const ids1 = new Set(page1.data.list.map(b => b.id))
        const overlap = page2.data.list.filter(b => ids1.has(b.id))
        assert(overlap.length === 0, `两页预约有重复 id: ${overlap.map(b => b.id)}`)
      }
    })

    await test('GET /booking/stats/merchant 返回商家统计数据', async () => {
      const r = await get('/booking/stats/merchant', ctx.tokens.merchant_c)
      assertOk(r)
      assert(typeof r.data?.totalOrders === 'number', 'totalOrders 应为数字')
      assert(typeof r.data?.completedOrders === 'number', 'completedOrders 应为数字')
      assert(r.data.completedOrders >= 1, '完成预约数应 ≥ 1')
    })

    await test('独立取消流程（新建档期 → 预约 → 取消，#41）', async () => {
      const date = futureDate(170)
      await post('/schedule', { date, timeSlot: '08:00-09:00', bookType: 0, serviceType: 1 },
        ctx.tokens.merchant_c)
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: date.slice(0, 7) })
      const s = lr.data?.find(s => s.date === date && s.status === 0)
      if (!s) throw new Error('取消流程测试：找不到可用档期')
      const br = await post('/booking', { scheduleId: s.id, remark: 'cancel-test' },
        ctx.tokens.user_a)
      assertOk(br, '创建取消测试预约')
      const blr = await get('/booking/my', ctx.tokens.user_a, { size: 20 })
      const b = blr.data?.list?.find(b => b.remark === 'cancel-test')
      assert(b, '找不到 cancel-test 预约')
      assertOk(await put(`/booking/${b.id}/cancel`, undefined, ctx.tokens.user_a), 'user_a 取消')
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 5：抢档期（测试清单 #48-54）
// ════════════════════════════════════════════════════════════════
async function suiteRush() {
  await suite('5. 抢档期', async () => {
    await test('rushOpenTime 在未来时 rush 返回错误（未到开放时间）', async () => {
      const date = futureDate(250)
      await post('/schedule', {
        date, timeSlot: '20:00-22:00', bookType: 1,
        serviceType: 1, rushOpenTime: futureDatetime(365), maxQueueSize: 5
      }, ctx.tokens.merchant_c)
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: date.slice(0, 7) })
      const s = lr.data?.find(sc => sc.date === date && sc.bookType === 1)
      if (!s) throw new Error('找不到测试用未开放抢档期')
      const r = await post(`/schedule/${s.id}/rush`, undefined, ctx.tokens.user_a)
      assertFail(r, '未到开放时间应拒绝')
    })

    await test('user_a 加入已开放抢档期，返回正整数排名（#48）', async () => {
      const r = await post(`/schedule/${ctx.rushScheduleId}/rush`, undefined, ctx.tokens.user_a)
      assertOk(r, 'user_a rush')
      assert(Number.isInteger(r.data?.rankNo) && r.data.rankNo >= 1,
        `rankNo 应为正整数，实际 ${r.data?.rankNo}`)
    })

    await test('user_a 重复加入同一档期返回错误（#49）', async () => {
      const r = await post(`/schedule/${ctx.rushScheduleId}/rush`, undefined, ctx.tokens.user_a)
      assertFail(r, '重复 rush')
    })

    await test('user_b 加入队列，排名 ≥ 2（#50）', async () => {
      const r = await post(`/schedule/${ctx.rushScheduleId}/rush`, undefined, ctx.tokens.user_b)
      assertOk(r, 'user_b rush')
      assert(r.data?.rankNo >= 2, `rankNo 应 ≥ 2，实际 ${r.data?.rankNo}`)
    })

    await test('maxQueueSize=2 已满，第三人加入返回错误（#51）', async () => {
      const r = await post(`/schedule/${ctx.rushScheduleId}/rush`, undefined, ctx.tokens.merchant_d)
      assertFail(r, '队满应失败')
    })

    await test('商家查看排队名单应有 2 人，含 userNickname（#53）', async () => {
      const r = await get(`/schedule/${ctx.rushScheduleId}/queue`, ctx.tokens.merchant_c)
      assertOk(r)
      assert(Array.isArray(r.data), 'queue 不是数组')
      assert(r.data.length === 2, `队列应有 2 人，实际 ${r.data.length}`)
      assert(typeof r.data[0].userNickname === 'string', '缺少 userNickname 字段')
    })

    await test('商家更新排队状态（#54）', async () => {
      const qr = await get(`/schedule/${ctx.rushScheduleId}/queue`, ctx.tokens.merchant_c)
      const rushId = qr.data?.[0]?.id
      assert(rushId, '获取 rushRecord.id 失败')
      const r = await put(`/schedule/rush/${rushId}/status`, undefined,
        ctx.tokens.merchant_c, { status: 1 })
      assertOk(r, '更新排队状态')
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 6：社区动态 & 关注（测试清单 #61-72）
// ════════════════════════════════════════════════════════════════
async function suiteSocial() {
  await suite('6. 社区动态 & 关注', async () => {
    let initLikeCount = 0  // 点赞前初始值，用于验证 +1 / 恢复

    await test('merchant_c 发布动态（#61）', async () => {
      const r = await post('/post', { content: `自动化测试动态_${Date.now()}` },
        ctx.tokens.merchant_c)
      assertOk(r, '发布动态')
    })

    await test('按商家查动态列表，返回游标分页结构（#62）', async () => {
      const r = await get(`/post/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.user_a, { size: 10 })
      assertOk(r)
      assert(Array.isArray(r.data?.list) && r.data.list.length > 0, '动态列表为空')
      assert(typeof r.data?.hasMore === 'boolean', 'hasMore 类型错误')
      assert('nextCursor' in r.data, '缺少 nextCursor')
      ctx.postId = r.data.list[0].id
      initLikeCount = r.data.list[0].likeCount ?? 0
    })

    await test('user_a 点赞动态，likeCount +1（#63）', async () => {
      await post(`/post/${ctx.postId}/like`, undefined, ctx.tokens.user_a)
      const r = await get(`/post/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.user_a, { size: 10 })
      assertOk(r)
      const count = r.data?.list?.find(p => p.id === ctx.postId)?.likeCount
      assert(count === initLikeCount + 1,
        `点赞后 likeCount 应为 ${initLikeCount + 1}，实际 ${count}`)
    })

    await test('再次点赞切换取消，likeCount 恢复初始值（#65）', async () => {
      await post(`/post/${ctx.postId}/like`, undefined, ctx.tokens.user_a)
      const r = await get(`/post/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.user_a, { size: 10 })
      assertOk(r)
      const count = r.data?.list?.find(p => p.id === ctx.postId)?.likeCount
      assert(count === initLikeCount,
        `取消点赞后 likeCount 应恢复为 ${initLikeCount}，实际 ${count}`)
    })

    await test('非作者删除动态返回错误（#67）', async () => {
      const r = await del(`/post/${ctx.postId}`, ctx.tokens.user_a)
      assertFail(r, '非作者删除动态')
    })

    await test('GET /post/feed 动态广场返回游标分页结构', async () => {
      const r = await get('/post/feed', ctx.tokens.user_a, { size: 10 })
      assertOk(r)
      assert(Array.isArray(r.data?.list), 'feed.list 应为数组')
      assert(typeof r.data?.hasMore === 'boolean', 'hasMore 类型错误')
      assert('nextCursor' in r.data, '缺少 nextCursor')
    })

    await test('user_a 关注 merchant_c（#68）', async () => {
      assertOk(
        await post(`/follow/${ctx.merchantIds.merchant_c}`, undefined, ctx.tokens.user_a),
        '关注'
      )
    })

    await test('重复关注返回 200 或 400（#69）', async () => {
      const r = await post(`/follow/${ctx.merchantIds.merchant_c}`, undefined, ctx.tokens.user_a)
      assert(r.code === 200 || r.code === 400,
        `重复关注应返回 200 或 400，实际 code=${r.code}`)
    })

    await test('关注状态查询返回 true（#）', async () => {
      const r = await get(`/follow/${ctx.merchantIds.merchant_c}/status`, ctx.tokens.user_a)
      assertOk(r)
      assert(r.data === true, `关注状态应为 true，实际 ${r.data}`)
    })

    await test('查我的关注列表包含 merchant_c（#71）', async () => {
      const r = await get('/follow/my', ctx.tokens.user_a)
      assertOk(r)
      assert(r.data?.some(m => m.id === ctx.merchantIds.merchant_c),
        '关注列表未包含 merchant_c')
    })

    await test('GET /rush/lobby 关注商家后可见抢档期列表', async () => {
      const r = await get('/rush/lobby', ctx.tokens.user_a)
      assertOk(r)
      assert(Array.isArray(r.data), '抢档大厅应返回数组')
      if (r.data.length > 0) {
        assert('scheduleId' in r.data[0], '条目缺少 scheduleId')
        assert('merchantId' in r.data[0], '条目缺少 merchantId')
      }
    })

    await test('GET /post/followed-feed 返回关注商家的动态', async () => {
      const r = await get('/post/followed-feed', ctx.tokens.user_a, { size: 10 })
      assertOk(r)
      assert(Array.isArray(r.data?.list), 'followed-feed.list 应为数组')
      assert(r.data.list.length > 0, 'followed-feed 为空（merchant_c 动态未出现）')
    })

    await test('取消关注（#70）', async () => {
      assertOk(
        await del(`/follow/${ctx.merchantIds.merchant_c}`, ctx.tokens.user_a),
        '取消关注'
      )
    })

    await test('取消关注后状态为 false', async () => {
      const r = await get(`/follow/${ctx.merchantIds.merchant_c}/status`, ctx.tokens.user_a)
      assertOk(r)
      assert(r.data === false, `取消后关注状态应为 false，实际 ${r.data}`)
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 7：评价（测试清单 #73-76）
// ════════════════════════════════════════════════════════════════
async function suiteReview() {
  await suite('7. 评价', async () => {
    await test('对未完成预约评价返回错误（#74）', async () => {
      const date = futureDate(180)
      await post('/schedule', { date, timeSlot: '18:00-20:00', bookType: 0, serviceType: 1 },
        ctx.tokens.merchant_c)
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: date.slice(0, 7) })
      const s = lr.data?.find(sc => sc.date === date && sc.status === 0)
      if (!s) throw new Error('找不到用于非法评价测试的空闲档期')
      await post('/booking', { scheduleId: s.id, remark: 'review-invalid-test' }, ctx.tokens.user_a)
      const blr = await get('/booking/my', ctx.tokens.user_a, { size: 20 })
      const b = blr.data?.list?.find(b => b.remark === 'review-invalid-test')
      assert(b, '找不到 review-invalid-test 预约')
      const r = await post('/review', {
        orderId: b.id, score: 5, content: '非法：预约未完成就评价，内容足够长以通过校验'
      }, ctx.tokens.user_a)
      assertFail(r, '对未完成预约评价')
    })

    await test('user_a 对已完成预约提交评价（#73）', async () => {
      const r = await post('/review', {
        orderId: ctx.completeBookingId, score: 5, content: '自动化测试好评，服务非常棒！'
      }, ctx.tokens.user_a)
      assertOk(r, '提交评价')
    })

    await test('同一预约重复评价返回错误（#75）', async () => {
      const r = await post('/review', {
        orderId: ctx.completeBookingId, score: 4, content: '重复评价测试，不应成功'
      }, ctx.tokens.user_a)
      assertFail(r, '重复评价')
    })

    await test('查看商家评价列表包含刚提交的评价（#76）', async () => {
      const r = await get(`/review/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.user_a, { page: 1, size: 20 })
      assertOk(r)
      assert(Array.isArray(r.data?.records), 'records 不是数组')
      assert(r.data.records.some(rv => rv.orderId === ctx.completeBookingId),
        '评价列表未找到刚提交的评价')
    })

    await test('评价后商家 avgScore > 0（#73 跨服务更新）', async () => {
      const r = await get(`/merchant/${ctx.merchantIds.merchant_c}`, ctx.tokens.user_a)
      assertOk(r)
      assert(r.data?.avgScore > 0, `avgScore 应 > 0，实际 ${r.data?.avgScore}`)
    })

    await test('商家回复评价', async () => {
      // 用 user_a token 获取评价列表（非白名单接口需要 token）
      const lr = await get(`/review/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.user_a, { page: 1, size: 20 })
      const review = lr.data?.records?.find(rv => rv.orderId === ctx.completeBookingId)
      if (!review) throw new Error('找不到用于回复测试的评价')
      const r = await put(`/review/${review.id}/reply`,
        { reply: '感谢好评！自动化测试回复' }, ctx.tokens.merchant_c)
      assertOk(r, '商家回复评价')
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 8：投诉（测试清单 #77-78）
// ════════════════════════════════════════════════════════════════
async function suiteComplaint() {
  await suite('8. 投诉', async () => {
    await test('user_a 对已完成预约发起投诉（#77）', async () => {
      const r = await post('/complaint', {
        orderId: ctx.completeBookingId,
        reason: '自动化测试投诉原因，描述足够详细以满足最低字数限制，内容合规'
      }, ctx.tokens.user_a)
      assertOk(r, '提交投诉')
    })

    await test('merchant_c 查看收到的投诉列表包含刚提交的（#121）', async () => {
      const r = await get('/complaint/received', ctx.tokens.merchant_c)
      assertOk(r)
      assert(Array.isArray(r.data), 'data 应为数组')
      assert(r.data.some(c => c.orderId === ctx.completeBookingId),
        '商家投诉列表未找到刚提交的投诉')
    })

    await test('对待确认预约投诉，接口不应崩溃（#78）', async () => {
      const lr = await get('/booking/my', ctx.tokens.user_a, { size: 20 })
      const pending = lr.data?.list?.find(b => b.status === 0)
      if (!pending) return
      const r = await post('/complaint', {
        orderId: pending.id, reason: '测试对未完成预约投诉，自动化测试内容足够详细'
      }, ctx.tokens.user_a)
      assert(r.code !== 500, `投诉接口不应返回 500，实际 code=${r.code}`)
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 9：消息（测试清单 #79-81）
// ════════════════════════════════════════════════════════════════
async function suiteMessage() {
  await suite('9. 消息', async () => {
    await test('user_a 向 merchant_c 发送文字消息（#79）', async () => {
      assertOk(
        await post('/message/send', {
          toUserId: ctx.userIds.merchant_c, content: `自动化测试消息_${Date.now()}`
        }, ctx.tokens.user_a),
        '发送消息'
      )
    })

    await test('merchant_c 向 user_a 回复消息', async () => {
      assertOk(
        await post('/message/send', {
          toUserId: ctx.userIds.user_a, content: '自动化测试回复'
        }, ctx.tokens.merchant_c),
        '回复消息'
      )
    })

    await test('user_a 查看会话列表，包含 merchant_c 会话（#80）', async () => {
      const r = await get('/message/conversations', ctx.tokens.user_a)
      assertOk(r)
      assert(Array.isArray(r.data), '会话列表应为数组')
      const conv = r.data.find(c => c.userId === ctx.userIds.merchant_c)
      assert(conv, '会话列表中未找到与 merchant_c 的会话')
      assert(typeof conv.lastMessage === 'string', 'lastMessage 应为字符串')
    })

    await test('查看消息历史，列表非空，含分页字段（#81）', async () => {
      const r = await get('/message/history', ctx.tokens.user_a, {
        targetUserId: ctx.userIds.merchant_c, size: 20
      })
      assertOk(r)
      assert(Array.isArray(r.data?.list) && r.data.list.length > 0, '消息历史为空')
      assert('hasMore' in r.data && 'nextCursor' in r.data, '缺少游标分页字段')
    })

    await test('消息历史游标翻页结果不重复（#103）', async () => {
      // 多发几条确保超过一页
      for (let i = 0; i < 4; i++) {
        await post('/message/send', {
          toUserId: ctx.userIds.merchant_c, content: `分页测试_${i}_${Date.now()}`
        }, ctx.tokens.user_a)
      }
      const page1 = await get('/message/history', ctx.tokens.user_a,
        { targetUserId: ctx.userIds.merchant_c, size: 3 })
      assertOk(page1)
      assert(page1.data?.hasMore === true, '发送多条消息后 hasMore 应为 true')
      const page2 = await get('/message/history', ctx.tokens.user_a, {
        targetUserId: ctx.userIds.merchant_c,
        lastId: page1.data.nextCursor,
        size: 3
      })
      assertOk(page2)
      const ids1 = new Set(page1.data.list.map(m => m.id))
      const overlap = page2.data.list.filter(m => ids1.has(m.id))
      assert(overlap.length === 0, `两页消息有重复 id: ${overlap.map(m => m.id)}`)
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 10：权限隔离（测试清单 #88-93）
// ════════════════════════════════════════════════════════════════
async function suitePermission() {
  await suite('10. 权限隔离', async () => {
    await test('无 token 访问 /booking/my → 非 200（#89）', async () => {
      assert((await get('/booking/my')).code !== 200, '期望鉴权失败')
    })

    await test('无 token 访问白名单 /user/login → 正常响应（#88）', async () => {
      assertOk(
        await post('/user/login', { phone: ACCOUNTS.user_a.phone, password: ACCOUNTS.user_a.password }),
        '白名单接口'
      )
    })

    await test('路径参数类型非法（字母 id）→ 返回 400（#105）', async () => {
      // 必须带 token，否则 Sa-Token 拦截器先返回 401，Spring 类型转换不会触发
      const r = await get('/merchant/not-a-number', ctx.tokens.user_a)
      assertFail(r, '非法路径参数')
      assert(r.code !== 401, `应为 400 类型错误，而非 401 鉴权错误，实际 code=${r.code}`)
    })

    await test('user_a 无法删除 merchant_c 的档期（#92）', async () => {
      assertFail(await del(`/schedule/${ctx.scheduleId}`, ctx.tokens.user_a), 'user_a 删档期')
    })

    await test('merchant_d 无法删除 merchant_c 的档期（#92）', async () => {
      assertFail(await del(`/schedule/${ctx.scheduleId}`, ctx.tokens.merchant_d), 'merchant_d 越权')
    })

    await test('user_a 无法调用 /booking/{id}/confirm（#93）', async () => {
      assertFail(
        await put(`/booking/${ctx.bookingId}/confirm`, undefined, ctx.tokens.user_a),
        'user_a 调 confirm'
      )
    })

    await test('merchant_c 可以删除自己的空闲档期（#91）', async () => {
      const date = futureDate(300)
      assertOk(
        await post('/schedule', { date, timeSlot: '07:00-08:00', bookType: 0, serviceType: 1 },
          ctx.tokens.merchant_c),
        '创建待删档期'
      )
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: date.slice(0, 7) })
      const s = lr.data?.find(s => s.date === date)
      assert(s, '找不到待删档期')
      assertOk(await del(`/schedule/${s.id}`, ctx.tokens.merchant_c), '删除空闲档期')
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 11：问卷（测试清单 #55-58）
// ════════════════════════════════════════════════════════════════
async function suiteQuestionnaire() {
  await suite('11. 问卷', async () => {
    let qId = null

    await test('merchant_c 创建问卷模板（#55）', async () => {
      assertOk(
        await post('/questionnaire', {
          title: '自动化测试问卷',
          questions: JSON.stringify([
            { id: 'q1', label: '你的 coser 名？', type: 'text', required: true },
            { id: 'q2', label: '偏好妆容风格？', type: 'radio',
              options: ['甜美', '酷飒', '古风'], required: false }
          ]),
          isRequired: 1
        }, ctx.tokens.merchant_c),
        '创建问卷'
      )
    })

    await test('查看商家的问卷模板列表（#56）', async () => {
      const r = await get(`/questionnaire/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.user_a)
      assertOk(r)
      assert(Array.isArray(r.data) && r.data.length > 0, '问卷列表为空')
      qId = r.data[0].id
    })

    await test('查看商家自己的问卷模板（/questionnaire/my）', async () => {
      const r = await get('/questionnaire/my', ctx.tokens.merchant_c)
      assertOk(r)
      assert(Array.isArray(r.data), '返回结构异常')
    })

    await test('预约时携带问卷答案（#57）', async () => {
      const date = futureDate(200)
      await post('/schedule', { date, timeSlot: '16:00-18:00', bookType: 0, serviceType: 1 },
        ctx.tokens.merchant_c)
      const lr = await get(`/schedule/merchant/${ctx.merchantIds.merchant_c}`,
        ctx.tokens.merchant_c, { month: date.slice(0, 7) })
      const s = lr.data?.find(sc => sc.date === date && sc.status === 0)
      if (!s) throw new Error('找不到问卷测试用档期')
      assertOk(
        await post('/booking', {
          scheduleId: s.id,
          remark: '问卷测试预约',
          questionnaireAnswer: JSON.stringify({ q1: 'AutoTester', q2: '酷飒' })
        }, ctx.tokens.user_b),
        '携带问卷答案预约'
      )
    })

    await test('删除问卷模板', async () => {
      if (!qId) throw new Error('qId 为空，前置测试失败')
      assertOk(await del(`/questionnaire/${qId}`, ctx.tokens.merchant_c), '删除问卷')
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Suite 12：聊天入口（BookingVO.merchantUserId + 首次会话）
// ════════════════════════════════════════════════════════════════
async function suiteChatEntry() {
  await suite('12. 聊天入口', async () => {
    await test('GET /booking/my 返回的 BookingVO 含 merchantUserId 字段', async () => {
      const r = await get('/booking/my', ctx.tokens.user_a, { size: 20 })
      assertOk(r)
      const bookings = r.data?.list ?? []
      assert(bookings.length > 0, '预约列表为空，无法验证 merchantUserId')
      assert('merchantUserId' in bookings[0], 'BookingVO 缺少 merchantUserId 字段')
      assert(typeof bookings[0].merchantUserId === 'number' && bookings[0].merchantUserId > 0,
        `merchantUserId 应为正整数，实际: ${bookings[0].merchantUserId}`)
    })

    await test('BookingVO.merchantUserId 与商家账号 userId 一致', async () => {
      const r = await get('/booking/my', ctx.tokens.user_a, { size: 20 })
      assertOk(r)
      const b = r.data?.list?.find(b => b.merchantId === ctx.merchantIds.merchant_c)
      assert(b, '找不到 user_a 与 merchant_c 的预约')
      assert(b.merchantUserId === ctx.userIds.merchant_c,
        `merchantUserId=${b.merchantUserId}，期望 ${ctx.userIds.merchant_c}`)
    })

    await test('GET /booking/received 商家侧 BookingVO 含 merchantUserId', async () => {
      const r = await get('/booking/received', ctx.tokens.merchant_c, { size: 20 })
      assertOk(r)
      const bookings = r.data?.list ?? []
      assert(bookings.length > 0, '商家收到的预约列表为空')
      assert('merchantUserId' in bookings[0], '商家侧 BookingVO 缺少 merchantUserId')
      assert(bookings[0].merchantUserId === ctx.userIds.merchant_c,
        `商家侧 merchantUserId=${bookings[0].merchantUserId}，期望 ${ctx.userIds.merchant_c}`)
    })

    await test('user_b 向 merchant_d 发第一条消息（全新会话）', async () => {
      assertOk(
        await post('/message/send', {
          toUserId: ctx.userIds.merchant_d, content: `聊天入口测试_${Date.now()}`
        }, ctx.tokens.user_b),
        '首次发消息'
      )
    })

    await test('发消息后 user_b 会话列表包含 merchant_d 的会话', async () => {
      const r = await get('/message/conversations', ctx.tokens.user_b)
      assertOk(r)
      assert(Array.isArray(r.data), '会话列表不是数组')
      const conv = r.data.find(c => c.userId === ctx.userIds.merchant_d)
      assert(conv, '未找到与 merchant_d 的会话')
      assert(conv.lastMessage?.length > 0, 'lastMessage 为空')
    })

    await test('merchant_d 会话列表包含来自 user_b 的会话', async () => {
      const r = await get('/message/conversations', ctx.tokens.merchant_d)
      assertOk(r)
      assert(r.data?.find(c => c.userId === ctx.userIds.user_b),
        'merchant_d 会话列表中未找到 user_b 的会话')
    })

    await test('查询未聊过的对象消息历史返回空列表而非报错', async () => {
      const r = await get('/message/history', ctx.tokens.merchant_c,
        { targetUserId: ctx.userIds.user_b, size: 20 })
      assert(r.code === 200, `空历史应返回 200，实际 code=${r.code}`)
      assert(Array.isArray(r.data?.list), '应返回 list 数组')
    })

    await test('user_b 与 merchant_d 的消息历史包含刚发送的消息', async () => {
      const r = await get('/message/history', ctx.tokens.user_b,
        { targetUserId: ctx.userIds.merchant_d, size: 20 })
      assertOk(r)
      assert(r.data?.list?.length > 0, '消息历史为空，首条消息未保存')
      assert('hasMore' in r.data && 'nextCursor' in r.data, '缺少游标分页字段')
    })

    await test('merchant_d 向 user_b 回复消息（双向对话）', async () => {
      assertOk(
        await post('/message/send', {
          toUserId: ctx.userIds.user_b, content: '商家回复测试消息'
        }, ctx.tokens.merchant_d),
        'merchant_d 回复'
      )
    })

    await test('user_b 收到回复后历史消息数 ≥ 2', async () => {
      const r = await get('/message/history', ctx.tokens.user_b,
        { targetUserId: ctx.userIds.merchant_d, size: 20 })
      assertOk(r)
      assert((r.data?.list?.length ?? 0) >= 2,
        `双向对话后消息数应 ≥ 2，实际 ${r.data?.list?.length}`)
    })

    await test('无 token 发消息返回非 200（#89）', async () => {
      const r = await post('/message/send', { toUserId: ctx.userIds.merchant_c, content: 'test' })
      assert(r.code !== 200, `鉴权应拦截，实际 code=${r.code}`)
    })
  })
}

// ════════════════════════════════════════════════════════════════
// Main
// ════════════════════════════════════════════════════════════════
async function main() {
  process.stdout.write(
    '\x1b[33m╔══════════════════════════════════════════╗\x1b[0m\n' +
    '\x1b[33m║  MHP 档期预约平台 — API 自动化测试       ║\x1b[0m\n' +
    '\x1b[33m╚══════════════════════════════════════════╝\x1b[0m\n'
  )
  process.stdout.write(`  目标: ${BASE}\n  时间: ${new Date().toLocaleString()}\n\n`)

  await preflight()

  try {
    await setup()
  } catch (e) {
    process.stderr.write(`\n\x1b[31m[Setup 失败，终止测试]\x1b[0m ${e.message}\n`)
    process.exit(1)
  }

  await suiteAuth()
  await suiteMerchant()
  await suiteSchedule()
  await suiteBooking()
  await suiteRush()
  await suiteSocial()
  await suiteReview()
  await suiteComplaint()
  await suiteMessage()
  await suitePermission()
  await suiteQuestionnaire()
  await suiteChatEntry()

  const total = passed + failed
  process.stdout.write('\n\x1b[33m═══ 测试结果 ═══\x1b[0m\n')
  process.stdout.write(
    `  总计 ${total}  ` +
    `通过 \x1b[32m${passed}\x1b[0m  ` +
    `失败 \x1b[31m${failed}\x1b[0m\n`
  )
  if (failures.length > 0) {
    process.stdout.write('\n  失败用例：\n')
    failures.forEach(f => {
      process.stdout.write(`    \x1b[31m✗\x1b[0m ${f.name}\n      └─ ${f.reason}\n`)
    })
  } else {
    process.stdout.write('\n  \x1b[32m所有用例通过 🎉\x1b[0m\n')
  }
  process.stdout.write('\n')
  process.exit(failed > 0 ? 1 : 0)
}

main().catch(e => {
  process.stderr.write(`\x1b[31m未捕获异常:\x1b[0m ${e.stack || e.message}\n`)
  process.exit(1)
})
