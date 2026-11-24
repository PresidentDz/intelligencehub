<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import { useSpaces } from '../stores/spaces'
import type { DirectoryUser, Member, SpaceRole } from '../types'
import { SPACE_ROLE_LABEL } from '../types'
import { toast } from '../utils'
import SpaceDialog from '../components/SpaceDialog.vue'

const props = defineProps<{ spaceId: string }>()
const auth = useAuth()
const spaces = useSpaces()
const router = useRouter()
const id = computed(() => Number(props.spaceId))
const space = computed(() => spaces.list.find((s) => s.id === id.value))
const isAdmin = computed(() => space.value?.myRole === 'admin')

const members = ref<Member[]>([])
const editOpen = ref(false)
const q = ref('')
const candidates = ref<DirectoryUser[]>([])
const newRole = ref<SpaceRole>('reader')
const roles: SpaceRole[] = ['admin', 'editor', 'reader']

async function load() {
  members.value = (await api.get<{ members: Member[] }>(`/spaces/${id.value}/members`)).members
}
onMounted(() => load().catch((e) => toast(errorMessage(e), 'error')))

let timer: ReturnType<typeof setTimeout> | undefined
watch(q, (value) => {
  clearTimeout(timer)
  if (!value.trim()) {
    candidates.value = []
    return
  }
  timer = setTimeout(async () => {
    const users = (await api.get<{ users: DirectoryUser[] }>(`/auth/users?q=${encodeURIComponent(value.trim())}`)).users
    const existing = new Set(members.value.map((m) => m.id))
    candidates.value = users.filter((u) => !existing.has(u.id))
  }, 200)
})

async function setRole(userId: number, role: SpaceRole) {
  try {
    await api.put(`/spaces/${id.value}/members/${userId}`, { role })
    await load()
    if (userId === auth.user?.id) await spaces.loadList()
  } catch (e) {
    toast(errorMessage(e), 'error')
    await load()
  }
}

async function add(u: DirectoryUser) {
  await setRole(u.id, newRole.value)
  q.value = ''
  toast(`已添加 ${u.name}`)
}

async function remove(m: Member) {
  const self = m.id === auth.user?.id
  if (!confirm(self ? '退出这个知识空间？' : `将 ${m.name} 移出空间？`)) return
  try {
    await api.del(`/spaces/${id.value}/members/${m.id}`)
    if (self) {
      await spaces.loadList()
      router.replace({ name: 'home' })
    } else {
      await load()
    }
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}

async function removeSpace() {
  if (!space.value) return
  const typed = prompt(`删除空间会同时删除其中所有页面、评论和历史版本，且无法恢复。\n请输入空间名称「${space.value.name}」确认：`)
  if (typed !== space.value.name) return
  try {
    await api.del(`/spaces/${id.value}`)
    await spaces.loadList()
    router.replace({ name: 'home' })
    toast('空间已删除')
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}

async function onSaved() {
  editOpen.value = false
  await spaces.loadList()
  toast('已保存')
}
</script>

<template>
  <div class="page-scroll">
    <div v-if="space" class="settings">
      <header class="settings-head">
        <RouterLink :to="{ name: 'space', params: { spaceId: id } }" class="muted small">← 返回 {{ space.name }}</RouterLink>
        <h1>{{ space.icon }} {{ space.name }} · 成员与设置</h1>
        <p class="muted small">{{ space.visibility === 'public' ? '🌐 所有登录用户都可阅读，成员按角色编辑和管理' : '🔒 仅下列成员可以访问' }}</p>
        <div v-if="isAdmin" class="hero-actions">
          <button class="btn" @click="editOpen = true">编辑空间信息</button>
          <button class="btn danger" @click="removeSpace">删除空间</button>
        </div>
      </header>

      <section class="card">
        <h2 class="section-title">成员 {{ members.length }}</h2>
        <div v-if="isAdmin" class="member-add">
          <input v-model="q" placeholder="搜索姓名或账号添加成员" />
          <select v-model="newRole">
            <option v-for="r in roles" :key="r" :value="r">{{ SPACE_ROLE_LABEL[r] }}</option>
          </select>
          <div v-if="candidates.length" class="member-candidates">
            <button v-for="u in candidates" :key="u.id" @click="add(u)">
              <span class="avatar small">{{ u.name.slice(0, 1) }}</span>{{ u.name }}
              <span class="muted tiny">{{ u.username }} · {{ u.department || '未设置部门' }}</span>
            </button>
          </div>
        </div>
        <table class="table">
          <thead><tr><th>成员</th><th>部门</th><th>角色</th><th /></tr></thead>
          <tbody>
            <tr v-for="m in members" :key="m.id">
              <td><span class="avatar small">{{ m.name.slice(0, 1) }}</span> {{ m.name }} <span class="muted tiny">{{ m.username }}</span></td>
              <td class="muted">{{ m.department || '—' }}</td>
              <td>
                <select v-if="isAdmin" :value="m.role" @change="setRole(m.id, ($event.target as HTMLSelectElement).value as SpaceRole)">
                  <option v-for="r in roles" :key="r" :value="r">{{ SPACE_ROLE_LABEL[r] }}</option>
                </select>
                <span v-else>{{ SPACE_ROLE_LABEL[m.role] }}</span>
              </td>
              <td class="right">
                <button v-if="isAdmin || m.id === auth.user?.id" class="link-btn" @click="remove(m)">{{ m.id === auth.user?.id ? '退出' : '移除' }}</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="muted tiny">管理员：管理成员和空间设置；可编辑：新建、编辑、移动、删除页面；可阅读：查看和评论。</p>
      </section>
    </div>
    <SpaceDialog v-if="editOpen && space" :space="space" @close="editOpen = false" @saved="onSaved" />
  </div>
</template>
