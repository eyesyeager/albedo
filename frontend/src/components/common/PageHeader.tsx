import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'
import { Badge } from '../ui'

export function PageHeader({ title, description, actions, tag, className }: { title: string; description?: string; actions?: ReactNode; tag?: string; className?: string }) {
  return (
    <div className={cn('mb-5 flex flex-wrap items-start justify-between gap-3', className)}>
      <div>
        <div className="flex items-center gap-2.5">
          <h1 className="text-2xl font-semibold tracking-tight text-ink">{title}</h1>
          {tag && <Badge tone="neutral">{tag}</Badge>}
        </div>
        {description && <p className="mt-1.5 text-sm text-ink-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

export function SectionCard({ title, description, actions, children, className, bodyClassName }: { title?: string; description?: string; actions?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string }) {
  return (
    <section className={cn('card', className)}>
      {(title || actions) && (
        <div className="flex flex-wrap items-start justify-between gap-3 border-b border-line px-5 py-3.5">
          <div>
            {title && <h2 className="section-title">{title}</h2>}
            {description && <p className="mt-1 text-xs text-ink-muted">{description}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </div>
      )}
      <div className={cn('p-5', bodyClassName)}>{children}</div>
    </section>
  )
}
