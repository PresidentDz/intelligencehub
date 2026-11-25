<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { useSpaces } from '../stores/spaces'
import { api, errorMessage } from '../api'
import { toast } from '../utils'
import { drag } from './treeActions'
import PageTree from './PageTree.vue'
import SearchDialog from './SearchDialog.vue'
import SpaceDialog from './SpaceDialog.vue'
import Modal from './Modal.vue'

const auth = useAuth()
const spaces = useSpaces()
const route = useRoute()
const router = useRouter()
const searchOpen = ref(false)
const createOpen = ref(false)
const expandedSpaceId = ref<number | null>(null)
const spaceDropOverId = ref<number | null>(null)
const pendingMove = ref<{ pageId: number; sourceSpaceId: number; targetSpaceId: number } | null>(null)
const moving = ref(false)

const spaceId = computed(() => (route.params.spaceId ? Number(route.params.spaceId) : null))
const currentSpace = computed(() => spaces.list.find((s) => s.id === spaceId.value) ?? null)

onMounted(() => {
  spaces.loadList().catch((e) => toast(errorMessage(e), 'error'))
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

watch(spaceId, (id) => {
  expandedSpaceId.value = id
  if (id != null && spaces.treeSpaceId !== id) spaces.loadTree(id).catch((e) => toast(errorMessage(e), 'error'))
}, { immediate: true })

function toggleSpace(id: number) {
  if (spaceId.value === id && expandedSpaceId.value === id) {
    expandedSpaceId.value = null
    return
  }
  expandedSpaceId.value = id
  if (spaceId.value !== id) router.push({ name: 'space', params: { spaceId: id } })
}

function onSpaceDragOver(space: typeof spaces.list[number], event: DragEvent) {
  if (drag.id == null || spaces.treeSpaceId === space.id || space.myRole === 'reader') return
  event.preventDefault()
  spaceDropOverId.value = space.id
}

function onSpaceDragLeave(spaceId: number, event: DragEvent) {
  if (event.relatedTarget instanceof Node && (event.currentTarget as HTMLElement).contains(event.relatedTarget)) return
  if (spaceDropOverId.value === spaceId) spaceDropOverId.value = null
}

function onSpaceDrop(spaceId: number, event: DragEvent) {
  event.preventDefault()
  spaceDropOverId.value = null
  const pageId = drag.id
  const sourceSpaceId = spaces.treeSpaceId
  drag.id = null
  drag.overId = null
  drag.zone = null
  if (pageId == null || sourceSpaceId == null || sourceSpaceId === spaceId) return
  pendingMove.value = { pageId, sourceSpaceId, targetSpaceId: spaceId }
}

async function confirmMove() {
  const move = pendingMove.value
  if (!move || moving.value) return
  moving.value = true
  const currentPageId = Number(route.params.pageId)
  const moveIncludesCurrentPage = currentPageId > 0 && spaces.isAncestor(move.pageId, currentPageId)
  try {
    await api.post(`/pages/${move.pageId}/move`, { targetSpaceId: move.targetSpaceId, parentId: null })
    pendingMove.value = null
    if (moveIncludesCurrentPage) {
      await router.replace({ name: 'page', params: { spaceId: move.targetSpaceId, pageId: currentPageId } })
    } else if (spaces.treeSpaceId === move.sourceSpaceId) {
      await spaces.loadTree(move.sourceSpaceId)
    }
    toast('文章及其子页面已移动到目标空间')
  } catch (e) {
    toast(errorMessage(e), 'error')
  } finally {
    moving.value = false
  }
}

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
                      :class="{ active: s.id === spaceId, 'space-drop-target': spaceDropOverId === s.id }" :aria-expanded="expandedSpaceId === s.id"
                      @dragover="onSpaceDragOver(s, $event)" @dragleave="onSpaceDragLeave(s.id, $event)" @drop="onSpaceDrop(s.id, $event)"
                      @click.prevent="toggleSpace(s.id)">
            <span class="space-icon">{{ s.icon }}</span><span class="ellipsis">{{ s.name }}</span>
            <span v-if="s.visibility === 'private'" class="lock" title="仅成员可见">🔒</span>
          </RouterLink>
          <PageTree v-if="expandedSpaceId === s.id && currentSpace" :space="currentSpace" />
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
    <Modal v-if="pendingMove" title="确认跨空间移动" @close="pendingMove = null">
      <div class="modal-body">
        <p>确定将「{{ spaces.pages.find((page) => page.id === pendingMove?.pageId)?.title || '无标题' }}」及其所有子页面移动到「{{ spaces.list.find((space) => space.id === pendingMove?.targetSpaceId)?.name }}」吗？</p>
        <p class="muted tiny">移动后，这些页面将从当前空间移除，并出现在目标空间根目录。</p>
      </div>
      <footer class="modal-foot">
        <button class="btn" :disabled="moving" @click="pendingMove = null">取消</button>
        <button class="btn primary" :disabled="moving" @click="confirmMove">{{ moving ? '移动中…' : '确认移动' }}</button>
      </footer>
    </Modal>
  </div>
</template>
