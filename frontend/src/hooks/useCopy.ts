import { useCallback } from 'react'
import { copyText } from '../lib/clipboard'
import { toast } from '../components/ui/Feedback'

export function useCopy(): (text: string, message?: string) => Promise<void> {
  return useCallback(async (text: string, message = '已复制到剪贴板') => {
    const ok = await copyText(text)
    if (ok) toast.success(message)
    else toast.error('复制失败，请手动选择文本复制')
  }, [])
}
