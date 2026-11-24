import { strToU8, zipSync, type Zippable } from 'fflate'
import { api, downloadUploadedFile, UPLOADED_FILE } from '../api'
import { htmlToDoc, mapImages, pageToMarkdown } from './markdown'
import { safeName, saveBlob } from './files'

interface ExportPage {
  id: number
  parentId: number | null
  title: string
  content: string
  position: number
}

interface ExportNode extends ExportPage {
  children: ExportNode[]
}

async function fetchTree(spaceId: number, pageId: number | null): Promise<ExportNode[]> {
  const query = pageId == null ? '' : `?pageId=${pageId}`
  const { pages } = await api.get<{ pages: ExportPage[] }>(`/spaces/${spaceId}/export${query}`)
  const nodes = new Map<number, ExportNode>(pages.map((p) => [p.id, { ...p, children: [] }]))
  const roots: ExportNode[] = []
  for (const node of nodes.values()) {
    const parent = node.parentId == null || node.id === pageId ? undefined : nodes.get(node.parentId)
    ;(parent ? parent.children : roots).push(node)
  }
  const sort = (list: ExportNode[]) => {
    list.sort((a, b) => a.position - b.position || a.id - b.id)
    list.forEach((n) => sort(n.children))
  }
  sort(roots)
  return roots
}

/** Exports one page as a .md file. Uploaded images are linked by absolute URL. */
export async function exportPageMarkdown(spaceId: number, pageId: number) {
  const [page] = await fetchTree(spaceId, pageId)
  if (!page) throw new Error('页面不存在')
  const doc = htmlToDoc(page.content)
  await mapImages(doc, async (src) => (src.startsWith('/') ? new URL(src, location.origin).href : src))
  saveBlob(new Blob([pageToMarkdown(page.title, doc)], { type: 'text/markdown;charset=utf-8' }), `${safeName(page.title)}.md`)
}

/**
 * Exports a page with its subpages (or the whole space when {@code pageId} is null) as a zip.
 * A page with subpages becomes "标题.md" plus a "标题/" folder, which is the layout the folder
 * import reads back. Uploaded images are bundled under assets/ and linked relatively.
 */
export async function exportZip(spaceId: number, pageId: number | null, zipName: string,
                                onProgress: (text: string) => void): Promise<number> {
  const roots = await fetchTree(spaceId, pageId)
  if (!roots.length) throw new Error('没有可导出的页面')
  const zip: Zippable = {}
  const assets: Zippable = {}
  const images = new Map<string, Promise<boolean>>()
  let done = 0

  async function write(nodes: ExportNode[], folder: Zippable, depth: number) {
    // The top level reserves "assets" for the bundled images.
    const used = new Set<string>(depth === 0 ? ['assets'] : [])
    const unique = (base: string) => {
      let name = base
      for (let i = 2; used.has(name.toLowerCase()); i++) name = `${base} (${i})`
      used.add(name.toLowerCase())
      return name
    }
    for (const node of nodes) {
      const name = unique(safeName(node.title))
      const doc = htmlToDoc(node.content)
      await mapImages(doc, async (src) => {
        const file = UPLOADED_FILE.exec(src)?.[1]
        if (!file) return src.startsWith('/') ? new URL(src, location.origin).href : src
        if (!images.has(file)) {
          images.set(file, downloadUploadedFile(file).then((bytes) => {
            assets[file] = [bytes, { level: 0 }]
            return true
          }, () => false))
        }
        return (await images.get(file)) ? `${'../'.repeat(depth)}assets/${file}` : new URL(src, location.origin).href
      })
      folder[`${name}.md`] = strToU8(pageToMarkdown(node.title, doc))
      onProgress(`正在导出 ${++done} 个页面`)
      if (node.children.length) {
        const sub: Zippable = {}
        folder[name] = sub
        await write(node.children, sub, depth + 1)
      }
    }
  }

  await write(roots, zip, 0)
  if (Object.keys(assets).length) zip.assets = assets
  onProgress('正在打包…')
  const data = zipSync(zip, { level: 6 })
  saveBlob(new Blob([data], { type: 'application/zip' }), `${safeName(zipName)}.zip`)
  return done
}
