# Installs the optional voice pieces for Blocknet into %LOCALAPPDATA%\blocknet:
#   whisper.cpp server (speech to text; CUDA build when an NVIDIA GPU is present, else CPU),
#   a Whisper model (large-v3-turbo on GPU, small on CPU), Piper (text to speech) and a Russian voice.
# Every download is checked against its published SHA-256. Re-running skips what is already there.
# The brain finds these paths by itself; players also need Simple Voice Chat in the game.
#
#   powershell -ExecutionPolicy Bypass -File brain/setup-voice.ps1            # auto
#   powershell -ExecutionPolicy Bypass -File brain/setup-voice.ps1 -Cpu       # force the CPU build
#   powershell -ExecutionPolicy Bypass -File brain/setup-voice.ps1 -Model ggml-base-q5_1.bin -Voice irina
#   powershell -ExecutionPolicy Bypass -File brain/setup-voice.ps1 -Voices all   # every Russian voice; switch in game with /blocknet voice
param([switch]$Cpu, [string]$Model = '', [string]$Voice = 'dmitri', [string[]]$Voices = @(), [string]$Root = (Join-Path $env:LOCALAPPDATA 'blocknet'))
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = $Root

$whisperBuild = 'b5454'
$builds = @{
  cuda = @{ file = 'whisper-bin-win-cuda-12.4.0-x64.zip'; sha = 'afef0b881c500958921c3f5523b50e59ee2ec9b6f5cbd25b324c51ed308a957a' }
  cpu  = @{ file = 'whisper-bin-x64.zip'; sha = '6ba69e3482d7826214f90a6a9c84ca07782aec1e1d0c6a7c30c994fd5d816ccb' }
}
$models = @{
  'ggml-large-v3-turbo-q8_0.bin' = '317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1'
  'ggml-large-v3-turbo-q5_0.bin' = '394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2'
  'ggml-small-q5_1.bin'          = 'ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb'
  'ggml-base-q5_1.bin'           = '422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898'
}
$voices = @{
  dmitri = 'f073356ebc4bd0f80c5af58df2953a5988bd5bdab1eb38635ce960b071fbefcb'
  denis  = '15fab56e11a097858ee115545d0f697fc2a316c41a291a5362349fb870411b0a'
  irina  = '8ff38212d23da300bbe3705c645e6e5b9475f0bfde01558eb17813e22acaaaaa'
  ruslan = '72a5f88e0b20928064eb45d88e1daa21f8af62d18613580d32cbb4aed48dcf7f'
}

function Get-Checked([string]$url, [string]$path, [string]$sha) {
  if ((Test-Path $path) -and (-not $sha -or (Get-FileHash $path -Algorithm SHA256).Hash -eq $sha)) { Write-Host "ok   $path"; return }
  New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
  Write-Host "get  $url"
  Invoke-WebRequest -Uri $url -OutFile $path
  if ($sha -and (Get-FileHash $path -Algorithm SHA256).Hash -ne $sha) { Remove-Item $path; throw "checksum mismatch for $path" }
}

$gpu = -not $Cpu -and (Get-CimInstance Win32_VideoController | Where-Object { $_.Name -match 'NVIDIA' })
$kind = if ($gpu) { 'cuda' } else { 'cpu' }
if (-not $Model) { $Model = if ($gpu) { 'ggml-large-v3-turbo-q8_0.bin' } else { 'ggml-small-q5_1.bin' } }
Write-Host "Speech recognition: $kind build, model $Model"

$build = $builds[$kind]
$zip = Join-Path $root "whisper\dl\$($build.file)"
Get-Checked "https://github.com/ggml-org/whisper.cpp/releases/download/$whisperBuild/$($build.file)" $zip $build.sha
$dest = Join-Path $root "whisper\$kind"
if (-not (Test-Path (Join-Path $dest 'Release\whisper-server.exe'))) { Expand-Archive $zip $dest -Force }
Get-Checked "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$Model" (Join-Path $root "whisper\models\$Model") $models[$Model]

$piperZip = Join-Path $root 'piper\dl\piper_windows_amd64.zip'
Get-Checked 'https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip' $piperZip ''
if (-not (Test-Path (Join-Path $root 'piper\piper\piper.exe'))) { Expand-Archive $piperZip (Join-Path $root 'piper') -Force }
$wanted = if ($Voices -contains 'all') { @($voices.Keys | Sort-Object) } elseif ($Voices.Count) { $Voices } else { @($Voice) }
foreach ($name in $wanted) {
  if (-not $voices.ContainsKey($name)) { throw "unknown voice $name; known: $($voices.Keys -join ', ')" }
  $voiceBase = "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/$name/medium/ru_RU-$name-medium.onnx"
  Get-Checked $voiceBase (Join-Path $root "piper\voices\ru_RU-$name-medium.onnx") $voices[$name]
  Get-Checked "$voiceBase.json" (Join-Path $root "piper\voices\ru_RU-$name-medium.onnx.json") ''
}

Write-Host "Voice ready in $root. Install Simple Voice Chat in the game; hold its push-to-talk key near the bot."
