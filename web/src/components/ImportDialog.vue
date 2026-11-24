<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage } from '../api'
import { useSpaces } from '../stores/spaces'
import { toast } from '../utils'
import { pickFiles } from '../transfer/files'
import { planImport, runImport, type ImportPlan, type PlanNode } from '../transfer/importer'
import Modal from './Modal.vue'

/** Imports Markdown files or a folder of them into a space, optionally under a page. */
const props = defineProps<{ spaceId: number; parent?: { id: number; title: string } }>()
const emit = defineEmits<{ close: [] }>()
const spaces = useSpaces()
const router = useRouter()

const plan = ref<ImportPlan | null>(null)
const source = ref('')
const busy = ref(false)
const progress = ref('')
const error = ref('')
const missing = ref<string[]>([])

const preview = computed(() => {
  const rows: { title: string; depth: number; folder: boolean }[] = []
  const add = (nodes: PlanNode[], depth: number) => {
    for (const n of nodes) {
      if (rows.length >= 200) return
      rows.push({ title: n.title, depth, folder: n.children.length > 0 })
      add(n.children, depth + 1)
    }
  }
  add(plan.value?.nodes ?? [], 0)
  return rows
})

async function choose(directory: boolean) {
  error.value = ''
  missing.value = []
  const picked = await pickFiles(directory)
  if (!picked.length) return
  const next = planImport(picked, directory)
  if (!next.pageCount) {
    plan.value = null
    error.value = directory ? '所选文件夹中没有 .md 文件' : '请选择 .md 文件'
    return
  }
  plan.value = next
  source.value = directory ? `文件夹「${picked[0].path.split('/')[0]}」` : `${next.pageCount} 个文件`
}

async function submit() {
  if (!plan.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await runImport(props.spaceId, props.parent?.id ?? null, plan.value, (t) => (progress.value = t))
    if (props.parent) spaces.expanded.add(props.parent.id)
    await spaces.loadTree(props.spaceId)
    await spaces.loadList()
    toast(`已导入 ${result.count} 个页面`)
    if (result.missingImages.length) {
      missing.value = result.missingImages
      plan.value = null
    } else {
      emit('close')
    }
    if (result.firstId != null) router.push({ name: 'page', params: { spaceId: props.spaceId, pageId: result.firstId } })
  } catch (e) {
    error.value = `导入失败：${errorMessage(e)}`
  } finally {
    busy.value = false
    progress.value = ''
  }
}
</script>

<template>
  <Modal title="导入 Markdown" @close="busy || emit('close')">
    <div class="modal-body">
      <p class="muted tiny">
        导入到：{{ parent ? `「${parent.title || '无标题'}」下` : '空间根目录' }}。
        选择文件夹时，子文件夹会成为父页面，文件夹中引用的本地图片会一起上传。
      </p>
      <div class="import-pick">
        <button class="btn" :disabled="busy" @click="choose(false)">📄 选择 Markdown 文件</button>
        <button class="btn" :disabled="busy" @click="choose(true)">📁 选择文件夹</button>
      </div>
      <template v-if="plan">
        <p class="tiny">{{ source }}，将创建 {{ plan.pageCount }} 个页面：</p>
        <ul class="import-preview">
          <li v-for="(row, i) in preview" :key="i" :style="{ paddingLeft: row.depth * 16 + 'px' }" class="ellipsis">
            {{ row.folder ? '📁' : '📄' }} {{ row.title }}
          </li>
          <li v-if="preview.length < plan.pageCount" class="muted">…</li>
        </ul>
      </template>
      <div v-if="missing.length" class="form-error">
        <p>导入完成，但以下图片没有找到，已从页面中省略：</p>
        <ul class="import-preview"><li v-for="m in missing" :key="m" class="ellipsis" :title="m">{{ m }}</li></ul>
      </div>
      <p v-if="progress" class="muted tiny">{{ progress }}</p>
      <p v-if="error" class="form-error">{{ error }}</p>
    </div>
    <footer class="modal-foot">
      <button class="btn" :disabled="busy" @click="emit('close')">{{ missing.length ? '关闭' : '取消' }}</button>
      <button v-if="!missing.length" class="btn primary" :disabled="busy || !plan" @click="submit">{{ busy ? '导入中…' : '开始导入' }}</button>
    </footer>
  </Modal>
</template>
