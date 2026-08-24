# Albedo 设计系统

**版本**：V1.3  
**日期**：2026-08-18  
**负责人**：@UI 设计师  
**适用范围**：M1 对话站点、M3 终端用户侧工具编排交互、M3.1 每日对话额度展示  
**需求基线**：`docs/prd.md` V1.4  
**接口基线**：`docs/api-spec.md` V1.2.5  
**技术基线**：`docs/architecture.md` V1.4.5（ADR-020）  
**Token 唯一来源**：`frontend/src/styles/tokens.css`

> 本文决定 Albedo “长什么样、怎么动”，不增删 PRD 信息项、不改变业务规则。静态 UI 文案、租户配置文案与平台配置提示的归属见本文第 3 节。Boss 已裁决 M2 管理后台全量 Deferred 至二期；本版不提供任何 `/admin/*`、`/platform/*` 页面、入口或视觉实现规范。

---

## 1. 风格选型与依据

### 1.1 产品与受众判断

- **产品类型**：多租户 AI 问答 SaaS；一期设计范围仅含终端对话与站点状态。
- **核心任务**：阅读和输入长文本、识别生成与工具执行状态、对高风险工具作出明确决定、管理本人会话。
- **主要受众**：终端问答用户。M2 管理角色界面全量 Deferred 至二期。
- **体验关键词**：安静、可信、清晰、快速、可恢复、内容优先。
- **视觉记忆点**：不是装饰，而是“界面退后、内容前置”——助手回答直接落在阅读面上，用户输入仅以轻填充区分。

### 1.2 Skill 辅助结论与裁决

`ui-ux-pro-max --design-system` 将该产品识别为“单一核心任务、留白、强对比、移动优先”的极简产品；中文字体检索首选 **Noto Sans SC / Noto Sans SC**，适用于中国大陆专业业务系统与长文本阅读。其可访问性检索确认：正文对比度至少 4.5:1、44×44px 触控区、可见焦点、逻辑化 Tab 顺序、Reduced Motion、375/768/1024/1440 四档测试。

Skill 同时给出了 OLED 深色、蓝色主色、橙色 CTA、Inter 与 glow 等通用候选。以下候选被明确舍弃：

| 候选 | 裁决 | 原因 |
|---|---|---|
| 蓝色主色 + 橙色 CTA | 舍弃 | 破坏 Boss 已确定的单一近黑主色与 90/10 中性比例 |
| OLED 纯黑全局背景 | 收敛 | 采用 `#0E1014`，避免纯黑造成层级断裂与长文眩光 |
| Inter | 舍弃 | 中文产品字形覆盖和气质不足；采用思源黑体优先 |
| glow / 霓虹 | 舍弃 | 命中 AI 风与视觉噪声红线 |
| 大卡片堆叠 | 舍弃 | 对话内容会被容器抢夺视觉权重 |

### 1.3 最终风格：Refined Minimal（克制现代）

1. **中性色占约 90%**：页面、侧栏、分隔、文字均来自冷中性灰阶。
2. **单一近黑品牌色占约 10%**：只用于主操作、焦点、流式光标和必要强调。
3. **描边优于阴影**：普通卡片默认无阴影；下拉、抽屉、模态才使用阴影。
4. **对话优先**：助手消息左对齐且无气泡；用户消息右对齐、轻填充、最大宽度受控。
5. **统一字族**：标题和正文均使用 Noto Sans SC，以字重、字号、留白建立层级，避免无目的字体混搭。
6. **Dark 非简单反色**：深灰分层背景 + 浅色交互面，保证内容密度和可读性。
7. **动效隐形**：高频操作短且可中断；流式文本只做分片合并后的 ≤120ms 淡入，不做逐字位移。

`frontend-design` 质感把关结论：本项目的差异化不依赖纹理、渐变或不规则构图，而依赖精确的 720px 阅读宽度、16px/1.6 正文、克制圆角、稳定基线与助手无气泡表达。所有视觉选择均服务于长文本阅读。

---

## 2. 设计原则

1. **内容先于容器**：能用留白和分隔表达的层级，不新增卡片。
2. **一屏一个主动作**：对话页主动作是发送/停止；高风险确认卡局部主动作是允许执行，同时保留同等可达的拒绝路径。
3. **状态可识别、可恢复**：颜色必须配合图标和文字；失败态必须给恢复动作。
4. **高频交互不拖沓**：会话切换、键盘操作不做位移动画。
5. **空间连续**：抽屉从其所属边缘进入；下拉从触发点展开；退出比进入快。
6. **移动优先**：最小支持宽度 375px；任何档位不得产生页面级横向滚动。
7. **双主题同构**：信息层级和语义不因主题改变，只调整色值与阴影。

---

## 3. 文案与配置边界

| 文案类型 | 唯一归属 | 示例 | 设计/实现要求 |
|---|---|---|---|
| 租户品牌与业务文案 | `site_config_versions` | `siteTitle`、`welcomeText`、`inputPlaceholder`、`loginText`、`registerText`、`newChatText`、`emptySessionText`、`agentUnavailableText`、`footerDisclaimer` | 设计稿标注“租户可配置”；不得写死在组件或 locales |
| 平台业务枚举/阈值提示 | `sys_config`，`is_frontend=1` | 模型清单、消息长度上限、分页可选项、工具状态标签 | 前端按配置渲染，不内联魔法数字 |
| 静态 UI 文案 | `frontend/src/locales/zh-CN.ts` | 发送、停止、取消、复制、重试、加载失败、暂无会话 | `.vue` 中只引用 locale key，不内联字面量 |

### 3.1 设计稿标注规则

- `[TENANT]`：租户可配置文案，例如 `[TENANT] welcomeText`。
- `[SYS]`：平台配置或枚举，例如 `[SYS] messageMaxChars`。
- `[I18N]`：静态 UI 文案，例如 `[I18N] common.retry`。
- 租户 `themePrimaryColor` 仅可在发布校验通过后覆盖品牌语义组；必须同步生成 hover/pressed/subtle，并验证按钮文字至少 4.5:1。校验失败继续使用本文默认 Token，不允许前端静默接受低对比颜色。

### 3.2 一期明确不出现

图片/文件上传、语音输入输出、插件、深度研究、计费/套餐入口均不设计、不预留假入口，也不复制参考图中的商标、产品名或专属能力文案。

---

## 4. Design Token

### 4.1 颜色语义层：Light / Dark 完整对照

| Token | Light | Dark | 用途 |
|---|---|---|---|
| `--color-brand` | `#111111` | `#F5F7FA` | 主操作、选中、重点交互 |
| `--color-brand-hover` | `#2B2B2B` | `#E4E7EB` | 主操作 hover |
| `--color-brand-pressed` | `#000000` | `#FFFFFF` | 主操作 active |
| `--color-brand-subtle` | `#F0F1F2` | `rgba(245,247,250,.10)` | 选中底、低强调 Chip |
| `--color-success` | `#237A35` | `#58D275` | 成功文字/图标；须配合语义文案 |
| `--color-warning` | `#915D00` | `#FFC15A` | 警告文字/图标 |
| `--color-danger` | `#C92A2A` | `#FF7B7B` | 危险操作、错误 |
| `--color-info` | `var(--color-brand)` | 继承主题品牌色 | 中性信息提示 |
| `--color-text-primary` | `#16181C` | `#F5F7FA` | 标题、正文 |
| `--color-text-secondary` | `#4B5159` | `#B5BCC6` | 次级说明、时间 |
| `--color-text-tertiary` | `#656B73` | `#8F98A5` | 占位符、辅助信息；已按 AA 加深 |
| `--color-text-disabled` | `#8A9099` | `#676F7A` | 禁用控件；不得承载必要信息 |
| `--color-text-on-brand` | `#FFFFFF` | `#111111` | 品牌按钮文字 |
| `--color-text-link` | `#2B2B2B` | `#E4E7EB` | 正文链接；始终配合下划线 |
| `--color-bg-base` | `#FFFFFF` | `#0E1014` | 页面底色 |
| `--color-bg-subtle` | `#F6F7F9` | `#16181D` | 侧栏、次级区域 |
| `--color-bg-elevated` | `#FFFFFF` | `#1C1F26` | 浮层、输入区、卡片 |
| `--color-bg-inverse` | `#16181C` | `#F5F7FA` | Tooltip 等反色面 |
| `--color-bg-overlay` | `rgba(14,16,20,.56)` | `rgba(0,0,0,.66)` | 抽屉/模态遮罩 |
| `--color-fill-subtle` | `rgba(22,24,28,.04)` | `rgba(255,255,255,.05)` | hover、弱填充 |
| `--color-fill-default` | `rgba(22,24,28,.07)` | `rgba(255,255,255,.08)` | 默认填充 |
| `--color-fill-strong` | `rgba(22,24,28,.11)` | `rgba(255,255,255,.13)` | active、强填充 |
| `--color-border` | `rgba(22,24,28,.12)` | `rgba(255,255,255,.11)` | 常规描边、分隔 |
| `--color-border-strong` | `rgba(22,24,28,.20)` | `rgba(255,255,255,.19)` | hover、强分隔 |
| `--color-border-focus` | `#111111` | `#F5F7FA` | 2px 键盘焦点环 |
| `--color-bubble-user-bg` | `#F0F1F2` | `#252930` | 用户消息轻填充 |
| `--color-bubble-user-text` | `#16181C` | `#F5F7FA` | 用户消息文字 |
| `--color-bubble-assistant-bg` | `transparent` | `transparent` | 助手消息无气泡 |
| `--color-bubble-assistant-text` | `#16181C` | `#F5F7FA` | 助手回答文字 |
| `--color-code-bg` | `#16181C` | `#08090B` | 代码块背景 |
| `--color-code-text` | `#F5F7FA` | `#E8ECF2` | 代码文字 |
| `--color-streaming-caret` | `#111111` | `#F5F7FA` | 流式状态光标 |

#### 4.1.1 M3 工具编排新增语义色

| Token | Light | Dark | 用途 |
|---|---|---|---|
| `--color-tool-status-neutral` | `#4B5159` | `#B5BCC6` | pending / cancelled 文字与图标 |
| `--color-tool-status-running` | `#2B2B2B` | `#E4E7EB` | running 文字与图标 |
| `--color-tool-status-success` | `#237A35` | `#58D275` | succeeded 文字与图标 |
| `--color-tool-status-warning` | `#915D00` | `#FFC15A` | awaiting_confirmation / timed_out 文字与图标 |
| `--color-tool-status-danger` | `#C92A2A` | `#FF7B7B` | failed / denied 文字与图标 |
| `--color-tool-surface` | `#F8F9FA` | `#16181D` | 极简工具状态条背景 |
| `--color-tool-border` | `rgba(22,24,28,.12)` | `rgba(255,255,255,.11)` | 工具状态条描边 |
| `--color-tool-confirm-surface` | `#FFFDF7` | `#211D16` | 高风险确认卡片的克制警示底色 |
| `--color-tool-confirm-border` | `rgba(145,93,0,.32)` | `rgba(255,193,90,.32)` | 高风险确认卡片描边 |
| `--color-tool-risk-low` / `-bg` | `#4B5159` / `rgba(22,24,28,.06)` | `#B5BCC6` / `rgba(255,255,255,.08)` | low 风险标签 |
| `--color-tool-risk-medium` / `-bg` | `#915D00` / `rgba(145,93,0,.10)` | `#FFC15A` / `rgba(255,193,90,.12)` | medium 风险标签 |
| `--color-tool-risk-high` / `-bg` | `#C92A2A` / `rgba(201,42,42,.09)` | `#FF7B7B` / `rgba(255,123,123,.12)` | high 风险标签 |
| `--color-tool-countdown-track` | `rgba(145,93,0,.14)` | `rgba(255,193,90,.16)` | 确认倒计时静态轨道 |
| `--color-tool-countdown-progress` | `#915D00` | `#FFC15A` | 倒计时剩余进度；只配合时间文字使用 |
| `--color-rate-limit-surface` | `#F8F9FA` | `#16181D` | Composer 限流说明背景 |
| `--color-rate-limit-border` | `rgba(22,24,28,.20)` | `rgba(255,255,255,.19)` | Composer 限流说明描边 |

> 上述状态色只作辅助编码，所有状态必须同时具备 Lucide 图标与文字。M3 自定义组件直接消费项目 Token；Element Plus 的 success / warning / danger / error 继续映射既有 `--color-success` / `--color-warning` / `--color-danger`，不为风险标签新增第二套 `--el-*` 色值。

### 4.2 字体刻度

| Token | 值 | 用途 |
|---|---|---|
| `--font-family-display` | `"Noto Sans SC", "Source Han Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif` | 页面标题、欢迎语 |
| `--font-family-body` | 同 display | 正文、控件；统一中文灰度 |
| `--font-family-mono` | `"JetBrains Mono", "SFMono-Regular", Consolas, "Liberation Mono", monospace` | 代码与结构化标识 |
| `--font-size-display` | `40px` | 极少使用的展示标题；移动端降为 title-1 |
| `--font-size-title-1` | `30px` | 页面 H1，字重 600 |
| `--font-size-title-2` | `24px` | 大区块标题 |
| `--font-size-title-3` | `20px` | 子标题，字重 500 |
| `--font-size-headline` | `17px` | 组件标题、对话强调 |
| `--font-size-body` | `16px` | 正文、输入；移动端不缩小 |
| `--font-size-callout` | `15px` | 控件、列表主文案 |
| `--font-size-footnote` | `13px` | 辅助说明、表头、错误提示 |
| `--font-size-caption` | `12px` | 时间、代码语言、极短标签 |
| `--font-weight-regular` | `400` | 正文 |
| `--font-weight-medium` | `500` | 子标题、控件 |
| `--font-weight-semibold` | `600` | H1、主按钮、重点 |
| `--font-weight-bold` | `700` | 仅强警示或 Markdown strong |
| `--line-height-tight` | `1.25` | 标题 |
| `--line-height-base` | `1.6` | 控件与普通正文 |
| `--line-height-relaxed` | `1.75` | 助手 Markdown 长文 |
| `--letter-spacing-title` | `-0.02em` | 标题 |
| `--letter-spacing-body` | `0` | 中文正文 |

> 字体加载失败时使用系统中文无衬线栈，不阻塞首屏。正文每行建议 60～75 个字符，主阅读容器不超过 720px。

### 4.3 间距、圆角、描边与阴影

| 组 | Token | 值 | 用途 |
|---|---|---:|---|
| 间距 | `--spacing-xs` | `4px` | 图标与短标签微间距 |
| 间距 | `--spacing-sm` | `8px` | 相邻控件、触控目标间最小间隔 |
| 间距 | `--spacing-md` | `12px` | 组件内部紧凑间距 |
| 间距 | `--spacing-base` | `16px` | 移动页边距、常规内边距 |
| 间距 | `--spacing-lg` | `24px` | 区块间距、桌面 gutter |
| 间距 | `--spacing-xl` | `32px` | 大区块间距 |
| 间距 | `--spacing-2xl` | `48px` | 页面段落节奏 |
| 间距 | `--spacing-3xl` | `64px` | 空态与首屏大留白 |
| 圆角 | `--radius-xs` | `4px` | 代码内联、微型标签 |
| 圆角 | `--radius-sm` | `6px` | 小按钮、状态条 |
| 圆角 | `--radius-md` | `8px` | 默认按钮、输入框 |
| 圆角 | `--radius-lg` | `12px` | 卡片、下拉、用户气泡 |
| 圆角 | `--radius-xl` | `16px` | 抽屉/模态大面 |
| 圆角 | `--radius-full` | `9999px` | 仅头像、状态点、确有语义的 Pill |
| 阴影 | `--shadow-none` | `none` | 普通内容容器 |
| 阴影 | `--shadow-sm` | Light `0 1px 2px rgba(22,24,28,.06)`；Dark `rgba(0,0,0,.28)` | 轻抬升 |
| 阴影 | `--shadow-md` | Light `0 8px 24px rgba(22,24,28,.09)`；Dark `rgba(0,0,0,.36)` | 下拉、浮层 |
| 阴影 | `--shadow-lg` | Light `0 20px 48px rgba(22,24,28,.14)`；Dark `rgba(0,0,0,.46)` | 抽屉、模态 |

描边统一 `1px solid var(--color-border)`；高密度表格分隔允许视觉 1px，不使用彩色阴影。Focus 为 `2px solid var(--color-border-focus)` + `2px` offset。

### 4.4 动效 Token

| Token | 值 | 用途 |
|---|---|---|
| `--duration-instant` | `120ms` | 按下、流式分片淡入、状态快速切换 |
| `--duration-fast` | `180ms` | Tooltip、hover、Toast 退出 |
| `--duration-normal` | `240ms` | 下拉、侧栏、常规组件 |
| `--duration-slow` | `300ms` | 抽屉、模态 |
| `--duration-page` | `360ms` | 低频页面转场；高频会话切换不使用 |
| `--ease-enter` | `cubic-bezier(.22,1,.36,1)` | 入场与通用减速 |
| `--ease-move` | `cubic-bezier(.25,1,.5,1)` | 侧栏、抽屉空间移动 |
| `--ease-exit` | `cubic-bezier(.4,0,1,1)` | 更快退场 |
| `--spring-default` | `300, 30` | 抽屉释放、可中断空间移动 |
| `--spring-snappy` | `400, 32` | 按钮、短反馈；无明显弹跳 |
| `--spring-smooth` | `260, 36` | 平稳状态切换 |

实现优先级：CSS transitions > WAAPI > CSS keyframes > JS。Vue3 可选 Motion One / `@vueuse/motion`；禁止 React 专属动效库。简单淡入、颜色变化不用 spring。

### 4.5 z-index

| Token | 值 | 用途 |
|---|---:|---|
| `--z-base` | `0` | 常规内容 |
| `--z-sticky` | `900` | 吸顶头部、吸底输入区 |
| `--z-dropdown` | `1000` | Select、Popover、更多菜单 |
| `--z-drawer` | `1100` | 移动端会话侧栏抽屉 |
| `--z-modal` | `1200` | Dialog 与遮罩 |
| `--z-toast` | `1300` | 全局反馈；不可被模态遮挡 |

禁止组件自行发明 `9999`。嵌套弹层先复用上述层级，再通过 DOM 顺序解决。

### 4.6 布局 Token

| Token | 值 | 用途 |
|---|---:|---|
| `--layout-sidebar-width` | `280px` | ≥1024px 会话侧栏 |
| `--layout-header-height` | `56px` | 顶部栏与内容避让 |
| `--layout-content-max-width` | `720px` | 对话正文最佳阅读宽度 |
| `--layout-composer-max-width` | `720px` | 输入区最大宽度 |

---

## 5. WCAG 2.1 AA 实测对比度

计算采用 WCAG 2.1 相对亮度公式。普通文本阈值 4.5:1；大文本阈值 3:1。本项目下表均按更严格的普通文本阈值判断。

| 场景 | Light 前景/背景 | 比值 | AA | Dark 前景/背景 | 比值 | AA |
|---|---|---:|:---:|---|---:|:---:|
| 正文 | `#16181C / #FFFFFF` | `17.77:1` | 通过 | `#F5F7FA / #0E1014` | `17.74:1` | 通过 |
| 次级文字 | `#4B5159 / #FFFFFF` | `8.01:1` | 通过 | `#B5BCC6 / #0E1014` | `9.95:1` | 通过 |
| 占位/辅助 | `#656B73 / #FFFFFF` | `5.38:1` | 通过 | `#8F98A5 / #0E1014` | `6.53:1` | 通过 |
| 主按钮文字 | `#FFFFFF / #111111` | `18.88:1` | 通过 | `#111111 / #F5F7FA` | `17.59:1` | 通过 |
| 链接 | `#2B2B2B / #FFFFFF` | `14.16:1` | 通过 | `#E4E7EB / #0E1014` | `15.35:1` | 通过 |
| 危险态文字 | `#C92A2A / #FFFFFF` | `5.46:1` | 通过 | `#FF7B7B / #0E1014` | `7.59:1` | 通过 |
| 成功态文字 | `#237A35 / #FFFFFF` | `5.38:1` | 通过 | `#58D275 / #0E1014` | `9.87:1` | 通过 |
| 警告态文字 | `#915D00 / #FFFFFF` | `5.57:1` | 通过 | `#FFC15A / #0E1014` | `11.81:1` | 通过 |
| M3 工具中性状态 | `#4B5159 / #F8F9FA` | `7.60:1` | 通过 | `#B5BCC6 / #16181D` | `9.28:1` | 通过 |
| M3 工具执行中 | `#2B2B2B / #F8F9FA` | `13.43:1` | 通过 | `#E4E7EB / #16181D` | `14.32:1` | 通过 |
| M3 工具成功 | `#237A35 / #F8F9FA` | `5.10:1` | 通过 | `#58D275 / #16181D` | `9.21:1` | 通过 |
| M3 确认警告 | `#915D00 / #FFFDF7` | `5.48:1` | 通过 | `#FFC15A / #211D16` | `10.41:1` | 通过 |
| M3 确认危险 | `#C92A2A / #FFFDF7` | `5.37:1` | 通过 | `#FF7B7B / #211D16` | `6.69:1` | 通过 |

### 5.1 参考色调整说明

- `#8A9099` 在白底作为占位文字不足 AA，因此可读占位调整为 `#656B73`；原值仅保留为非必要的 disabled 色。
- success 从 `#2FB344` 调整为 `#237A35`、warning 调整为 `#915D00`、danger 从 `#E03131` 调整为 `#C92A2A`，确保它们作为小字号状态文字时满足 AA。
- Dark 使用更亮的功能色，不沿用 Light 色值，避免低亮度背景上的视觉塌陷。
- 链接除对比度外始终有下划线或明确图标，不只靠颜色表达。

---

## 6. 组件规范

### 6.1 通用交互基线

- 默认可点击目标至少 `44×44px`；相邻触控目标间距至少 8px。
- hover 仅在 `@media (hover: hover) and (pointer: fine)` 启用。
- 所有 icon-only 按钮必须有 `[I18N] aria-label` 和可见 Tooltip。
- `cursor: pointer` 仅用于确实可点击的元素；disabled 使用 `not-allowed` 或默认光标并禁止事件。
- 焦点顺序必须与视觉顺序一致；不得使用正数 `tabindex` 人工跳序。

### 6.2 按钮

**尺寸**：默认高度 44px，水平内边距 16px；大按钮 48px。默认圆角 `--radius-md`，仅状态点、头像等明确语义使用 full；M3 风险标签使用 `--radius-xs`，不得无脑胶囊化。

| 变体 | Default | Hover | Active | Focus | Disabled | Loading |
|---|---|---|---|---|---|---|
| Primary | brand 背景 + on-brand 文字；600 | brand-hover | brand-pressed + `scale(.97)` | 2px focus ring + 2px offset | fill-subtle + disabled 文字；无阴影 | 保持宽度；Spinner + 原语义可供读屏 |
| Secondary | elevated 背景 + strong border + primary 文字 | fill-subtle | fill-default + `scale(.97)` | 同上 | border + disabled 文字 | 同上 |
| Ghost | 透明背景 + primary 文字 | fill-subtle | fill-default + `scale(.97)` | 同上 | disabled 文字 | 同上 |
| Danger | danger 文字 + danger 语义描边；确认态可填充 | 轻 danger 底，不引入 glow | 强化填充 + `scale(.97)` | focus ring + 危险语义文本 | disabled | Spinner，不改变危险语义 |

- loading 时禁止重复提交，保留按钮原宽，不因文案变化造成布局跳动。
- 键盘 Space/Enter 触发功能，但不播放 scale 动画；仍保留颜色反馈。
- 主操作每个局部区域至多一个。

### 6.3 输入框与多行 Composer

- 单行输入高度 44px；多行消息 Composer 最小高度 52px，内容增长到约 200px 后内部滚动。
- 字号 16px，行高 1.6；水平内边距 16px，垂直 12px；圆角 `--radius-lg`。
- 默认 elevated 背景 + border；focus 只增强描边，不使用彩色 glow。
- Enter 发送，Shift+Enter 换行；IME composition 期间 Enter 不得发送。
- 字符计数仅接近 `[SYS] messageMaxChars` 时出现；超长使用 danger 图标 + 文案，不只变红。
- 发送与停止占用同一 44px 操作位，避免控件跳动；无 Agent、空白、只读时禁用并给文字原因。
- 自动增长不得动画 `height`；直接更新高度，保持输入延迟最低。
- label 可视觉隐藏但必须存在；placeholder 不代替 label。

### 6.4 下拉 / Agent 选择器

- 触发器高度 44px，最大宽度 280px；名称单行省略，当前 Agent 必须有文本名称。
- 下拉宽度至少等于触发器，最大 360px；名称 + 可选描述，不展示敏感模型配置。
- 当前项使用 check 图标 + `brand-subtle`，不是仅颜色。
- 支持 Arrow Up/Down、Home/End、Enter、Escape；打开后焦点进入当前项，关闭后返回触发器。
- 加载用 3 行骨架；空/失败状态占据菜单内容区并提供静态说明/重试。
- 从触发边展开，`scale(.96)+opacity`，transform-origin 跟随触发侧。

### 6.5 抽屉

- Tablet/Mobile 会话侧栏使用左侧抽屉，宽度 `min(88vw, 320px)`。
- 背景 elevated，右侧 1px border，`--shadow-lg`；遮罩使用 bg-overlay。
- 打开后焦点移入抽屉首个有意义控件并形成 focus trap；Escape、遮罩点击和关闭按钮均可关闭。
- 关闭后焦点返回触发器；页面主体在抽屉打开时不可滚动。
- 位置变化用 transform；不得动画 width/left。

### 6.6 对话气泡与消息操作

**用户消息**：右对齐，`bubble-user-bg`，圆角 `--radius-lg`，内边距 `12px 16px`，最大宽度 `min(85%, 640px)`；文字 16/1.6。  
**助手消息**：左对齐、无气泡、占满 720px 阅读列；Markdown 16/1.75，段落间 16px，列表缩进 24px。  
**消息组间距**：同角色连续消息 16px，不同角色 28px；首尾保留 32px。  
**操作区**：复制、重试、重新生成在消息完成或 hover/focus-within 时出现；移动端保持可点击的显式更多按钮。  
**状态**：pending/sent/streaming/completed/stopped/failed 均有文字或可访问名称；停止/失败不得只靠颜色。

流式更新将相邻小分片按帧或短窗口合并后写入，分片容器只做 opacity 0→1，最长 120ms。消息容器使用稳定 DOM，不把每个 token 建成独立节点，不自动移动读屏焦点。

### 6.7 代码块

- 背景 code-bg，文字 code-text，圆角 `--radius-md`；顶栏 36px，显示语言名与复制按钮。
- 代码字体 13px/1.6；保留水平滚动，不强制折行破坏代码结构。
- 复制按钮命中区 44px，复制成功显示图标 + `[I18N] copied`，2 秒后恢复；读屏用礼貌 live region。
- 代码只展示、可复制、不可执行；安全渲染由 markdown-it + DOMPurify 负责。

### 6.8 工具调用状态条

M1 基线升级为 M3 正式规范，详见 §14。实现必须使用 API V1.1 的 snake_case 状态：`pending / awaiting_confirmation / running / succeeded / failed / timed_out / cancelled / denied`；状态文案取 `[SYS] display.tool_status_labels`，风险文案取 `[SYS] display.tool_risk_labels`。高风险确认是消息内联业务卡片，不是阻塞模态。

### 6.9 会话列表项

- 单行最小高度 44px；标题 15/1.4，单行省略；可选时间使用 12px secondary。
- 默认透明；hover/focus-within 使用 fill-subtle；当前会话使用 brand-subtle + 左侧 2px 语义标识。
- 更多菜单按钮占 44px 命中区，桌面 hover/focus-within 显示，触屏始终可达。
- 重命名使用原位输入，Esc 取消、Enter 保存；删除必须二次确认。
- 列表分页追加时不播放整列 stagger；新加载项直接出现，避免高频干扰。

### 6.10 四态：骨架 / 空态 / 失败态 / 加载态

| 状态 | 视觉 | 文案归属 | 行为 |
|---|---|---|---|
| 骨架屏 | 与最终结构同尺寸；中性块；伪元素 transform 微光 | 无正文 | 保留空间防跳动；Reduced Motion 静态 |
| 空态 | 48px Lucide 线性图标 + title-3 + secondary 描述 + 可选 secondary 操作 | 静态说明 `[I18N]`；租户引导 `[TENANT]` | 不用 emoji，不强塞 primary CTA |
| 失败态 | danger 图标 + 可理解原因 + 重试；不展示堆栈 | `[I18N]` + 后端安全语义 | 失败后不得继续展示可能属于旧租户的数据 |
| 加载态 | 短操作用按钮 Spinner；页面结构用骨架；流式等待显示生成状态 | `[I18N]` | 不用长时间全屏 Spinner；可取消时提供停止 |

无权限态与只读态遵循同一结构：状态图标 + 原因 + 可执行下一步；不只禁用控件而无解释。

### 6.11 页脚声明

- `[TENANT] footerDisclaimer`，置于主阅读列底部，13px secondary，最大宽度 720px，居中或左对齐随页面语境。
- 只允许纯文本或安全 Markdown 链接；链接必须下划线、可键盘访问、外链带安全 rel。
- 移动端不得被吸底 Composer 遮挡，内容末尾预留 Composer 高度 + safe-area。

### 6.12 站点状态页

404 不存在、403 暂停、503 配置异常使用同一模板，不显示 Logo、Agent、登录或租户业务数据。

```text
┌──────────────────────────────────────────┐
│                                          │
│              [线性状态图标]              │
│                状态标题                  │
│         安全、简短、不泄露租户的说明       │
│            [重试/返回（若允许）]           │
│                                          │
└──────────────────────────────────────────┘
```

- 内容宽度 480px，垂直视觉居中，页面边距 24px。
- 404：中性图标；403：warning 图标；503：danger/中性错误图标。
- HTTP 状态由 `/site/status` 返回；前端兜底视图保持同一视觉模板。

---

## 7. Element Plus 主题映射

Element Plus 是唯一组件库。映射区禁止写死色值；所有样式均从项目语义 Token 取值。

| Element Plus 变量 | 项目语义变量 |
|---|---|
| `--el-color-primary` | `--color-brand` |
| `--el-color-primary-light-3` | `--color-brand-hover` |
| `--el-color-primary-light-5/9` | `--color-brand-subtle` |
| `--el-color-primary-light-7` | `--color-fill-default` |
| `--el-color-primary-light-8` | `--color-fill-subtle` |
| `--el-color-primary-dark-2` | `--color-brand-pressed` |
| `--el-color-success/warning/danger/error/info` | 对应 `--color-*` 功能语义 |
| `--el-text-color-primary` | `--color-text-primary` |
| `--el-text-color-regular` | `--color-text-secondary` |
| `--el-text-color-secondary/placeholder` | `--color-text-tertiary` |
| `--el-text-color-disabled` | `--color-text-disabled` |
| `--el-bg-color` | `--color-bg-base` |
| `--el-bg-color-page` | `--color-bg-subtle` |
| `--el-bg-color-overlay` / `--el-fill-color-blank` | `--color-bg-elevated` |
| `--el-bg-color-disabled` | `--color-fill-subtle` |
| `--el-fill-color-extra-light/lighter/light` | `--color-fill-subtle` |
| `--el-fill-color` | `--color-fill-default` |
| `--el-fill-color-dark/darker` | `--color-fill-strong` |
| `--el-mask-color*` | `--color-bg-overlay` |
| `--el-disabled-*` | text-disabled / fill-subtle / border |
| `--el-border-color*` | border / border-strong |
| `--el-border-radius-small/base/round/circle` | radius-sm / radius-md / radius-full |
| `--el-box-shadow-light/lighter` | `--shadow-sm` |
| `--el-box-shadow` | `--shadow-md` |
| `--el-box-shadow-dark` | `--shadow-lg` |
| `--el-font-size-extra-small/small/base/medium/large/extra-large` | caption / footnote / body / headline / title-3 / title-2 |
| `--el-font-family` | `--font-family-body` |
| `--el-index-normal/top/popper` | z-base / z-sticky / z-dropdown |
| `--el-transition-duration(-fast)` | duration-fast / duration-instant |

必要用法示例：

```css
.chat-composer {
  color: var(--color-text-primary);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  transition:
    border-color var(--duration-fast) var(--ease-enter),
    transform var(--duration-instant) var(--ease-enter);
}
```

禁止在 `.vue`、Tailwind arbitrary value 或 Element Plus 局部覆盖中再次出现原始色值、圆角、阴影或动效时长。

---

## 8. 动画标注表

### 8.1 基线

- 动画只使用 `transform`、`opacity`；颜色反馈可做短过渡，但不驱动空间运动。
- 不使用 `transition: all`；不动画 width/height/top/left/margin/padding。
- 快速重复交互使用 CSS transition，确保从当前状态重定向；手势驱动期间 1:1 跟手且无 transition。
- 键盘触发的会话切换、方向键选择、Tab 导航不播放位移动画。
- `will-change` 只在动画期间加，结束立即移除。
- 流式内容使用稳定 DOM，屏幕阅读器 live region 采用礼貌、节流后的摘要更新，不抢焦点。

### 8.2 动画标注

| 动画名 | 触发条件 | 属性与起止态 | 时长/参数 | 缓动 | 可中断 | Reduced Motion |
|---|---|---|---|---|:---:|---|
| 流式文本流入 | 合并后的文本分片提交 DOM | `opacity: 0→1`；无位移 | ≤`120ms` | `--ease-enter` | 是 | ≤100ms 交叉淡化；可直接出现 |
| 停止生成切换 | 发送按钮切为停止，或停止完成 | 两个图标/文字 `opacity` 交叉；容器不移动 | `120ms` | `--ease-enter` | 是 | ≤100ms 淡化 |
| 桌面侧栏折叠 | 点击侧栏按钮 | 侧栏 `translateX(0→-100%)`；主体在结束点切换布局，不动画宽度 | `240ms` | `--ease-move` / spring smooth | 是 | 主体瞬态切换 + ≤100ms 淡化，无位移 |
| 移动端抽屉 | 点击菜单、遮罩、Esc、拖拽释放 | `translateX(-100%→0)`；遮罩 `opacity:0→1` | 面板 `300ms` 或 spring default；遮罩 `180ms` | move / enter | 是；拖拽可接管 | ≤100ms 遮罩淡化；面板瞬态出现 |
| 下拉展开 | 打开 Agent/更多菜单 | `scale(.96→1)+opacity 0→1`，origin=触发点 | `180ms`；退出 `120ms` | enter / exit | 是 | ≤100ms 淡化，无缩放 |
| 消息进入 | 用户发送确认、助手消息容器首次创建 | 整条消息 `opacity:0→1`；不做逐字位移 | `180ms` | `--ease-enter` | 是 | ≤100ms 淡化 |
| 按钮反馈 | 指针按下/释放 | `scale(1→.97→1)`；背景同步 | `120ms` / spring snappy | enter / spring | 是 | 无缩放，仅 ≤100ms 颜色/透明度 |
| 骨架屏微光 | 数据加载且骨架在视口 | 伪元素 `translateX(-100%→100%)`，低透明度 | `1600ms` 循环 | `linear` 仅用于匀速加载 | 否；离屏暂停 | 静态骨架，无循环 |
| Toast | 全局反馈加入/移除 | `translateY(6px→0)+opacity 0→1`；退出同方向 | 入 `220ms`，退 `160ms` | enter / exit | 是 | ≤100ms 仅淡化 |
| 工具状态展开 | 用户点击状态条 | 内容瞬态参与布局；内部 `opacity 0→1`，不动画高度 | `180ms` | enter | 是 | 直接出现或 ≤100ms 淡化 |
| 模态确认 | 删除会话等低频阻塞确认（不含高风险工具） | `scale(.96→1)+opacity`；遮罩淡入 | 面板 `240ms`，退出 `180ms` | enter / exit | 是 | ≤100ms 仅淡化 |

> Spring 值为 `stiffness, damping`。若前端不引入物理库，使用对应 `--ease-*` CSS 曲线降级，不得引入 React 依赖。

### 8.3 时序图：移动端抽屉

```text
0ms          60ms                180ms                 300ms
│-------------│--------------------│---------------------│
遮罩     opacity 0 ───────────────────────────────→ 1（180ms）
面板     translateX(-100%) ───────────────────────→ 0（300ms）
焦点     保持触发器 ─────────────────────────────→ 移入首项
交互     可取消/反向；拖拽接管时立即取消 transition，1:1 跟手
```

### 8.4 动画审查（ui-animation review-format）

| 之前/风险 | 之后/本规范 | 原因 |
|---|---|---|
| 流式逐 token 位移 | 分片合并 + ≤120ms opacity | 避免抖动、节点爆炸与阅读干扰 |
| 侧栏动画 width | transform 移出，布局在端点切换 | 避免 layout/paint，保持可中断 |
| 下拉从中心放大 | 从触发点 `scale(.96)` | 符合空间来源；避免 `scale(0)` |
| `transition: all` | 明确 transform/opacity/颜色属性 | 避免意外动画布局属性 |
| 入退同为 240ms | 退出缩短 20%～30% | 用户对关闭反馈期望更即时 |
| 键盘切换也播放动画 | 键盘路径无位移动画 | 高频操作保持即时 |
| Reduced Motion 仍滑动 | ≤100ms cross-fade，无位移/缩放 | 降低前庭刺激 |
| 永久 `will-change` | 只在动画期添加 | 避免合成层内存开销 |

**规范级走查结果**：未发现 `ease-in` 主曲线、布局属性动画、`transition: all`、`scale(0)`、键盘位移动画或 Reduced Motion 缺失。实现后仍需 @前端 + @测试用 DevTools 0.1×、Performance 与真实移动设备复验 60fps；当前不对尚未实现的帧率签署。

---

## 9. 四档响应式布局

断点唯一基线：`md=768px`、`lg=1024px`、`xl=1440px`；禁止恢复 `sm=640px`。

| 档位 | 栏数与侧栏 | 内容宽度 | 输入区 | 字号/间距 |
|---|---|---|---|---|
| Mobile `<768` | 单栏；侧栏为左抽屉 | 100%，页面边距 16px；正文 ≤720px | 吸底，宽度 `calc(100%-32px)`，含 safe-area | H1 24px；正文保持 16px；区块间 24px |
| Tablet `768–1023` | 主栏 + 会话抽屉侧栏 | 主内容边距 24px，正文 ≤720px | 主栏底部 sticky，最大 720px | H1 30px；gutter 24px |
| Desktop `≥1024` | 280px 固定会话侧栏 + 主栏 | 主栏中 720px 阅读列 | 主栏底部 sticky/固定视觉区，720px 居中 | 默认 Token，不缩放 |
| Wide `≥1440` | 280px 侧栏 + 居中主舞台 | 应用最大视觉宽度建议 1440px；阅读列仍 720px | 720px 居中，不随屏幕无限拉宽 | 外侧留白增加，内部间距不放大 |

### 9.1 对话页 ASCII

**Desktop / Wide**

```text
┌──────────── 280px ────────────┬──────────────────────────────────────────────┐
│ [品牌]                         │ [Agent 选择]                    [用户入口]   │ 56
│ [新聊天 44]                    ├──────────────────────────────────────────────┤
│ 会话历史                       │                                              │
│ ┌────────────────────────────┐ │        ┌──── 阅读列 max 720px ────┐          │
│ │ 会话项 44 / 更多菜单        │ │        │ 用户消息（右，轻填充）    │          │
│ │ 会话项 44                  │ │        │ 助手回答（左，无气泡）      │          │
│ └────────────────────────────┘ │        │ 工具状态 / 代码块           │          │
│                                │        └───────────────────────────┘          │
│ [用户入口]                     │        ┌──── Composer 720px ──────┐          │
└────────────────────────────────┴────────┴───────────────────────────┴──────────┘
```

**Mobile**

```text
┌──────────────────────────────┐
│ [菜单44] [Agent] [用户44]     │ 56 + safe-area
├──────────────────────────────┤
│                              │
│ 用户消息（右，≤85%）          │
│ 助手回答（左，无气泡）         │
│ 代码 / 工具状态               │
│                              │
│ [TENANT] footer              │
│ 预留 Composer + safe-area     │
├──────────────────────────────┤
│ [多行输入............][发送44]│ sticky bottom
└──────────────────────────────┘
```

### 9.2 响应式验收

- 375 / 768 / 1024 / 1440 四档必须逐档检查，无页面级横向滚动。
- 代码块和宽表允许自身横向滚动，但不得撑宽页面。
- fixed/sticky 元素为内容预留空间；移动端使用 `env(safe-area-inset-bottom)`。
- 断点变化不依赖 JS 测量；Resize 时不持续播放动画。

---

## 10. 页面级视觉规范

### 10.1 对话首页 `/`

**区块层级**

1. 顶部栏 56px：移动端菜单、Agent 选择器、用户入口。
2. 桌面侧栏：`[TENANT] Logo + siteTitle`、`[TENANT] newChatText`、会话列表、用户入口；一期不得出现管理入口。
3. 空会话主舞台：`[TENANT] welcomeText`（title-1/600）、可选 Agent 说明、Composer。
4. 页脚：`[TENANT] footerDisclaimer`。

**节奏**：欢迎语与 Composer 间 24px；空态整体在可用高度中略高于垂直中心，避免被吸底输入区压迫。  
**关键状态**：配置加载骨架、Agent 加载/空/失败、未登录 SSO 触发、输入草稿恢复、无 Agent 禁用。  
**限制**：不出现上传、语音、插件、深度研究、地图、计费入口。

### 10.2 会话详情 `/c/{conversationId}`

**区块层级**

1. 顶部栏：当前 Agent、会话标题、用户入口；标题过长省略。
2. 消息流：720px 阅读列，用户轻气泡、助手无气泡。
3. 消息局部操作：复制、重试、重新生成；默认不抢正文注意力。
4. 工具状态：贴近产生它的助手消息。
5. Composer：桌面/平板位于主栏底部，移动端吸底。

**间距**：消息组 28px，Markdown 段落 16px，代码块上下 16px；首条消息距顶部 32px。  
**关键状态**：历史加载骨架、流式、停止、失败、stopped、readOnly、Agent 停用。  
**滚动**：仅当用户接近底部时跟随新内容；用户向上阅读后显示“回到最新”，不得强制拉回底部；流式更新不得抢焦点。

### 10.3 M2 管理后台延期声明

Boss 已裁决 M2 管理后台全量 Deferred 至二期。一期不提供、不预留、不签署任何 `/admin/*`、`/platform/*` 页面、入口、表单、表格、导航或权限渲染；站点配置、Agent、Skill、MCP Server 与 Tool 授权由 DBA/开发人员按 PRD V1.2 直接维护。二期恢复时须基于届时 PRD 重新设计，不沿用本版占位。

### 10.4 站点状态页

- `SiteNotFound`：状态标题“站点不存在”语义；不确认任何租户是否存在。
- `SiteSuspended`：状态标题“站点暂停服务”语义；无登录入口。
- `SiteUnavailable`：状态标题“站点配置异常”语义；可提供重试，不泄露内部配置细节。
- 三页共用 480px 模板、同一字体和间距，仅状态图标/语义改变。

---

## 11. 可访问性检查清单

### 11.1 设计规范已覆盖（ui-ux-pro-max Pre-Delivery）

- [x] 正文与关键状态文字对比度 ≥4.5:1，并记录实测值。
- [x] 关键触控目标 ≥44×44px，相邻目标间距 ≥8px。
- [x] Focus ring 为 2px + 2px offset，未用 `outline: none` 无替代。
- [x] 焦点顺序按品牌/导航 → 主内容 → 输入 → 页脚的视觉顺序。
- [x] 所有图标按钮要求 aria-label；Tooltip 不替代无障碍名称。
- [x] 输入框有 label；错误与字段建立程序化关联。
- [x] 抽屉/模态具备焦点圈定、Escape 关闭、关闭后焦点返回。
- [x] 所有状态均使用图标/文字 + 颜色，不只靠颜色。
- [x] 链接有下划线；外链具备安全 rel。
- [x] 语义结构使用 header/nav/main/article/footer 与连续 heading 层级。
- [x] 导航较长时提供“跳到主内容”链接。
- [x] 流式更新不抢焦点；live region 使用 `polite` 且节流。
- [x] Reduced Motion 将位移/缩放降级为 ≤100ms 交叉淡化。
- [x] 375/768/1024/1440 四档规范明确，移动端无页面横向滚动。
- [x] 图标统一 Lucide SVG，不使用 emoji 作为 UI 图标。
- [x] hover 不导致布局位移，且仅在精细指针设备启用。
- [x] 异步内容预留尺寸，避免 Content Layout Shift。
- [x] 骨架与循环微光离屏暂停；Reduced Motion 静态。

### 11.2 实现后必须复验

- [ ] 使用键盘完成新建会话、Agent 选择、发送/停止、重命名、删除，以及 M3 工具摘要展开、允许/拒绝、Esc 离开确认卡。
- [ ] 使用 VoiceOver 验证消息顺序、工具状态、流式更新与错误提示。
- [ ] 系统开启 Reduce Motion 后，确认无位移/缩放/循环微光。
- [ ] Light/Dark 各自用浏览器无障碍工具复测实际渲染对比度。
- [ ] 375/768/1024/1440 各档无横向滚动、遮挡和焦点丢失。
- [ ] 200% 缩放下内容不截断，操作仍可达。
- [ ] icon-only 按钮无遗漏 aria-label；图片有恰当 alt 或空 alt。

---

## 12. 去 AI 味与视觉自查

- [x] 无紫蓝渐变、霓虹、彩色 glow、多重渐变按钮。
- [x] 无 emoji UI 图标、3D 装饰球或抽象贴纸。
- [x] 无全局胶囊化；圆角按组件层级使用。
- [x] 无浅色卡片堆叠 + 彩色阴影；普通容器优先描边和留白。
- [x] 无通用 Inter/Roboto 套用；中文以 Noto Sans SC 为有意图的主字体。
- [x] 对话助手无气泡，避免聊天页面成为“卡片墙”。
- [x] 无浮夸入场、bounce、粒子或无意义 stagger。
- [x] 主动画使用 ease-out / move / spring；linear 仅用于匀速骨架微光。
- [x] Light/Dark 同步设计，Dark 非简单反色。

---

## 13. 交付与实现约束

1. `tokens.css` 是颜色、字号、间距、圆角、阴影、动效和布局值的唯一来源。
2. 前端不得在 `.vue`、TS、Tailwind arbitrary value 或 Element Plus 局部 CSS 中硬编码上述值。
3. `tailwind.config.ts` 的 `md=768 / lg=1024 / xl=1440` 不得修改；现有 Token 映射可直接使用。
4. 静态文案只进入 `frontend/src/locales/`；租户品牌文案只来自 `site_config_versions`；平台阈值与枚举来自 `sys_config`。
5. M3 新增 19 个工具编排语义 Token，已在 §4.1.1 与 `tokens.css` 同步登记；既有变量未改名、未改值、未改语义。Element Plus 继续使用 §7 映射，不新增硬编码主题色。
6. 设计系统就绪不等于实现验收。完成页面后必须由 @UI 做视觉一致性与动画走查，由 @测试复验四档、AA、键盘和 Reduced Motion。

---

## 14. M3 工具编排交互规范（仅终端用户侧）

### 14.1 范围、视觉层级与布局

本章覆盖 `ToolCallBar` 升级、高风险确认、`10005` 限流、M3 错误语义与相关动效。工具调用是回答生成的过程信息，视觉权重必须低于助手正文：默认使用 `13px` 辅助文字、1px 描边、无阴影、无大色块；只有 `awaiting_confirmation` 升级为可操作确认卡片。禁止新增任何管理页面、管理入口、图片/文件/语音/插件/计费入口。

`ui-ux-pro-max` 候选中的蓝色主色、橙色 CTA、玻璃拟态与 backdrop blur 均被舍弃；M3 延续 Refined Minimal 的中性灰阶、单一近黑主操作与克制警示色。`frontend-design` 裁决：差异化来自“工具过程退后、用户决定前置”，不靠 glow、渐变、重阴影或夸张动画。

```text
助手消息（max 720px）
├─ 助手正文片段
├─ [I18N] chat.toolCall.round（仅多轮时、每轮一次）
│  ├─ ToolCallBar：状态图标 + toolKey + [SYS] 状态文案 + 可选摘要开关
│  └─ ToolCallBar：同轮并列，4px 间距
├─ ToolConfirmCard（仅 awaiting_confirmation，替代该调用的普通状态条）
└─ 后续助手正文片段
```

- 工具节点按 SSE 到达顺序稳定渲染，`toolCallId` 作为唯一 key；状态更新必须原位更新，禁止重建整个消息节点。
- 连续工具调用按 `round` 分组；只有出现两个及以上不同轮次时，才在每轮首项上方显示一次 `[I18N] chat.toolCall.round`，不在每条状态条重复数字，不画彩色时间线，不播放 stagger。
- 同轮状态条间距 `--spacing-xs`，跨轮间距 `--spacing-md`；轮次标签使用 caption/secondary，不使用 Pill。
- `toolKey` 是当前契约唯一可展示的工具标识；以 mono 字体单行省略，不解析、不展示 MCP endpoint、凭据、完整参数或完整结果。

### 14.2 工具调用状态条

#### 14.2.1 基础结构

- 容器宽度 `100%`、最小高度 44px、水平内边距 12px、圆角 `--radius-sm`、背景 `--color-tool-surface`、描边 `--color-tool-border`、无阴影。
- 左侧 16px Lucide 线性图标；中部 `toolKey` + `[SYS] display.tool_status_labels`；右侧仅在有可展示摘要时出现 44×44px 展开按钮。
- 整行可作为展开按钮时使用原生 `button`；否则容器为 `role="group"`。不得用可点击 `div`。
- 展开按钮必须有 `[I18N]` 无障碍名称、`aria-expanded`、`aria-controls`；状态变化不改变 DOM 顺序。
- 状态标签来自 `sys_config: display.tool_status_labels`；前端 locales 仅作契约缺失兜底，不得在组件内联字面量。

#### 14.2.2 全状态视觉编码

| `status` | Lucide 图标 | 颜色 Token | 状态文案 | 默认摘要 | 可展开内容 |
|---|---|---|---|---|---|
| `pending` | `Clock3` | `--color-tool-status-neutral` | `[SYS]` | 折叠 | 有 `argsSummary` 时可展开参数摘要 |
| `awaiting_confirmation` | `ShieldAlert` | `--color-tool-status-warning` | `[SYS]` | 不使用普通条 | 渲染 §14.3 确认卡，参数摘要直接可见 |
| `running` | `LoaderCircle` | `--color-tool-status-running` | `[SYS]` | 折叠 | 有 `argsSummary` 时可展开参数摘要 |
| `succeeded` | `CheckCircle2` | `--color-tool-status-success` | `[SYS]` | 折叠 | 有 `resultSummary` 时可展开结果摘要 |
| `failed` | `CircleX` | `--color-tool-status-danger` | `[SYS]` | 折叠 | 有安全摘要时可展开；同时显示错误语义，不显示堆栈 |
| `timed_out` | `TimerOff` | `--color-tool-status-warning` | `[SYS]` | 折叠 | 有安全摘要时可展开；由 `errorCode` 区分确认超时与执行超时 |
| `cancelled` | `Ban` | `--color-tool-status-neutral` | `[SYS]` | 折叠 | 仅服务端提供非空安全摘要时可展开 |
| `denied` | `ShieldX` | `--color-tool-status-danger` | `[SYS]` | 折叠 | 仅服务端提供非空安全摘要时可展开 |

状态不得只靠颜色表达；图标、文字与状态值必须同时一致。`running` 的图标可匀速旋转，但读屏名称只播报状态文字，不播报动画。

#### 14.2.3 摘要折叠与截断

- 参数只显示服务端 `argsSummary`，结果只显示服务端 `resultSummary`；两者均已脱敏，前端不得回退到原始 args/result。
- 默认折叠，用户主动展开后以 footnote/1.6、可换行、`overflow-wrap:anywhere` 展示；结构化文本使用 mono 字体，但不做可执行代码块。
- `resultSummary` 的截断阈值来自 `sys_config: tool.result_summary_max_chars`，`argsSummary` 阈值来自 `sys_config: tool.args_summary_max_chars`；值由后端下发/执行，前端不得硬编码任何字符数。
- `truncated=true` 时在摘要末尾显示独立的 `[I18N] chat.toolCall.summaryTruncated`，不得显示硬编码阈值，不提供虚假的“查看全部”入口，也不得请求完整结果。
- 同一 `toolCallId` 更新状态时保留用户的展开意图；切换会话或消息卸载后无需跨会话记忆。
- 展开时内容瞬态参与布局、内部仅淡入；禁止动画 `height` / `max-height` / `grid-template-rows`。

### 14.3 高风险工具确认卡片

#### 14.3.1 最终形态

确认卡片内联在产生它的助手消息中，替代该调用的普通状态条；不是 Dialog、Popover 或 Toast。背景 `--color-tool-confirm-surface`，描边 `--color-tool-confirm-border`，圆角 `--radius-lg`，无常驻阴影，内边距 16px。风险等级标签的文字必须取 `sys_config: display.tool_risk_labels`，前端不得硬编码“低/中/高风险”。

```text
┌────────────────────────────────────────────────────┐
│ [ShieldAlert] [I18N] chat.toolConfirm.title   [SYS] │
│ toolKey（mono，单行省略）                           │
│ [I18N] chat.toolConfirm.argsLabel                   │
│ argsSummary（已脱敏，可换行，不展示完整参数）         │
│ ── countdown track / transform: scaleX(remaining) ─ │
│ [I18N] chat.toolConfirm.remaining {remaining}       │
│                         [拒绝语义] [允许执行语义]     │
└────────────────────────────────────────────────────┘
```

- 风险标签只使用 `--color-tool-risk-low/medium/high` 与对应 `-bg`，视觉为小型圆角标签 `--radius-xs`，不是全胶囊。
- 操作顺序按安全优先：DOM 与视觉均为“拒绝”在前、“允许执行”在后；两者至少 44px 高、间距 8px。允许使用 neutral primary；拒绝使用 secondary/danger outline，不用彩色 glow。
- 参数摘要完整展示服务端已截断、已脱敏的 `argsSummary`；不得提供查看原始参数的入口。
- 提交任一决定后，两个按钮原位锁定；被提交的按钮显示 loading，卡片等待 SSE 原位收敛。不得乐观展示 `succeeded`。
- `30055` 冲突时不重试、不弹 Toast；按服务端最新状态原位刷新卡片，并以 `[I18N] chat.toolConfirm.stateSynced` 作一次礼貌播报。

#### 14.3.2 倒计时与超时收敛

- 总时长只取前端配置 `sys_config: tool.confirm_wait_seconds`；默认值由后端配置管理，前端不得写死 `120` 或任何替代秒数。
- 视觉采用 2px 静态轨道 + 单色剩余进度，进度通过 `transform: scaleX(1→0)`、`transform-origin:left` 线性变化；旁边显示 tabular-nums 的 `{remaining}` 文本。禁止圆环、闪烁、红色脉冲和逐秒跳动动画，避免制造焦虑。
- 可视倒计时每秒更新文本，但 `aria-live` 不逐秒播报；卡片出现时只礼貌播报一次“需要确认”语义，临近结束只允许一次提醒，具体提醒点按总时长比例计算，不硬编码秒数。
- 计时必须由“配置时长 + 本地单调截止时间”计算剩余值，页面隐藏/恢复后重新计算，不得依赖简单 `remaining--`。本地到 0 时立即禁用允许/拒绝并显示 `[I18N] chat.toolConfirm.expiredSyncing`；最终终态只以 SSE `timed_out` 为准。
- 收到 `timed_out + errorCode=30050`：收敛为“确认等待超时”的普通状态条；收到 `timed_out + errorCode=30051/30056`：收敛为执行超时/结果待确认语义，二者不得混用文案。

#### 14.3.3 焦点、键盘与读屏

- 卡片为 `role="group"`，使用 `aria-labelledby` 关联标题、`aria-describedby` 关联风险与参数摘要；不要使用 `role="alertdialog"`，因为它不是阻塞模态。
- 卡片出现时**绝不移动焦点**，不得从 Composer、消息正文或用户当前阅读位置抢焦点；流式更新继续遵守 M1 纪律。
- 在消息列表已有的单一 `aria-live="polite"` 区域中节流播报一次 `[I18N] chat.toolConfirm.announcement`；心跳、倒计时逐秒变化、running 分片不得重复播报。
- Tab / Shift+Tab 按文档顺序进入“拒绝 → 允许执行 → 后续控件”；Enter/Space 只触发当前聚焦按钮，不设默认按钮、不自动允许。
- Esc 不代表拒绝，也不提交决定；当焦点在卡内时，仅将焦点移至 Composer 的“停止生成”按钮，卡片保持等待。该焦点移动来自用户主动按键，允许执行。
- 状态收敛后：若焦点仍在被移除按钮上，将焦点移到同一工具状态条的只读标题（临时 `tabindex=-1` 后聚焦）；若焦点不在卡内，则不移动焦点。

#### 14.3.4 停止生成期间

用户在等待确认时点击“停止生成”：确认卡立即进入客户端 `stopping` 视觉态，允许/拒绝按钮禁用，倒计时视觉冻结，控制区交叉淡化为 `[I18N] chat.toolConfirm.stopping`；不得把停止生成当作拒绝决定。收到 SSE `cancelled` 后原位收敛为 cancelled 状态条；停止失败则恢复服务端最新状态与剩余时间，不重置完整等待上限。

#### 14.3.5 移动端呈现裁决

确认卡在 `<768px` **不吸底、不另开 Bottom Sheet**，继续位于消息时间线内，避免与 sticky Composer/停止按钮形成双层遮挡，也避免复制一份可操作 DOM。用户不在底部时不强制滚动；复用“回到最新”按钮并增加 `[I18N] chat.toolConfirm.pendingBadge` 语义，用户主动返回。卡片宽度 100%，按钮正常 375px 下双列等宽；200% 缩放或可用宽度不足时自动改为单列，每项仍 ≥44px。

### 14.4 `10005` 限流提示

| 位置 | 视觉与行为 |
|---|---|
| Composer | 在现有 `composer-note` 区域显示 Clock 图标 + `[I18N] errors.rateLimited.description {remaining}`；使用 `--color-rate-limit-surface/border` 的克制内联说明，发送按钮禁用但草稿保留，停止按钮不受影响 |
| 消息流 | 若限流发生在已建立 SSE 后，在本次 assistant 尝试内显示 message-level 内联错误行；不创建空白助手气泡，不弹 Dialog/Toast |
| 倒计时结束 | 自动恢复发送能力并将说明交叉淡化为 `[I18N] errors.rateLimited.recovered`，短暂保留后移除；**不得自动发送或自动重试** |

- 剩余时间只取 HTTP `data.retryAfterSeconds` 或 SSE `error.retryAfterSeconds`；前端不得硬编码等待秒数或限流阈值。
- 倒计时同样使用截止时间重算，页面恢复时校正；显示 tabular-nums，不做进度环、不变红、不使用“系统故障”语气。
- 无障碍使用 `role="status" aria-live="polite"`，只播报首次等待与恢复，不逐秒播报。

### 14.5 M3 错误码展示层级与 locale key

原则：能在工具节点解释的错误不升级为全局提示；只有不存在对应 `toolCallId` 或整次生成无法继续时，才使用消息级错误块。下表所有文案仅定义语义和 key，字面量必须进入 `frontend/src/locales/`。

| code | 首选层级 | 视觉/操作规范 | locale key 前缀 |
|---:|---|---|---|
| `10005` | Composer 内联 + 必要时消息级 | 等待语义、保留草稿、倒计时后恢复，不自动重试 | `errors.rateLimited.*` |
| `30050` | 流内工具状态条 | `denied` 或确认 `timed_out`；说明“未执行/未获允许”，不得暴露未授权、未绑定或 SSRF 内部细分；整流终止且无工具节点时补消息级块 | `errors.toolDenied.*` |
| `30051` | 流内工具状态条 | `timed_out` + TimerOff；说明执行未在时限内完成，可按业务允许重新提问，不暗示已成功 | `errors.toolTimeout.*` |
| `30052` | 流内工具状态条 | `failed` + CircleX；说明外部工具暂不可用，不展示 endpoint、鉴权方式或协议细节；若无 `toolCallId` 则消息级 | `errors.mcpUnavailable.*` |
| `30053` | 流内工具状态条 | 参数不符合工具约束；禁止“重试同参”按钮，可提示调整提问 | `errors.toolArgsInvalid.*` |
| `30054` | 消息级错误块 | 因契约规定不再下发新 tool 帧，显示达到本次调用轮次上限，可重新提问；不显示配置数字 | `errors.toolLoopLimit.*` |
| `30055` | 确认卡局部状态 | 不弹窗、不 Toast、不重试；服务端既有决定为准并原位刷新，一次 polite 播报 | `errors.toolConfirmConflict.*` |
| `30056` | 流内工具状态条 + 必要时消息级 | warning；明确“结果待确认”，禁止自动重试，仅允许用户显式重新提问 | `errors.toolRetryBlocked.*` |
| `30057` | 流内工具状态条 | `failed`；说明工具未完成请求，显示安全 `resultSummary`（若有），不展示堆栈 | `errors.toolExecutionFailed.*` |
| `30060` | 消息级错误块 | 说明当前能力配置暂不可用；禁止白屏、NPE 文案和字段级内部详情；是否禁用 Composer 只服从服务端业务状态，不由展示层自行推断 | `errors.runtimeConfigInvalid.*` |

`30061` 属于无管理 UI 的 M2-min 运维接口，不在终端对话 M3 展示范围内；本章不得为其新增终端入口或全局提示。以上错误默认均**不使用全局 Toast**，避免同一 SSE 错误在状态条、消息块和 Toast 三处重复。

文案语气统一：先说用户可理解的结果，再说可执行下一步；短句、非责备、非技术化。不出现 endpoint、SSRF、JSON Schema、幂等、堆栈、内部地址、租户/Agent 配置字段名。建议子 key 统一为 `.title`、`.description`、`.action`；无操作时不创建空 action key。

### 14.6 四档响应式表现

| 验收宽度 | 工具状态条 | 确认卡 | 限流与消息错误 |
|---:|---|---|---|
| `375px` | 占满阅读列；toolKey 优先单行省略；状态文案不省略；展开按钮 44px | 时间线内 100%，不吸底；标题/风险标签可换行；操作双列等宽，空间不足或 200% 缩放改单列 | Composer note 可换行；不得挤压发送/停止 44px 命中区 |
| `768px` | 仍在 720px 阅读列内；摘要展开自然换行 | 内边距 16px，操作右对齐，卡片不超阅读列 | 消息级块与正文同宽，不出现浮层 |
| `1024px` | 固定侧栏之外的主阅读列居中；多轮标签不向左越界 | 最大宽度随 720px 阅读列，不拉伸到整个主栏 | Composer 说明与 720px 输入区对齐 |
| `1440px` | 阅读列仍为 720px，外侧留白不放大组件 | 同 1024；不增加装饰、阴影或并排信息栏 | 同 1024 |

四档均须验证：无页面级横向滚动；长 `toolKey`、长脱敏摘要与动态 `{remaining}` 不撑宽；fixed/sticky Composer 不遮挡卡片；safe-area 生效。

### 14.7 M3 动画标注表

| 元素 | 触发 | 属性与起止态 | 时长/缓动 | 可中断 | Reduced Motion |
|---|---|---|---|:---:|---|
| 状态条首次进入 | 新 `toolCallId` 到达且用户接近底部 | `opacity 0→1`；无位移 | `--duration-fast` / `--ease-enter` | 是 | ≤100ms 淡化 |
| 状态切换 | 同一调用的 `status` 更新 | 旧/新图标与文字原位 `opacity 1→0 / 0→1`；颜色瞬态切换 | `--duration-instant` / `--ease-enter` | 是 | ≤100ms 淡化 |
| running 图标 | `status=running` | `transform:rotate(0→360deg)` | 1000ms linear 循环；仅功能性 loading | 否；状态更新立即停止 | 静态 LoaderCircle + 状态文字 |
| 确认卡进入 | `awaiting_confirmation` 到达 | `translateY(6px→0) + opacity 0→1` | `--duration-normal` / `--ease-enter` | 是 | ≤100ms 仅淡化，无位移 |
| 确认卡提交收敛 | allow/deny 提交 | 操作区与 loading 状态原位 opacity 交叉；卡片不缩放 | `--duration-instant` / `--ease-enter` | 是 | ≤100ms 淡化 |
| 确认卡变状态条 | 收到 running/终态 | 卡片 `opacity 1→0`，DOM 端点切换后状态条 `opacity 0→1`；不动画高度 | 退 `--duration-instant`，入 `--duration-fast` / exit→enter | 是 | 总计 ≤100ms 交叉淡化 |
| 确认倒计时 | 卡片挂载至本地截止时间 | 进度层 `scaleX(1→0)`，origin left；数字直接更新不动画 | 总时长=`[SYS] tool.confirm_wait_seconds`，linear | 是；allow/deny/stop/终态立即取消 | 静态轨道 + 每秒文本更新，无空间动画 |
| 摘要展开 | 用户点击 chevron | 内容先参与布局，内部 `opacity 0→1`；chevron `rotate(0→180deg)` | `--duration-fast` / `--ease-enter` | 是 | ≤100ms 仅淡化；chevron 瞬态 |
| 摘要收起 | 用户点击收起 | 内容 `opacity 1→0` 后移除；不动画容器高度 | `--duration-instant` / `--ease-exit` | 是 | ≤100ms 淡化或直接移除 |
| 停止确认中工具 | 点击停止生成 | 决策区与 stopping 说明 opacity 交叉；倒计时 transform 停在当前值 | `--duration-instant` / `--ease-enter` | 是 | ≤100ms 淡化 |
| 限流恢复 | `{remaining}` 到 0 | 等待/恢复说明原位 opacity 交叉，发送按钮状态瞬态恢复 | `--duration-instant` / `--ease-enter` | 是 | ≤100ms 淡化 |

实现红线：仅动画 `transform` / `opacity`；不使用 `transition: all`；不动画 height/width/top/left/margin/padding；状态快速反转必须从当前视觉值重定向；`will-change` 仅动画期存在。键盘 Tab/Enter/Esc 路径不播放位移动画。

#### 14.7.1 ui-animation review-format 走查

| 之前/风险 | 之后/本规范 | 原因 |
|---|---|---|
| 每个 SSE 状态更新重放整条入场 | 同 `toolCallId` 原位 cross-fade | 避免流式噪音与 DOM 重建 |
| 倒计时逐秒缩短 width | 固定轨道 + `scaleX` | 不触发布局，保持合成层动画 |
| 确认使用阻塞 Dialog/移动吸底层 | 时间线内联卡片 | 保持工具与消息归属，不遮挡 Composer |
| 展开摘要动画 height | 布局瞬态 + 内部 opacity | 避免 layout/paint 与中断跳变 |
| 每秒 aria-live 播报 | 仅首次、比例临界提醒、恢复/终态播报 | 避免读屏轰炸 |
| Reduced Motion 仍位移/旋转 | ≤100ms cross-fade，Spinner 静态 | 符合前庭无障碍要求 |

规范级走查通过；实现后的真实界面、性能、读屏语义、四档响应式与 Reduced Motion 复验已于 V1.2 完成，最终证据与签署裁决见 §14.10。

### 14.8 M3 可访问性检查表

- [x] 8 个工具状态均以图标 + 文字 + 辅助颜色表达。
- [x] 风险等级标签文案来自 `[SYS] display.tool_risk_labels`，不只靠色彩。
- [x] 所有操作目标 ≥44×44px，焦点环 2px + 2px offset。
- [x] 展开按钮与所有 icon-only 控件具备 `[I18N]` 无障碍名称。
- [x] 确认卡不抢焦点；状态更新不重建消息、不改变 Tab 顺序。
- [x] Tab/Shift+Tab、Enter/Space、Esc 语义明确，未设置默认允许动作。
- [x] `role="group"`、`aria-labelledby`、`aria-describedby`、`aria-expanded`/`aria-controls` 关系明确。
- [x] live region 使用 polite 且节流；倒计时不逐秒播报。
- [x] 状态收敛时仅在焦点即将丢失的情况下执行焦点修复。
- [x] 摘要只显示后端脱敏值，不暴露完整参数、结果、endpoint、凭据或内部地址。
- [x] `prefers-reduced-motion` 降级为 ≤100ms 交叉淡化，无位移/缩放/旋转。
- [x] Light/Dark 状态文字沿用已通过 AA 的功能色；正文与必要说明 ≥4.5:1。
- [x] 375/768/1024/1440 均无横向滚动，200% 缩放下按钮可重排。
- [x] @UI 真实界面复验：确认卡出现不抢焦点；单一 polite live region 内容稳定且倒计时不逐秒进入；拒绝→允许顺序、Esc 仅移焦停止按钮、收敛焦点修复均由 DOM/键盘 E2E 证据覆盖。VoiceOver 语义模型以原生控件、正确 role/name/description 与单一 live region 通过复核。
- [x] @测试已复验键盘全路径、Reduced Motion、375/768/1024/1440 四档响应式、200% 文本缩放和状态机逐帧更新；54 条 Playwright E2E 全绿。

### 14.9 locale key 与实现约束

建议新增静态 key（仅命名契约，不在组件写死字面量）：

```text
chat.toolCall.round
chat.toolCall.expandSummary / collapseSummary / summaryTruncated
chat.toolCall.argsLabel / resultLabel
chat.toolConfirm.title / argsLabel / allow / deny
chat.toolConfirm.announcement / remaining / expiring
chat.toolConfirm.submitting / stopping / expiredSyncing / stateSynced / pendingBadge
errors.rateLimited.title / description / recovered
errors.toolDenied.* / toolTimeout.* / mcpUnavailable.* / toolArgsInvalid.*
errors.toolLoopLimit.* / toolConfirmConflict.* / toolRetryBlocked.*
errors.toolExecutionFailed.* / runtimeConfigInvalid.*
a11y.toolCallSummaryToggle / toolConfirmCard
```

`display.tool_status_labels`、`display.tool_risk_labels` 与 `tool.confirm_wait_seconds` 必须来自 `sys_config`；`retryAfterSeconds` 必须来自对应 HTTP/SSE 响应；摘要截断只服从后端配置与 `truncated`。前端不得硬编码状态文案、风险文案、倒计时总秒数、等待秒数、摘要字符阈值或调用轮次上限。

#### 14.9.1 给 @前端的实现约束清单

1. **必须取 Token**：所有工具状态/风险/确认/倒计时/限流颜色使用 §4.1.1 新变量；尺寸、间距、圆角、字号、时长、缓动继续使用既有 Token。`.vue`、TS、Tailwind arbitrary value 与 Element Plus 局部覆盖不得出现原始色值、圆角、阴影或动效毫秒数。
2. **必须走 locales / sys_config**：静态动作、错误与无障碍名称走 `frontend/src/locales/`；状态/风险标签与确认上限走 `sys_config`；响应剩余时间走 `retryAfterSeconds`。严禁在组件模板或脚本内联文案与魔法数字。
3. **不可省略的交互**：8 状态全覆盖；`timed_out` 按 `errorCode` 分义；摘要 `truncated` 提示；allow/deny 防重复提交；停止期间禁用决策；`30055` 原位刷新且不重试；限流结束只恢复、不自动发送。
4. **不可省略的无障碍**：卡片出现不抢焦点；单一 polite live region 节流；44px 命中区；2px + 2px focus；icon-only 有名称；焦点移除前修复；Reduced Motion ≤100ms 无位移。
5. **不可省略的渲染纪律**：`toolCallId` 稳定 key、状态原位更新、多轮只在轮次边界标一次、确认卡移动端不吸底、用户离底部不强制滚动。
6. **Element Plus**：仍为唯一组件库；全局 success/warning/danger/error 映射保持 §7 现状。M3 业务色只通过项目 Token 进入自定义组件，不添加硬编码 `--el-*` 覆盖，不引入第二套色值。

### 14.10 M3 最终视觉与交互复验（签署基线）

复验日期：2026-08-13。范围仅为一期终端用户侧对话与工具编排；未走查 `/admin`、`/platform`。

| 项目 | 结论 | 复验证据 |
|---|:---:|---|
| 8 种工具状态与双模式 | ✅ | 真实页面注入 8 态；`toolStatusRender.spec.ts` 22 条；Light/Dark Token 计算样式正常，图标 + 文字 + 辅助色不只靠颜色 |
| 高风险确认与倒计时 | ✅ | 卡片位于 `ToolCallTimeline` 内联 DOM；拒绝→允许；按钮 44px；2px `scaleX` 轨道；本地归零仅锁定，终态服从 SSE |
| 焦点、键盘与读屏语义 | ✅ | 出现时 activeElement 不在卡内；单一 polite live region 1.1s 内容稳定；E2E 覆盖 Tab、Esc、防重复提交；原生 button 与 aria 关系完整 |
| 动效与 Reduced Motion | ✅ | 无 `transition: all` / 布局属性动画；Reduced Motion 计算样式为纯淡化、无 transform、spinner 静止、倒计时无 transition；停止最坏 119ms |
| 对比度与风格 | ✅ | Light 状态文字最小 5.10:1，Dark 最小 7.08:1；无紫蓝渐变、霓虹、glow、emoji 图标或夸张胶囊 |
| 响应式与缩放 | ✅ | 375/768/1024/1440 的 `scrollWidth <= clientWidth`；确认卡/按钮/Composer 均在视口内；200% 文本缩放通过；D-001 未复现 |
| Token 与文案纪律 | ✅ | M3 组件无裸色值/尺寸/动效时长；Element Plus 仅映射 Token；`noHardcodeGuard.spec.ts` 覆盖 9 个 M3 Vue 组件及关键 Store/Utils |
| 走查修复 | ✅ | `RateLimitNote` 将可视逐秒倒计时与独立 `aria-live` 解耦；只在开始等待与恢复时更新播报，新增单测防逐秒读屏回归 |
| 质量门禁 | ✅ | `npm run typecheck` 0 错；Vitest 16 spec / 238 tests；`npm run build` 成功；既有 Playwright 54/54 基线保持 |

动画审查（`ui-animation/review-format`）：实现与 §14.7.1 的“之后”列一致，无新增问题。独立 Chromium 在 375×812、正常动效偏好、`running` 旋转态下采样 1.206s：145 帧、120.2fps、P95 10.3ms、>34ms 长帧 0；结合停止响应最坏 119ms、仅合成友好属性及可中断 CSS transition，M3 动效满足 60fps 签署条件。

签署裁决：M3 终端用户侧视觉、交互、双模式、四档响应式、读屏语义与 Reduced Motion 均满足 Refined Minimal 设计系统；@UI设计师 **✅ 签署**。

---

## 15. M3.1 每日对话额度展示（Composer 共用状态区）

### 15.1 范围、风格与信息层级

本章覆盖 PRD V1.4 `REQ-LMT-003`、`REQ-QUOTA-003/004` 与 ADR-020：首页和会话详情共用同一个 Composer 额度状态区，展示每日剩余、已用/总量、租户时区重置时间，并把短时 QPM 限流与当日额度用尽表达为两套明确不同的状态。组件延续 Refined Minimal：默认退居输入操作之后，状态越接近阻断越明确，但不使用进度环、大面积色块、彩色阴影或品牌色充当警告色。

**固定信息优先级：**

1. **主信息：剩余次数**。用户发送前最需要判断“还能否继续”，使用 footnote/Medium；仅在“仅剩一次”和“已用尽”时提高至 Semibold，不放大字号。
2. **次信息：已用/总量**。使用 caption/Regular + `--color-text-secondary`，表达权益基线，但不与剩余次数争夺焦点。
3. **三级信息：重置时间**。使用 caption/Regular + `--color-text-tertiary`；始终由 `resetsAt` 按响应中的 IANA `timezone` 格式化。不得按浏览器本地时区推算。

不使用环形/线性用量进度条。接口允许在途预占期间出现 `used + remaining < limit`；若把两者画成同一条进度，会制造“数值合计错误”的假象。三项信息以文字分层表达更准确，也更适合 375px 和 200% 缩放。

#### 15.1.1 展示等级计算（只影响视觉，不参与业务准入）

前端只根据权威快照计算展示等级，不本地推断能否发送，不写死平台默认 `50` 或任何租户限额：

```text
若 status=exhausted 或 remaining=0       → 日额度用尽
否则若服务端 10005 倒计时仍有效          → QPM 限流中
否则若 remaining=1                      → 额度偏低 · 仅剩一次子态
否则若 remaining < QUOTA_REMIND_THRESHOLD → 额度偏低
否则                                    → 额度充足（不展示提醒，整条状态轨不挂载）
```

- `QUOTA_REMIND_THRESHOLD` 是**展示层命名常量**，值 `10`；集中放入前端 UI 常量模块，不散落在组件，不进入 Store/API，也不改变后端准入。它表达“剩余次数不足 10 次时才展示额度状态轨提醒，充足时不挂载、释放底部对话空间”，不是业务额度阈值。
- **绝对阈值而非比例**：不同租户限额下“何时提醒用户”的心智一致（例如 limit=100 时 remaining=15 依然充足、不提醒），避免比例判定随 limit 漂移。
- `remaining=1` 是整数额度的最后一个正值，用于把文案改为“仅剩 1 次”；它不假设总量、不改变可发送性。
- 日额度用尽优先级高于 QPM；已用尽时不得启动或保留 QPM 秒级倒计时。QPM 阈值不在接口中，UI 不展示“每分钟限 N 次”或任何策略数字。

### 15.2 位置、稳定布局与共用关系

`QuotaStatusRail` 放在 Composer 输入面与页脚声明之间，复用现有 `composer-note` 区域；首页和会话详情必须引用同一组件及同一样式，不复制状态模板。

```text
┌──────────── Composer max 720px ────────────┐
│ ┌────────────────────────────────────────┐ │
│ │ 可编辑多行输入                     发送 │ │  输入面始终保持原位
│ └────────────────────────────────────────┘ │
│ ┌──────── QuotaStatusRail ───────────────┐ │
│ │ 剩余 / 已用·总量 / 重置，或阻断状态      │ │  共用稳定状态轨
│ └────────────────────────────────────────┘ │
└────────────────────────────────────────────┘
```

**不跳动规则：**

- 已登录且需要展示额度时，状态轨使用单格 CSS Grid；偏低、QPM、用尽以及加载/失败模板占同一 `grid-area`。非激活模板 `visibility:hidden + opacity:0 + pointer-events:none + aria-hidden=true`，仍参与网格最大尺寸计算；激活模板原位交叉淡化。禁止用状态切换反复 `display:none` 造成 Composer 高度改变。
- 发送/停止按钮继续占同一 44px 操作位；额度变化不得改变输入面的 x/y、发送按钮尺寸或消息列表底部预留。
- 375px 下状态轨按最坏两行信息预留；≥768px 为一行。200% 缩放时允许容器自然增高，不截断必要文案，但同一缩放级别内各状态仍由网格最大模板统一高度。
- **匿名用户不挂载整个额度状态轨**：不请求、不显示、不保留空白、不显示骨架。登录完成后再挂载并加载当前租户额度。
- **额度充足（remaining ≥ 10）同样不挂载状态轨**：与匿名同理由（无需要提醒的信息），整条状态轨隐藏以释放底部对话空间；由 `resolveQuotaDisplayState` 返回 `hidden` 实现，与匿名共用同一挂载开关。

样式层建议（仅示意，不要求本轮创建文件）：

```css
.quota-status-slot {
  display: grid;
  min-width: 0;
}

.quota-status-panel {
  grid-area: 1 / 1;
  min-width: 0;
  transition: opacity var(--duration-instant) var(--ease-enter);
}

.quota-status-panel:not(.is-active) {
  visibility: hidden;
  opacity: 0;
  pointer-events: none;
}

@media (prefers-reduced-motion: reduce) {
  .quota-status-panel {
    transition-duration: 0.01ms;
  }
}
```

### 15.3 四种核心状态视觉规格

| 状态 | 可见文案与语气 | 颜色与容器（仅现有 Token） | 图标 | 布局空间 | 动效 |
|---|---|---|---|---|---|
| **额度充足（remaining ≥ 10）** | 🔴 不展示：整条状态轨不挂载，释放底部对话空间 | 无 | 无 | 不占用布局 | 无 |
| **额度偏低** | 普通偏低：`今日剩余 {remaining} 次`；仅剩一次：`今日仅剩 1 次`。后接已用/总量与重置时间，不使用“马上用完”等焦虑措辞 | 图标与主信息使用 `--color-warning`；元信息仍为 secondary/tertiary。普通偏低透明；仅剩一次增加 `--color-bg-subtle` + `1px solid var(--color-warning)`，圆角 `--radius-sm` | 普通偏低 `Gauge`；仅剩一次 `TriangleAlert`，均为 Lucide 16px | 占用同一状态轨，不增高 Composer | normal→low/last 原位颜色与 opacity 过渡，`--duration-fast`；不脉冲、不闪烁、不缩放 |
| **QPM 限流中（10005）** | 主句：`发送太频繁，{remainingSeconds} 秒后可继续`；副信息保留当前每日 `剩余 {remaining} · 已用 {used}/{limit} · {resetLabel} 重置`。语气是“短暂等待、自动恢复”，不得出现 QPM 阈值或“每分钟限 N 次” | `--color-rate-limit-surface` 背景 + `--color-rate-limit-border` 描边；Clock 与倒计时数字用 `--color-warning`，其余文字用 primary/secondary；不用 danger | `Clock3` 16px；图标不旋转 | 完整替换状态轨的主内容，但轨道尺寸不变；发送临时禁用，输入仍可编辑 | 状态进入/恢复只做原位 opacity 交叉；可见数字每秒直接更新，不做跳动动画；倒计时结束显示一次“现在可以继续发送”后淡出，绝不自动重发 |
| **日额度用尽（30070）** | `今日额度已用完 · 已用 {used}/{limit}`；`{resetLabel}（租户时区）重置。如需提高额度，请联系租户管理员`。语气明确、持久、无秒级等待暗示 | **不使用 `--color-danger`，也不使用 `--color-brand`**。采用 `--color-bg-subtle`、`--color-border-strong`、`--color-text-primary/secondary`；主句 Semibold。发送按钮改中性禁用态 | `CalendarX2` 16px；与 QPM 的 Clock、偏低的 Gauge/TriangleAlert 形成形状差异 | 占用同一状态轨；移动端最多两行可见文本，不弹 Toast/Dialog，不移入浮层 | 进入时原位 opacity 交叉 `--duration-instant`；无摇晃、脉冲、进度动画；持续显示至权威快照恢复 |

状态不只靠颜色区分：额度充足不展示（无图标、无语气）；偏低有 Gauge/TriangleAlert + “剩余”；QPM 有 Clock + 秒级倒计时 + 自动恢复；日额度用尽有 CalendarX2 + 绝对重置时刻 + 持久禁用。这几套图标、措辞、恢复模型与容器强度不可互换。

#### 15.3.1 契约已有的补充状态

| 状态 | 视觉与行为 |
|---|---|
| `unlimited` | 显示“今日对话不限量”，neutral 文本，无数值总量、无重置倒计时；不伪造 limit |
| 已登录加载中 | 在同一状态轨显示静态中性“正在获取今日额度…”；可使用无微光的单行占位，禁止影响输入草稿 |
| 查询失败 | 使用 `CircleAlert` + “额度暂不可用，请重试”；不展示旧租户快照。重试是局部操作；是否可发送仍由后端最终准入，不由视觉层臆测 |
| 身份/租户切换 | 立即清空旧主体内容并进入加载态；不得短暂显示上一租户的额度或时区 |

### 15.4 租户时区的可见表达

1. 将 `resetsAt` 视为 UTC 绝对时间，将响应 `timezone` 原样作为 `Intl.DateTimeFormat` 的 `timeZone`；禁止省略 `timeZone` 后使用浏览器默认值。
2. `resetLabel` 的“今日/明日”关系也必须在同一 IANA timezone 下比较日期，不能先按浏览器本地日期判断再只格式化时分。
3. 显示实际格式化结果，不把 `00:00` 写死。DST 导致当地日首为 `01:00` 时必须如实显示 `01:00`。
4. ≥768px 可显示 IANA 名称，例如 `明日 00:00（Asia/Shanghai）重置`；375px 为避免挤占，视觉可写 `明日 00:00（租户时区）重置`，但其可访问描述必须包含实际 IANA 名称。

```ts
new Intl.DateTimeFormat(locale, {
  timeZone: quota.timezone,
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
}).format(new Date(quota.resetsAt))
```

此代码仅是格式化建议，不是本轮业务逻辑改动；`resetsAt` 与 `timezone` 的来源、刷新和状态机由 @前端按 API V1.2.5 实现。

### 15.5 日额度用尽时的输入与发送表达

- **输入框保持启用**：保留当前值、选区、复制、编辑和草稿持久化。用户可能要整理问题、复制未发送内容，或等待管理员调高限额；禁用输入会把“不能消耗生成资源”错误扩大成“不能管理自己的文本”。
- **只禁用发送路径**：发送按钮及 Enter 发送均不可触发请求；Shift+Enter、输入、复制和进行中回答的“停止”不受影响。按钮 DOM 尺寸与正常态一致。
- **发送按钮禁用视觉**：`--color-fill-subtle` 背景、`--color-border` 描边、`--color-text-disabled` 图标/文字；移除 brand 填充、hover、active scale 与阴影，光标采用 default/not-allowed。禁用不使用红色，避免被误读为“危险操作”。
- **反馈方式裁决：持久 inline 为主，不使用 Tooltip/Toast**。禁用按钮 Tooltip 在触屏不可发现，原生 disabled 元素也不稳定触发 hover/focus；Toast 会与状态轨重复且很快消失。输入框和发送按钮通过 `aria-describedby` 关联日额度说明。已知用尽后再次按 Enter 不重复 Toast、不重复播报；若因陈旧快照首次收到 `30070`，状态轨立即切换并礼貌播报一次。
- 焦点保持在用户当前控件，不自动移到状态轨、不弹模态、不选中文本。按钮禁用后也不强制把焦点送回输入框；仅保持稳定 DOM，让浏览器按自然 Tab 顺序继续。

### 15.6 四档响应式规格

| 验收宽度 | 排布 | 文案密度与空间纪律 |
|---:|---|---|
| `375px` | 状态轨位于输入面下方并占满 Composer；内部两行：第一行主状态，第二行已用/总量 + 重置。QPM 第一行倒计时、第二行每日额度；用尽第一行结果与已用、第二行重置与联系管理员 | **不折叠为无语义纯数字**，不隐藏已用/总量或重置；可省略重复的“今日”但保留“剩余/已用/重置”。`min-width:0`、允许自然换行，数字使用 tabular-nums；发送 44px 不被挤压 |
| `768px` | 状态轨一行两区：左侧剩余/状态，右侧已用/总量 + 重置；QPM/用尽可横跨整行 | Composer 仍 max 720px；元信息右对齐但 DOM 顺序仍先主后次；空间不足自动回两行，不横向滚动 |
| `1024px` | 固定侧栏外的 720px Composer 居中；状态轨与输入左右边缘对齐 | 不移到侧栏/顶栏，不新增悬浮 Badge；首页与会话详情完全同构 |
| `1440px` | 与 1024px 相同，外侧留白增加；状态轨不随主舞台拉宽 | 保持 720px 阅读/输入节奏，不增加额外图表、说明卡或装饰 |

```text
375px
┌──────────────────────────────────┐
│ [可编辑输入................][发送]│
├──────────────────────────────────┤
│ 今日剩余 3 次                    │
│ 已用 12/15 · 明日 00:00 重置      │
└──────────────────────────────────┘

≥768px
┌──────────────────────────────────────────────────────┐
│ [可编辑输入..................................][发送] │
├──────────────────────────────────────────────────────┤
│ 今日剩余 3 次       已用 12/15 · 明日 00:00（租户时区）│
└──────────────────────────────────────────────────────┘
```

示意数字仅用于排版，不是默认额度或阈值。四档均不得出现页面级横向滚动；200% 缩放允许状态轨增高，不得裁切必要文本或覆盖输入/发送。

### 15.7 动态状态、焦点与读屏

#### 15.7.1 aria-live 裁决

使用一个与可见倒计时解耦的隐藏播报节点：`role="status" aria-live="polite" aria-atomic="true"`。**不使用 assertive**：额度变化不涉及人身安全、数据丢失或必须打断当前朗读的紧急事件；assertive 会截断用户正在听的助手回答或输入回显。发送阻断在视觉上即时生效，读屏在当前语句结束后播报即可。

| 事件 | 播报策略 |
|---|---|
| 初次加载成功 | 默认不主动播报；组件通过可访问名称和 `aria-describedby` 可被读取 |
| 普通额度更新 | 权威 `remaining` 变化后 polite 一次“今日剩余 N 次”；相同 `status + remaining` 去重，不按 `asOf` 重复播报 |
| 进入偏低/仅剩一次 | polite 一次，包含剩余与重置时刻；不因颜色变化额外播报 |
| QPM 开始 | polite 一次“发送太频繁，约 N 秒后可继续” |
| QPM 每秒 tick | **不进入 live region**；可见数字容器 `aria-hidden=true`，避免每秒刷屏 |
| QPM 恢复 | polite 一次“现在可以继续发送”；不自动聚焦、不自动发送 |
| 日额度用尽 | 状态首次变为 exhausted 时 polite 一次完整结果与重置时刻；后续轮询同态去重 |
| 配额恢复/管理员上调 | polite 一次“今日额度已恢复，剩余 N 次”；发送按钮恢复但不自动聚焦 |

#### 15.7.2 焦点与色盲友好

- 状态变化只更新原位文本和属性，不插入模态、不抢焦点、不把页面滚到底。额度用尽时焦点可继续留在输入框，用户仍可编辑草稿。
- 发送按钮的可访问名称保持“发送”；禁用原因由 `aria-describedby` 指向状态轨，不把长原因塞入每次按钮名称。
- 颜色始终与图标、明确文字和恢复时刻共同出现。灰度模式下仍可凭 Gauge/TriangleAlert、Clock3、CalendarX2 及“秒后可继续/明日重置”区分。
- 关键文字对比度沿用第 5 节已通过 AA 的 text/warning Token；disabled 色不承载唯一原因，必要原因始终由 primary/secondary 文本显示。

### 15.8 gift 红色品牌与额度警示色冲突裁决

**最终裁决：品牌色、警告色、用尽色三域彻底分离；每日额度用尽不使用红色。**

| 语义域 | gift | redbook | 额度组件规则 |
|---|---|---|---|
| 品牌/可操作 | `--color-brand` = 租户 `#E5484D` | `--color-brand` = 租户 `#2E6BE6` | 只用于可用的主操作与焦点，不用于额度状态 |
| 偏低/QPM | 两租户都使用 `--color-warning` | 两租户都使用 `--color-warning` | Amber + Gauge/Clock + 文字；不随租户换色 |
| 日额度用尽 | 两租户都使用 neutral Token | 两租户都使用 neutral Token | `bg-subtle + border-strong + text-primary` + CalendarX2 + 绝对重置时间；不用 brand/danger |
| 真正错误/危险 | `--color-danger` | `--color-danger` | 仅留给系统错误、失败与破坏性操作；必须配 CircleX/文字，不用于“权益已消耗完” |

这样 gift 的红色只表示“品牌与可操作”，不会同时表示“额度危险”；用尽通过持久中性阻断面、日历失效图标、禁用发送和重置时刻获得最高明确度，而不是靠更红。redbook 也使用同一语义结构，因此两个租户不会出现页面间风格漂移。

**同时记录一个样式实现风险（本轮不改代码）：** `#E5484D` 配白字对比度约 `3.91:1`，不满足普通文字 AA；配现有 `--color-text-primary`（`#16181C`）约 `4.54:1`，可通过。`#2E6BE6` 配白字约 `4.81:1`。因此租户品牌覆盖必须让 `--color-text-on-brand` 成为对比度感知的配对 Token：gift 取深色文字，redbook 取白色文字；组件仍只消费 Token，不写死租户色。当前 `brandTheme.ts` 的“只对白色验收”会拒绝 gift 主色，需由 @前端在不触碰业务状态机/API 的前提下单独调整品牌样式工具并补对比度测试。本额度组件本身不使用 brand 填充，故不依赖该调整才能保持警示语义正确。

### 15.9 动画标注与 Reduced Motion

| 动画 | 属性 | 时长/缓动 | 可中断 | Reduced Motion |
|---|---|---|:---:|---|
| 状态轨 normal/low/QPM/exhausted 切换 | 同一网格位置旧/新面板 `opacity` 交叉；不动画高度 | `--duration-instant` / `--ease-enter` | 是，快速变化从当前 opacity 重定向 | `0.01ms` 直接切换或 ≤100ms 淡化 |
| 普通额度数值更新 | 数字 opacity 交叉；tabular-nums，容器宽度由布局吸收 | `--duration-instant` / `--ease-enter` | 是 | 直接更新 |
| QPM 可见秒数 | 文本直接替换，无 scale/translate/脉冲 | 无逐秒动画 | 是 | 同默认 |
| QPM 恢复 | 等待文案→恢复文案原位 opacity 交叉 | `--duration-instant` / `--ease-enter` | 是 | 直接切换 |
| 发送可用→禁用 | background-color / border-color / color | `--duration-instant` / `--ease-enter` | 是 | 直接切换 |

禁止动画 `height/width/top/left/margin/padding`，禁止 `transition:all`，禁止闪烁、抖动、红色脉冲、倒计时圆环。所有过渡只解释状态替换，不营造紧迫感。

#### 15.9.1 ui-animation review-format 走查

| 之前/风险 | 之后/本规范 | 原因 |
|---|---|---|
| 状态文案长短导致 Composer 上下跳 | 同网格叠放模板，由最大模板稳定占位 | 保持输入与发送位置稳定 |
| QPM 与日额度共用红色错误条 | QPM=Clock+秒；用尽=CalendarX2+重置时刻+中性阻断面 | 恢复条件和语义完全不同，且解决 gift 红色冲突 |
| 倒计时每秒淡入/缩放 | 数字直接替换，live region 不含 tick | 降低视觉与读屏噪声 |
| 用尽后弹 Toast/Tooltip | 持久 inline + aria-describedby | 可发现、可持续、触屏与键盘一致 |
| Reduced Motion 仍交叉位移 | 直接切换/极短淡化，无位移缩放 | 遵守前庭无障碍要求 |

规范级动画走查通过；真实 60fps、快速状态反转与 Reduced Motion 仍须实现后由 @前端 + @测试复验，本版不提前签署实现质量。

### 15.10 locale key 与实现分工

建议新增静态 key（命名契约，字面量进入 `frontend/src/locales/zh-CN.ts`，不得内联进组件）：

```text
chat.quota.remaining / usedTotal / resetAt / tenantTimezone
chat.quota.low / lastOne / exhausted / contactAdmin
chat.quota.unlimited / loading / unavailable / retry / restored
chat.quota.a11ySummary / a11yRateLimited / a11yRateLimitRecovered
errors.rateLimited.description / recovered（沿用既有 key，按本章语气校准）
```

**本轮 UI 交付边界：**

- 本次只更新 `docs/design-system.md`；现有 `tokens.css` 已具备全部所需的 brand/warning/text/background/border/rate-limit/disabled/motion Token，**不新增、不改名、不改值**。
- @UI 不修改 `frontend/src/stores/`、`frontend/src/api/`、额度状态机、接口消费、倒计时计算或发送准入。
- @前端负责组件 DOM、状态优先级、权威快照刷新、IANA timezone 格式化、locales、`aria-live` 去重与样式落地；建议把额度样式放在额度组件自身 `<style scoped>` 或前端已认领的组件样式文件，避免双方编辑同一文件。
- `brandTheme.ts` 的 gift 对比度配对问题是独立品牌样式工具任务，不与额度 Store/API 改动混在同一提交。

### 15.11 实现后验收清单

- [ ] 首页与会话详情使用同一额度组件；匿名态无请求、无组件、无占位、无骨架。
- [ ] 额度充足（remaining ≥ 10）不挂载状态轨；偏低/仅剩一次只由 `remaining` 与绝对阈值 `QUOTA_REMIND_THRESHOLD`（10）判定，不出现代码默认 50 或比例推断；QPM UI 不出现任何阈值。
- [ ] `10005` 只出现秒级等待与恢复；`30070` 只出现租户时区重置与持久禁用，两者无共用提示皮肤。
- [ ] `resetsAt` 在 `Asia/Shanghai`、`America/New_York` 及 DST 边界均按响应 timezone 格式化；改变浏览器时区不改变同租户显示。
- [ ] 用尽后输入、选区、复制和草稿仍可用；发送与 Enter 禁用；停止生成不受影响。
- [ ] 状态快速切换时输入面和发送按钮 bounding box 不变；无 `transition:all` 或布局属性动画。
- [ ] 375/768/1024/1440 与 200% 缩放无页面横向滚动、遮挡、截断；发送命中区始终 ≥44px。
- [ ] VoiceOver 只在额度真实变化、QPM 开始/恢复、用尽/恢复时 polite 播报；倒计时不逐秒播报；状态变化不抢焦点。
- [ ] gift/redbook Light/Dark 截图中额度语义一致；warning 不跟随品牌色；用尽不用红；灰度下仍可凭图标与文字区分。
- [ ] `prefers-reduced-motion: reduce` 下无位移、缩放、脉冲或循环动画。

---

## 16. 版本记录

| 版本 | 日期 | 变更 |
|---|---|---|
| V1.3 | 2026-08-18 | 对齐 PRD V1.4、Architecture V1.4.5 ADR-020 与 API V1.2.5；新增每日额度信息层级、四态视觉、稳定 Composer 状态轨、IANA timezone、发送禁用、四档响应式、aria-live、Reduced Motion 与 gift/redbook 品牌冲突裁决；未修改业务逻辑与 Token 文件 |
| V1.2 | 2026-08-13 | 完成 M3 真实界面最终视觉与交互复验；补充 8 态、Light/Dark、焦点/读屏语义、Reduced Motion、对比度、四档响应式、200% 缩放及质量门禁证据；修复限流可视倒计时潜在逐秒读屏；@UI设计师签署 M3 |
| V1.1 | 2026-08-13 | 对齐 PRD V1.2 / API V1.1 与 M2 全量 Deferred；新增 M3 终端工具状态条、高风险确认卡、限流、错误码、动效、无障碍、四档响应式和 19 个 Light/Dark 语义 Token；明确 locales/sys_config 边界与前端实现约束 |
| V1.0 | 2026-08-12 | 确立 Refined Minimal；完成 Light/Dark Token、Element Plus 映射、组件、动画、四档响应式、页面级规范与可访问性检查 |
