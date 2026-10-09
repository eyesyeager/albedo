# 动画无障碍指南

## prefers-reduced-motion

`prefers-reduced-motion` 是一个 CSS 媒体查询，用于检测用户是否在系统设置中开启了"减少动态效果"选项。

### 为什么重要

- **前庭功能障碍**：某些动画可能导致眩晕、恶心或头痛
- **注意力障碍**：动画可能分散注意力，影响专注
- **认知负担**：过多动画增加认知负担
- **电池寿命**：减少动画可延长移动设备续航

### 用户在哪里设置

| 系统 | 路径 |
|------|------|
| **macOS** | 系统偏好设置 → 辅助功能 → 显示 → 减少动态效果 |
| **iOS** | 设置 → 辅助功能 → 动态效果 → 减少动态效果 |
| **Windows** | 设置 → 轻松使用 → 显示 → 显示动画 |
| **Android** | 设置 → 辅助功能 → 移除动画 |

---

## CSS 实现

### 方法一：移除动画（推荐）

```css
/* 默认：有动画 */
.modal {
  transition: transform 300ms ease-out, opacity 300ms ease-out;
}

/* 用户偏好减少动画：移除动画 */
@media (prefers-reduced-motion: reduce) {
  .modal {
    transition: none;
  }
}
```

### 方法二：渐进增强（更好）

```css
/* 默认：无动画 */
.modal {
  /* 基础样式，无动画 */
}

/* 仅在用户接受动画时添加 */
@media (prefers-reduced-motion: no-preference) {
  .modal {
    transition: transform 300ms ease-out, opacity 300ms ease-out;
  }
}
```

### 方法三：全局动画开关

```css
/* 全局减少动画 */
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

> ⚠️ 注意：不要设置 `animation: none`，这会破坏依赖动画状态的 JS 逻辑。使用极短时长让动画"瞬间完成"。

---

## JavaScript 实现

### 检测用户偏好

```ts
// 检查用户是否偏好减少动画
function prefersReducedMotion(): boolean {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

// 使用示例
if (prefersReducedMotion()) {
  // 跳过动画或使用简化版本
  element.style.opacity = "1";
} else {
  // 正常动画
  element.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 300 });
}
```

### 监听偏好变化

```ts
const motionQuery = window.matchMedia("(prefers-reduced-motion: reduce)");

motionQuery.addEventListener("change", (event) => {
  if (event.matches) {
    // 用户刚刚开启了减少动画
    cancelAllAnimations();
  } else {
    // 用户刚刚关闭了减少动画
    enableAnimations();
  }
});
```

### React Hook

```tsx
import { useState, useEffect } from "react";

function usePrefersReducedMotion(): boolean {
  const [prefersReduced, setPrefersReduced] = useState(() => {
    // SSR 安全：服务端默认返回 true（保守策略）
    if (typeof window === "undefined") return true;
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  });

  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const handler = (event: MediaQueryListEvent) => {
      setPrefersReduced(event.matches);
    };

    query.addEventListener("change", handler);
    return () => query.removeEventListener("change", handler);
  }, []);

  return prefersReduced;
}

// 使用示例
function AnimatedComponent() {
  const prefersReduced = usePrefersReducedMotion();

  return (
    <motion.div
      animate={{ x: 100 }}
      transition={prefersReduced ? { duration: 0 } : { duration: 0.3 }}
    />
  );
}
```

---

## Framer Motion 集成

```tsx
import { motion, useReducedMotion } from "framer-motion";

function Modal({ isOpen }: { isOpen: boolean }) {
  const shouldReduceMotion = useReducedMotion();

  const variants = {
    hidden: { 
      opacity: 0, 
      scale: shouldReduceMotion ? 1 : 0.9 
    },
    visible: { 
      opacity: 1, 
      scale: 1 
    },
  };

  return (
    <motion.div
      variants={variants}
      initial="hidden"
      animate={isOpen ? "visible" : "hidden"}
      transition={shouldReduceMotion ? { duration: 0 } : { duration: 0.3 }}
    />
  );
}
```

---

## 哪些动画应该保留

即使用户偏好减少动画，某些动画仍然**应该保留**：

| 保留 | 原因 |
|------|------|
| **颜色变化** | 不涉及运动 |
| **透明度变化** | 不涉及空间移动 |
| **加载指示器** | 提供重要反馈 |
| **进度条** | 传达状态信息 |
| **焦点指示器** | 无障碍必需 |

### 减少动画时的替代方案

```css
/* 正常：滑入动画 */
@media (prefers-reduced-motion: no-preference) {
  .toast {
    animation: slideIn 300ms ease-out;
  }
}

/* 减少动画：仅淡入 */
@media (prefers-reduced-motion: reduce) {
  .toast {
    animation: fadeIn 150ms ease-out;
  }
}

@keyframes slideIn {
  from { transform: translateY(100%); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
```

---

## 应该移除的动画

| 移除 | 原因 |
|------|------|
| **视差滚动** | 可能导致眩晕 |
| **自动轮播** | 分散注意力 |
| **背景动画** | 无功能意义 |
| **悬停时大幅缩放** | 可能导致不适 |
| **无限循环动画** | 除非有功能用途 |
| **突然的大幅位移** | 可能导致眩晕 |

---

## 测试清单

- [ ] 在系统设置中开启"减少动态效果"
- [ ] 确认所有位移动画被移除或大幅简化
- [ ] 确认颜色/透明度过渡仍然有效
- [ ] 确认加载状态仍然可见
- [ ] 确认焦点指示器正常工作
- [ ] 确认功能性动画（如展开/折叠）仍然可用
- [ ] 使用屏幕阅读器测试（动画不应干扰朗读）

---

## 代码审查检查项

```text
□ 是否使用了 prefers-reduced-motion 媒体查询？
□ 是否采用渐进增强（默认无动画，检测后添加）？
□ 大幅位移动画是否在 reduce 时被替换或移除？
□ 是否保留了必要的功能性动画？
□ 是否监听了偏好变化（实时响应）？
```
