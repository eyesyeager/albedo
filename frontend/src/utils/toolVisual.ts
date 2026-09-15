/**
 * 工具状态视觉映射（design-system.md §14.2.2）。
 *
 * 抽成纯模块的理由：8 种状态的图标 / 色调映射需要被单测直接断言，
 * 且避免组件文件因映射表膨胀；🔴 状态永远同时具备图标 + 文字，颜色只是辅助编码。
 */
import {
  Ban,
  CheckCircle2,
  CircleX,
  Clock3,
  LoaderCircle,
  ShieldX,
  TimerOff,
} from 'lucide-vue-next'
import type { Component } from 'vue'

import type { ToolCallStatusValue } from '@/types/tool'

/** 语义色调名（对应 tokens.css 的 --color-tool-status-*）。 */
export type ToolStatusTone = 'neutral' | 'running' | 'success' | 'warning' | 'danger'

const ICONS: Readonly<Record<string, Component>> = {
  pending: Clock3,
  running: LoaderCircle,
  succeeded: CheckCircle2,
  failed: CircleX,
  timed_out: TimerOff,
  cancelled: Ban,
  denied: ShieldX,
}

const TONES: Readonly<Record<string, ToolStatusTone>> = {
  pending: 'neutral',
  running: 'running',
  succeeded: 'success',
  failed: 'danger',
  timed_out: 'warning',
  cancelled: 'neutral',
  denied: 'danger',
}

/** 未知状态降级为中性时钟图标（🔴 不崩、不显示原始枚举值）。 */
export function toolStatusIcon(status: ToolCallStatusValue): Component {
  return ICONS[status] ?? Clock3
}

export function toolStatusTone(status: ToolCallStatusValue): ToolStatusTone {
  return TONES[status] ?? 'neutral'
}
