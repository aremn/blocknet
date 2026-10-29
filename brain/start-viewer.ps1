param([string]$Config, [int]$Port = 25600)
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $Config) {
    $Config = Join-Path $env:APPDATA 'PrismLauncher/instances/Blocknet Dev/minecraft/config/blocknet_ai-bridge.json'
    if (-not (Test-Path -LiteralPath $Config)) { $Config = Join-Path $repoRoot 'mod/run/config/blocknet_ai-bridge.json' }
}
node (Join-Path $PSScriptRoot 'viewer.js') --config $Config --port $Port
