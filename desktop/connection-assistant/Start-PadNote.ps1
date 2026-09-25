$ErrorActionPreference = "Stop"

$rawDistros = & wsl.exe --list --quiet
if ($LASTEXITCODE -ne 0) {
    throw "无法读取 WSL 发行版列表（退出码 $LASTEXITCODE）。"
}
$distros = @($rawDistros | ForEach-Object { ($_ -replace "`0", "").Trim() } | Where-Object { $_ })
if ($distros.Count -eq 0) {
    throw "未发现 WSL。请先安装 WSL，并在安装 Hermes 的发行版中运行本助手。"
}

Write-Host "请选择 Hermes 所在的 WSL 发行版："
for ($index = 0; $index -lt $distros.Count; $index++) {
    Write-Host "  [$($index + 1)] $($distros[$index])"
}
$selected = 0
if ($distros.Count -gt 1) {
    $answer = Read-Host "输入序号"
    $selected = [int]$answer - 1
    if ($selected -lt 0 -or $selected -ge $distros.Count) {
        throw "发行版序号无效。"
    }
}
$distro = $distros[$selected]
$wslDirectory = (& wsl.exe -d $distro -- wslpath -a $PSScriptRoot).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "无法把助手目录转换为 WSL 路径（退出码 $LASTEXITCODE）。"
}
if (-not $wslDirectory) {
    throw "无法把助手目录转换为 WSL 路径。"
}

Write-Host "将在 $distro 中启动 PadNote 助手。Hermes 也应运行在这个发行版。"
$browserJob = Start-Job -ScriptBlock {
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 1 -Uri "http://127.0.0.1:8766/" | Out-Null
            Start-Process "http://127.0.0.1:8766/"
            return
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
}
try {
    & wsl.exe -d $distro --cd $wslDirectory --exec python3 ./start.py --no-browser
    $assistantExitCode = $LASTEXITCODE
} finally {
    Stop-Job -Job $browserJob -ErrorAction SilentlyContinue
    Remove-Job -Job $browserJob -Force -ErrorAction SilentlyContinue
}
if ($assistantExitCode -ne 0) {
    Write-Host -ForegroundColor Red "PadNote 助手退出（退出码 $assistantExitCode）。请检查上方提示。"
}
exit $assistantExitCode
