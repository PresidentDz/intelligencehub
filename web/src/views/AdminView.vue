<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import type { SystemRole, User } from '../types'
import { toast } from '../utils'
import Modal from '../components/Modal.vue'

const auth = useAuth()
const users = ref<User[]>([])
const editing = ref<(User & { password: string }) | null>(null)
const creating = ref(false)
const form = reactive({ username: '', name: '', password: '', role: 'member' as SystemRole, department: '' })
const error = ref('')

async function load() {
  users.value = (await api.get<{ users: User[] }>('/admin/users')).users
}
onMounted(() => load().catch((e) => toast(errorMessage(e), 'error')))

async function create() {
  error.value = ''
  try {
    await api.post('/admin/users', form)
    Object.assign(form, { username: '', name: '', password: '', role: 'member', department: '' })
    creating.value = false
    await load()
    toast('已添加成员')
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function saveEdit() {
  if (!editing.value) return
  error.value = ''
  const u = editing.value
  try {
    await api.put(`/admin/users/${u.id}`, { name: u.name, role: u.role, department: u.department, active: u.active, password: u.password })
    editing.value = null
    await load()
    toast('已保存')
  } catch (e) {
    error.value = errorMessage(e)
  }
}
</script>

<template>
  <div class="page-scroll">
    <div class="settings">
      <header class="settings-head">
        <h1>成员管理</h1>
        <p class="muted small">管理系统账号。空间内的权限在各空间的「成员与设置」中配置；系统管理员可以访问和管理所有空间。</p>
        <div class="hero-actions"><button class="btn primary" @click="creating = true; error = ''">＋ 添加成员</button></div>
      </header>
      <section class="card">
        <table class="table">
          <thead><tr><th>姓名</th><th>账号</th><th>部门</th><th>系统角色</th><th>状态</th><th /></tr></thead>
          <tbody>
            <tr v-for="u in users" :key="u.id" :class="{ inactive: !u.active }">
              <td><span class="avatar small">{{ u.name.slice(0, 1) }}</span> {{ u.name }}</td>
              <td class="muted">{{ u.username }}</td>
              <td>{{ u.department || '—' }}</td>
              <td>{{ u.role === 'admin' ? '系统管理员' : '成员' }}</td>
              <td>{{ u.active ? '正常' : '已停用' }}</td>
              <td class="right"><button class="link-btn" @click="editing = { ...u, password: '' }; error = ''">编辑</button></td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>

    <Modal v-if="creating" title="添加成员" @close="creating = false">
      <form class="modal-body" @submit.prevent="create">
        <label class="field"><span>账号</span><input v-model="form.username" required autofocus placeholder="字母、数字、_ . -" /></label>
        <label class="field"><span>姓名</span><input v-model="form.name" required /></label>
        <label class="field"><span>初始密码</span><input v-model="form.password" type="password" required minlength="8" autocomplete="new-password" placeholder="至少 8 位" /></label>
        <label class="field"><span>部门</span><input v-model="form.department" /></label>
        <label class="field"><span>系统角色</span>
          <select v-model="form.role"><option value="member">成员</option><option value="admin">系统管理员</option></select>
        </label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <footer class="modal-foot"><button type="button" class="btn" @click="creating = false">取消</button><button class="btn primary">添加</button></footer>
      </form>
    </Modal>

    <Modal v-if="editing" :title="`编辑 ${editing.username}`" @close="editing = null">
      <form class="modal-body" @submit.prevent="saveEdit">
        <label class="field"><span>姓名</span><input v-model="editing.name" required /></label>
        <label class="field"><span>部门</span><input v-model="editing.department" /></label>
        <label class="field"><span>系统角色</span>
          <select v-model="editing.role" :disabled="editing.id === auth.user?.id">
            <option value="member">成员</option><option value="admin">系统管理员</option>
          </select>
        </label>
        <label class="radio"><input v-model="editing.active" type="checkbox" :disabled="editing.id === auth.user?.id" /> 账号可用</label>
        <label class="field"><span>重置密码</span><input v-model="editing.password" type="password" minlength="8" autocomplete="new-password" placeholder="留空则不修改" /></label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <footer class="modal-foot"><button type="button" class="btn" @click="editing = null">取消</button><button class="btn primary">保存</button></footer>
      </form>
    </Modal>
  </div>
</template>
