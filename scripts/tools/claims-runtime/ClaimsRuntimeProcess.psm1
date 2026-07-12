Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-ClaimsRuntimeJson {
    param([string] $Path, [object] $Value)
    $parent = Split-Path -Path $Path -Parent
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    $json = $Value | ConvertTo-Json -Depth 30
    [IO.File]::WriteAllText(
        [IO.Path]::GetFullPath($Path),
        $json + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false)
    )
}

function Write-ClaimsRuntimeText {
    param([string] $Path, [AllowEmptyString()][string] $Value)
    $parent = Split-Path -Path $Path -Parent
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText(
        [IO.Path]::GetFullPath($Path),
        $Value,
        [Text.UTF8Encoding]::new($false)
    )
}

function Copy-ClaimsRuntimeDirectoryTree {
    param([string] $Source, [string] $Destination, [switch] $FilterPluginPayload)
    if ($FilterPluginPayload -and
            ((Test-Path -LiteralPath (Join-Path $Source "manifest.json")) -or
            (Test-Path -LiteralPath (Join-Path $Source "manifest-assets.json")))) {
        return
    }
    if (-not (Test-Path -LiteralPath $Destination)) {
        New-Item -ItemType Directory -Path $Destination | Out-Null
    }
    foreach ($item in @(Get-ChildItem -LiteralPath $Source -Force)) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing to copy reparse point from UpgradeSaveSource: '$($item.FullName)'."
        }
        $target = Join-Path $Destination $item.Name
        if ($item.PSIsContainer) {
            Copy-ClaimsRuntimeDirectoryTree -Source $item.FullName -Destination $target `
                -FilterPluginPayload:$FilterPluginPayload
            continue
        }
        if ($FilterPluginPayload -and $item.Extension -in @(
                ".jar", ".zip", ".class", ".dll", ".so", ".dylib", ".exe", ".com"
            )) { continue }
        Copy-Item -LiteralPath $item.FullName -Destination $target -Force
    }
}

function Copy-ClaimsRuntimeSaveRoot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string] $Source,
        [Parameter(Mandatory = $true)][string] $Destination
    )

    # Runtime artifacts and caches must be created by this run, never inherited.
    $excludedNames = @(
        "logs", "temp", "tmp", "cache", "backup", "assetEditor",
        "appdata", "localappdata", "user-home"
    )
    foreach ($item in @(Get-ChildItem -LiteralPath $Source -Force)) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing to copy reparse point from UpgradeSaveSource: '$($item.FullName)'."
        }
        if ($item.Name -in $excludedNames) { continue }
        if ($item.PSIsContainer -and $item.Name -ieq "mods") {
            Copy-ClaimsRuntimeDirectoryTree -Source $item.FullName `
                -Destination (Join-Path $Destination "mods") -FilterPluginPayload
            continue
        }
        if ($item.PSIsContainer) {
            Copy-ClaimsRuntimeDirectoryTree -Source $item.FullName `
                -Destination (Join-Path $Destination $item.Name)
        } else {
            Copy-Item -LiteralPath $item.FullName -Destination $Destination -Force
        }
    }
}

function Initialize-ClaimsRuntimeScenario {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][psobject] $Scenario,
        [Parameter(Mandatory = $true)][psobject] $Inputs,
        [Parameter(Mandatory = $true)][psobject] $Manifests,
        [Parameter(Mandatory = $true)][psobject] $Settings
    )

    $scenarioRoot = Join-Path $Inputs.outputRoot (Join-Path "scenarios" $Scenario.id)
    $scenarioHome = Join-Path $scenarioRoot "home"
    $evidence = Join-Path $scenarioRoot "evidence"
    foreach ($directory in @($scenarioRoot, $scenarioHome, $evidence)) {
        New-Item -ItemType Directory -Path $directory | Out-Null
    }
    if ($Scenario.copiedUpgrade) {
        Copy-ClaimsRuntimeSaveRoot -Source $Inputs.upgradeSaveSource -Destination $scenarioHome
    }

    $mods = Join-Path $scenarioHome "mods"
    $universe = Join-Path $scenarioHome "universe"
    if (-not (Test-Path -LiteralPath $universe)) {
        New-Item -ItemType Directory -Path $universe | Out-Null
    }
    if (-not (Test-Path -LiteralPath $mods)) {
        New-Item -ItemType Directory -Path $mods | Out-Null
    }
    $inheritedPayloads = @(Get-ChildItem -LiteralPath $mods -Recurse -File -ErrorAction Stop |
        Where-Object { $_.Extension -in @(
                ".jar", ".zip", ".class", ".dll", ".so", ".dylib", ".exe", ".com"
            ) -or $_.Name -in @("manifest.json", "manifest-assets.json") })
    if ($inheritedPayloads.Count -gt 0) {
        throw "Inherited plugin payloads survived isolated staging: $($inheritedPayloads.FullName -join ', ')."
    }
    $worlds = Join-Path $universe "worlds"
    if (-not (Test-Path -LiteralPath $worlds)) {
        New-Item -ItemType Directory -Path $worlds | Out-Null
    }

    $databasePath = Join-Path $universe "Tamework\Data\tamework.sqlite"
    $upgradeCopyEvidence = $null
    $preexistingPreV6Backups = @()
    if ($Scenario.copiedUpgrade) {
        if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
            throw "Copied upgrade scenario is missing its database at '$databasePath'."
        }
        $snapshotFiles = [System.Collections.Generic.List[object]]::new()
        foreach ($suffix in @("", "-wal", "-shm")) {
            $sourceFile = $Inputs.upgradeSourceDatabase + $suffix
            $copiedFile = $databasePath + $suffix
            $sourceExists = Test-Path -LiteralPath $sourceFile -PathType Leaf
            $copyExists = Test-Path -LiteralPath $copiedFile -PathType Leaf
            if ($sourceExists -ne $copyExists) {
                throw "Copied upgrade database snapshot differs for sidecar '$suffix'."
            }
            if (-not $sourceExists) { continue }
            $sourceItem = Get-Item -LiteralPath $sourceFile
            $copyItem = Get-Item -LiteralPath $copiedFile
            $sourceHash = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash
            $copyHash = (Get-FileHash -LiteralPath $copiedFile -Algorithm SHA256).Hash
            if ($sourceItem.Length -ne $copyItem.Length -or $sourceHash -cne $copyHash) {
                throw "Copied upgrade database snapshot hash differs for sidecar '$suffix'."
            }
            $snapshotFiles.Add([pscustomobject][ordered]@{
                suffix = $suffix
                sourcePath = $sourceFile
                copiedPath = $copiedFile
                length = $sourceItem.Length
                sourceSha256 = $sourceHash
                copiedSha256 = $copyHash
            })
        }
        $upgradeCopyEvidence = [pscustomobject][ordered]@{
            sourceDatabase = $Inputs.upgradeSourceDatabase
            copiedDatabase = $databasePath
            snapshotFiles = @($snapshotFiles)
            matchedBeforeStartup = $true
        }
        $preexistingPreV6Backups = @(Get-ChildItem -LiteralPath (Split-Path $databasePath -Parent) `
            -File -Filter "tamework_pre_v6_*.sqlite.bak" | ForEach-Object {
                [pscustomobject][ordered]@{
                    path = $_.FullName
                    length = $_.Length
                    sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
                }
            })
    }

    $stagedTamework = Join-Path $mods "tamework-under-test.jar"
    Copy-Item -LiteralPath $Inputs.builtArtifact -Destination $stagedTamework
    $stagedProviders = [System.Collections.Generic.List[string]]::new()
    if ($Scenario.providerKinds -contains "simpleclaims") {
        $destination = Join-Path $mods "SimpleClaims-1.0.38.jar"
        Copy-Item -LiteralPath $Inputs.simpleClaimsJar -Destination $destination
        $stagedProviders.Add($destination)
    }
    if ($Scenario.providerKinds -contains "questlines") {
        $destination = Join-Path $mods "questlines-claims-1.3.1.jar"
        Copy-Item -LiteralPath $Inputs.questLinesClaimsJar -Destination $destination
        $stagedProviders.Add($destination)
    }

    $settingsPath = Join-Path $universe "Tamework\Settings\tamework-settings.json"
    Write-ClaimsRuntimeJson -Path $settingsPath -Value $Settings
    $settingsReadBack = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
    $settingsConfiguredActive = $settingsReadBack.population.limitPerPlayerOwnedTotal -eq 3 -and
        $settingsReadBack.population.perPlayerLimitScope -ceq "Global" -and
        $settingsReadBack.simpleClaims.provider -ceq $Scenario.providerSetting -and
        $settingsReadBack.simpleClaims.simpleClaimsEnabled -and
        $settingsReadBack.simpleClaims.limitPerClaimChunk -eq 2 -and
        $settingsReadBack.simpleClaims.limitPerClaimTotal -eq 6 -and
        $settingsReadBack.simpleClaims.breedingRequiresClaim -and
        $settingsReadBack.simpleClaims.protectTamedFromNonMembers
    if (-not $settingsConfiguredActive) {
        throw "Staged claims runtime settings did not read back with every required rule active."
    }
    $expectedProviderIds = [System.Collections.Generic.List[string]]::new()
    if ($Scenario.providerKinds -contains "simpleclaims") {
        $expectedProviderIds.Add($Manifests.simpleClaims.pluginId)
    }
    if ($Scenario.providerKinds -contains "questlines") {
        $expectedProviderIds.Add($Manifests.questLinesClaims.pluginId)
    }
    $allProviderIds = @($Manifests.simpleClaims.pluginId, $Manifests.questLinesClaims.pluginId)
    return [pscustomobject][ordered]@{
        plan = $Scenario
        scenarioRoot = $scenarioRoot
        home = $scenarioHome
        evidence = $evidence
        mods = $mods
        universe = $universe
        worlds = $worlds
        settingsPath = $settingsPath
        settings = $Settings
        settingsReadBack = $settingsReadBack
        settingsSha256 = (Get-FileHash -LiteralPath $settingsPath -Algorithm SHA256).Hash
        settingsConfiguredActive = $settingsConfiguredActive
        settingsAssertionScope = "configured-and-read-back; runtime application remains an operation gate"
        databasePath = $databasePath
        upgradeCopyEvidence = $upgradeCopyEvidence
        preexistingPreV6Backups = $preexistingPreV6Backups
        stagedTamework = $stagedTamework
        stagedProviders = @($stagedProviders)
        expectedPluginIds = @($Manifests.tamework.pluginId) + @($expectedProviderIds)
        forbiddenProviderIds = @($allProviderIds | Where-Object { $_ -notin $expectedProviderIds })
    }
}

function Format-ClaimsRuntimeCommandArgument {
    param([string] $Argument)
    if ($Argument -notmatch '[\s"]') { return $Argument }
    return '"' + ($Argument -replace '"', '\"') + '"'
}

function Test-ClaimsRuntimeLogMarker {
    param([string] $LogRoot, [string] $Marker)
    if (-not (Test-Path -LiteralPath $LogRoot)) { return $false }
    foreach ($file in @(Get-ChildItem -LiteralPath $LogRoot -Recurse -File -ErrorAction SilentlyContinue)) {
        if (Select-String -LiteralPath $file.FullName -SimpleMatch $Marker -Quiet -ErrorAction SilentlyContinue) {
            return $true
        }
    }
    return $false
}

function Invoke-ClaimsRuntimeServerProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][psobject] $Context,
        [Parameter(Mandatory = $true)][psobject] $Inputs,
        [Parameter(Mandatory = $true)][int] $DwellSeconds,
        [Parameter(Mandatory = $true)][int] $StartupTimeoutSeconds,
        [Parameter(Mandatory = $true)][int] $ShutdownTimeoutSeconds,
        [string] $ExecutableOverride,
        [string[]] $ArgumentOverride
    )

    $temp = Join-Path $Context.home "temp"
    $userHome = Join-Path $Context.home "user-home"
    $appData = Join-Path $Context.home "appdata"
    $localAppData = Join-Path $Context.home "localappdata"
    foreach ($directory in @($temp, $userHome, $appData, $localAppData)) {
        New-Item -ItemType Directory -Path $directory | Out-Null
    }
    $arguments = if ($PSBoundParameters.ContainsKey("ArgumentOverride")) {
        @($ArgumentOverride)
    } else {
        @(
            "--enable-native-access=ALL-UNNAMED",
            "-Duser.home=$userHome",
            "-Djava.io.tmpdir=$temp",
            "-jar", $Inputs.hytaleServerJar,
            "--assets", $Inputs.hytaleAssets,
            "--universe", $Context.universe,
            "--bind", "127.0.0.1:0",
            "--auth-mode", "offline",
            "--disable-sentry",
            "--disable-file-watcher"
        )
    }
    $executable = if ($PSBoundParameters.ContainsKey("ExecutableOverride")) {
        $ExecutableOverride
    } else {
        $Inputs.javaExecutable
    }
    $commandLine = ((Format-ClaimsRuntimeCommandArgument $executable) + " " +
        (($arguments | ForEach-Object { Format-ClaimsRuntimeCommandArgument $_ }) -join " ")).Trim()
    Write-ClaimsRuntimeText -Path (Join-Path $Context.evidence "command-line.txt") `
        -Value ($commandLine + [Environment]::NewLine)

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $executable
    $startInfo.WorkingDirectory = $Context.home
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $arguments) { $startInfo.ArgumentList.Add($argument) }
    $startInfo.Environment["APPDATA"] = $appData
    $startInfo.Environment["LOCALAPPDATA"] = $localAppData
    $startInfo.Environment["USERPROFILE"] = $userHome
    $startInfo.Environment["HOME"] = $userHome
    $startInfo.Environment["TMP"] = $temp
    $startInfo.Environment["TEMP"] = $temp
    $startInfo.Environment.Remove("JAVA_TOOL_OPTIONS") | Out-Null
    $startInfo.Environment.Remove("_JAVA_OPTIONS") | Out-Null

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $processStarted = $false
    $pidValue = $null
    $startedAt = [DateTime]::UtcNow
    $bootedAt = $null
    $stopSentAt = $null
    $forced = $false
    $stdoutTask = $null
    $stderrTask = $null
    $stdout = ""
    $stderr = ""
    $exitCode = $null
    $exitedAt = $null
    $startFailure = $null
    $lifecycleFailure = $null
    try {
        if (-not $process.Start()) { throw "Server process did not start." }
        $processStarted = $true
        $pidValue = $process.Id
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $startupWatch = [Diagnostics.Stopwatch]::StartNew()
        $logRoot = Join-Path $Context.home "logs"
        while (-not $process.HasExited -and $startupWatch.Elapsed.TotalSeconds -lt $StartupTimeoutSeconds) {
            if (Test-ClaimsRuntimeLogMarker -LogRoot $logRoot -Marker "Hytale Server Booted!") {
                $bootedAt = [DateTime]::UtcNow
                break
            }
            Start-Sleep -Milliseconds 100
        }
        if ($null -ne $bootedAt) {
            $dwellWatch = [Diagnostics.Stopwatch]::StartNew()
            while (-not $process.HasExited -and $dwellWatch.Elapsed.TotalSeconds -lt $DwellSeconds) {
                Start-Sleep -Milliseconds 100
            }
        }
        if (-not $process.HasExited) {
            $stopSentAt = [DateTime]::UtcNow
            try {
                $process.StandardInput.WriteLine("stop")
                $process.StandardInput.Flush()
            } catch {
                $lifecycleFailure = $_.Exception
            }
            if (-not $process.HasExited -and
                    -not $process.WaitForExit($ShutdownTimeoutSeconds * 1000)) {
                $forced = $true
                $process.Kill($true)
                $process.WaitForExit()
            }
        }
        $process.WaitForExit()
    } catch {
        if ($processStarted) { $lifecycleFailure = $_.Exception } else { $startFailure = $_.Exception }
    } finally {
        if ($processStarted) {
            try {
                if (-not $process.HasExited) {
                    $forced = $true
                    $process.Kill($true)
                    $process.WaitForExit()
                }
                $exitCode = $process.ExitCode
                $exitedAt = [DateTime]::UtcNow
                if ($null -ne $stdoutTask) { $stdout = $stdoutTask.GetAwaiter().GetResult() }
                if ($null -ne $stderrTask) { $stderr = $stderrTask.GetAwaiter().GetResult() }
            } catch {
                if ($null -eq $lifecycleFailure) { $lifecycleFailure = $_.Exception }
            }
        }
        $process.Dispose()
    }
    if ($null -ne $startFailure) { throw $startFailure }
    return [pscustomobject][ordered]@{
        commandLine = $commandLine
        pid = $pidValue
        startedAtUtc = $startedAt.ToString("o")
        bootedAtUtc = if ($null -eq $bootedAt) { $null } else { $bootedAt.ToString("o") }
        stopSentAtUtc = if ($null -eq $stopSentAt) { $null } else { $stopSentAt.ToString("o") }
        exitedAtUtc = if ($null -eq $exitedAt) { $null } else { $exitedAt.ToString("o") }
        exitCode = $exitCode
        forcedTermination = $forced
        lifecycleError = if ($null -eq $lifecycleFailure) { $null } else { $lifecycleFailure.ToString() }
        stdout = $stdout
        stderr = $stderr
    }
}

function Read-ClaimsRuntimeScenarioLogs {
    param([psobject] $Context, [string] $Stdout, [string] $Stderr)
    $parts = [System.Collections.Generic.List[string]]::new()
    $parts.Add("===== STDOUT =====")
    $parts.Add($Stdout)
    $parts.Add("===== STDERR =====")
    $parts.Add($Stderr)
    $logRoot = Join-Path $Context.home "logs"
    if (Test-Path -LiteralPath $logRoot) {
        foreach ($file in @(Get-ChildItem -LiteralPath $logRoot -Recurse -File | Sort-Object FullName)) {
            $parts.Add("===== FILE $($file.FullName) =====")
            $parts.Add((Get-Content -LiteralPath $file.FullName -Raw -ErrorAction SilentlyContinue))
        }
    }
    return $parts -join [Environment]::NewLine
}

function Read-ClaimsRuntimeCanonicalServerLog {
    param([psobject] $Context)
    $logRoot = Join-Path $Context.home "logs"
    if (-not (Test-Path -LiteralPath $logRoot)) { return "" }
    $candidates = [System.Collections.Generic.List[object]]::new()
    foreach ($file in @(Get-ChildItem -LiteralPath $logRoot -Recurse -File | Sort-Object FullName)) {
        $content = Get-Content -LiteralPath $file.FullName -Raw -ErrorAction SilentlyContinue
        if ($content -match '(?im)Enabled plugin\s+' -or $content.Contains("Hytale Server Booted!")) {
            $score = 0
            if ($content.Contains("Hytale Server Booted!")) { $score += 2 }
            if ($content.Contains("Shutdown completed!")) { $score += 2 }
            if ($content -match '(?im)Enabled plugin\s+') { $score += 1 }
            $candidates.Add([pscustomobject]@{ score = $score; length = $content.Length; text = $content })
        }
    }
    $selected = $candidates | Sort-Object score, length -Descending | Select-Object -First 1
    if ($null -eq $selected) { return "" }
    return [string]$selected.text
}

function Write-ClaimsRuntimeScenarioSummary {
    param([psobject] $Result, [string] $Path)
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("# Claims runtime scenario: $($Result.id)")
    $lines.Add("")
    $lines.Add("- Result: **$(if ($Result.passed) { 'PASS' } else { 'FAIL' })**")
    $lines.Add("- Description: $($Result.description)")
    if ($null -ne $Result.process) {
        $lines.Add("- PID: $($Result.process.pid)")
        $lines.Add("- Exit code: $($Result.process.exitCode)")
        $lines.Add("- Forced termination: $($Result.process.forcedTermination)")
    }
    if ($null -ne $Result.sqliteEvidence) {
        $lines.Add("- SQLite integrity / WAL / synchronous: $($Result.sqliteEvidence.integrityCheck) / $($Result.sqliteEvidence.journalMode) / $($Result.sqliteEvidence.synchronous)")
        $lines.Add("- Coverage READY: $($Result.sqliteEvidence.coverageReady)/$($Result.sqliteEvidence.coverageTotal)")
        $lines.Add("- Nonterminal operations: $($Result.sqliteEvidence.nonterminalOperations)")
        $lines.Add("- Canonical/profile rows: $($Result.sqliteEvidence.canonicalRows)/$($Result.sqliteEvidence.profileRows)")
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$Result.error)) {
        $lines.Add("")
        $lines.Add("## Failure")
        $lines.Add("")
        $lines.Add('```text')
        $lines.Add([string]$Result.error)
        $lines.Add('```')
    }
    Write-ClaimsRuntimeText -Path $Path `
        -Value (($lines -join [Environment]::NewLine) + [Environment]::NewLine)
}

Export-ModuleMember -Function @(
    "Write-ClaimsRuntimeJson",
    "Write-ClaimsRuntimeText",
    "Copy-ClaimsRuntimeSaveRoot",
    "Initialize-ClaimsRuntimeScenario",
    "Invoke-ClaimsRuntimeServerProcess",
    "Read-ClaimsRuntimeScenarioLogs",
    "Read-ClaimsRuntimeCanonicalServerLog",
    "Write-ClaimsRuntimeScenarioSummary"
)
