<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import type { PageSummary } from '../types'
import { highlight, relativeTime } from '../utils'
import Modal from './Modal.vue'

const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const q = ref('')
const results = ref<PageSummary[]>([])
const selected = ref(0)
const loading = ref(false)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let seq = 0

watch(q, (value) => {
  clearTimeout(timer)
  timer = setTimeout(() => run(value), 200)
})
onBeforeUnmount(() => clearTimeout(timer))

async function run(value: string) {
  const mine = ++seq
  if (!value.trim()) {
    results.value = []
    return
  }
  loading.value = true
  try {
    const data = await api.get<{ results: PageSummary[] }>(`/search?q=${encodeURIComponent(value)}`)
    if (mine !== seq) return
    results.value = data.results
    selected.value = 0
    error.value = ''
  } catch (e) {
    if (mine === seq) error.value = errorMessage(e)
  } finally {
    if (mine === seq) loading.value = false
  }
}

function open(r: PageSummary) {
  emit('close')
  router.push({ name: 'page', params: { spaceId: r.spaceId, pageId: r.id } })
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    selected.value = Math.min(selected.value + 1, results.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    selected.value = Math.max(selected.value - 1, 0)
  } else if (e.key === 'Enter' && results.value[selected.value]) {
    open(results.value[selected.value])
  }
}
</script>

<template>
  <Modal wide @close="emit('close')">
    <div class="search-box">
      <span>🔍</span>
      <input v-model="q" autofocus placeholder="搜索所有可访问空间中的页面标题和正文" @keydown="onKey" />
      <kbd>Esc</kbd>
    </div>
    <div class="search-results">
      <p v-if="error" class="form-error pad">{{ error }}</p>
      <p v-else-if="!q.trim()" class="muted pad">输入关键词开始搜索，↑↓ 选择，回车打开</p>
      <p v-else-if="!loading && !results.length" class="muted pad">没有找到相关页面</p>
      <button v-for="(r, i) in results" :key="r.id" class="search-hit" :class="{ selected: i === selected }"
              @mouseenter="selected = i" @click="open(r)">
        <div class="hit-title">
          <span>{{ r.icon || '📄' }}</span>
          <strong><template v-for="(part, j) in highlight(r.title || '无标题', q)" :key="j"><mark v-if="part.hit">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template></strong>
        </div>
        <div v-if="r.excerpt" class="hit-excerpt">
          <template v-for="(part, j) in highlight(r.excerpt, q)" :key="j"><mark v-if="part.hit">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template>
        </div>
        <div class="hit-meta muted tiny">{{ r.spaceIcon }} {{ r.spaceName }} · {{ r.updatedBy }} 更新于 {{ relativeTime(r.updatedAt) }}</div>
      </button>
    </div>
  </Modal>
</template>
