# 组件动画模式

## 目录
- [按钮](#按钮)
- [弹出框和下拉菜单](#弹出框和下拉菜单)
- [工具提示](#工具提示)
- [抽屉和面板](#抽屉和面板)
- [模态框和对话框](#模态框和对话框)
- [Toast 通知](#toast-通知)
- [列表和交错](#列表和交错)
- [悬停效果](#悬停效果)

## 按钮

在 `:active` 时添加 `transform: scale(0.97)` 实现即时按下反馈。

```css
.button {
  transition: transform 160ms cubic-bezier(0.22, 1, 0.36, 1);
}
.button:active {
  transform: scale(0.97);
}
```

使用模糊来掩盖按钮状态之间不完美的交叉淡入淡出过渡：

```css
.button-content.transitioning {
  filter: blur(2px);
  opacity: 0.7;
}
```

保持模糊在 20px 以下 — 重度模糊开销很大，尤其在 Safari 中。

## 弹出框和下拉菜单

从触发点缩放，而不是从中心。默认的 `transform-origin: center` 对弹出框是错误的。

```css
/* Radix UI */
.popover {
  transform-origin: var(--radix-popover-content-transform-origin);
}

/* Data attribute 降级方案 */
.popover[data-side="top"]    { transform-origin: bottom center; }
.popover[data-side="bottom"] { transform-origin: top center; }
.popover[data-side="left"]   { transform-origin: center right; }
.popover[data-side="right"]  { transform-origin: center left; }
```

从 `scale(0.88)` 开始，永远不要 `scale(0)`。现实世界中没有东西从无到有出现。

```css
.menu {
  transform: scale(0.88);
  opacity: 0;
  transition: transform 200ms cubic-bezier(0.22, 1, 0.36, 1),
              opacity 200ms cubic-bezier(0.22, 1, 0.36, 1);
}
.menu[data-open="true"] {
  transform: scale(1);
  opacity: 1;
}
```

## 工具提示

首次出现前延迟（300–500ms）以防止意外触发。一旦一个工具提示打开，后续的工具提示立即打开，无动画。

```css
.tooltip {
  transition: transform 125ms ease-out, opacity 125ms ease-out;
  transform-origin: var(--transform-origin);
}
.tooltip[data-starting-style],
.tooltip[data-ending-style] {
  opacity: 0;
  transform: scale(0.97);
}
.tooltip[data-instant] {
  transition-duration: 0ms;
}
```

## 抽屉和面板

使用移动缓动曲线。百分比的 `translateY`/`translateX` 可适应任何抽屉高度。

```css
.drawer {
  transform: translateY(100%);
  transition: transform 240ms cubic-bezier(0.25, 1, 0.5, 1);
}
.drawer[data-open="true"] {
  transform: translateY(0);
}
```

```tsx
<motion.aside
  initial={{ transform: "translate3d(100%, 0, 0)" }}
  animate={{ transform: "translate3d(0, 0, 0)" }}
  exit={{ transform: "translate3d(100%, 0, 0)" }}
  transition={{ duration: 0.24, ease: [0.25, 1, 0.5, 1] }}
/>
```

## 模态框和对话框

**例外：模态框保持 `transform-origin: center`。** 它们代表应用级状态，不锚定到触发点。

使用 `@starting-style` 实现无 JavaScript 的入场动画：

```css
.modal {
  opacity: 1;
  transform: scale(1);
  transition: opacity 250ms cubic-bezier(0.22, 1, 0.36, 1),
              transform 250ms cubic-bezier(0.22, 1, 0.36, 1);

  @starting-style {
    opacity: 0;
    transform: scale(0.95);
  }
}
```

当 `@starting-style` 浏览器支持不足时，降级使用 `data-mounted` 属性模式。

## Toast 通知

从同一方向进入和退出以保持空间一致性（使滑动关闭直观）。

```css
.toast {
  transform: translate3d(0, 6px, 0);
  opacity: 0;
  transition: transform 220ms cubic-bezier(0.22, 1, 0.36, 1),
              opacity 220ms cubic-bezier(0.22, 1, 0.36, 1);
}
.toast[data-open="true"] {
  transform: translate3d(0, 0, 0);
  opacity: 1;
}
```

对 Toast 使用 CSS transitions（而非 keyframes）— 它们会被快速添加，keyframes 在中断时从头重启，而 transitions 会平滑重定向。

## 列表和交错

保持交错延迟短（每项 30–50ms）。总交错应保持在 300ms 以内。

```css
.item {
  opacity: 0;
  transform: translateY(8px);
  transition: transform 220ms cubic-bezier(0.22, 1, 0.36, 1),
              opacity 220ms cubic-bezier(0.22, 1, 0.36, 1);
}
.list[data-open="true"] .item {
  opacity: 1;
  transform: translateY(0);
}
.list[data-open="true"] .item:nth-child(2) { transition-delay: 50ms; }
.list[data-open="true"] .item:nth-child(3) { transition-delay: 100ms; }
.list[data-open="true"] .item:nth-child(4) { transition-delay: 150ms; }
```

```tsx
const listVariants = {
  show: { transition: { staggerChildren: 0.05 } },
};
```

交错动画播放时永远不要阻止交互。

## 悬停效果

将悬停动画放在媒体查询后面，以避免触摸设备误触发。

```css
@media (hover: hover) and (pointer: fine) {
  .link {
    transition: color 200ms ease, opacity 200ms ease;
  }
  .link:hover {
    opacity: 0.8;
  }
}
```

通过在父元素上应用悬停并动画子元素来修复悬停闪烁：

```css
.box:hover .box-inner {
  transform: translateY(-20%);
}
.box-inner {
  transition: transform 200ms ease;
}
```
