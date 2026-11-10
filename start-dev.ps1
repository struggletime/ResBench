$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$resbenchmarkRootDir = Join-Path $root "backend"
$resbenchmarkDir = Join-Path $root "backend\resbenchmark"
$resManagerDir = Join-Path $root "backend\res-manager"
$frontendDir = Join-Path $root "frontend"
$logsDir = Join-Path $root ".codex\run-logs"

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

function Stop-PortProcess {
    param(
        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if (-not $connections) { return }

    $killed = @{}
    foreach ($conn in $connections) {
        $procId = $conn.OwningProcess
        if ($killed.ContainsKey($procId)) { continue }
        try {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($null -ne $proc) {
                Write-Host "Killing process $($proc.ProcessName) (PID $procId) on port $Port"
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
                $killed[$procId] = $true
            }
        } catch {}
    }

    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-PortListening -Port $Port)) { break }
        Start-Sleep -Milliseconds 500
    }
}

function Test-PortListening {
    param(
        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    $connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($null -ne $connection) {
        return $true
    }

    $listener = [System.Net.Sockets.TCPClient]::new()
    try {
        $asyncResult = $listener.BeginConnect("127.0.0.1", $Port, $null, $null)
        if ($asyncResult.AsyncWaitHandle.WaitOne(100, $false)) {
            try { $listener.EndConnect($asyncResult) } catch {}
            return $true
        } else {
            $listener.Dispose()
            $listener = [System.Net.Sockets.TCPClient]::new()
            $asyncResult = $listener.BeginConnect("::1", $Port, $null, $null)
            if ($asyncResult.AsyncWaitHandle.WaitOne(100, $false)) {
                try { $listener.EndConnect($asyncResult) } catch {}
                return $true
            }
        }
        return $false
    } catch {
        return $false
    } finally {
        $listener.Dispose()
    }
}

function Start-BackgroundService {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory,
        [Parameter(Mandatory = $true)]
        [string]$Command,
        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    $stdoutLog = Join-Path $logsDir "$Name.out.log"
    $stderrLog = Join-Path $logsDir "$Name.err.log"
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command))

    Write-Host "Starting $Name on port $Port"
    Start-Process `
        -FilePath "powershell.exe" `
        -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -ArgumentList @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-EncodedCommand",
            $encodedCommand
        ) | Out-Null
}

function Wait-ForPort {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [int]$Port,
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening -Port $Port) {
            Write-Host "$Name is ready on port $Port"
            return
        }
        Start-Sleep -Seconds 1
    }

    $stdoutLog = Join-Path $logsDir "$Name.out.log"
    $stderrLog = Join-Path $logsDir "$Name.err.log"

    Write-Host "$Name failed to start on port $Port within $TimeoutSeconds seconds" -ForegroundColor Red

    if (Test-Path $stderrLog) {
        Write-Host "---- $Name stderr (tail) ----" -ForegroundColor Yellow
        Get-Content $stderrLog -Tail 80
    }

    if (Test-Path $stdoutLog) {
        Write-Host "---- $Name stdout (tail) ----" -ForegroundColor Yellow
        Get-Content $stdoutLog -Tail 80
    }

    throw "$Name failed to start"
}

function Invoke-PrecompileDependencies {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory
    )

    Write-Host "Precompiling dependency modules"
    Push-Location -LiteralPath $WorkingDirectory
    try {
        & mvn -q "-DskipTests" "-Dmaven.gitcommitid.skip=true" -pl resbenchmark-common -am install
        if ($LASTEXITCODE -ne 0) {
            throw "Dependency precompile failed"
        }
        Write-Host "Dependency precompile completed"
    }
    finally {
        Pop-Location
    }
}

# ---- Kill old processes before starting anything ----
Write-Host "Stopping old services..." -ForegroundColor Cyan
Stop-PortProcess -Port 8080
Stop-PortProcess -Port 8090
Write-Host "Old services stopped" -ForegroundColor Cyan

# ---- Precompile dependencies ----
Invoke-PrecompileDependencies -WorkingDirectory $resbenchmarkRootDir

# ---- Build resbenchmark (clean compile) ----
Write-Host "Building resbenchmark (clean compile)"
Push-Location -LiteralPath $resbenchmarkDir
try {
    & mvn -q "-DskipTests" "-Dmaven.gitcommitid.skip=true" "-P" "opengauss,mysql" clean compile
    if ($LASTEXITCODE -ne 0) {
        throw "resbenchmark compile failed"
    }
    Write-Host "resbenchmark build ready"

    # Generate runtime classpath for forked engine process.
    # The opengauss profile sets project classifier, which confuses build-classpath
    # when resolving internal dependencies, so we override it to empty here.
    Write-Host "Generating runtime classpath file"
    & mvn -q "-P" "opengauss,mysql" "-Dclassifier=" dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" "-Dmdep.stripClassifier=true"
    if ($LASTEXITCODE -ne 0) {
        throw "runtime classpath generation failed"
    }
    Write-Host "Runtime classpath file generated"
} finally {
    Pop-Location
}

# ---- Build res-manager (clean package) ----
Write-Host "Building res-manager (clean package)"
Push-Location -LiteralPath $resManagerDir
try {
    & mvn -q "-DskipTests" "-Dmaven.gitcommitid.skip=true" clean package
    if ($LASTEXITCODE -ne 0) {
        throw "res-manager package failed"
    }
    Write-Host "res-manager build ready"
} finally {
    Pop-Location
}

# ---- Start services ----
$resManagerJar = Get-ChildItem -Path (Join-Path $resManagerDir "target") -Filter "*.jar" | Where-Object { $_.Name -notlike "*sources*" -and $_.Name -notlike "*javadoc*" } | Select-Object -First 1
if (-not $resManagerJar) { throw "res-manager jar not found in target/" }
Write-Host "Using res-manager jar: $($resManagerJar.Name)"

Start-BackgroundService `
    -Name "resbenchmark-server" `
    -WorkingDirectory $resbenchmarkDir `
    -Port 8080 `
    -Command '& { mvn -q "-DskipTests" "-P" "opengauss,mysql" exec:java "-Dexec.mainClass=com.resbench.Application" "-Dexec.args=server" }'
Wait-ForPort -Name "resbenchmark-server" -Port 8080

Start-BackgroundService `
    -Name "res-manager" `
    -WorkingDirectory $resManagerDir `
    -Port 8090 `
    -Command "& { java -jar `"$($resManagerJar.FullName)`" }"
Wait-ForPort -Name "res-manager" -Port 8090

# ---- Frontend ----
Set-Location -LiteralPath $frontendDir

if (-not (Test-Path (Join-Path $frontendDir "node_modules"))) {
    npm.cmd install
}

npm.cmd run dev -- --host 0.0.0.0
