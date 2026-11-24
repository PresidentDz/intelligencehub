import type { Editor, Range } from '@tiptap/vue-3'

export interface BlockCommand {
  title: string
  hint: string
  icon: string
  /** Extra search terms, e.g. markdown shortcuts and pinyin initials. */
  keywords: string[]
  run: (editor: Editor, range: Range) => void
}

/** Opens a file picker and resolves with the chosen image, or null if cancelled. */
export function pickImage(): Promise<File | null> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/png,image/jpeg,image/gif,image/webp'
    input.onchange = () => resolve(input.files?.[0] ?? null)
    input.oncancel = () => resolve(null)
    input.click()
  })
}

export function blockCommands(insertImage: (editor: Editor) => void): BlockCommand[] {
  const chain = (editor: Editor, range: Range) => editor.chain().focus().deleteRange(range)
  return [
    { title: '正文', hint: '普通段落', icon: '¶', keywords: ['text', 'p', 'zw'], run: (e, r) => chain(e, r).setParagraph().run() },
    { title: '一级标题', hint: '#', icon: 'H1', keywords: ['h1', 'heading', 'bt'], run: (e, r) => chain(e, r).setHeading({ level: 1 }).run() },
    { title: '二级标题', hint: '##', icon: 'H2', keywords: ['h2', 'heading', 'bt'], run: (e, r) => chain(e, r).setHeading({ level: 2 }).run() },
    { title: '三级标题', hint: '###', icon: 'H3', keywords: ['h3', 'heading', 'bt'], run: (e, r) => chain(e, r).setHeading({ level: 3 }).run() },
    { title: '无序列表', hint: '-', icon: '•', keywords: ['ul', 'bullet', 'list', 'lb'], run: (e, r) => chain(e, r).toggleBulletList().run() },
    { title: '有序列表', hint: '1.', icon: '1.', keywords: ['ol', 'ordered', 'list', 'lb'], run: (e, r) => chain(e, r).toggleOrderedList().run() },
    { title: '待办列表', hint: '[]', icon: '☑', keywords: ['todo', 'task', 'check', 'db'], run: (e, r) => chain(e, r).toggleTaskList().run() },
    { title: '引用', hint: '>', icon: '❝', keywords: ['quote', 'blockquote', 'yy'], run: (e, r) => chain(e, r).toggleBlockquote().run() },
    { title: '代码块', hint: '```', icon: '</>', keywords: ['code', 'pre', 'dm'], run: (e, r) => chain(e, r).toggleCodeBlock().run() },
    { title: '分割线', hint: '---', icon: '—', keywords: ['hr', 'divider', 'line', 'fgx'], run: (e, r) => chain(e, r).setHorizontalRule().run() },
    {
      title: '表格', hint: '3 × 3', icon: '▦', keywords: ['table', 'bg'],
      run: (e, r) => chain(e, r).insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(),
    },
    {
      title: '图片', hint: '上传图片', icon: '🖼', keywords: ['image', 'img', 'picture', 'tp'],
      run: (e, r) => {
        chain(e, r).run()
        insertImage(e)
      },
    },
  ]
}

export function filterCommands(commands: BlockCommand[], query: string): BlockCommand[] {
  const q = query.trim().toLowerCase()
  if (!q) return commands
  return commands.filter((c) => c.title.includes(q) || c.keywords.some((k) => k.startsWith(q)))
}
