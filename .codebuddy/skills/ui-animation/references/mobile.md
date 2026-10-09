# 移动端动画指南

移动端动画的特殊考量、优化技巧和最佳实践。

---

## 移动端与桌面端的差异

| 维度 | 桌面端 | 移动端 |
|------|--------|--------|
| **输入方式** | 鼠标悬停、点击 | 触摸、滑动、长按 |
| **性能** | 通常更强 | CPU/GPU 受限 |
| **屏幕** | 大屏、高刷新率 | 小屏、可能 60Hz |
| **网络** | 稳定 | 可能不稳定 |
| **电池** | 不敏感 | 敏感 |
| **交互期望** | 精确 | 容错性高 |

---

## 触摸反馈

### 点击反馈延迟

移动浏览器默认有 300ms 点击延迟（用于检测双击缩放）。

```css
/* 禁用双击缩放，移除延迟 */
html {
  touch-action: manipulation;
}
```

### 触摸高亮

```css
/* 移除默认的触摸高亮 */
button {
  -webkit-tap-highlight-color: transparent;
}

/* 自定义触摸反馈 */
button:active {
  transform: scale(0.97);
  opacity: 0.8;
}
```

### 触摸状态样式

```css
/* 仅在支持悬停的设备上应用悬停样式 */
@media (hover: hover) and (pointer: fine) {
  .button:hover {
    background: #f0f0f0;
  }
}

/* 触摸设备使用 active 状态 */
.button:active {
  background: #e0e0e0;
}
```

---

## 手势动画

### 滑动阈值

```ts
const SWIPE_THRESHOLD = 50;        // 最小滑动距离
const VELOCITY_THRESHOLD = 0.5;    // 最小速度 (px/ms)

function handleSwipe(startX: number, endX: number, duration: number) {
  const distance = endX - startX;
  const velocity = Math.abs(distance) / duration;

  if (Math.abs(distance) > SWIPE_THRESHOLD || velocity > VELOCITY_THRESHOLD) {
    // 触发滑动操作
    return distance > 0 ? "right" : "left";
  }
  
  return null;
}
```

### 拖拽边界阻尼

```ts
// 超出边界时应用阻尼效果
function applyRubberBand(offset: number, limit: number, elasticity = 0.55): number {
  if (Math.abs(offset) <= limit) {
    return offset;
  }

  const overflow = Math.abs(offset) - limit;
  const dampedOverflow = (1 - Math.exp(-overflow / 200)) * 100 * elasticity;
  
  return offset > 0 ? limit + dampedOverflow : -limit - dampedOverflow;
}
```

### 动量滚动

```ts
interface MomentumConfig {
  friction: number;      // 0.92-0.98
  minVelocity: number;   // 停止阈值
}

function animateMomentum(
  initialVelocity: number,
  onUpdate: (position: number) => void,
  config: MomentumConfig = { friction: 0.95, minVelocity: 0.1 }
) {
  let velocity = initialVelocity;
  let position = 0;

  function tick() {
    velocity *= config.friction;
    position += velocity;
    
    onUpdate(position);

    if (Math.abs(velocity) > config.minVelocity) {
      requestAnimationFrame(tick);
    }
  }

  requestAnimationFrame(tick);
}
```

---

## 性能优化

### 避免布局抖动

```ts
// ❌ 不好：交替读写导致强制同步布局
elements.forEach((el) => {
  const height = el.offsetHeight;  // 读
  el.style.height = height * 2 + "px";  // 写
});

// ✅ 好：批量读，然后批量写
const heights = elements.map((el) => el.offsetHeight);  // 批量读
elements.forEach((el, i) => {
  el.style.height = heights[i] * 2 + "px";  // 批量写
});
```

### 使用 transform 代替位置属性

```css
/* ❌ 不好：触发布局 */
.animated {
  left: 100px;
  top: 100px;
}

/* ✅ 好：仅触发合成 */
.animated {
  transform: translate(100px, 100px);
}
```

### 硬件加速

```css
/* 提升到 GPU 层 */
.gpu-accelerated {
  transform: translateZ(0);
  /* 或 */
  will-change: transform;
}
```

### 降低动画复杂度

```ts
// 检测设备性能
function isLowEndDevice(): boolean {
  // 检查 CPU 核心数
  if (navigator.hardwareConcurrency && navigator.hardwareConcurrency < 4) {
    return true;
  }
  
  // 检查设备内存（如果可用）
  if ((navigator as any).deviceMemory && (navigator as any).deviceMemory < 4) {
    return true;
  }
  
  return false;
}

// 根据设备性能调整动画
const animationConfig = isLowEndDevice()
  ? { duration: 0, stagger: 0 }  // 低端设备禁用动画
  : { duration: 300, stagger: 50 };  // 正常动画
```

---

## 60fps 保障

### 帧预算

```
每帧时间 = 1000ms / 60fps ≈ 16.67ms

预算分配：
- JavaScript: ~5ms
- 样式计算: ~2ms
- 布局: ~2ms
- 绘制: ~3ms
- 合成: ~2ms
- 缓冲: ~2ms
```

### 避免主线程阻塞

```ts
// ❌ 不好：在动画期间执行重计算
function onScroll() {
  items.forEach((item) => {
    item.style.transform = `translateY(${heavyCalculation()}px)`;
  });
}

// ✅ 好：使用 requestAnimationFrame 节流
let ticking = false;

function onScroll() {
  if (!ticking) {
    requestAnimationFrame(() => {
      updatePositions();
      ticking = false;
    });
    ticking = true;
  }
}
```

### Passive 事件监听器

```ts
// 告诉浏览器不会调用 preventDefault()，允许优化滚动
element.addEventListener("touchmove", handleTouchMove, { passive: true });
element.addEventListener("wheel", handleWheel, { passive: true });
```

---

## 电池优化

### 屏幕外暂停动画

```ts
// 使用 IntersectionObserver 在屏幕外暂停
const observer = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      const animation = entry.target.getAnimations()[0];
      if (entry.isIntersecting) {
        animation?.play();
      } else {
        animation?.pause();
      }
    });
  },
  { threshold: 0 }
);

document.querySelectorAll(".animated").forEach((el) => observer.observe(el));
```

### 页面不可见时暂停

```ts
document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    pauseAllAnimations();
  } else {
    resumeAllAnimations();
  }
});
```

### 低电量模式检测

```ts
// 检测省电模式（如果浏览器支持）
if ("getBattery" in navigator) {
  (navigator as any).getBattery().then((battery: any) => {
    if (battery.level < 0.2 || battery.charging === false) {
      // 低电量，减少动画
      reduceAnimations();
    }
  });
}
```

---

## 常见手势实现

### 下拉刷新

```tsx
import { motion, useMotionValue, useTransform } from "framer-motion";

function PullToRefresh({ onRefresh, children }: Props) {
  const y = useMotionValue(0);
  const opacity = useTransform(y, [0, 80], [0, 1]);
  const scale = useTransform(y, [0, 80], [0.5, 1]);

  const handleDragEnd = () => {
    if (y.get() > 80) {
      onRefresh();
    }
  };

  return (
    <div>
      <motion.div
        className="refresh-indicator"
        style={{ opacity, scale }}
      >
        <RefreshIcon />
      </motion.div>
      
      <motion.div
        drag="y"
        dragConstraints={{ top: 0, bottom: 0 }}
        dragElastic={{ top: 0.5, bottom: 0 }}
        style={{ y }}
        onDragEnd={handleDragEnd}
      >
        {children}
      </motion.div>
    </div>
  );
}
```

### 滑动删除

```tsx
function SwipeToDelete({ onDelete, children }: Props) {
  const x = useMotionValue(0);
  const background = useTransform(
    x,
    [-100, 0],
    ["#ff4444", "transparent"]
  );

  const handleDragEnd = (_: any, info: PanInfo) => {
    if (info.offset.x < -100) {
      onDelete();
    }
  };

  return (
    <motion.div style={{ background }}>
      <motion.div
        drag="x"
        dragConstraints={{ left: -100, right: 0 }}
        dragElastic={{ left: 0.2, right: 0 }}
        style={{ x }}
        onDragEnd={handleDragEnd}
      >
        {children}
      </motion.div>
    </motion.div>
  );
}
```

### 捏合缩放

```tsx
import { useGesture } from "@use-gesture/react";

function PinchZoom({ children }: Props) {
  const [scale, setScale] = useState(1);

  const bind = useGesture({
    onPinch: ({ offset: [s] }) => {
      setScale(Math.min(Math.max(s, 0.5), 3));  // 限制范围
    },
  });

  return (
    <div {...bind()} style={{ touchAction: "none" }}>
      <div style={{ transform: `scale(${scale})` }}>
        {children}
      </div>
    </div>
  );
}
```

---

## iOS 特定问题

### 安全区域

```css
/* 适配刘海屏和底部手势条 */
.bottom-bar {
  padding-bottom: env(safe-area-inset-bottom);
}

.fullscreen-modal {
  padding-top: env(safe-area-inset-top);
}
```

### 弹性滚动

```css
/* 防止页面整体弹性滚动影响动画 */
html, body {
  overscroll-behavior: none;
}

/* 局部启用弹性滚动 */
.scrollable {
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
}
```

### 键盘弹出

```ts
// iOS 键盘弹出时视口变化
const visualViewport = window.visualViewport;

visualViewport?.addEventListener("resize", () => {
  // 调整 fixed 元素位置
  const keyboardHeight = window.innerHeight - visualViewport.height;
  document.documentElement.style.setProperty(
    "--keyboard-height",
    `${keyboardHeight}px`
  );
});
```

---

## Android 特定问题

### 过度滚动颜色

```html
<!-- 设置过度滚动时的颜色 -->
<meta name="theme-color" content="#ffffff">
```

### 返回按钮动画

```ts
// 监听返回按钮
window.addEventListener("popstate", (event) => {
  // 执行退出动画
  animatePageExit().then(() => {
    // 动画完成后处理导航
  });
});
```

---

## 测试清单

```text
移动端动画测试清单：

□ 触摸交互
  □ 点击反馈是否即时？（无 300ms 延迟）
  □ 触摸目标是否足够大？（最小 44x44px）
  □ 滑动手势是否流畅？

□ 性能
  □ 在低端设备上测试
  □ 帧率是否稳定 60fps？
  □ 电池消耗是否合理？

□ 兼容性
  □ iOS Safari 测试
  □ Android Chrome 测试
  □ 不同屏幕尺寸测试

□ 特殊情况
  □ 键盘弹出时的行为
  □ 横屏/竖屏切换
  □ 多点触控场景
  □ 网络慢时的表现
```
