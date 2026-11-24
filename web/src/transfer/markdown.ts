import { generateHTML, generateJSON, type JSONContent } from '@tiptap/core'
import { MarkdownManager } from '@tiptap/markdown'
import { schemaExtensions } from '../editor/schema'

/**
 * Markdown <-> editor HTML conversion. Both directions go through the editor's own schema
 * (via Tiptap JSON), so imported pages contain only what the editor can display and edit.
 */

// The editor only has H1-H3. Parse with all six levels and clamp afterwards, otherwise
// marked turns #### headings into plain paragraphs.
const parseExtensions = schemaExtensions.map((e) =>
  e.name === 'starterKit' ? e.configure({ heading: { levels: [1, 2, 3, 4, 5, 6] } }) : e,
)
let parser: MarkdownManager | undefined
let serializer: MarkdownManager | undefined

function walk(node: JSONContent, fn: (n: JSONContent) => void) {
  fn(node)
  node.content?.forEach((child) => walk(child, fn))
}

export interface ParsedMarkdown {
  title: string | null
  doc: JSONContent
}

/**
 * Parses a Markdown file. The title comes from front matter or, failing that, from a leading
 * H1, which is then removed from the body (exported files start with "# 页面标题").
 */
export function parseMarkdown(source: string, fileTitle?: string): ParsedMarkdown {
  parser ??= new MarkdownManager({ extensions: parseExtensions })
  let text = source.replace(/^﻿/, '').replace(/\r\n?/g, '\n')
  let title: string | null = null
  const front = /^---\n([\s\S]*?)\n---(?:\n|$)/.exec(text)
  if (front) {
    const m = /^title:\s*(.+)$/m.exec(front[1])
    if (m) title = m[1].trim().replace(/^(['"])(.*)\1$/, '$2').trim() || null
    text = text.slice(front[0].length)
  }
  const doc = parser.parse(text)
  walk(doc, (n) => {
    if (n.type === 'heading' && Number(n.attrs?.level) > 3) n.attrs = { ...n.attrs, level: 3 }
  })
  // A leading H1 is the document title when it is the only H1 or matches the file name (the
  // layout written by export). Otherwise several H1s are section headings and stay in the body.
  const first = doc.content?.[0]
  const h1Count = (doc.content ?? []).filter((n) => n.type === 'heading' && n.attrs?.level === 1).length
  if (first?.type === 'heading' && first.attrs?.level === 1) {
    const h1 = (first.content ?? []).map((c) => c.text ?? '').join('').trim()
    const isTitle = h1Count === 1 || (fileTitle != null && h1 === fileTitle) || h1 === title
    if (h1 && isTitle && (title == null || h1 === title)) {
      title = h1
      doc.content!.shift()
    }
  }
  return { title, doc }
}

/** Calls {@code fn} for every image node; it returns the new src, or null to remove the image. */
export async function mapImages(doc: JSONContent, fn: (src: string) => Promise<string | null>) {
  if (!doc.content) return
  const kept: JSONContent[] = []
  for (const child of doc.content) {
    if (child.type === 'image' && child.attrs?.src) {
      const src = await fn(String(child.attrs.src))
      if (src == null) continue
      child.attrs = { ...child.attrs, src }
    }
    await mapImages(child, fn)
    kept.push(child)
  }
  doc.content = kept
}

export function docToHtml(doc: JSONContent): string {
  return generateHTML(doc, schemaExtensions)
}

export function htmlToDoc(html: string): JSONContent {
  return generateJSON(html || '<p></p>', schemaExtensions) as JSONContent
}

/** Renders a page as Markdown with its title as the leading H1. */
export function pageToMarkdown(title: string, doc: JSONContent): string {
  serializer ??= new MarkdownManager({ extensions: schemaExtensions })
  // The image serializer writes alt text verbatim, so a "]" would end it early.
  walk(doc, (n) => {
    if (n.type === 'image' && n.attrs?.alt) n.attrs = { ...n.attrs, alt: String(n.attrs.alt).replace(/[[\]\\]/g, '\\$&') }
  })
  const body = serializer.serialize(doc).trim()
  const heading = `# ${title || '无标题'}`
  return body ? `${heading}\n\n${body}\n` : `${heading}\n`
}
