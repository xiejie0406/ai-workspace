import { useRef, useState } from 'react'

import { ACCEPTED_TYPES } from '@/api/assets'
import { checkFile } from '@/hooks/useAssets'

type Props = {
  onFile: (file: File) => void
  disabled?: boolean
  hint?: string
  multiple?: boolean
}

export default function ImageDropzone({ onFile, disabled, hint, multiple }: Props) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const acceptFiles = (files: File[]) => {
    if (disabled) return
    const problems: string[] = []
    for (const file of multiple ? files : files.slice(0, 1)) {
      const problem = checkFile(file)
      if (problem) problems.push(multiple ? `${file.name}：${problem}` : problem)
      else onFile(file)
    }
    // 同轮合法文件不能清掉此前非法文件的提示。
    setError(problems.length ? problems.join('；') : null)
  }

  return (
    <div>
      <button
        type="button"
        disabled={disabled}
        onClick={() => inputRef.current?.click()}
        onDragOver={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault()
          setDragging(false)
          const files = Array.from(event.dataTransfer.files)
          acceptFiles(files)
        }}
        className={`flex w-full flex-col items-center gap-1.5 rounded-[18px] border border-dashed px-6 py-10 transition-colors disabled:opacity-50 ${
          dragging ? 'border-brand bg-brand-soft' : 'border-line-strong bg-paper hover:border-brand'
        }`}
      >
        <span className="text-ink text-sm font-medium">
          {disabled ? '上传中…' : multiple ? '拖入多张图片，或点击选择' : '拖入图片，或点击选择'}
        </span>
        <span className="text-faint text-xs">{hint ?? 'JPG / PNG / WebP，单张不超过 20 MB'}</span>
      </button>

      <input
        ref={inputRef}
        type="file"
        accept={ACCEPTED_TYPES.join(',')}
        multiple={multiple}
        hidden
        onChange={(event) => {
          const files = Array.from(event.target.files ?? [])
          acceptFiles(files)
          event.target.value = ''
        }}
      />

      {error && <p className="text-danger mt-2 text-sm">{error}</p>}
    </div>
  )
}
