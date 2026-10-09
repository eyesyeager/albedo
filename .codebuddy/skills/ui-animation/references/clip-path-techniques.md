# clip-path 动画技术

`clip-path` 是 CSS 中最强大的动画工具之一。它是硬件加速的，可以创建仅用 `opacity` 和 `transform` 无法实现的效果。

## 目录
- [inset 形状](#inset-形状)
- [标签页颜色过渡](#标签页颜色过渡)
- [长按删除](#长按删除)
- [滚动时图片揭示](#滚动时图片揭示)
- [对比滑块](#对比滑块)

## inset 形状

`clip-path: inset(top right bottom left)` 定义一个矩形裁剪区域。每个值从该侧"吃入"元素。

```css
/* 从右侧完全隐藏 */
.hidden { clip-path: inset(0 100% 0 0); }

/* 完全可见 */
.visible { clip-path: inset(0 0 0 0); }
```

使用 CSS 过渡在状态之间动画：

```css
.reveal {
  clip-path: inset(0 100% 0 0);
  transition: clip-path 300ms cubic-bezier(0.22, 1, 0.36, 1);
}
.reveal.active {
  clip-path: inset(0 0 0 0);
}
```

## 标签页颜色过渡

复制标签页列表。将副本样式设为"激活"状态（不同的背景、不同的文字颜色）。裁剪副本使只有激活的标签页可见。在标签页切换时动画裁剪区域。

这创造了单独的 `color` 过渡永远无法实现的无缝颜色过渡。

```css
.tabs-active-overlay {
  clip-path: inset(0 var(--clip-right) 0 var(--clip-left));
  transition: clip-path 200ms cubic-bezier(0.22, 1, 0.36, 1);
}
```

当激活标签页改变时通过 JavaScript 更新 `--clip-left` 和 `--clip-right`。

## 长按删除

在彩色覆盖层上使用 `clip-path: inset(0 100% 0 0)`。在 `:active` 时，用 2s `linear` 时序过渡到 `inset(0 0 0 0)`。释放时，用 200ms `ease-out` 快速回弹。配合按钮上的 `scale(0.97)` 实现按下反馈。

```css
.delete-overlay {
  clip-path: inset(0 100% 0 0);
  transition: clip-path 200ms ease-out;
}

.delete-button:active .delete-overlay {
  clip-path: inset(0 0 0 0);
  transition: clip-path 2s linear;
}
```

## 滚动时图片揭示

从 `clip-path: inset(0 0 100% 0)`（从底部隐藏）开始。当元素进入视口时动画到 `inset(0 0 0 0)`。

```tsx
"use client";
import { useRef, useEffect, useState } from "react";

export function RevealImage({ src, alt }: { src: string; alt: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      ([entry]) => { if (entry.isIntersecting) setVisible(true); },
      { threshold: 0.1, rootMargin: "-100px" }
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);

  return (
    <div
      ref={ref}
      style={{
        clipPath: visible ? "inset(0 0 0 0)" : "inset(0 0 100% 0)",
        transition: "clip-path 800ms cubic-bezier(0.77, 0, 0.175, 1)",
      }}
    >
      <img src={src} alt={alt} />
    </div>
  );
}
```

## 对比滑块

叠加两张图片。用 `clip-path: inset(0 50% 0 0)` 裁剪顶部图片。根据拖拽位置调整右侧 inset。不需要额外的 DOM 元素，完全硬件加速。

```css
.comparison-top {
  clip-path: inset(0 var(--split) 0 0);
}
```

通过滑块手柄上的指针事件更新 `--split`。
