# 动画审查格式

## 前后对比表

使用 markdown 表格。每个问题一行。

| 之前 | 之后 | 原因 |
|---|---|---|
| `transition: all 300ms` | `transition: transform 200ms ease-out` | 指定确切的属性；避免 `all` |
| `transform: scale(0)` | `transform: scale(0.95); opacity: 0` | 现实世界中没有东西从无到有出现 |
| 下拉菜单使用 `ease-in` | 使用自定义曲线的 `ease-out` | `ease-in` 感觉迟钝；`ease-out` 给出即时反馈 |
| 按钮没有 `:active` 状态 | `:active` 时 `transform: scale(0.97)` | 按钮必须对按压有响应感 |
| 弹出框使用 `transform-origin: center` | `transform-origin: var(--radix-popover-content-transform-origin)` | 弹出框从触发点缩放（模态框保持居中） |

## 审查清单

| 问题 | 修复 |
|---|---|
| `transition: all` | 指定具体属性 |
| 动画布局属性（`width`、`height`、`top`、`left`） | 改用 `transform` 和 `opacity` |
| UI 入场使用 `ease-in` | 使用入场缓动：`cubic-bezier(0.22, 1, 0.36, 1)` |
| 永久设置 `will-change` | 仅在动画期间切换 |
| `scale(0)` 起始 | 使用 `scale(0.85–0.95)` 配合 `opacity: 0` |
| 悬停效果没有触摸设备保护 | 添加 `@media (hover: hover) and (pointer: fine)` |
| 对称的进入/退出时序 | 使退出比进入快 20–30% |
| CSS 变量拖拽动画 | 直接在元素上使用 `transform` |
| 拖拽缺少 `setPointerCapture` | 添加指针捕获以可靠跟踪 |
| Motion `x`/`y` 和手写 `transform` 混用 | 为元素选择一个 transform 所有者 |
| 键盘操作有动画 | 完全移除动画 |
| UI 元素时长 > 300ms | 减少到 150–250ms |
| 快速触发的元素使用 Keyframes | 使用 CSS transitions 以实现可中断性 |
| 共享元素的视图之间硬切 | 添加共享元素过渡；原地动画持久组件 |
| 上下文覆盖层从中心入场 | 将 `transform-origin` 设为触发点；从源元素向外动画 |
| 所有元素同时出现 | 添加交错延迟（项之间 30–50ms） |

## 组件设计原则

- **好的默认值优于选项。** 大多数用户从不自定义。默认的缓动、时序和设计应该开箱即用就很出色。
- **动态 UI 用 Transitions 而非 Keyframes。** 快速添加的元素（Toast、列表项）需要可中断的动画。Keyframes 在中断时从零重启；Transitions 平滑重定向。
- **一致性。** 动画风格应匹配组件的个性。俏皮的组件可以更有弹性。专业的仪表盘应该干脆利落。
- **隐形的边界情况。** 标签页隐藏时暂停计时器。用伪元素填充堆叠元素之间的间隙以保持悬停状态。拖拽时捕获指针事件。

## 调试动画

- **慢动作：** 临时将时长增加到 2–5 倍或使用浏览器动画检查器。检查颜色时序、缓动和 transform-origin。
- **逐帧：** 在 Chrome DevTools 动画面板中逐步查看，揭示协调属性之间的时序问题。
- **真实设备：** 对于触摸交互（抽屉、滑动手势），在真实设备上测试。Xcode 模拟器可以用，但真实硬件更适合手势测试。
- **第二天回顾：** 用新鲜的眼光你会注意到开发时错过的缺陷。
