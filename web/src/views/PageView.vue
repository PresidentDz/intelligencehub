<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { api, ApiError, errorMessage } from '../api'
import { useSpaces } from '../stores/spaces'
import type { Comment, CommentAnchor, PageDetail, TextAnchor } from '../types'
import { SPACE_ROLE_LABEL } from '../types'
import { fullTime, PAGE_ICONS, relativeTime, toast } from '../utils'
import BlockEditor from '../editor/BlockEditor.vue'
import CommentPanel from '../components/CommentPanel.vue'
import HistoryPanel from '../components/HistoryPanel.vue'
import { createPage } from '../components/treeActions'
import ImportDialog from '../components/ImportDialog.vue'
import { exportPageMarkdown, exportZip } from '../transfer/exporter'

const props = defineProps<{ spaceId: string; pageId: string }>()
const spaces = useSpaces()
const router = useRouter()
const SAVE_DELAY = 800

const page = ref<PageDetail | null>(null)
const loadError = ref('')
const title = ref('')
const icon = ref('')
const content = ref('')
const commentAnchors = ref<CommentAnchor[]>([])
const comments = ref<Comment[]>([])
const selectionAnchor = ref<TextAnchor | null>(null)
const selectionTop = ref<number | null>(null)
const hasAnchoredComments = computed(() => comments.value.some((comment) =>
  comment.anchorStart != null && comment.anchorEnd != null && !!comment.quote,
))
const hasFullTextComments = computed(() => comments.value.some((comment) =>
  comment.anchorStart == null && comment.anchorEnd == null && !comment.quote,
))
const hasDocumentContent = computed(() =>
  /<(?:img|table|video|audio|iframe)\b/i.test(content.value) ||
  content.value.replace(/<[^>]*>/g, '').replace(/&nbsp;|&#160;/gi, ' ').trim().length > 0,
)
const status = ref<'saved' | 'dirty' | 'saving' | 'error' | 'conflict'>('saved')
const panel = ref<'comments' | 'history' | null>(null)
const iconPicker = ref(false)
const editorRef = ref<InstanceType<typeof BlockEditor>>()
const transferMenu = ref(false)
const importOpen = ref(false)
const exporting = ref(false)
const hasChildren = computed(() => spaces.pages.some((p) => p.parentId === Number(props.pageId)))

function onCommentSelection(anchor: TextAnchor) {
  selectionAnchor.value = anchor
  panel.value = 'comments'
  void updateSelectionTop()
}

function onCommentClick(commentId: number) {
  const comment = comments.value.find((item) => item.id === commentId)
  if (!comment || comment.anchorStart == null || comment.anchorEnd == null || !comment.quote) return
  selectionAnchor.value = { start: comment.anchorStart, end: comment.anchorEnd, quote: comment.quote }
  panel.value = 'comments'
  void updateSelectionTop()
}

async function updateSelectionTop() {
  await nextTick()
  if (selectionAnchor.value) selectionTop.value = editorRef.value?.anchorTop(selectionAnchor.value) ?? null
}

function onCommentsLoaded(loadedComments: Comment[]) {
  comments.value = loadedComments
  commentAnchors.value = loadedComments.flatMap((comment) =>
    comment.anchorStart != null && comment.anchorEnd != null && comment.quote
      ? [{ id: comment.id, start: comment.anchorStart, end: comment.anchorEnd, quote: comment.quote }]
      : [],
  )
}

function selectCommentAnchor(anchor: TextAnchor) {
  editorRef.value?.focusAnchor(anchor)
}

function closeComments() {
  panel.value = null
  selectionAnchor.value = null
  selectionTop.value = null
}

function toggleComments() {
  if (panel.value === 'comments') closeComments()
  else if (hasAnchoredComments.value) panel.value = 'comments'
}

/** Exports this page as .md, or with its subpages as a .zip. Pending edits are saved first. */
async function exportPage(withChildren: boolean) {
  transferMenu.value = false
  if (!page.value || exporting.value) return
  exporting.value = true
  try {
    await save()
    if (withChildren) {
      const n = await exportZip(page.value.spaceId, page.value.id, title.value.trim() || '无标题', () => {})
      toast(`已导出 ${n} 个页面`)
    } else {
      await exportPageMarkdown(page.value.spaceId, page.value.id)
    }
  } catch (e) {
    toast(errorMessage(e), 'error')
  } finally {
    exporting.value = false
  }
}

const canEdit = computed(() => !!page.value && page.value.myRole !== 'reader' && status.value !== 'conflict')
const statusText = computed(() => ({
  saved: page.value ? `已保存 · ${relativeTime(page.value.updatedAt)}` : '',
  dirty: '编辑中…',
  saving: '保存中…',
  error: '保存失败，稍后自动重试',
  conflict: '页面已被他人修改',
}[status.value]))

let timer: ReturnType<typeof setTimeout> | undefined
let inFlight: Promise<void> | null = null

async function load() {
  try {
    const data = await api.get<{ page: PageDetail }>(`/pages/${props.pageId}`)
    if (String(data.page.spaceId) !== props.spaceId) {
      router.replace({ name: 'page', params: { spaceId: data.page.spaceId, pageId: data.page.id } })
      return
    }
    page.value = data.page
    title.value = data.page.title
    icon.value = data.page.icon
    content.value = data.page.content
    commentAnchors.value = []
    try {
      const commentsData = await api.get<{ comments: Comment[] }>(`/pages/${props.pageId}/comments`)
      onCommentsLoaded(commentsData.comments)
    } catch (e) {
      toast(errorMessage(e), 'error')
    }
    status.value = 'saved'
    document.title = `${data.page.title || '无标题'} · 知序`
  } catch (e) {
    loadError.value = errorMessage(e)
  }
}

function schedule() {
  if (!canEdit.value) return
  status.value = 'dirty'
  clearTimeout(timer)
  timer = setTimeout(save, SAVE_DELAY)
}

/** Saves the latest local state. Calls made while a save is running chain after it. */
async function save(): Promise<void> {
  clearTimeout(timer)
  if (inFlight) {
    await inFlight
    if (status.value === 'dirty' || status.value === 'error') return save()
    return
  }
  if (!page.value || (status.value !== 'dirty' && status.value !== 'error')) return
  const body = { title: title.value.trim(), icon: icon.value, content: content.value, revision: page.value.revision }
  status.value = 'saving'
  inFlight = (async () => {
    try {
      const res = await api.put<{ revision: number; updatedAt: string }>(`/pages/${props.pageId}`, body)
      page.value!.revision = res.revision
      page.value!.updatedAt = res.updatedAt
      // Edits typed while saving keep the page dirty and trigger another save.
      const changed = body.title !== title.value.trim() || body.icon !== icon.value || body.content !== content.value
      status.value = changed ? 'dirty' : 'saved'
      if (changed) timer = setTimeout(save, SAVE_DELAY)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        status.value = 'conflict'
      } else {
        status.value = 'error'
        toast(errorMessage(e), 'error')
        timer = setTimeout(save, 5000)
      }
    } finally {
      inFlight = null
    }
  })()
  await inFlight
}

function onTitle(e: Event) {
  title.value = (e.target as HTMLTextAreaElement).value.replace(/\n/g, '')
  spaces.patchPage(Number(props.pageId), { title: title.value.trim() })
  schedule()
}

function onTitleKey(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    editorRef.value?.focusEnd()
  }
}

function chooseIcon(value: string) {
  icon.value = value
  iconPicker.value = false
  spaces.patchPage(Number(props.pageId), { icon: value })
  schedule()
}

function onContent(html: string) {
  content.value = html
  schedule()
}

async function toggleFavorite() {
  if (!page.value) return
  try {
    const res = page.value.favorite
      ? await api.del<{ favorite: boolean }>(`/pages/${props.pageId}/favorite`)
      : await api.put<{ favorite: boolean }>(`/pages/${props.pageId}/favorite`)
    page.value.favorite = res.favorite
    toast(res.favorite ? '已收藏' : '已取消收藏')
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}

async function onRestored() {
  panel.value = null
  await load()
  await spaces.loadTree(Number(props.spaceId))
  toast('已恢复到该版本')
}

function copyLink() {
  navigator.clipboard?.writeText(location.href).then(() => toast('链接已复制'), () => toast('复制失败', 'error'))
}

function beforeUnload(e: BeforeUnloadEvent) {
  if (status.value === 'dirty' || status.value === 'saving' || status.value === 'error') {
    save()
    e.preventDefault()
  }
}

onMounted(() => {
  load()
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  clearTimeout(timer)
  document.title = '知序 · 团队知识库'
})
onBeforeRouteLeave(async () => {
  await save()
  return true
})
</script>

<template>
  <div v-if="loadError" class="page-scroll"><p class="empty">{{ loadError }}</p></div>
  <div v-else-if="page" class="page-layout">
    <div class="page-main">
      <header class="page-header">
        <nav class="breadcrumb ellipsis">
          <RouterLink :to="{ name: 'space', params: { spaceId: page.spaceId } }">{{ page.spaceIcon }} {{ page.spaceName }}</RouterLink>
          <template v-for="a in page.ancestors" :key="a.id">
            <span class="crumb-sep">&gt;</span>
            <RouterLink :to="{ name: 'page', params: { spaceId: page.spaceId, pageId: a.id } }">{{ a.title || '无标题' }}</RouterLink>
          </template>
          <span class="crumb-sep">&gt;</span><span>{{ title || '无标题' }}</span>
        </nav>
        <div class="header-actions">
          <span class="save-status" :class="status" :title="fullTime(page.updatedAt)">{{ statusText }}</span>
          <span v-if="page.myRole === 'reader'" class="badge">{{ SPACE_ROLE_LABEL.reader }}</span>
          <button class="icon-btn" :title="page.favorite ? '取消收藏' : '收藏'" @click="toggleFavorite">{{ page.favorite ? '⭐' : '☆' }}</button>
          <button class="icon-btn" title="复制链接" @click="copyLink">🔗</button>
          <button class="icon-btn" :class="{ on: panel === 'comments' }" title="评论" @click="toggleComments">💬</button>
          <button class="icon-btn" :class="{ on: panel === 'history' }" title="历史版本" @click="panel = panel === 'history' ? null : 'history'">🕘</button>
          <div class="menu-anchor">
            <button class="icon-btn" :class="{ on: transferMenu }" title="导入 / 导出" :disabled="exporting"
                    @click="transferMenu = !transferMenu">{{ exporting ? '⏳' : '⇅' }}</button>
            <div v-if="transferMenu" class="menu-popover" @mouseleave="transferMenu = false">
              <button @click="exportPage(false)">导出为 Markdown（.md）</button>
              <button v-if="hasChildren" @click="exportPage(true)">导出本页及子页面（.zip）</button>
              <button v-if="page.myRole !== 'reader'" @click="transferMenu = false; importOpen = true">导入 Markdown 为子页面</button>
            </div>
          </div>
          <button v-if="page.myRole !== 'reader'" class="btn small" @click="createPage(router, page.spaceId, page.id)">＋ 子页面</button>
        </div>
      </header>

      <div v-if="status === 'conflict'" class="conflict-bar">
        其他人在你编辑期间修改了这个页面，你最近的修改没有保存。
        <button class="btn small" @click="load">加载最新内容</button>
      </div>

      <div class="page-scroll" @scroll="updateSelectionTop">
        <article class="doc">
          <div class="doc-icon-row">
            <button class="doc-icon" :class="{ empty: !icon }" :disabled="!canEdit" @click="iconPicker = !iconPicker">
              {{ icon || (canEdit ? '＋ 添加图标' : '') }}
            </button>
            <div v-if="iconPicker" class="icon-popover">
              <button v-for="i in PAGE_ICONS" :key="i" class="icon-choice" @click="chooseIcon(i)">{{ i }}</button>
              <button class="btn small ghost" @click="chooseIcon('')">移除</button>
            </div>
          </div>
          <textarea class="doc-title-input" :value="title" :readonly="!canEdit" rows="1" maxlength="240"
                    placeholder="无标题" @input="onTitle" @keydown="onTitleKey" />
          <p class="doc-meta muted tiny">{{ page.createdBy }} 创建于 {{ fullTime(page.createdAt) }} · {{ page.updatedBy }} 最后编辑</p>
          <BlockEditor ref="editorRef" :content="content" :editable="canEdit" :comment-anchors="commentAnchors"
                       @update="onContent" @comment-selection="onCommentSelection" @comment-click="onCommentClick" />
          <CommentPanel v-if="hasDocumentContent || hasFullTextComments" mode="section" :page-id="page.id" :can-moderate="page.myRole === 'admin'" :anchor="null" :comments="comments"
                        @comments-loaded="onCommentsLoaded" @select-anchor="selectCommentAnchor" />
        </article>
      </div>
    </div>

    <CommentPanel v-if="panel === 'comments' && (hasAnchoredComments || selectionAnchor)" mode="anchor" :page-id="page.id" :can-moderate="page.myRole === 'admin'" :anchor="selectionAnchor" :anchor-top="selectionTop" :comments="comments"
                  @close="closeComments" @comments-loaded="onCommentsLoaded" @anchor-created="selectionAnchor = null"
                  @select-anchor="selectCommentAnchor" />
    <HistoryPanel v-if="panel === 'history'" :page-id="page.id" :can-restore="canEdit" @close="panel = null" @restored="onRestored" />
    <ImportDialog v-if="importOpen" :space-id="page.spaceId" :parent="{ id: page.id, title }" @close="importOpen = false" />
  </div>
</template>
