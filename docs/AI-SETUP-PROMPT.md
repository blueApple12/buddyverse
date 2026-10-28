# Set up Buddyverse with an AI coding agent

Copy everything inside the box below and paste it into an AI coding agent that can run commands on your Windows PC
(Claude Code, Codex CLI, Gemini CLI, Hermes, or similar). Start the agent in a normal (non-admin) terminal. The agent
asks before it installs anything, and it never asks you to type a password or key into the chat.

````text
You are helping me install and set up Buddyverse on this Windows PC.

Buddyverse is a free, open-source app (https://github.com/blueApple12/buddyverse) that runs Hermes Agent (by Nous
Research, https://hermes-agent.nousresearch.com/docs) on my own PC and shows my AI bots as animated buddies. My phone
connects to my PC through a Cloudflare quick tunnel that my PC opens. Use PowerShell for every command.

SAFETY RULES (follow all of them, always):
1. Ask me before you download, install or start anything. Say what it is and where it comes from.
2. Never ask me to type a password, API key or token into this chat. Never print, echo, log or read back any secret.
   Never show the contents of a password file or of "$env:LOCALAPPDATA\hermes\.env".
3. Do not register anything to run at Windows sign-in (no scheduled tasks, no Startup folder, no Run keys, no services).
4. Do not use admin rights. Everything installs per-user. If something asks for elevation, stop and tell me.
5. Download only from:
   - https://github.com/blueApple12/buddyverse/releases/download/v0.1.0-beta/Buddyverse-Setup.exe
   Buddyverse's own setup then downloads Hermes Agent (https://hermes-agent.nousresearch.com/install.ps1) and
   cloudflared (github.com/cloudflare/cloudflared releases). Nothing else unless I agree.
   This beta installs Hermes' newest CANARY build (its daily preview: the official installer pinned to the newest
   v...+canary tag of github.com/NousResearch/hermes-agent), because Hermes' stable release has no Webapp yet. It skips Hermes' optional
   Chromium browser and computer-use driver to keep the install fast.
6. Do not change, stop or delete an existing Hermes install, its profiles, its .env or its config, except through the
   steps below. If Hermes is already installed, tell me; setup reuses it, but only a canary build from 26 Oct 2026 or
   newer works (one with "$env:LOCALAPPDATA\hermes\hermes-agent\gateway\webapp_listener.py"). If it's older, setup
   stops and prints the command that updates it; show me that command and let me decide.
7. If a step fails, show me the short error and suggest the next step. Don't work around it silently.

STEPS:

Step 1. Check the PC and tell me what you found.
- Windows 10 or 11, 64-bit: [Environment]::OSVersion.Version; [Environment]::Is64BitOperatingSystem
- Internet: Test-NetConnection github.com -Port 443 -InformationLevel Quiet
- Hermes already installed?   Test-Path "$env:LOCALAPPDATA\hermes\bin\hermes.exe"
- If so, does it have the Webapp?   Test-Path "$env:LOCALAPPDATA\hermes\hermes-agent\gateway\webapp_listener.py"
- Buddyverse already installed?   Test-Path "$env:LOCALAPPDATA\Buddyverse\Buddyverse.exe"
- Something already on the gateway port?   try { (Invoke-WebRequest http://127.0.0.1:9120/api/status -UseBasicParsing -TimeoutSec 5).StatusCode } catch { 'nothing' }

Step 2. Download the installer (ask first).
    $setup = Join-Path $env:USERPROFILE 'Downloads\Buddyverse-Setup.exe'
    Invoke-WebRequest https://github.com/blueApple12/buddyverse/releases/download/v0.1.0-beta/Buddyverse-Setup.exe -OutFile $setup -UseBasicParsing
    (Get-Item $setup).Length; (Get-FileHash $setup -Algorithm SHA256).Hash
  Show me the size and SHA-256 (I can compare it with the release page). Don't run it yet.

Step 3. Install Buddyverse for my user only (ask first).
    $p = Start-Process -FilePath $setup -ArgumentList '/quiet' -Wait -PassThru; $p.ExitCode
    $bv = "$env:LOCALAPPDATA\Buddyverse\Buddyverse.exe"; Test-Path $bv
  Exit code 0 and Test-Path True = installed. If it isn't, run the installer without '/quiet' and let me click
  through it. If SmartScreen says "Windows protected your PC", tell me to click "More info", then "Run anyway"
  (the installer isn't code-signed).

Step 4. My login (ask me).
- If Step 1 found Hermes already installed, check whether it already has a Buddyverse login (names only, no values):
    Select-String -Path "$env:LOCALAPPDATA\hermes\.env" -Pattern '^HERMES_DASHBOARD_BASIC_AUTH_USERNAME=' -Quiet
  If True, tell me this PC already has a login and that I need ITS password (setup won't replace it).
- Otherwise ask me for a username (letters, digits and . _ @ - only). The username is not a secret.
- Do NOT ask for the password in chat. Create an empty file and open it in Notepad:
    $pw = Join-Path $env:LOCALAPPDATA 'buddyverse-setup-password.txt'
    New-Item -ItemType File -Path $pw -Force | Out-Null; notepad $pw
  Tell me: "Type your password on the first line (at least 8 characters, no space at the start or end), save, close
  Notepad, then tell me 'done'." The line break after it doesn't matter.
- After I say done, check only that the file isn't empty: (Get-Item $pw).Length -gt 0. Never read it.

Step 5. Run the headless setup (ask first).
- Explain that it will: install Hermes Agent with its official installer if it's missing (that installer also adds
  "$env:LOCALAPPDATA\hermes\bin" to my user PATH), download cloudflared (unless I don't want a phone now), create an
  empty notes vault in "$env:USERPROFILE\buddyverse\brain", save my login (only a scrypt hash of the password), add the
  Buddyverse web app to Hermes, start the Hermes gateway on http://127.0.0.1:9120 and check that my login works.
- Run (add '--skip-phone' to the list if I don't want to connect a phone now; leave out '--username', '<my username>'
  if Step 4 found an existing login). Buddyverse.exe is a windowed program, so start it with Start-Process and wait:
    $p = Start-Process -FilePath $bv -PassThru -WindowStyle Hidden -ArgumentList @('--setup', '--username', '<my username>', '--password-file', "`"$pw`"")
    $null = $p.Handle; $p.WaitForExit(); $code = $p.ExitCode; $code
    Get-Content "$env:USERPROFILE\buddyverse\setup.log" -Tail 25
- Exit codes: 0 = done. 1 = a setup step failed (show me the "FAILED at step" line; after fixing the cause, run the same
  command again: it resumes). 2 = a bad option, or the username/password breaks the rules (tell me which, then I fix the
  file). 3 = this PC already has a different login, or the password doesn't match it.
- Setup can take 5-15 minutes the first time (the Hermes install). Don't stop it early.
- When it exits 0 (or I give up), delete the password file: Remove-Item $pw

Step 6. Connect an AI model (I do this myself).
- If the setup output says "model: connected", skip this step.
- Ask me which provider I want (for example OpenRouter, Anthropic/Claude, OpenAI, Google Gemini, Nous Portal, or my own
  OpenAI-compatible server) and whether I use an API key or a sign-in.
- Easiest: tell me to open Buddyverse (Start menu), sign in once with my username and password, then go to
  More > Settings > Connect a model, pick the provider, paste the key there (or press Sign in), and press
  "Pick one of its models".
- Command-line way: open a NEW visible terminal window for me where I type everything myself:
    Start-Process powershell -ArgumentList '-NoExit', '-Command', "& '$env:LOCALAPPDATA\hermes\bin\hermes.exe' model"
- To confirm without showing secrets, check only that config.yaml names a provider and print just provider and model:
    Select-String -Path "$env:LOCALAPPDATA\hermes\config.yaml" -Pattern '^\s+(provider|default):' | ForEach-Object { $_.Line.Trim() }

Step 7. Start and verify.
- Start Buddyverse: Start-Process $bv   (it starts the gateway and the phone link by itself if they are down)
- Check the gateway: (Invoke-WebRequest http://127.0.0.1:9120/api/status -UseBasicParsing).StatusCode  -> expect 200.
- Ask me to confirm the Buddyverse window opened, that I signed in, and that my bot answers a short message.

Step 8. Pair my phone (guide me; don't do it for me).
- In Buddyverse on the PC, press the "Phone" button at the bottom of the side bar (or the tray icon's phone item).
  It shows a QR code for the Android app, a pairing QR code, and a "Web version" link.
- Android: scan the app QR (or open https://github.com/blueApple12/buddyverse/releases/download/v0.1.0-beta/Buddyverse.apk
  on the phone), allow "Install unknown apps" for the browser once, install, open it, scan the pairing code, sign in.
- iPhone (iOS 18.2+): open the web link in Safari, sign in, then Share > Add to Home Screen.
- If I used --skip-phone, opening Buddyverse once installs the phone link (it asks for my password again).
- Remind me: the phone link changes when the PC restarts; then I open "Phone" on the PC and scan again. The PC must be
  on, with Buddyverse open, for the phone to work. Nothing starts by itself at Windows sign-in.

When done, give me a short summary: what was installed and where, what is running, and anything still to do.
No secrets in the summary.
````

## Notes

- The agent works only on your PC. Your password goes into a file on your PC that setup reads and the agent deletes;
  your API keys go into the app or into a terminal window you type in yourself.
- Prefer doing it by hand? Follow the [README](../README.md).
