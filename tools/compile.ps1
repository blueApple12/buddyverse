# Compiles the app with one warm Gradle daemon, one agent at a time: a named mutex makes parallel agents take turns
# instead of each starting its own daemon (7 GB each). Prints only errors and the result.
#   powershell -File tools\compile.ps1                         # shared module, desktop target (fastest check)
#   powershell -File tools\compile.ps1 :androidApp:assembleDebug
#   powershell -File tools\compile.ps1 :desktopApp:packageExe -Online   # -Online: may download new dependencies
param([string]$Task = ':shared:compileKotlinDesktop', [switch]$Online)
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Join-Path (Split-Path $root -Parent) '.build-tools\jdk\jdk-21.0.12.1+1'
$mutex = New-Object System.Threading.Mutex($false, 'Local\BuddyverseAppGradle')
[void]$mutex.WaitOne()
try {
    $mode = @(if (-not $Online) { '--offline' })
    & "$root\gradlew.bat" -p $root @mode --console=plain $Task 2>&1 |
        Select-String -Pattern '^e: |error:|warning: unresolved|BUILD (SUCCESSFUL|FAILED)|What went wrong' |
        ForEach-Object { $_.Line }
} finally {
    $mutex.ReleaseMutex()
}
