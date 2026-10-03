<template>
  <div :class="['fashion-materials', { 'app-container': !product }]">
    <el-alert v-if="!readOnly" :title="product ? `当前商品：${product.name} / ${product.skuCode}。上传图片默认关联此规格，人工确认用途与许可后生效。` : '上传有权使用的图片，明确规格SKU或款号＋颜色并人工确认后关联商品。系统不直连外部电商。'" type="info" :closable="false" show-icon class="mb20" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="mb20" />
    <el-table v-if="product" :data="currentImages" class="mb20" empty-text="此商品尚无已入库图片">
      <el-table-column label="图片" width="120" fixed="left"><template #default="{row}"><el-image v-if="imageUrls[row.imageId]" :src="imageUrls[row.imageId]" :preview-src-list="[imageUrls[row.imageId]]" :preview-teleported="true" fit="contain" class="thumbnail" /><span v-else-if="row.allowInternal===false">无内部查看许可</span><span v-else-if="imageLoading[row.imageId]">图片加载中</span><el-button v-else link @click="loadImage(row.imageId)">{{imageErrors[row.imageId]||'查看图片'}}</el-button></template></el-table-column>
      <el-table-column label="商品名称" min-width="160"><template #default>{{product.name}}</template></el-table-column>
      <el-table-column label="主SKU" min-width="140"><template #default>{{valueText(product.attributes?.mainSkuCode)}}</template></el-table-column>
      <el-table-column label="规格SKU" min-width="170"><template #default>{{product.skuCode}}</template></el-table-column>
      <el-table-column label="款号" min-width="140"><template #default>{{product.styleCode}}</template></el-table-column>
      <el-table-column label="颜色" min-width="140"><template #default>{{product.colorName}}（{{product.colorCode}}）</template></el-table-column>
      <el-table-column prop="originalFilename" label="文件" min-width="140" /><el-table-column label="用途" width="90"><template #default="{row}">{{row.usage==='main'?'主图':'细节图'}}</template></el-table-column>
      <el-table-column label="来源" width="120"><template #default="{row}">{{row.sourceType==='manual'?'人工上传':row.sourceType==='jd_plugin'?'京东采集':valueText(row.sourceType)}}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{row}">{{reviewLabel(row.status)}}</template></el-table-column>
      <el-table-column label="复核状态" width="120"><template #default="{row}">{{Number(row.confirmedBy)>0&&row.confirmedAt?'已复核':'待复核'}}</template></el-table-column>
      <el-table-column label="提案许可" width="120"><template #default="{row}">{{proposalPermissionLabel(row)}}</template></el-table-column>
      <el-table-column label="许可" min-width="220"><template #default="{row}">{{permissionLabel(row)}}</template></el-table-column>
      <el-table-column label="尺寸" width="100"><template #default="{row}">{{row.width}}×{{row.height}}</template></el-table-column>
      <el-table-column label="确认时间" min-width="210"><template #default="{row}">{{valueText(row.confirmedAt)}}</template></el-table-column>
      <el-table-column label="采集时间" min-width="210"><template #default="{row}">{{valueText(row.capturedAt)}}</template></el-table-column>
      <el-table-column label="来源链接" min-width="240"><template #default="{row}">{{valueText(row.sourceUrl)}}</template></el-table-column>
      <el-table-column label="复核人员编号" min-width="130"><template #default="{row}">{{valueText(row.confirmedBy)}}</template></el-table-column>
      <el-table-column label="驳回原因 / 许可有效期" min-width="210"><template #default>当前资料未记录</template></el-table-column>
      <el-table-column v-if="canManage" prop="imageId" label="图片ID" min-width="150" />
      <el-table-column v-if="canManage" prop="objectKey" label="对象键" min-width="250" show-overflow-tooltip />
      <el-table-column v-if="canManage" prop="sha256" label="图片摘要" min-width="240" show-overflow-tooltip />
    </el-table>
    <el-card v-if="!readOnly" shadow="never">
      <el-form label-width="100px">
        <el-form-item label="来源"><el-select v-model="sourceCode" :disabled="!!product" style="width:220px"><el-option v-for="item in sources" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="图片/ZIP"><input type="file" multiple accept=".jpg,.jpeg,.png,.webp,.zip" @change="selectFiles"><div class="hint">支持 JPG/PNG/WebP 或 ZIP；ZIP 映射可在下表补充。</div></el-form-item>
      </el-form>
      <el-table v-if="mappings.length" :data="mappings" size="small">
        <el-table-column label="预览" width="92"><template #default="{row}"><el-image v-if="previewUrls[row.filename]" :src="previewUrls[row.filename]" fit="cover" class="thumbnail" /><span v-else>ZIP 内文件</span></template></el-table-column>
        <el-table-column prop="filename" label="文件" min-width="150" /><el-table-column label="规格SKU" width="140"><template #default="{row}"><el-input v-model="row.skuCode" :disabled="!!product" placeholder="与款色二选一" /></template></el-table-column>
        <el-table-column label="款号" width="130"><template #default="{row}"><el-input v-model="row.styleCode" :disabled="!!product" /></template></el-table-column><el-table-column label="颜色编码" width="120"><template #default="{row}"><el-input v-model="row.colorCode" :disabled="!!product" /></template></el-table-column>
        <el-table-column label="用途" width="110"><template #default="{row}"><el-select v-model="row.usage"><el-option label="主图" value="main" /><el-option label="细节图" value="detail" /></el-select></template></el-table-column>
        <el-table-column label="主图" width="70"><template #default="{row}"><el-checkbox v-model="row.main" /></template></el-table-column><el-table-column label="颜色确认" width="95"><template #default="{row}"><el-checkbox v-model="row.colorConfirmed" /></template></el-table-column>
        <el-table-column label="提案可用" width="95"><template #default="{row}"><el-checkbox v-model="row.allowProposal" /></template></el-table-column>
        <el-table-column label="AI可用" width="90"><template #default="{row}"><el-checkbox v-model="row.allowAi" /></template></el-table-column>
        <el-table-column label="电商外发" width="100"><template #default="{row}"><el-checkbox v-model="row.allowEcommerce" /></template></el-table-column>
      </el-table>
      <el-button class="mt20" type="primary" :disabled="!files.length || !sourceCode" :loading="loading" v-hasPermi="['fashion:product:image']" @click="preview">上传并预览</el-button>
    </el-card>
    <el-card v-if="batch" shadow="never" class="mt20"><template #header><div class="header"><span>{{batch.batchNo}} · 错误 {{batch.errorCount}}</span><el-button type="success" v-hasPermi="['fashion:product:image']" :loading="loading" :disabled="batch.status!=='validated'" @click="confirm">人工确认入库</el-button></div></template>
      <el-table :data="batch.details"><el-table-column prop="businessKey" label="图片项" min-width="180" /><el-table-column label="像素" width="110"><template #default="{row}">{{row.normalizedData?.width&&row.normalizedData?.height?`${row.normalizedData.width}×${row.normalizedData.height}`:'未识别'}}</template></el-table-column><el-table-column prop="status" label="状态" width="90" /><el-table-column label="错误/待办" min-width="260"><template #default="{row}"><div v-for="error in row.errors" :key="error.code" class="row-error">{{error.message}}</div><span v-if="!row.errors.length">映射明确，待最终确认</span></template></el-table-column></el-table>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getDicts } from '@/api/system/dict/data'
import auth from '@/plugins/auth'
import { confirmMaterials, previewMaterials, getProductImageContent, type ImageMapping } from '@/api/fashion/material'
import type { FashionProduct, ProductImage, ImportBatchView } from '@/api/fashion/types'
interface Option{label:string;value:string}
import {valueText,reviewLabel,permissionLabel as proposalPermissionLabel} from '../product/productList'
const props=defineProps<{product?:FashionProduct;readOnly?:boolean}>(),emit=defineEmits<{confirmed:[]}>()
const canManage=computed(()=>auth.hasPermiOr(['fashion:product:edit','fashion:product:image']))
type CurrentImage = ProductImage
const currentImages=computed(()=>(props.product?.images??[]) as CurrentImage[])
const sources=ref<Option[]>([]),sourceCode=ref(props.product?.sourceCode??''),files=ref<File[]>([]),mappings=ref<ImageMapping[]>([]),batch=ref<ImportBatchView>(),loading=ref(false),error=ref(''),previewUrls=reactive<Record<string,string>>({}),imageUrls=reactive<Record<string,string>>({}),imageLoading=reactive<Record<string,boolean>>({}),imageErrors=reactive<Record<string,string>>({})
let inputRevision=0,disposed=false
function clearPreviews(){for(const url of Object.values(previewUrls))URL.revokeObjectURL(url);for(const key of Object.keys(previewUrls))delete previewUrls[key]}
function clearImages(){for(const url of Object.values(imageUrls))URL.revokeObjectURL(url);for(const state of [imageUrls,imageLoading,imageErrors])for(const key of Object.keys(state))delete state[key]}
function newMapping(filename:string):ImageMapping{return{filename,skuCode:props.product?.skuCode??(filename.split('__')[0]||''),usage:'detail',main:false,colorConfirmed:false,sourceType:props.product?'manual':'jd_plugin',capturedAt:new Date().toISOString(),allowAi:false,allowProposal:false,allowEcommerce:false}}
function selectFiles(event:Event){clearPreviews();files.value=Array.from((event.target as HTMLInputElement).files??[]);batch.value=undefined;for(const file of files.value){if(!file.name.toLowerCase().endsWith('.zip'))previewUrls[file.name]=URL.createObjectURL(file)}mappings.value=files.value.filter(file=>!file.name.toLowerCase().endsWith('.zip')).map(file=>newMapping(file.name))}
async function preview(){const revision=inputRevision;loading.value=true;error.value='';try{const response=await previewMaterials(files.value,sourceCode.value,new Date().toISOString(),mappings.value);if(disposed)return;if(revision!==inputRevision){error.value='图片或映射在上传期间已变化，请重新预览';return}for(const detail of response.data.details){const filename=String(detail.rawData?.filename??'');if(filename&&!mappings.value.some(item=>item.filename===filename))mappings.value.push(newMapping(filename))}batch.value=response.data;ElMessage.success(response.data.errorCount?'已列出待匹配项；修正映射后可重新预览':'预览通过，请人工确认入库')}catch(e){if(!disposed)error.value=e instanceof Error?e.message:'图片预览失败'}finally{loading.value=false}}
async function confirm(){if(!batch.value||loading.value)return;const revision=inputRevision;loading.value=true;error.value='';try{const response=await confirmMaterials(batch.value.batchId,batch.value.rowVersion);if(disposed||revision!==inputRevision)return;batch.value=response.data;ElMessage.success('图片素材已关联商品');emit('confirmed')}catch(e){if(!disposed&&revision===inputRevision)error.value=e instanceof Error?e.message:'图片入库失败'}finally{loading.value=false}}
function permissionLabel(image:CurrentImage){return[['内部查看',image.allowInternal],['AI处理',image.allowAi],['提案',image.allowProposal],['电商外发',image.allowEcommerce]].map(([label,value])=>`${label}：${value===undefined?'未记录':value?'允许':'未许可'}`).join('；')}
async function loadImage(imageId:string){const productId=props.product?.id;if(!productId||imageLoading[imageId]||currentImages.value.find(image=>image.imageId===imageId)?.allowInternal===false)return;imageErrors[imageId]='';imageLoading[imageId]=true;try{const blob=await getProductImageContent(productId,imageId);if(disposed||productId!==props.product?.id)return;if(!blob.type.startsWith('image/'))throw new Error('服务未返回可预览图片');if(imageUrls[imageId])URL.revokeObjectURL(imageUrls[imageId]);imageUrls[imageId]=URL.createObjectURL(blob)}catch(e){if(!disposed&&productId===props.product?.id)imageErrors[imageId]=(e as {status?:number})?.status===403?'无查看权限':e instanceof Error?e.message:'图片读取失败'}finally{if(!disposed&&productId===props.product?.id)imageLoading[imageId]=false}}
watch([mappings,sourceCode,files],()=>{inputRevision++;batch.value=undefined},{deep:true,flush:'sync'})
watch(()=>props.product?.id,()=>{sourceCode.value=props.product?.sourceCode??'';files.value=[];mappings.value=[];batch.value=undefined;clearPreviews();clearImages()})
onMounted(async()=>{if(props.readOnly)return;try{const response=await getDicts('fashion_product_source');sources.value=(response.data??[]).filter((item:any)=>String(item.status)==='0').map((item:any)=>({label:String(item.dictLabel),value:String(item.dictValue)}))}catch(e){error.value=e instanceof Error?e.message:'图片来源加载失败'}})
onBeforeUnmount(()=>{disposed=true;clearPreviews();clearImages()})
</script>
<style scoped>.hint{margin-left:12px;color:var(--el-text-color-secondary)}.mt20{margin-top:20px}.header{display:flex;justify-content:space-between}.row-error{color:var(--el-color-danger)}.thumbnail{width:64px;height:64px;border-radius:4px}</style>
