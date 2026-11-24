<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { errorMessage } from '../api'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  busy.value = true
  error.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/'
    router.replace(redirect)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <aside class="login-story">
      <div class="brand"><span class="brand-mark">知</span>知序 · 团队知识库</div>
      <div class="story-copy">
        <h1>把团队经验，<br />留在自己的地方。</h1>
        <p>按知识空间组织文档，目录层层展开；输入 / 即可插入任意内容块，编辑自动保存。</p>
      </div>
      <div class="story-stats">
        <div><strong>知识空间</strong><span>按团队和主题分区</span></div>
        <div><strong>成员权限</strong><span>管理、编辑、阅读三级</span></div>
        <div><strong>版本历史</strong><span>每次修改都能找回</span></div>
      </div>
    </aside>
    <section class="login-form-wrap">
      <form class="login-form" @submit.prevent="submit">
        <h2>登录知识库</h2>
        <p class="muted">使用团队账号继续</p>
        <label class="field"><span>账号</span><input v-model="username" autocomplete="username" required autofocus placeholder="输入账号" /></label>
        <label class="field"><span>密码</span><input v-model="password" type="password" autocomplete="current-password" required placeholder="输入密码" /></label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="btn primary block" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
      </form>
    </section>
  </div>
</template>
