import { api, uploadImage } from '../api'
import { docToHtml, mapImages, parseMarkdown } from './markdown'
import { isMarkdown, readText, type PickedFile } from './files'

/** Must match PageController limits. */
const MAX_CONTENT = 2_000_000
const MAX_TITLE = 240
/** Keeps each request well under the 11 MB body limit in deploy/nginx.conf. */
const MAX_REQUEST_BYTES = 4_000_000
const MAX_REQUEST_PAGES = 500

const IMAGE_TYPES: Record<string, string> = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' }
const FOLDER_PAGE_FILES = ['index.md', 'readme.md']

/** A page to be created. {@code source} is null for a folder that has no Markdown of its own. */
export interface PlanNode {
  title: string
  source: PickedFile | null
  children: PlanNode[]
}

export interface ImportPlan {
  nodes: PlanNode[]
  pageCount: number
  /** All picked files by relative path, used to resolve image references. */
  files: Map<string, File>
}

interface Dir {
  name: string
  dirs: Map<string, Dir>
  files: Map<string, PickedFile>
}

const collator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })
const stripExt = (name: string) => name.replace(/\.(md|markdown)$/i, '')
const baseName = (path: string) => path.slice(path.lastIndexOf('/') + 1)

/**
 * Turns picked files into a page tree. Each folder becomes a page holding its contents, with
 * its body taken from a sibling "name.md" or from index.md / README.md inside it. Folders without
 * any Markdown (for example image folders) are skipped. When a folder was picked, the folder
 * itself is not created as a page; its contents go directly under the import target.
 */
export function planImport(picked: PickedFile[], directory: boolean): ImportPlan {
  const files = new Map<string, File>()
  const root: Dir = { name: '', dirs: new Map(), files: new Map() }
  for (const p of picked) {
    const path = directory ? p.path.split('/').slice(1).join('/') : baseName(p.path)
    if (!path) continue
    const item = { path, file: p.file }
    files.set(path, p.file)
    if (!isMarkdown(path)) continue
    const parts = path.split('/')
    let dir = root
    for (const part of parts.slice(0, -1)) {
      let next = dir.dirs.get(part)
      if (!next) dir.dirs.set(part, (next = { name: part, dirs: new Map(), files: new Map() }))
      dir = next
    }
    dir.files.set(parts[parts.length - 1], item)
  }
  const nodes = planDir(root)
  let pageCount = 0
  const count = (list: PlanNode[]) => list.forEach((n) => { pageCount++; count(n.children) })
  count(nodes)
  return { nodes, pageCount, files }
}

function planDir(dir: Dir): PlanNode[] {
  const entries: { key: string; node: PlanNode }[] = []
  for (const sub of dir.dirs.values()) {
    const source = take(dir.files, (n) => stripExt(n) === sub.name.toLowerCase())
      ?? take(sub.files, (n) => FOLDER_PAGE_FILES.includes(n) || stripExt(n) === sub.name.toLowerCase())
    const children = planDir(sub)
    if (source || children.length) entries.push({ key: sub.name, node: { title: sub.name, source, children } })
  }
  for (const [name, file] of dir.files) {
    entries.push({ key: stripExt(name), node: { title: stripExt(name), source: file, children: [] } })
  }
  return entries.sort((a, b) => collator.compare(a.key, b.key)).map((e) => e.node)
}

interface ImportPage {
  title: string
  content: string
  children: ImportPage[]
}

export interface ImportResult {
  count: number
  /** Id of the first created top-level page, to open after the import. */
  firstId: number | null
  /** Local images that were referenced but not found among the picked files. */
  missingImages: string[]
}

/**
 * Converts every planned page to HTML (uploading referenced local images) and creates the
 * pages under {@code parentId}. Conversion finishes before any page is created, so a bad file
 * aborts the import without leaving half of it behind.
 */
export async function runImport(spaceId: number, parentId: number | null, plan: ImportPlan,
                                onProgress: (text: string) => void): Promise<ImportResult> {
  const uploads = new Map<string, Promise<string>>()
  const missing = new Set<string>()
  let done = 0

  async function convert(node: PlanNode): Promise<ImportPage> {
    let title = node.title
    let content = ''
    if (node.source) {
      const parsed = parseMarkdown(await readText(node.source.file), stripExt(baseName(node.source.path)))
      title = parsed.title ?? title
      await mapImages(parsed.doc, (src) => resolveImage(src, node.source!.path, plan.files, uploads, missing))
      content = docToHtml(parsed.doc)
      if (content.length > MAX_CONTENT) throw new Error(`「${node.source.path}」内容过长，无法导入`)
    }
    onProgress(`正在转换 ${++done}/${plan.pageCount}`)
    const children: ImportPage[] = []
    for (const child of node.children) children.push(await convert(child))
    return { title: title.trim().slice(0, MAX_TITLE), content, children }
  }

  const pages: ImportPage[] = []
  for (const node of plan.nodes) pages.push(await convert(node))

  let created = 0
  let firstId: number | null = null
  async function send(parent: number | null, list: ImportPage[]) {
    for (const batch of batches(list)) {
      // A subtree too large for one request is created level by level instead.
      const split = batch.length === 1 && (size(batch[0]) > MAX_REQUEST_BYTES || count(batch[0]) > MAX_REQUEST_PAGES)
      const body = split ? [{ ...batch[0], children: [] }] : batch
      const res = await api.post<{ ids: number[]; count: number }>(`/spaces/${spaceId}/import`, { parentId: parent, pages: body })
      created += res.count
      firstId ??= res.ids[0] ?? null
      onProgress(`正在创建页面 ${created}/${plan.pageCount}`)
      if (split) await send(res.ids[0], batch[0].children)
    }
  }
  await send(parentId, pages)
  return { count: created, firstId, missingImages: [...missing] }
}

const encoder = new TextEncoder()
const sizes = new WeakMap<ImportPage, number>()

/** UTF-8 size of the subtree in the request body (plus JSON escaping overhead). */
function size(page: ImportPage): number {
  let s = sizes.get(page)
  if (s == null) {
    s = (encoder.encode(page.title).length + encoder.encode(page.content).length) * 1.1 + 64
      + page.children.reduce((sum, c) => sum + size(c), 0)
    sizes.set(page, s)
  }
  return s
}

function count(page: ImportPage): number {
  return 1 + page.children.reduce((s, c) => s + count(c), 0)
}

/** Groups sibling subtrees so each request stays under the size and page-count limits. */
function batches(list: ImportPage[]): ImportPage[][] {
  const out: ImportPage[][] = []
  let current: ImportPage[] = []
  let bytes = 0
  let pages = 0
  for (const page of list) {
    const b = size(page)
    const n = count(page)
    if (current.length && (bytes + b > MAX_REQUEST_BYTES || pages + n > MAX_REQUEST_PAGES)) {
      out.push(current)
      current = []
      bytes = pages = 0
    }
    current.push(page)
    bytes += b
    pages += n
  }
  if (current.length) out.push(current)
  return out
}

/**
 * Maps an image reference in a Markdown file to an uploaded URL. Web URLs are kept, local
 * paths are resolved against the Markdown file and uploaded once, and data: URIs are uploaded.
 * Returns null (the image is dropped) when a local file cannot be found or uploaded.
 */
async function resolveImage(src: string, mdPath: string, files: Map<string, File>,
                            uploads: Map<string, Promise<string>>, missing: Set<string>): Promise<string | null> {
  if (/^https?:\/\//i.test(src) || src.startsWith('/api/files/')) return src
  if (/^data:image\//i.test(src)) {
    const blob = await fetch(src).then((r) => r.blob()).catch(() => null)
    if (!blob) return null
    return upload(uploads, src, () => new File([blob], 'image', { type: blob.type }))
  }
  if (/^[a-z][a-z0-9+.-]*:/i.test(src)) return null
  const path = resolvePath(mdPath, src)
  const file = path == null ? undefined : files.get(path)
  const type = IMAGE_TYPES[(path ?? '').split('.').pop()!.toLowerCase()]
  if (!file || !type) {
    missing.add(`${mdPath} → ${src}`)
    return null
  }
  return upload(uploads, path!, () => file).catch(() => {
    missing.add(`${mdPath} → ${src}`)
    return null
  })
}

function upload(uploads: Map<string, Promise<string>>, key: string, file: () => File): Promise<string> {
  let p = uploads.get(key)
  if (!p) uploads.set(key, (p = uploadImage(file())))
  return p
}

/** Resolves {@code src} relative to the folder of {@code from}; null if it points outside the selection. */
function resolvePath(from: string, src: string): string | null {
  let ref = src.replace(/[?#].*$/, '').replace(/\\/g, '/')
  try {
    ref = decodeURIComponent(ref)
  } catch {
    // Keep the raw path when it is not valid percent-encoding.
  }
  const parts = ref.startsWith('/') ? [] : from.split('/').slice(0, -1)
  for (const part of ref.split('/')) {
    if (part === '' || part === '.') continue
    if (part === '..') {
      if (!parts.length) return null
      parts.pop()
    } else {
      parts.push(part)
    }
  }
  return parts.join('/')
}

/** Removes and returns the first file whose lower-cased name matches. */
function take(files: Map<string, PickedFile>, match: (lowerName: string) => boolean): PickedFile | null {
  for (const [name, file] of files) {
    if (match(name.toLowerCase())) {
      files.delete(name)
      return file
    }
  }
  return null
}
