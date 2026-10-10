#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Plan','ApplyCandidates','Check')][string]$Mode='Plan',
    [Security.SecureString]$AccessToken
)
$ErrorActionPreference='Stop'
# 默认仅展示非秘密候选；显式ApplyCandidates才调用原若依授权API保存。
$preset=Get-Content -LiteralPath (Join-Path $PSScriptRoot '模型接入配置.json') -Raw | ConvertFrom-Json -AsHashtable
if($preset.version -ne 1 -or $preset.providers.Count -ne 2){throw '模型候选配置格式不正确'}
function Assert-NoInlineSecrets($Value,[int]$Depth=0){
    if($Depth -gt 8){throw '候选配置嵌套超过范围'}
    if($Value -is [Collections.IDictionary]){
        foreach($field in $Value.Keys){
            if($field -match '(?i)^(api[_-]?key|access[_-]?key(?:id|secret)?|secret|password|token|credentials?|authorization|private[_-]?key)$'){throw '候选配置不允许保存任何内联凭据'}
            Assert-NoInlineSecrets $Value[$field] ($Depth+1)
        }
    }elseif($Value -is [Collections.IEnumerable] -and $Value -isnot [string]){
        foreach($item in $Value){Assert-NoInlineSecrets $item ($Depth+1)}
    }elseif($Value -is [string] -and $Value -match 'sk-[a-zA-Z0-9]{20,}'){
        throw '候选配置含疑似完整API密钥，拒绝输出或提交'
    }
}
Assert-NoInlineSecrets $preset
foreach($candidate in $preset.providers){
    if($candidate.scope -notin @('LLM','EMBEDDING') -or $candidate.enabled -or $candidate.active -or $candidate.rowVersion -ne 0){throw '仅支持首次未启用、未激活候选配置'}
}
if($Mode -eq 'Plan'){
    $preset | ConvertTo-Json -Depth 12
    Write-Host '仅展示候选，没有连接数据库、登记凭据、调用模型或保存Provider。'
    return
}
if(-not $AccessToken){$AccessToken=Read-Host '请输入已登录本机若依的访问令牌（不回显）' -AsSecureString}
if($AccessToken.Length -eq 0){throw '需要原若依有效登录令牌；不创建登录或绕过验证码'}
$headers=@{Authorization='Bearer '+([Net.NetworkCredential]::new('',$AccessToken).Password)}
$base='http://127.0.0.1:18096/paismart'
function Invoke-ProjectApi([string]$Method,[string]$Path,$Body=$null){
    $arguments=@{Uri=$base+$Path;Method=$Method;Headers=$headers;NoProxy=$true;MaximumRedirection=0;TimeoutSec=30;ErrorAction='Stop'}
    if($null -ne $Body){$arguments.ContentType='application/json; charset=utf-8';$arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 12 -Compress))}
    try{$response=Invoke-RestMethod @arguments}catch{throw '本机项目API请求失败；已停止，不自动重试；请从原项目页面核对状态'}
    if($response.code -ne 200){throw '原若依API拒绝本次操作；请核对权限、托管用途和配置，不自动重试'}
    return $response
}
function Same-Candidate($Actual,$Expected){
    foreach($field in @('scope','provider','model','secretRef')){if($Actual.$field -cne $Expected[$field]){return $false}}
    if($Actual.address.TrimEnd('/') -cne $Expected.address.TrimEnd('/')){return $false}
    if($Expected.scope -eq 'EMBEDDING' -and $Actual.dimension -ne $Expected.dimension){return $false}
    foreach($field in $Expected.config.Keys){if($Actual.config.$field -cne $Expected.config[$field]){return $false}}
    $extra=@($Actual.config.PSObject.Properties.Name | Where-Object {$_ -ne 'compatibility' -and $_ -notin $Expected.config.Keys})
    return $extra.Count -eq 0
}
$mutex=$null;$locked=$false
try{
    if($Mode -eq 'ApplyCandidates'){
        $mutex=[Threading.Mutex]::new($false,'Local\PaiSmartModelCandidateConfig')
        try{$locked=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$locked=$true}
        if(-not $locked){throw '同一本机配置入口正在执行，请先核对其结果，不并发创建候选'}
    }
    $rows=@((Invoke-ProjectApi 'Get' '/providers').rows)
    if($Mode -eq 'Check'){
        $rows | Select-Object id,scope,provider,model,address,dimension,secretRef,configured,enabled,active,rowVersion,compatibilityValid | ConvertTo-Json -Depth 5
        return
    }
    # 在写任一scope前核对两个scope；已有不同配置不自动替换。
    foreach($candidate in $preset.providers){
        $existing=@($rows | Where-Object scope -eq $candidate.scope)
        if($existing.Count -gt 1 -or ($existing.Count -eq 1 -and -not (Same-Candidate $existing[0] $candidate))){throw '现有Provider与候选不同，未自动覆盖；请先在原供应商页审查差异'}
    }
    foreach($candidate in $preset.providers){
        $rows=@((Invoke-ProjectApi 'Get' '/providers').rows)
        $existing=@($rows | Where-Object scope -eq $candidate.scope)
        if($existing.Count -gt 1 -or ($existing.Count -eq 1 -and -not (Same-Candidate $existing[0] $candidate))){throw '配置准备期间Provider已变化，停止创建；请在原页面核对'}
        if($existing.Count -eq 1){Write-Host "$($candidate.scope)候选参数已存在，复用，保留原启用和激活状态";continue}
        $saved=(Invoke-ProjectApi 'Put' ('/providers/'+$candidate.scope) $candidate).data
        $actual=@((Invoke-ProjectApi 'Get' '/providers').rows | Where-Object scope -eq $candidate.scope)
        if($actual.Count -ne 1 -or $actual[0].id -ne $saved.id -or -not (Same-Candidate $actual[0] $candidate) -or $actual[0].enabled -or $actual[0].active){throw '候选保存回读不一致，已停止；保留现有配置，人工核对，不自动重送'}
        Write-Host "$($candidate.scope)候选已保存并回读；未启用、未激活，不调用模型"
    }
    Write-Host '请在原供应商页启用候选，准备账户额度，再执行真实验证；通过后再激活。'
}finally{
    if($locked){$mutex.ReleaseMutex()}
    if($mutex){$mutex.Dispose()}
    $headers.Authorization=$null
    $AccessToken=$null
}
