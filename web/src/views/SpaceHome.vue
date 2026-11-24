<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSpaces } from '../stores/spaces'
import { SPACE_ROLE_LABEL } from '../types'
import { createPage } from '../components/treeActions'
import ImportDialog from '../components/ImportDialog.vue'
import { errorMessage } from '../api'
import { toast } from '../utils'
import { exportZip } from '../transfer/exporter'

const props = defineProps<{ spaceId: string }>()
const spaces = useSpaces()
const router = useRouter()
const importOpen = ref(false)
const exporting = ref(false)
const id = computed(() => Number(props.spaceId))

async function exportSpace() {
  if (!space.value || exporting.value) return
  exporting.value = true
  try {
    const n = await exportZip(space.value.id, null, space.value.name, () => {})
    toast(`已导出 ${n} 个页面`)
  } catch (e) {
    toast(errorMessage(e), 'error')
  } finally {
    exporting.value = false
  }
}
const space = computed(() => spaces.list.find((s) => s.id === id.value))
const roots = computed(() => (spaces.treeSpaceId === id.value ? spaces.tree() : []))
</script>

<template>
  <div class="page-scroll">
    <div v-if="space" class="space-home">
      <div class="space-hero">
        <span class="space-hero-icon">{{ space.icon }}</span>
        <div>
          <h1>{{ space.name }}</h1>
          <p class="muted">{{ space.description || '暂无简介' }}</p>
          <p class="muted tiny">
            {{ space.visibility === 'public' ? '🌐 所有人可阅读' : '🔒 仅成员可见' }} · 我的权限：{{ SPACE_ROLE_LABEL[space.myRole] }}
          </p>
        </div>
        <div class="hero-actions">
          <button v-if="space.myRole !== 'reader'" class="btn primary" @click="createPage(router, space.id, null)">＋ 新建页面</button>
          <button v-if="space.myRole !== 'reader'" class="btn" @click="importOpen = true">导入 Markdown</button>
          <button class="btn" :disabled="exporting || !roots.length" title="导出整个空间为 Markdown 压缩包" @click="exportSpace">
            {{ exporting ? '导出中…' : '导出空间' }}
          </button>
          <RouterLink class="btn" :to="{ name: 'space-settings', params: { spaceId: space.id } }">成员与设置</RouterLink>
        </div>
      </div>
      <h2 class="section-title">目录</h2>
      <div class="doc-list">
        <RouterLink v-for="p in roots" :key="p.id" class="doc-row" :to="{ name: 'page', params: { spaceId: space.id, pageId: p.id } }">
          <span class="page-icon">{{ p.icon || '📄' }}</span>
          <span class="doc-title ellipsis">{{ p.title || '无标题' }}</span>
          <span v-if="p.children.length" class="muted tiny">{{ p.children.length }} 个子页面</span>
        </RouterLink>
        <p v-if="!roots.length" class="empty">空间里还没有页面</p>
      </div>
    </div>
    <p v-else class="empty">知识空间不存在或无权访问</p>
    <ImportDialog v-if="importOpen" :space-id="id" @close="importOpen = false" />
  </div>
</template>
