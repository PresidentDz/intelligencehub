<script setup lang="ts">
import { toasts } from './utils'
</script>

<template>
  <RouterView />
  <div class="toasts" aria-live="polite">
    <div v-for="t in toasts" :key="t.id" class="toast" :class="t.kind">{{ t.text }}</div>
  </div>
</template>
