<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { Extension } from '@tiptap/core'
import { Plugin, PluginKey, TextSelection } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import { EditorContent, useEditor, type Editor } from '@tiptap/vue-3'
import { BubbleMenu } from '@tiptap/vue-3/menus'
import { Placeholder } from '@tiptap/extensions'
import { uploadImage, errorMessage } from '../api'
import { toast } from '../utils'
import { pickImage } from './commands'
import { SlashCommands } from './slashCommands'
import { schemaExtensions } from './schema'
import type { CommentAnchor, TextAnchor } from '../types'

const props = defineProps<{ content: string; editable: boolean; commentAnchors?: CommentAnchor[] }>()
const emit = defineEmits<{ update: [html: string]; commentSelection: [anchor: TextAnchor]; commentClick: [commentId: number] }>()

const commentAnchorsKey = new PluginKey<DecorationSet>('commentAnchors')

function resolveAnchor(doc: Parameters<typeof DecorationSet.create>[0], anchor: TextAnchor): TextAnchor | null {
  if (anchor.start >= 1 && anchor.end > anchor.start && anchor.end <= doc.content.size &&
      doc.textBetween(anchor.start, anchor.end, '\n', '\n') === anchor.quote) return anchor

  const matches: TextAnchor[] = []
  doc.descendants((node, pos) => {
    if (!node.isTextblock || !anchor.quote) return
    const text = node.textBetween(0, node.content.size, '\n', '\n')
    let index = text.indexOf(anchor.quote)
    while (index >= 0) {
      matches.push({ start: pos + 1 + index, end: pos + 1 + index + anchor.quote.length, quote: anchor.quote })
      index = text.indexOf(anchor.quote, index + 1)
    }
  })
  return matches.sort((a, b) => Math.abs(a.start - anchor.start) - Math.abs(b.start - anchor.start))[0] ?? null
}

const CommentAnchors = Extension.create({
  name: 'commentAnchors',
  addProseMirrorPlugins() {
    return [new Plugin<DecorationSet>({
      key: commentAnchorsKey,
      state: {
        init: () => DecorationSet.empty,
        apply(transaction, decorations) {
          const anchors = transaction.getMeta(commentAnchorsKey) as CommentAnchor[] | undefined
          if (anchors) {
            const ranges = anchors.flatMap((anchor) => {
              const range = resolveAnchor(transaction.doc, anchor)
              return range ? [Decoration.inline(range.start, range.end, {
                class: 'comment-anchor',
                'data-comment-id': String(anchor.id),
              })] : []
            })
            return DecorationSet.create(transaction.doc, ranges)
          }
          return decorations.map(transaction.mapping, transaction.doc)
        },
      },
      props: {
        decorations(state) {
          return commentAnchorsKey.getState(state) ?? DecorationSet.empty
        },
      },
    })]
  },
})

const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']

async function insertFiles(editor: Editor, files: File[], pos?: number) {
  for (const file of files.filter((f) => IMAGE_TYPES.includes(f.type))) {
    try {
      const src = await uploadImage(file)
      const chain = editor.chain().focus()
      ;(pos != null ? chain.insertContentAt(pos, { type: 'image', attrs: { src, alt: file.name } }) : chain.setImage({ src, alt: file.name })).run()
    } catch (e) {
      toast(`图片上传失败：${errorMessage(e)}`, 'error')
    }
  }
}

async function chooseImage(editor: Editor) {
  const file = await pickImage()
  if (file) await insertFiles(editor, [file])
}

const editor = useEditor({
  content: props.content,
  editable: props.editable,
  extensions: [
    ...schemaExtensions,
    CommentAnchors,
    Placeholder.configure({
      placeholder: ({ node }) => (node.type.name === 'heading' ? '标题' : '输入 / 插入内容块'),
    }),
    SlashCommands.configure({ insertImage: chooseImage }),
  ],
  editorProps: {
    attributes: { class: 'doc-body', spellcheck: 'false' },
    handleClick: (_view, _pos, event) => {
      const eventTarget = event.target
      const target = eventTarget instanceof Element ? eventTarget : eventTarget instanceof Node ? eventTarget.parentElement : null
      if (!target) return false
      const marker = target.closest<HTMLElement>('[data-comment-id]')
      if (!marker) return false
      const commentId = Number(marker.dataset.commentId)
      if (Number.isFinite(commentId)) emit('commentClick', commentId)
      return false
    },
    handlePaste: (_view, event) => {
      const files = Array.from(event.clipboardData?.files ?? [])
      if (!files.some((f) => IMAGE_TYPES.includes(f.type)) || !editor.value) return false
      insertFiles(editor.value, files)
      return true
    },
    handleDrop: (view, event, _slice, moved) => {
      const files = Array.from(event.dataTransfer?.files ?? [])
      if (moved || !files.some((f) => IMAGE_TYPES.includes(f.type)) || !editor.value) return false
      const pos = view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos
      insertFiles(editor.value, files, pos)
      return true
    },
  },
  onUpdate: ({ editor }) => emit('update', editor.getHTML()),
  onCreate: ({ editor }) => editor.view.dispatch(editor.state.tr.setMeta(commentAnchorsKey, props.commentAnchors ?? [])),
})

watch(() => props.editable, (value) => editor.value?.setEditable(value))
watch(() => props.commentAnchors, (anchors) => {
  if (editor.value) editor.value.view.dispatch(editor.value.state.tr.setMeta(commentAnchorsKey, anchors ?? []))
}, { deep: true })

// Content is replaced from outside only when another version is loaded (restore, reload).
watch(() => props.content, (html) => {
  if (editor.value && html !== editor.value.getHTML()) editor.value.commands.setContent(html, { emitUpdate: false })
})

onBeforeUnmount(() => editor.value?.destroy())

function setLink() {
  if (!editor.value) return
  const previous = editor.value.getAttributes('link').href as string | undefined
  const url = window.prompt('链接地址', previous ?? 'https://')
  if (url === null) return
  if (url.trim() === '') editor.value.chain().focus().extendMarkRange('link').unsetLink().run()
  else editor.value.chain().focus().extendMarkRange('link').setLink({ href: url.trim() }).run()
}

function focusEnd() {
  editor.value?.commands.focus('end')
}

function commentSelection() {
  if (!editor.value) return
  const { from, to, empty } = editor.value.state.selection
  if (empty) return
  const quote = editor.value.state.doc.textBetween(from, to, '\n', '\n')
  if (!quote.trim()) return
  emit('commentSelection', { start: from, end: to, quote })
}

function focusAnchor(anchor: TextAnchor) {
  if (!editor.value) return
  const range = resolveAnchor(editor.value.state.doc, anchor)
  if (!range) return
  editor.value.view.dispatch(editor.value.state.tr.setSelection(TextSelection.create(editor.value.state.doc, range.start, range.end)))
  editor.value.commands.focus()
}

function anchorTop(anchor: TextAnchor): number | null {
  if (!editor.value) return null
  const range = resolveAnchor(editor.value.state.doc, anchor)
  if (!range) return null
  return editor.value.view.coordsAtPos(range.start).top
}

defineExpose({ focusEnd, focusAnchor, anchorTop })
</script>

<template>
  <div class="block-editor">
    <BubbleMenu v-if="editor && editable" :editor="editor" class="bubble-menu"
                :should-show="({ editor: ed, state }) => !state.selection.empty && !ed.isActive('image') && !ed.isActive('codeBlock')">
      <button :class="{ on: editor.isActive('heading', { level: 1 }) }" title="一级标题" @click="editor.chain().focus().toggleHeading({ level: 1 }).run()">H1</button>
      <button :class="{ on: editor.isActive('heading', { level: 2 }) }" title="二级标题" @click="editor.chain().focus().toggleHeading({ level: 2 }).run()">H2</button>
      <button :class="{ on: editor.isActive('heading', { level: 3 }) }" title="三级标题" @click="editor.chain().focus().toggleHeading({ level: 3 }).run()">H3</button>
      <span class="sep" />
      <button :class="{ on: editor.isActive('bold') }" title="加粗 Ctrl+B" @click="editor.chain().focus().toggleBold().run()"><b>B</b></button>
      <button :class="{ on: editor.isActive('italic') }" title="斜体 Ctrl+I" @click="editor.chain().focus().toggleItalic().run()"><i>I</i></button>
      <button :class="{ on: editor.isActive('underline') }" title="下划线 Ctrl+U" @click="editor.chain().focus().toggleUnderline().run()"><u>U</u></button>
      <button :class="{ on: editor.isActive('strike') }" title="删除线" @click="editor.chain().focus().toggleStrike().run()"><s>S</s></button>
      <button :class="{ on: editor.isActive('code') }" title="行内代码" @click="editor.chain().focus().toggleCode().run()">&lt;/&gt;</button>
      <button :class="{ on: editor.isActive('link') }" title="链接" @click="setLink">🔗</button>
      <button title="评论选中内容" @mousedown.prevent="commentSelection">💬 评论</button>
    </BubbleMenu>
    <BubbleMenu v-if="editor && !editable" :editor="editor" class="bubble-menu"
                :should-show="({ editor: ed, state }) => !state.selection.empty && !ed.isActive('image') && !ed.isActive('codeBlock')">
      <button title="评论选中内容" @mousedown.prevent="commentSelection">💬 评论</button>
    </BubbleMenu>

    <div v-if="editor && editable && editor.isActive('table')" class="table-tools">
      <button @click="editor.chain().focus().addRowAfter().run()">＋行</button>
      <button @click="editor.chain().focus().addColumnAfter().run()">＋列</button>
      <button @click="editor.chain().focus().deleteRow().run()">－行</button>
      <button @click="editor.chain().focus().deleteColumn().run()">－列</button>
      <button @click="editor.chain().focus().deleteTable().run()">删除表格</button>
    </div>

    <EditorContent :editor="editor" />
  </div>
</template>
