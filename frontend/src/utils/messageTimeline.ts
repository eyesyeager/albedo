/**
 * 助手消息时间线构建（api-spec §4.5.6 `segments` + §5.2 `tool` 事件）。
 *
 * 🔴 存在的理由：模型在多轮工具编排里的真实时序是
 * 「思考① → 工具① → 思考② → 正文」。若按 `reasoning` / `toolCalls` / `content`
 * 三个桶各自整块渲染，工具节点会被固定堆到末尾 —— 因果关系被破坏，
 * 用户看到的是「先想完所有事，再调工具」，与实际发生的顺序不符。
 *
 * 🔴 进一步地：<b>工具调用发生「在思考之中」，不是思考的分隔符</b>。
 * 因此连续的思考/工具会被合并为<b>一个</b>过程块（{@link MessageProcessBlock}），
 * 工具栏嵌在思考面板内部 —— 而不是把一段连续推理切成两个「思考过程」面板
 * （那会让用户误以为模型中途停止了思考）。
 */
import type { ChatMessageView, MessageRoundSegment } from '@/types/chat'
import type { ToolCallSummary } from '@/types/tool'

/** 过程块内部的节点：思考文本与工具调用按真实顺序交替。 */
export type MessageProcessItem =
  | { kind: 'reasoning'; key: string; text: string; round: number }
  | { kind: 'tools'; key: string; round: number; calls: ToolCallSummary[] }

/**
 * 顶层渲染块。
 *
 * - `process`：一段连续的「思考 + 工具」过程，折叠为单个思考面板
 * - `text`：面向用户的正文，永远在过程块之外（它是答案，不是过程）
 */
export type MessageTimelineBlock =
  | {
      kind: 'process'
      key: string
      items: MessageProcessItem[]
      /** 🔴 是否含思考文本：只含工具时不得套「思考过程」外壳（非推理模型没有思考） */
      hasReasoning: boolean
    }
  | { kind: 'text'; key: string; text: string; round: number }

/** 无时序信息时的兜底轮号（旧数据 / segments 解析失败）。 */
const LEGACY_ROUND = 0

/**
 * 是否值得渲染成一个块。
 *
 * 🔴 用 `trim` 判定而非 `length`：实测模型常在工具调用前只吐一个换行符，
 * 那会渲染出一个高度非零却什么都没有的空块（视觉上像是排版错乱）。
 * 🔴 仅用于**决定是否渲染**，绝不修改内容本身 —— 正文权威始终是 `message.content`。
 */
function renderable(text: string): boolean {
  return text.trim().length > 0
}

function toolsByRound(calls: ToolCallSummary[]): Map<number, ToolCallSummary[]> {
  const grouped = new Map<number, ToolCallSummary[]>()
  for (const call of calls) {
    const round = Number.isFinite(call.round) ? call.round : LEGACY_ROUND
    const bucket = grouped.get(round)
    if (bucket === undefined) {
      grouped.set(round, [call])
    } else {
      bucket.push(call)
    }
  }
  return grouped
}

/** 细粒度节点（内部中间态，随后按连续性合并为顶层块）。 */
type FlatItem = MessageProcessItem | { kind: 'text'; key: string; text: string; round: number }

/**
 * 旧版布局：思考 → 工具 → 正文。
 *
 * 🔴 仅用于**没有** `segments` 的消息（V1.1.8 之前落库的历史行）。
 * 保留它是为了让排版投影缺失时只降级、不空白 —— 内容一个字都不能少。
 */
function legacyFlat(message: ChatMessageView): FlatItem[] {
  const items: FlatItem[] = []
  if (renderable(message.reasoning)) {
    items.push({
      kind: 'reasoning',
      key: 'reasoning-legacy',
      text: message.reasoning,
      round: LEGACY_ROUND,
    })
  }
  if (message.toolCalls.length > 0) {
    items.push({
      kind: 'tools',
      key: 'tools-legacy',
      round: LEGACY_ROUND,
      calls: message.toolCalls,
    })
  }
  if (renderable(message.content)) {
    items.push({ kind: 'text', key: 'text-legacy', text: message.content, round: LEGACY_ROUND })
  }
  return items
}

/**
 * 展开为按真实顺序排列的细粒度节点。
 *
 * 🔴 **轮号缺口必须容错**：某一轮可能只有工具、没有任何思考/正文
 * （模型直接决定调用工具），此时该轮没有段落但有工具，不能因此丢掉工具节点。
 * 故按轮号从小到大遍历「段落轮号 ∪ 工具轮号」的并集。
 */
function flatten(message: ChatMessageView): FlatItem[] {
  const segments: MessageRoundSegment[] = message.segments
  if (segments.length === 0) {
    return legacyFlat(message)
  }

  const grouped = toolsByRound(message.toolCalls)
  const segmentByRound = new Map<number, MessageRoundSegment>()
  for (const segment of segments) {
    segmentByRound.set(segment.round, segment)
  }

  const rounds = [...new Set([...segmentByRound.keys(), ...grouped.keys()])].sort((a, b) => a - b)

  const items: FlatItem[] = []
  for (const round of rounds) {
    const segment = segmentByRound.get(round)
    if (segment !== undefined) {
      if (renderable(segment.reasoning)) {
        items.push({
          kind: 'reasoning',
          key: `reasoning-${round}`,
          text: segment.reasoning,
          round,
        })
      }
      if (renderable(segment.text)) {
        items.push({ kind: 'text', key: `text-${round}`, text: segment.text, round })
      }
    }
    const calls = grouped.get(round)
    if (calls !== undefined && calls.length > 0) {
      items.push({ kind: 'tools', key: `tools-${round}`, round, calls })
    }
  }
  return items
}

/**
 * 构建顶层渲染块。
 *
 * 🔴 **同一函数同时服务实时流与历史回显** —— 两条路径若各写一套交织逻辑，
 * 必然出现「刷新前后排版不一致」这类只在特定时机复现的缺陷。
 *
 * 🔴 **合并规则**：连续的 `reasoning` / `tools` 归入同一个过程块；
 * 一旦出现面向用户的 `text` 就结束当前过程块。
 * 理由：正文意味着模型「对用户说话了」，该思考阶段就此结束；
 * 而工具调用只是思考中途的一次外部查询，不构成阶段边界。
 */
export function buildMessageTimeline(message: ChatMessageView): MessageTimelineBlock[] {
  const blocks: MessageTimelineBlock[] = []
  let pending: MessageProcessItem[] = []

  const flushPending = (): void => {
    if (pending.length === 0) {
      return
    }
    const items = pending
    pending = []
    blocks.push({
      kind: 'process',
      key: `process-${items[0]?.key ?? blocks.length}`,
      items,
      hasReasoning: items.some((item) => item.kind === 'reasoning'),
    })
  }

  for (const item of flatten(message)) {
    if (item.kind === 'text') {
      flushPending()
      blocks.push(item)
      continue
    }
    pending.push(item)
  }
  flushPending()

  return blocks
}

/**
 * 仍在进行中的过程块 key。
 *
 * 🔴 判定口径：**仅当消息处于流式中、且最后一个块就是过程块**时，该块才算进行中。
 * 一旦正文开始到达（最后一个块变为 `text`），思考即告结束 ——
 * 面板随之自动折叠，把视觉重心交还给答案。
 */
export function activeProcessKey(
  blocks: MessageTimelineBlock[],
  streaming: boolean,
): string | null {
  if (!streaming) {
    return null
  }
  const last = blocks[blocks.length - 1]
  return last !== undefined && last.kind === 'process' ? last.key : null
}
