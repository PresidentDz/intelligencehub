<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api, errorMessage } from '../api'
import type { Space, Visibility } from '../types'
import Modal from './Modal.vue'

const SPACE_ICONS = ['📘', '📗', '📙', '📕', '🎨', '🛠️', '🧪', '📊', '🚀', '🏢', '💼', '🧭']

const props = defineProps<{ space?: Space }>()
const emit = defineEmits<{ close: []; saved: [id: number] }>()
const form = reactive({
  name: props.space?.name ?? '',
  description: props.space?.description ?? '',
  icon: props.space?.icon ?? '📘',
  visibility: (props.space?.visibility ?? 'private') as Visibility,
})
const error = ref('')
const busy = ref(false)

async function submit() {
  busy.value = true
  error.value = ''
  try {
    const data = props.space
      ? await api.put<{ space: Space }>(`/spaces/${props.space.id}`, form)
      : await api.post<{ space: Space }>('/spaces', form)
    emit('saved', data.space.id)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Modal :title="space ? '空间设置' : '新建知识空间'" @close="emit('close')">
    <form class="modal-body" @submit.prevent="submit">
      <div class="field">
        <span>图标</span>
        <div class="icon-grid">
          <button v-for="i in SPACE_ICONS" :key="i" type="button" class="icon-choice" :class="{ on: form.icon === i }" @click="form.icon = i">{{ i }}</button>
        </div>
      </div>
      <label class="field"><span>名称</span><input v-model="form.name" maxlength="80" required autofocus placeholder="例如：产品与设计" /></label>
      <label class="field"><span>简介</span><textarea v-model="form.description" maxlength="500" rows="2" placeholder="这个空间用来存放什么" /></label>
      <div class="field">
        <span>可见范围</span>
        <label class="radio"><input v-model="form.visibility" type="radio" value="private" /> 🔒 仅空间成员可见</label>
        <label class="radio"><input v-model="form.visibility" type="radio" value="public" /> 🌐 所有人可阅读（成员仍按角色编辑）</label>
      </div>
      <p v-if="error" class="form-error">{{ error }}</p>
      <footer class="modal-foot">
        <button type="button" class="btn" @click="emit('close')">取消</button>
        <button class="btn primary" :disabled="busy">{{ space ? '保存' : '创建' }}</button>
      </footer>
    </form>
  </Modal>
</template>
