param(
    [string]$AppImage = "jadp:latest",
    [string]$HybridCpuImage = "jadp-hybrid-cpu:latest",
    [string]$HybridGpuImage = "jadp-hybrid-gpu:latest",
    [ValidateSet("cpu", "gpu", "all")]
    [string]$HybridTarget = "cpu",
    [switch]$RunTests,
    [switch]$SkipApp,
    [switch]$SkipHybrid
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-MavenCommand {
    if ($env:MAVEN_CMD) {
        return $env:MAVEN_CMD
    }

    $mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($mvn) {
        return $mvn.Source
    }

    $ideaMaven = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.1\plugins\maven\lib\maven3\bin\mvn.cmd"
    if (Test-Path $ideaMaven) {
        return $ideaMaven
    }

    throw "mvn.cmd 를 찾지 못했습니다. MAVEN_CMD 환경변수 또는 mvn.cmd PATH 를 설정하세요."
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $root

try {
    if (-not $SkipApp) {
        $mavenCommand = Resolve-MavenCommand
        $mavenArgs = @("-s", ".mvn-local-settings.xml")
        if (-not $RunTests) {
            $mavenArgs += "-DskipTests"
        }
        $mavenArgs += "package"

        & $mavenCommand @mavenArgs

        $jar = Get-ChildItem -Path target -Filter "*.jar" |
                Where-Object { $_.Name -notlike "*original*" } |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1

        if (-not $jar) {
            throw "target 디렉터리에서 실행용 JAR 을 찾지 못했습니다."
        }

        docker build --build-arg "JAR_FILE=target/$($jar.Name)" -t $AppImage -f Dockerfile .
    }

    if (-not $SkipHybrid) {
        if ($HybridTarget -in @("cpu", "all")) {
            docker build -t $HybridCpuImage -f docker/hybrid/Dockerfile.cpu .
        }
        if ($HybridTarget -in @("gpu", "all")) {
            docker build -t $HybridGpuImage -f docker/hybrid/Dockerfile.gpu .
        }
    }

    Write-Host "App image: $AppImage"
    Write-Host "Hybrid CPU image: $HybridCpuImage"
    Write-Host "Hybrid GPU image: $HybridGpuImage"
} finally {
    Pop-Location
}
