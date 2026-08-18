import { describe, expect, it } from 'vitest'

import { renderInlineMarkdown, renderMarkdown } from '@/utils/markdown'

/**
 * Markdown 安全渲染测试（AC-CHAT-005 / architecture.md §11）。
 *
 * 断言口径：把渲染结果**真正解析成 DOM** 再检查，而不是做字符串包含判断。
 * 原因：危险字符串以"已转义的纯文本"形式出现是**安全**的（不会产生节点、不会执行），
 * 只有出现真实的危险元素 / 事件属性 / 危险协议 href 才是漏洞。
 */
const DANGEROUS_TAGS = ['script', 'iframe', 'object', 'embed', 'form', 'input', 'style', 'link', 'img']
const SAFE_URI = /^(?:https?:|mailto:)/i

function parse(html: string): HTMLElement {
  const root = document.createElement('div')
  root.innerHTML = html
  return root
}

function allElements(root: HTMLElement): Element[] {
  return Array.from(root.querySelectorAll('*'))
}

function eventAttributes(root: HTMLElement): string[] {
  return allElements(root).flatMap((node) =>
    Array.from(node.attributes)
      .map((attr) => attr.name.toLowerCase())
      .filter((name) => name.startsWith('on')),
  )
}

describe('markdown · XSS 防护（DOM 级断言）', () => {
  it('原始 HTML 不产生任何节点，只保留为惰性文本', () => {
    const root = parse(renderMarkdown('<script>window.__pwned = 1</script>'))

    expect(root.querySelector('script')).toBeNull()
    expect(allElements(root).map((el) => el.tagName.toLowerCase())).toEqual(['p'])
    // 内容被转义为文本，可见但不可执行
    expect(root.textContent).toContain('<script>')
  })

  it('事件属性不会出现在任何元素上', () => {
    const root = parse(
      renderMarkdown('<img src="x" onerror="window.__pwned=1">\n\n<div onclick="alert(1)">hi</div>'),
    )

    expect(eventAttributes(root)).toEqual([])
    expect(root.querySelector('img')).toBeNull()
    expect(root.querySelector('div')).toBeNull()
  })

  it('危险协议不会生成链接节点（markdown-it 校验 + DOMPurify 白名单双重防护）', () => {
    const sources = [
      '[点我](javascript:alert(1))',
      '[x](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)',
      '[a](vbscript:msgbox(1))',
      '[b](file:///etc/passwd)',
    ]
    sources.forEach((source) => {
      const root = parse(renderMarkdown(source))
      expect(root.querySelector('a')).toBeNull()
    })
  })

  it('所有实际生成的链接协议均在 http/https/mailto 白名单内', () => {
    const root = parse(
      renderMarkdown('[安全](https://example.test/doc) 与 [邮件](mailto:a@example.test)'),
    )
    const anchors = Array.from(root.querySelectorAll('a'))

    expect(anchors).toHaveLength(2)
    anchors.forEach((anchor) => {
      expect(anchor.getAttribute('href')).toMatch(SAFE_URI)
      expect(anchor.getAttribute('rel')).toBe('noopener noreferrer nofollow')
      expect(anchor.getAttribute('target')).toBe('_blank')
    })
  })

  it('style 属性不会出现在任何元素上', () => {
    const root = parse(renderMarkdown('<span style="position:fixed;inset:0">x</span>'))
    const withStyle = allElements(root).filter((el) => el.hasAttribute('style'))

    expect(withStyle).toEqual([])
  })

  it('危险标签在任何输入下都不出现', () => {
    const root = parse(
      renderMarkdown(
        '<iframe src="https://evil.test"></iframe>\n\n<form><input name="a"></form>\n\n![alt](https://evil.test/track.png)',
      ),
    )
    const tags = allElements(root).map((el) => el.tagName.toLowerCase())

    DANGEROUS_TAGS.forEach((tag) => {
      expect(tags).not.toContain(tag)
    })
  })

  it('代码块：渲染为复制按钮 + 惰性文本代码（可复制、不可执行）', () => {
    const root = parse(renderMarkdown('```html\n<script>alert(1)</script>\n```'))

    expect(root.querySelector('.md-code')).not.toBeNull()
    const button = root.querySelector('button[data-action="copy-code"]')
    expect(button).not.toBeNull()
    expect(button?.getAttribute('aria-label')).not.toBeNull()
    // 复制按钮不得携带任何内联事件
    expect(eventAttributes(root)).toEqual([])
    // 代码内容作为文本存在，且没有生成 script 节点
    expect(root.querySelector('code')?.textContent).toContain('<script>alert(1)</script>')
    expect(root.querySelector('script')).toBeNull()
  })

  it('常规 Markdown 结构正常渲染', () => {
    const root = parse(renderMarkdown('# 标题\n\n- 项目一\n- 项目二\n\n**粗体**\n\n`inline`'))

    expect(root.querySelector('h1')?.textContent).toBe('标题')
    expect(root.querySelectorAll('li')).toHaveLength(2)
    expect(root.querySelector('strong')?.textContent).toBe('粗体')
    expect(root.querySelector('code')?.textContent).toBe('inline')
  })

  it('页脚行内渲染：危险链接不成节点，安全链接保留', () => {
    const root = parse(
      renderInlineMarkdown('声明 [条款](javascript:alert(1)) 与 [隐私](https://example.test/p)'),
    )
    const anchors = Array.from(root.querySelectorAll('a'))

    expect(anchors).toHaveLength(1)
    expect(anchors[0].getAttribute('href')).toBe('https://example.test/p')
    expect(eventAttributes(root)).toEqual([])
  })
})
