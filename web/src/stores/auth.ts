import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { api } from '../api'
import type { User } from '../types'

export const useAuth = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const loaded = ref(false)
  const isAdmin = computed(() => user.value?.role === 'admin')

  async function load() {
    const data = await api.get<{ user: User | null }>('/auth/me')
    user.value = data.user ?? null
    loaded.value = true
  }

  async function login(username: string, password: string) {
    const data = await api.post<{ user: User }>('/auth/login', { username, password })
    user.value = data.user
  }

  async function logout() {
    await api.post('/auth/logout').catch(() => {})
    user.value = null
  }

  return { user, loaded, isAdmin, load, login, logout }
})
