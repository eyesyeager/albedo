/**
 * 会话历史加载（`stores/chat.ts` 的内部实现细节）。
 *
 * 抽出理由：`chat.ts` 已超 300 行；历史分页取「最后一页」这条容易被写错的规则
 * 与"加载失败只降级不白屏"的错误处理，值得独立成可单测的纯逻辑。
 *
 * 🔴 日志纪律：失败原因已渲染成失败态的业务错误（如跨租户 `10004`）只记 debug；
 *    网络 / 解析 / `50000+` 仍记 error（判定收口在 `utils/errorLevel.ts`）。
 */
import { conversationApi } from '@/api/conversation'
import { t } from '@/locales'
import type { ChatMessage, ChatMessageView, Conversation } from '@/types/chat'
import { toMessageView } from '@/utils/chatMessage'
import { isUiHandledError } from '@/utils/errorLevel'
import { ApiError } from '@/utils/request'

/**
 * 拉取"最近"的消息。
 *
 * 🔴 历史按 `created_at ASC` 分页，因此消息数超过一页时必须取**最后一页**，
 * 否则用户打开会话看到的是最早的内容。
 *
 * @param pageSize 单页条数（来自 `sys_config: business.pageSizeMax`）
 */
export async function loadLatestMessages(
  conversationId: string,
  pageSize: number,
): Promise<ChatMessage[]> {
  const first = await conversationApi.messages(conversationId, { page: 1, pageSize })
  if (first.total <= first.list.length) {
    return first.list
  }
  const lastPage = Math.ceil(first.total / pageSize)
  const last = await conversationApi.messages(conversationId, { page: lastPage, pageSize })
  return last.list
}

export interface ConversationSnapshot {
  conversation: Conversation | null
  messages: ChatMessageView[]
  /** 可展示的失败原因；null 表示成功 */
  error: string | null
}

/**
 * 打开会话：并行取详情与最近消息。
 *
 * 🔴 失败只返回可展示原因（由调用方渲染失败态 + 重试），绝不白屏、绝不抛给视图层。
 */
export async function loadConversationSnapshot(
  conversationId: string,
  pageSize: number,
): Promise<ConversationSnapshot> {
  try {
    const [detail, history] = await Promise.all([
      conversationApi.detail(conversationId),
      loadLatestMessages(conversationId, pageSize),
    ])
    return { conversation: detail, messages: history.map(toMessageView), error: null }
  } catch (e: unknown) {
    // 🔴 分级：已被本函数消费并渲染成失败态的业务错误（如跨租户 10004）不报 error，
    //    否则会污染"关键页面 console 零 error"；网络 / 解析 / 50000+ 仍保留 error。
    if (isUiHandledError(e)) {
      console.debug('[chat] 会话加载失败（已渲染失败态）', e)
    } else {
      console.error('[chat] 会话加载失败', e)
    }
    return {
      conversation: null,
      messages: [],
      error: e instanceof ApiError ? e.message : t('chat.historyLoadFailed'),
    }
  }
}
