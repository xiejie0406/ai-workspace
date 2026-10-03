<script setup lang="ts">
const props=defineProps<{disabled?:boolean}>(),emit=defineEmits<{insert:[type:string,columns:number]}>();const types={text:'文字',bullets:'要点',image:'图片',chart:'图表',table:'表格',kpi:'指标',cards:'卡片',callout:'提示'}
function drag(e:DragEvent,type:string,columns=1){if(props.disabled){e.preventDefault();return}e.dataTransfer?.setData('application/aippt-block',JSON.stringify({type,columns}));if(e.dataTransfer)e.dataTransfer.effectAllowed='copy'}
</script>
<template><section><h4>插入内容</h4><p>点击插入选中块之后；也可拖到画布容器空白处。</p><div class="insert-grid"><el-button v-for="(label,type) in types" :key="type" :disabled="disabled" draggable="true" @dragstart="drag($event,type)" @click="emit('insert',type,1)">{{label}}</el-button><el-button :disabled="disabled" draggable="true" @dragstart="drag($event,'text',2)" @click="emit('insert','text',2)">两列文字</el-button><el-button :disabled="disabled" draggable="true" @dragstart="drag($event,'text',3)" @click="emit('insert','text',3)">三列文字</el-button></div></section></template>
<style scoped>.insert-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}.insert-grid .el-button{margin:0}p{font-size:13px;line-height:1.5;color:#606266}</style>
