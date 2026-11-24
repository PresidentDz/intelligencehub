<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import type { Comment } from '../types'
import { fullTime, relativeTime, toast } from '../utils'

const props = defineProps<{ pageId: number; canModerate: boolean }>()
const emit = defineEmits<{ close: [] }>()
const auth = useAuth()
const comments = ref<Comment[]>([])
const body = ref('')
const busy = ref(false)

async function load() {
  comments.value = (await api.get<{ comments: Comment[] }>(`/pages/${props.pageId}/comments`)).comments
}
onMounted(() => load().catch((e) => toast(errorMessage(e), 'error')))

async function submit() {
  if (!body.value.trim()) return
  busy.value = true
  try {
    await api.post(`/pages/${props.pageId}/comments`, { body: body.value })
    body.value = ''
    await load()
  } catch (e) {
    toast(errorMessage(e), 'error')
  } finally {
    busy.value = false
  }
}

async function remove(c: Comment) {
  if (!confirm('删除这条评论？')) return
  try {
    await api.del(`/comments/${c.id}`)
    await load()
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}
</script>

<template>
  <aside class="side-panel">
    <header class="panel-head"><h3>评论 {{ comments.length || '' }}</h3><button class="icon-btn" @click="emit('close')">✕</button></header>
    <div class="panel-body">
      <div v-for="c in comments" :key="c.id" class="comment">
        <div class="comment-head">
          <span class="avatar small">{{ c.author.slice(0, 1) }}</span>
          <strong>{{ c.author }}</strong>
          <span class="muted tiny" :title="fullTime(c.createdAt)">{{ relativeTime(c.createdAt) }}</span>
          <button v-if="c.userId === auth.user?.id || canModerate" class="link-btn tiny" @click="remove(c)">删除</button>
        </div>
        <p class="comment-body">{{ c.body }}</p>
      </div>
      <p v-if="!comments.length" class="muted pad">还没有评论</p>
    </div>
    <form class="comment-form" @submit.prevent="submit">
      <textarea v-model="body" rows="3" maxlength="3000" placeholder="发表评论，Ctrl+Enter 发送" @keydown.ctrl.enter="submit" />
      <button class="btn primary small" :disabled="busy || !body.trim()">发送</button>
    </form>
  </aside>
</template>
