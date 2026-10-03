export function evaluationMetric(value:unknown,percent=false):string{
  if(value===undefined)return '未返回'
  if(value===null)return '无可计算样本'
  if(typeof value==='number')return percent?(value*100).toFixed(2)+'%':Number.isInteger(value)?value.toLocaleString():value.toFixed(4)
  return String(value)
}
export function verdictLabel(correct:boolean|null|undefined){return correct===true?'通过':correct===false?'不通过':correct===null?'需补证据':'未复核'}
