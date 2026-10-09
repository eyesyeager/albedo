# 动画调试指南

使用浏览器 DevTools 调试、分析和优化动画。

---

## Chrome DevTools 动画面板

### 打开方式

1. 打开 DevTools (F12 / Cmd+Option+I)
2. 按 `Cmd+Shift+P` (Mac) 或 `Ctrl+Shift+P` (Windows)
3. 输入 "Animations" 并选择 "Show Animations"

### 功能

| 功能 | 用途 |
|------|------|
| **时间轴** | 查看所有动画的时序关系 |
| **速度控制** | 25% / 10% 慢放，发现细微问题 |
| **暂停/播放** | 逐帧检查动画状态 |
| **时长修改** | 拖拽调整动画时长 |
| **缓动曲线** | 可视化编辑贝塞尔曲线 |

### 使用技巧

```
1. 触发动画 → 面板自动捕获
2. 点击动画条 → 查看详细时间线
3. 拖拽动画条边缘 → 调整时长
4. 点击缓动曲线 → 修改缓动函数
```

---

## Performance 面板分析

### 录制动画性能

1. 打开 Performance 面板
2. 点击录制按钮
3. 触发动画
4. 停止录制
5. 分析火焰图

### 关键指标

| 指标 | 目标 | 说明 |
|------|------|------|
| **帧率 (FPS)** | 60fps | 低于 60 会感到卡顿 |
| **帧时间** | <16.67ms | 每帧渲染时间 |
| **Layout** | 最小化 | 紫色块表示布局重计算 |
| **Paint** | 最小化 | 绿色块表示绘制 |
| **Composite** | 仅此 | 黄色块表示合成（最优） |

### 识别问题

```
🔴 红色条 = 长任务（>50ms）
🟣 紫色块过多 = 触发了布局抖动
🟢 绿色块过多 = 触发了重绘
🟡 仅黄色块 = 最优（GPU 合成）
```

---

## Layers 面板

### 打开方式

1. DevTools → More tools → Layers
2. 或在 Performance 录制后点击 "Layers" 标签

### 分析合成层

- 查看哪些元素被提升为合成层
- 识别不必要的层（内存浪费）
- 确认动画元素在独立层上

### 创建合成层的属性

```css
/* 会创建新的合成层 */
transform: translateZ(0);
will-change: transform;
opacity: 0.99; /* hack，不推荐 */
filter: blur(0);
```

---

## Rendering 面板

### 打开方式

DevTools → More tools → Rendering

### 有用的选项

| 选项 | 用途 |
|------|------|
| **Paint flashing** | 绿色高亮显示重绘区域 |
| **Layout Shift Regions** | 蓝色高亮显示布局位移 |
| **Layer borders** | 显示合成层边界 |
| **FPS meter** | 实时显示帧率 |
| **Scrolling performance issues** | 高亮滚动性能问题 |

### Paint Flashing 使用

```
1. 开启 Paint flashing
2. 触发动画
3. 观察绿色闪烁区域
4. 目标：动画期间无绿色闪烁（仅合成）
```

---

## CSS 动画调试技巧

### 慢放动画

```css
/* 开发时临时添加 */
* {
  animation-duration: 3s !important;
  transition-duration: 3s !important;
}
```

### 暂停所有动画

```css
/* 冻结当前状态进行检查 */
* {
  animation-play-state: paused !important;
}
```

### 高亮动画元素

```css
/* 添加边框识别动画元素 */
*[style*="animation"],
*[style*="transition"] {
  outline: 2px solid red !important;
}
```

---

## JavaScript 调试

### 打印动画状态

```ts
// 监控 WAAPI 动画
const animation = element.animate([...], {...});

animation.addEventListener("finish", () => console.log("动画完成"));
animation.addEventListener("cancel", () => console.log("动画取消"));

// 打印当前进度
setInterval(() => {
  console.log("进度:", animation.currentTime, animation.playState);
}, 100);
```

### 性能标记

```ts
// 标记动画开始和结束
performance.mark("animation-start");

element.animate([...], {...}).finished.then(() => {
  performance.mark("animation-end");
  performance.measure("animation", "animation-start", "animation-end");
  
  const measure = performance.getEntriesByName("animation")[0];
  console.log("动画耗时:", measure.duration, "ms");
});
```

### 帧率监控

```ts
let lastTime = performance.now();
let frames = 0;

function measureFPS() {
  frames++;
  const now = performance.now();
  
  if (now - lastTime >= 1000) {
    console.log("FPS:", frames);
    frames = 0;
    lastTime = now;
  }
  
  requestAnimationFrame(measureFPS);
}

measureFPS();
```

---

## Framer Motion 调试

### 启用开发工具

```tsx
import { LazyMotion, domAnimation, MotionConfig } from "framer-motion";

function App() {
  return (
    <MotionConfig reducedMotion="user">
      <LazyMotion features={domAnimation} strict>
        {/* strict 模式会警告性能问题 */}
        <YourApp />
      </LazyMotion>
    </MotionConfig>
  );
}
```

### 打印动画值

```tsx
import { useMotionValue, useMotionValueEvent } from "framer-motion";

function Component() {
  const x = useMotionValue(0);

  useMotionValueEvent(x, "change", (latest) => {
    console.log("x:", latest);
  });

  return <motion.div style={{ x }} />;
}
```

---

## 常见问题诊断

### 问题：动画卡顿

**检查清单：**

```text
□ 是否动画了布局属性（width, height, top, left）？
  → 改用 transform
  
□ 是否使用了 transition: all？
  → 指定具体属性
  
□ Paint flashing 是否显示大面积绿色？
  → 添加 will-change 或检查层级
  
□ 是否在动画期间执行了 JS？
  → 使用 requestAnimationFrame 或 Web Worker
  
□ 是否有过多的合成层？
  → 检查 Layers 面板，减少层数
```

### 问题：动画闪烁

```text
□ 是否有 z-index 冲突？
□ 是否在动画开始时创建新层？
  → 提前设置 will-change
  
□ 是否有半透明叠加问题？
  → 检查 background 和 opacity
```

### 问题：动画不流畅

```text
□ 缓动函数是否合适？
  → 避免 linear 和 ease-in 用于 UI
  
□ 时长是否过长或过短？
  → 参考标准时长表
  
□ 是否有突然的方向变化？
  → 使用弹簧动画实现自然过渡
```

---

## 移动端调试

### 远程调试

```bash
# Android
1. 手机开启开发者模式和 USB 调试
2. 连接 USB
3. Chrome 打开 chrome://inspect
4. 点击 inspect

# iOS
1. Safari → 开发 → [设备名] → [网页]
```

### 模拟弱网/低性能

```
DevTools → Performance → CPU: 4x slowdown / 6x slowdown
DevTools → Network → Slow 3G
```

### 触摸事件调试

```ts
// 打印触摸事件
element.addEventListener("touchstart", (e) => {
  console.log("touchstart", e.touches.length, e.touches[0]);
});

element.addEventListener("touchmove", (e) => {
  console.log("touchmove", e.touches[0].clientX, e.touches[0].clientY);
});

element.addEventListener("touchend", (e) => {
  console.log("touchend");
});
```

---

## 性能预算

### 建议标准

| 指标 | 目标 |
|------|------|
| **首次动画** | <100ms 内开始 |
| **帧率** | 稳定 60fps |
| **JS 执行** | <5ms/帧 |
| **主线程阻塞** | <50ms |
| **动画库大小** | <30KB gzipped |

### 自动化检测

```ts
// 使用 PerformanceObserver 监控长任务
const observer = new PerformanceObserver((list) => {
  for (const entry of list.getEntries()) {
    if (entry.duration > 50) {
      console.warn("长任务:", entry.duration, "ms", entry);
    }
  }
});

observer.observe({ entryTypes: ["longtask"] });
```

---

## 调试清单模板

```text
动画调试清单：

□ 基础检查
  □ 动画是否触发？
  □ 时长和缓动是否正确？
  □ 开始/结束状态是否正确？

□ 性能检查
  □ 帧率是否稳定 60fps？
  □ Paint flashing 是否最小化？
  □ 是否只动画 transform/opacity？

□ 兼容性检查
  □ 是否在目标浏览器测试？
  □ 是否有降级方案？
  □ prefers-reduced-motion 是否生效？

□ 用户体验检查
  □ 动画是否可中断？
  □ 快速操作是否流畅？
  □ 移动端触摸是否正常？
```
