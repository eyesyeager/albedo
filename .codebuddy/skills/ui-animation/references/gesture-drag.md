# 手势和拖拽动画

用于拖拽、滑动和手势交互的模式，用户直接操控元素。

## 目录
- [基于动量的关闭](#基于动量的关闭)
- [边界阻尼](#边界阻尼)
- [指针捕获](#指针捕获)
- [多点触控保护](#多点触控保护)
- [摩擦力 vs 硬停止](#摩擦力-vs-硬停止)
- [滑动关闭模式](#滑动关闭模式)

## 基于动量的关闭

不要要求拖过距离阈值。计算释放时的速度 — 快速轻扫应该足以关闭。

```ts
function onPointerUp(e: PointerEvent) {
  const timeTaken = Date.now() - dragStartTime;
  const velocity = Math.abs(swipeAmount) / timeTaken;

  if (Math.abs(swipeAmount) >= SWIPE_THRESHOLD || velocity > 0.11) {
    dismiss();
  } else {
    snapBack();
  }
}
```

使用速度 > 0.11 作为合理的默认阈值。结合最小距离阈值（如 20px）以防止意外关闭。

## 边界阻尼

当用户拖过自然边界时（如在已经在顶部时向上拉抽屉），应用阻尼。拖得越多，元素移动得越少。

```ts
function applyDamping(offset: number, max: number): number {
  return max * (1 - Math.exp(-offset / max));
}

// 用法：随着 offset 增长，移动减少
const dampedOffset = applyDamping(rawOffset, 200);
```

现实生活中的东西不会突然停止 — 它们先减速。摩擦力而非硬停止总是感觉更自然。

## 指针捕获

一旦拖拽开始，在元素上捕获所有指针事件。这确保即使指针离开元素边界，拖拽也能继续。

```ts
function onPointerDown(e: PointerEvent) {
  (e.target as HTMLElement).setPointerCapture(e.pointerId);
  isDragging = true;
}

function onPointerUp(e: PointerEvent) {
  (e.target as HTMLElement).releasePointerCapture(e.pointerId);
  isDragging = false;
}
```

始终使用 `setPointerCapture` — 没有它，快速滑动会逃出元素，拖拽就断了。

## 多点触控保护

在初始拖拽开始后忽略额外的触摸点。没有这个，拖拽中途换手指会导致元素跳动。

```ts
let activeTouchId: number | null = null;

function onPointerDown(e: PointerEvent) {
  if (activeTouchId !== null) return; // 忽略额外的触摸
  activeTouchId = e.pointerId;
  // 开始拖拽...
}

function onPointerUp(e: PointerEvent) {
  if (e.pointerId !== activeTouchId) return;
  activeTouchId = null;
  // 结束拖拽...
}
```

## 摩擦力 vs 硬停止

不要阻止拖过边界，而是允许它但增加摩擦力：

```ts
function applyFriction(delta: number, isAtBoundary: boolean): number {
  if (!isAtBoundary) return delta;
  return delta * 0.3; // 在边界处 30% 的移动量
}
```

硬停止感觉像坏了 — 用户期望物理效果。对滚动容器、滑块和抽屉应用摩擦力。

## 滑动关闭模式

结合速度、距离和方向实现完整的滑动手势：

```ts
function handleSwipeEnd(direction: "left" | "right", distance: number, velocity: number) {
  const shouldDismiss = distance > THRESHOLD || velocity > 0.11;

  if (shouldDismiss) {
    // 带剩余动量沿滑动方向动画退出
    animateOut(direction, velocity);
  } else {
    // 弹回原位
    springBack();
  }
}
```

退出动画应沿滑动方向继续带动量 — 跳到不同方向会感觉不对。
