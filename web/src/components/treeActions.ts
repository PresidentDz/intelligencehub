import { reactive } from 'vue'
import type { Router } from 'vue-router'
import { api, errorMessage } from '../api'
import { useSpaces } from '../stores/spaces'
import { toast } from '../utils'

export type DropZone = 'before' | 'after' | 'inside'

/** Drag state shared by every row of the page tree. */
export const drag = reactive<{ id: number | null; overId: number | null; zone: DropZone | null }>({
  id: null,
  overId: null,
  zone: null,
})

export function zoneFor(e: DragEvent): DropZone {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const y = e.clientY - rect.top
  if (y < rect.height * 0.25) return 'before'
  if (y > rect.height * 0.75) return 'after'
  return 'inside'
}

export async function createPage(router: Router, spaceId: number, parentId: number | null) {
  const spaces = useSpaces()
  try {
    const { id } = await api.post<{ id: number }>(`/spaces/${spaceId}/pages`, { parentId, title: '' })
    if (parentId != null) spaces.expanded.add(parentId)
    await spaces.loadTree(spaceId)
    router.push({ name: 'page', params: { spaceId, pageId: id } })
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}

/**
 * Moves the dragged page relative to {@code targetId}. The backend removes the page from its
 * old position before inserting, so the index is computed among siblings without the page.
 */
export async function dropOn(spaceId: number, targetId: number | null, zone: DropZone) {
  const spaces = useSpaces()
  const id = drag.id
  drag.id = drag.overId = null
  drag.zone = null
  if (id == null || id === targetId) return
  let parentId: number | null
  let index: number
  if (targetId == null) {
    parentId = null
    index = Number.MAX_SAFE_INTEGER
  } else if (zone === 'inside') {
    parentId = targetId
    index = Number.MAX_SAFE_INTEGER
  } else {
    const target = spaces.pages.find((p) => p.id === targetId)
    if (!target) return
    parentId = target.parentId
    const siblings = spaces.pages
      .filter((p) => p.parentId === parentId && p.id !== id)
      .sort((a, b) => a.position - b.position || a.id - b.id)
    index = siblings.findIndex((p) => p.id === targetId) + (zone === 'after' ? 1 : 0)
  }
  if (parentId != null && spaces.isAncestor(id, parentId)) {
    toast('不能把页面移动到它自己的子页面下', 'error')
    return
  }
  try {
    await api.post(`/pages/${id}/move`, { parentId, index: Math.min(index, 1_000_000) })
    if (parentId != null) spaces.expanded.add(parentId)
    await spaces.loadTree(spaceId)
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}
