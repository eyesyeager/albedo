/**
 * UI 行为常量。
 *
 * 收口纪律：
 *   ① 视觉量（颜色 / 字号 / 间距 / 圆角 / 阴影 / 时长 / 布局尺寸）→ `styles/tokens.css`
 *   ② 业务阈值（消息长度、分页、限流）→ `sys_config`（configStore）
 *   ③ 本文件只放**交互行为常量**（判定阈值、节流参数），全部具名并注明来源，
 *      目的就是消灭组件内的裸魔法数字。
 */

/** 复制成功反馈保持时长（design-system.md §6.7：2 秒后恢复）。 */
export const COPY_FEEDBACK_MS = 2000

/** 字符计数出现的占比阈值（design-system.md §6.3：仅接近上限时出现）。 */
export const COUNTER_VISIBLE_RATIO = 0.9

/** 判定"已接近底部"的容差像素：小于该距离才自动跟随流式内容（design-system.md §10.2）。 */
export const NEAR_BOTTOM_TOLERANCE_PX = 48

/** 桌面断点（design-system.md §9：≥1024 固定侧栏，<1024 抽屉）。 */
export const DESKTOP_MEDIA_QUERY = '(min-width: 1024px)'

/** 减少动效偏好查询（可访问性强制项）。 */
export const REDUCED_MOTION_MEDIA_QUERY = '(prefers-reduced-motion: reduce)'

/** 深色模式系统偏好查询。 */
export const DARK_MEDIA_QUERY = '(prefers-color-scheme: dark)'

/** WCAG 2.1 AA 普通文本对比度阈值。 */
export const WCAG_AA_CONTRAST = 4.5

/** 倒计时刷新间隔：每秒更新一次可视文本（design-system §14.3.2：不逐秒播报、不逐秒动画）。 */
export const COUNTDOWN_TICK_MS = 1000

/** 限流恢复说明的保留时长（design-system §14.4：交叉淡化后短暂保留再移除）。 */
export const RATE_LIMIT_RECOVERED_NOTE_MS = 5000

/** 埋点批量上报的等待窗口：攒批降低请求数，同时保证事件及时落库。 */
export const ANALYTICS_FLUSH_DELAY_MS = 3000

/**
 * 行内图标像素尺寸（design-system §14.2.1：状态条左侧 16px 线性图标）。
 * Lucide 组件的 `size` 只接受数字，无法直接消费 CSS 变量，故在此具名收口，
 * 🔴 组件模板内不得再出现裸数字。
 */
export const ICON_SIZE_INLINE = 16

/**
 * 「停止生成」按钮的 DOM id。
 * 用途：确认卡内按 Esc 时把焦点移到停止按钮（design-system §14.3.3），
 * 该跨组件焦点转移由用户主动按键触发，属规范明确允许的行为。
 */
export const COMPOSER_STOP_BUTTON_ID = 'composer-stop-button'

/**
 * 剩余次数提醒的展示阈值（design-system §15.1.1）。
 *
 * 🔴 这是**展示层常量**，不是业务额度阈值：
 *   它只表达"剩余次数不足 10 时才展示额度状态轨提醒，充足时整条状态轨不挂载"，
 *   🔴 不进 Store / API、不改变后端准入、🔴 更不得写死平台默认日限额（50）本身。
 *   绝对次数（而非比例）保证不同租户限额下"何时提醒用户"的心智一致。
 */
export const QUOTA_REMIND_THRESHOLD = 10

/** 平板及以上断点（design-system §15.6：≥768px 状态轨一行两区并可显示 IANA 名称）。 */
export const TABLET_MEDIA_QUERY = '(min-width: 768px)'

/**
 * 额度状态轨的 DOM id。
 * 用途：额度用尽时输入框与发送按钮通过 `aria-describedby` 关联持久说明
 * （design-system §15.5：持久 inline，🔴 不用 Tooltip / Toast）。
 */
export const QUOTA_RAIL_ID = 'composer-quota-rail'

/**
 * 「到达 resetsAt 后重新查询」定时器的单段上限（分段重排，避免超长 setTimeout）。
 *
 * 存在理由：额度日长度可达 25 小时（DST），单个超长定时器在后台标签页极易被节流/漂移；
 * 分段到期后先校验是否真的越过 `resetsAt`，🔴 未到达则继续排程，绝不提前刷新。
 */
export const QUOTA_RESET_RECHECK_MAX_MS = 15 * 60 * 1000

/**
 * 到达 `resetsAt` 后的重查询宽限（毫秒）。
 * 🔴 只用于"等服务端跨过日界线"，不参与任何额度日长度推算。
 */
export const QUOTA_RESET_SLACK_MS = 1000
