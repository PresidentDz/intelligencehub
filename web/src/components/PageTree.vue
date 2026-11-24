<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSpaces } from '../stores/spaces'
import type { Space } from '../types'
import TreeItem from './TreeItem.vue'
import { createPage, drag, dropOn } from './treeActions'

const props = defineProps<{ space: Space }>()
const spaces = useSpaces()
const route = useRoute()
const router = useRouter()
const rootOver = ref(false)

const canEdit = computed(() => props.space.myRole !== 'reader')
const roots = computed(() => (spaces.treeSpaceId === props.space.id ? spaces.tree() : []))

// Reveal the open page in the tree, including after a reload.
watch(() => [route.params.pageId, spaces.pages] as const, ([pageId]) => {
  if (pageId) spaces.expandTo(Number(pageId))
}, { immediate: true })

function onRootDrop(e: DragEvent) {
  e.preventDefault()
  rootOver.value = false
  dropOn(props.space.id, null, 'after')
}
</script>

<template>
  <div class="page-tree">
    <TreeItem v-for="node in roots" :key="node.id" :node="node" :space-id="space.id" :depth="1" :can-edit="canEdit" />
    <div v-if="canEdit" class="tree-root-drop" :class="{ over: rootOver && drag.id != null }"
         @dragover.prevent="rootOver = true" @dragleave="rootOver = false" @drop="onRootDrop">
      <button class="tree-new" @click="createPage(router, space.id, null)">＋ 新建页面</button>
    </div>
    <p v-else-if="!roots.length" class="muted tiny pad-tree">空间里还没有页面</p>
  </div>
</template>
