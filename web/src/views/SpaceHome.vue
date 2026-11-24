<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useSpaces } from '../stores/spaces'
import { SPACE_ROLE_LABEL } from '../types'
import { createPage } from '../components/treeActions'

const props = defineProps<{ spaceId: string }>()
const spaces = useSpaces()
const router = useRouter()
const id = computed(() => Number(props.spaceId))
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
  </div>
</template>
