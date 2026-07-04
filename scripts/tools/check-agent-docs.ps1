param(
    [string] $Root = (Get-Location).Path
)

$ErrorActionPreference = "Stop"

function Assert-PathExists([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing required path: $Path"
    }
}

function Assert-Contains([string] $Path, [string] $Text) {
    $content = Get-Content -LiteralPath $Path -Raw
    if (-not $content.Contains($Text)) {
        throw "Expected '$Path' to contain '$Text'"
    }
}

$repoRoot = (Resolve-Path $Root).Path

$requiredFiles = @(
    "AGENTS.md",
    "docs/agents/agent-map.md",
    "docs/agents/guardrails.md",
    "docs/agents/runtime-vs-source-checklist.md",
    "docs/agents/lessons-index.md",
    "docs/agents/generated-index.md",
    "scripts/tools/build-agent-index.ps1",
    "scripts/tools/check-agent-docs.ps1",
    "src/test/java/com/alechilles/alecstamework/architecture/EcsWriteSafetyGuardTest.java",
    "src/test/java/com/alechilles/alecstamework/architecture/AsyncThreadSafetyGuardTest.java"
)

foreach ($file in $requiredFiles) {
    Assert-PathExists (Join-Path $repoRoot $file)
}

$agentsPath = Join-Path $repoRoot "AGENTS.md"
Assert-Contains $agentsPath "docs/agents/agent-map.md"
Assert-Contains $agentsPath "docs/agents/guardrails.md"
Assert-Contains $agentsPath "docs/agents/runtime-vs-source-checklist.md"
Assert-Contains $agentsPath "docs/agents/lessons-index.md"
Assert-Contains $agentsPath ".\scripts\tools\build-agent-index.ps1"
Assert-Contains $agentsPath ".\scripts\tools\check-agent-docs.ps1"

$indexPath = Join-Path $repoRoot "docs/agents/generated-index.md"
$indexContent = Get-Content -LiteralPath $indexPath -Raw
if ($indexContent -match "Microsoft\.PowerShell|@\{|`\$\(") {
    throw "Generated index appears to contain leaked PowerShell expressions."
}

$lessonRoot = "C:\Users\22ale\AppData\Roaming\Hytale\My Mod Docs\Lessons Learned"
Assert-PathExists $lessonRoot

& (Join-Path $repoRoot "scripts/tools/build-agent-index.ps1") -Root $repoRoot -Check

Write-Host "Agent docs checks passed."

