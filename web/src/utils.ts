import { reactive } from 'vue'

/** Server timestamps are SQLite CURRENT_TIMESTAMP values in UTC: "YYYY-MM-DD HH:MM:SS". */
export function parseTime(s: string): Date {
  return new Date(s.replace(' ', 'T') + 'Z')
}

export function relativeTime(s: string): string {
  const d = parseTime(s)
  const diff = (Date.now() - d.getTime()) / 1000
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  if (diff < 172800) return '昨天'
  const pad = (n: number) => String(n).padStart(2, '0')
  const md = `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  return d.getFullYear() === new Date().getFullYear() ? md : `${d.getFullYear()}-${md}`
}

export function fullTime(s: string): string {
  return parseTime(s).toLocaleString('zh-CN', { hour12: false })
}

export const toasts = reactive<{ id: number; text: string; kind: 'info' | 'error' }[]>([])
let toastId = 0
export function toast(text: string, kind: 'info' | 'error' = 'info') {
  const id = ++toastId
  toasts.push({ id, text, kind })
  setTimeout(() => {
    const i = toasts.findIndex((t) => t.id === id)
    if (i >= 0) toasts.splice(i, 1)
  }, 2600)
}

/** Splits text into plain and matching parts so highlights can be rendered without v-html. */
export function highlight(text: string, query: string): { text: string; hit: boolean }[] {
  const q = query.trim().toLowerCase()
  if (!q) return [{ text, hit: false }]
  const parts: { text: string; hit: boolean }[] = []
  const lower = text.toLowerCase()
  let from = 0
  for (let at = lower.indexOf(q); at >= 0; at = lower.indexOf(q, from)) {
    if (at > from) parts.push({ text: text.slice(from, at), hit: false })
    parts.push({ text: text.slice(at, at + q.length), hit: true })
    from = at + q.length
  }
  if (from < text.length) parts.push({ text: text.slice(from), hit: false })
  return parts
}

export const PAGE_ICONS = ['📄', '📘', '📗', '📙', '📝', '📌', '💡', '🚀', '🎯', '🔧', '🧭', '📊', '🗓️', '✅', '⚠️', '🚨', '👋', '🎨', '🧪', '📦', '🔒', '💬', '⭐', '🏁']
