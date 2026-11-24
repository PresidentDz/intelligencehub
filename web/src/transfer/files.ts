/** Browser file helpers for Markdown import/export. */

export interface PickedFile {
  /** Path relative to the selection, "/"-separated. Plain file name when files were picked individually. */
  path: string
  file: File
}

/** Opens a file or folder picker; resolves with an empty list if cancelled. */
export function pickFiles(directory: boolean): Promise<PickedFile[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.multiple = true
    if (directory) input.webkitdirectory = true
    else input.accept = '.md,.markdown,image/png,image/jpeg,image/gif,image/webp'
    input.onchange = () =>
      resolve(Array.from(input.files ?? []).map((file) => ({ path: file.webkitRelativePath || file.name, file })))
    input.oncancel = () => resolve([])
    input.click()
  })
}

export const isMarkdown = (path: string) => /\.(md|markdown)$/i.test(path)

/** Reads a text file as UTF-8, falling back to GB18030 for files saved by older Chinese editors. */
export async function readText(file: File): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer())
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return new TextDecoder('gb18030').decode(bytes)
  }
}

export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

const RESERVED = /^(con|prn|aux|nul|com\d|lpt\d)$/i

/** Turns a page title into a file name that is valid on Windows, macOS and Linux. */
export function safeName(title: string): string {
  let name = title.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').replace(/[. ]+$/, '').trim().slice(0, 80)
  if (!name) name = '无标题'
  return RESERVED.test(name) ? `${name}_` : name
}
