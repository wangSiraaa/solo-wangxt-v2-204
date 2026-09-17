<script setup>
import { ref, onMounted } from 'vue'
import { api, getSession, getToken, setAuth, clearAuth } from './lib/api.js'
import LoginView from './views/LoginView.vue'
import PlayerConsole from './views/PlayerConsole.vue'
import OperatorConsole from './views/OperatorConsole.vue'

const session = ref(getSession())
const toasts = ref([])
let toastSeq = 0

function pushToast(kind, message) {
  const id = ++toastSeq
  toasts.value.push({ id, kind, message })
  setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }, 4200)
}

async function doLogin(username, password) {
  const { data } = await api.login(username, password)
  setAuth(data.token, {
    username: data.username,
    displayName: data.displayName,
    role: data.role,
    expiresAt: data.expiresAt
  })
  session.value = getSession()
  pushToast('ok', `欢迎，${data.displayName}（${data.role === 'OPERATOR' ? '运营' : '玩家'}）`)
}

async function onLogin(username, password) {
  try {
    await doLogin(username, password)
  } catch (e) {
    pushToast('err', e.message || '登录失败')
  }
}

async function doLogout() {
  try {
    await api.logout()
  } catch {
    // 忽略登出失败，本地始终清除
  }
  clearAuth()
  session.value = null
}

onMounted(() => {
  if (getToken() && !session.value) {
    clearAuth()
  }
})
</script>

<template>
  <div>
    <header class="topbar" v-if="session">
      <h1>🎁 限时道具合成 · 操作台</h1>
      <div>
        <span class="who">
          <b>{{ session.displayName }}</b>
          <span class="badge" :class="session.role === 'OPERATOR' ? 'draft' : 'published'">
            {{ session.role === 'OPERATOR' ? '运营' : '玩家' }}
          </span>
          <span class="mono small">@{{ session.username }}</span>
        </span>
        <button class="ghost" @click="doLogout">退出登录</button>
      </div>
    </header>

    <LoginView v-if="!session" @login="onLogin" @error="(m) => pushToast('err', m)" />

    <main class="container" v-else>
      <PlayerConsole v-if="session.role === 'PLAYER'" @toast="pushToast" />
      <OperatorConsole v-else @toast="pushToast" />
    </main>

    <div class="toast">
      <div v-for="t in toasts" :key="t.id" class="t" :class="t.kind">{{ t.message }}</div>
    </div>
  </div>
</template>
