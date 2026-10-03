<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
const props=withDefaults(defineProps<{value:string;label:string;editable?:boolean;multiline?:boolean}>(),{editable:false,multiline:true});const emit=defineEmits<{commit:[text:string];enter:[];emptyBackspace:[]}>();const element=ref<HTMLElement|null>(null),composing=ref(false),focused=ref(false)
function content(){return (element.value?.innerText??element.value?.textContent??'').replace(/\r\n/g,'\n').replace(/\u00a0/g,' ')}
let deferredBlur=false,committedValue=props.value
function commit(){if(composing.value)return;const value=content();if(value!==committedValue){committedValue=value;emit('commit',value)}}
function blur(){focused.value=false;if(composing.value)deferredBlur=true;else commit()}
function compositionEnd(){composing.value=false;if(deferredBlur){deferredBlur=false;commit()}}
function key(e:KeyboardEvent){if(e.isComposing||composing.value)return;if(e.key==='Enter'&&!props.multiline){e.preventDefault();emit('enter');commit()}if(e.key==='Backspace'&&!content()){emit('emptyBackspace')}}
function paste(e:ClipboardEvent){e.preventDefault();const raw=e.clipboardData?.getData('text/plain')||'';const text=props.multiline?raw:raw.replace(/[\r\n]+/g,' ');const selection=window.getSelection();if(!selection?.rangeCount)return;const range=selection.getRangeAt(0);if(!element.value?.contains(range.commonAncestorContainer))return;range.deleteContents();const node=document.createTextNode(text);range.insertNode(node);range.setStartAfter(node);range.collapse(true);selection.removeAllRanges();selection.addRange(range)}
watch(()=>props.value,v=>{committedValue=v;if(element.value&&!focused.value&&!composing.value&&content()!==v)element.value.textContent=v});onMounted(()=>{if(element.value)element.value.textContent=props.value})
</script>
<template><span ref="element" class="plain-edit" :contenteditable="editable" :role="editable?'textbox':undefined" :aria-label="label" :aria-multiline="editable?multiline:undefined" :tabindex="editable?0:undefined" @focus="focused=true" @blur="blur" @compositionstart="composing=true" @compositionend="compositionEnd" @keydown="key" @paste="paste"/></template>
<style scoped>.plain-edit{display:block;min-height:1em;white-space:pre-wrap;word-break:break-word;outline:none}.plain-edit[contenteditable=true]:focus{outline:1px dashed currentColor;outline-offset:3px}</style>
