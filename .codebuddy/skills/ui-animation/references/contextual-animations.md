# 上下文动画

图标切换、词级交错入场和微妙退出的模式。

## 目录
- [上下文图标切换](#上下文图标切换)
- [词级交错入场](#词级交错入场)
- [微妙的退出动画](#微妙的退出动画)

---

## 上下文图标切换

当图标上下文切换状态时（复制 → 勾选、播放 → 暂停、发送 → 已发送），同时动画 `opacity`、`scale` 和 `blur`。这使切换感觉有响应而非瞬间完成。模糊隐藏了离开和进入图标之间的交叉淡入淡出接缝。

**Motion（推荐 — 支持弹簧）：**

```tsx
import { AnimatePresence, motion } from "motion/react"

<button onClick={handleCopy}>
  <AnimatePresence mode="wait" initial={false}>
    {isCopied ? (
      <motion.span
        key="check"
        initial={{ opacity: 0, scale: 0.8, filter: "blur(4px)" }}
        animate={{ opacity: 1, scale: 1, filter: "blur(0px)" }}
        exit={{ opacity: 0, scale: 0.8, filter: "blur(4px)" }}
        transition={{ type: "spring", duration: 0.2, bounce: 0 }}
      >
        <CheckIcon />
      </motion.span>
    ) : (
      <motion.span
        key="copy"
        initial={{ opacity: 0, scale: 0.8, filter: "blur(4px)" }}
        animate={{ opacity: 1, scale: 1, filter: "blur(0px)" }}
        exit={{ opacity: 0, scale: 0.8, filter: "blur(4px)" }}
        transition={{ type: "spring", duration: 0.2, bounce: 0 }}
      >
        <CopyIcon />
      </motion.span>
    )}
  </AnimatePresence>
</button>
```

**纯 CSS：**

```css
.icon {
  transition:
    opacity 150ms ease,
    scale 150ms ease,
    filter 150ms ease;
}

.icon[data-hidden] {
  opacity: 0;
  scale: 0.8;
  filter: blur(4px);
  pointer-events: none;
}
```

在 AnimatePresence 中使用 `mode="wait"`，让退出在进入开始前完成，防止两个图标同时可见。

---

## 词级交错入场

对于主标题文字或页面标题的入场动画，将内容拆分成段落（或单个词）并为每个添加交错延迟动画。组合 `opacity + translateY + blur` 是必要的 — 单独每个属性看起来都很平淡、机械或廉价。

**两级交错：**

| 级别 | 延迟 | 用于 |
|-------|-------|---------|
| 段落级 | 每段 100ms | 标题块、描述块、按钮组 |
| 词级 | 每词 80ms | 仅主标题 |

**CSS 模式：**

```css
@keyframes enter {
  from {
    transform: translateY(8px);
    filter: blur(5px);
    opacity: 0;
  }
}

.animate-enter {
  animation: enter 800ms cubic-bezier(0.25, 0.46, 0.45, 0.94) both;
  animation-delay: calc(var(--delay, 0ms) * var(--stagger, 0));
}

/* 段落级 — 100ms 间隔 */
.animate-enter-section {
  --delay: 100ms;
}

/* 词级 — 80ms 间隔 */
.animate-enter-word {
  --delay: 80ms;
}
```

**段落级 JSX：**

```tsx
<div className="animate-enter animate-enter-section" style={{ "--stagger": 1 }}>
  <Title />
</div>
<div className="animate-enter animate-enter-section" style={{ "--stagger": 2 }}>
  <Description />
</div>
<div className="animate-enter animate-enter-section" style={{ "--stagger": 3 }}>
  <Buttons />
</div>
```

**词级 JSX：**

```tsx
{"Track expenses, build habits".split(" ").map((word, i) => (
  <span
    key={word}
    className="animate-enter animate-enter-word inline-block"
    style={{ "--stagger": i + 1 }}
  >
    {word}&nbsp;
  </span>
))}
```

这些值与 `component-patterns.md` 中通用的 30–50ms 项交错不同。列表使用 30–50ms；页面级入场使用 80–100ms，因为每个块都承载叙事权重。

---

## 微妙的退出动画

退出动画应该有方向性 — 指示内容去向 — 但不应该像入场动画那样引人注目。使用固定的小偏移量，而不是计算完整元素高度。

**完整退出（对覆盖层来说移动太多）：**

```tsx
<motion.div
  exit={{
    opacity: 0,
    y: "calc(-100% - 4px)", // 完整高度，加上间隙
    filter: "blur(4px)",
  }}
  transition={{ type: "spring", duration: 0.45, bounce: 0 }}
/>
```

**微妙退出（推荐）：**

```tsx
<motion.div
  initial={{ opacity: 0, y: "calc(-100% - 4px)", filter: "blur(4px)" }}
  animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
  exit={{
    opacity: 0,
    y: "-12px", // 固定值，与元素高度无关
    filter: "blur(4px)",
  }}
  transition={{ type: "spring", duration: 0.45, bounce: 0 }}
/>
```

`-12px` 值是故意固定的 — 不要根据元素尺寸计算它。目标是传达方向，而不是追踪完整的退出路径。入场动画使用完整距离来建立存在感；退出使用短的固定距离来安静地释放注意力。

弹簧配置：`{ type: "spring", duration: 0.45, bounce: 0 }` — 零弹跳实现干净、可控的退出。
