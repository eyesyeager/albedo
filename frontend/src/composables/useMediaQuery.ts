/**
 * 媒体查询响应式封装（断点判断不依赖 JS 测量宽度，只监听 matchMedia）。
 *
 * 用途：桌面固定侧栏 vs 抽屉侧栏（design-system.md §9）、减少动效偏好降级。
 */
import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

export function useMediaQuery(query: string): Ref<boolean> {
  const matches = ref(false)
  let mediaQueryList: MediaQueryList | null = null

  function update(event: MediaQueryList | MediaQueryListEvent): void {
    matches.value = event.matches
  }

  onMounted(() => {
    if (typeof window.matchMedia !== 'function') {
      return
    }
    mediaQueryList = window.matchMedia(query)
    matches.value = mediaQueryList.matches
    mediaQueryList.addEventListener('change', update)
  })

  onBeforeUnmount(() => {
    mediaQueryList?.removeEventListener('change', update)
    mediaQueryList = null
  })

  return matches
}
