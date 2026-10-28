# Buddyverse

This folder is your own agentic OS. The UI is the Buddyverse app (PC app + Android app or the web version);
the engine is Hermes Agent, running on this PC with your own model account. Everything stays on this PC.

## Layout
- `brain\`: the vault (plain markdown, works with Obsidian). The source of truth about you.
  - `index.md`: one line per page. `log.md`: append-only history.
  - `me\profile.md`: ≤150 lines, the most important facts. `me\*.md`: topic pages (preferences, stack, goals, people…).
  - `inbox\`: candidate facts. `projects\<page>.md`: each project's living page (Now · Done · Gotchas · Next).
- `bin\`: `start-hermes.ps1` (starts the gateway and the phone link when the PC app opens), `cloudflared.exe`.

## How you work
- Never stop or restart the Hermes gateway or the tunnel yourself: the phone loses its link.
- Never make anything start itself on Windows (scheduled task, service, Startup folder, Run key).
- Questions about the user: answer from `brain\` and cite the page. When you learn a lasting fact, add it to the right
  `brain\me\*.md` page in the fact format: `- [type] fact (YYYY-MM-DD · [[projects/<page>]] · conf:high|med|low)`.
- One fact per line; merge duplicates; never delete a fact (move it under `## History` with `superseded YYYY-MM-DD`).
