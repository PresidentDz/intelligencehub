<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { useSpaces } from '../stores/spaces'
import { errorMessage } from '../api'
import { toast } from '../utils'
import PageTree from './PageTree.vue'
import SearchDialog from './SearchDialog.vue'
import SpaceDialog from './SpaceDialog.vue'

const auth = useAuth()
const spaces = useSpaces()
const route = useRoute()
const router = useRouter()
const searchOpen = ref(false)
const createOpen = ref(false)

const spaceId = computed(() => (route.params.spaceId ? Number(route.params.spaceId) : null))
const currentSpace = computed(() => spaces.list.find((s) => s.id === spaceId.value) ?? null)

onMounted(() => {
  spaces.loadList().catch((e) => toast(errorMessage(e), 'error'))
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

watch(spaceId, (id) => {
  if (id != null && spaces.treeSpaceId !== id) spaces.loadTree(id).catch((e) => toast(errorMessage(e), 'error'))
}, { immediate: true })

function onKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    searchOpen.value = true
  }
}

async function logout() {
  await auth.logout()
  router.replace({ name: 'login' })
}

function onSpaceCreated(id: number) {
  createOpen.value = false
  spaces.loadList()
  router.push({ name: 'space', params: { spaceId: id } })
}
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="sidebar-top">
        <RouterLink to="/" class="brand small"><span class="brand-mark">知</span>知序</RouterLink>
        <button class="search-trigger" @click="searchOpen = true">
          <span>🔍 搜索</span><kbd>Ctrl K</kbd>
        </button>
        <RouterLink to="/" class="nav-item" exact-active-class="active">🏠 首页</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin" class="nav-item" active-class="active">⚙️ 成员管理</RouterLink>
      </div>

      <div class="sidebar-section">
        <div class="section-head">
          <span>知识空间</span>
          <button class="icon-btn" title="新建知识空间" @click="createOpen = true">＋</button>
        </div>
        <template v-for="s in spaces.list" :key="s.id">
          <RouterLink :to="{ name: 'space', params: { spaceId: s.id } }" class="nav-item space-item"
                      :class="{ active: s.id === spaceId }">
            <span class="space-icon">{{ s.icon }}</span><span class="ellipsis">{{ s.name }}</span>
            <span v-if="s.visibility === 'private'" class="lock" title="仅成员可见">🔒</span>
          </RouterLink>
          <PageTree v-if="s.id === spaceId && currentSpace" :space="currentSpace" />
        </template>
        <p v-if="!spaces.list.length" class="muted tiny pad">还没有可访问的空间</p>
      </div>

      <div class="sidebar-user">
        <span class="avatar">{{ auth.user?.name.slice(0, 1) }}</span>
        <div class="user-meta">
          <strong>{{ auth.user?.name }}</strong>
          <span class="muted tiny">{{ auth.user?.department || auth.user?.username }}</span>
        </div>
        <button class="btn ghost small" @click="logout">退出</button>
      </div>
    </aside>

    <main class="main">
      <RouterView :key="route.params.pageId ? `p${route.params.pageId}` : route.fullPath" />
    </main>

    <SearchDialog v-if="searchOpen" @close="searchOpen = false" />
    <SpaceDialog v-if="createOpen" @close="createOpen = false" @saved="onSpaceCreated" />
  </div>
</template>
