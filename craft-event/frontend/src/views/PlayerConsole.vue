<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { api } from '../lib/api.js'

const emit = defineEmits(['toast'])

const tab = ref('craft')
const recipes = ref([])
const inventory = ref([])
const orders = ref([])
const ledger = ref([])
const holds = ref([])
const selectedVersion = ref(null)
const preview = ref(null)
const qty = ref(1)
const detail = ref(null)
const loading = ref(false)

let timer = null

function toast(kind, msg) { emit('toast', kind, msg) }

async function loadRecipes() {
  const { data } = await api.playerRecipes()
  recipes.value = data
  if (!selectedVersion.value && data.length) {
    await selectRecipe(data[0].versionId)
  }
}

async function selectRecipe(versionId) {
  selectedVersion.value = versionId
  const { data } = await api.preview(versionId)
  preview.value = data
}

async function refreshAll() {
  try {
    const [inv, ord, led, hld] = await Promise.all([
      api.inventory(), api.orders(), api.ledger(), api.holds()
    ])
    inventory.value = inv.data
    orders.value = ord.data
    ledger.value = led.data
    holds.value = hld.data
    if (selectedVersion.value) {
      const p = await api.preview(selectedVersion.value)
      preview.value = p.data
    }
  } catch (e) {
    toast('err', '刷新失败：' + e.message)
  }
}

async function doCraft() {
  if (!selectedVersion.value) return
  loading.value = true
  const clientRequestId = 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
  try {
    const { data, requestId } = await api.craft(selectedVersion.value, qty.value, clientRequestId)
    toast('ok', `合成单已预占：${data.orderNo}（请求幂等键 ${requestId.slice(0, 18)}…）`)
    await refreshAll()
  } catch (e) {
    toast('err', e.message)
  } finally {
    loading.value = false
  }
}

async function doComplete(orderNo) {
  try {
    await api.complete(orderNo)
    toast('ok', `合成完成，奖励已入账：${orderNo}`)
    await refreshAll()
    if (detail.value?.orderNo === orderNo) await openDetail(orderNo)
  } catch (e) {
    toast('err', e.message)
  }
}

async function doCancel(orderNo) {
  try {
    await api.cancel(orderNo)
    toast('info', `已取消并释放预占：${orderNo}`)
    await refreshAll()
    if (detail.value?.orderNo === orderNo) await openDetail(orderNo)
  } catch (e) {
    toast('err', e.message)
  }
}

async function openDetail(orderNo) {
  const { data } = await api.order(orderNo)
  detail.value = data
}

function closeDetail() { detail.value = null }

/**
 * 并发自检：同一时刻发出 N 个合成请求（各自独立 requestId）。
 * 对 alice 的 v1 秘银宝箱（需要唯一的 GEM），应当恰好 1 单成功、其余 409。
 */
const raceResult = ref(null)
async function raceTest(n = 6) {
  if (!selectedVersion.value) return
  raceResult.value = 'running'
  const versionId = selectedVersion.value
  const jobs = Array.from({ length: n }, () =>
    api.craft(versionId, 1, undefined).then(
      (r) => ({ ok: true, orderNo: r.data.orderNo }),
      (e) => ({ ok: false, status: e.status, message: e.message })
    )
  )
  const results = await Promise.all(jobs)
  const wins = results.filter((r) => r.ok)
  const loses = results.filter((r) => !r.ok)
  raceResult.value = {
    total: n,
    win: wins.length,
    lose: loses.length,
    winOrder: wins[0]?.orderNo,
    loseMessage: loses[0]?.message
  }
  toast(wins.length === 1 ? 'ok' : 'err',
    `并发自检完成：${wins.length} 单成功，${loses.length} 单被服务端拒绝`)
  await refreshAll()
}

const now = ref(new Date())
const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 19) : '—')

// 兼容 "yyyy-MM-dd HH:mm:ss[.SSS]" 与 ISO "yyyy-MM-ddTHH:mm:ss"
function toDate(s) {
  if (!s) return null
  const str = String(s).includes('T') ? String(s) : String(s).replace(' ', 'T')
  return new Date(str)
}

function remainText(o) {
  if (o.status !== 'HELD') return ''
  const until = toDate(o.completeAt)
  const expire = toDate(o.expireAt)
  const ms1 = until - now.value
  const ms2 = expire - now.value
  if (ms1 > 0) return `合成中，约 ${Math.ceil(ms1 / 1000)}s 后可领取`
  if (ms2 > 0) return `可领取，${Math.ceil(ms2 / 1000)}s 后超时释放`
  return '超时处理中'
}

const typeText = {
  INIT: '初始', HOLD: '预占', CONSUME: '消耗', RELEASE: '释放',
  PRODUCE: '产出', REVERSE_PRODUCE: '反向-回收', REVERSE_CONSUME: '反向-退还'
}
const typeClass = {
  HOLD: 'zero', CONSUME: 'neg', RELEASE: 'pos', PRODUCE: 'pos',
  REVERSE_PRODUCE: 'neg', REVERSE_CONSUME: 'pos'
}

onMounted(async () => {
  await loadRecipes().catch((e) => toast('err', e.message))
  await refreshAll()
  timer = setInterval(() => { now.value = new Date() }, 1000)
})
onUnmounted(() => timer && clearInterval(timer))
</script>

<template>
  <div class="tabs">
    <button :class="{ active: tab === 'craft' }" @click="tab = 'craft'">配方与合成预览</button>
    <button :class="{ active: tab === 'orders' }" @click="tab = 'orders'">我的合成单</button>
    <button :class="{ active: tab === 'inventory' }" @click="tab = 'inventory'">背包</button>
    <button :class="{ active: tab === 'ledger' }" @click="tab = 'ledger'">逐笔材料去向</button>
    <button class="ghost" @click="refreshAll">刷新</button>
  </div>

  <!-- ============ 配方与预览 ============ -->
  <div v-if="tab === 'craft'" class="grid cols-3">
    <div
      v-for="r in recipes" :key="r.versionId"
      class="card" :class="{ selected: r.versionId === selectedVersion }"
      @click="selectRecipe(r.versionId)"
    >
      <div class="row between">
        <b>{{ r.recipeName }}</b>
        <span class="badge" :class="r.status.toLowerCase()">v{{ r.versionNo }} · {{ r.status }}</span>
      </div>
      <p class="muted" style="margin: 6px 0">
        产出：{{ r.outputItemName }} × {{ r.outputQty }}
      </p>
      <div class="small muted mono">
        {{ fmt(r.eventStartsAt) }} ~ {{ fmt(r.eventEndsAt) }}
      </div>
      <div class="small muted">
        合成 {{ r.craftSeconds }}s · 预占超时 {{ r.timeoutSeconds }}s ·
        {{ r.autoComplete ? '到期自动完成' : '需手动领取' }}
      </div>
      <div style="margin-top: 8px">
        <span v-for="m in r.materials" :key="m.itemCode" class="badge" style="margin: 2px 4px 2px 0">
          {{ m.itemName }} ×{{ m.qty }}
        </span>
      </div>
    </div>
  </div>

  <div v-if="tab === 'craft' && preview" class="panel">
    <h2>
      合成预览 · {{ preview.outputItemName }} × {{ preview.outputQty }}
      <span class="badge draft">v{{ preview.versionNo }}</span>
      <span class="badge" :class="preview.eventOpen ? 'published' : 'err'">
        {{ preview.eventOpen ? '活动进行中（服务端时间）' : '活动未开放/已结束' }}
      </span>
      <span class="badge" :class="preview.sufficient ? 'published' : 'err'">
        {{ preview.sufficient ? '材料充足' : '材料不足（仍可尝试，服务端最终裁决）' }}
      </span>
    </h2>
    <table>
      <thead>
        <tr><th>材料</th><th>每份需要</th><th>持有总量</th><th>预占中</th><th>当前可用</th><th>结论</th></tr>
      </thead>
      <tbody>
        <tr v-for="m in preview.materials" :key="m.itemCode">
          <td>{{ m.itemName }} <span class="muted mono">{{ m.itemCode }}</span></td>
          <td>{{ m.need }}</td>
          <td>{{ m.totalQty }}</td>
          <td>{{ m.heldQty }}</td>
          <td>{{ m.availableQty }}</td>
          <td><span class="badge" :class="m.sufficient ? 'published' : 'err'">
            {{ m.sufficient ? '充足' : '不足' }}
          </span></td>
        </tr>
      </tbody>
    </table>
    <div class="form-inline" style="margin-top: 12px">
      <label class="field">合成份数
        <input v-model.number="qty" type="number" min="1" max="99" style="width: 110px" />
      </label>
      <button :disabled="loading || !preview.eventOpen" @click="doCraft">
        {{ loading ? '提交中…' : '发起合成（预占材料）' }}
      </button>
      <span class="muted">按钮置灰仅为体验；活动结束/材料不足由服务端判定，绕过前端同样会被拒绝。</span>
    </div>
    <div class="form-inline" style="margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--line)">
      <button class="warnbtn" :disabled="raceResult === 'running' || !preview.eventOpen"
              @click="raceTest(6)">
        {{ raceResult === 'running' ? '并发提交中…' : '并发自检：同时发起 6 单' }}
      </button>
      <span class="muted">
        6 个请求同刻发出（6 个不同 requestId）。材料仅够 1 份时，应恰好 1 单成功、5 单 409。
      </span>
      <span v-if="raceResult && raceResult !== 'running'" class="badge published">
        成功 {{ raceResult.win }} / 失败 {{ raceResult.lose }}，单号 {{ raceResult.winOrder }}
      </span>
    </div>
  </div>

  <!-- ============ 合成单 ============ -->
  <div v-if="tab === 'orders'" class="panel">
    <h2>我的合成单（{{ orders.length }}）</h2>
    <table>
      <thead>
        <tr>
          <th>单号</th><th>配方版本</th><th>产出</th><th>状态</th><th>倒计时</th>
          <th>预占/完成时间</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="o in orders" :key="o.orderNo">
          <td><span class="pill-link mono" @click="openDetail(o.orderNo)">{{ o.orderNo }}</span></td>
          <td><span class="mono">{{ o.recipeCode }}</span> v{{ o.snapshotVersion }}</td>
          <td>{{ o.outputItemName }} ×{{ o.outputQty }}</td>
          <td><span class="badge" :class="o.status">{{ o.status }}</span>
            <div v-if="o.cancelReason" class="muted small">{{ o.cancelReason === 'TIMEOUT' ? '超时取消' : '玩家取消' }}</div>
            <div v-if="o.status === 'REVOKED'" class="muted small">运营 {{ o.revokeOperator }} 撤销</div>
          </td>
          <td class="small">{{ remainText(o) }}</td>
          <td class="small muted mono">
            预占 {{ fmt(o.heldFrom) }}<br />
            可领 {{ fmt(o.completeAt) }}<br />
            超时 {{ fmt(o.expireAt) }}
          </td>
          <td>
            <button v-if="o.status === 'HELD'" style="margin-right: 6px" @click="doComplete(o.orderNo)">领取</button>
            <button v-if="o.status === 'HELD'" class="ghost" @click="doCancel(o.orderNo)">取消</button>
            <button v-else class="ghost" @click="openDetail(o.orderNo)">详情</button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- 订单明细抽屉（材料去向 + 流水） -->
  <div v-if="detail" class="panel" style="border-color: var(--brand)">
    <div class="row between">
      <h2>合成单详情 <span class="mono">{{ detail.orderNo }}</span></h2>
      <button class="ghost" @click="closeDetail">关闭</button>
    </div>
    <h3>逐笔材料去向</h3>
    <table>
      <thead><tr><th>材料</th><th>数量</th><th>状态</th><th>时间</th></tr></thead>
      <tbody>
        <tr v-for="h in detail.holds" :key="h.id || h.itemCode">
          <td>{{ h.itemName }} <span class="muted mono">{{ h.itemCode }}</span></td>
          <td>{{ h.qty }}</td>
          <td><span class="badge" :class="h.status">{{ h.status }}</span></td>
          <td class="small muted mono">
            预占 {{ fmt(h.createdAt) }}<template v-if="h.consumedAt"> · 消耗 {{ fmt(h.consumedAt) }}</template>
            <template v-if="h.releasedAt"> · 释放 {{ fmt(h.releasedAt) }}</template>
            <template v-if="h.reversedAt"> · 反向 {{ fmt(h.reversedAt) }}</template>
          </td>
        </tr>
      </tbody>
    </table>
    <h3>关联流水</h3>
    <table>
      <thead><tr><th>#</th><th>类型</th><th>道具</th><th>总量变化</th><th>占用变化</th><th>余额(总/占)</th><th>时间</th><th>备注</th></tr></thead>
      <tbody>
        <tr v-for="e in detail.ledger" :key="e.id">
          <td class="mono muted">{{ e.id }}</td>
          <td>{{ typeText[e.changeType] || e.changeType }}</td>
          <td>{{ e.itemName }}</td>
          <td :class="typeClass[e.changeType]">{{ e.qtyChange > 0 ? '+' + e.qtyChange : e.qtyChange }}</td>
          <td :class="e.heldDelta > 0 ? 'pos' : e.heldDelta < 0 ? 'neg' : ''">
            {{ e.heldDelta > 0 ? '+' + e.heldDelta : e.heldDelta }}
          </td>
          <td class="mono">{{ e.balanceTotal }} / {{ e.balanceHeld }}</td>
          <td class="small muted mono">{{ fmt(e.createdAt) }}</td>
          <td class="small muted">{{ e.remark }}<span v-if="e.reversalOf">（冲正 #{{ e.reversalOf }}）</span></td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- ============ 背包 ============ -->
  <div v-if="tab === 'inventory'" class="panel">
    <h2>背包（总量 = 可用 + 预占）</h2>
    <table>
      <thead><tr><th>道具</th><th>持有总量</th><th>预占中</th><th>可用</th></tr></thead>
      <tbody>
        <tr v-for="i in inventory" :key="i.itemCode">
          <td>{{ i.itemName }} <span class="muted mono">{{ i.itemCode }}</span></td>
          <td>{{ i.totalQty }}</td>
          <td>{{ i.heldQty }}</td>
          <td><b :class="i.availableQty > 0 ? 'pos' : 'zero'">{{ i.availableQty }}</b></td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- ============ 流水 ============ -->
  <div v-if="tab === 'ledger'" class="panel">
    <h2>逐笔材料去向（只追加流水，最近 {{ ledger.length }} 笔）</h2>
    <table>
      <thead>
        <tr><th>#</th><th>类型</th><th>道具</th><th>总量变化</th><th>占用变化</th>
        <th>余额(总/占)</th><th>关联单据</th><th>时间</th><th>备注</th></tr>
      </thead>
      <tbody>
        <tr v-for="e in ledger" :key="e.id">
          <td class="mono muted">{{ e.id }}</td>
          <td>{{ typeText[e.changeType] || e.changeType }}</td>
          <td>{{ e.itemName }} <span class="muted mono">{{ e.itemCode }}</span></td>
          <td :class="typeClass[e.changeType]">{{ e.qtyChange > 0 ? '+' + e.qtyChange : e.qtyChange }}</td>
          <td :class="e.heldDelta > 0 ? 'pos' : e.heldDelta < 0 ? 'neg' : ''">
            {{ e.heldDelta > 0 ? '+' + e.heldDelta : e.heldDelta }}
          </td>
          <td class="mono">{{ e.balanceTotal }} / {{ e.balanceHeld }}</td>
          <td><span v-if="e.refType === 'ORDER'" class="pill-link mono" @click="tab = 'orders'; openDetail(e.refNo)">
            {{ e.refNo }}
          </span><span v-else class="muted mono">{{ e.refNo }}</span></td>
          <td class="small muted mono">{{ fmt(e.createdAt) }}</td>
          <td class="small muted">{{ e.remark }}<span v-if="e.reversalOf">（冲正 #{{ e.reversalOf }}）</span></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
