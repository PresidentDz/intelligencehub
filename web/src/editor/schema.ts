import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { TableKit } from '@tiptap/extension-table'
import { MermaidCodeBlock } from './mermaid'

/**
 * Extensions that define the document schema. Shared by the editor and the Markdown
 * import/export so both read and write exactly the same set of nodes and marks.
 */
export const schemaExtensions = [
  StarterKit.configure({
    heading: { levels: [1, 2, 3] },
    link: { openOnClick: false, autolink: true, defaultProtocol: 'https', protocols: ['http', 'https', 'mailto'] },
    // Replaced by MermaidCodeBlock, which renders ```mermaid fences as diagrams.
    codeBlock: false,
  }),
  MermaidCodeBlock,
  Image.configure({ inline: false }),
  TaskList,
  TaskItem.configure({ nested: true }),
  TableKit.configure({ table: { resizable: false } }),
]
