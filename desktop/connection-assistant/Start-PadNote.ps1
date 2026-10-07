$ErrorActionPreference = "Stop"

$bundle = (Resolve-Path $PSScriptRoot).Path
$python = Join-Path $bundle "runtime\python\python.exe"
$node = Join-Path $bundle "runtime\node\node.exe"
$entry = Join-Path $bundle "app\start.py"
$skill = Join-Path $bundle "skill"
$browserCache = Join-Path $bundle "runtime\browser"
$ffmpeg = Join-Path $bundle "runtime\ffmpeg"

foreach ($required in @($python, $node, $entry, (Join-Path $skill "scripts\builtin-engine.ts"))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "便携运行目录不完整，缺少：$required`n请从 Windows x64 原生打包器重新生成目录。"
    }
}
foreach ($requiredDirectory in @($browserCache, $ffmpeg)) {
    if (-not (Test-Path -LiteralPath $requiredDirectory -PathType Container)) {
        throw "便携运行目录不完整，缺少：$requiredDirectory"
    }
}

$pythonVersion = & $python --version 2>&1
if ($LASTEXITCODE -ne 0 -or $pythonVersion -notmatch "^Python 3\.(1[0-9]|[2-9][0-9])\.") {
    throw "内置 Python 版本无效（需要 Python 3.10 或更新版本）：$pythonVersion"
}
$nodeVersion = & $node --version 2>&1
if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch "^v(2[2-9]|[3-9][0-9])\.") {
    throw "内置 Node.js 版本无效（需要 Node 22 或更新版本）：$nodeVersion"
}

$stateDir = Join-Path $env:LOCALAPPDATA "PadNote\ConnectionAssistant"
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$env:PADNOTE_CONNECTION_STATE_DIR = $stateDir
$env:PUPPETEER_CACHE_DIR = $browserCache
$env:PATH = "$ffmpeg;$env:PATH"
$adminUrl = "http://127.0.0.1:8766/"

Write-Host "正在启动 PadNote 内置视频工作流。首次启动可能需要几秒钟。"
Write-Host "管理页：$adminUrl"
$browserJob = Start-Job -ArgumentList $adminUrl -ScriptBlock {
    param($url)
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 1 -Uri $url | Out-Null
            Start-Process $url
            return
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
}

$exitCode = 1
try {
    Push-Location $bundle
    & $python $entry --no-browser --lan `
        --video-node $node `
        --video-skill-root $skill
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
    Stop-Job -Job $browserJob -ErrorAction SilentlyContinue
    Remove-Job -Job $browserJob -Force -ErrorAction SilentlyContinue
}

if ($exitCode -ne 0) {
    Write-Host -ForegroundColor Red "PadNote 助手退出（退出码 $exitCode）。请检查上方提示。"
}
exit $exitCode
