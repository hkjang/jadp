param(
    [string]$AppImage = "jadp:latest",
    [string]$HybridImage = "",
    [string]$AppContainer = "jadp-app",
    [string]$HybridContainer = "jadp-hybrid",
    [string]$Network = "jadp-net",
    [int]$AppPort = 8080,
    [int]$HybridPort = 5002,
    [string]$StorageDir = "var/docker-app-data",
    [string]$HybridCacheDir = "var/docker-hybrid-cache",
    [bool]$UseHybrid = $true,
    [ValidateSet("cpu", "gpu")]
    [string]$HybridVariant = "cpu",
    [bool]$AutoApplyToRequests = $true,
    [bool]$AutoApplyToPii = $true,
    [bool]$PreferFullMode = $false,
    [bool]$EnablePictureDescription = $false,
    [bool]$ForceOcr = $false,
    [string]$OcrLang = "ko,en",
    [int]$HybridTimeoutMillis = 120000,
    [bool]$HybridFallback = $true,
    [string]$HybridLogLevel = "info",
    [string]$JavaOpts = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $HybridImage) {
    $HybridImage = if ($HybridVariant -eq "gpu") { "jadp-hybrid-gpu:latest" } else { "jadp-hybrid-cpu:latest" }
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$storagePath = [System.IO.Path]::GetFullPath((Join-Path $root $StorageDir))
$hybridCachePath = [System.IO.Path]::GetFullPath((Join-Path $root $HybridCacheDir))

New-Item -ItemType Directory -Force $storagePath | Out-Null
New-Item -ItemType Directory -Force $hybridCachePath | Out-Null

$networkExists = docker network ls --format "{{.Name}}" | Where-Object { $_ -eq $Network }
if (-not $networkExists) {
    docker network create $Network | Out-Null
}

docker rm -f $AppContainer 2>$null | Out-Null
if ($UseHybrid) {
    docker rm -f $HybridContainer 2>$null | Out-Null
}

if ($UseHybrid) {
    $hybridArgs = @(
        "run", "-d",
        "--name", $HybridContainer,
        "--network", $Network,
        "-p", "${HybridPort}:5002",
        "-v", "${hybridCachePath}:/root/.cache",
        "-e", "HYBRID_HOST=0.0.0.0",
        "-e", "HYBRID_PORT=5002",
        "-e", "HYBRID_LOG_LEVEL=$HybridLogLevel"
    )

    if ($HybridVariant -eq "gpu") {
        $hybridArgs += @("--gpus", "all")
    }

    if ($ForceOcr) {
        $hybridArgs += @("-e", "HYBRID_FORCE_OCR=true", "-e", "HYBRID_OCR_LANG=$OcrLang")
    }
    if ($EnablePictureDescription) {
        $hybridArgs += @("-e", "HYBRID_ENRICH_PICTURE_DESCRIPTION=true")
        $PreferFullMode = $true
    }

    $hybridArgs += $HybridImage
    docker @hybridArgs | Out-Null
}

$appArgs = @(
    "run", "-d",
    "--name", $AppContainer,
    "--network", $Network,
    "-p", "${AppPort}:8080",
    "-v", "${storagePath}:/var/app-data",
    "-e", "APP_STORAGE_BASE_DIR=/var/app-data",
    "-e", "JAVA_OPTS=$JavaOpts"
)

if ($UseHybrid) {
    $hybridMode = if ($PreferFullMode) { "full" } else { "auto" }
    $appArgs += @(
        "-e", "APP_OPENDATALOADER_HYBRID_ENABLED=true",
        "-e", "APP_OPENDATALOADER_HYBRID_BACKEND=docling-fast",
        "-e", "APP_OPENDATALOADER_HYBRID_MODE=$hybridMode",
        "-e", "APP_OPENDATALOADER_HYBRID_URL=http://${HybridContainer}:5002",
        "-e", "APP_OPENDATALOADER_HYBRID_TIMEOUT_MILLIS=$HybridTimeoutMillis",
        "-e", "APP_OPENDATALOADER_HYBRID_FALLBACK=$($HybridFallback.ToString().ToLowerInvariant())",
        "-e", "APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_REQUESTS=$($AutoApplyToRequests.ToString().ToLowerInvariant())",
        "-e", "APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_PII=$($AutoApplyToPii.ToString().ToLowerInvariant())",
        "-e", "APP_OPENDATALOADER_HYBRID_PREFER_FULL_MODE=$($PreferFullMode.ToString().ToLowerInvariant())"
    )
} else {
    $appArgs += @("-e", "APP_OPENDATALOADER_HYBRID_ENABLED=false")
}

$appArgs += $AppImage
docker @appArgs | Out-Null

Write-Host "JADP app  : http://localhost:$AppPort"
Write-Host "Swagger   : http://localhost:$AppPort/swagger-ui.html"
Write-Host "Health    : http://localhost:$AppPort/actuator/health"
if ($UseHybrid) {
    Write-Host "Hybrid API: http://localhost:$HybridPort"
    Write-Host "Hybrid tag: $HybridImage ($HybridVariant)"
}
