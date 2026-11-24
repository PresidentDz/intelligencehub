<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import { useSpaces } from '../stores/spaces'
import type { PageSummary } from '../types'
import { relativeTime, toast } from '../utils'

const auth = useAuth()
const spaces = useSpaces()
const tab = ref<'recent' | 'favorites'>('recent')
const recent = ref<PageSummary[]>([])
const favorites = ref<PageSummary[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    const [r, f] = await Promise.all([
      api.get<{ pages: PageSummary[] }>('/home/recent'),
      api.get<{ pages: PageSummary[] }>('/home/favorites'),
    ])
    recent.value = r.pages
    favorites.value = f.pages
    if (!r.pages.length && f.pages.length) tab.value = 'favorites'
  } catch (e) {
    toast(errorMessage(e), 'error')
  } finally {
    loading.value = false
  }
})

function greeting() {
  const h = new Date().getHours()
  return h < 6 ? '夜深了' : h < 12 ? '上午好' : h < 18 ? '下午好' : '晚上好'
}
</script>

<template>
  <div class="page-scroll">
    <div class="home">
      <h1 class="home-title">{{ greeting() }}，{{ auth.user?.name }}</h1>

      <section>
        <div class="tabs">
          <button :class="{ on: tab === 'recent' }" @click="tab = 'recent'">🕘 最近访问</button>
          <button :class="{ on: tab === 'favorites' }" @click="tab = 'favorites'">⭐ 我的收藏</button>
        </div>
        <div class="doc-list">
          <RouterLink v-for="p in tab === 'recent' ? recent : favorites" :key="p.id" class="doc-row"
                      :to="{ name: 'page', params: { spaceId: p.spaceId, pageId: p.id } }">
            <span class="page-icon">{{ p.icon || '📄' }}</span>
            <span class="doc-title ellipsis">{{ p.title || '无标题' }}</span>
            <span class="muted tiny ellipsis doc-space">{{ p.spaceIcon }} {{ p.spaceName }}</span>
            <span class="muted tiny doc-time">{{ relativeTime(tab === 'recent' ? p.visitedAt! : p.favoritedAt!) }}</span>
          </RouterLink>
          <p v-if="!loading && !(tab === 'recent' ? recent : favorites).length" class="empty">
            {{ tab === 'recent' ? '打开过的页面会出现在这里' : '在页面右上角点击 ☆ 即可收藏' }}
          </p>
        </div>
      </section>

      <section>
        <h2 class="section-title">知识空间</h2>
        <div class="space-grid">
          <RouterLink v-for="s in spaces.list" :key="s.id" class="space-card" :to="{ name: 'space', params: { spaceId: s.id } }">
            <span class="space-card-icon">{{ s.icon }}</span>
            <strong>{{ s.name }}</strong>
            <span class="muted small two-lines">{{ s.description || '暂无简介' }}</span>
            <span class="muted tiny">{{ s.pageCount ?? 0 }} 个页面 · {{ s.visibility === 'public' ? '🌐 公开' : '🔒 成员可见' }}</span>
          </RouterLink>
        </div>
      </section>
    </div>
  </div>
</template>
