param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$ConfigPath = ".release/publish-config.json",
    [string]$OutputDir = "artifacts",
    [string]$ArtifactPathOutputFile = "",
    [string]$HytaleInstallPath = "",
    [string]$PrebuiltArtifactPath = "",
    [bool]$SkipTests = $false
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-NormalizedVersion {
    param([string]$RawVersion)
    return (($RawVersion.Trim()) -replace "^v", "")
}

function Get-ArtifactName {
    param(
        [object]$Config,
        [string]$NormalizedVersion
    )

    return $Config.artifactNameTemplate.Replace("{version}", $NormalizedVersion)
}

if (-not (Test-Path -Path $ConfigPath)) {
    throw "Release config '$ConfigPath' was not found."
}

$config = Get-Content -Path $ConfigPath -Raw | ConvertFrom-Json
$normalizedVersion = Get-NormalizedVersion -RawVersion $Version

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$artifactName = Get-ArtifactName -Config $config -NormalizedVersion $normalizedVersion
$artifactPath = Join-Path $OutputDir $artifactName
$resolvedArtifactDestination = [System.IO.Path]::GetFullPath($artifactPath)

switch ($config.packaging) {
    "jar" {
        if (-not [string]::IsNullOrWhiteSpace($PrebuiltArtifactPath)) {
            if (-not (Test-Path -Path $PrebuiltArtifactPath)) {
                throw "Prebuilt artifact '$PrebuiltArtifactPath' was not found."
            }

            $resolvedPrebuiltArtifact = (Resolve-Path -Path $PrebuiltArtifactPath).Path
            $prebuiltArtifact = Get-Item -Path $resolvedPrebuiltArtifact
            if ($prebuiltArtifact.Length -le 0) {
                throw "Prebuilt artifact is empty: '$resolvedPrebuiltArtifact'."
            }

            Write-Host "Using prebuilt artifact: $resolvedPrebuiltArtifact"
            if ($resolvedPrebuiltArtifact -ne $resolvedArtifactDestination) {
                Copy-Item -Path $resolvedPrebuiltArtifact -Destination $resolvedArtifactDestination -Force
            }
        } else {
            $mvnArgs = @("-B", "clean", "test", "package")
            if ($SkipTests) {
                $mvnArgs = @("-B", "clean", "package", "-DskipTests")
            }

            if (-not [string]::IsNullOrWhiteSpace($HytaleInstallPath)) {
                if (-not (Test-Path -Path $HytaleInstallPath)) {
                    throw "Provided hytale install path was not found: '$HytaleInstallPath'."
                }

                $resolvedInstallPath = (Resolve-Path -Path $HytaleInstallPath).Path
                $serverJarPath = Join-Path $resolvedInstallPath "Server\HytaleServer.jar"
                if (-not (Test-Path -Path $serverJarPath)) {
                    throw "Expected HytaleServer.jar at '$serverJarPath'."
                }

                $mvnArgs += "-Dhytale.install.path=$resolvedInstallPath"
                Write-Host "Using hytale.install.path override: $resolvedInstallPath"
            }

            & .\mvnw.cmd @mvnArgs
            if ($LASTEXITCODE -ne 0) {
                throw "Maven build failed with exit code $LASTEXITCODE."
            }

            $builtJar = Get-ChildItem -Path "target" -Filter "*.jar" |
                Where-Object { $_.Name -notlike "original-*" } |
                Sort-Object -Property LastWriteTime -Descending |
                Select-Object -First 1

            if ($null -eq $builtJar) {
                throw "No built jar was found in target/."
            }

            Copy-Item -Path $builtJar.FullName -Destination $artifactPath -Force
        }
    }
    "zip" {
        $stagingDir = Join-Path $OutputDir "staging"
        if (Test-Path -Path $stagingDir) {
            Remove-Item -Path $stagingDir -Recurse -Force
        }
        New-Item -ItemType Directory -Path $stagingDir -Force | Out-Null

        foreach ($path in @($config.zipIncludes)) {
            if (-not (Test-Path -Path $path)) {
                throw "Zip include '$path' was not found."
            }

            $item = Get-Item -Path $path
            $destination = Join-Path $stagingDir $path
            $destinationParent = Split-Path -Path $destination -Parent
            if (-not [string]::IsNullOrWhiteSpace($destinationParent)) {
                New-Item -ItemType Directory -Path $destinationParent -Force | Out-Null
            }

            if ($item.PSIsContainer) {
                Copy-Item -Path $item.FullName -Destination $destination -Recurse -Force
            } else {
                Copy-Item -Path $item.FullName -Destination $destination -Force
            }
        }

        if (Test-Path -Path $artifactPath) {
            Remove-Item -Path $artifactPath -Force
        }

        $zipSource = Join-Path $stagingDir "*"
        Compress-Archive -Path $zipSource -DestinationPath $artifactPath -CompressionLevel Optimal
    }
    default {
        throw "Unsupported packaging '$($config.packaging)' in $ConfigPath."
    }
}

if (-not (Test-Path -Path $artifactPath)) {
    throw "Artifact was not created: '$artifactPath'."
}

$artifactItem = Get-Item -Path $artifactPath
if ($artifactItem.Length -le 0) {
    throw "Artifact is empty: '$artifactPath'."
}

$resolvedArtifactPath = (Resolve-Path -Path $artifactPath).Path
Write-Host "Built artifact: $resolvedArtifactPath"

if (-not [string]::IsNullOrWhiteSpace($ArtifactPathOutputFile)) {
    Set-Content -Path $ArtifactPathOutputFile -Value $resolvedArtifactPath
}

if ($env:GITHUB_OUTPUT) {
    "artifact_path=$resolvedArtifactPath" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
}

Write-Output $resolvedArtifactPath
