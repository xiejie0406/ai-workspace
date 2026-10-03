import { useNavigate } from '@/host/router'

import CapabilityShowcase from '@/components/landing/CapabilityShowcase'
import DeliverySteps from '@/components/landing/DeliverySteps'
import LandingFooter from '@/components/landing/LandingFooter'
import LandingHero from '@/components/landing/LandingHero'
import StartBanner from '@/components/landing/StartBanner'
import { usePromptDraft } from '@/lib/promptDraft'

export default function LandingPage() {
  const navigate = useNavigate()
  const { read: readPromptDraft, save: savePromptDraft } = usePromptDraft()
  const entry = { label: '进入创作', to: '/create' }

  const start = (prompt: string) => {
    savePromptDraft(prompt)
    navigate(entry.to)
  }

  return (
    <div className="flex min-h-full flex-col">
      <p className="text-muted mx-auto mt-4 max-w-5xl px-6 text-sm">创作指南 · 下方界面是能力说明，实际可用能力以管理员能力清单为准。</p>

      <main className="flex-1">
        <LandingHero
          initialPrompt={readPromptDraft()}
          submitLabel={entry.label}
          onStart={start}
        />
        <CapabilityShowcase />
        <DeliverySteps />
        <StartBanner label={entry.label} to={entry.to} />
      </main>

      <LandingFooter />
    </div>
  )
}
