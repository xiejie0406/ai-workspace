import { ref, watch, onActivated, onDeactivated, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
export function useProjectId(){const route=useRoute();const resolve=()=>String(route.params.projectId||route.params.id||route.query.projectId||'');const id=ref(resolve());let active=true;watch(()=>route.fullPath,()=>{if(active)id.value=resolve()},{flush:'post'});onActivated(()=>{active=true;id.value=resolve()});onDeactivated(()=>{active=false});onBeforeUnmount(()=>{active=false});return id}
