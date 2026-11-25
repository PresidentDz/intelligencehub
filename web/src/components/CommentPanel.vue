<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, nextTick, watch } from 'vue'
import { api, errorMessage } from '../api'
import { useAuth } from '../stores/auth'
import type { Comment, DirectoryUser, TextAnchor } from '../types'
import { fullTime, relativeTime, toast } from '../utils'

const props = defineProps<{ mode: 'anchor' | 'section'; pageId: number; canModerate: boolean; anchor: TextAnchor | null; anchorTop?: number | null; comments: Comment[] }>()
const emit = defineEmits<{ close: []; commentsLoaded: [comments: Comment[]]; anchorCreated: []; selectAnchor: [anchor: TextAnchor] }>()
const auth = useAuth()
const comments = ref<Comment[]>(props.comments)
const body = ref('')
const busy = ref(false)
const editingId = ref<number | null>(null)
const replyingToId = ref<number | null>(null)
const editBody = ref('')
const mentionUsers = ref<DirectoryUser[]>([])
const mentionQuery = ref('')
const mentionStart = ref(-1)
const mentionEnd = ref(-1)
const mentionIndex = ref(0)
const textarea = ref<HTMLTextAreaElement>()
const selectedMentions = ref<DirectoryUser[]>([])
const editMentions = ref<DirectoryUser[]>([])
const mentionTarget = ref<'new' | 'edit'>('new')
const commentPositions = ref<{ comment: Comment; top: number }[]>([])
const moduleRoot = ref<HTMLElement>()
const panelBody = ref<HTMLElement>()
const showMentionMenu = computed(() => mentionStart.value >= 0 && mentionUsers.value.length > 0)
const visibleComments = computed(() => comments.value.filter((comment) => props.mode === 'anchor'
  ? comment.anchorStart != null && comment.anchorEnd != null && !!comment.quote
  : comment.anchorStart == null && comment.anchorEnd == null && !comment.quote))
function threadCount(comment: Comment): number {
  return 1 + (comment.replies ?? []).reduce((total, reply) => total + threadCount(reply), 0)
}
const visibleCommentCount = computed(() => visibleComments.value.reduce((total, comment) => total + threadCount(comment), 0))
const replyingTo = computed(() => replyingToId.value == null ? null : findComment(comments.value, replyingToId.value))
const composerTop = ref(0)
let mentionTimer: ReturnType<typeof setTimeout> | undefined
let mentionRequest = 0
let scrollContainer: HTMLElement | null = null
let editorObserver: MutationObserver | undefined

async function updatePositions() {
  await nextTick()
  const body = panelBody.value
  if (!scrollContainer || !body) return
  if (props.mode === 'section') {
    commentPositions.value = visibleComments.value.map((comment) => ({ comment, top: 0 }))
    return
  }
  const container = scrollContainer
  const bodyRect = body.getBoundingClientRect()
  const anchored = visibleComments.value.flatMap((comment) => {
    const target = container.querySelector<HTMLElement>(`[data-comment-id="${comment.id}"]`)
    return target ? [{ comment, top: target.getBoundingClientRect().top - bodyRect.top }] : []
  })
  commentPositions.value = anchored
  composerTop.value = props.anchorTop == null ? 0 : props.anchorTop - bodyRect.top
}

watch(() => props.comments, (value) => {
  comments.value = value
  requestAnimationFrame(() => void updatePositions())
}, { deep: true })
watch(() => props.anchorTop, () => void updatePositions())

function updateMentionSearch(target: 'new' | 'edit' = 'new') {
  mentionTarget.value = target
  const activeInput = document.activeElement
  const input = target === 'new' ? textarea.value : activeInput instanceof HTMLTextAreaElement ? activeInput : null
  if (!input) return
  const cursor = input.selectionStart
  const text = target === 'new' ? body.value : editBody.value
  const beforeCursor = text.slice(0, cursor)
  const match = /(?:^|\s)@([^\s@]*)$/.exec(beforeCursor)
  if (!match) {
    mentionStart.value = -1
    mentionUsers.value = []
    mentionRequest++
    clearTimeout(mentionTimer)
    return
  }
  mentionStart.value = cursor - match[1].length - 1
  mentionEnd.value = cursor
  mentionQuery.value = match[1]
  mentionIndex.value = 0
  clearTimeout(mentionTimer)
  const requestId = ++mentionRequest
  mentionTimer = setTimeout(async () => {
    try {
      const users = (await api.get<{ users: DirectoryUser[] }>(`/auth/users?q=${encodeURIComponent(mentionQuery.value.trim())}`)).users
      if (requestId === mentionRequest) mentionUsers.value = users
    } catch {
      mentionUsers.value = []
    }
  }, 180)
}

function updateNewMentionSearch() {
  updateMentionSearch('new')
}

function updateEditMentionSearch() {
  updateMentionSearch('edit')
}

async function insertMention(user: DirectoryUser) {
  const target = mentionTarget.value
  const activeInput = document.activeElement
  const input = target === 'new' ? textarea.value : activeInput instanceof HTMLTextAreaElement ? activeInput : null
  if (!input || mentionStart.value < 0) return
  const start = mentionStart.value
  const end = mentionEnd.value
  const inserted = `@${user.name} `
  const text = target === 'new' ? body.value : editBody.value
  const nextText = text.slice(0, start) + inserted + text.slice(end)
  if (target === 'new') body.value = nextText
  else editBody.value = nextText
  const recipients = target === 'new' ? selectedMentions.value : editMentions.value
  if (!recipients.some((item) => item.id === user.id)) recipients.push(user)
  mentionStart.value = -1
  mentionUsers.value = []
  await nextTick()
  input.focus()
  input.setSelectionRange(start + inserted.length, start + inserted.length)
}

function onBodyKeydown(event: KeyboardEvent) {
  if (showMentionMenu.value && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
    event.preventDefault()
    const direction = event.key === 'ArrowDown' ? 1 : -1
    mentionIndex.value = (mentionIndex.value + direction + mentionUsers.value.length) % mentionUsers.value.length
  } else if (showMentionMenu.value && event.key === 'Enter') {
    event.preventDefault()
    void insertMention(mentionUsers.value[mentionIndex.value])
  } else if (event.key === 'Escape' && mentionStart.value >= 0) {
    mentionStart.value = -1
    mentionUsers.value = []
  } else if (event.key === 'Enter' && event.ctrlKey) {
    event.preventDefault()
    if (mentionTarget.value === 'new') void submit()
    else {
      const comment = comments.value.find((item) => item.id === editingId.value)
      if (comment) void saveEdit(comment)
    }
  }
}

function beginEdit(comment: Comment) {
  editingId.value = comment.id
  replyingToId.value = null
  body.value = ''
  editBody.value = comment.body
  editMentions.value = [...(comment.mentions ?? [])]
  mentionTarget.value = 'edit'
}

async function beginReply(comment: Comment) {
  replyingToId.value = comment.id
  editingId.value = null
  body.value = ''
  selectedMentions.value = []
  mentionTarget.value = 'new'
  mentionStart.value = -1
  mentionUsers.value = []
  await nextTick()
  textarea.value?.focus()
}

function cancelReply() {
  replyingToId.value = null
  body.value = ''
  selectedMentions.value = []
  mentionStart.value = -1
  mentionUsers.value = []
}

function findComment(commentList: Comment[], id: number): Comment | null {
  for (const comment of commentList) {
    if (comment.id === id) return comment
    const nested = findComment(comment.replies ?? [], id)
    if (nested) return nested
  }
  return null
}

function flattenReplies(comment: Comment) {
  const replies: { comment: Comment; depth: number }[] = []
  const visit = (parent: Comment, depth: number) => {
    for (const reply of parent.replies ?? []) {
      replies.push({ comment: reply, depth })
      visit(reply, depth + 1)
    }
  }
  visit(comment, 1)
  return replies
}

function threadContains(comment: Comment, id: number): boolean {
  return comment.id === id || (comment.replies ?? []).some((reply) => threadContains(reply, id))
}

function cancelEdit() {
  editingId.value = null
  editMentions.value = []
  mentionStart.value = -1
  mentionUsers.value = []
}

function commentParts(comment: Comment) {
  const users = comment.mentions ?? []
  const tokens = users.map((user) => `@${user.name}`).sort((a, b) => b.length - a.length)
  if (!tokens.length) return [{ text: comment.body, userId: null as number | null }]
  const userByToken = new Map(users.map((user) => [`@${user.name}`, user.id]))
  const expression = new RegExp(`(${tokens.map((token) => token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'g')
  return comment.body.split(expression).filter(Boolean).map((text) => ({ text, userId: userByToken.get(text) ?? null }))
}

async function load() {
  comments.value = (await api.get<{ comments: Comment[] }>(`/pages/${props.pageId}/comments`)).comments
  emit('commentsLoaded', comments.value)
  requestAnimationFrame(() => void updatePositions())
}
onMounted(() => {
  scrollContainer = moduleRoot.value?.closest('.page-layout')?.querySelector('.page-scroll') ?? null
  scrollContainer?.addEventListener('scroll', updatePositions)
  window.addEventListener('resize', updatePositions)
  const editorBody = scrollContainer?.querySelector('.doc-body')
  if (editorBody) {
    editorObserver = new MutationObserver(() => requestAnimationFrame(() => void updatePositions()))
    editorObserver.observe(editorBody, { childList: true, subtree: true, characterData: true })
  }
  void updatePositions()
})

async function submit() {
  if (!body.value.trim()) return
  const parentId = replyingToId.value
  const anchor = parentId == null ? props.anchor : null
  busy.value = true
  try {
    await api.post(`/pages/${props.pageId}/comments`, {
      body: body.value,
      ...(anchor ? { anchor } : {}),
      ...(parentId != null ? { parentId } : {}),
      mentions: selectedMentions.value.filter((user) => body.value.includes(`@${user.name}`)).map((user) => user.id),
    })
    body.value = ''
    selectedMentions.value = []
    mentionStart.value = -1
    await load()
    if (anchor) emit('anchorCreated')
    if (parentId != null) replyingToId.value = null
  } catch (e) {
    toast(errorMessage(e), 'error')
  } finally {
    busy.value = false
  }
}

onBeforeUnmount(() => {
  clearTimeout(mentionTimer)
  scrollContainer?.removeEventListener('scroll', updatePositions)
  window.removeEventListener('resize', updatePositions)
  editorObserver?.disconnect()
})

async function remove(c: Comment) {
  if (!confirm('删除这条评论？')) return
  try {
    await api.del(`/comments/${c.id}`)
    await load()
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}

async function saveEdit(c: Comment) {
  const text = editBody.value.trim()
  if (!text) return
  try {
    await api.put(`/comments/${c.id}`, {
      body: text,
      mentions: editMentions.value.filter((user) => text.includes(`@${user.name}`)).map((user) => user.id),
    })
    editingId.value = null
    editMentions.value = []
    mentionStart.value = -1
    mentionUsers.value = []
    await load()
    toast('评论已更新')
  } catch (e) {
    toast(errorMessage(e), 'error')
  }
}
</script>

<template>
  <component :is="mode === 'anchor' ? 'aside' : 'section'" ref="moduleRoot"
             :class="mode === 'anchor' ? ['side-panel', 'comment-module'] : 'article-comment-section'">
    <header v-if="mode === 'anchor'" class="panel-head comment-module-head"><strong>评论（{{ visibleCommentCount }}）</strong>
      <button class="icon-btn" type="button" aria-label="关闭评论" @click="emit('close')">✕</button>
    </header>
    <h2 v-else class="article-comment-title">全文评论（{{ visibleCommentCount }}）</h2>
    <div ref="panelBody" :class="mode === 'anchor' ? 'comment-module-body' : 'article-comment-list'">
      <div v-for="position in commentPositions" :key="position.comment.id" class="comment-module-card" :class="{ 'article-comment-card': mode === 'section' }" :style="mode === 'anchor' ? { top: `${position.top}px` } : undefined">
        <div class="comment-head">
          <span class="avatar small">{{ position.comment.author.slice(0, 1) }}</span>
          <strong>{{ position.comment.author }}</strong>
          <span class="muted tiny" :title="fullTime(position.comment.createdAt)">{{ relativeTime(position.comment.createdAt) }}</span>
          <span v-if="position.comment.userId === auth.user?.id || canModerate || mode === 'anchor'" class="comment-actions">
            <button v-if="editingId !== position.comment.id && (position.comment.userId === auth.user?.id || canModerate)" class="link-btn tiny" @click="beginEdit(position.comment)">编辑</button>
            <button v-if="position.comment.userId === auth.user?.id || canModerate" class="link-btn tiny" @click="remove(position.comment)">删除</button>
            <button v-if="mode === 'anchor'" class="link-btn tiny" @click="beginReply(position.comment)">回复</button>
          </span>
        </div>
        <button v-if="position.comment.anchorStart != null && position.comment.anchorEnd != null && position.comment.quote" class="comment-quote" @click="emit('selectAnchor', { start: position.comment.anchorStart, end: position.comment.anchorEnd, quote: position.comment.quote })">{{ position.comment.quote }}</button>
        <form v-if="editingId === position.comment.id" class="comment-edit-form" @submit.prevent="saveEdit(position.comment)">
          <div class="mention-input-wrap">
            <textarea v-model="editBody" rows="3" maxlength="3000" aria-label="编辑评论" @input="updateEditMentionSearch" @click="updateEditMentionSearch" @keydown="onBodyKeydown" />
            <div v-if="showMentionMenu && mentionTarget === 'edit'" class="mention-menu">
              <button v-for="(user, index) in mentionUsers" :key="user.id" type="button" :class="{ selected: index === mentionIndex }" @mousedown.prevent="insertMention(user)">
                <span class="avatar small">{{ user.name.slice(0, 1) }}</span><span><strong>{{ user.name }}</strong><small>{{ user.username }}<template v-if="user.department"> · {{ user.department }}</template></small></span>
              </button>
            </div>
          </div>
          <div><button class="btn primary small" type="submit" :disabled="!editBody.trim()">保存</button><button class="btn small ghost" type="button" @click="cancelEdit">取消</button></div>
        </form>
        <p v-else class="comment-body"><template v-for="(part, index) in commentParts(position.comment)" :key="index"><span :class="{ 'comment-mention': part.userId != null, 'comment-mention-self': part.userId === auth.user?.id }">{{ part.text }}</span></template></p>
        <div v-if="position.comment.mentions?.length" class="comment-mentions">提及 {{ position.comment.mentions.map((user) => `@${user.name}`).join('、') }}</div>
        <div v-if="position.comment.replies?.length" class="comment-replies">
          <div v-for="item in flattenReplies(position.comment)" :key="item.comment.id" class="comment-reply" :style="{ marginLeft: `${Math.min(item.depth - 1, 4) * 12}px` }">
            <div class="comment-head">
              <strong>{{ item.comment.author }}</strong>
              <span class="muted tiny" :title="fullTime(item.comment.createdAt)">{{ relativeTime(item.comment.createdAt) }}</span>
              <span class="comment-actions">
                <button v-if="editingId !== item.comment.id && (item.comment.userId === auth.user?.id || canModerate)" class="link-btn tiny" @click="beginEdit(item.comment)">编辑</button>
                <button v-if="item.comment.userId === auth.user?.id || canModerate" class="link-btn tiny" @click="remove(item.comment)">删除</button>
                <button v-if="mode === 'anchor'" class="link-btn tiny" @click="beginReply(item.comment)">回复</button>
              </span>
            </div>
            <form v-if="editingId === item.comment.id" class="comment-edit-form" @submit.prevent="saveEdit(item.comment)">
              <div class="mention-input-wrap">
                <textarea v-model="editBody" rows="2" maxlength="3000" aria-label="编辑回复" @input="updateEditMentionSearch" @click="updateEditMentionSearch" @keydown="onBodyKeydown" />
                <div v-if="showMentionMenu && mentionTarget === 'edit'" class="mention-menu">
                  <button v-for="(user, index) in mentionUsers" :key="user.id" type="button" :class="{ selected: index === mentionIndex }" @mousedown.prevent="insertMention(user)">
                    <span class="avatar small">{{ user.name.slice(0, 1) }}</span><span><strong>{{ user.name }}</strong><small>{{ user.username }}<template v-if="user.department"> · {{ user.department }}</template></small></span>
                  </button>
                </div>
              </div>
              <div><button class="btn primary small" type="submit" :disabled="!editBody.trim()">保存</button><button class="btn small ghost" type="button" @click="cancelEdit">取消</button></div>
            </form>
            <p v-else class="comment-body"><template v-for="(part, index) in commentParts(item.comment)" :key="index"><span :class="{ 'comment-mention': part.userId != null, 'comment-mention-self': part.userId === auth.user?.id }">{{ part.text }}</span></template></p>
          </div>
        </div>
        <form v-if="mode === 'anchor' && replyingToId != null && threadContains(position.comment, replyingToId)" class="comment-form comment-reply-form" @submit.prevent="submit">
          <div class="mention-input-wrap">
            <textarea ref="textarea" v-model="body" rows="2" maxlength="3000" :placeholder="`回复 ${replyingTo?.author ?? '评论'}，输入 @ 提及用户`" @input="updateNewMentionSearch" @click="updateNewMentionSearch" @keydown="onBodyKeydown" />
            <div v-if="showMentionMenu && mentionTarget === 'new'" class="mention-menu">
              <button v-for="(user, index) in mentionUsers" :key="user.id" type="button" :class="{ selected: index === mentionIndex }" @mousedown.prevent="insertMention(user)">
                <span class="avatar small">{{ user.name.slice(0, 1) }}</span><span><strong>{{ user.name }}</strong><small>{{ user.username }}<template v-if="user.department"> · {{ user.department }}</template></small></span>
              </button>
            </div>
          </div>
          <div class="comment-reply-actions"><button class="btn primary small" :disabled="busy || !body.trim()">发送</button><button class="btn small ghost" type="button" @click="cancelReply">取消</button></div>
        </form>
      </div>
      <p v-if="!visibleComments.length && mode === 'anchor'" class="muted pad">还没有选中内容的评论</p>
      <p v-if="!visibleComments.length && mode === 'section'" class="muted pad article-comment-empty">还没评论</p>
      <form v-if="mode === 'anchor' && anchor && replyingToId == null" class="comment-form comment-anchor-form" :style="{ top: `${composerTop}px` }" @submit.prevent="submit">
        <p class="comment-quote active">对选中内容评论：“{{ anchor.quote }}”</p>
        <div class="mention-input-wrap">
          <textarea ref="textarea" v-model="body" rows="3" maxlength="3000" placeholder="发表评论，输入 @ 提及用户" @input="updateNewMentionSearch" @click="updateNewMentionSearch" @keydown="onBodyKeydown" />
          <div v-if="showMentionMenu && mentionTarget === 'new'" class="mention-menu">
            <button v-for="(user, index) in mentionUsers" :key="user.id" type="button" :class="{ selected: index === mentionIndex }" @mousedown.prevent="insertMention(user)">
              <span class="avatar small">{{ user.name.slice(0, 1) }}</span><span><strong>{{ user.name }}</strong><small>{{ user.username }}<template v-if="user.department"> · {{ user.department }}</template></small></span>
            </button>
          </div>
        </div>
        <button class="btn primary small" :disabled="busy || !body.trim()">发送</button>
      </form>
    </div>
    <form v-if="mode === 'section'" class="comment-form article-comment-form" @submit.prevent="submit">
      <div class="mention-input-wrap">
        <textarea ref="textarea" v-model="body" rows="3" maxlength="3000" placeholder="发表评论，输入 @ 提及用户" @input="updateNewMentionSearch" @click="updateNewMentionSearch" @keydown="onBodyKeydown" />
        <div v-if="showMentionMenu && mentionTarget === 'new'" class="mention-menu">
          <button v-for="(user, index) in mentionUsers" :key="user.id" type="button" :class="{ selected: index === mentionIndex }" @mousedown.prevent="insertMention(user)">
            <span class="avatar small">{{ user.name.slice(0, 1) }}</span><span><strong>{{ user.name }}</strong><small>{{ user.username }}<template v-if="user.department"> · {{ user.department }}</template></small></span>
          </button>
        </div>
      </div>
      <button class="btn primary small" :disabled="busy || !body.trim()">发送</button>
    </form>
  </component>
</template>
