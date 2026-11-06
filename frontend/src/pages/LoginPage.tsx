import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { ArrowRight, Check, Eye, EyeOff, KeyRound, LockKeyhole, Sparkles } from 'lucide-react'
import { asError } from '../lib/errors'
import { api } from '../lib/api'
import { Button, Input, toast } from '../components/ui'

const VALUES = ['可插拔能力', '全链路调试', '标准化发布']

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [value, setValue] = useState('')
  const [visible, setVisible] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (loading) return
    setLoading(true)
    setError('')
    api.auth
      .login(value)
      .then(() => {
        toast.success('欢迎回来，已进入工作台')
        const from = (location.state as { from?: string } | null)?.from ?? '/'
        navigate(from, { replace: true })
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 登录失败：', message)
        setError(message)
      })
      .finally(() => setLoading(false))
  }

  return (
    <div className="flex min-h-screen bg-white">
      <div
        className="relative hidden w-[47%] shrink-0 flex-col justify-between overflow-hidden bg-[#10121A] px-12 py-10 lg:flex"
        style={{
          backgroundImage:
            'radial-gradient(circle at 18% 42%, rgba(109,93,246,0.20), transparent 46%), radial-gradient(circle at 82% 80%, rgba(79,209,181,0.10), transparent 40%), linear-gradient(rgba(255,255,255,0.035) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.035) 1px, transparent 1px)',
          backgroundSize: '100% 100%, 100% 100%, 28px 28px, 28px 28px',
        }}
      >
        <div className="relative flex items-center gap-2.5">
          <span className="flex h-9 w-9 items-center justify-center rounded-[11px] bg-gradient-to-br from-brand-light to-brand-hover shadow-soft">
            <Sparkles className="h-4 w-4 text-white" aria-hidden />
          </span>
          <div className="leading-tight">
            <p className="text-lg font-semibold tracking-tight text-white">albedo</p>
            <p className="text-[9px] tracking-[0.16em] text-white/40">AGENT WORKSPACE</p>
          </div>
        </div>

        <div className="relative max-w-[440px]">
          <span className="inline-flex items-center gap-2 rounded-pill border border-white/10 bg-white/5 px-3 py-1.5 text-2xs text-white/70">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />Personal AI capability workspace
          </span>
          <h1 className="mt-7 text-[38px] font-semibold leading-[1.3] tracking-tight text-white">
            构建、调试并发布
            <br />
            <span className="bg-gradient-to-r from-brand-light via-[#A78BFA] to-[#4FD1C5] bg-clip-text text-transparent">属于你的智能体</span>
          </h1>
          <p className="mt-5 max-w-[380px] text-sm leading-[1.9] text-white/55">
            统一连接模型、MCP 与 Skills，以低代码方式完成能力编排，并通过标准 API 将智能体带到任何地方。
          </p>
          <div className="mt-9 flex flex-wrap gap-2.5">
            {VALUES.map((item) => (
              <span key={item} className="inline-flex items-center gap-2 rounded-field border border-white/10 bg-white/[0.04] px-3 py-2 text-xs text-white/70">
                <Check className="h-3 w-3 text-emerald-400" aria-hidden />{item}
              </span>
            ))}
          </div>
        </div>

        <p className="relative text-[10px] tracking-[0.18em] text-white/30">ALBEDO LABS</p>
      </div>

      <div className="flex flex-1 items-center justify-center bg-[#F7F8FA] px-6 py-12">
        <div className="w-full max-w-[360px] rounded-card bg-white p-8 shadow-pop">
          <div className="mb-7 flex items-center gap-3 lg:hidden">
            <span className="flex h-8 w-8 items-center justify-center rounded-[9px] bg-gradient-to-br from-brand-light to-brand-hover">
              <Sparkles className="h-4 w-4 text-white" aria-hidden />
            </span>
            <span className="text-md font-semibold text-ink">albedo</span>
          </div>
          <h2 className="text-xl font-semibold text-ink">欢迎回来</h2>
          <p className="mt-1.5 text-sm text-ink-muted">使用工作区访问 Key 登录 albedo</p>

          <form className="mt-7" onSubmit={submit}>
            <label htmlFor="access-key" className="field-label">访问 Key</label>
            <Input
              id="access-key"
              name="accessKey"
              type={visible ? 'text' : 'password'}
              autoComplete="current-password"
              value={value}
              invalid={Boolean(error)}
              leading={<KeyRound className="h-3.5 w-3.5" aria-hidden />}
              trailing={
                <button
                  type="button"
                  aria-label={visible ? '隐藏访问 Key' : '显示访问 Key'}
                  onClick={() => setVisible((current) => !current)}
                  className="rounded p-1.5 text-ink-faint transition-colors hover:text-ink"
                >
                  {visible ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                </button>
              }
              onChange={(event) => { setValue(event.target.value); setError('') }}
            />
            {error && <p className="field-error" role="alert">{error}</p>}

            <Button type="submit" block size="lg" loading={loading} className="mt-5" icon={<ArrowRight className="h-3.5 w-3.5" />}>
              进入工作台
            </Button>
          </form>

          <div className="mt-6 flex items-start gap-2 rounded-field bg-brand-faint px-3 py-2.5 text-2xs leading-relaxed text-brand">
            <LockKeyhole className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>个人工作区无需注册账号，访问 Key 由服务端校验，不会保存在浏览器。</span>
          </div>
        </div>
      </div>
    </div>
  )
}
