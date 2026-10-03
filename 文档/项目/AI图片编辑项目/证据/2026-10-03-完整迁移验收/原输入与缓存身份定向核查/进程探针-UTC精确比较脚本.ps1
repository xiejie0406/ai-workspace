param([ValidateSet('before','after')][string]$Phase)
$ErrorActionPreference='Stop'
$aipWorkRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$aipPrivateRoot=Join-Path $aipWorkRoot '.runtime/aipicture'
$aipManifest=Get-Content -LiteralPath (Join-Path $aipPrivateRoot 'processes.json') -Raw | ConvertFrom-Json
if($aipManifest.databaseMode -ne 'existing' -or $aipManifest.database -ne 'ry-vue') { throw '图片通道必须沿原ry-vue' }
$aipAllProcesses=@(Get-CimInstance Win32_Process)
$aipPorts=@(Get-NetTCPConnection -State Listen -LocalPort 8415,7405,14385,8416 | Select-Object LocalPort,OwningProcess -Unique)
function Aip-Identity([int]$ProcessId,[string]$Name) {
    $aipProcess=$aipAllProcesses | Where-Object ProcessId -eq $ProcessId
    if(-not $aipProcess) { throw "进程 $Name/$ProcessId 当前不存在" }
    [pscustomobject]@{name=$Name;pid=$aipProcess.ProcessId;parentPid=$aipProcess.ParentProcessId;
        executablePath=$aipProcess.ExecutablePath;commandLine=$aipProcess.CommandLine;
        creationDateUtc=$aipProcess.CreationDate.ToUniversalTime().ToString('o')}
}
$aipOwned=@(foreach($aipEntry in $aipManifest.processes) {
    $aipIdentity=Aip-Identity $aipEntry.pid $aipEntry.name
    if($aipEntry.name -eq 'java') {
        if($aipIdentity.commandLine -notlike '*aipicture*') { throw 'Java命令行未匹配图片通道' }
    } elseif($aipIdentity.commandLine -notmatch '-m\s+aipicture_runtime\s+(serve|worker)') { throw 'Python命令行未匹配图片通道' }
    $aipIdentity
    foreach($aipChild in $aipAllProcesses | Where-Object ParentProcessId -eq $aipEntry.pid) {
        if($aipEntry.name -in @('python-serve','python-worker')) { Aip-Identity $aipChild.ProcessId ($aipEntry.name+'-child') }
    }
})
$aipProtected=@(foreach($aipPort in @(14385,8416)) {
    $aipOwner=@($aipPorts | Where-Object LocalPort -eq $aipPort | Select-Object -ExpandProperty OwningProcess -Unique)
    if($aipOwner.Count -ne 1) { throw '受保护服务端口不能确定唯一进程' }
    Aip-Identity $aipOwner[0] "protected-$aipPort"
})
$aipJava=@($aipManifest.processes | Where-Object name -eq 'java')[0]
$aipRecord=[ordered]@{checkedAt=(Get-Date).ToUniversalTime().ToString('o');phase=$Phase;
    manifest=$aipManifest;owned=$aipOwned;protected=$aipProtected;ports=$aipPorts;
    java_sha256=(Get-FileHash -LiteralPath $aipJava.artifact -Algorithm SHA256).Hash.ToLowerInvariant();
    secret_sha256=(Get-FileHash -LiteralPath (Join-Path $aipPrivateRoot 'service.secret') -Algorithm SHA256).Hash.ToLowerInvariant()}
if($Phase -eq 'after') {
    $aipBefore=Get-Content -LiteralPath (Join-Path $aipPrivateRoot 'unicode-image-runtime-before.json') -Raw | ConvertFrom-Json
    if($aipRecord.java_sha256 -ne $aipBefore.java_sha256 -or $aipRecord.secret_sha256 -ne $aipBefore.secret_sha256 -or $aipManifest.startedAt -ne $aipBefore.manifest.startedAt) { throw 'Java/原secret/原startedAt发生非预期变化' }
    foreach($aipExisting in $aipBefore.protected+@($aipBefore.owned | Where-Object name -eq 'java')) {
        $aipNow=@($aipProtected+$aipOwned | Where-Object name -eq $aipExisting.name)[0]
        foreach($aipField in @('pid','executablePath','commandLine','creationDateUtc')) {
            if($aipField -eq 'creationDateUtc') {
                # ConvertFrom-Json在新版PowerShell会把ISO文本解析为DateTime；严格比较UTC 100ns ticks。
                $aipNowTicks=[DateTimeOffset]::Parse($aipNow.$aipField,[Globalization.CultureInfo]::InvariantCulture).UtcTicks
                $aipBeforeTicks=if($aipExisting.$aipField -is [DateTime]){$aipExisting.$aipField.ToUniversalTime().Ticks}else{[DateTimeOffset]::Parse($aipExisting.$aipField,[Globalization.CultureInfo]::InvariantCulture).UtcTicks}
                if($aipNowTicks -ne $aipBeforeTicks) { throw "受保护身份 $($aipExisting.name)/UTC创建时间变化" }
                continue
            }
            if($aipNow.$aipField -ne $aipExisting.$aipField) { throw "受保护身份 $($aipExisting.name)/$aipField 变化" }
        }
    }
    $aipRecord.protected_java_frontend_technical_unchanged=$true
    $aipRecord.original_secret_and_database_preserved=$true
}
$aipOutput=Join-Path $aipPrivateRoot "unicode-image-runtime-$Phase.json"
if(Test-Path -LiteralPath $aipOutput) { throw '拒绝覆盖运行身份原结果' }
$aipRecord | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $aipOutput -Encoding utf8
[pscustomobject]@{phase=$Phase;owned=$aipOwned.Count;protected=$aipProtected.Count;ports=$aipPorts.LocalPort;javaSha=$aipRecord.java_sha256} | ConvertTo-Json -Depth 3
