param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path $PSScriptRoot).Path
$repository = Split-Path $backend -Parent
$runId = [guid]::NewGuid().ToString('N')
$testRoot = Join-Path $env:TEMP "oneplace-api-test-$runId"
$ports = @(18080..18088) + 18761
$apiPassed = $false

foreach ($port in $ports) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $port is already in use. Stop the process using it before running isolated API tests."
    }
}

New-Item -ItemType Directory -Path $testRoot | Out-Null
Write-Host "Isolated test data and logs: $testRoot"

if (-not $SkipBuild) {
    Push-Location $backend
    try {
        & .\gradlew.bat :gateway:bootJar :auth-service:bootJar :catalog-service:bootJar :cart-service:bootJar :classroom-service:bootJar :feed-service:bootJar :studio-editor-service:bootJar :quiz-service:bootJar :order-delivery-service:bootJar :discovery-service:bootJar
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    } finally { Pop-Location }
}

$services = @(
    @{ Name='auth-service'; Port=18081; Database='auth' },
    @{ Name='catalog-service'; Port=18082; Database='catalog' },
    @{ Name='cart-service'; Port=18083; Database='cart' },
    @{ Name='classroom-service'; Port=18084; Database='classroom' },
    @{ Name='feed-service'; Port=18085; Database='feed' },
    @{ Name='studio-editor-service'; Port=18086; Database='studio' },
    @{ Name='quiz-service'; Port=18087; Database='quiz' },
    @{ Name='order-delivery-service'; Port=18088; Database='delivery' },
    @{ Name='gateway'; Port=18080; Database='gateway' },
    @{ Name='discovery-service'; Port=18761; Database='discovery' }
)

try {
    foreach ($service in $services) {
        $name = $service.Name
        $jar = Join-Path $backend "$name/build/libs/$name-0.1.0.jar"
        if (-not (Test-Path -LiteralPath $jar)) { throw "Missing jar: $jar" }
        $database = (Join-Path $testRoot $service.Database).Replace('\', '/')
        $eurekaEnabled = if ($name -eq 'discovery-service') { 'true' } else { 'false' }
        $arguments = @(
            '-jar', $jar,
            "--server.port=$($service.Port)",
            "--oneplace.test.run-id=$runId",
            "--spring.datasource.url=jdbc:h2:file:$database;DB_CLOSE_ON_EXIT=FALSE",
            "--eureka.client.enabled=$eurekaEnabled",
            '--eureka.client.register-with-eureka=false',
            '--eureka.client.fetch-registry=false',
            "--app.snapshot-dir=$testRoot",
            "--app.resource-dir=$testRoot",
            "--app.user-resource-dir=$testRoot",
            "--app.media-dir=$testRoot",
            "--app.video-dir=$testRoot",
            "--app.quiz-dir=$testRoot",
            '--services.auth=http://127.0.0.1:18081',
            '--services.catalog=http://127.0.0.1:18082',
            '--services.cart=http://127.0.0.1:18083',
            '--services.classroom=http://127.0.0.1:18084',
            '--services.feed=http://127.0.0.1:18085',
            '--services.studio=http://127.0.0.1:18086',
            '--services.quiz=http://127.0.0.1:18087',
            '--services.delivery=http://127.0.0.1:18088'
        )
        Start-Process -FilePath java.exe -ArgumentList $arguments -WorkingDirectory $backend `
            -RedirectStandardOutput (Join-Path $testRoot "$name.out.log") `
            -RedirectStandardError (Join-Path $testRoot "$name.err.log") `
            -WindowStyle Hidden | Out-Null
    }

    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $healthy = 0
        foreach ($port in $ports) {
            try {
                $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 1
                if ($response.StatusCode -eq 200) { $healthy++ }
            } catch {}
        }
        if ($healthy -eq $ports.Count) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw "Only $healthy of $($ports.Count) services became healthy. See logs in $testRoot" }

    & python -u (Join-Path $backend 'api-smoke-test.py')
    if ($LASTEXITCODE -ne 0) { throw 'API assertions failed.' }
    $apiPassed = $true
    Write-Host 'Isolated API suite passed.'
} finally {
    # Java on Windows may launch a child JVM. Match the unique run marker in both processes.
    $owned = Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'java.exe' -and $_.CommandLine -like "*--oneplace.test.run-id=$runId*" -and
        $_.CommandLine -like "*$backend*"
    }
    foreach ($process in $owned) { Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue }
    Write-Host "Stopped $($owned.Count) isolated Java processes; logs remain in $testRoot"
}

if ($apiPassed) {
    $h2Jar = Join-Path $testRoot 'h2.jar'
    $authJar = Join-Path $backend 'auth-service/build/libs/auth-service-0.1.0.jar'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($authJar)
    try {
        $entry = $archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/h2-[0-9].*\.jar$' } | Select-Object -First 1
        if (-not $entry) { throw 'Could not find H2 JDBC driver in auth jar.' }
        $source = $entry.Open()
        $target = [System.IO.File]::Create($h2Jar)
        try { $source.CopyTo($target) } finally { $source.Dispose(); $target.Dispose() }
    } finally { $archive.Dispose() }
    & java --class-path $h2Jar (Join-Path $backend 'VerifyApiDatabases.java') $testRoot
    if ($LASTEXITCODE -ne 0) { throw 'Database assertions failed.' }
}
