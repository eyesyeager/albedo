# 弹簧动画

弹簧比基于时长的动画感觉更自然，因为它们模拟真实的物理效果。它们没有固定的时长 — 而是根据物理参数来稳定。

## 何时使用弹簧

- 带动量的拖拽交互（释放后让物理接管）
- 应该感觉"活着"的元素（如 Apple 的灵动岛）
- 可能在动画中途被中断的手势
- 装饰性的鼠标跟踪交互
- 过冲效果（俏皮的 UI）

**不要在以下场景使用弹簧：** 简单的淡入淡出、颜色过渡，或需要精确时序的 UI。

## 弹簧参数

| 参数 | 控制什么 | 典型范围 |
|---|---|---|
| `stiffness` | 运动速度（越高 = 越快） | 100–500 |
| `damping` | 阻力（越低 = 弹跳越多） | 15–40 |
| `mass` | 重量感（越高 = 越慢、越重） | 0.5–2 |

## 配置预设

**Apple 风格（推荐 — 更容易理解）：**

```js
{ type: "spring", duration: 0.5, bounce: 0.2 }
```

**传统物理（更多控制）：**

| 预设 | stiffness | damping | 用例 |
|---|---|---|---|
| Snappy（Apple 默认） | 500 | 40 | 通用 UI，无弹跳 |
| Bouncy（弹性） | 300 | 20 | 俏皮的元素、通知 |
| Gentle（柔和） | 200 | 30 | 页面过渡、大型元素 |
| Stiff（硬朗） | 700 | 50 | 小型精确移动 |

使用时保持弹跳微妙（0.1–0.3）。大多数 UI 场景避免使用弹跳。

## 可中断性优势

弹簧在被中断时保持速度 — CSS keyframes 会从零重新开始。这使得弹簧非常适合用户可能在中途改变的手势。

```tsx
// 弹簧从当前位置平滑反转
<motion.div
  animate={{ transform: isOpen ? "translateX(0)" : "translateX(-100%)" }}
  transition={{ type: "spring", stiffness: 500, damping: 40 }}
/>
```

## 基于弹簧的鼠标交互

将值直接绑定到鼠标位置会感觉生硬。使用 `useSpring` 以弹簧般的行为进行插值，而不是立即更新。

```tsx
import { useSpring } from "framer-motion";

// 没有弹簧：即时，感觉生硬
const rotation = mouseX * 0.1;

// 有弹簧：有动量，感觉自然
const springRotation = useSpring(mouseX * 0.1, {
  stiffness: 100,
  damping: 10,
});
```

仅将此用于**装饰性**交互。如果这是银行应用中的功能性图表，不加动画会更好。

## 直接跳转而非弹簧

如果交互需要即时响应或精确时序，完全跳过弹簧。使用短过渡或直接跳到结束状态。

```tsx
<motion.div
  animate={{ opacity: isOpen ? 1 : 0, x: isOpen ? 0 : -12 }}
  transition={
    shouldSnap
      ? { duration: 0.12, ease: "linear" }
      : { type: "spring", stiffness: 500, damping: 40 }
  }
/>
```
