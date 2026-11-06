import type { ModelResource } from '../../types/domain'
import { Button, Field, Select } from '../../components/ui'

export function FilterPanel({
  open, models, modelFilter, onModelFilterChange, resultCount, onReset, hasFilters,
}: {
  open: boolean
  models: ModelResource[]
  modelFilter: string
  onModelFilterChange: (value: string) => void
  resultCount: number
  onReset: () => void
  hasFilters: boolean
}) {
  if (!open) return null

  return (
    <div className="card mt-3 flex flex-wrap items-end gap-4 p-4 animate-slide-up">
      <Field label="默认模型" htmlFor="filter-model" className="max-w-[260px]">
        <Select
          id="filter-model"
          value={modelFilter}
          onChange={(event) => onModelFilterChange(event.target.value)}
          options={[
            { value: 'all', label: '全部模型' },
            ...models.map((model) => ({ value: model.id, label: `${model.name} · ${model.vendor}` })),
          ]}
        />
      </Field>
      <p className="pb-2 text-xs text-ink-muted">当前共 <span className="font-semibold text-brand">{resultCount}</span> 个结果</p>
      <Button variant="ghost" size="sm" disabled={!hasFilters} onClick={onReset} className="mb-1 ml-auto">重置筛选</Button>
    </div>
  )
}
