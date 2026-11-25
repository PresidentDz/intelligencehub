export type SystemRole = 'admin' | 'member'
export type SpaceRole = 'admin' | 'editor' | 'reader'
export type Visibility = 'private' | 'public'

export interface User {
  id: number
  username: string
  name: string
  role: SystemRole
  department: string
  active: boolean
}

export interface Space {
  id: number
  name: string
  description: string
  icon: string
  visibility: Visibility
  createdAt: string
  myRole: SpaceRole
  pageCount?: number
}

export interface TreePage {
  id: number
  parentId: number | null
  title: string
  icon: string
  position: number
}

export interface PageDetail {
  id: number
  spaceId: number
  parentId: number | null
  title: string
  icon: string
  content: string
  revision: number
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  spaceName: string
  spaceIcon: string
  favorite: boolean
  ancestors: { id: number; title: string }[]
  myRole: SpaceRole
}

export interface PageSummary {
  id: number
  title: string
  icon: string
  updatedAt: string
  updatedBy: string
  spaceId: number
  spaceName: string
  spaceIcon: string
  excerpt?: string
  visitedAt?: string
  favoritedAt?: string
}

export interface Version {
  id: number
  title: string
  createdAt: string
  updatedAt: string
  editor: string
  content?: string
}

export interface Comment {
  id: number
  parentId: number | null
  body: string
  createdAt: string
  userId: number
  author: string
  anchorStart: number | null
  anchorEnd: number | null
  quote: string | null
  mentions?: DirectoryUser[]
  replies?: Comment[]
}

export interface TextAnchor {
  start: number
  end: number
  quote: string
}

export interface CommentAnchor extends TextAnchor {
  id: number
}

export interface DirectoryUser {
  id: number
  username: string
  name: string
  department: string
}

export interface Member extends DirectoryUser {
  role: SpaceRole
}

export const SPACE_ROLE_LABEL: Record<SpaceRole, string> = { admin: '管理员', editor: '可编辑', reader: '可阅读' }
