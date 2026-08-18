/**
 * Markdown 安全渲染（AC-CHAT-005 / architecture.md §11）。
 *
 * 渲染链固定为：markdown-it({ html:false }) → DOMPurify.sanitize(白名单)
 *   1. `html: false` 使模型输出中的原始 HTML 被转义为文本（第一道防线）
 *   2. DOMPurify 白名单再做一次消毒（第二道防线）：禁 script / style / iframe /
 *      事件属性 / `javascript:` 与 `data:` 协议
 *   3. 代码块**只展示、可复制、不可执行**：复制按钮由本模块渲染为纯 button，
 *      点击行为由 Vue 侧事件委托处理（不注入任何内联事件）
 *
 * 🔴 禁止在业务组件里直接 `v-html` 未经本模块处理的内容。
 */
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

import { t } from '@/locales'

/** 允许的标签白名单（不含 img：一期不支持图片，避免外链探测/追踪）。 */
const ALLOWED_TAGS: readonly string[] = [
  'p',
  'br',
  'hr',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'strong',
  'em',
  'del',
  's',
  'blockquote',
  'ul',
  'ol',
  'li',
  'code',
  'pre',
  'span',
  'div',
  'a',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
  'button',
]

/** 允许的属性白名单（无 style、无任何 on* 事件属性）。 */
const ALLOWED_ATTR: readonly string[] = [
  'href',
  'title',
  'class',
  'type',
  'aria-label',
  'aria-live',
  'colspan',
  'rowspan',
  'start',
  'lang',
  'dir',
]

/** 仅允许安全协议（阻断 javascript: / data: / vbscript: / file:）。 */
const ALLOWED_URI_REGEXP = /^(?:https?|mailto):/i

const md: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
  typographer: false,
})

/**
 * 代码块渲染：语言标签 + 复制按钮 + 纯文本代码。
 * 复制按钮只带 `data-action`，事件由组件层委托，绝不内联脚本。
 */
md.renderer.rules.fence = (tokens, idx): string => {
  const token = tokens[idx]
  const language = token.info.trim().split(/\s+/g)[0] ?? ''
  const escapedLanguage = md.utils.escapeHtml(language)
  const escapedCode = md.utils.escapeHtml(token.content)
  const copyLabel = md.utils.escapeHtml(t('common.copy'))
  const copyAriaLabel = md.utils.escapeHtml(t('a11y.copyCode'))
  return (
    `<div class="md-code">` +
    `<div class="md-code-head">` +
    `<span class="md-code-lang">${escapedLanguage}</span>` +
    `<button class="md-code-copy" type="button" data-action="copy-code" aria-label="${copyAriaLabel}">` +
    `<span class="md-code-copy-label">${copyLabel}</span>` +
    `</button>` +
    `</div>` +
    `<pre class="md-code-body"><code>${escapedCode}</code></pre>` +
    `</div>`
  )
}

let hooksInstalled = false

function installHooks(): void {
  if (hooksInstalled) {
    return
  }
  hooksInstalled = true
  DOMPurify.addHook('afterSanitizeAttributes', (node: Element): void => {
    if (node.tagName !== 'A') {
      return
    }
    // 外链安全：禁止 window.opener 反向控制 + 不传递权重
    node.setAttribute('rel', 'noopener noreferrer nofollow')
    node.setAttribute('target', '_blank')
  })
}

/**
 * 渲染 Markdown 为**已消毒**的 HTML 字符串。
 *
 * @param source 原始 Markdown（模型输出 / 租户配置文案，均按不可信内容处理）
 */
export function renderMarkdown(source: string): string {
  installHooks()
  const rawHtml = md.render(source)
  return DOMPurify.sanitize(rawHtml, {
    ALLOWED_TAGS: [...ALLOWED_TAGS],
    ALLOWED_ATTR: [...ALLOWED_ATTR, 'target'],
    ALLOWED_URI_REGEXP,
    ALLOW_DATA_ATTR: true,
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input', 'link', 'meta'],
    FORBID_ATTR: ['style', 'srcset', 'formaction', 'onerror', 'onload', 'onclick'],
    KEEP_CONTENT: true,
  })
}

/** 渲染单行安全文本（页脚声明等：只允许行内元素与安全链接）。 */
export function renderInlineMarkdown(source: string): string {
  installHooks()
  const rawHtml = md.renderInline(source)
  return DOMPurify.sanitize(rawHtml, {
    ALLOWED_TAGS: ['a', 'strong', 'em', 'code', 'span', 'br'],
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel', 'class'],
    ALLOWED_URI_REGEXP,
    FORBID_ATTR: ['style'],
  })
}
