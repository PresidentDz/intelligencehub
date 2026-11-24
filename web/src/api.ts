/**
 * Thin fetch wrapper. By default the API is same-origin (/api), served through the Vite
 * proxy in development and through a reverse proxy in production. Set VITE_API_BASE to
 * call a backend on another origin (the backend must then list this origin in KB_CORS_ORIGINS).
 */
const BASE = ((import.meta.env.VITE_API_BASE as string | undefined) ?? '').replace(/\/$/, '')

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method, credentials: 'include' }
  if (body instanceof FormData) {
    init.body = body
  } else if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  let res: Response
  try {
    res = await fetch(`${BASE}/api${path}`, init)
  } catch {
    throw new ApiError(0, '无法连接服务器')
  }
  const data = await res.json().catch(() => ({}))
  if (!res.ok) {
    if (res.status === 401 && !path.startsWith('/auth/')) onUnauthorized()
    throw new ApiError(res.status, (data as { error?: string }).error || `请求失败（${res.status}）`)
  }
  return data as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body: unknown = {}) => request<T>('POST', path, body),
  put: <T>(path: string, body: unknown = {}) => request<T>('PUT', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
}

export async function uploadImage(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  const { url } = await request<{ url: string }>('POST', '/files', form)
  return BASE + url
}

/** Matches the URL of an image uploaded to this knowledge base; group 1 is the stored file name. */
export const UPLOADED_FILE = /\/api\/files\/([a-f0-9]{32}\.[a-z]{3,4})$/

export async function downloadUploadedFile(name: string): Promise<Uint8Array> {
  const res = await fetch(`${BASE}/api/files/${name}`, { credentials: 'include' })
  if (!res.ok) throw new ApiError(res.status, `图片下载失败（${res.status}）`)
  return new Uint8Array(await res.arrayBuffer())
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
