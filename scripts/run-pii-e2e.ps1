param(
    [int]$Port = 18086
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$mvn = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$settings = Join-Path $root ".mvn-local-settings.xml"
$artifactDir = Join-Path $root "test-artifacts"
$reportFile = Join-Path $artifactDir "PII_DETECTION_MASKING_REPORT.md"
$rawResultsFile = Join-Path $artifactDir "pii-detection-results.json"
$logFile = Join-Path $artifactDir "pii-app-$Port.log"
$errLogFile = Join-Path $artifactDir "pii-app-$Port.err.log"

New-Item -ItemType Directory -Force $artifactDir | Out-Null

function Wait-HttpReady {
    param(
        [string]$Url,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return $true
            }
        } catch {
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Invoke-MultipartJson {
    param(
        [string]$Url,
        [hashtable]$Form
    )
    return Invoke-RestMethod -Method Post -Uri $Url -Form $Form -TimeoutSec 1800
}

function Invoke-JsonGet {
    param([string]$Url)
    return Invoke-RestMethod -Uri $Url -TimeoutSec 180
}

function Invoke-RawRequest {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Form
    )

    $request = @{
        Method = $Method
        Uri = $Url
        UseBasicParsing = $true
        TimeoutSec = 180
        SkipHttpErrorCheck = $true
    }
    if ($Form.Count -gt 0) {
        $request.Form = $Form
    }

    $response = Invoke-WebRequest @request
    $body = $null
    if ($response.Content) {
        try {
            $body = $response.Content | ConvertFrom-Json
        } catch {
            $body = $response.Content
        }
    }
    return [pscustomobject]@{
        statusCode = [int]$response.StatusCode
        body = $body
    }
}

function Get-DetectionSummary {
    param([object]$Response)

    $types = @($Response.findings | ForEach-Object { $_.type } | Sort-Object -Unique)
    $pages = @($Response.findings | ForEach-Object { $_.pageNumber } | Sort-Object -Unique)
    return [ordered]@{
        documentId = $Response.documentId
        mediaType = $Response.mediaType
        pageCount = $Response.pageCount
        findingCount = $Response.findingCount
        types = $types
        pages = $pages
        findings = @($Response.findings)
    }
}

function Download-File {
    param(
        [string]$Url,
        [string]$TargetPath
    )
    Invoke-WebRequest -Uri $Url -UseBasicParsing -OutFile $TargetPath -TimeoutSec 300 | Out-Null
}

function Convert-ResultToMarkdown {
    param(
        [array]$Results,
        [hashtable]$PngNote,
        [array]$Validations
    )

    $expectedTypes = @(
        "RESIDENT_REGISTRATION_NUMBER",
        "DRIVER_LICENSE_NUMBER",
        "PASSPORT_NUMBER",
        "FOREIGNER_REGISTRATION_NUMBER",
        "MOBILE_PHONE_NUMBER",
        "LANDLINE_PHONE_NUMBER",
        "CREDIT_CARD_NUMBER",
        "BANK_ACCOUNT_NUMBER",
        "PERSON_NAME",
        "EMAIL_ADDRESS",
        "IP_ADDRESS",
        "STREET_ADDRESS"
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# PII Detection and Masking Report")
    $lines.Add("")
    $executedAt = Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"
    $lines.Add("- Executed at: $executedAt")
    $lines.Add("- Test port: $Port")
    $lines.Add('- Raw results JSON: `test-artifacts/pii-detection-results.json`')
    $lines.Add("- Note: PII positive tests use synthetic Korean documents to avoid using exposed real-person personal data.")
    $lines.Add("")

    $full = $Results | Where-Object { $_.name -eq "synthetic_full_detect" } | Select-Object -First 1
    $mask = $Results | Where-Object { $_.name -eq "synthetic_full_mask" } | Select-Object -First 1
    $multi = $Results | Where-Object { $_.name -eq "synthetic_multipage_detect" } | Select-Object -First 1

    $lines.Add("## Key Findings")
    $lines.Add("")
    if ($full) {
        $missing = @($expectedTypes | Where-Object { $_ -notin $full.types })
        $lines.Add("- 합성 한글 PDF 1건에서 탐지된 PII 유형 수: $($full.types.Count) / 12")
        $lines.Add("- 누락 유형: $(if ($missing.Count -eq 0) { '없음' } else { $missing -join ', ' })")
    }
    if ($multi) {
        $lines.Add("- 다중 페이지 PDF는 페이지 집합 $($multi.pages -join ', ') 에서 탐지되어 페이지 번호/bounding box 응답이 동작했습니다.")
    }
    if ($mask) {
        $lines.Add("- 마스킹 후 재탐지 결과 건수: $($mask.redetectFindingCount). PDF는 비OCR 경로 기준으로 원문 텍스트가 남지 않도록 라스터 masked PDF로 생성했습니다.")
    }
    $negative = $Results | Where-Object { $_.name -eq "privacy_guide_negative_control" } | Select-Object -First 1
    if ($negative) {
        $lines.Add("- 공개 개인정보보호 안내서 baseline 탐지 건수: $($negative.findingCount)")
    }
    $finance = $Results | Where-Object { $_.name -eq "financial_statement_negative_control" } | Select-Object -First 1
    if ($finance) {
        $lines.Add("- 공개 재무제표 baseline 탐지 건수: $($finance.findingCount)")
    }
    $lines.Add("- PNG vLLM 경로: $($PngNote.status) - $($PngNote.note)")
    $lines.Add("")

    $lines.Add("## Scenario Summary")
    $lines.Add("")
    $lines.Add("| Scenario | File | Findings | Types | Pages | Notes |")
    $lines.Add("| --- | --- | ---: | --- | --- | --- |")
    foreach ($result in $Results) {
        $lines.Add("| $($result.name) | $($result.file) | $($result.findingCount) | $([string]::Join(', ', $result.types)) | $([string]::Join(', ', $result.pages)) | $($result.note) |")
    }
    $lines.Add("")

    if ($Validations.Count -gt 0) {
        $lines.Add("## API Validation")
        $lines.Add("")
        $lines.Add("| Scenario | Expected | Actual | Notes |")
        $lines.Add("| --- | ---: | ---: | --- |")
        foreach ($validation in $Validations) {
            $lines.Add("| $($validation.name) | $($validation.expectedStatus) | $($validation.actualStatus) | $($validation.note) |")
        }
        $lines.Add("")
    }

    foreach ($result in $Results) {
        $lines.Add("## $($result.name)")
        $lines.Add("")
        $lines.Add('- File: `' + $result.file + '`')
        $lines.Add("- Finding count: $($result.findingCount)")
        $lines.Add("- Types: $([string]::Join(', ', $result.types))")
        $lines.Add("- Pages: $([string]::Join(', ', $result.pages))")
        if ($result.maskedDownloadUrl) {
            $lines.Add("- Masked download URL: $($result.maskedDownloadUrl)")
            $lines.Add("- Re-detect finding count after masking: $($result.redetectFindingCount)")
        }
        $lines.Add("- Notes: $($result.note)")
        if ($result.findings.Count -gt 0) {
            $lines.Add("")
            $lines.Add("| Type | Original | Masked | Page | Bounding Box | Source |")
            $lines.Add("| --- | --- | --- | ---: | --- | --- |")
            foreach ($finding in $result.findings) {
                $bbox = "x=$($finding.boundingBox.x), y=$($finding.boundingBox.y), w=$($finding.boundingBox.width), h=$($finding.boundingBox.height)"
                $lines.Add("| $($finding.type) | $($finding.originalText) | $($finding.maskedText) | $($finding.pageNumber) | $bbox | $($finding.detectionSource) |")
            }
        }
        $lines.Add("")
    }

    $lines.Add("## PNG Note")
    $lines.Add("")
    $lines.Add("- Status: $($PngNote.status)")
    $lines.Add("- Detail: $($PngNote.note)")

    return ($lines -join [Environment]::NewLine)
}

Push-Location $root
try {
    & $mvn -o -s $settings -Dtest=PiiSampleDocumentGeneratorTest test | Tee-Object -FilePath (Join-Path $artifactDir "pii-sample-generator.log")
    & $mvn -o -s $settings -DskipTests package | Tee-Object -FilePath (Join-Path $artifactDir "pii-package.log")

    $jar = Get-ChildItem (Join-Path $root "target") -Filter "jadp-*.jar" |
            Where-Object { $_.Name -notlike "*.original" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    if (-not $jar) {
        throw "Packaged jar not found."
    }

    if (Test-Path $logFile) { Remove-Item $logFile -Force }
    if (Test-Path $errLogFile) { Remove-Item $errLogFile -Force }

    $appProcess = Start-Process -FilePath "java" `
        -ArgumentList @("-jar", $jar.FullName, "--server.port=$Port") `
        -PassThru `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError $errLogFile

    $baseUrl = "http://127.0.0.1:$Port"
    if (-not (Wait-HttpReady -Url "$baseUrl/actuator/health")) {
        throw "Application did not become ready on port $Port"
    }

    $results = New-Object System.Collections.Generic.List[object]
    $validations = New-Object System.Collections.Generic.List[object]
    $downloadDir = Join-Path $artifactDir "pii-downloads"
    New-Item -ItemType Directory -Force $downloadDir | Out-Null

    $cases = @(
        @{
            name = "synthetic_full_detect"
            file = "samples\\pii\\synthetic-korean-pii-full.pdf"
            note = "12개 기준 유형이 모두 들어간 합성 한글 PDF"
        },
        @{
            name = "synthetic_multipage_detect"
            file = "samples\\pii\\synthetic-korean-pii-multipage.pdf"
            note = "다중 페이지 합성 한글 PDF"
        },
        @{
            name = "privacy_guide_negative_control"
            file = "samples\\privacy-protection-guide.pdf"
            note = "공개 개인정보보호 안내서 baseline"
        },
        @{
            name = "financial_statement_negative_control"
            file = "samples\\financial-statement.pdf"
            note = "공개 재무제표 샘플 baseline"
        },
        @{
            name = "korean_complex_negative_control"
            file = "samples\\korean-complex.pdf"
            note = "공개 한글 복합 PDF baseline"
        }
    )

    foreach ($case in $cases) {
        $response = Invoke-MultipartJson -Url "$baseUrl/api/v1/pii/detect" -Form @{
            file = Get-Item (Join-Path $root $case.file)
        }
        $summary = Get-DetectionSummary -Response $response
        $results.Add([pscustomobject][ordered]@{
            name = $case.name
            file = $case.file
            note = $case.note
            findingCount = $summary.findingCount
            types = @($summary.types)
            pages = @($summary.pages)
            findings = @($summary.findings)
        })
    }

    $maskResponse = Invoke-MultipartJson -Url "$baseUrl/api/v1/pii/mask" -Form @{
        file = Get-Item (Join-Path $root "samples\\pii\\synthetic-korean-pii-full.pdf")
    }
    $maskedTarget = Join-Path $downloadDir $maskResponse.maskedFilename
    Download-File -Url $maskResponse.maskedDownloadUrl -TargetPath $maskedTarget
    $redetect = Invoke-MultipartJson -Url "$baseUrl/api/v1/pii/detect" -Form @{
        file = Get-Item $maskedTarget
    }
    $redetectSummary = Get-DetectionSummary -Response $redetect
    $results.Add([pscustomobject][ordered]@{
        name = "synthetic_full_mask"
        file = "samples\\pii\\synthetic-korean-pii-full.pdf"
        note = "마스킹 API 호출 후 masked PDF 재탐지"
        findingCount = $maskResponse.findingCount
        types = @($maskResponse.findings | ForEach-Object { $_.type } | Sort-Object -Unique)
        pages = @($maskResponse.findings | ForEach-Object { $_.pageNumber } | Sort-Object -Unique)
        findings = @($maskResponse.findings)
        maskedDownloadUrl = $maskResponse.maskedDownloadUrl
        redetectFindingCount = $redetectSummary.findingCount
    })

    $unsupportedFile = Join-Path $downloadDir "unsupported.txt"
    Set-Content -Path $unsupportedFile -Value "not a pdf" -Encoding UTF8
    $unsupported = Invoke-RawRequest -Method "Post" -Url "$baseUrl/api/v1/pii/detect" -Form @{
        file = Get-Item $unsupportedFile
    }
    $validations.Add([pscustomobject][ordered]@{
        name = "detect_unsupported_text_file"
        expectedStatus = 415
        actualStatus = $unsupported.statusCode
        note = "Only PDF, PNG, JPG, JPEG are supported."
    })

    $missing = Invoke-RawRequest -Method "Get" -Url "$baseUrl/api/v1/pii/files/not-found" -Form @{}
    $validations.Add([pscustomobject][ordered]@{
        name = "download_missing_masked_file"
        expectedStatus = 404
        actualStatus = $missing.statusCode
        note = "Unknown artifact id should return not found."
    })

    $pngNote = [ordered]@{
        status = "SKIPPED"
        note = "사용자 요청에 따라 PNG vLLM OCR 경로는 구현만 하고, vLLM endpoint 미구성 상태에서 실행 검증은 생략했습니다."
    }

    $payload = [ordered]@{
        executedAt = (Get-Date).ToString("o")
        baseUrl = $baseUrl
        results = $results
        validations = $validations
        png = $pngNote
    }

    $payload | ConvertTo-Json -Depth 10 | Set-Content -Path $rawResultsFile -Encoding UTF8
    $report = Convert-ResultToMarkdown -Results $results -PngNote $pngNote -Validations $validations
    Set-Content -Path $reportFile -Value $report -Encoding UTF8

    Write-Host "Report written to $reportFile"
    Write-Host "Raw results written to $rawResultsFile"
}
finally {
    if ($appProcess) {
        try {
            if (-not $appProcess.HasExited) {
                Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {
        }
    }
    Pop-Location
}
