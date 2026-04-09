param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$ArtifactPath,
    [string]$ChangelogPath = "artifacts/changelog.md",
    [string]$ConfigPath = ".release/publish-config.json",
    [string]$ApiKey = $env:MODTALE_API_KEY,
    [string]$ApiToken = $env:MODTALE_API_TOKEN,
    [bool]$DryRun = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-ModtaleJsonRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Method,
        [Parameter(Mandatory = $true)]
        [string]$Url,
        [Parameter(Mandatory = $true)]
        [string]$ApiKey,
        [string]$JsonBody = ""
    )

    $responseTempFile = New-TemporaryFile
    $curlArgs = @(
        "-sS",
        "-X", $Method,
        $Url,
        "-H", "X-MODTALE-KEY: $ApiKey",
        "-H", "Content-Type: application/json"
    )
    if (-not [string]::IsNullOrWhiteSpace($JsonBody)) {
        $curlArgs += @("-d", $JsonBody)
    }

    $statusCode = & curl.exe @curlArgs -o $responseTempFile -w "%{http_code}"
    $statusCode = $statusCode.Trim()
    if ($LASTEXITCODE -ne 0) {
        Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
        throw "Modtale request failed for $Method $Url (curl exit code $LASTEXITCODE)."
    }

    $response = ""
    if (Test-Path -Path $responseTempFile) {
        $response = Get-Content -Path $responseTempFile -Raw
        Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
    }

    $statusCodeInt = 0
    if (-not [int]::TryParse($statusCode, [ref]$statusCodeInt)) {
        throw "Modtale request returned invalid HTTP status '$statusCode' for $Method $Url."
    }

    return @{
        StatusCode = $statusCodeInt
        ResponseBody = $response
    }
}

function Get-ModtalePropertyValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Object,
        [Parameter(Mandatory = $true)]
        [string]$PropertyName
    )

    if ($null -eq $Object) {
        return $null
    }

    $property = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Resolve-ModtaleVersionId {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectId,
        [Parameter(Mandatory = $true)]
        [string]$VersionNumber,
        [Parameter(Mandatory = $true)]
        [string]$ApiKey
    )

    $discoveryEndpoints = @(
        "https://api.modtale.net/api/v1/projects/$ProjectId",
        "https://api.modtale.net/api/v1/projects/$ProjectId/versions"
    )

    foreach ($discoveryEndpoint in $discoveryEndpoints) {
        $discoveryResult = $null
        try {
            $discoveryResult = Invoke-ModtaleJsonRequest -Method "GET" -Url $discoveryEndpoint -ApiKey $ApiKey
        } catch {
            continue
        }

        if ($discoveryResult.StatusCode -lt 200 -or $discoveryResult.StatusCode -ge 300) {
            continue
        }

        if ([string]::IsNullOrWhiteSpace($discoveryResult.ResponseBody)) {
            continue
        }

        $responseObject = $null
        try {
            $responseObject = $discoveryResult.ResponseBody | ConvertFrom-Json -Depth 32
        } catch {
            continue
        }

        $versionRecords = @()
        if (($responseObject -is [System.Collections.IEnumerable]) -and -not ($responseObject -is [string]) -and -not ($responseObject -is [System.Collections.IDictionary])) {
            $versionRecords = @($responseObject)
        } else {
            $versionsValue = Get-ModtalePropertyValue -Object $responseObject -PropertyName "versions"
            if ($null -ne $versionsValue) {
                $versionRecords += @($versionsValue)
            }

            $contentValue = Get-ModtalePropertyValue -Object $responseObject -PropertyName "content"
            if ($null -ne $contentValue) {
                $versionRecords += @($contentValue)
            }
        }

        foreach ($versionRecord in $versionRecords) {
            if ($null -eq $versionRecord) {
                continue
            }

            $recordVersion = Get-ModtalePropertyValue -Object $versionRecord -PropertyName "versionNumber"
            if ([string]::IsNullOrWhiteSpace("$recordVersion")) {
                $recordVersion = Get-ModtalePropertyValue -Object $versionRecord -PropertyName "version"
            }
            if ([string]::IsNullOrWhiteSpace("$recordVersion")) {
                continue
            }

            if ("$recordVersion".Trim() -ne $VersionNumber) {
                continue
            }

            $recordId = Get-ModtalePropertyValue -Object $versionRecord -PropertyName "id"
            if (-not [string]::IsNullOrWhiteSpace("$recordId")) {
                return "$recordId".Trim()
            }
        }
    }

    return ""
}

if (-not (Test-Path -Path $ConfigPath)) {
    throw "Release config '$ConfigPath' was not found."
}
if (-not (Test-Path -Path $ArtifactPath)) {
    throw "Artifact '$ArtifactPath' was not found."
}
if (-not (Test-Path -Path $ChangelogPath)) {
    throw "Changelog '$ChangelogPath' was not found."
}

$config = Get-Content -Path $ConfigPath -Raw | ConvertFrom-Json
$normalizedVersion = (($Version.Trim()) -replace "^v", "")
$projectId = $config.modtale.projectId

$effectiveApiKey = $ApiKey
if ([string]::IsNullOrWhiteSpace($effectiveApiKey)) {
    # Backward-compat alias for older secret naming.
    $effectiveApiKey = $ApiToken
}

$endpoint = if ([string]::IsNullOrWhiteSpace($projectId)) {
    "https://api.modtale.net/api/v1/projects/<project-id>/versions"
} else {
    "https://api.modtale.net/api/v1/projects/$projectId/versions"
}
$changelog = Get-Content -Path $ChangelogPath -Raw
$rawChannel = $config.modtale.releaseChannel
if ([string]::IsNullOrWhiteSpace($rawChannel)) {
    $rawChannel = "stable"
}
$channelKey = $rawChannel.Trim().ToLowerInvariant()
$channel = switch ($channelKey) {
    "stable" { "RELEASE" }
    "release" { "RELEASE" }
    "beta" { "BETA" }
    "alpha" { "ALPHA" }
    default { $rawChannel.Trim().ToUpperInvariant() }
}

$versionFieldName = if ([string]::IsNullOrWhiteSpace($config.modtale.versionFieldName)) { "versionNumber" } else { $config.modtale.versionFieldName }
$changelogFieldName = if ([string]::IsNullOrWhiteSpace($config.modtale.changelogFieldName)) { "changelog" } else { $config.modtale.changelogFieldName }
$channelFieldName = if ([string]::IsNullOrWhiteSpace($config.modtale.channelFieldName)) { "channel" } else { $config.modtale.channelFieldName }
$gameVersionFieldName = if ([string]::IsNullOrWhiteSpace($config.modtale.gameVersionFieldName)) { "gameVersions" } else { $config.modtale.gameVersionFieldName }

if ($DryRun) {
    Write-Host "Dry-run: would publish '$ArtifactPath' to Modtale project '$projectId'."
    Write-Host "Endpoint: $endpoint"
    Write-Host "Version: $normalizedVersion"
    Write-Host "Channel: $channel"
    if ([string]::IsNullOrWhiteSpace($projectId)) {
        Write-Host "Note: modtale.projectId is empty in $ConfigPath."
    }
    exit 0
}

if ([string]::IsNullOrWhiteSpace($projectId)) {
    throw "modtale.projectId is empty in $ConfigPath."
}

if ([string]::IsNullOrWhiteSpace($effectiveApiKey)) {
    throw "MODTALE_API_KEY is required when DryRun is false (MODTALE_API_KEY/MODTALE_API_TOKEN env var or -ApiKey/-ApiToken)."
}

$curlArgs = @(
    "-sS",
    "-X", "POST",
    $endpoint,
    "-H", "X-MODTALE-KEY: $effectiveApiKey",
    "-F", "$versionFieldName=$normalizedVersion",
    "-F", "$changelogFieldName=$changelog",
    "-F", "$channelFieldName=$channel"
)

foreach ($gameVersion in @($config.modtale.gameVersions)) {
    $curlArgs += @("-F", "$gameVersionFieldName=$gameVersion")
}

$curlArgs += @("-F", "file=@$ArtifactPath")

$responseTempFile = New-TemporaryFile
$statusCode = & curl.exe @curlArgs `
    -o $responseTempFile `
    -w "%{http_code}"
$statusCode = $statusCode.Trim()

if ($LASTEXITCODE -ne 0) {
    Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
    throw "Modtale upload failed with exit code $LASTEXITCODE."
}

$response = ""
if (Test-Path -Path $responseTempFile) {
    $response = Get-Content -Path $responseTempFile -Raw
    Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
}

$statusCodeInt = 0
if (-not [int]::TryParse($statusCode, [ref]$statusCodeInt)) {
    throw "Modtale upload failed with an invalid HTTP status value '$statusCode'."
}

if ($statusCodeInt -lt 200 -or $statusCodeInt -ge 300) {
    $responseSummary = if ([string]::IsNullOrWhiteSpace($response)) { "<empty>" } else { $response }

    $knownVersionsSummary = ""
    $knownVersionsTempFile = New-TemporaryFile
    $knownVersionsStatus = & curl.exe `
        -sS `
        -o $knownVersionsTempFile `
        -w "%{http_code}" `
        -X GET `
        "https://api.modtale.net/api/v1/meta/game-versions" `
        -H "X-MODTALE-KEY: $effectiveApiKey"
    if ($LASTEXITCODE -eq 0) {
        $knownVersionsBody = Get-Content -Path $knownVersionsTempFile -Raw
        if ($knownVersionsStatus -eq "200" -and -not [string]::IsNullOrWhiteSpace($knownVersionsBody)) {
            $knownVersionsSummary = " Known game versions response: $knownVersionsBody"
        }
    }
    Remove-Item -Path $knownVersionsTempFile -Force -ErrorAction SilentlyContinue

    $alreadyExists = ($statusCodeInt -eq 400) -and ($responseSummary -match "(?i)already exists")
    if ($alreadyExists) {
        $updatePayloadObject = @{
            $versionFieldName = $normalizedVersion
            $changelogFieldName = $changelog
            $channelFieldName = $channel
            $gameVersionFieldName = @($config.modtale.gameVersions)
        }
        $updatePayload = $updatePayloadObject | ConvertTo-Json -Depth 16 -Compress

        $existingVersionId = Resolve-ModtaleVersionId -ProjectId $projectId -VersionNumber $normalizedVersion -ApiKey $effectiveApiKey

        $candidateUpdates = @()
        if (-not [string]::IsNullOrWhiteSpace($existingVersionId)) {
            $candidateUpdates += @{
                Method = "PUT"
                Url = "https://api.modtale.net/api/v1/projects/$projectId/versions/$existingVersionId"
            }
        }

        $candidateUpdates += @{
            Method = "PUT"
            Url = "https://api.modtale.net/api/v1/projects/$projectId/versions/$normalizedVersion"
        }

        $attemptErrors = @()
        foreach ($candidate in $candidateUpdates) {
            try {
                $result = Invoke-ModtaleJsonRequest -Method $candidate.Method -Url $candidate.Url -ApiKey $effectiveApiKey -JsonBody $updatePayload
                if ($result.StatusCode -ge 200 -and $result.StatusCode -lt 300) {
                    Write-Host "Modtale version already existed; updated existing version metadata via $($candidate.Method) $($candidate.Url) (HTTP $($result.StatusCode))."
                    if (-not [string]::IsNullOrWhiteSpace($result.ResponseBody)) {
                        Write-Output $result.ResponseBody
                    }
                    return
                }

                $bodySummary = if ([string]::IsNullOrWhiteSpace($result.ResponseBody)) { "<empty>" } else { $result.ResponseBody }
                $attemptErrors += "$($candidate.Method) $($candidate.Url) => HTTP $($result.StatusCode): $bodySummary"
            } catch {
                $attemptErrors += "$($candidate.Method) $($candidate.Url) => $($_.Exception.Message)"
            }
        }

        $attemptSummary = if ($attemptErrors.Count -eq 0) { "No update endpoints attempted." } else { ($attemptErrors -join " || ") }
        $discoverySummary = if ([string]::IsNullOrWhiteSpace($existingVersionId)) {
            "No existing version id was resolved for '$normalizedVersion'."
        } else {
            "Resolved existing version id '$existingVersionId'."
        }
        throw "Modtale upload failed because version '$normalizedVersion' already exists and update fallback did not succeed. $discoverySummary Attempts: $attemptSummary"
    }

    throw "Modtale upload failed with HTTP status $statusCode. Response: $responseSummary$knownVersionsSummary"
}

Write-Host "Modtale upload completed (HTTP $statusCode)."
if (-not [string]::IsNullOrWhiteSpace($response)) {
    Write-Output $response
}
