import { useState } from 'react'

import { RATIO_LABELS, type GenerateInput, type Ratio } from '@/api/runs'
import type { Asset } from '@/api/assets'
import { codePointLength } from '@/lib/text'

const RATIOS = Object.keys(RATIO_LABELS) as Ratio[]
const COUNTS = [1, 2, 3, 4, 5, 6]

export default function GenerateForm({
  onSubmit,
  pending,
  defaultPrompt = '',
  disabled = false,
  referenceAssets = [],
  onPromptChange,
}: {
  onSubmit: (input: GenerateInput) => void
  pending: boolean
  defaultPrompt?: string
  disabled?: boolean
  referenceAssets?: Asset[]
  onPromptChange?: (prompt: string) => void
}) {
  const [prompt, setPrompt] = useState(defaultPrompt)
  const [ratio, setRatio] = useState<Ratio>('1:1')
  const [count, setCount] = useState(4)
  const [negative, setNegative] = useState('')
  const [advanced, setAdvanced] = useState(false)
  const [seed, setSeed] = useState('')
  const [references, setReferences] = useState<string[]>([])

  const validSeed = seed === '' || (/^\d+$/.test(seed) && Number(seed) <= 2147483647)
  const promptLength = codePointLength(prompt.trim())
  const canSubmit = promptLength > 0 && promptLength <= 1500 && !pending && !disabled && validSeed

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        if (!canSubmit) return
        onSubmit({
          prompt: prompt.trim(),
          ratio,
          count,
          negative_prompt: negative.trim() || undefined,
          seed: seed === '' ? undefined : Number(seed),
          reference_asset_ids: references.length ? references : undefined,
        })
      }}
      className="border-line bg-paper shadow-panel rounded-panel border p-2"
    >
      <textarea
        rows={3}
        value={prompt}
        onChange={(event) => {
          setPrompt(event.target.value)
          onPromptChange?.(event.target.value)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault()
            event.currentTarget.form?.requestSubmit()
          }
        }}
        placeholder="描述你想要的画面，例如：白色陶瓷马克杯放在浅木色桌面，晨光从左侧照入。回车生成，Shift+Enter 换行"
        aria-label="画面描述"
        className="text-ink placeholder:text-faint w-full resize-none bg-transparent px-4 pt-3 pb-1 text-[15px] leading-relaxed outline-none"
      />

      <div className="flex flex-wrap items-center gap-2 px-2 pb-1">
        <Segmented
          label="比例"
          options={RATIOS.map((value) => ({ value, label: value }))}
          value={ratio}
          onChange={setRatio}
        />
        <Segmented
          label="数量"
          options={COUNTS.map((value) => ({ value, label: String(value) }))}
          value={count}
          onChange={setCount}
        />

        <button
          type="button"
          onClick={() => setAdvanced((open) => !open)}
          className="text-muted hover:text-ink rounded-control px-2 py-1.5 text-xs font-medium transition-colors"
        >
          {advanced ? '收起高级设置' : '高级设置'}
        </button>

        <button
          type="submit"
          disabled={!canSubmit}
          className="bg-ink hover:bg-dark rounded-control ml-auto px-4 py-2 text-sm font-medium text-white transition-all duration-150 active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40 disabled:active:scale-100"
        >
          {pending ? '提交中…' : '生成'}
        </button>
      </div>
      <p className={`${promptLength > 1500 ? 'text-danger' : 'text-faint'} px-3 pb-2 text-[11px]`} role={promptLength > 1500 ? 'alert' : undefined}>
        {disabled ? '当前账号没有生成权限' : pending ? '任务已提交，稍后会跳到候选页' : `回车生成 · Shift+Enter 换行 · ${promptLength}/1500 字${promptLength > 1500 ? ' · 超出上限，请缩短提示词' : ''}`}
      </p>

      {advanced && (
        <div className="border-line animate-fade-in mt-1 border-t px-4 py-3">
          <input
            value={negative}
            onChange={(event) => setNegative(event.target.value)}
            placeholder="不希望出现的内容，例如：文字、水印、多余的手"
            aria-label="排除项"
            className="text-ink placeholder:text-faint w-full bg-transparent text-sm outline-none"
          />
          <label className="text-muted mt-4 block text-xs">随机种子（可选，0–2147483647）
            <input type="number" min={0} max={2147483647} value={seed} onChange={(event) => setSeed(event.target.value)} className="border-line mt-1 w-full rounded-control border p-2 text-sm" />
          </label>
          {!validSeed && <p className="text-danger mt-1 text-xs">随机种子必须是范围内的整数。</p>}
          <p className="text-muted mt-4 text-xs">参考图片（最多 3 张，仅限本人素材）</p>
          {referenceAssets.length ? <div className="mt-2 flex flex-wrap gap-2">{referenceAssets.map((asset) => {
            const selected = references.includes(asset.id)
            return <button key={asset.id} type="button" aria-pressed={selected} disabled={!selected && references.length >= 3} onClick={() => setReferences((current) => selected ? current.filter((id) => id !== asset.id) : [...current, asset.id])} className={`size-16 overflow-hidden rounded-[8px] border-2 disabled:opacity-30 ${selected ? 'border-brand' : 'border-line'}`} title={`参考图 ${asset.width}×${asset.height}`}>
              <img src={asset.url} alt={`参考图 ${asset.id}`} className="size-full object-cover" />
            </button>
          })}</div> : <p className="text-faint mt-2 text-xs">先上传图片，再选择参考图。</p>}
        </div>
      )}
    </form>
  )
}

function Segmented<T extends string | number>({
  label,
  options,
  value,
  onChange,
}: {
  label: string
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return (
    <div className="border-line rounded-control flex items-center gap-0.5 border p-0.5">
      <span className="text-faint px-1.5 text-xs">{label}</span>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          onClick={() => onChange(option.value)}
          aria-pressed={option.value === value}
          className={`rounded-[6px] px-2 py-1 text-xs font-medium transition-all duration-150 active:scale-95 ${
            option.value === value ? 'bg-ink text-white' : 'text-muted hover:bg-soft hover:text-ink'
          }`}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
