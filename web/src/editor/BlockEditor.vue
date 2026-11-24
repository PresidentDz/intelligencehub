<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { EditorContent, useEditor, type Editor } from '@tiptap/vue-3'
import { BubbleMenu } from '@tiptap/vue-3/menus'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { TableKit } from '@tiptap/extension-table'
import { Placeholder } from '@tiptap/extensions'
import { uploadImage, errorMessage } from '../api'
import { toast } from '../utils'
import { pickImage } from './commands'
import { SlashCommands } from './slashCommands'

const props = defineProps<{ content: string; editable: boolean }>()
const emit = defineEmits<{ update: [html: string] }>()

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
    StarterKit.configure({
      heading: { levels: [1, 2, 3] },
      link: { openOnClick: false, autolink: true, defaultProtocol: 'https', protocols: ['http', 'https', 'mailto'] },
    }),
    Image.configure({ inline: false }),
    TaskList,
    TaskItem.configure({ nested: true }),
    TableKit.configure({ table: { resizable: false } }),
    Placeholder.configure({
      placeholder: ({ node }) => (node.type.name === 'heading' ? '标题' : '输入 / 插入内容块'),
    }),
    SlashCommands.configure({ insertImage: chooseImage }),
  ],
  editorProps: {
    attributes: { class: 'doc-body', spellcheck: 'false' },
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
})

watch(() => props.editable, (value) => editor.value?.setEditable(value))

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

defineExpose({ focusEnd })
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
