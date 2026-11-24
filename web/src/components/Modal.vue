<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'

defineProps<{ title?: string; wide?: boolean }>()
const emit = defineEmits<{ close: [] }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @mousedown.self="emit('close')">
      <div class="modal" :class="{ wide }" role="dialog" aria-modal="true">
        <header v-if="title" class="modal-head">
          <h3>{{ title }}</h3>
          <button class="icon-btn" aria-label="关闭" @click="emit('close')">✕</button>
        </header>
        <slot />
      </div>
    </div>
  </Teleport>
</template>
