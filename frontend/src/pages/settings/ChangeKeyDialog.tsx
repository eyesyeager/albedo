import { useEffect, useState } from 'react'
import { Eye, EyeOff, KeyRound, ShieldCheck } from 'lucide-react'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { useWorkspace } from '../../store/workspace'
import { Button, Field, Input, Modal, toast } from '../../components/ui'

export function ChangeKeyDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const minLength = useWorkspace((state) => state.systemConfig.limits.accessKeyMinLength || 12)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [visible, setVisible] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!open) return
    setCurrent('')
    setNext('')
    setConfirm('')
    setVisible(false)
    setError('')
  }, [open])

  const nextError = next.length > 0 && next.length < minLength ? `新 Key 至少需要 ${minLength} 个字符` : undefined
  const confirmError = confirm.length > 0 && next !== confirm ? '两次输入的新 Key 不一致' : undefined
  const disabled = next.length < minLength || confirm.length < minLength || Boolean(confirmError) || Boolean(nextError)

  const submit = () => {
    if (disabled || busy) return
    setBusy(true)
    api.settings
      .changeAccessKey({ current, next, confirm })
      .then(() => {
        toast.success('访问 Key 已更新，旧 Key 立即失效')
        onClose()
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 修改访问 Key 失败：', message)
        setError(message)
      })
      .finally(() => setBusy(false))
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="修改访问 Key"
      description="修改后旧 Key 立即失效，请妥善保管新 Key"
      width="md"
      dirty={Boolean(next || confirm)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button loading={busy} disabled={disabled} onClick={submit}>确认修改</Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="当前访问 Key" htmlFor="key-current">
          <Input id="key-current" type="password" value={current} leading={<KeyRound className="h-3.5 w-3.5" />} onChange={(event) => { setCurrent(event.target.value); setError('') }} />
        </Field>
        <Field label="新访问 Key" htmlFor="key-next" required error={nextError} hint={`至少 ${minLength} 个字符，建议混合字母、数字与符号`}>
          <Input
            id="key-next"
            type={visible ? 'text' : 'password'}
            value={next}
            invalid={Boolean(nextError)}
            trailing={
              <button type="button" aria-label={visible ? '隐藏新 Key' : '显示新 Key'} onClick={() => setVisible((value) => !value)} className="rounded p-1.5 text-ink-faint hover:text-ink">
                {visible ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
              </button>
            }
            onChange={(event) => { setNext(event.target.value); setError('') }}
          />
        </Field>
        <Field label="确认新 Key" htmlFor="key-confirm" required error={confirmError}>
          <Input id="key-confirm" type={visible ? 'text' : 'password'} value={confirm} invalid={Boolean(confirmError)} onChange={(event) => { setConfirm(event.target.value); setError('') }} />
        </Field>
        {error && <p className="field-error" role="alert">{error}</p>}
        <div className="flex items-start gap-2 rounded-field bg-brand-faint px-3 py-2.5 text-2xs leading-relaxed text-brand">
          <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>修改后当前会话仍有效，其他会话将在下次验证时失效。访问 Key 不会进入日志、埋点或导出文件。</span>
        </div>
      </div>
    </Modal>
  )
}
