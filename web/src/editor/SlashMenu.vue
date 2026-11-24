<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { BlockCommand } from './commands'

const props = defineProps<{ items: BlockCommand[]; command: (item: BlockCommand) => void }>()
const selected = ref(0)
const list = ref<HTMLElement>()

watch(() => props.items, () => (selected.value = 0))
watch(selected, async () => {
  await nextTick()
  list.value?.querySelector('.selected')?.scrollIntoView({ block: 'nearest' })
})

function choose(i: number) {
  const item = props.items[i]
  if (item) props.command(item)
}

/** Called by the suggestion plugin; returns true when the key was handled. */
function onKeyDown(event: KeyboardEvent): boolean {
  if (!props.items.length) return false
  if (event.key === 'ArrowDown') {
    selected.value = (selected.value + 1) % props.items.length
    return true
  }
  if (event.key === 'ArrowUp') {
    selected.value = (selected.value - 1 + props.items.length) % props.items.length
    return true
  }
  if (event.key === 'Enter') {
    choose(selected.value)
    return true
  }
  return false
}

defineExpose({ onKeyDown })
</script>

<template>
  <div ref="list" class="slash-menu" role="listbox">
    <div class="slash-head">基础块</div>
    <button v-for="(item, i) in items" :key="item.title" type="button" class="slash-item" :class="{ selected: i === selected }"
            role="option" :aria-selected="i === selected" @mouseenter="selected = i" @mousedown.prevent="choose(i)">
      <span class="slash-icon">{{ item.icon }}</span>
      <span class="slash-title">{{ item.title }}</span>
      <span class="slash-hint">{{ item.hint }}</span>
    </button>
    <div v-if="!items.length" class="slash-empty">没有匹配的内容块</div>
  </div>
</template>
