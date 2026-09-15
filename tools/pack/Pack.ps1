<#
    WPC 手机版一键出发布 APK

    用法（仓库根目录 WPEProxyCap.Android\ 下；平时直接双击 pack.cmd）：
        powershell -ExecutionPolicy Bypass -File tools\pack\Pack.ps1
        powershell -ExecutionPolicy Bypass -File tools\pack\Pack.ps1 -SkipWeb     # 不重新构建 / 同步前端
        powershell -ExecutionPolicy Bypass -File tools\pack\Pack.ps1 -SkipTests   # 不跑单元测试

    流程：检查本机配置 → 同步前端（tools\sync-web.ps1）→ gradle clean [testDebugUnitTest] assembleRelease
          → 核对签名指纹 / APK 内容 / 没有明文 ApiKey → 输出 dist\WPC Android v<版本>.apk + .sha256.txt

    工具链：默认用 %USERPROFILE%\AndroidDev 下的便携版（jdk、gradle-*、SDK 路径取 local.properties 的 sdk.dir），
            可用环境变量 WPC_ANDROID_DEV 指到别处；找不到本机 Gradle 时退回仓库里的 gradlew.bat。
    ⚠️ 内核 AAR（app\libs\wpccore.aar）不在这里重编：改了 core\ 先跑 bash tools/build-core.sh。
    ⚠️ 前端仓库 WPEProxyCap.Web 要与本仓库并排检出（-SkipWeb 时不需要）。
#>
[CmdletBinding()]
param(
    [switch]$SkipWeb,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$sw = [Diagnostics.Stopwatch]::StartNew()

function Step($text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }

# Java .properties：去掉转义（C\:\\Users → C:\Users）
function Read-Props($path) {
    $h = @{}
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        if ($line -match '^\s*([^#!=\s][^=]*?)\s*=\s*(.*)$') { $h[$Matches[1]] = ($Matches[2] -replace '\\(.)', '$1').Trim() }
    }
    return $h
}

# 调原生程序：stderr 里的警告不当成 PowerShell 错误
function Invoke-Native([scriptblock]$block) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $block } finally { $ErrorActionPreference = $old }
}

# ———————————————— 1. 本机配置与工具链 ————————————————
Step '检查本机配置与工具链'

$localProps = Join-Path $Repo 'local.properties'
if (-not (Test-Path $localProps)) { throw '缺少 local.properties（sdk.dir 与 wpc.apiKey），见 README「构建」一节' }
$lp = Read-Props $localProps
if (-not $lp['sdk.dir'] -or -not (Test-Path $lp['sdk.dir'])) { throw "local.properties 的 sdk.dir 不存在：$($lp['sdk.dir'])" }
$apiKey = [string]$lp['wpc.apiKey']
if (-not $apiKey) { throw 'local.properties 的 wpc.apiKey 为空：编出来的 APK 连不上订阅服务器' }

$ksProps = Join-Path $Repo 'keystore.properties'
if (-not (Test-Path $ksProps)) { throw '缺少 keystore.properties：没有发布签名，不能出发布包' }
$kp = Read-Props $ksProps
$ksFile = Join-Path $Repo $kp['storeFile']
if (-not (Test-Path $ksFile)) { throw "找不到签名密钥：$ksFile" }

$dev = if ($env:WPC_ANDROID_DEV) { $env:WPC_ANDROID_DEV } else { Join-Path $env:USERPROFILE 'AndroidDev' }
if (Test-Path (Join-Path $dev 'jdk\bin\java.exe')) { $env:JAVA_HOME = Join-Path $dev 'jdk' }
elseif (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw "找不到 JDK：$dev\jdk 不存在，JAVA_HOME 也没设" }
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path

$gradleDir = Get-ChildItem -Path $dev -Directory -Filter 'gradle-*' -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin\gradle.bat') } | Sort-Object Name -Descending | Select-Object -First 1
$gradle = if ($gradleDir) { Join-Path $gradleDir.FullName 'bin\gradle.bat' } else { Join-Path $Repo 'gradlew.bat' }

$buildTools = Get-ChildItem -Path (Join-Path $lp['sdk.dir'] 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
    Sort-Object { $v = $null; if ([version]::TryParse(($_.Name -replace '[^\d.]', ''), [ref]$v)) { $v } else { [version]'0.0' } } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "SDK 里没有 build-tools（apksigner）：$($lp['sdk.dir'])\build-tools" }
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'

$gradleKts = Get-Content -Raw -LiteralPath (Join-Path $Repo 'app\build.gradle.kts')
if ($gradleKts -notmatch 'versionName\s*=\s*"([^"]+)"') { throw 'app\build.gradle.kts 里找不到 versionName' }
$version = $Matches[1]

# 签名密钥的证书指纹（密码经环境变量传给 keytool，不出现在命令行里）
$env:WPC_PACK_KSPW = $kp['storePassword']
try {
    $ktOut = Invoke-Native { & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -list -v -keystore $ksFile -alias $kp['keyAlias'] -storepass:env WPC_PACK_KSPW 2>&1 | Out-String }
} finally { Remove-Item Env:\WPC_PACK_KSPW -ErrorAction SilentlyContinue }
if ($ktOut -notmatch 'SHA256:\s*([0-9A-Fa-f:]{95})') { throw "读不出签名密钥的指纹（密码或别名不对？）`n$ktOut" }
$expectedCert = ($Matches[1] -replace ':', '').ToLower()

Write-Host ("版本 {0} · JDK {1} · {2} · build-tools {3}" -f $version, $env:JAVA_HOME, (Split-Path (Split-Path $gradle -Parent) -Parent | Split-Path -Leaf), $buildTools.Name)
Write-Host ("ApiKey 已配置（{0} 个字符）· 签名证书 SHA-256 {1}…{2}" -f $apiKey.Length, $expectedCert.Substring(0, 8), $expectedCert.Substring(56))

# ———————————————— 2. 前端 ————————————————
if ($SkipWeb) {
    Step '跳过前端同步（-SkipWeb），用仓库里现有的 assets\www'
} else {
    Step '构建并同步共用前端（WPEProxyCap.Web → assets\www）'
    & (Join-Path $Repo 'tools\sync-web.ps1')
}

# ———————————————— 3. Gradle ————————————————
$tasks = @('clean')
if (-not $SkipTests) { $tasks += 'testDebugUnitTest' }
$tasks += 'assembleRelease'
Step ("Gradle：{0}" -f ($tasks -join ' '))
Push-Location $Repo
try {
    Invoke-Native { & $gradle @tasks --no-daemon --console=plain }
    $code = $LASTEXITCODE
} finally { Pop-Location }
if ($code -ne 0) { throw "Gradle 构建失败（退出码 $code）" }

# ———————————————— 4. 核对产物 ————————————————
Step '核对 APK：签名、内容、没有明文 ApiKey'
$apk = Join-Path $Repo 'app\build\outputs\apk\release\app-arm64-v8a-release.apk'
if (-not (Test-Path $apk)) { throw "没有生成 APK：$apk" }

$sigOut = Invoke-Native { & $apksigner verify --print-certs $apk 2>&1 | Out-String }
if ($LASTEXITCODE -ne 0) { throw "apksigner 验证失败：`n$sigOut" }
if ($sigOut -notmatch 'certificate SHA-256 digest:\s*([0-9a-f]{64})') { throw "apksigner 没有输出证书指纹：`n$sigOut" }
if ($Matches[1] -ne $expectedCert) { throw "APK 的签名证书（$($Matches[1])）不是 keystore.properties 里那把密钥（$expectedCert）" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    foreach ($need in 'AndroidManifest.xml', 'classes.dex', 'lib/arm64-v8a/libgojni.so', 'assets/www/index.html', 'assets/base-android.yaml') {
        if ($names -notcontains $need) { throw "APK 里缺少 $need" }
    }
    $ascii = [Text.Encoding]::ASCII
    foreach ($e in $zip.Entries) {
        $ms = New-Object IO.MemoryStream
        $s = $e.Open(); try { $s.CopyTo($ms) } finally { $s.Close() }
        if ($ascii.GetString($ms.ToArray()).Contains($apiKey)) { throw "APK 的 $($e.FullName) 里出现了明文 ApiKey" }
    }
} finally { $zip.Dispose() }

$webCommitFile = Join-Path $Repo 'app\src\main\assets\www\web-commit.txt'
$webCommit = if (Test-Path $webCommitFile) { (Get-Content -Raw $webCommitFile).Trim() } else { '（没有 web-commit.txt）' }

# ———————————————— 5. 输出 ————————————————
$dist = Join-Path $Repo 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$outName = "WPC Android v$version.apk"
$outApk = Join-Path $dist $outName
Copy-Item -LiteralPath $apk -Destination $outApk -Force
$hash = (Get-FileHash -LiteralPath $outApk -Algorithm SHA256).Hash
[IO.File]::WriteAllText("$outApk.sha256.txt", "$hash  $outName`r`n", (New-Object Text.UTF8Encoding $false))

$repoCommit = Invoke-Native { (git -C $Repo rev-parse --short HEAD 2>$null) }
$repoDirty = Invoke-Native { git -C $Repo status --porcelain 2>$null }

Write-Host ''
Write-Host ("完成（{0:N0} 秒）" -f $sw.Elapsed.TotalSeconds) -ForegroundColor Green
Write-Host ("  {0}  {1:N2} MB" -f $outApk, ((Get-Item -LiteralPath $outApk).Length / 1MB)) -ForegroundColor Green
Write-Host ("  SHA256 {0}" -f $hash)
Write-Host ("  签名 {0}…{1}（与密钥一致）· 本仓库 {2} · {3}" -f $expectedCert.Substring(0, 8), $expectedCert.Substring(56), $repoCommit, $webCommit)
if ($repoDirty) { Write-Host '  注意：本仓库有未提交的改动（同步前端也会改 assets\www）。按 GPL 分发前请先提交，APK 才对得上源码。' -ForegroundColor Yellow }
if ($webCommit -match 'dirty') { Write-Host '  注意：前端仓库 WPEProxyCap.Web 有未提交的改动。' -ForegroundColor Yellow }
