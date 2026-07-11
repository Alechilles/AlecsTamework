[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $Jar
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ExpectedGroup = "net.evilcraft"
$ExpectedName = "QuestLinesClaims"
$ExpectedVersion = "1.3.1"
$ExpectedMain = "net.evilcraft.questlinesclaims.QuestLinesClaimsPlugin"

function Resolve-Javap {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates.Add((Join-Path $env:JAVA_HOME "bin\javap.exe"))
        $candidates.Add((Join-Path $env:JAVA_HOME "bin\javap"))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JDK_HOME)) {
        $candidates.Add((Join-Path $env:JDK_HOME "bin\javap.exe"))
        $candidates.Add((Join-Path $env:JDK_HOME "bin\javap"))
    }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $command = Get-Command javap -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    throw "Unable to find javap. Configure JAVA_HOME/JDK_HOME or add a full JDK's bin directory to PATH."
}

function Read-PublicClassContract {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Javap,
        [Parameter(Mandatory = $true)]
        [string] $JarPath,
        [Parameter(Mandatory = $true)]
        [string] $ClassName
    )

    $output = & $Javap -classpath $JarPath -public $ClassName 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "javap failed for $ClassName with exit code $LASTEXITCODE`n$output"
    }
    return $output
}

function Assert-ContractPattern {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ClassName,
        [Parameter(Mandatory = $true)]
        [string] $Output,
        [Parameter(Mandatory = $true)]
        [string] $Pattern,
        [Parameter(Mandatory = $true)]
        [string] $Description,
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]] $Failures
    )

    if (-not [regex]::IsMatch($Output, $Pattern, [Text.RegularExpressions.RegexOptions]::CultureInvariant)) {
        $Failures.Add("$ClassName is missing $Description")
    }
}

$jarPath = (Resolve-Path -LiteralPath $Jar -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "QuestLines Claims jar is not a file: $jarPath"
}

$classContracts = [ordered]@{
    "net.evilcraft.questlinesclaims.QuestLinesClaimsPlugin" = @(
        @{ Pattern = 'public static net\.evilcraft\.questlinesclaims\.QuestLinesClaimsPlugin getInstance\(\);'; Description = 'public static getInstance()' },
        @{ Pattern = 'public net\.evilcraft\.questlinesclaims\.api\.QuestLinesClaimsAPI getApi\(\);'; Description = 'public getApi()' }
    )
    "net.evilcraft.questlinesclaims.api.QuestLinesClaimsAPI" = @(
        @{ Pattern = 'public net\.evilcraft\.questlinesclaims\.data\.PlayerClaim getClaimAtBlock\(java\.lang\.String, int, int\);'; Description = 'getClaimAtBlock(String, int, int)' }
    )
    "net.evilcraft.questlinesclaims.data.PlayerClaim" = @(
        @{ Pattern = 'public int getClaimId\(\);'; Description = 'getClaimId()' },
        @{ Pattern = 'public java\.util\.UUID getOwnerUuid\(\);'; Description = 'getOwnerUuid()' },
        @{ Pattern = 'public net\.evilcraft\.questlinesclaims\.data\.ClaimOwnerType getOwnerType\(\);'; Description = 'getOwnerType()' },
        @{ Pattern = 'public java\.lang\.String getWorldName\(\);'; Description = 'getWorldName()' },
        @{ Pattern = 'public java\.util\.Set<net\.evilcraft\.questlinesclaims\.data\.ChunkCoord> getChunks\(\);'; Description = 'getChunks() returning Set<ChunkCoord>' }
    )
    "net.evilcraft.questlinesclaims.data.ChunkCoord" = @(
        @{ Pattern = 'public int getChunkX\(\);'; Description = 'getChunkX()' },
        @{ Pattern = 'public int getChunkZ\(\);'; Description = 'getChunkZ()' },
        @{ Pattern = 'public java\.lang\.String getWorldName\(\);'; Description = 'getWorldName()' }
    )
    "net.evilcraft.questlinesclaims.data.ClaimOwnerType" = @(
        @{ Pattern = 'public static final net\.evilcraft\.questlinesclaims\.data\.ClaimOwnerType PLAYER;'; Description = 'PLAYER enum constant' },
        @{ Pattern = 'public static final net\.evilcraft\.questlinesclaims\.data\.ClaimOwnerType GUILD;'; Description = 'GUILD enum constant' },
        @{ Pattern = 'public static final net\.evilcraft\.questlinesclaims\.data\.ClaimOwnerType NATION;'; Description = 'NATION enum constant' },
        @{ Pattern = 'public static final net\.evilcraft\.questlinesclaims\.data\.ClaimOwnerType CITY;'; Description = 'CITY enum constant' }
    )
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $manifestEntries = @($archive.Entries | Where-Object { $_.FullName -ceq "manifest.json" })
    if ($manifestEntries.Count -ne 1) {
        throw "Expected exactly one root manifest.json, found $($manifestEntries.Count)."
    }

    $reader = [System.IO.StreamReader]::new($manifestEntries[0].Open())
    try {
        $manifest = $reader.ReadToEnd() | ConvertFrom-Json
    }
    finally {
        $reader.Dispose()
    }

    $manifestChecks = [ordered]@{
        Group = $ExpectedGroup
        Name = $ExpectedName
        Version = $ExpectedVersion
        Main = $ExpectedMain
    }
    foreach ($entry in $manifestChecks.GetEnumerator()) {
        $actual = [string] $manifest.($entry.Key)
        if ($actual -cne $entry.Value) {
            throw "manifest.json $($entry.Key) must be '$($entry.Value)', found '$actual'."
        }
    }

    $entryNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $archive.Entries) {
        [void] $entryNames.Add($entry.FullName)
    }
    foreach ($className in $classContracts.Keys) {
        $classEntry = $className.Replace('.', '/') + ".class"
        if (-not $entryNames.Contains($classEntry)) {
            throw "Jar is missing required class entry $classEntry."
        }
    }
}
finally {
    $archive.Dispose()
}

$javap = Resolve-Javap
$failures = [System.Collections.Generic.List[string]]::new()
foreach ($className in $classContracts.Keys) {
    $output = Read-PublicClassContract -Javap $javap -JarPath $jarPath -ClassName $className
    foreach ($contract in $classContracts[$className]) {
        Assert-ContractPattern `
            -ClassName $className `
            -Output $output `
            -Pattern $contract.Pattern `
            -Description $contract.Description `
            -Failures $failures
    }
}

if ($failures.Count -gt 0) {
    $details = ($failures | ForEach-Object { " - $_" }) -join [Environment]::NewLine
    throw "QuestLines Claims 1.3.1 contract verification failed:$([Environment]::NewLine)$details"
}

Write-Output "Verified QuestLines Claims $ExpectedVersion contract: $jarPath"
