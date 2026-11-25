import CodeBlock from '@tiptap/extension-code-block'
import type { Node as ProseMirrorNode } from '@tiptap/pm/model'
import { TextSelection } from '@tiptap/pm/state'
import type { ViewMutationRecord } from '@tiptap/pm/view'

/**
 * Draws ```mermaid fences as diagrams.
 *
 * The fence stays a regular code block, so the stored HTML, the server-side sanitizer and
 * the Markdown import/export are all untouched; only the browser turns the source into a
 * picture. The server safelist has no SVG tags, so a rendered diagram is never sent back.
 */

type MermaidApi = typeof import('mermaid').default

let mermaidApi: Promise<MermaidApi> | null = null

/** Mermaid is loaded on demand, so pages without a diagram never download the bundle. */
function loadMermaid(): Promise<MermaidApi> {
  mermaidApi ??= import('mermaid').then(({ default: mermaid }) => {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      suppressErrorRendering: true,
      theme: 'default',
      fontFamily: 'inherit',
    })
    return mermaid
  })
  return mermaidApi
}

let diagramSeq = 0

function failureText(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error)
  const line = raw.split('\n').map((part) => part.trim()).find(Boolean) ?? 'Unknown error'
  return line.length > 160 ? `${line.slice(0, 160)}…` : line
}

export const MermaidCodeBlock = CodeBlock.extend({
  addNodeView() {
    return ({ node, view, getPos }) => {
      const language = String(node.attrs.language ?? '')
      const isDiagram = language.toLowerCase() === 'mermaid'
      const pre = document.createElement('pre')
      const code = document.createElement('code')
      if (language) code.classList.add(`language-${language}`)
      pre.appendChild(code)

      let dom: HTMLElement = pre
      let preview: HTMLElement | null = null
      let source = node.textContent
      let timer: number | undefined
      let token = 0

      if (isDiagram) {
        preview = document.createElement('div')
        preview.className = 'kb-mermaid-preview'
        preview.contentEditable = 'false'
        dom = document.createElement('div')
        dom.className = 'kb-mermaid-block'
        dom.append(preview, pre)

        preview.addEventListener('click', () => {
          if (!view.editable) return
          if (dom.dataset.sourceVisible === 'true') {
            delete dom.dataset.sourceVisible
            return
          }
          dom.dataset.sourceVisible = 'true'
          const position = getPos()
          if (typeof position === 'number') {
            const cursor = Math.min(position + 1, view.state.doc.content.size)
            view.dispatch(view.state.tr.setSelection(TextSelection.create(view.state.doc, cursor)))
            view.focus()
          }
        })
      }

      async function draw(current: number) {
        if (!preview) return
        try {
          const mermaid = await loadMermaid()
          const { svg } = await mermaid.render(`kb-mermaid-${++diagramSeq}`, source)
          if (current !== token) return
          preview.innerHTML = svg
          preview.removeAttribute('data-error')
          delete dom.dataset.error
        } catch (error) {
          if (current !== token) return
          preview.replaceChildren()
          preview.dataset.error = failureText(error)
          dom.dataset.error = 'true'
        }
      }

      function schedule() {
        window.clearTimeout(timer)
        const current = ++token
        timer = window.setTimeout(() => void draw(current), 200)
      }

      if (isDiagram) schedule()

      return {
        dom,
        contentDOM: code,
        update(updated: ProseMirrorNode) {
          if (updated.type.name !== 'codeBlock') return false
          const nextLanguage = String(updated.attrs.language ?? '')
          if (nextLanguage.toLowerCase() !== language.toLowerCase()) return false
          const nextSource = updated.textContent
          if (nextSource !== source) {
            source = nextSource
            schedule()
          }
          return true
        },
        stopEvent(event: Event) {
          return preview ? !code.contains(event.target as Node) : false
        },
        ignoreMutation(mutation: ViewMutationRecord) {
          if (mutation.type === 'selection') return false
          const target = mutation.target
          if (preview?.contains(target)) return true
          return target === dom && mutation.type === 'attributes'
        },
        destroy() {
          window.clearTimeout(timer)
          token++
        },
      }
    }
  },
})
