<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, errorMessage } from '../api'
import type { Version } from '../types'
import { fullTime, toast } from '../utils'
import BlockEditor from '../editor/BlockEditor.vue'
import Modal from './Modal.vue'

const props = defineProps<{ pageId: number; canRestore: boolean }>()
const emit = defineEmits<{ close: []; restored: [] }>()
const versions = ref<Version[]>([])
const preview = ref<Version | null>(null)

onMounted(async () => {
  try {
    versions.value = (await api.get<{ versions: Version[] }>(`/pages/${props.pageId}/versions`)).versions
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
})

async function open(v: Version) {
  try {
    preview.value = (await api.get<{ version: Version }>(`/pages/${props.pageId}/versions/${v.id}`)).version
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}

async function restore() {
  if (!preview.value || !confirm('用这个版本覆盖当前内容？当前内容会保留在历史中。')) return
  try {
    await api.post(`/pages/${props.pageId}/versions/${preview.value.id}/restore`)
    preview.value = null
    emit('restored')
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}
</script>

<template>
  <aside class="side-panel">
    <header class="panel-head"><h3>历史版本</h3><button class="icon-btn" @click="emit('close')">✕</button></header>
    <div class="panel-body">
      <p class="muted tiny pad">同一人 10 分钟内的连续编辑合并为一个版本</p>
      <button v-for="(v, i) in versions" :key="v.id" class="version-row" @click="open(v)">
        <strong>{{ fullTime(v.updatedAt) }}</strong>
        <span v-if="i === 0" class="badge">当前</span>
        <span class="muted tiny">{{ v.editor }} · {{ v.title || '无标题' }}</span>
      </button>
    </div>
    <Modal v-if="preview" wide :title="`${fullTime(preview.updatedAt)} · ${preview.editor}`" @close="preview = null">
      <div class="modal-body version-preview">
        <h1 class="preview-title">{{ preview.title || '无标题' }}</h1>
        <BlockEditor :content="preview.content || ''" :editable="false" />
      </div>
      <footer class="modal-foot">
        <button class="btn" @click="preview = null">关闭</button>
        <button v-if="canRestore" class="btn primary" @click="restore">恢复此版本</button>
      </footer>
    </Modal>
  </aside>
</template>
