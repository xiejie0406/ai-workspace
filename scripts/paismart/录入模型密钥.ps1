[CmdletBinding()]
param([ValidateSet('Sub2API','Aliyun','Both')][string]$Provider='Both',[switch]$ImportProvidedKeys)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '本机环境公共函数.ps1')
$folder=Join-Path $script:PaismartRuntime 'model-credentials'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
# 仅持久化已修改的DACL；Set-Acl对既有受保护目录可能要求无关SACL特权。
$directory=[IO.DirectoryInfo]::new($folder)
if($PSVersionTable.PSEdition -eq 'Core'){[IO.FileSystemAclExtensions]::SetAccessControl($directory,$acl)}
else{$directory.SetAccessControl($acl)}
# 显式导入现有私有资料时，先校验全部选中值，再保存；不读取Markdown。
$provided=@{}
if($ImportProvidedKeys){
    $source=Join-Path $folder '本次模型密钥.json'
    try{$sourceData=Get-Content -LiteralPath $source -Raw | ConvertFrom-Json}catch{throw '现有私有密钥资料不可读，未登记或覆盖凭据'}
    $fingerprints=@{Sub2API='d984a1ca523aedcf';Aliyun='aec16905a7b2d081'}
    foreach($name in @('Sub2API','Aliyun')){
        if($Provider -ne 'Both' -and $Provider -ne $name){continue}
        $key=if($name -eq 'Sub2API'){$sourceData.sub2api.key}else{$sourceData.aliyun.key}
        if($key -isnot [string] -or $key -notmatch '^sk-[0-9a-f]{32,128}$'){throw '现有密钥格式无效，未登记或覆盖凭据'}
        $sha=[Security.Cryptography.SHA256]::Create()
        try{$digest=$sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($key));$fingerprint=([BitConverter]::ToString($digest)).Replace('-','').ToLowerInvariant().Substring(0,16)}finally{$sha.Dispose()}
        if($fingerprint -ne $fingerprints[$name]){throw '私有资料与用户确认的密钥指纹不同，禁止自动导入或轮换'}
        $provided[$name]=[PSCredential]::new($name,(ConvertTo-SecureString -String $key -AsPlainText -Force))
        $path=Join-Path $folder ($name.ToLowerInvariant()+'.clixml')
        if(Test-Path -LiteralPath $path){
            $existing=Import-Clixml -LiteralPath $path
            if($existing.GetNetworkCredential().Password -cne $key){throw '已有DPAPI凭据与用户确认值不同，禁止覆盖；请通过原平台轮换流程处理'}
        }
        $key=$null
    }
    $sourceData=$null
}
foreach($name in @('Sub2API','Aliyun')){
    if($Provider -ne 'Both' -and $Provider -ne $name){continue}
    $path=Join-Path $folder ($name.ToLowerInvariant()+'.clixml')
    if(Test-Path -LiteralPath $path){Write-Host "$name 的受保护文件已存在，保留并复用；不自动轮换";continue}
    $value=if($ImportProvidedKeys){$provided[$name].Password}else{Read-Host "请输入$name 的PaiSmart专用密钥（不回显）" -AsSecureString}
    if($value.Length -lt 8){throw '密钥输入为空或长度无效'}
    [PSCredential]::new($name,$value) | Export-Clixml -LiteralPath $path
    Write-Host "$name 已保存到当前Windows用户DPAPI文件；没有打印或写入命令参数"
}
Invoke-PaismartLocalSetup 'register'
Write-Host '模型密钥用途已登记；Provider的地址、协议、验证和激活仍须完成。'
