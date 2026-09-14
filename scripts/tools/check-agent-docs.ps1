param(
    [string] $Root = (Get-Location).Path,
    [switch] $CheckGeneratedIndex
)

$ErrorActionPreference = "Stop"

function Assert-PathExists([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing required path: $Path"
    }
}

$repoRoot = (Resolve-Path $Root).Path

$requiredFiles = @(
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

$agentDocs = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "docs/agents") -Filter "*.md" -File)
# Local policy is Git-ignored and may not be present in a fresh checkout.
$agentsPath = Join-Path $repoRoot "AGENTS.md"
if (Test-Path -LiteralPath $agentsPath) {
    $agentDocs += Get-Item -LiteralPath $agentsPath
}

foreach ($doc in $agentDocs) {
    $content = Get-Content -LiteralPath $doc.FullName -Raw
    foreach ($link in [regex]::Matches($content, '\[[^\]]*\]\(([^)]+)\)')) {
        $target = $link.Groups[1].Value.Trim().Trim('<', '>')
        # External URLs, absolute local paths, and same-page anchors are not repo links.
        if ($target -match '^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|[/\\]|#)') {
            continue
        }
        $relativePath = [uri]::UnescapeDataString(($target -split '[#?]', 2)[0])
        if ($relativePath -and -not (Test-Path -LiteralPath (Join-Path $doc.DirectoryName $relativePath))) {
            throw "Broken local link in $($doc.FullName): $target"
        }
    }
}

if ($CheckGeneratedIndex) {
    & (Join-Path $repoRoot "scripts/tools/build-agent-index.ps1") -Root $repoRoot -Check
}

Write-Host "Agent docs checks passed."

