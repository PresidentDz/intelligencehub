import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '../api'
import type { Space, TreePage } from '../types'

export interface TreeNode extends TreePage {
  children: TreeNode[]
}

/** Spaces visible to the user, and the page tree of the space currently open. */
export const useSpaces = defineStore('spaces', () => {
  const list = ref<Space[]>([])
  const treeSpaceId = ref<number | null>(null)
  const pages = ref<TreePage[]>([])
  /** Page ids expanded in the sidebar tree, kept across navigation. */
  const expanded = ref(new Set<number>())

  async function loadList() {
    list.value = (await api.get<{ spaces: Space[] }>('/spaces')).spaces
  }

  async function loadTree(spaceId: number) {
    const data = await api.get<{ pages: TreePage[] }>(`/spaces/${spaceId}/pages`)
    treeSpaceId.value = spaceId
    pages.value = data.pages
  }

  function tree(): TreeNode[] {
    const nodes = new Map<number, TreeNode>()
    for (const p of pages.value) nodes.set(p.id, { ...p, children: [] })
    const roots: TreeNode[] = []
    for (const node of nodes.values()) {
      const parent = node.parentId == null ? null : nodes.get(node.parentId)
      ;(parent ? parent.children : roots).push(node)
    }
    const sort = (items: TreeNode[]) => {
      items.sort((a, b) => a.position - b.position || a.id - b.id)
      items.forEach((n) => sort(n.children))
    }
    sort(roots)
    return roots
  }

  /** Reflects an edited title/icon in the sidebar without refetching. */
  function patchPage(id: number, patch: Partial<TreePage>) {
    const p = pages.value.find((x) => x.id === id)
    if (p) Object.assign(p, patch)
  }

  function expandTo(pageId: number) {
    let p = pages.value.find((x) => x.id === pageId)
    while (p && p.parentId != null) {
      expanded.value.add(p.parentId)
      p = pages.value.find((x) => x.id === p!.parentId)
    }
  }

  function isAncestor(ancestorId: number, pageId: number): boolean {
    let p = pages.value.find((x) => x.id === pageId)
    while (p) {
      if (p.id === ancestorId) return true
      p = p.parentId == null ? undefined : pages.value.find((x) => x.id === p!.parentId)
    }
    return false
  }

  return { list, treeSpaceId, pages, expanded, loadList, loadTree, tree, patchPage, expandTo, isAncestor }
})
