param(
  [string]$TargetPluginsDir = "$env:APPDATA\Blockbench\plugins"
)

$ErrorActionPreference = "Stop"

$source = Join-Path $PSScriptRoot "tamework_spawner_icon_batch_renderer.js"
if (-not (Test-Path $source)) {
  throw "Plugin source not found: $source"
}

if (-not (Test-Path $TargetPluginsDir)) {
  New-Item -ItemType Directory -Path $TargetPluginsDir -Force | Out-Null
}

$destination = Join-Path $TargetPluginsDir "tamework_spawner_icon_batch_renderer.js"
Copy-Item -Path $source -Destination $destination -Force

Write-Output "Installed: $destination"
