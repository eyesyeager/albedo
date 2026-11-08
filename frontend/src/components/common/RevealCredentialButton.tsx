import { useRef, useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { asError } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import type { CredentialView } from '../../types/domain'
import { Button, CopyButton, Modal, toast } from '../ui'

export interface RevealCredentialButtonProps {
  reveal: () => Promise<CredentialView>
  title?: string
  label?: string
  link?: boolean
}

/** 按需从服务端读取完整凭证，明文只保留在当前弹窗的局部状态中，关闭即清空。 */
export function RevealCredentialButton({
  reveal,
  title = '查看访问凭证',
  label = '查看完整密钥',
  link = false,
}: RevealCredentialButtonProps) {
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [visible, setVisible] = useState(false)
  const [credential, setCredential] = useState<CredentialView | null>(null)
  const generation = useRef(0)

  const close = () => {
    generation.current += 1
    setOpen(false)
    setVisible(false)
    setCredential(null)
  }

  const show = () => {
    generation.current += 1
    const current = generation.current
    setOpen(true)
    setVisible(false)
    setCredential(null)
    setLoading(true)
    reveal()
      .then((value) => {
        if (generation.current === current) setCredential(value)
      })
      .catch((reason: unknown) => {
        if (generation.current !== current) return
        setOpen(false)
        setCredential(null)
        toast.error(asError(reason).message)
      })
      .finally(() => {
        if (generation.current === current) setLoading(false)
      })
  }

  return (
    <>
      {link ? (
        <button
          type="button"
          onClick={show}
          className="text-2xs font-medium text-brand transition-colors hover:text-brand-hover"
        >
          {label} →
        </button>
      ) : (
        <Button variant="outline" size="sm" icon={<Eye className="h-3 w-3" />} onClick={show}>{label}</Button>
      )}
      <Modal
        open={open}
        onClose={close}
        title={title}
        description="默认仅展示脱敏值，此处按需解密；查看操作记入系统操作日志，日志不记录明文"
        width="md"
        footer={<Button onClick={close}>关闭</Button>}
      >
        {credential ? (
          <div className="space-y-3">
            <code className="block break-all rounded-field bg-canvas px-3 py-3 font-mono text-xs text-ink">
              {visible ? credential.fullKey : `••••••••••••${credential.credentialSuffix}`}
            </code>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                icon={visible ? <EyeOff className="h-3 w-3" /> : <Eye className="h-3 w-3" />}
                onClick={() => setVisible((current) => !current)}
              >
                {visible ? '隐藏' : '显示'}
              </Button>
              <CopyButton value={credential.fullKey} label="复制完整密钥" message="完整密钥已复制" />
            </div>
            {credential.rotatedAt && (
              <p className="text-2xs text-ink-faint">最近轮换于 {formatDateTime(credential.rotatedAt)}</p>
            )}
            <p className="text-2xs leading-relaxed text-ink-muted">请勿在公开场合展示或转发该密钥；泄露时请使用「重置密钥」或「轮换密钥」立即作废旧值。</p>
          </div>
        ) : (
          <p className="py-6 text-center text-xs text-ink-muted">{loading ? '正在读取凭证…' : '凭证暂不可用'}</p>
        )}
      </Modal>
    </>
  )
}
