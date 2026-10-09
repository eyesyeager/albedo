# View Transitions API

View Transitions API 是浏览器原生的页面/组件过渡动画 API，可以在 DOM 状态变化时创建平滑的动画效果。

## 浏览器支持

| 浏览器 | 支持 |
|--------|------|
| Chrome 111+ | ✅ |
| Edge 111+ | ✅ |
| Safari 18+ | ✅ |
| Firefox | 🚧 开发中 |

---

## 基础用法：同文档过渡

### 简单示例

```ts
// 包裹 DOM 变化，浏览器自动创建交叉淡入淡出
document.startViewTransition(() => {
  // 任何 DOM 变化
  document.getElementById("content").innerHTML = newContent;
});
```

### 带 Promise 的异步操作

```ts
async function updateContent() {
  const transition = document.startViewTransition(async () => {
    // 获取新数据
    const data = await fetchData();
    // 更新 DOM
    renderContent(data);
  });

  // 等待过渡完成
  await transition.finished;
  console.log("过渡完成");
}
```

---

## 自定义过渡动画

### CSS 伪元素结构

View Transitions 会创建以下伪元素树：

```
::view-transition
├── ::view-transition-group(root)
│   └── ::view-transition-image-pair(root)
│       ├── ::view-transition-old(root)  ← 旧状态截图
│       └── ::view-transition-new(root)  ← 新状态截图
```

### 自定义动画

```css
/* 修改默认的交叉淡入淡出时长 */
::view-transition-old(root),
::view-transition-new(root) {
  animation-duration: 500ms;
}

/* 自定义入场动画 */
::view-transition-new(root) {
  animation: slide-in 300ms ease-out;
}

::view-transition-old(root) {
  animation: fade-out 200ms ease-in;
}

@keyframes slide-in {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}

@keyframes fade-out {
  from { opacity: 1; }
  to { opacity: 0; }
}
```

---

## 命名过渡：独立动画元素

### 标记元素

```css
/* 给特定元素命名，使其独立动画 */
.hero-image {
  view-transition-name: hero;
}

.page-title {
  view-transition-name: title;
}
```

> ⚠️ 注意：`view-transition-name` 在整个文档中必须唯一！

### 为命名元素设置动画

```css
/* hero 图片的自定义动画 */
::view-transition-old(hero),
::view-transition-new(hero) {
  animation-duration: 400ms;
  animation-timing-function: cubic-bezier(0.22, 1, 0.36, 1);
}

/* 标题的独立动画 */
::view-transition-group(title) {
  animation-duration: 300ms;
}
```

---

## 实战示例

### 列表项删除动画

```ts
function removeItem(id: string) {
  document.startViewTransition(() => {
    const item = document.getElementById(id);
    item?.remove();
  });
}
```

```css
/* 删除时的退出动画 */
::view-transition-old(list-item) {
  animation: shrink-out 200ms ease-in forwards;
}

@keyframes shrink-out {
  to {
    transform: scale(0.8);
    opacity: 0;
  }
}
```

### 卡片展开为详情页

```html
<!-- 列表页 -->
<div class="card" style="view-transition-name: card-1">
  <img src="..." style="view-transition-name: card-image-1" />
  <h2 style="view-transition-name: card-title-1">标题</h2>
</div>

<!-- 详情页（相同的 view-transition-name） -->
<div class="detail" style="view-transition-name: card-1">
  <img src="..." style="view-transition-name: card-image-1" />
  <h1 style="view-transition-name: card-title-1">标题</h1>
</div>
```

```ts
// 点击卡片
card.addEventListener("click", () => {
  document.startViewTransition(() => {
    // 切换到详情页视图
    showDetailPage();
  });
});
```

### 主题切换

```ts
function toggleTheme() {
  document.startViewTransition(() => {
    document.documentElement.dataset.theme =
      document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  });
}
```

```css
/* 主题切换时禁用默认过渡，使用自定义 */
::view-transition-old(root),
::view-transition-new(root) {
  animation: none;
  mix-blend-mode: normal;
}

/* 圆形揭示效果 */
::view-transition-new(root) {
  animation: reveal 500ms ease-out;
}

@keyframes reveal {
  from {
    clip-path: circle(0% at var(--click-x, 50%) var(--click-y, 50%));
  }
  to {
    clip-path: circle(150% at var(--click-x, 50%) var(--click-y, 50%));
  }
}
```

---

## React 集成

### 基础 Hook

```tsx
function useViewTransition() {
  const startTransition = (callback: () => void) => {
    if (!document.startViewTransition) {
      // 降级：直接执行
      callback();
      return;
    }

    document.startViewTransition(callback);
  };

  return { startTransition };
}

// 使用
function App() {
  const { startTransition } = useViewTransition();
  const [page, setPage] = useState("home");

  const navigate = (newPage: string) => {
    startTransition(() => {
      setPage(newPage);
    });
  };

  return <button onClick={() => navigate("about")}>Go to About</button>;
}
```

### 配合 React Router

```tsx
import { useNavigate } from "react-router-dom";

function useViewTransitionNavigate() {
  const navigate = useNavigate();

  return (to: string) => {
    if (!document.startViewTransition) {
      navigate(to);
      return;
    }

    document.startViewTransition(() => {
      navigate(to);
    });
  };
}
```

### flushSync 确保同步更新

```tsx
import { flushSync } from "react-dom";

function Component() {
  const [items, setItems] = useState([...]);

  const removeItem = (id: string) => {
    document.startViewTransition(() => {
      // flushSync 确保 React 同步更新 DOM
      flushSync(() => {
        setItems(items.filter(item => item.id !== id));
      });
    });
  };
}
```

---

## 跨文档过渡（MPA）

适用于多页应用（传统页面跳转）。

### 启用

```css
/* 在两个页面的 CSS 中都添加 */
@view-transition {
  navigation: auto;
}
```

### 条件过渡

```css
/* 仅在同源导航时启用 */
@view-transition {
  navigation: auto;
  types: same-origin;
}
```

### JavaScript 控制

```ts
// 在目标页面
window.addEventListener("pagereveal", (event) => {
  if (event.viewTransition) {
    // 可以在这里自定义动画
    console.log("View transition 正在进行");
  }
});
```

---

## 性能优化

### 跳过某些元素

```css
/* 复杂元素不参与过渡，提升性能 */
.complex-chart {
  view-transition-name: none;
}
```

### 减少动画元素

```ts
// 仅为关键元素创建独立过渡
document.startViewTransition(() => {
  // 先移除非关键元素的 view-transition-name
  document.querySelectorAll(".minor").forEach((el) => {
    el.style.viewTransitionName = "none";
  });
  
  // 执行 DOM 变化
  updateContent();
});
```

### 检测用户偏好

```ts
function startTransition(callback: () => void) {
  // 尊重用户的减少动画偏好
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    callback();
    return;
  }

  if (document.startViewTransition) {
    document.startViewTransition(callback);
  } else {
    callback();
  }
}
```

---

## 与其他动画方案对比

| 特性 | View Transitions | Framer Motion | CSS Transitions |
|------|------------------|---------------|-----------------|
| **原生支持** | ✅ | ❌ | ✅ |
| **跨页面** | ✅ | ❌ | ❌ |
| **自动截图** | ✅ | ❌ | ❌ |
| **Bundle 大小** | 0KB | ~30KB | 0KB |
| **浏览器支持** | 较新 | 全部 | 全部 |
| **复杂动画** | 中等 | 强大 | 基础 |

---

## 常见问题

### 1. 闪烁问题

```css
/* 确保新旧状态不重叠 */
::view-transition-old(root) {
  animation: fade-out 200ms ease-out both;
}

::view-transition-new(root) {
  animation: fade-in 200ms ease-out 200ms both; /* 延迟开始 */
}
```

### 2. 动态命名

```tsx
// 为列表项动态设置 view-transition-name
{items.map((item) => (
  <div 
    key={item.id}
    style={{ viewTransitionName: `item-${item.id}` }}
  >
    {item.content}
  </div>
))}
```

### 3. 降级处理

```ts
function safeStartViewTransition(callback: () => void): Promise<void> {
  if (!document.startViewTransition) {
    callback();
    return Promise.resolve();
  }

  return document.startViewTransition(callback).finished;
}
```
