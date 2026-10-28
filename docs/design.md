# Design spec: the agentic OS app

Status: draft 1, 2026-10-26. Sister doc: `hermes-api.md` (Hermes API map, written separately). Wire names below come from `contract/gateway-contract.openrpc.json`. If they disagree with `hermes-api.md`, `hermes-api.md` wins.

The app's name is not picked yet. This doc writes it as **`{App}`**. In code it is one string resource (`app_name`) and one constant. No Hermes, Nous, Codync or Grok name or logo appears anywhere in the UI.

---

## 0. The five rules (each answers a complaint)

| # | Rule | Answers |
|---|------|---------|
| 1 | **Characters never stand still.** Every visible character always breathes, looks around and blinks on an irregular rhythm. On top of that, each one reacts to touch and to every agent event. No still frames, not even when idle. | "blob is frozen, just blinks, it should feel alive" |
| 2 | **Nothing smaller than 48dp to touch.** Primary actions are 56dp. Send and Stop are 48dp. Rows are 72–76dp. Tab labels are at least 12sp. | "buttons too small" |
| 3 | **Feedback starts at touch-down, within one frame.** A squish, a haptic tick and an optimistic state change happen first; the network comes after. No ripples, no spinners under 300ms. | "not snappy" |
| 4 | **Everything that opens can be closed three ways:** back gesture, tapping the scrim, and a visible 48dp ×. A session has its own close in three places (§3.5). Screens are native; nothing is overlaid on someone else's web UI. | "can't close a session" |
| 5 | **Fun comes from the characters and the motion, not from points.** No XP, levels or streaks (the user rejected these before). Words are plain, minor actions are icons, and there are no help-text lines. | profile: dislikes |

---

## 1. Platform and structure

- **Kotlin Compose Multiplatform.** Android first (a 120Hz mid-range phone; Hebrew and English). The Windows desktop app (JVM) comes next from the same `commonMain` UI.
- **No Material widgets beyond `Text` and `BasicTextField`.** We use our own design system, as Codync does with its `design` module. Material 3 brings ripples, small default sizes and the stock look.
- **Navigation:** `org.jetbrains.androidx.navigation:navigation-compose`, which is multiplatform, with Android predictive back. Tabs keep their own back stacks.
- **No blur or Liquid Glass.** Codync's `kyant.backdrop` is Android-only and expensive. We use solid "candy" surfaces with soft shadows instead: cheaper, works on all targets, and reads as more playful.

```
app/
  shared/src/commonMain/kotlin/
    core/      HermesClient (hermes-api.md), models, Store (state + optimistic intents), cache
    design/    Theme.kt Palette.kt Type.kt Motion.kt Squish.kt Haptics.kt Sounds.kt Icons.kt
    buddy/     Buddy.kt BuddyShapes.kt BuddyFace.kt BuddyBrain.kt BuddyClock.kt Particles.kt BuddyBitmap.kt
    screens/   home/ chat/ chats/ routines/ more/ project/ studio/ sheets/ connect/
  androidApp/  MainActivity, notifications, SoundPool, haptics actual
  desktopApp/  Window, Tray, javax.sound actual
```

---

## 2. Navigation

### 2.1 Phone: floating tab bar, 4 tabs

The tab names are the user's own: **Bots · Chats · Routines · More**.

```
   ╭──────────────────────────────────────────╮
   │  ( Bots )    Chats    Routines    More   │   68dp tall, floats 8dp above nav bar,
   ╰──────────────────────────────────────────╯   12dp side margins, capsule, solid surface
```

- Each item is at least 72dp wide and 60dp tall: a 26dp icon over a 12sp medium label (Codync used 10sp, too small).
- **Active tab:** an accent pill slides under it on the `snappy` spring (§5.1). The icon switches to its filled version and hops 3dp (`bouncy`). Each switch gives a `CLOCK_TICK` haptic.
- **Tapping the active tab again** scrolls to the top. On Bots, a third tap also makes every character on screen hop in a wave, 40ms apart. This is pure fun.
- **Badge:** the Bots tab shows an amber count of bots that need you. The Chats tab shows an accent dot when something is unread.
- **In a chat** the bar slides down out of view (200ms, `smooth`), and the composer takes the bottom.
- **System bars** are edge-to-edge and transparent, with no system scrim; status icons follow the app theme. Under 3-button navigation the app paints a dark strip under the keys in both themes (ground in dark, near-black `text` in light) and asks for light keys, because some bars ignore the light-keys flag (Android 16's 3-button taskbar draws white keys regardless). Content never sits under the keys; on tabs it fades out 24dp above them.

### 2.2 Desktop (Windows) and wide windows

| Window width | Layout |
|---|---|
| < 600dp | phone layout |
| 600–1279dp | 80dp nav rail on the left with the same 4 items, list pane (380dp), detail pane |
| ≥ 1280dp | as above, plus a right panel (320dp) with Work, Plan and Files for the open chat |

- **Mouse:** hovering counts as a finger. Characters follow the cursor with their eyes (§4.6).
- **Right-click** opens the same menu as a long press.
- **Keyboard:**
  - `Ctrl+K` command palette, `/` palette in the composer
  - `Enter` send, `Shift+Enter` newline
  - `Esc` closes the top sheet or menu
  - `Ctrl+W` closes the current session, with an undo toast
  - `Ctrl+N` new session
  - `Alt+1..4` switch tabs
- **Tray icon** has an engine on/off item, as the PC app has today.

### 2.3 Screen map

```
Bots (home) ──┬─ Chat (bot) ──── Session sheet · Bot profile sheet · Work detail · Command palette
              ├─ Project (room chat) ── Project info sheet · @mention menu
              ├─ Needs-you card → inline approve / Chat
              └─ (+) New bot → Avatar studio
Chats ─────────── Chat (any session) · swipe close · long-press menu
Routines ──────── Routine editor sheet
More ──────────── Engine · Skills · Tools & MCP · Connectors · Plugins · Memory · Projects · Models
                  Processes · Remote screen · Usage · Checkpoints · Settings · Buddy gallery (debug)
Connect (no PC) ─ Pairing
```

---

## 3. Screens

Sizes are dp, text is sp. "Row" means the whole row is the touch target. Wireframes are for phone width (~392dp).

### 3.1 Bots (home): living stage plus roster

```
┌──────────────────────────────────────────┐
│ (o) PC on                   Bots    [ + ]│  header 56; status chip 48h; [+] 48 = new bot
├──────────────────────────────────────────┤
│                 study                    │
│   (o o)~~~~~~~~~~~~~~~~~~~~(o.o) second  │  STAGE 300 tall, card radius 28
│  studyapp  \     /          brain        │  - brain 72 in the middle
│        (^ ^)--(O O)--.  no project nodes │  - ring: top agents 52
│   tutor   study  brain '--(o o) mods     │  - sub-agents 40 beyond them, deeper 34
│         (^ ^)              (o o)!        │  - links: soft curves, pulses run along
│                                          │    them while a bot works
├──────────────────────────────────────────┤
│ NEEDS YOU                                │  section label 13 caps, only if any
│ ╭──────────────────────────────────────╮ │
│ │ (O O)!  mods-bot                     │ │  attention card, min 120
│ │         wants to run: gradle build   │ │
│ │              [  Deny  ] [  Allow  ]  │ │  buttons 48 tall, ≥ 96 wide
│ ╰──────────────────────────────────────╯ │
│ (o o)  brain                 *    9:41   │  row 76: buddy 52 + status badge
│        Learned: likes rounded fonts      │
│ (o.o)~ mods-bot             (*) working  │  live activity replaces the preview
│        Reading build.gradle ...          │
│ (oo)(oo) study . 3 bots          2       │  group/project row: stacked buddies,
│        tutor: plan for the Infi quiz |   │  live streamed text with caret
╞══════════════════════════════════════════╡
│   ( Bots )   Chats   Routines   More     │
└──────────────────────────────────────────┘
```

**The stage** (the Jarvis graph from the earlier ask) is ported from Codync `BrainGraph.kt` and made alive:

- **Layout (the user's rule):** a project is just an agent, so there are no project nodes. The brain is in the center; top agents (52) sit on a ring around it, linked to it; an agent's sub-agents (40, deeper ones 34) fan out beyond it, away from the brain (60dp out, 0.7 rad apart around the parent's outward angle), linked to it, recursively. The tree comes from folders, no extra metadata: a bot's home is its shallowest non-archived project folder, and its parent is the other bot whose home is the nearest strict ancestor folder (case-insensitive, `/` = `\`, ties by profile name); otherwise the brain, as for bots with no project (`agentParents`). Ring spots sit on an ellipse at 0.58 reach, starting upper-left at −3π/4; there is no cap, the stage grows to 660 tall so no names overlap. Positions are absolute, so Hebrew does not mirror the graph away from its lines.
- **Alive:** every node drifts ±3dp on its own seeded period of 5–8s.
- **Links** are quadratic curves, 1.5dp, `secondary @30%`. While an agent works, a 4dp dot in that agent's color runs from the brain down its links to the agent every 1.2s.
- **Status rings:** a working agent has a blue pulsing ring and a needs-you agent an amber one. This is Codync's `pulse()`: 1600ms, LinearOutSlowIn, the ring spreads 10dp while alpha goes 0.55 → 0.
- **Neighbors react:** when one character hops, neighbors within 120dp glance at it for 800ms (§4.6).
- **Tap a node:** it bounces (§4.5), then opens its chat after 120ms, so the bounce is visible first. Long press shows a peek card (status, last line, `Message`, `Open`).
- **Collapse:** scrolling the roster collapses the stage, on the `smooth` spring, into a 64dp **team strip**: one horizontal row of every character at 40dp, all still alive.

**Roster rows:**

- 76dp tall, with 20dp horizontal padding. When pressed, the whole row squishes to 0.97 and fills with `surface2` at radius 20.
- **Order:** brain first (star), then pinned, then needs-you, then most recent. Groups and projects sit in the same list (Hermes Bot Mode does the same).
- **Line 1:** name in `headline`, plus time.
- **Line 2,** by state:
  - needs you: amber text
  - working: activity text with a small thinking-dots glyph
  - streaming: the live last 60 characters with a blinking caret
  - error: red text
  - otherwise: the last message
- **Badge** at the avatar's bottom-end, with a 2.5dp ring cut out in the ground color (Codync and OpenMausBot pattern):
  - needs-you: amber 20dp circle with a white "!"
  - unread: accent 12dp dot
  - working: no dot, the character's motion already shows it
- **Swipe toward start** reveals Hide; **long press** opens Pin, Edit look, New session, Mark read, Hide, Delete.

**States:**

- **Empty:** one big sleepy buddy (160dp) and a 56dp `Make your first bot` button.
- **Engine off:** every character goes to `Sleeping` and a banner reads `PC is off · [Wake it]`.
- **Connecting:** characters `Offline`. When the gateway is ready they wake one by one (§4.5 Arrive), 70ms apart.

### 3.2 Chat with a bot

```
┌──────────────────────────────────────────┐
│ [<]  (o o) nova           [sessions v][..]│ header 64: back 48, buddy 44 (alive),
│       Main chat . on PC                  │ title taps = profile; session chip 48h; [..] 48
├──────────────────────────────────────────┤
│                     Today 9:40           │
│                  ╭─────────────────────╮ │
│                  │ fix the mod crash   │ │ user bubble: accent fill, white text,
│                  ╰─────────────────────╯ │ radius 22, max 80% width
│ ╭ Work . 1m 12s . Reading files     [v] ╮│ WORK CARD (foldable, min 56 header)
│ │ v Read build.gradle                   ││ step rows 48: icon, human label, status
│ │ v Search "NullPointer"                ││
│ │ * Run gradle build          (mini o)  ││ subagent = mini buddy of the same shape
│ ╰───────────────────────────────────────╯│
│ ╭ Plan  2/5 ███████░░░░░░░░             ╮│ PLAN CARD (todo.updated), 6dp bar
│ ╰───────────────────────────────────────╯│
│ (o o)  ╭──────────────────────────────╮  │ agent bubble surface2, radius 22;
│ ~bob~  │ Found it: the mixin targets  |│  │ the buddy (32) sits beside the
│        ╰──────────────────────────────╯  │ streaming bubble and talks (§4.4)
├──────────────────────────────────────────┤
│ ╭──────────────────────────────────────╮ │ composer: ONE capsule, min 56: + 48,
│ │ [+] Message nova...   [Auto v] (^)   │ │ field fills it, model chip 36h/48
│ ╰──────────────────────────────────────╯ │ touch (friendly name), send 48
└──────────────────────────────────────────┘
```

- **Header buddy** is 44dp and live. On open it plays `Greeting`: looks at you and hops 6dp. Tapping the name opens the Bot profile sheet.
- **Messages appear at once.** A sent message shows in the same frame (optimistic), with a `pop` sound and a `CONFIRM` haptic. While it delivers there is a small dot under it; failure turns it into retry and delete icons (48dp each).
- **The bot's reply grows out of its avatar:** it scales from 0.84 at the avatar corner, with translateY 8 → 0, 220ms, cubic(0.22, 1, 0.36, 1) (OpenMausBot `turn-answer-in`). Streaming text appends with no re-layout jank (`animateContentSize`, `smooth`).
- **Work card.** This is the user's "steps visible, foldable", shown like Grok Bot's "steps appear as the job unfolds":
  - **Header:** mini buddy, `Working · 1m 12s · <phrase>`. The phrase is the latest tool's human label, crossfading every 320ms, with a 2s shimmer.
  - **Folded by default** while it runs, showing the last 3 steps. Tapping it opens or closes it (chevron rotates 90°, `snappy`).
  - **Step rows** show a human label ("Read build.gradle", never the raw tool name; that is under `Details`), a status icon (spinner, check, ×) and a tap to open input and output in a mono block, max 240dp tall, scrollable.
  - **Subagents** (`subagent.start`) appear as a mini buddy of the parent's shape at 0.55 scale in a lighter tint. They pop out of the header buddy and work beside it. On `subagent.complete` the mini hops back into the parent.
- **Plan card:** Codync `PlanCard`. A counter, a 6dp progress bar on the `smooth` spring, and up to 5 steps starting just before the first not-done one, then "+N more".
- **Approval and question cards** go inline, in a full-width card with an amber 2dp leading edge:
  - **Approval:** title in plain words, a mono command block, and `Deny` (outline) / `Allow` (accent fill), each 48dp tall and at least 120dp wide. `Always allow` is in the `..` menu.
  - **Clarify:** option chips, each 48dp.
  - **Secret / sudo:** opens a secure input sheet.
- **Long-press a message** for Copy, Reply (quotes it in the composer), React, What it did (opens the trace), Select text.

**Composer** (a Codync `MessageComposer` port with bigger targets):

- `[+]` is 48dp: Files, Photo, Camera, Paste.
- One capsule, minimum 56dp, holds `[+]`, the field (it fills the width, growing to 6 lines; text direction `Content`, §9), the model chip and the trailing button; the buttons stay at the bottom as it grows.
- **The trailing button (48dp) morphs** between `Mic` (empty field), `Send` (text) and `Stop` (bot working, empty field). The morph is 200ms: the icon swaps with scale 0.6 → 1 and the fill crossfades.
- **Sending while the bot works** is the user's "mid-turn" habit:
  - A **tap** steers: the message reaches the running turn without stopping it (`session.steer`).
  - A **long press** on Send opens a 3-item menu: `Tell it now` (steer, default), `Queue for after`, `Stop and send`.
  - `Ask aside` (`prompt.btw`) is a 4th item, answered in a dismissible side bubble.
- **`/`** at the start of the field opens the Command palette (§3.9). **`@`** in a room opens the mention menu.
- The **model chip** sits inside the capsule, before the trailing button (36dp tall, 48dp touch area). It shows the model as people say it (`Opus 5.5 · 1M`, never a raw `claude-opus-5-5[1m]`) or `Auto`, and opens the model sheet.
- **Listening:** while you type, the header buddy and the stream buddy look at the composer, leaning in, and nod on keystrokes (§4.3).

### 3.3 Project (one conversation per project = a Hermes room)

```
┌──────────────────────────────────────────┐
│ [<] [dir] mods                      [i]  │ header 64; [i] 48 = project info sheet
├──────────────────────────────────────────┤
│  (o o)   (o.o)~   (- -)z    [ + ]        │ CREW STRIP 88: buddies 52, names 12,
│  nova    builder  tester    add          │ the one speaking is lifted 6dp
├──────────────────────────────────────────┤
│ (o o) nova                               │ cluster label: buddy 28 + name in its color
│ ╭────────────────────────────╮           │
│ │ builder, run the build     │           │
│ ╰────────────────────────────╯           │
│ (o.o) builder                            │
│ ╭ Work . 20s . Running gradle  [v] ╮     │
├──────────────────────────────────────────┤
│ (o.o)~ builder is working . 20s          │ PRESENCE LINE 40: mini buddy + text
│ [+] ╭ Message the team, @ to pick ╮ (^)  │
└──────────────────────────────────────────┘
```

- **Crew strip.** Tapping a buddy opens its profile sheet; long press puts `@name` into the composer.
- **Cluster labels** start each run of messages from one bot (OpenMausBot pattern).
- **Presence line:** `<name> is thinking / working / writing · timer`. When two or more bots work at once it shows a huddle of their mini buddies, bobbing out of phase.
- **@mention menu:** rows 56dp tall (buddy 36, name, role). Mentions render as LTR-isolated chips.
- **Project info sheet:**
  - folder path (mono, LTR)
  - subprojects (rows 56)
  - the living page from `brain/projects/<page>.md`: Now and Next, read-only, `Open in Obsidian`
  - sessions
  - Archive project / Delete project (with a confirm sheet)

### 3.4 Chats (all sessions)

```
┌──────────────────────────────────────────┐
│ Chats                          [search]  │ title 26 bold; search 48
│ ( All ) ( Live ) ( Archived ) ( Backgr… │ filter chips 40 tall, 48 touch
├──────────────────────────────────────────┤
│ TODAY                                    │
│ (o o) Fix mod crash                9:41  │ row 72: buddy 44, title, bot . project
│       nova . mods          (*) live      │
│ ◄───── swipe ───── [ (x) Close ]         │ swipe toward start reveals red Close;
│ (^ ^) Infi limits plan             8:02  │ full swipe (>50%) closes; haptic tick at 35%
│       tutor . study                      │
└──────────────────────────────────────────┘
```

- **Close** removes the row at once (optimistic `session.close`). An undo snackbar shows for 4s, and the bot's buddy plays `Wave` in it.
- **Long press** gives Rename, Pin, Archive, Delete (destructive; confirm sheet), Close.
- **Live** = `session.active_list`. **Archived** rows can be resumed (`session.resume`).
- **What shows where.** **All** = your chats: this PC's apps (desktop, CLI, TUI) and messaging apps (Telegram, …). **Background** = internal work, the sources `acp, cron, kanban, oneshot, subagent, tool` (one-shot calls, tool and helper runs, kanban workers, routine runs): never mixed into All, read-only there (open, Copy as text, Delete), no swipe-close. Its chip shows a count, and an empty All offers it. Live covers both.

### 3.5 Session sheet (fixes "can't close a session")

This sheet opens from the chat header's `[sessions v]` chip.

```
╭──────────────────────────────────────────╮
│                 ───                      │ grabber; sheet radius 28; scrim tap closes
│ nova's sessions                     [x]  │ [x] 48
│ ╭──────────────────────────────────────╮ │
│ │ (*) Main chat            now     [x] │ │ row 64; [x] 48 = Close this session
│ │     Fix mod crash        9:41    [x] │ │
│ │     Shader research      Tue     [x] │ │
│ ╰──────────────────────────────────────╯ │
│ [        + New session (worktree)      ] │ 56, full width
╰──────────────────────────────────────────╯
```

A session can be closed in three places: this sheet's ×, a swipe in Chats, and `..` → `Close session` in the chat header. All three call `session.close`; the row stays resumable. Delete is separate and asks first. Closing the session you are in returns you to the bot's main chat with an undo toast.

### 3.6 Routines (Hermes cron)

```
┌──────────────────────────────────────────┐
│ Routines                                 │
│ ╭──────────────────────────────────────╮ │
│ │ (o o) Morning brief         [ ●  ]   │ │ card 88: buddy 44, name, switch 48
│ │       Every day 07:30 . next in 9h   │ │ schedule in words
│ │                             [ > ]    │ │ run now 48
│ ╰──────────────────────────────────────╯ │
│                  [ + New routine ]       │ 56 capsule, bottom-end, above tab bar
╞══════════════════════════════════════════╡
```

- **Editor sheet** has three parts:
  - **What:** a text field.
  - **When:** preset chips (Every morning, Every evening, Weekdays, Every hour, Once…) plus a 56dp time wheel, with a cron field under `Advanced`.
  - **Who:** buddy picker, 64dp tiles.
- **Run now:** the owner buddy plays `Working` in the card until the run finishes, then `Done`.
- **Switch:** our own squishy toggle. The knob stretches 1.3× wide during travel (`snappy`) and gives a `CLOCK_TICK`.

### 3.7 More: every other Hermes feature

```
┌──────────────────────────────────────────┐
│ More                                     │
│ ╭──────────────────────────────────────╮ │
│ │ Engine   (o) running . 3h 12m [ ON ] │ │ engine card 96; toggle 56x32 knob, 48+ touch
│ │ Phone link: active          [copy]   │ │
│ ╰──────────────────────────────────────╯ │
│ ╭────────────╮ ╭────────────╮            │ tiles 2 columns, 104 tall, radius 24
│ │ Skills     │ │ Tools/MCP  │            │ 28 icon + label 15 semibold + count 13
│ ╰────────────╯ ╰────────────╯            │
│ ╭────────────╮ ╭────────────╮            │
│ │ Connectors │ │ Plugins    │  ...       │
└──────────────────────────────────────────┘
```

- **Tiles:** Skills, Tools & MCP, Connectors, Plugins, Memory (vault + learned facts), Projects, Models, Processes, Remote screen, Usage, Checkpoints (rollback), Settings.
- **Every Hermes method family** gets a home here or elsewhere in the app. `hermes-api.md` owns the full list, and a test in the build fails if a family has no screen.
- **Lists** inside these screens use 64dp rows with a 48dp trailing icon action. No row has more than one visible button; the rest go in the long-press menu (the user dislikes button rows).
- **Settings:**
  - Theme: System / Light / Dark
  - Language: System / English / עברית
  - Sounds on/off
  - Haptics on/off
  - Motion: Full / Calm / Off. Calm means no fidgets or particles; Off is the same as the system "Remove animations".
  - Notifications
  - Paired PC

### 3.8 Bot profile and avatar studio

```
╭──────────────────────────────────────────╮
│ [x]                              [Done]  │ 48 each
│               ( O   O )                  │ live buddy 160, plays every state on tap
│                 nova                     │ display 34
│ ( Idle )( Think )( Work )( Talk )( Need )│ state preview chips 40/48 (horizontal scroll)
│ SHAPE   [blob][pebble][squircle][tablet] │ 64 tiles, each a live mini buddy
│         [wedge][hex][cloud][teardrop]    │
│ COLOR   (o)(o)(o)(o)(o)(o)(o)(o)(o)(o).. │ 48 swatches
│ Name  [ nova                         ]   │ 56 field
│ Role  [ Builder for the mods project ]   │ 56 field
│ [dice] Surprise me                       │ 48: random shape+color, buddy spins 360
╰──────────────────────────────────────────╯
```

- Changing the shape **morphs** the outline over 280ms. Both paths are resampled to 64 points and blended with `bouncy`. The eyes stay put, so it reads as the same character (OpenMausBot's "one face, many bodies").
- **Profile sheet** (read view) has a buddy at 120dp, name, role, model, skills and tools summary, and `Message` (56dp). `Routines`, `Edit look` and `Delete` go in the menu.

### 3.9 Command palette (`/` and `Ctrl+K`)

- A bottom sheet at 70% height with a search field on top (56dp).
- **Sections:** Recent, Skills, Commands, Tools, from `commands.catalog`, `complete.slash` and the skills list. Rows are 56dp: icon, `/name`, and a one-line description in 13sp.
- Typing filters with fuzzy matching. `Enter` or a tap runs the command, or puts it in the composer if it needs arguments.
- Commands are LTR tokens even in Hebrew UI.

### 3.10 Connect / start and pairing

```
┌──────────────────────────────────────────┐
│                                          │
│      (- -)z  (- -)   (- -)z  (- -)       │ the team asleep on a line, breathing
│      ────────────────────────────        │
│              PC is off                   │ title 26
│     [        Wake up my PC          ]    │ 64 tall, accent
│     [  Pair with a link or QR code  ]    │ 56 outline
└──────────────────────────────────────────┘
```

- When the PC answers, the buddies wake one by one (`Arrive`, 70ms stagger) and the screen fades to Bots.
- **Pairing:** a full-screen QR viewfinder, or paste a link into a 56dp field. Done gives a `CONFIRM` haptic and a `bloop`.

### 3.11 Notifications (Android)

- **Only three kinds** (the Duet "quiet unless needed" policy):
  - **needs you:** with `Deny` / `Allow` actions for approvals
  - **done:** only if the app is in the background and the turn ran longer than 30s
  - **failed**
- **Grouped per bot.** The large icon is the bot's buddy rendered to a bitmap by the same draw code (`BuddyBitmap.kt`, as in Codync `characterAvatarBitmap`). The pose matches the state: wide eyes, happy or sad.

---

## 4. The character system ("buddies")

### 4.1 What Codync draws today (reuse and lessons)

These files are the reference: Android `apps/android/design/.../CharacterAvatar.kt` and iOS `apps/ios/Kit/Sources/CodyncKit/Design/CharacterAvatar.swift`, which hold the same algorithm.

- **Dot matrix:**
  - The grid is 7 cells under 18dp, 9 under 28dp, otherwise 13.
  - A dot is drawn wherever the shape's path contains a cell center. The shape is defined in a 1000×1000 box, and the mask is computed once per shape and grid.
  - Dot radius is `step·0.42·(0.55+0.45·shade)`.
- **Lighting:** a sphere normal with `z = √max(.2, 1−u²−v²)`. The light is `l = (sin(yaw)·.8, .55, cos(yaw)·.5+.6)` and `shade = .3 + .7·max(0, n̂·l̂)`.
  - Dot ink alpha is `.4+.4·shade` under 13 cells, else `.2+.4·min(1, shade/.7)`.
  - Where shade > 0.6, the bot color is drawn over the dot at alpha `(shade−.6)/.4`. That gives a lit, colored highlight on a grey dot body.
- **Eyes** are *missing* dots: columns {4, 8} and rows {4, 5, 6} on the 13 grid ({3, 6} and {3, 4} on 9; {2, 4} and {2, 3} on 7). A blink keeps only the bottom eye row missing.
- **Shapes,** in the 1000 box:

  | Shape | Geometry |
  |---|---|
  | `blob` | 64-point loop, r = 0.46 + 0.035·sin(3a + 0.6) |
  | `pebble` | oval (0,100)–(1000,900) |
  | `squircle` | rounded rect (40,40)–(960,960), r 300 |
  | `tablet` | rounded rect (140,0)–(860,1000), r 220 |
  | `wedge` | rounded triangle M500,40 Q900,400 980,860 Q500,1040 20,860 Q100,400 500,40 |
  | `hex` | pointy-top, R 490, unioned with an 80-wide round-join stroke |
  | `cloud` | ovals (0,300,550,850), (450,300,1000,850), (180,80,820,720), plus a rounded rect (100,500,900,850) r 150 |
  | `teardrop` | M500,0 C620,200 940,380 940,620, arc over (60,180,940,1060) 0→180°, C60,380 380,200 500,0 |

- **Colors:** black #2B2B2B, brown #936439, red #FF263C, orange #FF6700, yellow #FF9800, green #00C972, cyan #00BCA6, blue #1084FE, violet #9159FE, magenta #FF309B, gray #777777.
- **Motion** runs only while `working` or `needsInput`:
  - **working:** the light orbits (yaw = 1.4·t) and the eyes glance ±1 column (`round(sin(2πt/3.2)·1.4)`).
  - **needsInput:** a radial ripple, shade × (.6 + .4·(.5 + .5·sin(9r − 5t))).
  - **blink:** a fixed rhythm, `(t/4.7) mod 1 < .035`, about 165ms every 4.7s.
- **Badges:** needs-you is an amber #F0A030 dot (36% of the avatar) with a white "!". Unread is an accent dot (28%). Both have a 2dp ring in the ground color.
- **Groups:** 2 members as diagonal pairs at 66%, 3 as a triangle at 55%, 4 as quadrants at 50%, more as "+N".

**Lessons:**

1. **Idle is a still image.** That is the "frozen" complaint, word for word.
2. **The blink is metronomic,** so it reads as mechanical.
3. **No body motion:** no squash, bob, or reaction to touch.
4. **It recomposes every frame.** `val seconds by transition.animateFloat(...)` is read in the composable body, so the whole avatar recomposes each frame. It should be read only in the draw phase.
5. **The dot face can't show emotion,** because the eyes are just holes.

**What we keep:** the 8 shapes (exact paths above), the 11 colors, the light direction, the badge cut-out rings, the group layouts, the bitmap render for notifications, and the graph layout.

**What we change:** the body is a smooth vector with real eyes, a mouth and lids, and it always moves. The dot look survives later as an optional `Dots` skin, reusing Codync's mask and shading code unchanged. It is not in v1.

### 4.2 Anatomy

```
          .-""""-.         body: shape path (1000 box) scaled to size W
        .'  o  o  '.       eyes: 2 rounded rects, white (ink on light bodies)
       /    |  |    \      gloss: white ellipse top-start, alpha .30
      |   (gloss)    |     mouth: hidden unless talking or reacting
      |     \__/     |     cheeks: blush ovals on happy/shy/pet, #FF7AA8 @ 35%
       '.          .'
         '-.____.-'
      ~~~~~~shadow~~~~~~    ground shadow: black @ 12%, 0.7W x 0.08W, shrinks while airborne
```

| Part | Spec (W = buddy size) |
|---|---|
| Body fill | Linear gradient from top-start to bottom-end: `mix(c, white, .35)` → `c` → `mix(c, black, .25)`. This is one hex giving a candy look, the OpenMausBot trick softened. |
| Gloss | Ellipse at (0.30W, 0.22W), 0.22W × 0.12W, rotated −20°, white @ 0.30. Drawn at ≥ 32dp only. |
| Rim (dark mode) | A 1.5dp stroke of `white @ 8%` on the `black` and `brown` bodies, so they don't vanish on the dark ground. |
| Face anchor | Per shape `(fx, fy)` in body units: blob (.50,.46), pebble (.50,.50), squircle (.50,.46), tablet (.50,.40), wedge (.50,.62), hex (.50,.48), cloud (.50,.58), teardrop (.50,.62) |
| Eyes | Centers at `fx ± 0.15W`, `fy`. Size 0.11W wide × 0.20W tall, corner radius 0.055W (a capsule). These are Grok-style tall ovals with no pupils; the whole eye moves. |
| Eye glint | 0.03W white dot at the eye's top-start, at ≥ 48dp. It doesn't move with the eye, which fakes wetness. |
| Eye color | `#FFFFFF`. The `cream` and `yellow` bodies use `#1C1A1F` instead. |
| Mouth | A path at `(fx, fy + 0.17W)`, 0.16W wide. Parameters: `open` 0..1 (gives an ellipse up to 0.10W tall), `smile` −1..1 (curve of a 2.5%-W stroke when `open` = 0). |
| Gaze travel | Eye offset up to ±0.07W in x and ±0.05W in y. Large buddies also lean the body 2° and shift the face 0.02W toward the target. |
| Lids | Expressed as a clip on each eye: `open` (scaleY, 0.08 to 1), `lidTop` (0 to 0.7 cut from top), `lidBottom` (0 to 0.6, which gives happy ^ ^ arcs), `lidTilt` (±25°, + is angry, − is sad). |

**Colors (12).** These are Codync's 11 plus cream:

`black #2B2B2B · brown #936439 · red #FF263C · orange #FF6700 · yellow #FF9800 · green #00C972 · cyan #00BCA6 · blue #1084FE · violet #9159FE · magenta #FF309B · gray #777777 · cream #F1EFE9`

**Expressions** are 12 named lid, eye and mouth poses. Each state picks from a pool of them, the way OpenMausBot pools its faces:

| Expression | open | lidTop | lidBottom | tilt | eye scale | mouth | cheeks |
|---|---|---|---|---|---|---|---|
| neutral | 1 | 0 | 0 | 0 | 1 | hidden | – |
| happy | 1 | 0 | .55 | 0 | 1 | smile .6 | yes |
| excited | 1 | 0 | .35 | 0 | 1.12 | open .5 | yes |
| curious | 1 | .15 | 0 | −6 (one eye 1.1) | 1.05 | hidden | – |
| focused | .85 | .35 | 0 | +4 | .95 | hidden | – |
| surprised | 1 | 0 | 0 | 0 | 1.2 (round: h = 1.2w) | open .35 round | – |
| sleepy | .25 | .6 | 0 | −4 | 1 | hidden | – |
| sad | 1 | .2 | 0 | −18 | .9 | smile −.5 | – |
| worried | 1 | .1 | 0 | −10 | 1.05 | smile −.2 | – |
| angry | 1 | .3 | 0 | +22 | .95 | smile −.3 | – |
| proud | .6 | .4 | .2 | 0 | 1 | smile .8 | yes |
| wink | L 1 / R .08 | 0 | .5 | 0 | 1 | smile .5 | yes |

Expressions are blended, never snapped. Every parameter is a spring (k 260, ζ 0.85, about 200ms) toward the target pose, so face changes flow.

### 4.3 Moods: the state machine

A buddy has one **mood**, which loops. On top of it plays at most one **one-shot**. A **gaze** target and a **lid** layer are always running.

```
                          gateway.ready / connect
        ┌──────────┐  ─────────────────────────────►  ┌────────┐
        │ Offline  │                                  │  Idle  │◄──────────────────┐
        └──────────┘  ◄─── connection lost (any) ──── └───┬────┘                   │
             ▲                                   typing   │   prompt sent /        │
             │                        ┌──────────────┐◄───┤   message.start        │
        idle > 30 min                 │  Listening   │    ▼                        │
        or engine off   ┌──────────┐  └──────────────┘  ┌──────────┐ tool.start   │
             └──────────│ Sleeping │                    │ Thinking │────────┐     │
                        └──────────┘                    └────┬─────┘        ▼     │
                                         message.delta      │        ┌─────────┐  │
                                    ┌──────────────────────►│◄───────│ Working │  │
                                    │                       ▼        └─────────┘  │
                               ┌─────────┐  message.complete  ┌──────┐            │
                               │ Talking │ ─────────────────► │ Done │ (one-shot) ┘
                               └─────────┘                    └──────┘
   approval / clarify / secret / sudo request ──► NeedsYou   (until answered → previous)
   error / tool failure / turn failed          ──► Error      (until next prompt or tap)
```

**Priority,** when several apply: NeedsYou > Error > Talking > Working > Thinking > Listening > Idle > Sleeping > Offline.

**A minimum dwell of 400ms per mood** stops flicker when tool calls are short.

**Hermes event → mood.** Names are from the contract; `hermes-api.md` confirms them.

| Event | Mood / effect |
|---|---|
| `gateway.ready`, link up | all Offline → Idle via `Arrive`, staggered 70ms |
| composer focused + text changing (this bot's chat) | Listening (falls back to Idle after 3s with no keystroke) |
| `prompt.submit` sent, `message.start`, `thinking.delta`, `reasoning.delta` | Thinking |
| `tool.start`, `tool.progress`, `tool.generating`, `browser.progress` | Working. `tool.start` also pops a tool glyph above the head for 1.2s |
| `subagent.start` / `subagent.complete` | `Spawn` / `Return` one-shots (mini buddy) |
| `message.delta` | Talking (each chunk kicks the mouth, §4.4) |
| `message.complete` | `Done` one-shot → Idle |
| server request `approval`, `clarify`, `secret`, `sudo`, `vault.unlock_prompt` | NeedsYou |
| `error`, `tool.complete` with error, turn failed | Error |
| `todo.updated` | plan card. The buddy gives a tiny nod per completed step |
| learned fact (brain; event per `hermes-api.md`) | `Learned` one-shot + `bloop` |
| `session.close` done | `Wave` one-shot |
| engine off / idle > 30 min | Sleeping |

**Per-mood loop specs.** A = amplitude; T = period. Bob amplitude is in W units. Each bot gets a random phase and a ±10% period jitter from its id seed, so a team never moves in sync.

| Mood | Body | Eyes / gaze | Lids, blink interval | Mouth | Extras | FPS tier |
|---|---|---|---|---|---|---|
| **Idle** | Breathe: sy = 1 + A·sin(2πt/T), sx = 1 − A/2·sin(…), A .018, T 3.6s, pivot at ground | Saccades every 0.8–3.0s; 40% of them go back to center (looking at you) | neutral; blink 2.5–6s | hidden | Fidgets every 8–20s (§4.5) | S: 30 / M+: full |
| **Listening** | Lean 3° toward the composer; breathe .012 / 3.0s; a 1.5% nod per keystroke (k 600, ζ .5) | Locked on the composer; eye scale 1.08 | curious; blink 3–7s | hidden | – | full |
| **Thinking** | Sway ±3°, T 2.4s, ease in-out sine; breathe .010 / 3.0s | Up and sideways (.5, −.7), drifting ±.1 | focused; blink 3.5–7s | hidden | 3 dots orbit above the head (r .35W, T 1.2s, dot .06W, each pulsing in turn); this is the Grok "three dots" | full |
| **Working** | Bob: y = −A·\|sin(πt/T)\|, A .04, T 0.9s; squash .12 at each landing (sx 1.06, sy .94) | Reading scan: x ±.6 travel, T 1.6s, y +.2 (looking down at the work) | focused; blink 2.8–5.5s | hidden | Tool glyph bubble on `tool.start`; 4-spark pop on each `tool.complete`; after 20s an orbit ring (2 dots, r .62W, T 2.2s), Grok's "long work" | full |
| **Talking** | Breathe .012; a bob impulse per chunk (§4.4) | Toward the message bubble: x +.5 in the reading direction, y +.3 | neutral or happy; blink 2–5s | Spring-driven open/close (§4.4) | – | full |
| **NeedsYou** | Hop every 1.8s (§4.5 hop); after 10s unanswered, every 4s | Straight at you (0, 0), ±.03 jitter; eye scale 1.15 | surprised → worried after 10s; blink 4–8s | open .2 | "!" badge pops in (`bouncy`, overshoot to 1.2); amber ring pulse 1600ms; one haptic on arrival | full |
| **Error** | Shake x ±.05W at 85ms for 420ms, decaying; then droop (sy .96, sx 1.02) | Down (0, .5) | sad; blink 4–8s, slow (close 120ms) | smile −.5 | Body desaturated 35%; one sweat-drop particle | 30 |
| **Sleeping** | Breathe .028 / 4.6s, tilt 4° | – | sleepy (open .08, curved); no blink | hidden | "z" glyphs every 2.4s, rising .6W over 2s and fading | 15 |
| **Offline** | As Sleeping, desaturated 60% | – | closed | hidden | none | 15 |

### 4.4 Typing along while streaming

The buddy beside the streaming bubble, and in the roster row and chat header, "says" the text:

- Chars from each `message.delta` go into a buffer. Every **70ms**, if the buffer has any chars: `energy = min(1, chars / 12)`. The mouth target is set to `0.25 + 0.6·energy` and the buffer clears.
- **Mouth spring:** k 900, ζ 0.45. The target decays back to 0 at 12/s, so the mouth flaps with natural overshoot.
- **Body:** each kick adds a −1.5dp·energy y-impulse (k 500, ζ 0.5), a tiny nod.
- **Vowel flavor (cheap):** if the chunk is mostly a/o (or Hebrew ו/ע/א), the mouth goes rounder (width ×0.8, height ×1.2). Otherwise it is wider. One `count` over the chunk; no audio analysis.
- **Pauses:** a gap of 400ms or more closes the mouth and runs a Thinking-style glance up. A gap of 1.5s or more returns to Thinking.
- **In rows,** the preview line shows the streamed tail with a 2px caret blinking at 530ms (steps), so the list itself looks alive.

### 4.5 Body physics, one-shots and fidgets

**Squash and stretch.** These are always volume-preserving (sx·sy ≈ 1) and pivot on the ground line, at bottom center.

**The hop** (used by tap, NeedsYou, Done):

1. **Anticipate** 90ms: sx 1.10, sy 0.90, ease-in-quad.
2. **Launch:** sx 0.92, sy 1.10, while y goes to −h over 220ms, ease-out-cubic.
3. **Fall** 180ms, ease-in-quad.
4. **Land:** sx 1.12, sy 0.88, then a spring to 1 (k 500, ζ 0.4, about 300ms, with 1–2 visible wobbles).

The shadow scales by `1 − 0.5·(y/h)` and fades by 30% at the top of the hop.

| One-shot | Trigger | Motion | Face | Sound / haptic |
|---|---|---|---|---|
| **Tap** | Tap on any buddy | Press: squash (sx 1.12, sy .88) at touch-down (`snappy`); release: hop h .15W | happy 600ms | `VIRTUAL_KEY` |
| **Giggle** | 3 taps within 900ms | Spin 360° over 600ms, ease-out-back (c 1.7); 3 hearts | wink | `CONFIRM` |
| **Dizzy** | 5+ taps within 1.5s | Wobble ±8°, T 300ms, ×3 | spiral eyes 1.5s (2 arcs rotating) | – |
| **Pet** | Long press, held (max 3s) | Wiggle ±3° at 6Hz; a heart every 300ms | happy + cheeks | `CLOCK_TICK` every 120ms (a purr) |
| **Arrive** | New bot, wake-up | Scale 0 → 1, 520ms, ease-out-back (c 1.7), plus spin 360° over 620ms; eyes open after 200ms | excited → neutral | – |
| **Leave** | Bot deleted | Eyes close, then scale → .05 over 1400ms ease-in-out, then 6-particle poof | sleepy | – |
| **Done** | `message.complete` after a turn of ≥ 3s | Hop h .18W; confetti 10 in bot color + 2 accent | happy 1.2s | `bloop`, `CONFIRM` |
| **Nod** | Done after a turn < 3s; plan step done | Rotate 6° down and back, 300ms | happy 600ms | – |
| **Greeting** | Chat opened | Look at you, hop h .08W | happy | – |
| **Wave** | Session closed | Tilt −8° → +8° ×2, 500ms | happy | – |
| **Spawn / Return** | Subagent start / complete | Parent squashes; a mini (.55 scale, lighter tint) pops out with `bouncy` and sits beside the parent / hops back along a 400ms arc, and the parent nods | – | – |
| **Startle** | Message arrives while idle | Hop h .05W | surprised 200ms | – |
| **Learned** | Brain learned a fact | Eyes up; a spark glyph above for 900ms | proud | `bloop` |

**Idle fidgets.** One plays every 8–20s, only when the buddy is visible, in Idle, and Motion is set to Full. Weighted pick:

| Fidget | Weight | What it does |
|---|---|---|
| look around | 3 | Glance start 400ms, then end 400ms, then back |
| mini hop | 2 | Hop h .05W |
| wiggle | 2 | ±4° ×3 over 600ms |
| stretch | 1 | sy 1.08 / sx .94 for 500ms, then settle |
| yawn | 1 | Only after idle > 2 min: eyes .3, mouth open .6 for 900ms |
| look at neighbor | 2 | Stage and crew strip only |

**Magic trick** (an easter egg for the magician): hold the brain buddy for 2s and it vanishes in a 12-particle poof, then reappears 600ms later from the other side of the stage with `Arrive`.

### 4.6 Gaze: looking at your finger, the composer, each other

Gaze target priority:

1. **Finger or mouse.** The last pointer position, if within the last 1.5s. A root `Modifier.pointerInput` on `PointerEventPass.Initial` observes the pointer without consuming it and writes window coordinates into one global `LookTarget`.
2. **Composer** while Listening.
3. **Event focus:** the streaming bubble's end, or a neighbor that just hopped.
4. **The mood's default** (table in §4.3).
5. **Saccades.**

**Mapping:**

- `dir = target − buddyCenter`. The buddy center is kept in a plain var via `onPlaced`, so no state is involved.
- `gaze = dir / max(|dir|, 160dp)`, clamped to the unit circle, then scaled by the travel (§4.2).
- In RTL, "reading direction" defaults flip x. Finger tracking does not flip, because it is physical.

**Eye movement:**

- **Saccade:** spring k 900, ζ 0.9 (about 60ms). 30% of saccades longer than half the travel include a blink, which is realistic.
- **Smooth pursuit** (finger): k 140, ζ 1.
- **Micro-saccades:** ±2% travel every 300–600ms, at ≥ 96dp only.

### 4.7 Blink

- **Interval:** uniform(2.5s, 6.0s) × the mood's factor (Listening .8, Thinking 1.4, Working 1.2, Sleepy partial). Never a fixed rhythm.
- **Double blink:** 15% chance, with the second one 180ms later.
- **Curve:** close 70ms (ease-in), hold 40ms at open .08, open 120ms (ease-out), about 230ms in total. While closed the eye is 1.1× wider, which looks squishy.
- **Sleepy** buddies do partial blinks down to .3.

### 4.8 Particles

- **Pool:** one `FloatArray` of struct-of-arrays per buddy holding x, y, vx, vy, life, size, rotation, type and colorIndex. Max 24 per buddy, with a global cap of 120; extra emits are dropped.
- **No allocation in the frame loop.**

| Type | Look | Physics |
|---|---|---|
| confetti | 3×5dp rect, rotating | v₀ 180–320dp/s at a −60..−120° spread, gravity 900dp/s², drag .98/frame, life 1.1s |
| spark | 2dp dot | radial 120dp/s, life .4s |
| heart | small path, 8dp | rises 40dp/s, sways ±6dp, life .9s |
| z | glyph path | rises .6W over 2s, fades |
| poof | 6–12 circles, 4–8dp | radial, shrinking, life .5s |
| sweat | 5dp drop path | slides down 20dp, life .8s |

Particles are drawn only for buddies ≥ 40dp, when Motion is Full and Battery Saver is off.

### 4.9 Rendering cheaply (60fps or more with 20+ buddies)

1. **One clock for the whole app.** `BuddyClock` holds two `mutableLongStateOf` values, `fast` (every frame) and `slow` (updated about every 33ms). They are driven by one `withFrameNanos` loop inside `repeatOnLifecycle(RESUMED)`, so everything stops when the app is in the background or the screen is off.
2. **Read the clock only in the draw phase.** `drawWithCache { onDrawBehind { val t = clock.fast } }`. A state read in draw invalidates **draw only**: no recomposition and no layout. This is the fix for Codync's per-frame recomposition.
3. **Pure pose function.** `BuddyBrain.step(nanos)` advances its springs with semi-implicit Euler. It sub-steps at 1/240s when dt > 1/120 and caps dt at 50ms after a pause. It writes into one reused `Pose` object and allocates nothing. Randomness comes from a seeded xorshift per bot id, so each bot is deterministic and distinct.
4. **Cache what doesn't change.** The body `Path`, gradient `Brush` and shadow are built in `drawWithCache` and rebuilt only on size, shape or color change. Eyes and mouth are drawn as `drawRoundRect` or `drawOval` plus `clipRect` for the lids (rotated with `rotate()`), with no per-frame Path ops. The mouth path is reused with `rewind()`.
5. **Body transform in one `withTransform`** (translate, scale around the ground pivot, rotate). On Android, a press scale on whole rows goes in `graphicsLayer` (RenderThread).
6. **FPS tiers by size:**

   | Tier | Sizes | Clock | Content |
   |---|---|---|---|
   | XS | < 32dp | `slow` | breathe + blink only, no mouth, no particles |
   | S | 32–47dp | `slow` | everything except particles and micro-saccades |
   | M | 48–95dp | `fast` | full |
   | L | ≥ 96dp | `fast` | full, plus micro-saccades and body lean |

   Any buddy in Talking or Working is promoted to `fast`.
7. **Only visible buddies tick.** `LazyColumn` disposes offscreen rows by itself. The stage pauses when it is collapsed or scrolled away, because its strip is a separate composable.
8. **Battery Saver** (`PowerManager.isPowerSaveMode`): every buddy runs on `slow` with no particles.
9. **Motion Off** (or system animator scale 0): no body motion and no particles. The **state is still clear:** badges and status rings stay, faces show the mood expression as a still pose, and blinks continue.
10. **Budget:** under 0.15ms of draw per buddy and under 3ms for 20 buddies. Check it in the Buddy gallery (debug, under More): 8 shapes × 9 moods, all running at once. Measure with `JankStats` and Macrobenchmark `FrameTimingMetric` on a 120Hz mid-range phone; the target is p95 frame time under 8ms.
11. **Simple loops** (status ring pulse, caret, shimmer) use `rememberInfiniteTransition`, read in draw. That is fine because they are single floats.

Sketch of the core (commonMain):

```kotlin
@Stable class BuddyClock { val fast = mutableLongStateOf(0L); val slow = mutableLongStateOf(0L) }
val LocalBuddyClock = staticCompositionLocalOf { BuddyClock() }

@Composable fun ProvideBuddyClock(content: @Composable () -> Unit) {
    val clock = remember { BuddyClock() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) withFrameNanos { now ->
                clock.fast.longValue = now
                if (now - clock.slow.longValue >= 33_000_000L) clock.slow.longValue = now
            }
        }
    }
    CompositionLocalProvider(LocalBuddyClock provides clock, content = content)
}

/** Critically-damped-ish spring stepped by hand: no coroutines, no allocation. */
class Spring(var k: Float, var zeta: Float) {
    var x = 0f; var v = 0f; var target = 0f
    fun step(dt: Float) { val w = sqrt(k); v += (-2f * zeta * w * v - k * (x - target)) * dt; x += v * dt }
}

@Composable fun Buddy(spec: BuddySpec, mood: Mood, size: Dp, modifier: Modifier = Modifier) {
    val clock = LocalBuddyClock.current
    val brain = remember(spec.id) { BuddyBrain(seed = spec.id.hashCode()) }
    SideEffect { brain.mood = mood }                     // plain field, picked up next frame
    val tick = if (size >= 48.dp || mood.busy) clock.fast else clock.slow
    Spacer(modifier.size(size)
        .onPlaced { brain.center = it.positionInWindow() + it.size.center.toOffset() }
        .pointerInput(spec.id) { detectTapGestures(onPress = { brain.press(); if (tryAwaitRelease()) brain.release() },
            onLongPress = { brain.pet() }) }
        .semantics { contentDescription = "${spec.name}, ${mood.spoken}" }
        .drawWithCache {
            val body = BuddyShapes.path(spec.shape, this.size)      // cached per size/shape
            val fill = BuddyShapes.brush(spec.color, this.size)
            onDrawBehind {
                val p = brain.step(tick.longValue)                  // draw-phase read: redraw only
                drawShadow(p); withTransform({ translate(0f, -p.lift); scale(p.sx, p.sy, p.ground); rotate(p.tilt, p.ground) }) {
                    drawPath(body, fill); drawGloss(p); drawFace(p, spec)
                }
                drawParticles(brain.particles)
            }
        })
}
```

---

## 5. Motion and haptics

### 5.1 Springs and curves (`design/Motion.kt`)

| Token | Spec | Use |
|---|---|---|
| `snappy` | spring ζ 0.62, k 700 | press release, chips, toggles, tab pill |
| `bouncy` | spring ζ 0.45, k 500 | badges, "!", buddy pops, the new-message counter |
| `smooth` | spring ζ 1.0, k 440 (Codync `layout`) | layout, list insert and remove, content size |
| `sheet` | spring ζ 0.86, k 380 | sheets, screen push and pop (about 280ms, interruptible) |
| `gentle` | spring ζ 1.0, k 160 | stage collapse, large moves |
| `fade` | tween 120ms, cubic(.25, .1, .25, 1) | crossfades |
| `morph` | tween 200ms, cubic(.22, 1, .36, 1) | icon swaps, send ↔ stop |
| `reply-in` | tween 220ms, cubic(.22, 1, .36, 1), from scale .84 + y 8dp | a bot bubble grows from its avatar |

**`Modifier.squish()`** replaces every ripple:

- **Touch-down:** scale 0.94 for buttons, 0.97 for rows and cards, applied in under 16ms with `tween(50)`.
- **Release:** the `snappy` spring back to 1, a slight overshoot that reads as bouncy.
- **Haptic** at touch-down.
- **The action runs on release.** It is cancelled if the finger slides more than 8dp away.

**Navigation:** a pushed screen slides in from the end edge, 100% → 0, on `sheet`, while the screen below drifts 25% toward start and dims to 0.9. Predictive back drags it live.

**Lists:** stable keys and `animateItem()` with `smooth`. A new message auto-scrolls with `gentle` only if the user is within 120dp of the bottom; otherwise a `↓ N new` pill (48dp) appears.

### 5.2 Haptics (`design/Haptics.kt`; Android `View.performHapticFeedback`, no-op on desktop)

| Moment | Constant |
|---|---|
| Any button or buddy press | `VIRTUAL_KEY` |
| Tab switch, toggle, swipe threshold crossed, palette selection | `CLOCK_TICK` |
| Message sent, pairing done, Done one-shot, Allow | `CONFIRM` (API 30+; fallback `VIRTUAL_KEY`) |
| Error, Deny, destructive confirm | `REJECT` (API 30+; fallback `LONG_PRESS`) |
| Long-press menu opens | `LONG_PRESS` |
| Pet purr | `CLOCK_TICK` every 120ms |
| Needs-you arrives while the app is open | `CONFIRM`, then 120ms later `CLOCK_TICK` (a double tap) |

Haptics can be turned off in Settings and also follow the system haptics setting.

---

## 6. Sounds

The user likes the soft short "bloop". Every sound is **synthesized once into tiny OGG files** shipped in resources, each under 10KB. Android plays them through `SoundPool`, preloaded for low latency, with `USAGE_ASSISTANCE_SONIFICATION`. The desktop uses `javax.sound.sampled.Clip`.

| Name | When | Recipe | Level |
|---|---|---|---|
| `bloop` | Done (turn ≥ 3s), learned fact, paired | Sine 660 → 440Hz, exponential glide over 90ms; attack 5ms, exponential decay 140ms; a little 2nd harmonic at −14dB | −18dBFS |
| `pop` | Message sent | Sine 900 → 1250Hz up-chirp, 45ms; attack 2ms, decay 50ms | −22dBFS |
| `boop-boop` | Needs you (app open) | 520Hz then 780Hz, 80ms each with a 60ms gap; soft sine | −18dBFS |
| `plonk` | New message from another bot while you are elsewhere in the app | Sine 520 → 600Hz, 70ms; decay 120ms | −22dBFS |
| `bonk` | Error | Triangle 220 → 160Hz, 120ms; decay 100ms | −20dBFS |

**Rules:**

- At most one sound every 700ms.
- Silent when the ringer is silent or vibrate, or Sounds is off.
- No sound for taps; those are haptic only.
- No sound in the background; notifications use the system sound.

---

## 7. Color

The ground is neutral and warm. **Color comes from the bots and one accent.** Amber means "needs you", red means error, blue means working (the ring).

| Token | Light | Dark | Use |
|---|---|---|---|
| `ground` | #FAF8F5 | #0F0E12 | screen background |
| `surface` | #FFFFFF | #1A191E | cards, sheets, tab bar, stage |
| `surface2` | #F1EEE9 | #24232A | agent bubble, pressed row, chips, fields |
| `hairline` | #E7E3DD | #2C2A31 | dividers (only where there is no fill) |
| `text` | #18161B | #F3F1F6 | primary text |
| `secondary` | #6B6672 | #A7A2AE | previews, captions (AA on ground) |
| `tertiary` | #8F8A96 | #77737E | icons and non-text only |
| `accent` | #6E56FF | #9D8BFF | links, active tab icon, focus |
| `accentFill` | #6E56FF | #6A55F0 | user bubble, primary buttons, unread dot (white text passes AA) |
| `onAccent` | #FFFFFF | #FFFFFF | text on accentFill |
| `needsYou` | #F5A524 | #FFB224 | badge, ring, card edge |
| `needsYouText` | #8A5A00 | #FFC766 | amber text |
| `error` | #E5484D | #FF6B6F | error fill and icons |
| `errorText` | #C62F35 | #FF8A8D | error text |
| `success` | #1FAF6B | #3DD68C | checks |
| `working` | #3B82F6 | #5B9BFF | working ring, link pulses |
| `scrim` | #000000 @ 32% | #000000 @ 55% | behind sheets |
| `shadow` | #000 @ 8%, blur 18, y 4 | #000 @ 40%, blur 18, y 4 | raised surfaces |

The theme follows the system unless set in Settings.

**Radii:** card 24, stage 28, row press 20, bubble 22 (the corner nearest the author is 8 at the end of a group), composer and buttons are capsules, sheet top 28.

---

## 8. Typography

**Rubik** is the UI font, a variable font with weights 300–900. It is rounded and friendly, covers Hebrew and Latin, and is under the OFL. Codync's Inter has **no Hebrew**, so it is out. **JetBrains Mono** is used for code, paths and commands. Both are bundled as compose resources.

Sizes are in sp, so they follow the system font scale.

| Style | Size / line | Weight | Use |
|---|---|---|---|
| display | 34 / 40 | 700 | studio name, empty states |
| title | 26 / 32 | 700 | screen titles |
| title2 | 21 / 26 | 600 | sheet titles |
| headline | 18 / 24 | 600 | row names, card titles |
| body | 17 / 24 | 400 | messages |
| callout | 16 / 22 | 400 | buttons (600) |
| sub | 15 / 20 | 400 | row previews |
| caption | 13 / 18 | 500 | time, labels, section caps (+0.4 tracking) |
| micro | 12 / 16 | 500 | tab labels, badges (the floor; nothing smaller) |
| code | 14 / 20 | JetBrains Mono 400 | code, paths |

**Icons:** Material Symbols **Rounded**, weight 500, optical size 24, as vector resources, about 60 of them. The filled variant marks the active state.

---

## 9. Hebrew and RTL

The phone is Hebrew. The app language follows the system (Hebrew), and Settings can override it to English or Hebrew. Strings live in compose resources `values/` and `values-iw/`, with no hard-coded text.

1. **Layout direction comes from the locale.** Use `start` and `end` only, never left and right. Codync's `absoluteOffset` exception applies only to the stage graph, which must not mirror away from its lines.
2. **Mirror directional icons:** back chevron, reply, "open" arrows and swipe hints. Do not mirror send (up arrow), play, checks or buddies.
3. **Bubbles:** the user's bubbles sit on the end side, which is the left in RTL; the bot's sit on the start side. This happens automatically with `Alignment.End`.
4. **Text direction per paragraph:** every message, preview and field uses `TextStyle(textDirection = TextDirection.Content)`, so the first strong character decides. A Hebrew message is right-aligned and an English one left-aligned, even in a Hebrew UI. The composer flips alignment as you type.
5. **Always LTR:** code blocks, paths, commands, `/skills`, `@mentions`, model names and numbers with units. Inline ones are wrapped in Unicode isolates (FSI…PDI), so `הרץ את /build עכשיו` doesn't scramble.
6. **Time and digits:** Western digits, tabular numerals, LTR-isolated.
7. **Swipes are by edge, not by side:** "swipe toward start = Close" stays consistent with the system's back-gesture side.
8. **Buddies:** faces are symmetric and never mirrored. "Reading direction" behaviors (the Working scan bias, looking toward the bubble, a pushed screen's drift) take their sign from `LocalLayoutDirection`. Finger gaze is physical and never flips.
9. **Font:** Rubik covers both scripts, so there is no fallback jump between Hebrew and English in one line.
10. **Typing along:** Hebrew ו, ע and א count as round vowels (§4.4).
11. **QA:** every screen is screenshot-tested in both `en` and `iw` at font scale 1.0 and 1.3.

---

## 10. Touch-size checklist (rule 2)

| Element | Visual | Touch |
|---|---|---|
| Any icon button | 24–28 icon | **48×48** |
| Primary button | 56 tall | 56 |
| Hero button (Wake up PC) | 64 tall | 64 |
| Send / Stop / Mic | 48 circle | 48 |
| Composer field | 56 min | 56 |
| Tab item | 60 × 72+ | same |
| Roster row | 76 | full row |
| Session / chat row | 64–72 | full row, × is its own 48 |
| Chips | 40 tall | 48 (padded via `minimumInteractiveComponentSize`) |
| Swatch / shape tile | 48 / 64 | same |
| Switch | 52×32 | 56×48 |
| Spacing between adjacent targets | ≥ 8 | – |

---

## 11. What we took from where

| Source | Taken | Verified how |
|---|---|---|
| **Codync** (local checkout, read-only) | 8 shape paths, 11 colors, lighting model, badge cut-out rings, group avatar layouts, graph layout, `pulse()` ring, `Motion` tokens, plan card, composer morph button, roster row anatomy, bitmap render for notifications | Read `apps/android/design/*.kt` and `app/*.kt`, plus iOS `CodyncKit/Design/CharacterAvatar.swift` |
| **OpenMausBot** (cloned to `.research/openmausbot`, Apache-2.0; "Maus" and its mascot are trademarks, so we copy ideas, not the character) | One face on many bodies; a 3-stop gradient from one hex; a state → (amplitude, period) motion table; squash anchored at the ground; ease-out-back entrances (c 1.7); "powering-down" shrink; expression pools that re-roll; a 320ms blink with random per-state intervals; confetti, orbit and glyph effects; the speaker presence line with shimmer and timer; human-readable tool step labels; a shared clock with offscreen pausing; the mascot gallery as a debug screen | `src/components/CursorAvatar.tsx`, `src/lib/mascot.ts`, `mascot-motion.ts`, `Sidebar.tsx`, `GroupView.tsx`, `ToolActivity.tsx`, `styles.css`. It is an Electron desktop app with **no haptics and no UI sounds**; we add both. |
| **Grok Bot** (xAI, beta Aug 2026) | Geometric bodies with tall oval eyes and no mouth by default; roster moods (idle, working, waiting, blocked, thinking, done); "three dots" thinking and an orbit ring for long work; an iMessage-like chat; groups of 2–6 bots; steps appearing as the job unfolds; a queue option in the composer; an "Auto" model chip | **Public material only:** VentureBeat, Composio, 60fps.design ("mascots blink and shift their gaze"), and an unofficial site (grokbotexplained.app) for the shape, color and state lists. x.ai/bot returned 403, and no shipped build was available locally, so **the REA skill was not used**. Treat the details as unverified. |
| **Claude Duet** (Anthropic, research preview Oct 21 2026) | One ongoing thread per bot; quiet-unless-needed notifications; approval before consequential actions; a morning brief (a routine template) | **Press only** (MacRumors, Engadget, Gizmodo). Duet has **no mascot** (its identity is a wiggly line and a circle), so it gives us nothing for characters. No artifact was available for REA. |
| **Hermes Bot Mode** (docs) | Profiles = bots, each with an avatar and a canonical Bot Chat; group rows interleaved in the roster; Routines = cron | Hermes desktop docs and the contract `profiles.*`, `groups.*`, `cron.manage` |

---

## 12. Not doing (and why)

- **XP, levels, streaks:** the user rejected them.
- **Blur or Liquid Glass:** cost, and it doesn't work on all targets.
- **Lottie or sprite sheets:** procedural buddies are smaller, react live and recolor for free. Hermes `pet.*` sprites are left unused.
- **36 moods** (as OpenMausBot has): 9 moods + 13 one-shots + 12 expressions carry the same meaning.
- **Material ripples and default components.**
- **Device-tilt parallax and pinch-zoom on the stage:** add them when the graph outgrows one screen.
- **The `Dots` skin:** later. It is a pure port of Codync's mask code.

## 13. Build order and acceptance

1. **Design module:** tokens, `squish`, haptics, sounds.
2. **Buddy, with the Buddy gallery.** Acceptance: 20 buddies on screen with p95 frame time under 8ms on a mid-range phone, and no buddy still for more than 4s.
3. **Bots home**, with stage and roster.
4. **Chat:** streaming, Work card, composer, session sheet.
5. **Chats, Routines, More.**
6. **Desktop shell.**

**Acceptance for the three complaints:**

- **(a)** A screen recording of the idle roster shows every buddy moving, out of sync.
- **(b)** An automated test asserts that every clickable node is at least 48dp.
- **(c)** A session can be closed from the chat, the session sheet and the Chats list, with undo, and back/scrim/× close every sheet.
