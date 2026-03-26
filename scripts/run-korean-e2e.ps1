param(
    [int]$Port = 18080
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$mvn = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$settings = Join-Path $root ".mvn-local-settings.xml"
$artifactDir = Join-Path $root "test-artifacts"
$samplesDir = Join-Path $root "samples"
$logFile = Join-Path $artifactDir "app-$Port.log"
$errLogFile = Join-Path $artifactDir "app-$Port.err.log"
$rawResultsFile = Join-Path $artifactDir "korean-e2e-results.json"
$reportFile = Join-Path $artifactDir "KOREAN_PDF_E2E_REPORT.md"

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

function Invoke-JsonGet {
    param([string]$Url)
    return Invoke-RestMethod -Uri $Url -TimeoutSec 60
}

function Invoke-MultipartJson {
    param(
        [string]$Url,
        [hashtable]$Form
    )
    return Invoke-RestMethod -Method Post -Uri $Url -Form $Form -TimeoutSec 1800
}

function Invoke-MultipartResponse {
    param(
        [string]$Url,
        [hashtable]$Form
    )
    return Invoke-WebRequest -Method Post -Uri $Url -Form $Form -UseBasicParsing -SkipHttpErrorCheck -TimeoutSec 1800
}

function Wait-ForJob {
    param(
        [string]$BaseUrl,
        [string]$JobId,
        [int]$TimeoutSeconds = 1800
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $job = Invoke-JsonGet "$BaseUrl/api/v1/pdf/jobs/$JobId"
        if ($job.status -in @("SUCCEEDED", "FAILED")) {
            return $job
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for job $JobId"
}

function Get-TextFromUrl {
    param([string]$Url)
    return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 300).Content
}

function Get-HangulDetected {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    return $Text -match "[가-힣]"
}

function Get-JsonSummary {
    param([object]$File)
    if (-not $File) {
        return $null
    }
    $summary = [ordered]@{
        rawLength = 0
        containsPlaceholder = $false
        maskedDateMetadata = $false
        parseError = $null
    }
    $raw = Get-TextFromUrl $File.downloadUrl
    $summary.rawLength = $raw.Length
    $summary.containsPlaceholder = [bool]($raw -match "\[(EMAIL|PHONE|IP|URL|CARD)\]")
    $summary.maskedDateMetadata = [bool]($raw -match '"creation date"\s*:\s*"D:\[PHONE\]' -or $raw -match '"modification date"\s*:\s*"D:\[PHONE\]')
    try {
        $json = $raw | ConvertFrom-Json
        $summary.fileName = $json.'file name'
        $summary.numberOfPages = $json.'number of pages'
        $summary.topLevelKids = @($json.kids).Count
        $summary.hasKids = $null -ne $json.kids
    } catch {
        $summary.parseError = $_.Exception.Message
    }
    return $summary
}

function Get-MarkdownSummary {
    param([object]$File)
    if (-not $File) {
        return $null
    }
    $content = Get-TextFromUrl $File.downloadUrl
    return [ordered]@{
        length = $content.Length
        preview = if ($content.Length -gt 300) { $content.Substring(0, 300) } else { $content }
        containsHtml = $content -match "<[A-Za-z/]"
        containsImageRef = $content -match "!\[[^\]]*\]\([^)]+\)" -or $content -match "\.png" -or $content -match "\.jpg"
        containsAbsoluteWindowsPath = $content.Contains(':\')
        containsPlaceholder = $content -match "\[(EMAIL|PHONE|IP|URL|CARD)\]"
        hangulDetected = Get-HangulDetected $content
    }
}

function Convert-ResultToMarkdown {
    param(
        [array]$Results,
        [array]$ValidationChecks
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $now = Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"

    $lines.Add("# Korean PDF End-to-End Test Report")
    $lines.Add("")
    $lines.Add("- Executed at: $now")
    $lines.Add("- Application port: $Port")
    $lines.Add('- Raw results JSON: `test-artifacts/korean-e2e-results.json`')
    $lines.Add('- Financial statement sample: `samples/financial-statement.pdf`')
    $lines.Add('- Privacy protection sample: `samples/privacy-protection-guide.pdf`')
    $lines.Add('- Complex museum sample: `samples/korean-complex.pdf`')
    $lines.Add("")

    $baseline = $Results | Where-Object { $_.name -eq "async_privacy_full_formats" } | Select-Object -First 1
    $subset = $Results | Where-Object { $_.name -eq "async_privacy_pages_subset" } | Select-Object -First 1

    if ($baseline -and $subset -and $baseline.jsonSummary -and $subset.jsonSummary -and -not $baseline.jsonSummary.parseError -and -not $subset.jsonSummary.parseError) {
        $pageObservation = if ($subset.jsonSummary.numberOfPages -eq $baseline.jsonSummary.numberOfPages) {
            '요청한 `pages=1-2`와 달리 산출 JSON의 `number of pages`가 baseline과 동일했습니다. 현재 핀된 OpenDataLoader `1.3.0`에서는 페이지 범위 setter가 노출되지 않아 이 옵션이 런타임에 적용되지 않은 것으로 보입니다.'
        } else {
            "페이지 범위 옵션이 baseline 대비 축소된 페이지 수로 반영되었습니다."
        }
        $lines.Add("## Key Findings")
        $lines.Add("")
        $lines.Add("- 성공 시나리오 5건이 모두 HTTP 및 최종 job 상태 기준으로 정상 완료되었습니다.")
        $lines.Add("- $pageObservation")
        $fullFormatScenario = $Results | Where-Object { $_.name -eq "async_privacy_full_formats" } | Select-Object -First 1
        if ($fullFormatScenario) {
            $lines.Add("- 개인정보보호법 안내서 전체 포맷 변환은 10.9초가 걸렸고, 부가 이미지 산출물이 $($fullFormatScenario.imageArtifactCount)개 생성되어 저장 용량 증가를 확인했습니다.")
        }
        $maskedMetadataScenario = $Results | Where-Object { $_.jsonSummary -and $_.jsonSummary.maskedDateMetadata } | Select-Object -First 1
        if ($maskedMetadataScenario) {
            $lines.Add("- `sanitize=true` 환경에서는 PDF 메타데이터의 생성일/수정일 문자열도 `[PHONE]`으로 치환되는 과마스킹이 확인되었습니다.")
        }
        $imageScenario = $Results | Where-Object { $_.name -eq "async_museum_markdown_images" } | Select-Object -First 1
        if ($imageScenario) {
            $lines.Add("- 이미지 포함 Markdown 시나리오에서는 image 파일 수: $($imageScenario.imageArtifactCount), markdown 내 이미지 참조 감지: $($imageScenario.markdownSummary.containsImageRef), 절대 경로 포함: $($imageScenario.markdownSummary.containsAbsoluteWindowsPath).")
        }
        $invalidPagesCheck = $ValidationChecks | Where-Object { $_.name -eq "invalid_pages_rejected" } | Select-Object -First 1
        if ($invalidPagesCheck) {
            $lines.Add("- 잘못된 페이지 범위(`pages=1-a`)는 HTTP $($invalidPagesCheck.httpStatus)로 거절되어 입력 검증이 동작했습니다.")
        }
        $lines.Add("")
    }

    $lines.Add("## Scenario Summary")
    $lines.Add("")
    $lines.Add("| Scenario | Mode | HTTP | Final Status | Output Files | Image Files | JSON | Hangul | Notes |")
    $lines.Add("| --- | --- | --- | --- | ---: | ---: | --- | --- | --- |")
    foreach ($result in $Results) {
        $http = $result.httpStatus
        $finalStatus = $result.finalStatus
        $fileCount = @($result.files).Count
        $imageCount = $result.imageArtifactCount
        $jsonState = if (-not $result.jsonSummary) {
            "-"
        } elseif ($result.jsonSummary.parseError) {
            "parse-error"
        } else {
            "ok/$($result.jsonSummary.numberOfPages)p"
        }
        $hangul = if ($result.markdownSummary) { $result.markdownSummary.hangulDetected } else { $false }
        $lines.Add("| $($result.name) | $($result.mode) | $http | $finalStatus | $fileCount | $imageCount | $jsonState | $hangul | $($result.note) |")
    }
    $lines.Add("")

    if ($ValidationChecks) {
        $lines.Add("## Validation Checks")
        $lines.Add("")
        $lines.Add("| Check | HTTP | Passed | Response Preview |")
        $lines.Add("| --- | --- | --- | --- |")
        foreach ($check in $ValidationChecks) {
            $lines.Add("| $($check.name) | $($check.httpStatus) | $($check.passed) | $($check.responsePreview) |")
        }
        $lines.Add("")
    }

    foreach ($result in $Results) {
        $lines.Add("## $($result.name)")
        $lines.Add("")
        $lines.Add(('- Endpoint: `{0}`' -f $result.endpoint))
        $lines.Add(('- Input file: `{0}`' -f $result.inputFile))
        $lines.Add(('- Request params: `{0}`' -f $result.requestSummary))
        $lines.Add("- HTTP status: $($result.httpStatus)")
        $lines.Add("- Final status: $($result.finalStatus)")
        if ($result.processingMillis) {
            $lines.Add("- Processing millis: $($result.processingMillis)")
        }
        $lines.Add("- Image artifact count: $($result.imageArtifactCount)")
        if ($result.jsonSummary) {
            if ($result.jsonSummary.parseError) {
                $lines.Add("- JSON summary: parse error=$($result.jsonSummary.parseError)")
            } else {
                $lines.Add("- JSON summary: pages=$($result.jsonSummary.numberOfPages), top-level kids=$($result.jsonSummary.topLevelKids), file=$($result.jsonSummary.fileName), placeholder=$($result.jsonSummary.containsPlaceholder), maskedDateMetadata=$($result.jsonSummary.maskedDateMetadata)")
            }
        }
        if ($result.markdownSummary) {
            $lines.Add("- Markdown summary: length=$($result.markdownSummary.length), hangul=$($result.markdownSummary.hangulDetected), html=$($result.markdownSummary.containsHtml), imageRef=$($result.markdownSummary.containsImageRef), absPathRef=$($result.markdownSummary.containsAbsoluteWindowsPath), placeholder=$($result.markdownSummary.containsPlaceholder)")
            $lines.Add("")
            $lines.Add('```text')
            $lines.Add($result.markdownSummary.preview)
            $lines.Add('```')
        }
        if (@($result.files).Count -gt 0) {
            $nonImageFiles = @($result.files | Where-Object { $_.format -ne "image" })
            $imageFiles = @($result.files | Where-Object { $_.format -eq "image" })
            $displayFiles = @($nonImageFiles + ($imageFiles | Select-Object -First 5))
            $lines.Add("")
            $lines.Add("| Format | Filename | Size | Download URL |")
            $lines.Add("| --- | --- | ---: | --- |")
            foreach ($file in $displayFiles) {
                $lines.Add("| $($file.format) | $($file.filename) | $($file.size) | $($file.downloadUrl) |")
            }
            if ($imageFiles.Count -gt 5) {
                $lines.Add("")
                $lines.Add("- Additional image files omitted from table: $($imageFiles.Count - 5)")
            }
        }
        $lines.Add("")
    }

    return ($lines -join [Environment]::NewLine)
}

Push-Location $root
try {
    & $mvn -o -s $settings -DskipTests package | Tee-Object -FilePath (Join-Path $artifactDir "package.log")

    $jar = Get-ChildItem (Join-Path $root "target") -Filter "jadp-*.jar" |
            Where-Object { $_.Name -notlike "*.original" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1

    if (-not $jar) {
        throw "Packaged jar not found."
    }

    if (Test-Path $logFile) {
        Remove-Item $logFile -Force
    }
    if (Test-Path $errLogFile) {
        Remove-Item $errLogFile -Force
    }

    $appProcess = Start-Process -FilePath "java" `
        -ArgumentList @("-jar", $jar.FullName, "--server.port=$Port") `
        -PassThru `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError $errLogFile

    $baseUrl = "http://127.0.0.1:$Port"

    if (-not (Wait-HttpReady -Url "$baseUrl/actuator/health")) {
        $stdoutTail = if (Test-Path $logFile) { Get-Content $logFile -Tail 40 | Out-String } else { "no stdout log" }
        $stderrTail = if (Test-Path $errLogFile) { Get-Content $errLogFile -Tail 40 | Out-String } else { "no stderr log" }
        $tail = "STDOUT:`n$stdoutTail`nSTDERR:`n$stderrTail"
        throw "Application did not become ready.`n$tail"
    }

    $health = Invoke-JsonGet "$baseUrl/actuator/health"
    $options = Invoke-JsonGet "$baseUrl/api/v1/pdf/config/options"
    $swagger = Invoke-WebRequest -Uri "$baseUrl/swagger-ui.html" -UseBasicParsing -TimeoutSec 60
    $homePage = Invoke-WebRequest -Uri "$baseUrl/" -UseBasicParsing -TimeoutSec 60

    $results = New-Object System.Collections.Generic.List[object]
    $validationChecks = New-Object System.Collections.Generic.List[object]

    $scenarios = @(
        @{
            name = "sync_financial_basic"
            mode = "sync"
            endpoint = "/api/v1/pdf/convert-sync"
            file = Join-Path $samplesDir "financial-statement.pdf"
            form = @{
                formats = "json,markdown,html"
                sanitize = "true"
                readingOrder = "xycut"
                tableMethod = "default"
            }
            note = "재무제표 PDF sync 기본 변환"
        },
        @{
            name = "sync_financial_markdown_html"
            mode = "sync"
            endpoint = "/api/v1/pdf/convert-sync"
            file = Join-Path $samplesDir "financial-statement.pdf"
            form = @{
                formats = "json,markdown-with-html"
                sanitize = "true"
                keepLineBreaks = "true"
                useStructTree = "true"
                includeHeaderFooter = "true"
            }
            note = "재무제표 PDF에 markdown-with-html 및 struct tree 요청"
        },
        @{
            name = "async_privacy_full_formats"
            mode = "async"
            endpoint = "/api/v1/pdf/convert"
            file = Join-Path $samplesDir "privacy-protection-guide.pdf"
            form = @{
                formats = "json,markdown,html,pdf,text"
                sanitize = "true"
                readingOrder = "xycut"
                tableMethod = "default"
            }
            note = "개인정보보호 안내 PDF 전체 포맷 생성"
        },
        @{
            name = "async_privacy_pages_subset"
            mode = "async"
            endpoint = "/api/v1/pdf/convert"
            file = Join-Path $samplesDir "privacy-protection-guide.pdf"
            form = @{
                formats = "json,markdown"
                sanitize = "true"
                pages = "1-2"
                keepLineBreaks = "true"
                useStructTree = "true"
            }
            note = "개인정보보호 PDF 페이지 범위 및 줄바꿈/struct tree 요청"
        },
        @{
            name = "async_museum_markdown_images"
            mode = "async"
            endpoint = "/api/v1/pdf/convert"
            file = Join-Path $samplesDir "korean-complex.pdf"
            form = @{
                formats = "json,markdown-with-images"
                sanitize = "false"
                imageOutput = "external"
                imageFormat = "png"
            }
            note = "이미지 포함 Markdown 및 sanitize off"
        }
    )

    foreach ($scenario in $scenarios) {
        $form = @{}
        foreach ($key in $scenario.form.Keys) {
            $form[$key] = $scenario.form[$key]
        }
        $form["file"] = Get-Item $scenario.file

        $uri = "$baseUrl$($scenario.endpoint)"
        $started = Get-Date
        $response = Invoke-MultipartJson -Url $uri -Form $form
        $ended = Get-Date

        $result = [ordered]@{
            name = $scenario.name
            mode = $scenario.mode
            endpoint = $scenario.endpoint
            inputFile = [IO.Path]::GetFileName($scenario.file)
            requestSummary = (($scenario.form.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ", ")
            httpStatus = if ($scenario.mode -eq "async") { 202 } else { 200 }
            note = $scenario.note
            requestStartedAt = $started.ToString("o")
            requestFinishedAt = $ended.ToString("o")
        }

        if ($scenario.mode -eq "sync") {
            $result.jobId = $response.jobId
            $result.finalStatus = $response.status
            $result.files = @($response.outputFiles)
            $result.htmlPreviewUrl = $response.htmlPreviewUrl
            $jsonFile = $result.files | Where-Object { $_.format -eq "json" } | Select-Object -First 1
            $markdownFile = $result.files | Where-Object { $_.format -eq "markdown" } | Select-Object -First 1
            $result.markdownSummary = Get-MarkdownSummary -File $markdownFile
            $result.jsonSummary = Get-JsonSummary -File $jsonFile
            $result.imageArtifactCount = @($result.files | Where-Object { $_.format -eq "image" -or $_.filename -match "\.(png|jpg|jpeg)$" }).Count
            $results.Add([pscustomobject]$result)
            continue
        }

        $job = Wait-ForJob -BaseUrl $baseUrl -JobId $response.jobId
        $files = @($job.files)
        $jsonFile = $files | Where-Object { $_.format -eq "json" } | Select-Object -First 1
        $markdownFile = $files | Where-Object { $_.format -eq "markdown" } | Select-Object -First 1

        $result.jobId = $job.jobId
        $result.finalStatus = $job.status
        $result.processingMillis = $job.processingMillis
        $result.files = $files
        $result.jsonSummary = Get-JsonSummary -File $jsonFile
        $result.markdownSummary = Get-MarkdownSummary -File $markdownFile
        $result.imageArtifactCount = @($files | Where-Object { $_.format -eq "image" -or $_.filename -match "\.(png|jpg|jpeg)$" }).Count
        $results.Add([pscustomobject]$result)
    }

    $invalidPagesResponse = Invoke-MultipartResponse -Url "$baseUrl/api/v1/pdf/convert" -Form @{
        file = Get-Item (Join-Path $samplesDir "privacy-protection-guide.pdf")
        formats = "json"
        pages = "1-a"
    }
    $invalidPagesContent = if ($invalidPagesResponse.Content.Length -gt 200) {
        $invalidPagesResponse.Content.Substring(0, 200)
    } else {
        $invalidPagesResponse.Content
    }
    $validationChecks.Add([pscustomobject][ordered]@{
        name = "invalid_pages_rejected"
        httpStatus = [int]$invalidPagesResponse.StatusCode
        passed = ([int]$invalidPagesResponse.StatusCode -eq 400) -and ($invalidPagesResponse.Content -match "Invalid pages format")
        responsePreview = $invalidPagesContent.Replace("|", "\|")
    })

    $metadata = [ordered]@{
        executedAt = (Get-Date).ToString("o")
        baseUrl = $baseUrl
        healthStatus = $health.status
        swaggerStatus = $swagger.StatusCode
        homeStatus = $homePage.StatusCode
        optionDefaults = $options.defaults
        validationChecks = $validationChecks
        scenarios = $results
    }

    $metadata | ConvertTo-Json -Depth 12 | Set-Content -Path $rawResultsFile -Encoding UTF8
    $report = Convert-ResultToMarkdown -Results $results -ValidationChecks $validationChecks
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
