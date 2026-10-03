<template>
  <div class="app-container ps-page">
    <h2>额度充值</h2>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <div class="ps-grid" v-loading="loading">
      <el-card v-for="item in packages" :key="item.id" shadow="hover">
        <h3>{{item.name}}</h3><p v-if="item.description">{{item.description}}</p><strong class="ps-card-value">¥ {{money(item.amountFen)}}</strong>
        <p>对话 {{item.llmTokens.toLocaleString()}} Token</p><p>向量 {{item.embeddingTokens.toLocaleString()}} Token</p>
        <p v-if="item.benefit">{{item.benefit}}</p><el-button v-hasPermi="['paismart:recharge:create']" type="primary" :loading="saving" :disabled="uncertain" @click="orderPackage(item.id)">选择套餐</el-button>
      </el-card>
    </div>
    <el-empty v-if="!loading&&!packages.length" description="当前没有可购买套餐，支付是否启用由后台配置决定"/>
    <el-card shadow="never" class="ps-section">
      <template #header>自定义充值</template>
      <el-form inline><el-form-item label="充值金额（元）"><el-input-number v-model="amount" :min="0.01" :max="100000" :precision="2"/></el-form-item>
        <el-button v-hasPermi="['paismart:recharge:create']" :loading="saving" :disabled="uncertain" @click="customOrder">创建充值订单</el-button>
      </el-form>
    </el-card>
    <el-card v-if="order" shadow="never" class="ps-section">
      <template #header>订单与支付状态</template>
      <el-alert v-if="uncertain" title="支付请求状态待核对，原订单已保留。请查询原订单，核对前不要重复创建充值。" type="warning" :closable="false"/>
      <el-alert v-if="order.errorMessage" :title="order.errorMessage" type="warning" :closable="false"/>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="订单号">{{order.tradeNo}}</el-descriptions-item>
        <el-descriptions-item label="状态">{{paymentLabel(order.state)}}</el-descriptions-item>
        <el-descriptions-item label="金额">¥ {{money(order.amountFen)}}</el-descriptions-item>
        <el-descriptions-item label="有效期">{{order.expiresAt||'由支付服务返回'}}</el-descriptions-item>
        <el-descriptions-item label="对话权益">{{order.llmTokens?.toLocaleString()??'由订单快照返回'}} Token</el-descriptions-item>
        <el-descriptions-item label="向量权益">{{order.embeddingTokens?.toLocaleString()??'由订单快照返回'}} Token</el-descriptions-item>
      </el-descriptions>
      <div class="payment">
        <img v-if="qrUrl" :src="qrUrl" alt="本次订单微信支付二维码" width="240" height="240"/>
        <p v-if="order.state==='SUCCEEDED'">支付已由服务端确认，请到用量页面回查余额与唯一入账流水。</p>
        <p v-else-if="closedPayment(order.state)" class="ps-muted">订单已结束，扫码地址已撤下。</p>
        <p v-else-if="uncertain" class="ps-muted">当前停止自动查单。点击查询原订单，或从下方历史订单选择本次订单继续核对。</p>
        <p v-else-if="expired(order)" class="ps-muted">扫码地址已到期。查询原订单可核对最终结果；本页不会自动新建订单。</p>
        <p v-else-if="!order.codeUrl" class="ps-muted">原订单尚无可用扫码地址，请先查询原订单。</p>
        <p v-else class="ps-muted">扫码后等待服务端确认。查询原订单不会绕过回调验签或重复入账。</p>
      </div>
      <el-button :loading="polling" @click="refreshOrder(true)">查询原订单</el-button>
    </el-card>
    <el-card shadow="never" class="ps-section">
      <template #header><div class="ps-toolbar"><span>我的历史订单</span><el-button :loading="historyLoading" @click="loadHistory()">刷新列表</el-button></div></template>
      <el-alert v-if="historyError" :title="historyError" type="error" :closable="false"/>
      <el-form inline @submit.prevent="loadHistory(true)"><el-form-item label="订单状态"><el-select v-model="historyQuery.state" clearable placeholder="全部状态"><el-option v-for="state in orderStates" :key="state" :label="paymentLabel(state)" :value="state"/></el-select></el-form-item><el-button @click="loadHistory(true)">查询订单</el-button><el-button @click="resetHistory">重置筛选</el-button></el-form>
      <el-table :data="history" v-loading="historyLoading">
        <el-table-column prop="tradeNo" label="订单号" min-width="270"/>
        <el-table-column label="金额" width="130"><template #default="{row}">¥ {{money(row.amountFen)}}</template></el-table-column>
        <el-table-column label="状态" width="160"><template #default="{row}">{{paymentLabel(row.state)}}</template></el-table-column>
        <el-table-column prop="expiresAt" label="扫码有效期" min-width="180"/>
        <el-table-column label="操作" width="150"><template #default="{row}"><el-button v-hasPermi="['paismart:recharge:create']" link :disabled="polling||saving" @click="selectOrder(row)">选择原订单查询</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="historyQuery.pageNum" :page-size="historyQuery.pageSize" :total="historyTotal" layout="total,prev,pager,next" @current-change="loadHistory()"/>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { computed,onBeforeUnmount,onMounted,ref,shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import { createOrder,getOrder,listOrders,listPackages } from '@/api/paismart/recharge'
import { call,newKey,pathId } from '@/api/paismart/client'
import type { Id,Order,RechargePackage } from '@/types/paismart'
import { errorText,usePage } from '../composables/usePage'
import '../common.css'
const route=useRoute(),packages=shallowRef<RechargePackage[]>([]),order=shallowRef<Order>(),loading=ref(false),saving=ref(false),polling=ref(false),error=ref(''),amount=ref(1),qrUrl=ref('')
const {rows:history,total:historyTotal,query:historyQuery,loading:historyLoading,error:historyError,load:loadHistory}=usePage(listOrders)
const orderStates=['NOT_PAY','PAYING','SUCCEEDED','FAILED','CLOSED','EXPIRED','CREATING','PREPAY_STARTED','UNCERTAIN']
function resetHistory(){historyQuery.value.state=undefined;void loadHistory(true)}
let timer:ReturnType<typeof setTimeout>|undefined,key=newKey(),objectUrl='',qrTrade='',qrCode='',disposed=false,serial=0
const payable=(state:string)=>['NOT_PAY','PAYING'].includes(state)
const closedPayment=(state:string)=>['SUCCEEDED','CLOSED','EXPIRED','FAILED'].includes(state)
const uncertain=computed(()=>!!order.value&&!payable(order.value.state)&&!closedPayment(order.value.state))
const money=(fen:number)=>(fen/100).toFixed(2)
function expired(row:Order){return !!row.expiresAt&&new Date(row.expiresAt).getTime()<=Date.now()}
function paymentLabel(state:string){return ({SUCCEEDED:'已支付',CLOSED:'已关闭',EXPIRED:'已到期',FAILED:'失败',NOT_PAY:'待支付',PAYING:'等待支付确认',CREATING:'创建中，待核对',PREPAY_STARTED:'请求已发出，待核对',UNCERTAIN:'支付状态待核对'} as Record<string,string>)[state]??`${state}（待核对）`}
function clearQr(){if(objectUrl)URL.revokeObjectURL(objectUrl);objectUrl='';qrUrl.value='';qrTrade='';qrCode=''}
async function showQr(current:number){
  const row=order.value;if(!row||!payable(row.state)||expired(row)){clearQr();return}
  if(qrUrl.value&&qrTrade===row.tradeNo&&qrCode===row.codeUrl)return
  clearQr()
  if(row.qrImageDataUrl&&/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(row.qrImageDataUrl)){qrUrl.value=row.qrImageDataUrl;qrTrade=row.tradeNo;qrCode=row.codeUrl??'';return}
  if(row.codeUrl){const blob=await call<Blob>(`/recharge/orders/${pathId(row.tradeNo)}/qrcode`,{responseType:'blob'});if(disposed||current!==serial)return;if(blob.type!=='image/png')throw new Error('支付二维码内容类型不受支持');objectUrl=URL.createObjectURL(blob);qrUrl.value=objectUrl;qrTrade=row.tradeNo;qrCode=row.codeUrl}
}
function schedule(){clearTimeout(timer);if(!disposed&&order.value&&payable(order.value.state)&&!expired(order.value))timer=setTimeout(()=>void refreshOrder(),5000)}
async function submit(data:{packageId?:Id;customAmountFen?:number}){
  if(saving.value||uncertain.value)return;saving.value=true;error.value='';clearTimeout(timer);const current=++serial
  try{const response=await createOrder(data,key);if(disposed||current!==serial)return;order.value=response.data;key=newKey();await showQr(current);schedule();await loadHistory()}
  catch(e){if(current===serial)error.value=errorText(e)}finally{saving.value=false}
}
function orderPackage(id:Id){void submit({packageId:id})}
function customOrder(){void submit({customAmountFen:Math.round(amount.value*100)})}
async function readOrder(tradeNo:string,updateHistory=false){
  if(polling.value||saving.value)return;polling.value=true;error.value='';clearTimeout(timer);const current=++serial
  try{const response=await getOrder(tradeNo);if(disposed||current!==serial)return;order.value=response.data;await showQr(current);schedule();if(updateHistory)await loadHistory()}
  catch(e){if(current===serial)error.value=errorText(e)}finally{polling.value=false}
}
async function refreshOrder(updateHistory=false){if(order.value)await readOrder(order.value.tradeNo,updateHistory)}
async function selectOrder(row:Order){await readOrder(row.tradeNo)}
onMounted(async()=>{
  loading.value=true;const historyLoad=loadHistory()
  try{packages.value=(await listPackages()).rows.filter(p=>p.enabled);if(typeof route.query.tradeNo==='string')await readOrder(route.query.tradeNo)}
  catch(e){error.value=errorText(e)}finally{loading.value=false}
  await historyLoad
})
onBeforeUnmount(()=>{disposed=true;serial++;clearTimeout(timer);clearQr()})
</script>
<style scoped>.payment{padding:20px 0}.payment img{border:1px solid var(--el-border-color);border-radius:8px}</style>
