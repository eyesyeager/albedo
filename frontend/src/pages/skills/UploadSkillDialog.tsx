import { useEffect, useRef, useState, type DragEvent } from 'react'
import { FileArchive, UploadCloud } from 'lucide-react'
import type { Skill } from '../../types/domain'
import { cn } from '../../lib/cn'
import { formatFileSize } from '../../lib/format'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { useWorkspace } from '../../store/workspace'
import { Button, Field, Input, Modal, Textarea, toast } from '../../components/ui'

interface PickedFile {
  name: string
  size: number
  file: File
}

export function UploadSkillDialog({ open, onClose, targetSkill }: { open: boolean; onClose: () => void; targetSkill?: Skill | null }) {
  const maxSize = useWorkspace((state) => state.systemConfig.limits.skillMaxBytes || 100 * 1024 * 1024)
  const maxLabel = `${Math.round(maxSize / 1024 / 1024)} MB`
  const fileRef = useRef<HTMLInputElement>(null)
  const [name, setName] = useState('')
  const [instructions, setInstructions] = useState('')
  const [file, setFile] = useState<PickedFile | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [error, setError] = useState('')
  const [uploading, setUploading] = useState(false)

  useEffect(() => {
    if (!open) return
    setName(targetSkill?.name ?? '')
    setInstructions(targetSkill?.instructions ?? '')
    setFile(null)
    setError('')
    setDragOver(false)
  }, [open, targetSkill?.id])

  const pick = (picked: File) => {
    if (!picked.name.toLowerCase().endsWith('.zip')) {
      setError('仅支持 .zip 格式的 Skill 包')
      setFile(null)
      return
    }
    if (picked.size > maxSize) {
      setError(`Skill 包不能超过 ${maxLabel}`)
      setFile(null)
      return
    }
    setFile({ name: picked.name, size: picked.size, file: picked })
    setError('')
  }

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    setDragOver(false)
    const dropped = event.dataTransfer.files[0]
    if (dropped) pick(dropped)
  }

  const submit = () => {
    if (!file || uploading) return
    setUploading(true)
    const draft = { name, instructions, fileName: file.name, sizeBytes: file.size, file: file.file }
    const request = targetSkill
      ? api.skills.createVersion(targetSkill.id, draft)
      : api.skills.create(draft)
    request
      .then((skill) => {
        toast.success(targetSkill
          ? `「${skill.name}」新版本 ${skill.version} 上传并校验通过`
          : `「${skill.name}」上传并校验通过，版本 ${skill.version}`)
        onClose()
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] Skill 上传失败：', message)
        setError(message)
      })
      .finally(() => setUploading(false))
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={targetSkill ? `上传新版本 · ${targetSkill.name}` : '上传 Skill'}
      description={targetSkill ? '新版本不覆盖历史包，已有 Agent 默认继续使用原版本' : '上传打包好的能力插件，校验通过后进入资源池'}
      width="md"
      dirty={Boolean(file || (targetSkill ? instructions !== targetSkill.instructions : name || instructions))}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button loading={uploading} disabled={!name.trim() || !file || !instructions.trim()} onClick={submit}>{targetSkill ? '上传新版本' : '上传并校验'}</Button>
        </>
      }
    >
      <div className="space-y-4">
        {targetSkill && (
          <div className="rounded-field bg-brand-faint px-3 py-2.5 text-2xs leading-relaxed text-brand">
            当前版本 {targetSkill.version} → 新版本将自动递增，资源 ID {targetSkill.id} 与已有 Agent 挂载关系保持不变。
          </div>
        )}
        <Field label="展示名称" htmlFor="skill-name" required hint={targetSkill ? '新版本沿用原资源名称，不可修改' : undefined}>
          <Input id="skill-name" value={name} disabled={Boolean(targetSkill)} placeholder="例如：合同审查助手" onChange={(event) => { setName(event.target.value); setError('') }} />
        </Field>

        <Field label="Skill 包" required hint={`仅支持 .zip 格式，最大 ${maxLabel}；前端校验扩展名与体积，后端再执行结构与安全校验`}>
          <input
            ref={fileRef}
            type="file"
            accept=".zip"
            className="hidden"
            aria-label="选择 Skill 包"
            onChange={(event) => {
              const selected = event.target.files?.[0]
              if (selected) pick(selected)
            }}
          />
          <div
            onDragOver={(event) => { event.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
            className={cn(
              'flex flex-col items-center justify-center rounded-field border-2 border-dashed px-4 py-6 text-center transition-colors',
              dragOver ? 'border-brand bg-brand-faint' : 'border-line bg-canvas/50',
            )}
          >
            {file ? (
              <>
                <FileArchive className="h-6 w-6 text-brand" aria-hidden />
                <p className="mt-2 text-xs font-medium text-ink">{file.name}</p>
                <p className="mt-0.5 text-2xs text-ink-faint">{formatFileSize(file.size)} · 已通过前端校验</p>
                <button type="button" onClick={() => fileRef.current?.click()} className="mt-2 text-2xs font-medium text-brand hover:text-brand-hover">重新选择文件</button>
              </>
            ) : (
              <>
                <UploadCloud className="h-6 w-6 text-ink-faint" aria-hidden />
                <p className="mt-2 text-xs text-ink-muted">拖拽 .zip 文件到此处，或</p>
                <button type="button" onClick={() => fileRef.current?.click()} className="mt-1 text-xs font-medium text-brand hover:text-brand-hover">点击选择文件</button>
              </>
            )}
          </div>
        </Field>

        <Field label="使用说明" htmlFor="skill-instructions" required hint="必填：功能介绍、使用步骤与系统要求">
          <Textarea id="skill-instructions" rows={4} value={instructions} placeholder={'功能：……\n使用步骤：……\n系统要求：Python 3.10+'} onChange={(event) => { setInstructions(event.target.value); setError('') }} />
        </Field>

        {error && <p className="field-error" role="alert">{error}</p>}
        <p className="text-2xs leading-relaxed text-ink-faint">
          平台会校验解压后总大小、文件数量与目录深度，禁止路径穿越和危险符号链接；新版本不覆盖历史包。
        </p>
      </div>
    </Modal>
  )
}
