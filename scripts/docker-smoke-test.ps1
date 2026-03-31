# =============================================================================
# JADP Docker Smoke Test (PowerShell)
# Validates that a deployed JADP Docker environment is functioning correctly.
# Run after docker-run.ps1 completes.
# Usage: .\scripts\docker-smoke-test.ps1 [-AppUrl url] [-HybridUrl url]
# =============================================================================
param(
    [string]$AppUrl     = "http://localhost:$($env:APP_PORT ?? '8080')",
    [string]$HybridUrl  = "http://localhost:$($env:HYBRID_PORT ?? '5002')",
    [int]$TimeoutSec    = 10
)

$ErrorActionPreference = "Continue"
$pass = 0; $fail = 0; $skip = 0; $total = 0

function Record-Pass($msg)  { $script:total++; $script:pass++;  Write-Host "  PASS " -ForegroundColor Green -NoNewline; Write-Host $msg }
function Record-Fail($msg, $hint) { $script:total++; $script:fail++; Write-Host "  FAIL " -ForegroundColor Red -NoNewline; Write-Host $msg; if ($hint) { Write-Host "        hint: $hint" } }
function Record-Skip($msg)  { $script:total++; $script:skip++;  Write-Host "  SKIP " -ForegroundColor Yellow -NoNewline; Write-Host $msg }

function Invoke-Safe($uri, $method = "GET", $body = $null, $contentType = $null, $timeoutOverride = $null) {
    $t = if ($timeoutOverride) { $timeoutOverride } else { $TimeoutSec }
    try {
        $params = @{ Uri = $uri; Method = $method; TimeoutSec = $t; UseBasicParsing = $true }
        if ($body)        { $params.Body = $body }
        if ($contentType) { $params.ContentType = $contentType }
        return Invoke-WebRequest @params
    } catch {
        return $null
    }
}

# Create minimal PNG bytes
function New-MinimalPng {
    $tmp = [System.IO.Path]::GetTempFileName() -replace '\.tmp$', '.png'
    $bytes = [byte[]](
        0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,
        0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52,
        0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01,
        0x08,0x02,0x00,0x00,0x00,0x90,0x77,0x53,
        0xDE,0x00,0x00,0x00,0x0C,0x49,0x44,0x41,
        0x54,0x78,0x9C,0x63,0xF8,0x0F,0x00,0x00,
        0x01,0x01,0x00,0x05,0x18,0xD8,0x4E,0x00,
        0x00,0x00,0x00,0x49,0x45,0x4E,0x44,0xAE,
        0x42,0x60,0x82
    )
    [System.IO.File]::WriteAllBytes($tmp, $bytes)
    return $tmp
}

function Invoke-MultipartUpload($uri, $filePath, $fileName, $extraFields = @{}, $timeoutOverride = 60) {
    try {
        $boundary = "----jadp-smoke-" + [guid]::NewGuid().ToString("N")
        $LF = "`r`n"
        $fileBytes = [System.IO.File]::ReadAllBytes($filePath)

        $bodyLines = New-Object System.IO.MemoryStream
        $enc = [System.Text.Encoding]::UTF8

        # File field
        $header = "--$boundary${LF}Content-Disposition: form-data; name=`"file`"; filename=`"$fileName`"${LF}Content-Type: application/octet-stream${LF}${LF}"
        $bodyLines.Write($enc.GetBytes($header), 0, $enc.GetByteCount($header))
        $bodyLines.Write($fileBytes, 0, $fileBytes.Length)
        $bodyLines.Write($enc.GetBytes($LF), 0, $enc.GetByteCount($LF))

        # Extra fields
        foreach ($key in $extraFields.Keys) {
            $field = "--$boundary${LF}Content-Disposition: form-data; name=`"$key`"${LF}${LF}$($extraFields[$key])${LF}"
            $bodyLines.Write($enc.GetBytes($field), 0, $enc.GetByteCount($field))
        }

        $footer = "--$boundary--${LF}"
        $bodyLines.Write($enc.GetBytes($footer), 0, $enc.GetByteCount($footer))

        $bodyArray = $bodyLines.ToArray()
        $bodyLines.Dispose()

        $resp = Invoke-WebRequest -Uri $uri -Method POST `
            -ContentType "multipart/form-data; boundary=$boundary" `
            -Body $bodyArray `
            -TimeoutSec $timeoutOverride `
            -UseBasicParsing
        return $resp.Content
    } catch {
        return $null
    }
}

# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "=== JADP Smoke Test ==="
Write-Host "App URL    : $AppUrl"
Write-Host "Hybrid URL : $HybridUrl"
Write-Host ""

# 1. Health Check
Write-Host "[1/8] Actuator Health"
$health = Invoke-Safe "$AppUrl/actuator/health"
if ($health -and $health.Content -match '"status"\s*:\s*"UP"') {
    Record-Pass "GET /actuator/health -> UP"
} else {
    Record-Fail "GET /actuator/health" "Response: $($health.Content ?? 'no response')"
}

# 2. Test Page
Write-Host "[2/8] Test Console Page"
$page = Invoke-Safe "$AppUrl/"
if ($page -and $page.StatusCode -eq 200) {
    Record-Pass "GET / -> 200"
} else {
    Record-Fail "GET / -> $($page.StatusCode ?? 'no response')"
}

# 3. Swagger UI
Write-Host "[3/8] Swagger UI"
$swagger = Invoke-Safe "$AppUrl/swagger-ui.html"
if ($swagger -and ($swagger.StatusCode -eq 200 -or $swagger.StatusCode -eq 302)) {
    Record-Pass "GET /swagger-ui.html -> $($swagger.StatusCode)"
} else {
    Record-Fail "GET /swagger-ui.html -> $($swagger.StatusCode ?? 'no response')"
}

# 4. Config Options
Write-Host "[4/8] PDF Config Options API"
$opts = Invoke-Safe "$AppUrl/api/v1/pdf/config/options"
if ($opts -and $opts.Content -match '"formats"') {
    Record-Pass "GET /api/v1/pdf/config/options -> valid JSON"
} else {
    Record-Fail "GET /api/v1/pdf/config/options" "Expected JSON with 'formats'"
}

# 5. Hybrid Service
Write-Host "[5/8] Hybrid Service Connectivity"
$hybrid = Invoke-Safe "$HybridUrl/docs"
if ($hybrid -and $hybrid.StatusCode -eq 200) {
    Record-Pass "GET $HybridUrl/docs -> 200"
} else {
    Record-Skip "Hybrid service not reachable - may be disabled"
}

# 6. PDF Convert
Write-Host "[6/8] PDF Convert API (image upload)"
$png = New-MinimalPng
$convertResp = Invoke-MultipartUpload "$AppUrl/api/v1/pdf/convert-sync" $png "test.png" @{ formats = "json" }
Remove-Item $png -ErrorAction SilentlyContinue
if ($convertResp -match '"SUCCEEDED"') {
    Record-Pass "POST /api/v1/pdf/convert-sync -> SUCCEEDED"
} elseif ($convertResp -match '"FAILED"') {
    $err = if ($convertResp -match '"error"\s*:\s*"([^"]*)"') { $Matches[1] } else { "unknown" }
    Record-Fail "POST /api/v1/pdf/convert-sync -> FAILED" $err
} else {
    Record-Fail "POST /api/v1/pdf/convert-sync" "Unexpected: $($convertResp.Substring(0, [Math]::Min(200, $convertResp.Length)))"
}

# 7. PII Detect
Write-Host "[7/8] PII Detect API"
$png2 = New-MinimalPng
$piiResp = Invoke-MultipartUpload "$AppUrl/api/v1/pii/detect" $png2 "test.png"
Remove-Item $png2 -ErrorAction SilentlyContinue
if ($piiResp -match '"findingCount"') {
    Record-Pass "POST /api/v1/pii/detect -> valid response"
} else {
    Record-Fail "POST /api/v1/pii/detect" "Unexpected: $($piiResp.Substring(0, [Math]::Min(200, $piiResp.Length)))"
}

# 8. Korean Filename
Write-Host "[8/8] Korean Filename Upload"
$png3 = New-MinimalPng
$koResp = Invoke-MultipartUpload "$AppUrl/api/v1/pdf/convert-sync" $png3 ([System.Text.Encoding]::UTF8.GetString([System.Text.Encoding]::UTF8.GetBytes("한글테스트문서.png"))) @{ formats = "json" }
Remove-Item $png3 -ErrorAction SilentlyContinue
if ($koResp -match '"sourceFilename"') {
    if ($koResp -match '한글') {
        Record-Pass "Korean filename preserved in response"
    } else {
        Record-Fail "Korean filename lost" "Response did not contain Korean characters"
    }
} else {
    Record-Fail "Korean filename upload" "No valid response"
}

# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "==========================================="
Write-Host " Results: $pass PASS  $fail FAIL  $skip SKIP  ($total total)" -ForegroundColor $(if ($fail -gt 0) { "Red" } else { "Green" })
Write-Host "==========================================="

if ($fail -gt 0) {
    Write-Host ""
    Write-Host "Troubleshooting tips:"
    Write-Host "  - Check app logs : docker logs jadp-app --tail 200"
    Write-Host "  - Check hybrid   : docker logs jadp-hybrid --tail 200"
    Write-Host "  - Check health   : curl -s $AppUrl/actuator/health"
    Write-Host "  - Check storage  : docker exec jadp-app ls -la /var/app-data"
    Write-Host ""
    exit 1
}

exit 0
