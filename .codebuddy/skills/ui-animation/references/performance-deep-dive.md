# 性能深入指南

超越 SKILL.md 中快速规则的高级性能指导。

## 目录
- [CSS vs JS 动画](#css-vs-js-动画)
- [Web Animations API (WAAPI)](#web-animations-api-waapi)
- [CSS 变量继承陷阱](#css-变量继承陷阱)
- [Motion transform 所有权](#motion-transform-所有权)
- [屏幕外暂停循环动画](#屏幕外暂停循环动画)
- [合成层和 will-change](#合成层和-will-change)
- [修复抖动的 1px 偏移](#修复抖动的-1px-偏移)

## CSS vs JS 动画

| 方法 | 驱动 | 可中断 | 最适合 |
|---|---|---|---|
| CSS transitions | 浏览器/合成器（transform/opacity） | 是（重定向） | 预定的状态变化 |
| CSS keyframes | 浏览器/合成器（当属性允许时） | 否（从零重启） | 循环、预定序列 |
| WAAPI (`el.animate()`) | 浏览器动画引擎 | 是（取消/反转） | 需要命令式控制的动态值 |
| Motion values (`x`, `y`, `style`) | Motion DOM 渲染器，无 React 重渲染 | 是 | React 手势、拖拽、协调 UI |
| JS (`requestAnimationFrame`) | 主线程 | 是（手动） | 复杂编排、物理 |

**规则：CSS transitions > WAAPI > CSS keyframes > JS。** 在负载下（页面导航、繁重渲染），CSS 动画保持流畅而 JS 动画会丢帧。

## Web Animations API (WAAPI)

JavaScript 控制配合 CSS 性能。硬件加速、可中断、基于 Promise。

```ts
const animation = element.animate(
  [
    { transform: "translateY(100%)", opacity: 0 },
    { transform: "translateY(0)", opacity: 1 },
  ],
  {
    duration: 300,
    easing: "cubic-bezier(0.22, 1, 0.36, 1)",
    fill: "forwards",
  }
);

// 随时取消或反转
animation.reverse();
await animation.finished;
```

## CSS 变量继承陷阱

在父元素上更改 CSS 变量会为**所有子元素**重新计算样式。在有很多项的抽屉中，在容器上更新 `--swipe-amount` 会导致昂贵的样式重计算。

```ts
// 不好：触发所有子元素重计算
element.style.setProperty("--swipe-amount", `${distance}px`);

// 好：只影响这个元素
element.style.transform = `translateY(${distance}px)`;
```

例外：带 `inherits: false` 的 `@property` 可以避免级联，但浏览器支持有限。

## Motion transform 所有权

Motion 的 `x`/`y` 值是单轴移动和拖拽的一等 API。它们更新时不会触发 React 重渲染，是手势密集型组件的默认选择。

```tsx
const x = useMotionValue(0);

// 拖拽和轴向移动的惯用 Motion API
<motion.div drag="x" style={{ x }} />

// 当需要组合多个 transform 函数
// 或与非 Motion 代码交互时，使用一个手写的 transform 字符串
<motion.div animate={{ transform: "translateX(100px) rotate(4deg)" }} />
```

不要在同一元素上混用 Motion `x`/`y` 属性和单独的手写 `transform` 字符串。选择一个 transform 所有者。

## 屏幕外暂停循环动画

循环动画即使不可见也会消耗 GPU 资源。

```ts
"use client";
import { useEffect, useRef } from "react";

export function usePauseOffscreen<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(([entry]) => {
      el.style.animationPlayState = entry.isIntersecting ? "running" : "paused";
    });
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return ref;
}
```

## 合成层和 will-change

`will-change` 创建一个新的合成器层 — 这有内存成本。

- 仅在动画期间提升，之后移除
- 仅用于 `transform` 和 `opacity`
- 太多层比不提升更糟

```css
.animating { will-change: transform, opacity; }
```

在动画开始时切换这个类，在 `transitionend` 或 `animationend` 时移除。

## 修复抖动的 1px 偏移

由于 GPU/CPU 切换，元素可能在动画开始/结束时偏移 1px。在动画期间（而非永久）应用 `will-change: transform` 以保持整个过程在 GPU 上合成。
