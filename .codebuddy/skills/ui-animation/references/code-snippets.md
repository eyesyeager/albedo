# 常用动画代码片段

可直接复用的动画代码模板，按组件类型分类。

---

## 基础过渡

### 淡入淡出

```css
.fade {
  transition: opacity 200ms ease;
}

.fade-enter { opacity: 0; }
.fade-enter-active { opacity: 1; }
.fade-exit { opacity: 1; }
.fade-exit-active { opacity: 0; }
```

### 缩放淡入

```css
.scale-fade {
  transition: transform 200ms cubic-bezier(0.22, 1, 0.36, 1),
              opacity 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.scale-fade-enter {
  opacity: 0;
  transform: scale(0.9);
}

.scale-fade-enter-active {
  opacity: 1;
  transform: scale(1);
}
```

### 滑入

```css
/* 从下方滑入 */
.slide-up {
  transition: transform 250ms cubic-bezier(0.22, 1, 0.36, 1),
              opacity 250ms cubic-bezier(0.22, 1, 0.36, 1);
}

.slide-up-enter {
  opacity: 0;
  transform: translateY(16px);
}

.slide-up-enter-active {
  opacity: 1;
  transform: translateY(0);
}
```

---

## 按钮

### 点击反馈

```css
.button {
  transition: transform 100ms cubic-bezier(0.22, 1, 0.36, 1),
              box-shadow 100ms ease;
}

.button:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.button:active {
  transform: translateY(0) scale(0.98);
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}
```

### 加载状态

```css
.button-loading {
  position: relative;
  color: transparent;
  pointer-events: none;
}

.button-loading::after {
  content: "";
  position: absolute;
  width: 16px;
  height: 16px;
  top: 50%;
  left: 50%;
  margin: -8px 0 0 -8px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 600ms linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
```

### 涟漪效果

```tsx
function RippleButton({ children, onClick }: { children: React.ReactNode; onClick?: () => void }) {
  const [ripples, setRipples] = useState<{ x: number; y: number; id: number }[]>([]);

  const handleClick = (e: React.MouseEvent<HTMLButtonElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const id = Date.now();

    setRipples((prev) => [...prev, { x, y, id }]);
    setTimeout(() => setRipples((prev) => prev.filter((r) => r.id !== id)), 600);
    onClick?.();
  };

  return (
    <button className="ripple-button" onClick={handleClick}>
      {children}
      {ripples.map(({ x, y, id }) => (
        <span
          key={id}
          className="ripple"
          style={{ left: x, top: y }}
        />
      ))}
    </button>
  );
}
```

```css
.ripple-button {
  position: relative;
  overflow: hidden;
}

.ripple {
  position: absolute;
  width: 0;
  height: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.4);
  transform: translate(-50%, -50%);
  animation: ripple-expand 600ms ease-out forwards;
}

@keyframes ripple-expand {
  to {
    width: 300px;
    height: 300px;
    opacity: 0;
  }
}
```

---

## 模态框 / 对话框

### CSS 实现

```css
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  opacity: 0;
  visibility: hidden;
  transition: opacity 200ms ease, visibility 200ms ease;
}

.modal-overlay.open {
  opacity: 1;
  visibility: visible;
}

.modal-content {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%) scale(0.9);
  opacity: 0;
  transition: transform 250ms cubic-bezier(0.22, 1, 0.36, 1),
              opacity 250ms cubic-bezier(0.22, 1, 0.36, 1);
}

.modal-overlay.open .modal-content {
  transform: translate(-50%, -50%) scale(1);
  opacity: 1;
}
```

### Framer Motion 实现

```tsx
import { AnimatePresence, motion } from "framer-motion";

function Modal({ isOpen, onClose, children }: ModalProps) {
  return (
    <AnimatePresence>
      {isOpen && (
        <>
          <motion.div
            className="modal-overlay"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            onClick={onClose}
          />
          <motion.div
            className="modal-content"
            initial={{ opacity: 0, scale: 0.9, y: 20 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.9, y: 20 }}
            transition={{ type: "spring", stiffness: 500, damping: 30 }}
          >
            {children}
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
```

---

## Toast / 通知

### 从底部滑入

```css
.toast-container {
  position: fixed;
  bottom: 24px;
  right: 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.toast {
  transform: translateX(calc(100% + 24px));
  opacity: 0;
  animation: toast-in 300ms cubic-bezier(0.22, 1, 0.36, 1) forwards;
}

.toast.exiting {
  animation: toast-out 200ms ease-in forwards;
}

@keyframes toast-in {
  to {
    transform: translateX(0);
    opacity: 1;
  }
}

@keyframes toast-out {
  to {
    transform: translateX(calc(100% + 24px));
    opacity: 0;
  }
}
```

### 带进度条

```tsx
function Toast({ message, duration = 3000, onClose }: ToastProps) {
  return (
    <div className="toast">
      <p>{message}</p>
      <div 
        className="toast-progress"
        style={{ animationDuration: `${duration}ms` }}
        onAnimationEnd={onClose}
      />
    </div>
  );
}
```

```css
.toast-progress {
  position: absolute;
  bottom: 0;
  left: 0;
  height: 3px;
  background: currentColor;
  opacity: 0.3;
  animation: progress linear forwards;
}

@keyframes progress {
  from { width: 100%; }
  to { width: 0%; }
}
```

---

## 抽屉 / 侧边栏

### 从右侧滑入

```css
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  opacity: 0;
  visibility: hidden;
  transition: opacity 200ms ease, visibility 200ms ease;
}

.drawer-overlay.open {
  opacity: 1;
  visibility: visible;
}

.drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 320px;
  background: white;
  transform: translateX(100%);
  transition: transform 300ms cubic-bezier(0.32, 0.72, 0, 1);
}

.drawer-overlay.open .drawer {
  transform: translateX(0);
}
```

### 可拖拽关闭

```tsx
import { motion, useMotionValue, useTransform, PanInfo } from "framer-motion";

function SwipeableDrawer({ isOpen, onClose, children }: DrawerProps) {
  const x = useMotionValue(0);
  const opacity = useTransform(x, [0, 200], [1, 0]);

  const handleDragEnd = (_: any, info: PanInfo) => {
    if (info.offset.x > 100 || info.velocity.x > 500) {
      onClose();
    }
  };

  return (
    <AnimatePresence>
      {isOpen && (
        <>
          <motion.div
            className="drawer-overlay"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            style={{ opacity }}
            onClick={onClose}
          />
          <motion.div
            className="drawer"
            initial={{ x: "100%" }}
            animate={{ x: 0 }}
            exit={{ x: "100%" }}
            transition={{ type: "spring", stiffness: 400, damping: 40 }}
            drag="x"
            dragConstraints={{ left: 0, right: 0 }}
            dragElastic={{ left: 0, right: 0.5 }}
            onDragEnd={handleDragEnd}
            style={{ x }}
          >
            {children}
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
```

---

## 下拉菜单

### 从触发点展开

```css
.dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  min-width: 200px;
  transform-origin: top left;
  transform: scale(0.9);
  opacity: 0;
  visibility: hidden;
  transition: transform 150ms cubic-bezier(0.22, 1, 0.36, 1),
              opacity 150ms cubic-bezier(0.22, 1, 0.36, 1),
              visibility 150ms;
}

.dropdown.open {
  transform: scale(1);
  opacity: 1;
  visibility: visible;
}

/* 从右侧打开时 */
.dropdown.right {
  left: auto;
  right: 0;
  transform-origin: top right;
}
```

### 菜单项交错动画

```tsx
import { motion, AnimatePresence } from "framer-motion";

function Dropdown({ isOpen, items }: DropdownProps) {
  return (
    <AnimatePresence>
      {isOpen && (
        <motion.ul
          className="dropdown"
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          exit={{ opacity: 0, scale: 0.9 }}
          transition={{ duration: 0.15 }}
        >
          {items.map((item, index) => (
            <motion.li
              key={item.id}
              initial={{ opacity: 0, y: -8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: index * 0.03 }}
            >
              {item.label}
            </motion.li>
          ))}
        </motion.ul>
      )}
    </AnimatePresence>
  );
}
```

---

## 列表动画

### 交错入场

```css
.list-item {
  opacity: 0;
  transform: translateY(16px);
  animation: list-item-in 300ms cubic-bezier(0.22, 1, 0.36, 1) forwards;
}

.list-item:nth-child(1) { animation-delay: 0ms; }
.list-item:nth-child(2) { animation-delay: 50ms; }
.list-item:nth-child(3) { animation-delay: 100ms; }
.list-item:nth-child(4) { animation-delay: 150ms; }
.list-item:nth-child(5) { animation-delay: 200ms; }
/* 最大 300ms 总交错时间 */

@keyframes list-item-in {
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
```

### React + Framer Motion

```tsx
import { motion, AnimatePresence } from "framer-motion";

function AnimatedList({ items }: { items: Item[] }) {
  return (
    <ul>
      <AnimatePresence mode="popLayout">
        {items.map((item) => (
          <motion.li
            key={item.id}
            layout
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.9 }}
            transition={{ type: "spring", stiffness: 500, damping: 30 }}
          >
            {item.content}
          </motion.li>
        ))}
      </AnimatePresence>
    </ul>
  );
}
```

---

## 骨架屏 / 加载

### 闪烁效果

```css
.skeleton {
  background: linear-gradient(
    90deg,
    #f0f0f0 25%,
    #e0e0e0 50%,
    #f0f0f0 75%
  );
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.5s infinite;
}

@keyframes skeleton-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
```

### 脉冲效果

```css
.skeleton-pulse {
  background: #f0f0f0;
  animation: skeleton-pulse 1.5s ease-in-out infinite;
}

@keyframes skeleton-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
```

---

## 切换开关

```css
.toggle {
  width: 48px;
  height: 28px;
  background: #e0e0e0;
  border-radius: 14px;
  position: relative;
  cursor: pointer;
  transition: background 200ms ease;
}

.toggle.active {
  background: #4caf50;
}

.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 24px;
  height: 24px;
  background: white;
  border-radius: 50%;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
  transition: transform 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.toggle.active .toggle-thumb {
  transform: translateX(20px);
}
```

---

## 折叠 / 手风琴

### CSS 实现（需要已知高度）

```css
.collapsible-content {
  max-height: 0;
  overflow: hidden;
  transition: max-height 300ms ease-out;
}

.collapsible.open .collapsible-content {
  max-height: 500px; /* 需要足够大 */
}
```

### 使用 grid（更优雅）

```css
.collapsible-content {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 300ms ease-out;
}

.collapsible.open .collapsible-content {
  grid-template-rows: 1fr;
}

.collapsible-inner {
  overflow: hidden;
}
```

### React Hook 实现

```tsx
function useCollapse(defaultOpen = false) {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  const contentRef = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState<number | undefined>(defaultOpen ? undefined : 0);

  useEffect(() => {
    if (!contentRef.current) return;
    
    const resizeObserver = new ResizeObserver((entries) => {
      const contentHeight = entries[0].contentRect.height;
      setHeight(isOpen ? contentHeight : 0);
    });

    resizeObserver.observe(contentRef.current);
    return () => resizeObserver.disconnect();
  }, [isOpen]);

  return {
    isOpen,
    toggle: () => setIsOpen(!isOpen),
    contentRef,
    style: {
      height,
      overflow: "hidden",
      transition: "height 300ms ease-out",
    },
  };
}
```

---

## 页面过渡

### 淡入淡出

```css
.page-transition-enter {
  opacity: 0;
}

.page-transition-enter-active {
  opacity: 1;
  transition: opacity 200ms ease;
}

.page-transition-exit {
  opacity: 1;
}

.page-transition-exit-active {
  opacity: 0;
  transition: opacity 200ms ease;
}
```

### 滑动切换

```css
.page-slide-enter {
  transform: translateX(100%);
}

.page-slide-enter-active {
  transform: translateX(0);
  transition: transform 300ms cubic-bezier(0.22, 1, 0.36, 1);
}

.page-slide-exit {
  transform: translateX(0);
}

.page-slide-exit-active {
  transform: translateX(-30%);
  transition: transform 300ms cubic-bezier(0.22, 1, 0.36, 1);
}
```
