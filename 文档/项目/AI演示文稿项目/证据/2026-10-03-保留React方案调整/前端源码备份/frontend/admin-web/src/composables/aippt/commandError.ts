/** 平台保留的业务错误可通过HTTP200传递；不能让HTTP成功码盖住业务409/401。 */
export function commandErrorStatus(error:unknown):number{
 if(!error||typeof error!=='object')return 0
 const value=error as {status?:unknown;code?:unknown;response?:{status?:unknown;data?:{code?:unknown}}}
 for(const candidate of [value.status,value.response?.data?.code,value.response?.status,value.code]){const status=Number(candidate);if(Number.isFinite(status)&&status>=400)return status}
 return 0
}
