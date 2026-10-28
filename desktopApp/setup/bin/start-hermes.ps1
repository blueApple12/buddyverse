# Makes sure the PC side of Buddyverse runs, without windows: the Hermes gateway and the Cloudflare link the phone
# uses. Starts only what isn't running, so the phone link stays the same while its tunnel is up. Run by hand or by the
# Buddyverse app when it opens; nothing here runs at sign-in. Prints the phone link.
#   -NoTunnel   only the gateway (no cloudflared, no phone link): headless `--setup --skip-phone`, CI
# Env (set by the app for a second install beside another one): BUDDYVERSE_HERMES_HOME, BUDDYVERSE_PORT.
param([switch]$NoTunnel)
$ErrorActionPreference = 'Stop'
$h = if ($env:BUDDYVERSE_HERMES_HOME) { $env:BUDDYVERSE_HERMES_HOME } else { "$env:LOCALAPPDATA\hermes" }
$port = if ($env:BUDDYVERSE_PORT) { [int]$env:BUDDYVERSE_PORT } else { 9120 }
$local = "http://127.0.0.1:$port"
$hermes = "$h\bin\hermes.exe"
$bin = $PSScriptRoot
$cloudflared = "$bin\cloudflared.exe"
$linkFile = "$bin\phone-link.txt"
# Every hermes call below works on this install's home, whatever shell started the script.
Get-ChildItem Env: | Where-Object { $_.Name -like 'HERMES_*' } | ForEach-Object { Remove-Item "Env:$($_.Name)" -ErrorAction SilentlyContinue }
$env:HERMES_HOME = $h

# Hidden and outside whoever ran this script, so it keeps running when the script ends.
function Start-Hidden([string]$CommandLine) {
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
    $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = $CommandLine; CurrentDirectory = $bin; ProcessStartupInformation = $startup }
    if ($r.ReturnValue -ne 0) { throw "could not start: $CommandLine" }
}

function Test-Url([string]$Url) {
    try { $null = Invoke-WebRequest "$Url/api/status" -UseBasicParsing -TimeoutSec 8; $true } catch { $false }
}

function Wait-Gateway {
    for ($i = 0; $i -lt 90; $i++) { if (Test-Url $local) { return } ; Start-Sleep -Seconds 2 }
    throw 'the gateway did not come up'
}

# The tunnel is up when its link answers at all; Cloudflare's 530 means the tunnel itself is gone. Any other answer
# (502 while the gateway is down, for one) keeps the link.
function Test-Tunnel([string]$Url) {
    try { $null = Invoke-WebRequest "$Url/api/status" -UseBasicParsing -TimeoutSec 8; return $true }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        return ($null -ne $status -and $status -ne 530)
    }
}

# Only this install's cloudflared: other programs (or another install) may run their own tunnels.
function Get-OurTunnel { Get-Process cloudflared -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $cloudflared } }

$gatewayUp = Test-Url $local
$link = $local

if (-not $NoTunnel) {
    $link = if (Test-Path $linkFile) { (Get-Content $linkFile -Raw).Trim() } else { '' }
    $tunnelUp = (Get-OurTunnel) -and $link -and (Test-Tunnel $link)
    if (-not $tunnelUp) {
        # A quick-tunnel link dies with its cloudflared: start a fresh one and point the Webapp at it.
        Get-OurTunnel | Stop-Process -Force -PassThru | Wait-Process -Timeout 10 -ErrorAction SilentlyContinue
        # A fresh log per run: an old one can still hold the dead tunnel's link.
        Get-ChildItem "$bin\cloudflared*.log" -ErrorAction SilentlyContinue | Remove-Item -ErrorAction SilentlyContinue
        $log = "$bin\cloudflared-$([DateTime]::Now.Ticks).log"
        Start-Hidden "`"$cloudflared`" tunnel --no-autoupdate --url $local --logfile `"$log`""
        $link = $null
        for ($i = 0; $i -lt 45 -and -not $link; $i++) {
            Start-Sleep -Seconds 2
            if (Test-Path $log) {
                $m = Select-String -Path $log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' | Select-Object -First 1
                if ($m) { $link = $m.Matches[0].Value }
            }
        }
        if (-not $link) { throw 'Cloudflare gave no link' }
        Set-Content -Path $linkFile -Value $link -Encoding utf8
        # The gateway reads public_url when it starts; with it set, sign-in is required over the link.
        & $hermes config set webapp.public_url $link | Out-Null
        if ($gatewayUp) { & $hermes gateway stop *> $null; $gatewayUp = $false }
    }
}

if (-not $gatewayUp) {
    # Win32_Process.Create starts from the user's saved environment, not this script's: a non-default Hermes home
    # (BUDDYVERSE_HERMES_HOME) has to be passed on explicitly, or the gateway would serve the default home.
    if ($env:BUDDYVERSE_HERMES_HOME) { Start-Hidden "cmd.exe /d /c set `"HERMES_HOME=$h`" && `"$hermes`" gateway run" }
    else { Start-Hidden "`"$hermes`" gateway run" }
    Wait-Gateway
}
$link
