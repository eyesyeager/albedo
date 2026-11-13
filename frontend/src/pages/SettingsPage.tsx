import { useEffect, useState } from 'react'
import { Database, Globe2, KeyRound, ShieldCheck, SlidersHorizontal } from 'lucide-react'
import type { WorkspaceSettings } from '../types/domain'
import { maskedAccessKey } from '../lib/mask'
import { api } from '../lib/api'
import { asError } from '../lib/errors'
import { useWorkspace } from '../store/workspace'
import { Badge, Button, ConfirmDialog, CopyButton, Field, Input, Select, Switch, toast } from '../components/ui'
import { PageHeader, SectionCard } from '../components/common/PageHeader'
import { ChangeKeyDialog } from './settings/ChangeKeyDialog'

const EDITABLE_LIMITS: { key: string; label: string }[] = [
  { key: 'maxToolCallsMax', label: '工具调用次数上限' },
  { key: 'maxRunSecondsMax', label: '单次运行时长上限（秒）' },
  { key: 'maxOutputTokensCeiling', label: '最大输出 Tokens 上限' },
  { key: 'logPageSize', label: '日志每页条数' },
  { key: 'searchMaxResults', label: '全局搜索结果条数' },
  { key: 'exportExpireDays', label: '导出文件有效期（天）' },
  { key: 'skillMaxBytes', label: 'Skill 包大小上限（字节）' },
  { key: 'chatTimeoutMs', label: '测试会话超时（毫秒）' },
]

export function SettingsPage() {
  const data = useWorkspace()
  const settings = data.settings
  const config = data.systemConfig
  const [form, setForm] = useState<WorkspaceSettings>(settings)
  const [changeKeyOpen, setChangeKeyOpen] = useState(false)
  // 每个动作独立 pending，避免任一请求结束就提前解锁全部按钮造成重复提交。
  const [pending, setPending] = useState<Record<string, boolean>>({})
  const [limitValues, setLimitValues] = useState<Record<string, string>>({})
  const [allowPrivateMcp, setAllowPrivateMcp] = useState(false)
  const [retentionConfirmOpen, setRetentionConfirmOpen] = useState(false)

  const isBusy = (key: string): boolean => Boolean(pending[key])

  const runAction = (key: string, action: () => Promise<unknown>, onSuccess?: () => void): void => {
    if (pending[key]) return
    setPending((current) => ({ ...current, [key]: true }))
    action()
      .then(() => onSuccess?.())
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setPending((current) => ({ ...current, [key]: false })))
  }

  useEffect(() => {
    setForm(settings)
  }, [settings])

  useEffect(() => {
    setLimitValues(Object.fromEntries(EDITABLE_LIMITS.map((item) => [item.key, String(config.limits[item.key] ?? '')])))
    setAllowPrivateMcp(Boolean((config.limits as Record<string, unknown>).mcpAllowPrivateUrls))
  }, [config])

  const saveGeneral = () => runAction('general', () =>
    api.settings
      .update({
        workspaceName: form.workspaceName,
        language: form.language,
        timezone: form.timezone,
        domain: form.domain,
        telemetry: form.telemetry,
        autoArchive: form.autoArchive,
      })
      .then(() => toast.success('常规设置已保存到服务端数据库')),
  )

  const applyRetention = () => runAction('retention', () =>
    api.settings
      .update({ retentionDays: form.retentionDays })
      .then(() => {
        toast.success(`日志保留策略已更新为 ${form.retentionDays} 天，过期日志已归档后清理`)
        setRetentionConfirmOpen(false)
      }),
  )

  const saveConfig = () => {
    const parsed: Record<string, number> = {}
    for (const item of EDITABLE_LIMITS) {
      const value = Number(limitValues[item.key])
      if (!Number.isFinite(value) || value <= 0) {
        toast.error(`${item.label} 必须是正数`)
        return
      }
      parsed[item.key] = value
    }
    runAction('config', () =>
      api.settings
        .updateConfigs({ limits: { ...config.limits, ...parsed } })
        .then(() => toast.success('系统配置已保存并即时生效')),
    )
  }

  return (
    <div className="mx-auto max-w-[1100px]">
      <PageHeader title="系统设置" description="管理工作区信息、系统配置、访问安全与日志存储策略" />

      <div className="space-y-4">
        <SectionCard title="常规设置" description="用于管理界面与发布文档展示的基础信息">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="工作区名称" htmlFor="setting-name">
              <Input id="setting-name" value={form.workspaceName} onChange={(event) => setForm({ ...form, workspaceName: event.target.value })} />
            </Field>
            <Field label="默认语言" htmlFor="setting-language">
              <Select
                id="setting-language"
                value={form.language}
                onChange={(event) => setForm({ ...form, language: event.target.value as WorkspaceSettings['language'] })}
                options={config.options.languages.map((item) => ({ value: item.value, label: item.label }))}
              />
            </Field>
            <Field label="默认时区" htmlFor="setting-timezone" hint="影响日志时间、统计日期与系统变量">
              <Select
                id="setting-timezone"
                value={form.timezone}
                onChange={(event) => setForm({ ...form, timezone: event.target.value })}
                options={config.options.timezones.map((item) => ({ value: item.value, label: item.label }))}
              />
            </Field>
            <Field label="平台域名" htmlFor="setting-domain" hint="用于生成 Agent 与模型发布地址，变更后新地址同步更新">
              <Input id="setting-domain" className="font-mono text-xs" value={form.domain} onChange={(event) => setForm({ ...form, domain: event.target.value })} />
            </Field>
          </div>

          <div className="mt-5 divide-y divide-line border-t border-line">
            <div className="flex items-center justify-between gap-4 py-3.5">
              <div>
                <p className="text-xs font-medium text-ink">产品使用数据</p>
                <p className="mt-1 text-2xs text-ink-faint">发送匿名统计，不包含 Prompt、密钥或用户内容</p>
              </div>
              <Switch label="产品使用数据" checked={form.telemetry} onChange={(checked) => setForm({ ...form, telemetry: checked })} />
            </div>
            <div className="flex items-center justify-between gap-4 py-3.5">
              <div>
                <p className="text-xs font-medium text-ink">自动归档历史会话</p>
                <p className="mt-1 text-2xs text-ink-faint">超过日志保留周期后自动归档，再按策略清理在线索引</p>
              </div>
              <Switch label="自动归档历史会话" checked={form.autoArchive} onChange={(checked) => setForm({ ...form, autoArchive: checked })} />
            </div>
          </div>
          <Button className="mt-4" loading={isBusy('general')} onClick={saveGeneral}>保存更改</Button>
        </SectionCard>

        <SectionCard
          title="系统配置"
          description={`枚举、默认值与限额存放在服务端数据库（${config.platform.name} ${config.platform.version}），修改后对所有页面即时生效`}
        >
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {EDITABLE_LIMITS.map((item) => (
              <Field key={item.key} label={item.label} htmlFor={`limit-${item.key}`}>
                <Input
                  id={`limit-${item.key}`}
                  type="number"
                  value={limitValues[item.key] ?? ''}
                  onChange={(event) => setLimitValues({ ...limitValues, [item.key]: event.target.value })}
                />
              </Field>
            ))}
          </div>
          <div className="mt-4 flex items-center justify-between gap-4 rounded-field border border-line px-3 py-3">
            <div>
              <p className="text-xs font-medium text-ink">允许接入本地 / 内网 MCP</p>
              <p className="mt-1 text-2xs text-ink-faint">部署期安全开关（SSRF 防护）：仅由服务端环境变量控制，管理面不可修改</p>
            </div>
            <Badge tone="neutral">{allowPrivateMcp ? '部署期已开启' : '默认关闭'}</Badge>
          </div>
          <div className="mt-4 flex items-start gap-2 rounded-field bg-brand-faint px-3 py-2.5 text-2xs leading-relaxed text-brand">
            <SlidersHorizontal className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>枚举选项、新建资源默认值与运行时参数同样来自系统配置，可通过 GET / PATCH /api/v1/settings/configs 读取与调整。</span>
          </div>
          <Button variant="outline" className="mt-4" loading={isBusy('config')} onClick={saveConfig}>保存系统配置</Button>
        </SectionCard>

        <SectionCard title="访问与安全" description="管理界面访问 Key 与平台凭证安全状态">
          <div className="flex flex-wrap items-center gap-3 rounded-field border border-line bg-canvas/40 p-3">
            <KeyRound className="h-4 w-4 text-brand" aria-hidden />
            <div className="min-w-0 flex-1">
              <p className="text-2xs text-ink-faint">管理界面访问 Key</p>
              <code className="mt-0.5 block truncate font-mono text-xs text-ink">{maskedAccessKey(settings.accessKeySuffix)}</code>
            </div>
            <Badge tone="ok" dot>有效</Badge>
            <CopyButton value={settings.accessKeySuffix} label="复制后缀" message="Key 后缀已复制" />
            <Button variant="outline" size="sm" onClick={() => setChangeKeyOpen(true)}>修改 Key</Button>
          </div>

          <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
            {[
              { label: '上游模型密钥', count: data.models.length, note: '全部已加密', icon: <Globe2 className="h-3.5 w-3.5" /> },
              { label: '发布 API Key', count: data.publications.length, note: '相互隔离', icon: <KeyRound className="h-3.5 w-3.5" /> },
              { label: 'MCP 鉴权配置', count: data.mcps.filter((mcp) => Boolean(mcp.headers)).length, note: 'Header 已脱敏', icon: <ShieldCheck className="h-3.5 w-3.5" /> },
            ].map((item) => (
              <div key={item.label} className="rounded-field border border-line p-3">
                <p className="flex items-center gap-1.5 text-2xs text-ink-faint">{item.icon}{item.label}</p>
                <p className="mt-2 text-xl font-semibold text-ink">{item.count} <span className="text-xs font-normal text-ink-faint">个</span></p>
                <p className="mt-1 text-2xs text-ok">{item.note}</p>
              </div>
            ))}
          </div>
          <p className="mt-3 text-2xs leading-relaxed text-ink-faint">不同凭证不得复用同一存储字段或通过界面互相读取；完整密钥仅在创建或重置时展示一次。</p>
        </SectionCard>

        <SectionCard title="日志与存储" description="保留周期对全部日志统一生效，清理任务按日志时间执行">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="日志保留周期" htmlFor="setting-retention" hint={`平台推荐 ${config.platform.defaultRetentionDays} 天；修改后需点击「保存策略」，清理前会先脱敏归档`}>
              <Select
                id="setting-retention"
                value={String(form.retentionDays)}
                onChange={(event) => {
                  const value = Number(event.target.value)
                  setForm((current) => ({ ...current, retentionDays: value }))
                }}
                options={config.options.retentionDays.map((day) => ({ value: String(day), label: `${day} 天${day === config.platform.defaultRetentionDays ? '（推荐）' : ''}` }))}
              />
            </Field>
            <div className="rounded-field border border-line bg-canvas/40 p-3">
              <p className="flex items-center gap-1.5 text-2xs text-ink-faint"><Database className="h-3.5 w-3.5" />日志存储</p>
              <p className="mt-2 text-sm font-medium text-ink">当前快照已加载 {data.logs.length} 条调用日志</p>
              <p className="mt-1 text-2xs text-ink-muted">按 {form.retentionDays} 天保留策略，过期日志由服务端清理任务删除；导出文件在 {config.limits.exportExpireDays || 7} 天后失效</p>
            </div>
          </div>
          <Button
            variant="outline"
            className="mt-4"
            loading={isBusy('retention')}
            disabled={isBusy('retention') || form.retentionDays === settings.retentionDays}
            onClick={() => setRetentionConfirmOpen(true)}
          >保存策略</Button>
        </SectionCard>
      </div>

      <ConfirmDialog
        open={retentionConfirmOpen}
        onClose={() => setRetentionConfirmOpen(false)}
        onConfirm={applyRetention}
        title="确认调整日志保留周期"
        description={`将保留周期调整为 ${form.retentionDays} 天，服务端会立即清理早于该期限的日志。`}
        confirmText="保存并清理"
        danger
        loading={isBusy('retention')}
        impacts={['过期日志将从列表、搜索与统计中移除', '清理前会先脱敏归档到服务端 var/archive/ 目录', '操作不可撤销，可通过归档文件恢复']}
      />
      <ChangeKeyDialog open={changeKeyOpen} onClose={() => setChangeKeyOpen(false)} />
    </div>
  )
}
