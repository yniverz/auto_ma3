# Design decisions (show creation)

From the MA3 Show Creator (Python, ../claude_ma3_showcreator), moved into AutoMA3 in D15. Earlier entries keep their
original wording where it is history; D9 and D14 are replaced by D15.

## D1 — Pipeline: MVR → rig.json → planner (AI) → plan.json → validator → compiler → ShowBuilder plugin
Deterministic code everywhere except the creative step. The planner writes data only (semantic keys, fixture ids),
the compiler owns all grandMA3 syntax (`BuildProfile.java`), the plugin is a generic step runner.

## D2 — Job exchange as a Lua data file, not JSON (2026-10-04)
MA3 2.3 has `ExportJson` but no documented JSON import. The compiler writes `return { ... }` and the plugin loads
it with `loadfile(path, "t", {})` (empty environment: the job cannot run code). The log goes the other way as
JSON lines written with `io`, one line per step, flushed after each line so a crash still leaves a readable log.

## D3 — Plugin steps are generic ops
`cmd` (a command string from the compiler), `export` (object → XML next to the log, for read-back), `list`
(names/addresses of objects), `paths` (all `GetPath` results). Steps marked `fatal` abort the job;
`allow_fail` steps (e.g. deleting an empty range) are logged but not counted as failures.

## D4 — Reserved ranges, page 1 by default
Groups, sequences, MAtricks 501–599 are deleted and rebuilt on every build. Executors: page 1 (configurable),
101–199; executors are only re-assigned, never deleted. Anything inside these ranges belongs to the generator.
A look keeps the executor the plan gives it (`exec`, e.g. taken from the AutoMA3 look list); otherwise the compiler
allocates the next free one. Numbers are allocated in plan order, so the same plan gives the same numbers.

## D5 — Version profiles
The build console's version (Consoles tab, completed from the installed onPC, e.g. 2.3 → 2.3.2) selects a profile (2.0 … 2.5; newer versions fall back to the newest known profile with
a warning). Only 2.3 is checked against its manual; the others inherit it and are marked "assumed". Any template
can be overridden per key in the console's command overrides (Consoles tab). The plugin refuses to run a job compiled for another major.minor.

## D6 — Library from MA3's own file
`library.json` is generated from `predefined_phaser.xml` of the installed version (numbers, names, attributes →
capabilities). Only judgement (energy, tags, optional capabilities) is hand-written in `library/annotations.json`.
No console dump is needed; the inspect job still lists pool 21 so a renumbered pool is noticed.

## D7 — Static values allowed
Plans may set static values (dimmer, haze, fog) besides presets; needed for ACCENT/BLINDER/BLACKOUT/HAZE/FOG.

## D8 — Cue method: recipe lines (decided by the probe, 2026-10-04)
Preset lines become cue part recipe lines (group + preset + a shared MAtricks pool object for the spread), cooked
once per sequence. On 2.3.2 this keeps the preset reference, spreads phase as a proper cycle (0/90/180/270), and
leaves an editable recipe on the console. Static values are stored from the programmer into the same cue.
`cue_method = programmer` remains as a fallback (full cycles are shortened so `At Phase` gives the same result).
Selection MAtricks phase before `At Preset` had no effect and was removed.

## D12 — Every sequence runs on speed master 3.N (`automa3.speed_master`, default 3.1) unless the plan says
`"speed_master": false`. Command: `Set Sequence S Property "SpeedMaster" "Speed<N>"`.

## D13 — Layers and speed (after the first AutoMA3 run, 2026-10-04)
Every generated sequence sets `OffWhenOverridden = No`, so a layer does not switch itself off under another layer.
Plans may set `speed_scale` (Div4…Mul4, relative to the BPM speed master) and `priority` per sequence. MOVEMENT cues
carry a static tilt (and optional pan) offset because the predefined position phasers are relative. Looks of the
AutoMA3 list that the plan does not build are written back disabled. Taste rules the user asked for (variety
over whole-rig chases, musical speed choices) live in planner/CLAUDE.md, with two validator warnings to back them.

## D14 — App = local web page (user decision, 2026-10-04) — replaced by D15
`python3 -m showcreator serve` (or `Show Creator.command`): stdlib HTTP server on 127.0.0.1 + one HTML page.
Long steps run as background tasks the page polls. The app starts plugin jobs over OSC `/cmd` (as AutoMA3 does)
and still shows the command to paste as a fallback. The planner runs as headless Claude Code
(`claude -p --output-format stream-json --permission-mode acceptEdits --add-dir shows`) in planner/, resuming the
last session per show for follow-up requests; the show is passed as SHOWCREATOR_SHOW. POSTs need the header
`X-ShowCreator: 1` and the Host must be localhost (no cross-site or DNS-rebinding triggers).
Shared logic moved to service.py; the CLI and the app both use it.
Live status: the planner runs with `--include-partial-messages`; a stream tracker turns the events into a status
line (waiting / thinking / writing a reply / writing plan.json with size and counts / running a command). The
thinking text is not part of the stream, only its start, so it shows as a phase. Console jobs report step progress.

## D9 — No runtime dependencies — replaced by D15 (AutoMA3's dependencies)
Python standard library only (own minimal JSON-schema checker in `showcreator/schema.py`), so the tool can later
be packaged as a single app. pytest for development.

## D10 — Left/right is seen from FOH (user decision)

## D11 — Without a look list the planner creates one from AutoMA3's defaults (user decision)

## D15 — Part of AutoMA3, rebuilt in Java (user decision, 2026-10-05)
The show creator is the **Show** tab of AutoMA3, so the consoles, OSC and looks are set up once:
- Build target: a console from the Consoles tab (default: the first enabled one at 127.0.0.1); its version selects
  the build profile and its command overrides apply to build commands too (`BuildProfile`, keys like `store_cue`,
  next to the playback keys of `Ma3Profile`). Builds only to grandMA3 onPC on this Mac (jobs and logs are files in
  `~/MALightingTechnology`). Dry run blocks builds and shows the command to paste instead.
- Settings (ranges, page, cue method, FOH side, Claude Code path) in Settings → Show creation; speed master from
  AutoMA3's BPM settings.
- Shows live in `<data folder>/shows/<name>/`, **separate from the setups** (user decision). They can be copied
  (inputs only: rig, plan, look list, MVR; the copy's planner starts fresh from the copied plan) to make another
  version during a show without touching the original. Delete moves a show to `shows/.deleted/`.
- Looks: the look list comes from AutoMA3's current looks (no HTTP round trip); built looks go into the Looks tab's
  import preview (merge by id, then page + executor), then Save. The generator name in `meta` stays
  `ma3-showcreator` for looks built before the move.
- The planner runs in `<data folder>/planner/` (instructions written by AutoMA3 on every start) and calls back with
  `./sc brief|validate|rig`, which runs `automa3.Main show …` with the same Java and AutoMA3 as the app.
- The Show API only accepts changes from this Mac with the header `X-AutoMA3: 1` (no cross-site triggers, no
  builds or planner runs from a tablet on the show network).
- The port keeps the Python behaviour exactly: Python's rounding and number formatting (`Py`), and parity tests
  against reference results of the Python version (`src/test/resources/showcreator/golden`: job files byte for
  byte, rig, validation, allocation, looks, brief).
- Optional: without onPC or Claude Code, AutoMA3 runs as before; the Show tab says what is missing.

## D16 — Real strobes, speed master only where it makes sense (user request, 2026-10-05)
Replaces D12's "every sequence runs on the speed master". A sequence is linked to the BPM speed master (and gets its
`speed_scale`) only when it contains a phaser that follows the beat; static looks and strobe phasers (library tag
`strobe`) are not, unless the plan sets `speed_master` explicitly. STROBE looks use the fixtures' strobe channel
function (static value `strobe`, Hz) where they have one, and the dimmer strobe phaser (own 10 Hz) only on fixtures
without. The plan check warns about strobes on the speed master, speed scales without effect, and strobe looks
that switch the dimmer of fixtures that have a strobe channel (MA3_NOTES "Strobe and speed").
The strobe function is set by the plugin's `strobe` step (Lua SetProgPhaserValue with the fixture's own function and
physical range), not by a command: grandMA3 rejects channel functions on the command line (first build attempt,
2026-10-05). Nothing per fixture model is stored, so any fixture type with a strobe function works after a patch read.

## Ideas, not decided
- Custom phasers generated as preset XML and imported (`Import Preset Library "x.xml" At Preset 21.201`).
