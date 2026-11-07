export interface SseFrame {
  event: string
  data: string
}

function parseFrame(raw: string): SseFrame | null {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split('\n')) {
    const clean = line.endsWith('\r') ? line.slice(0, -1) : line
    if (!clean || clean.startsWith(':')) continue
    if (clean.startsWith('event:')) event = clean.slice('event:'.length).trim()
    else if (clean.startsWith('data:')) data.push(clean.slice('data:'.length).replace(/^ /, ''))
  }
  if (!data.length) return null
  return { event, data: data.join('\n') }
}

/** 逐帧解析 `text/event-stream`，不等待整段响应结束。 */
export async function readSse(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onFrame: (frame: SseFrame) => void,
): Promise<void> {
  const decoder = new TextDecoder()
  let buffer = ''
  const flush = (): void => {
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const raw = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const frame = parseFrame(raw)
      if (frame) onFrame(frame)
      boundary = buffer.indexOf('\n\n')
    }
  }
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    flush()
  }
  buffer += decoder.decode().replace(/\r\n/g, '\n')
  flush()
  const tail = parseFrame(buffer)
  if (tail) onFrame(tail)
}
