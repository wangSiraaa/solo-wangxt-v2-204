<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../lib/api.js'

const emit = defineEmits(['toast'])
const tab = ref('recipes')

const recipes = ref([])
const items = ref([])
const orders = ref([])
const exceptions = ref([])
const showAllEx = ref(false)
const creatingFor = ref(null) // recipeCode 基准
const publishing = ref(null)

const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 19) : '—')
function toast(kind, msg) { emit('toast', kind, msg) }

function defaultWindow(offsetHours = 1) {
  const pad = (n) => String(n).padStart(2, '0')
  const d = new Date(Date.now() + offsetHours * 3600000)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// 新版本草稿表单（已发布版本只能照此新建版本后修改）
const blankDraft = (base) => ({
  recipeCode: base?.recipeCode || '',
  recipeName: base?.recipeName || '',
  outputItemCode: base?.outputItemCode || '',
  outputQty: base?.outputQty || 1,
  eventStartsAt: defaultWindow(0),
  eventEndsAt: defaultWindow(1),
  autoComplete: base ? base.autoComplete : true,
  craftSeconds: base?.craftSeconds ?? 5,
  timeoutSeconds: base?.timeoutSeconds ?? 15,
  changelog: '',
  materials: (base?.materials || []).map((m) => ({ itemCode: m.itemCode, qty: m.qty }))
})
const draft = ref(null)

async function loadRecipes() {
  recipes.value = (await api.opRecipes(true)).data
}
async function loadOrders() {
  orders.value = (await api.opOrders()).data
}
async function loadExceptions() {
  exceptions.value = (await api.opExceptions(showAllEx.value)).data
}

async function refresh() {
  try {
    await Promise.all([loadRecipes(), loadOrders(), loadExceptions()])
  } catch (e) {
    toast('err', e.message)
  }
}

function groupByRecipe(list) {
  const map = new Map()
  for (const r of list) {
    const key = r.recipeCode
    if (!map.has(key)) map.set(key, { code: key, name: r.recipeName, versions: [] })
    map.get(key).versions.push(r)
  }
  return [...map.values()]
}

function startNewVersion(base) {
  // 基于“当前已发布版本”复制一份草稿；未发布过的配方组不能改，只能新建
  const published = base.versions.find((v) => v.status === 'PUBLISHED')
  if (!published) {
    toast('err', '该配方尚无已发布版本，无法基于其创建新版本')
    return
  }
  draft.value = blankDraft(published)
  creatingFor.value = base.code
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function startNewRecipe() {
  draft.value = blankDraft(null)
  draft.recipeCode = 'R' + String(Math.floor(Math.random() * 900) + 100)
  draft.recipeName = ''
  draft.outputItemCode = items.value[0]?.code
  draft.materials = [{ itemCode: items.value[0]?.code, qty: 1 }]
  creatingFor.value = '__new__'
}

function addMaterialRow() {
  draft.value.materials.push({ itemCode: items.value[0]?.code, qty: 1 })
}
function removeMaterialRow(i) {
  draft.value.materials.splice(i, 1)
}

async function submitDraft() {
  try {
    const { data } = await api.opCreateDraft(draft.value)
    toast('ok', `草稿已创建：${data.recipeCode} v${data.versionNo}（versionId=${data.versionId}），发布后才对玩家生效`)
    draft.value = null
    creatingFor.value = null
    await loadRecipes()
  } catch (e) {
    toast('err', e.message)
  }
}

async function publish(versionId) {
  publishing.value = versionId
  try {
    await api.opPublish(versionId)
    toast('ok', `版本 ${versionId} 已发布；旧版本进入 SUPERSEDED，已开始的合成仍按旧版本完成`)
    await loadRecipes()
  } catch (e) {
    toast('err', e.message)
  } finally {
    publishing.value = null
  }
}

async function revoke(o) {
  if (!confirm(`确认撤销合成单 ${o.orderNo}？\n系统将尝试回收产出并退还材料；若产出已被使用会进入异常清单，不会自动扣负。`)) return
  try {
    const { data } = await api.opRevoke(o.orderNo)
    if (data.reversed) {
      toast('ok', `已生成反向流水并退还材料：${o.orderNo}`)
    } else {
      toast('err', `无法回收（材料已使用），已进入异常清单 ${data.exceptionNo}`)
    }
    await refresh()
  } catch (e) {
    toast('err', e.message)
  }
}

async function resolveEx(ex) {
  const remark = prompt(`异常 ${ex.exceptionNo} 的线下处理备注：`, '已线下处理')
  if (remark === null) return
  try {
    await api.opResolve(ex.exceptionNo, remark)
    toast('ok', `异常 ${ex.exceptionNo} 已关闭`)
    await loadExceptions()
  } catch (e) {
    toast('err', e.message)
  }
}

const statusBadge = (s) => ({ COMPLETED: 'published', HELD: 'draft', CANCELLED: 'superseded', REVOKED: 'REVOKED' }[s] || s)

onMounted(async () => {
  items.value = (await api.opItems()).data
  await refresh()
})
</script>

<template>
  <div class="tabs">
    <button :class="{ active: tab === 'recipes' }" @click="tab = 'recipes'">配方版本</button>
    <button :class="{ active: tab === 'orders' }" @click="tab = 'orders'">全部合成单</button>
    <button :class="{ active: tab === 'exceptions' }" @click="tab = 'exceptions'">
      撤销异常清单 <span v-if="exceptions.filter(e => e.status === 'OPEN').length" class="badge err">
        {{ exceptions.filter(e => e.status === 'OPEN').length }}
      </span>
    </button>
    <button class="ghost" @click="refresh">刷新</button>
  </div>

  <!-- 草稿编辑面板 -->
  <div v-if="draft" class="panel" style="border-color: var(--brand)">
    <h2>{{ creatingFor === '__new__' ? '创建新配方（首版草稿）' : `基于 ${creatingFor} 已发布版本创建新版本草稿` }}</h2>
    <div class="grid cols-3">
      <label class="field">配方编码
        <input v-model="draft.recipeCode" :disabled="creatingFor !== '__new__'" />
      </label>
      <label class="field">配方名称
        <input v-model="draft.recipeName" :disabled="creatingFor !== '__new__'" />
      </label>
      <label class="field">产出道具
        <select v-model="draft.outputItemCode">
          <option v-for="i in items" :key="i.id" :value="i.code">{{ i.name }} ({{ i.code }})</option>
        </select>
      </label>
      <label class="field">产出数量
        <input v-model.number="draft.outputQty" type="number" min="1" />
      </label>
      <label class="field">活动开始（yyyy-MM-dd HH:mm:ss）
        <input v-model="draft.eventStartsAt" />
      </label>
      <label class="field">活动结束
        <input v-model="draft.eventEndsAt" />
      </label>
      <label class="field">合成耗时(秒)
        <input v-model.number="draft.craftSeconds" type="number" min="1" />
      </label>
      <label class="field">预占超时(秒，须大于耗时)
        <input v-model.number="draft.timeoutSeconds" type="number" />
      </label>
      <label class="field">完成方式
        <select v-model="draft.autoComplete">
          <option :value="true">到期自动完成</option>
          <option :value="false">玩家手动领取（超时释放）</option>
        </select>
      </label>
    </div>

    <h3>材料（版本快照，发布后不可变）</h3>
    <div v-for="(m, i) in draft.materials" :key="i" class="form-inline" style="margin-bottom: 6px">
      <select v-model="m.itemCode">
        <option v-for="it in items" :key="it.id" :value="it.code">{{ it.name }} ({{ it.code }})</option>
      </select>
      <input v-model.number="m.qty" type="number" min="1" style="width: 100px" />
      <button class="ghost" @click="removeMaterialRow(i)">删除</button>
    </div>
    <button class="ghost" @click="addMaterialRow">+ 增加材料</button>

    <h3>变更说明</h3>
    <input v-model="draft.changelog" style="width: 100%" placeholder="例如：活动第二轮，材料 GEM 需求从 1 调整为 2" />

    <div style="margin-top: 14px">
      <button @click="submitDraft">保存为草稿</button>
      <button class="ghost" style="margin-left: 8px" @click="draft = null">取消</button>
      <span class="muted" style="margin-left: 10px">草稿不影响线上；发布时旧 PUBLISHED 原子切换为 SUPERSEDED。</span>
    </div>
  </div>

  <!-- ============ 配方版本 ============ -->
  <template v-if="tab === 'recipes'">
    <div class="panel">
      <div class="row between">
        <h2>配方版本时间线</h2>
        <button @click="startNewRecipe">+ 新建配方</button>
      </div>
      <p class="muted">规则：已发布版本不可修改；变更只能创建新版本再发布。已开始的合成绑定版本快照，按预占时版本完成。</p>
    </div>

    <div v-for="g in groupByRecipe(recipes)" :key="g.code" class="panel">
      <div class="row between">
        <h2>{{ g.name }} <span class="muted mono">{{ g.code }}</span></h2>
        <button class="ghost" @click="startNewVersion(g)">基于已发布版创建新版本</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>版本</th><th>状态</th><th>产出</th><th>材料快照</th>
            <th>活动窗口</th><th>耗时/超时</th><th>变更说明</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in [...g.versions].sort((a,b) => b.versionNo - a.versionNo)" :key="v.versionId">
            <td><b>v{{ v.versionNo }}</b><div class="muted small mono">id={{ v.versionId }}</div></td>
            <td><span class="badge" :class="v.status.toLowerCase()">{{ v.status }}</span></td>
            <td>{{ v.outputItemName }} ×{{ v.outputQty }}</td>
            <td>
              <span v-for="m in v.materials" :key="m.itemCode" class="badge" style="margin: 2px 4px 2px 0">
                {{ m.itemName }}×{{ m.qty }}
              </span>
            </td>
            <td class="small muted mono">{{ fmt(v.eventStartsAt) }}<br />~ {{ fmt(v.eventEndsAt) }}</td>
            <td class="small">{{ v.craftSeconds }}s / {{ v.timeoutSeconds }}s<br />
              <span class="muted">{{ v.autoComplete ? '自动完成' : '手动领取' }}</span>
            </td>
            <td class="small muted">{{ v.changelog }}</td>
            <td>
              <button v-if="v.status === 'DRAFT'" :disabled="publishing === v.versionId"
                      @click="publish(v.versionId)">发布</button>
              <span v-else-if="v.status === 'PUBLISHED'" class="muted small">生效中</span>
              <span v-else class="muted small">已被取代</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </template>

  <!-- ============ 全部合成单 ============ -->
  <div v-if="tab === 'orders'" class="panel">
    <h2>全部合成单（最近 {{ orders.length }} 单）</h2>
    <table>
      <thead>
        <tr>
          <th>单号</th><th>玩家</th><th>配方版本</th><th>产出</th><th>状态</th>
          <th>时间线</th><th>幂等键</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="o in orders" :key="o.orderNo">
          <td class="mono small">{{ o.orderNo }}</td>
          <td class="mono">#{{ o.accountId }}</td>
          <td class="small mono">v{{ o.snapshotVersion }} (id={{ o.versionId }})</td>
          <td class="small">#item{{ o.outputItemId }} ×{{ o.outputQty }}</td>
          <td><span class="badge" :class="statusBadge(o.status)">{{ o.status }}</span>
            <div v-if="o.cancelReason" class="muted small">{{ o.cancelReason }}</div>
            <div v-if="o.revokeOperator" class="muted small">by {{ o.revokeOperator }}</div>
          </td>
          <td class="small muted mono">
            预占 {{ fmt(o.heldFrom) }}<br />
            <template v-if="o.completedAt">完成 {{ fmt(o.completedAt) }}</template>
            <template v-else-if="o.cancelledAt">取消 {{ fmt(o.cancelledAt) }}</template>
            <template v-else>可领 {{ fmt(o.completeAt) }} · 超时 {{ fmt(o.expireAt) }}</template>
            <template v-if="o.revokedAt"><br />撤销 {{ fmt(o.revokedAt) }}</template>
          </td>
          <td class="small muted mono">{{ o.requestId }}</td>
          <td>
            <button v-if="o.status === 'COMPLETED'" class="danger" @click="revoke(o)">撤销奖励</button>
            <span v-else class="muted small">—</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- ============ 异常清单 ============ -->
  <div v-if="tab === 'exceptions'" class="panel">
    <div class="row between">
      <h2>撤销异常清单（产出材料已被使用，无法自动反向）</h2>
      <label class="row small muted">
        <input type="checkbox" :checked="showAllEx" @change="showAllEx = !showAllEx; loadExceptions()" />
        显示已处理
      </label>
    </div>
    <table>
      <thead>
        <tr><th>异常号</th><th>合成单</th><th>玩家</th><th>原因</th><th>缺口</th><th>状态</th><th>登记人/时间</th><th>处理</th></tr>
      </thead>
      <tbody>
        <tr v-for="e in exceptions" :key="e.exceptionNo">
          <td class="mono small">{{ e.exceptionNo }}</td>
          <td class="mono small">{{ e.orderNo }}</td>
          <td class="mono">#{{ e.accountId }}</td>
          <td class="small">{{ e.reason }}</td>
          <td class="small">item#{{ e.missingItemId }} 缺 {{ e.missingQty }}</td>
          <td><span class="badge" :class="e.status === 'OPEN' ? 'err' : 'published'">{{ e.status }}</span></td>
          <td class="small muted mono">{{ e.createdBy }}<br />{{ fmt(e.createdAt) }}
            <template v-if="e.resolvedAt"><br />{{ e.resolvedBy }} · {{ fmt(e.resolvedAt) }}<br />{{ e.remark }}</template>
          </td>
          <td><button v-if="e.status === 'OPEN'" class="warnbtn" @click="resolveEx(e)">标记已处理</button></td>
        </tr>
        <tr v-if="!exceptions.length"><td colspan="8" class="muted">暂无异常</td></tr>
      </tbody>
    </table>
  </div>
</template>
