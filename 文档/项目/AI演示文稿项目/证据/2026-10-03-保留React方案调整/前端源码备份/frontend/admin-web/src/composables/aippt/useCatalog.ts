import { api } from '@/api/aippt'
import { registerCatalog } from '@/views/aippt/render/design'
let loading:Promise<void>|null=null
export function ensureCatalog(){return loading ||= api.catalog().then(registerCatalog).catch(error=>{loading=null;throw error})}
