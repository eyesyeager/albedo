# JS 动画库选择指南

## 库概览

| 库 | 大小 | 框架依赖 | 特点 |
|---|------|---------|------|
| **Motion One** | ~3KB | 无 | 极致轻量，WAAPI 封装，Framer Motion 团队出品 |
| **GSAP** | ~60KB | 无 | 功能最强大，时间线编排，插件生态丰富 |
| **Anime.js** | ~17KB | 无 | API 简洁优雅，学习曲线平缓 |
| **React Spring** | ~25KB | React | 基于弹簧物理，声明式 API |

---

## 快速决策树

```
你用 React 吗？
├─ 是 → 需要物理/手势感吗？
│       ├─ 是 → React Spring 或 Framer Motion
│       └─ 否 → 简单动画用 CSS，复杂用 GSAP
└─ 否 →
    需要复杂时间线编排吗？
    ├─ 是 → GSAP
    └─ 否 →
        追求极致轻量吗？
        ├─ 是 → Motion One（3KB）
        └─ 否 → Anime.js（简单好用）
```

---

## Motion One

### 适合场景
- 追求极致轻量（gzip 后仅 3KB）
- 不使用 React 但想要现代 API
- 主要做简单到中等复杂度的动画
- 希望利用原生 WAAPI 性能

### 不适合场景
- 需要复杂时间线编排
- 需要丰富的插件生态
- 需要 SVG 变形动画

### 代码示例

```js
import { animate, stagger } from "motion";

// 基础动画
animate(".box", { opacity: 1, x: 100 }, { duration: 0.5 });

// 弹簧动画
animate(".box", { scale: 1.2 }, { type: "spring", stiffness: 300 });

// 交错动画
animate("li", { opacity: 1, y: 0 }, { delay: stagger(0.1) });

// 时间线
import { timeline } from "motion";

timeline([
  [".box", { x: 100 }],
  [".box", { rotate: 90 }, { at: "-0.2" }],  // 重叠 0.2s
  [".circle", { scale: 1.5 }],
]);

// 滚动触发
import { scroll } from "motion";

scroll(animate(".progress", { scaleX: 1 }));
```

### 性能特点
- ✅ 底层使用 WAAPI，硬件加速
- ✅ 自动使用 `transform` 和 `opacity`
- ✅ 极小的 bundle 影响

---

## GSAP (GreenSock)

### 适合场景
- 复杂的时间线动画编排
- 营销页面、动画密集型项目
- 需要 SVG 变形、路径动画
- 需要滚动触发（ScrollTrigger）
- 需要跨浏览器兼容性

### 不适合场景
- 简单的状态切换（用 CSS）
- 对 bundle 大小敏感的项目
- 商业项目需注意许可证

### 代码示例

```js
import gsap from "gsap";

// 基础动画
gsap.to(".box", { x: 100, opacity: 1, duration: 0.5 });

// 弹簧效果（使用 ease）
gsap.to(".box", { 
  scale: 1.2, 
  ease: "elastic.out(1, 0.3)",
  duration: 1 
});

// 时间线 - GSAP 最强大的功能
const tl = gsap.timeline();
tl.to(".box", { x: 100 })
  .to(".box", { rotate: 90 }, "-=0.2")  // 重叠 0.2s
  .to(".circle", { scale: 1.5 })
  .to(".text", { opacity: 1 }, "<");     // 与上一个同时开始

// 交错动画
gsap.to("li", { 
  opacity: 1, 
  y: 0, 
  stagger: 0.1 
});

// 滚动触发（需要插件）
import { ScrollTrigger } from "gsap/ScrollTrigger";
gsap.registerPlugin(ScrollTrigger);

gsap.to(".box", {
  x: 500,
  scrollTrigger: {
    trigger: ".box",
    start: "top center",
    end: "bottom center",
    scrub: true,  // 与滚动同步
  }
});

// SVG 路径动画（需要插件）
import { MotionPathPlugin } from "gsap/MotionPathPlugin";
gsap.registerPlugin(MotionPathPlugin);

gsap.to(".rocket", {
  motionPath: {
    path: "#flightPath",
    align: "#flightPath",
    autoRotate: true,
  },
  duration: 5
});
```

### 插件生态

| 插件 | 功能 |
|------|------|
| ScrollTrigger | 滚动触发动画 |
| MotionPathPlugin | SVG 路径动画 |
| MorphSVGPlugin | SVG 形状变形（付费） |
| DrawSVGPlugin | SVG 线条绘制（付费） |
| SplitText | 文字拆分动画（付费） |
| Flip | 布局动画 |

### 性能特点
- ✅ 高度优化，比大多数库快
- ✅ 自动处理 `will-change`
- ⚠️ 主线程运行，极端情况可能卡顿

---

## Anime.js

### 适合场景
- 中小型项目
- 快速原型开发
- 学习动画编程
- 需要简洁优雅的 API

### 不适合场景
- 需要复杂时间线控制
- 需要高性能滚动动画
- 大型生产项目

### 代码示例

```js
import anime from "animejs";

// 基础动画
anime({
  targets: ".box",
  translateX: 100,
  opacity: 1,
  duration: 500
});

// 弹簧效果
anime({
  targets: ".box",
  scale: 1.2,
  easing: "spring(1, 80, 10, 0)"  // mass, stiffness, damping, velocity
});

// 时间线
const tl = anime.timeline({
  easing: "easeOutExpo",
  duration: 500
});

tl.add({ targets: ".box", translateX: 100 })
  .add({ targets: ".box", rotate: 90 }, "-=200")  // 重叠 200ms
  .add({ targets: ".circle", scale: 1.5 });

// 交错动画
anime({
  targets: "li",
  opacity: [0, 1],
  translateY: [20, 0],
  delay: anime.stagger(100)  // 每个元素延迟 100ms
});

// 路径动画
const path = anime.path(".motion-path");
anime({
  targets: ".box",
  translateX: path("x"),
  translateY: path("y"),
  rotate: path("angle"),
  duration: 3000
});

// 关键帧
anime({
  targets: ".box",
  keyframes: [
    { translateX: 100 },
    { translateY: 100 },
    { translateX: 0 },
    { translateY: 0 }
  ],
  duration: 2000
});
```

### 性能特点
- ✅ 比手写 rAF 优化更好
- ⚠️ 主线程运行
- ⚠️ 复杂场景性能不如 GSAP

---

## React Spring

### 适合场景
- React 项目
- 需要自然物理感的动画
- 手势交互（配合 @use-gesture）
- 需要可中断的动画

### 不适合场景
- 非 React 项目
- 简单的 CSS 可实现的动画
- 需要精确时间控制的动画

### 代码示例

```tsx
import { useSpring, animated, useTrail, useTransition } from "@react-spring/web";

// 基础弹簧动画
function Box() {
  const [springs, api] = useSpring(() => ({
    from: { x: 0, opacity: 0 },
    to: { x: 100, opacity: 1 },
    config: { tension: 300, friction: 20 }
  }));

  return <animated.div style={springs} />;
}

// 交互触发
function HoverBox() {
  const [springs, api] = useSpring(() => ({
    scale: 1,
    config: { tension: 400, friction: 30 }
  }));

  return (
    <animated.div
      style={springs}
      onMouseEnter={() => api.start({ scale: 1.1 })}
      onMouseLeave={() => api.start({ scale: 1 })}
    />
  );
}

// 交错动画（Trail）
function List({ items }) {
  const trail = useTrail(items.length, {
    from: { opacity: 0, y: 20 },
    to: { opacity: 1, y: 0 },
  });

  return trail.map((style, index) => (
    <animated.li key={index} style={style}>
      {items[index]}
    </animated.li>
  ));
}

// 进入/退出动画（Transition）
function Modal({ isOpen }) {
  const transitions = useTransition(isOpen, {
    from: { opacity: 0, transform: "scale(0.9)" },
    enter: { opacity: 1, transform: "scale(1)" },
    leave: { opacity: 0, transform: "scale(0.9)" },
  });

  return transitions((style, show) =>
    show && <animated.div style={style}>Modal Content</animated.div>
  );
}

// 配合手势
import { useDrag } from "@use-gesture/react";

function DraggableCard() {
  const [{ x, y }, api] = useSpring(() => ({ x: 0, y: 0 }));

  const bind = useDrag(({ down, movement: [mx, my] }) => {
    api.start({
      x: down ? mx : 0,
      y: down ? my : 0,
      immediate: down,  // 拖动时立即响应
    });
  });

  return <animated.div {...bind()} style={{ x, y }} />;
}
```

### 弹簧配置预设

```tsx
import { config } from "@react-spring/web";

// 内置预设
config.default    // { tension: 170, friction: 26 }
config.gentle     // { tension: 120, friction: 14 }
config.wobbly     // { tension: 180, friction: 12 }
config.stiff      // { tension: 210, friction: 20 }
config.slow       // { tension: 280, friction: 60 }
config.molasses   // { tension: 280, friction: 120 }

// 自定义配置
const customConfig = {
  tension: 300,    // 弹性（越高越快）
  friction: 20,    // 摩擦力（越高越快停止）
  mass: 1,         // 质量（越高越慢启动）
};
```

### 性能特点
- ✅ 自动使用 transform
- ✅ 动画值变化不触发 re-render
- ✅ 完全可中断
- ⚠️ 学习曲线略陡

---

## 对比总结

| 维度 | Motion One | GSAP | Anime.js | React Spring |
|------|------------|------|----------|--------------|
| **大小** | 3KB ⭐ | 60KB | 17KB | 25KB |
| **学习曲线** | 低 | 中 | 低 ⭐ | 中高 |
| **时间线** | 基础 | 强大 ⭐ | 中等 | 有限 |
| **弹簧动画** | 有 | 通过 ease | 有 | 原生 ⭐ |
| **滚动触发** | 有 | 强大 ⭐ | 需手写 | 需配合 |
| **React 集成** | 需包装 | 需包装 | 需包装 | 原生 ⭐ |
| **SVG 支持** | 基础 | 强大 ⭐ | 良好 | 基础 |
| **性能** | 优秀 ⭐ | 优秀 | 良好 | 优秀 |

## 最终推荐

| 场景 | 首选 | 备选 |
|------|-----|------|
| **React + 物理动画** | React Spring | Framer Motion |
| **非 React + 轻量** | Motion One | Anime.js |
| **复杂时间线/营销页** | GSAP | - |
| **快速原型/学习** | Anime.js | Motion One |
| **SVG 动画** | GSAP | Anime.js |
| **滚动动画** | GSAP + ScrollTrigger | Motion One scroll |
