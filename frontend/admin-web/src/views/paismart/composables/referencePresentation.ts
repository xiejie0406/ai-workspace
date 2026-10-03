import type {Reference} from '@/types/paismart'

/** 反例优先；未知状态不能作为已核验。理由始终通过文本插值展示。 */
export function referenceVerdict(reference:Reference){
  const verification=reference.verification
  const suspicious=verification?.supported===false||reference.verified===false||verification?.status==='SUSPICIOUS'
  return {suspicious,label:suspicious?'待核实':verification?.supported===true||reference.verified===true?'已核验':'未核验',reason:suspicious?(verification?.reason?.trim()||'引用片段可能无法支撑回答，请核对原文。'):''}
}
