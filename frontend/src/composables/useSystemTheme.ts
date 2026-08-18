/**
 * 主题（Light / Dark）跟随系统偏好。
 *
 * 决策：M1 不提供主题切换控件（design-system.md 未定义该入口），
 * 只跟随 `prefers-color-scheme`，通过 `<html class="dark">` 驱动 tokens.css 的 Dark 覆盖。
 */
import { watchEffect } from 'vue'

import { useMediaQuery } from '@/composables/useMediaQuery'
import { DARK_MEDIA_QUERY } from '@/utils/uiConstants'

const DARK_CLASS = 'dark'

export function useSystemTheme(): void {
  const prefersDark = useMediaQuery(DARK_MEDIA_QUERY)

  watchEffect(() => {
    document.documentElement.classList.toggle(DARK_CLASS, prefersDark.value)
    document.documentElement.style.colorScheme = prefersDark.value ? 'dark' : 'light'
  })
}
