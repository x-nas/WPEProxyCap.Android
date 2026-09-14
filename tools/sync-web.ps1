<#
    把共用前端（同级仓库 WPEProxyCap.Web）的 Android 构建镜像进 app\src\main\assets\www。

    用法（仓库根目录）：
        powershell -ExecutionPolicy Bypass -File tools\sync-web.ps1            # 先 npm run build:android 再镜像
        powershell -ExecutionPolicy Bypass -File tools\sync-web.ps1 -SkipBuild # 只镜像现有的 dist\android

    ⚠️ 三个仓库要并排检出：x-nas\WPEProxyCap.Android 与 x-nas\WPEProxyCap.Web。
    ⚠️ 镜像完会把前端仓库当前的提交号写进 assets\www\web-commit.txt ——
       APK 按 GPL-3.0 分发，「对应源码」要能对上前端的哪一个提交；前端仓库有未提交改动时打印警告。
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$web = Join-Path $repo '..\WPEProxyCap.Web'
if (-not (Test-Path (Join-Path $web 'package.json'))) { throw "找不到前端仓库：$web（WPEProxyCap.Web 要与本仓库并排检出）" }

if (-not $SkipBuild) {
    Push-Location $web
    try {
        $old = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        npm run build:android
        $code = $LASTEXITCODE
        $ErrorActionPreference = $old
        if ($code -ne 0) { throw "npm run build:android 失败（退出码 $code）" }
    }
    finally { Pop-Location }
}

$src = Join-Path $web 'dist\android'
if (-not (Test-Path (Join-Path $src 'index.html'))) { throw "没有 Android 前端产物：$src" }

$dst = Join-Path $repo 'app\src\main\assets\www'
New-Item -ItemType Directory -Force $dst | Out-Null
robocopy $src $dst /MIR /NJH /NJS /NFL /NDL /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "镜像前端产物失败（robocopy 退出码 $LASTEXITCODE）" }
$global:LASTEXITCODE = 0

$commit = (git -C $web rev-parse HEAD).Trim()
$dirty = (git -C $web status --porcelain) -ne $null
Set-Content -Encoding ascii -Path (Join-Path $dst 'web-commit.txt') -Value ("WPEProxyCap.Web " + $commit + ($(if ($dirty) { ' (dirty)' } else { '' })))

$files = Get-ChildItem $dst -Recurse -File
Write-Host ("前端已同步：{0} 个文件，{1:N1} MB，WPEProxyCap.Web {2}" -f $files.Count, (($files | Measure-Object Length -Sum).Sum / 1MB), $commit.Substring(0, 8)) -ForegroundColor Green
if ($dirty) { Write-Host '警告：WPEProxyCap.Web 有未提交的改动，发布前先提交，否则 APK 对不上任何一个前端提交' -ForegroundColor Yellow }
