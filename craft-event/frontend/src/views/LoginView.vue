<script setup>
import { ref } from 'vue'

const emit = defineEmits(['login', 'error'])
const username = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  if (!username.value || !password.value) {
    emit('error', '请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    emit('login', username.value.trim(), password.value)
  } finally {
    loading.value = false
  }
}

function fill(u, p) {
  username.value = u
  password.value = p
}
</script>

<template>
  <div class="login-wrap">
    <div class="panel">
      <h2>登录</h2>
      <div class="grid" style="gap: 10px">
        <label class="field">
          用户名
          <input v-model="username" placeholder="ops / alice / bob" @keyup.enter="submit" />
        </label>
        <label class="field">
          密码
          <input v-model="password" type="password" placeholder="请输入密码" @keyup.enter="submit" />
        </label>
        <button :disabled="loading" @click="submit">登 录</button>
      </div>
      <h3>可登录的样例账号（点击自动填充）</h3>
      <div class="sample">
        <button class="ghost" @click="fill('ops', 'ops123')">运营 ops / ops123</button>
      </div>
      <div class="sample" style="margin-top: 8px">
        <button class="ghost" @click="fill('alice', 'alice123')">玩家 alice</button>
        <button class="ghost" @click="fill('bob', 'bob123')">玩家 bob</button>
      </div>
      <p class="muted" style="margin-bottom: 0">
        alice 的宝石（GEM）仅有 1 份，可用来验证“最后一份材料被并发合成”。
      </p>
    </div>
  </div>
</template>
