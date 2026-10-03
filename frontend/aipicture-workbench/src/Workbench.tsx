import { useHost } from '@/host/HostContext'
import BatchPage from '@/pages/BatchPage'
import CandidatesPage from '@/pages/CandidatesPage'
import CreatePage from '@/pages/CreatePage'
import EditorPage from '@/pages/EditorPage'
import LandingPage from '@/pages/LandingPage'
import MarketingPage from '@/pages/MarketingPage'
import ExportPage from '@/pages/ExportPage'

const permissions = {
  create: 'aipicture:asset:list', editor: 'aipicture:session:query', candidates: 'aipicture:asset:query',
  marketing: 'aipicture:session:query', batch: 'aipicture:batch:list', guide: 'aipicture:asset:list', exports: 'aipicture:export:download',
} as const

export default function Workbench() {
  const host = useHost()
  if (!host.hasPermission(permissions[host.page])) return <div className="p-8 text-sm" role="alert">当前账号没有访问此页面的权限。</div>
  switch (host.page) {
    case 'create': return <CreatePage />
    case 'editor': return <EditorPage />
    case 'candidates': return <CandidatesPage />
    case 'marketing': return <MarketingPage />
    case 'batch': return <BatchPage />
    case 'guide': return <LandingPage />
    case 'exports': return <ExportPage />
  }
}
