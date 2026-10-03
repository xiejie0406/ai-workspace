import type {QualityReport,Task} from '@/types/aippt'
import {waitTask} from './useTaskResult'
export async function resolveQualityReport(read:()=>Promise<QualityReport>,signal:AbortSignal):Promise<QualityReport>{
 let report=await read()
 const job=report.job_id||report.jobId
 if(job&&!Array.isArray(report.issues)){await waitTask({job_id:job,status:report.status||'queued'} as Task,signal);report=await read()}
 if(signal.aborted)throw new DOMException('检查已取消','AbortError')
 if(!Array.isArray(report.issues)||typeof(report.can_export??report.export_allowed)!=='boolean')throw new Error('质量检查尚未返回完整结果，请重试')
 return report
}
