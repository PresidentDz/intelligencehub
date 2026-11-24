<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSpaces, type TreeNode } from '../stores/spaces'
import { api, errorMessage } from '../api'
import { toast } from '../utils'
import { createPage, drag, dropOn, zoneFor } from './treeActions'

const props = defineProps<{ node: TreeNode; spaceId: number; depth: number; canEdit: boolean }>()
const spaces = useSpaces()
const route = useRoute()
const router = useRouter()

const open = computed(() => spaces.expanded.has(props.node.id))
const active = computed(() => Number(route.params.pageId) === props.node.id)
const dropClass = computed(() => (drag.overId === props.node.id && drag.zone ? `drop-${drag.zone}` : ''))

function toggle() {
  if (open.value) spaces.expanded.delete(props.node.id)
  else spaces.expanded.add(props.node.id)
}

function onDragStart(e: DragEvent) {
  drag.id = props.node.id
  e.dataTransfer?.setData('text/plain', String(props.node.id))
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}

function onDragOver(e: DragEvent) {
  if (drag.id == null || drag.id === props.node.id || spaces.isAncestor(drag.id, props.node.id)) return
  e.preventDefault()
  drag.overId = props.node.id
  drag.zone = zoneFor(e)
}

function onDragLeave() {
  if (drag.overId === props.node.id) drag.overId = null
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  if (drag.zone) dropOn(props.spaceId, props.node.id, drag.zone)
}

async function remove() {
  const hasChildren = props.node.children.length > 0
  if (!confirm(`删除「${props.node.title || '无标题'}」${hasChildren ? '及其所有子页面' : ''}？删除后无法恢复。`)) return
  try {
    await api.del(`/pages/${props.node.id}`)
    const currentId = Number(route.params.pageId)
    const affected = currentId && spaces.isAncestor(props.node.id, currentId)
    await spaces.loadTree(props.spaceId)
    if (affected) router.replace({ name: 'space', params: { spaceId: props.spaceId } })
    toast('已删除')
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}
</script>

<template>
  <div class="tree-item">
    <div class="tree-row" :class="[{ active }, dropClass]" :style="{ paddingLeft: 8 + depth * 16 + 'px' }"
         :draggable="canEdit" @dragstart="onDragStart" @dragend="drag.id = drag.overId = null"
         @dragover="onDragOver" @dragleave="onDragLeave" @drop="onDrop">
      <button class="caret" :class="{ open, hidden: !node.children.length }" :tabindex="node.children.length ? 0 : -1"
              :aria-label="open ? '收起' : '展开'" @click.stop="toggle">▸</button>
      <RouterLink class="tree-link" :to="{ name: 'page', params: { spaceId, pageId: node.id } }">
        <span class="page-icon">{{ node.icon || '📄' }}</span>
        <span class="ellipsis" :class="{ untitled: !node.title }">{{ node.title || '无标题' }}</span>
      </RouterLink>
      <span v-if="canEdit" class="row-actions">
        <button class="icon-btn" title="删除" @click.stop="remove">🗑</button>
        <button class="icon-btn" title="新建子页面" @click.stop="createPage(router, spaceId, node.id)">＋</button>
      </span>
    </div>
    <template v-if="open">
      <TreeItem v-for="child in node.children" :key="child.id" :node="child" :space-id="spaceId"
                :depth="depth + 1" :can-edit="canEdit" />
    </template>
  </div>
</template>
