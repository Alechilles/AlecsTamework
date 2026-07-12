Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Import-Module (Join-Path $PSScriptRoot "ClaimsRuntimeEvidence.psm1") -Force

function Get-ClaimsRuntimeFullPath {
    param([Parameter(Mandatory = $true)][string] $Path)
    return [IO.Path]::GetFullPath($Path)
}

function Test-ClaimsRuntimePathWithin {
    param(
        [Parameter(Mandatory = $true)][string] $Candidate,
        [Parameter(Mandatory = $true)][string] $Root
    )
    $candidatePath = (Get-ClaimsRuntimeFullPath -Path $Candidate).TrimEnd('\', '/')
    $rootPath = (Get-ClaimsRuntimeFullPath -Path $Root).TrimEnd('\', '/')
    if ($candidatePath.Equals($rootPath, [StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    $prefix = $rootPath + [IO.Path]::DirectorySeparatorChar
    return $candidatePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function Assert-ClaimsRuntimeNoReparseAncestors {
    param([Parameter(Mandatory = $true)][string] $Path, [string] $Label = "Path")
    $cursor = [IO.Path]::GetFullPath($Path)
    while (-not (Test-Path -LiteralPath $cursor)) {
        $parent = Split-Path -Path $cursor -Parent
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -ceq $cursor) { break }
        $cursor = $parent
    }
    while (Test-Path -LiteralPath $cursor) {
        $item = Get-Item -LiteralPath $cursor -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Label traverses a reparse point and is refused: '$($item.FullName)'."
        }
        $parent = Split-Path -Path $cursor -Parent
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -ceq $cursor) { break }
        $cursor = $parent
    }
}

function Assert-ClaimsRuntimeNoReparseTree {
    param(
        [Parameter(Mandatory = $true)][string] $Root,
        [string[]] $ExcludedTopLevelNames = @()
    )
    $rootPath = (Resolve-Path -LiteralPath $Root -ErrorAction Stop).Path
    $stack = [System.Collections.Generic.Stack[string]]::new()
    $stack.Push($rootPath)
    while ($stack.Count -gt 0) {
        $directory = $stack.Pop()
        foreach ($item in @(Get-ChildItem -LiteralPath $directory -Force)) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "UpgradeSaveSource active tree contains a reparse point: '$($item.FullName)'."
            }
            if ($item.PSIsContainer -and
                    -not ($directory -ceq $rootPath -and $item.Name -in $ExcludedTopLevelNames)) {
                $stack.Push($item.FullName)
            }
        }
    }
}

function Resolve-ClaimsRuntimeLeaf {
    param([string] $Path, [string] $Label)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "$Label is not a file: '$resolved'."
    }
    if ((Get-Item -LiteralPath $resolved).Length -le 0) {
        throw "$Label is empty: '$resolved'."
    }
    return $resolved
}

function Resolve-ClaimsRuntimeDirectory {
    param([string] $Path, [string] $Label)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
        throw "$Label is not a directory: '$resolved'."
    }
    return $resolved
}

function Get-ClaimsRuntimeInputs {
    param(
        [string] $BuiltArtifact,
        [string] $HytaleServerJar,
        [string] $HytaleAssets,
        [string] $JavaExecutable,
        [string] $SimpleClaimsJar,
        [string] $QuestLinesClaimsJar,
        [string] $UpgradeSaveSource,
        [string] $OutputRoot
    )

    $output = Get-ClaimsRuntimeFullPath -Path $OutputRoot
    if (Test-Path -LiteralPath $output) {
        throw "OutputRoot must be brand new and must not already exist: '$output'."
    }
    Assert-ClaimsRuntimeNoReparseAncestors -Path $output -Label "OutputRoot"
    $roaming = [Environment]::GetFolderPath([Environment+SpecialFolder]::ApplicationData)
    $liveUserData = Join-Path $roaming "Hytale\UserData"
    if (Test-ClaimsRuntimePathWithin -Candidate $output -Root $liveUserData) {
        throw "OutputRoot must not be inside the live Hytale UserData runtime: '$liveUserData'."
    }

    $assetsFull = Get-ClaimsRuntimeFullPath -Path $HytaleAssets
    if (-not (Test-Path -LiteralPath $assetsFull)) {
        throw "Hytale assets path does not exist: '$assetsFull'."
    }
    $assetsItem = Get-Item -LiteralPath $assetsFull
    if (-not $assetsItem.PSIsContainer -and $assetsItem.Length -le 0) {
        throw "Hytale assets file is empty: '$assetsFull'."
    }

    $upgrade = Resolve-ClaimsRuntimeDirectory -Path $UpgradeSaveSource -Label "UpgradeSaveSource"
    Assert-ClaimsRuntimeNoReparseAncestors -Path $upgrade -Label "UpgradeSaveSource"
    if (Test-ClaimsRuntimePathWithin -Candidate $upgrade -Root $liveUserData) {
        throw "UpgradeSaveSource must be a stopped copy outside the live Hytale UserData runtime."
    }
    if (Test-ClaimsRuntimePathWithin -Candidate $output -Root $upgrade) {
        throw "OutputRoot must not be nested inside UpgradeSaveSource."
    }
    Assert-ClaimsRuntimeNoReparseTree -Root $upgrade -ExcludedTopLevelNames @(
        "logs", "temp", "tmp", "cache", "backup", "assetEditor",
        "appdata", "localappdata", "user-home"
    )
    $sourceDatabase = Join-Path $upgrade "universe\Tamework\Data\tamework.sqlite"
    if (-not (Test-Path -LiteralPath $sourceDatabase -PathType Leaf)) {
        throw "UpgradeSaveSource must be a save/server root containing universe\Tamework\Data\tamework.sqlite."
    }
    if ((Get-Item -LiteralPath $sourceDatabase).Length -le 0) {
        throw "UpgradeSaveSource database is empty: '$sourceDatabase'."
    }

    return [pscustomobject][ordered]@{
        builtArtifact = Resolve-ClaimsRuntimeLeaf -Path $BuiltArtifact -Label "BuiltArtifact"
        hytaleServerJar = Resolve-ClaimsRuntimeLeaf -Path $HytaleServerJar -Label "HytaleServerJar"
        hytaleAssets = $assetsItem.FullName
        javaExecutable = Resolve-ClaimsRuntimeLeaf -Path $JavaExecutable -Label "JavaExecutable"
        simpleClaimsJar = Resolve-ClaimsRuntimeLeaf -Path $SimpleClaimsJar -Label "SimpleClaimsJar"
        questLinesClaimsJar = Resolve-ClaimsRuntimeLeaf -Path $QuestLinesClaimsJar -Label "QuestLinesClaimsJar"
        upgradeSaveSource = $upgrade
        upgradeSourceDatabase = (Resolve-Path -LiteralPath $sourceDatabase).Path
        outputRoot = $output
        liveUserDataRoot = $liveUserData
    }
}

function Assert-ClaimsRuntimeManifests {
    param([psobject] $Inputs)
    $tamework = Read-ClaimsRuntimeManifest -JarPath $Inputs.builtArtifact
    $simple = Read-ClaimsRuntimeManifest -JarPath $Inputs.simpleClaimsJar
    $quest = Read-ClaimsRuntimeManifest -JarPath $Inputs.questLinesClaimsJar
    $repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
    $pomVersionNode = Select-Xml -LiteralPath (Join-Path $repoRoot "pom.xml") `
        -XPath "/*[local-name()='project']/*[local-name()='version']"
    $expectedVersion = [string]$pomVersionNode.Node.InnerText
    if ($tamework.group -cne "Alechilles" -or $tamework.name -cne "Alec's Tamework!" -or
            $tamework.main -cne "com.alechilles.alecstamework.Tamework" -or
            $tamework.version -cne $expectedVersion) {
        throw "BuiltArtifact manifest identity/Main/version does not match this Tamework checkout ($expectedVersion)."
    }
    if ($tamework.optionalDependencies["Buuz135:SimpleClaims"] -cne ">=1.0.38 <1.1.0" -or
            $tamework.optionalDependencies["net.evilcraft:QuestLinesClaims"] -cne "=1.3.1") {
        throw "BuiltArtifact manifest claim-provider dependency ranges do not match the verified contracts."
    }
    if ($simple.group -cne "Buuz135" -or $simple.name -cne "SimpleClaims" -or $simple.version -cne "1.0.38") {
        throw "SimpleClaimsJar must be Buuz135:SimpleClaims version 1.0.38."
    }
    if ($quest.group -cne "net.evilcraft" -or $quest.name -cne "QuestLinesClaims" -or $quest.version -cne "1.3.1") {
        throw "QuestLinesClaimsJar must be net.evilcraft:QuestLinesClaims version 1.3.1."
    }
    if (-not (Test-ClaimsRuntimeZipEntry -JarPath $Inputs.builtArtifact -EntryName "org/sqlite/JDBC.class")) {
        throw "BuiltArtifact does not embed org/sqlite/JDBC.class; read-only database evidence cannot run."
    }
    if (-not (Test-ClaimsRuntimeZipEntry -JarPath $Inputs.hytaleServerJar `
            -EntryName "com/hypixel/hytale/server/core/HytaleServer.class")) {
        throw "HytaleServerJar does not contain the expected server entry point."
    }
    Resolve-ClaimsRuntimeJavap -JavaExecutable $Inputs.javaExecutable | Out-Null
    return [pscustomobject][ordered]@{
        tamework = $tamework
        simpleClaims = $simple
        questLinesClaims = $quest
    }
}

function Get-ClaimsRuntimeTrackedInputState {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][psobject] $Inputs)

    $paths = [ordered]@{
        builtArtifact = $Inputs.builtArtifact
        hytaleServerJar = $Inputs.hytaleServerJar
        javaExecutable = $Inputs.javaExecutable
        simpleClaimsJar = $Inputs.simpleClaimsJar
        questLinesClaimsJar = $Inputs.questLinesClaimsJar
        upgradeSourceDatabase = $Inputs.upgradeSourceDatabase
    }
    if (Test-Path -LiteralPath $Inputs.hytaleAssets -PathType Leaf) {
        $paths.hytaleAssets = $Inputs.hytaleAssets
    }
    foreach ($sidecar in @(
            @{ name = "upgradeSourceDatabaseWal"; suffix = "-wal" },
            @{ name = "upgradeSourceDatabaseShm"; suffix = "-shm" }
        )) {
        $sidecarPath = $Inputs.upgradeSourceDatabase + $sidecar.suffix
        if (Test-Path -LiteralPath $sidecarPath -PathType Leaf) {
            $paths[$sidecar.name] = $sidecarPath
        }
    }
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $paths.GetEnumerator()) {
        $item = Get-Item -LiteralPath $entry.Value -ErrorAction Stop
        $entries.Add([pscustomobject][ordered]@{
            name = $entry.Key
            path = $item.FullName
            length = $item.Length
            lastWriteTimeUtc = $item.LastWriteTimeUtc.ToString("o")
            sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash
        })
    }
    return @($entries)
}

function Compare-ClaimsRuntimeTrackedInputState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object[]] $Before,
        [Parameter(Mandatory = $true)][object[]] $After
    )

    $checks = [System.Collections.Generic.List[object]]::new()
    foreach ($beforeEntry in $Before) {
        $afterEntry = @($After | Where-Object name -CEQ $beforeEntry.name)
        $passed = $afterEntry.Count -eq 1 -and
            $afterEntry[0].path -ceq $beforeEntry.path -and
            $afterEntry[0].length -eq $beforeEntry.length -and
            $afterEntry[0].lastWriteTimeUtc -ceq $beforeEntry.lastWriteTimeUtc -and
            $afterEntry[0].sha256 -ceq $beforeEntry.sha256
        $checks.Add([pscustomobject][ordered]@{
            name = $beforeEntry.name
            passed = $passed
            before = $beforeEntry
            after = if ($afterEntry.Count -eq 1) { $afterEntry[0] } else { $null }
        })
    }
    return [pscustomobject][ordered]@{
        passed = $Before.Count -eq $After.Count -and -not ($checks | Where-Object { -not $_.passed })
        checks = @($checks)
    }
}

Export-ModuleMember -Function @(
    "Get-ClaimsRuntimeFullPath",
    "Test-ClaimsRuntimePathWithin",
    "Assert-ClaimsRuntimeNoReparseAncestors",
    "Assert-ClaimsRuntimeNoReparseTree",
    "Get-ClaimsRuntimeInputs",
    "Assert-ClaimsRuntimeManifests",
    "Get-ClaimsRuntimeTrackedInputState",
    "Compare-ClaimsRuntimeTrackedInputState"
)
