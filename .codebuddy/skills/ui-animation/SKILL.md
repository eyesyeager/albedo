---
name: ui-animation
description: 创建、审查和调试 UI 动效实现。涵盖弹簧动画、手势、拖拽交互、clip-path 揭示、缓动、时序和动画审查。适用于设计、实现或审查动效，CSS transitions、keyframes、framer-motion、弹簧动画，以及"添加动画"、"让这个更流畅"、"审查我的动画"、"这个应该有动画吗"或"添加滑动手势"等请求。
---

# UI 动画

## 参考文件

### 📋 决策与规划

| 文件 | 何时阅读 |
|------|----------|
| [decision-framework.md](references/decision-framework.md) | **默认阅读**：动画决策、缓动和时长选择 |
| [animation-libraries.md](references/animation-libraries.md) | 选择 JS 动画库：Motion One、GSAP、Anime.js、React Spring |

### 🎨 组件与模式

| 文件 | 何时阅读 |
|------|----------|
| [component-patterns.md](references/component-patterns.md) | 按钮、弹出框、工具提示、抽屉、模态框、Toast 动画 |
| [code-snippets.md](references/code-snippets.md) | **即用代码**：常用动画代码片段，可直接复用 |
| [contextual-animations.md](references/contextual-animations.md) | 图标切换、词级交错入场、固定偏移退出 |

### ⚙️ 技术实现

| 文件 | 何时阅读 |
|------|----------|
| [spring-animations.md](references/spring-animations.md) | 弹簧物理、framer-motion useSpring、配置参数 |
| [clip-path-techniques.md](references/clip-path-techniques.md) | clip-path 揭示效果、标签页、长按删除、对比滑块 |
| [gesture-drag.md](references/gesture-drag.md) | 拖拽、滑动关闭、动量、指针捕获 |
| [view-transitions.md](references/view-transitions.md) | **现代 API**：View Transitions API 页面/组件过渡 |

### 🔧 优化与调试

| 文件 | 何时阅读 |
|------|----------|
| [performance-deep-dive.md](references/performance-deep-dive.md) | 调试卡顿、CSS vs JS、WAAPI、CSS 变量陷阱 |
| [debugging.md](references/debugging.md) | DevTools 动画面板、Performance 分析、帧率监控 |
| [mobile.md](references/mobile.md) | 移动端适配、触摸反馈、60fps 保障、手势实现 |

### ♿ 无障碍与审查

| 文件 | 何时阅读 |
|------|----------|
| [accessibility.md](references/accessibility.md) | **必读**：prefers-reduced-motion、无障碍动画实现 |
| [review-format.md](references/review-format.md) | 审查动画代码 — 前后对比表和问题清单 |

---

## 核心规则

1. **动画有目的** — 提供反馈、定位方向、保持连续性或刻意的愉悦感
2. **键盘操作不动画** — 永远不要为快捷键、方向键、Tab 切换添加动画
3. **优先 CSS** — CSS transitions > WAAPI > CSS keyframes > JS
4. **可中断** — 确保动画可中断且响应输入
5. **非对称时序** — 进入可以稍慢；退出应该快速
6. **尊重用户偏好** — 检测 `prefers-reduced-motion` 并提供降级

## 实现优先级

```
CSS transitions  →  最优，可中断，自动重定向
      ↓
    WAAPI       →  需要 JS 控制时，性能接近 CSS
      ↓
CSS keyframes   →  循环/预定序列，不可中断
      ↓
     JS         →  复杂物理/编排，最后选择
```

## 动效设计原则

| 原则 | 说明 |
|------|------|
| **连续性优于瞬移** | 共享元素应原地过渡，不要硬切 |
| **方向匹配位置** | 标签页向前从左到右，向后从右到左 |
| **从触发点展开** | 覆盖层从打开它的按钮展开，而非屏幕中央 |
| **一致的精致度** | 所有界面的动效质量必须统一 |
| **愉悦感与频率成反比** | 高频操作要"隐形"，罕见操作可以有惊喜 |

## 应该动画什么

```css
/* ✅ 推荐 */
transform: translateX(), scale(), rotate()
opacity: 0 → 1

/* ⚠️ 可接受（状态反馈） */
color, background-color

/* ❌ 禁止 */
width, height, top, left, margin, padding
transition: all
```

## 缓动速查表

| 元素 | 时长 | 缓动 |
|------|------|------|
| 按钮按下 | 100–160ms | `cubic-bezier(0.22, 1, 0.36, 1)` |
| 工具提示 | 125–200ms | `ease-out` |
| 下拉菜单 | 150–250ms | `cubic-bezier(0.22, 1, 0.36, 1)` |
| 模态框/抽屉 | 200–350ms | `cubic-bezier(0.22, 1, 0.36, 1)` |
| 移动/滑动 | 200–300ms | `cubic-bezier(0.25, 1, 0.5, 1)` |
| 悬停效果 | 200ms | `ease` |

**命名曲线：**
- **Enter：** `cubic-bezier(0.22, 1, 0.36, 1)`
- **Move：** `cubic-bezier(0.25, 1, 0.5, 1)`
- **iOS 抽屉：** `cubic-bezier(0.32, 0.72, 0, 1)`

> ⚠️ UI 中避免使用 `ease-in`，感觉迟钝

## 无障碍（必须）

```css
/* 尊重用户减少动画的偏好 */
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}

/* 悬停动画仅在支持的设备上启用 */
@media (hover: hover) and (pointer: fine) {
  .button:hover {
    transform: scale(1.05);
  }
}
```

## 性能要点

- ✅ 仅动画 `transform` 和 `opacity`
- ✅ 使用 `IntersectionObserver` 屏幕外暂停动画
- ✅ `will-change` 仅在动画期间临时添加
- ❌ 不要用 CSS 变量动画拖拽（每帧重绘）
- ❌ 不要永久设置 `will-change`

## 反模式

| 反模式 | 正确做法 |
|--------|----------|
| `transition: all` | 指定具体属性 |
| 动画 `width/height` | 使用 `transform: scale()` |
| `scale(0)` 起始 | 使用 `scale(0.85–0.95)` |
| `ease-in` 入场 | 使用 `ease-out` 或自定义 |
| 挂载时自动动画 | 仅在用户触发后动画 |
| 对称的进入/退出 | 退出更快（用户期望即时） |
| 快速触发用 keyframes | 用 CSS transitions（可中断） |

## 工作流程

```text
动画进度清单：
- [ ] 1. 决定是否应该有动画
- [ ] 2. 选择目的、缓动和时长
- [ ] 3. 选择实现方式（CSS > WAAPI > JS）
- [ ] 4. 检查 prefers-reduced-motion 支持
- [ ] 5. 验证可中断性和设备兼容
```

## 验证清单

- [ ] 没有动画布局属性（width/height/top/left）
- [ ] 循环动画在屏幕外暂停
- [ ] `will-change` 动画后移除
- [ ] 快速切换时平滑重定向（非从零重启）
- [ ] `prefers-reduced-motion` 生效
- [ ] 在真实移动设备上测试触摸交互
- [ ] DevTools 0.1x 慢放无明显问题
