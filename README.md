# AutoMA3

Automatic grandMA3 lighting for techno and electronic music, driven by Pioneer / AlphaTheta CDJs.

AutoMA3 reads the CDJs over Pro DJ Link (beat, BPM, on-air, rekordbox phrase analysis and waveforms) and decides, beat by beat, which of **your** executors run.
It sends grandMA3 command-line commands over OSC (`/cmd`). You program the looks on the console. The app decides when to use them.

- **Sections**: intro, groove, build, breakdown, drop, peak and outro. They come from rekordbox phrase analysis, or from the app's own analysis of the CDJ waveform when there is none, with optional live audio as a safety net.
- **Look-ahead**: it knows a drop is coming, so the fog goes 16 beats early, the riser ramps over the build, the strobe runs from the end of the build into the drop, there's a blackout on the beat before, and the hits land on the beat (sent `latencyMs` early).
- **Layers**: one running look per layer (base, colour, movement, effect, or your own layers), chosen by section and energy with variety, and one colour per track.
- **Events**: riser, drop accent, strobe, blinder, blackout, haze level per section, fog bursts, and specials (CO2 etc.) that only fire when armed.
- **BPM**: the DJ's BPM goes to a speed master (`Master 3.N At BPM x`).
- **Operator first**: AUTO / HOLD / DROP / NEXT / strobe / arm / energy controls from the console (SendOSC macros) or the web UI. When you touch an auto executor, the app backs off that layer for N bars (MA3 2.1+).
- **Which deck drives the lights**: with a DJM mixer, the playing deck whose fader is up (on air). With every fader
  down no deck drives the lights, or optionally a playing deck (Settings → CDJs); without a mixer the playing deck.
  **Follow** on a deck (or `/automa3/follow,i,<player>` from the console) chooses it by hand.
- **Multiple consoles, multiple MA3 versions**: each console gets commands in the syntax of its own version profile, with per-console overrides.
- **Safety**: strobe and blinder max on-time, minimum gap and duty cycle. Specials are disarmed by default.
- **Show creation** (optional, Show tab): builds the looks themselves in grandMA3 onPC from the patch, designed by
  an AI planner (Claude Code). See below.

## Run

AutoMA3 runs in a Terminal window; the UI is a web page (Safari, Chrome, or a tablet on the show network).

**Release** (no Java needed): download `AutoMA3-<version>-mac-arm64.zip` from the
[releases](https://github.com/yniverz/auto_ma3/releases), unzip it anywhere and double-click `AutoMA3.command`.
The first time macOS may refuse because it is not from the App Store: right-click → Open, then Open again. It
starts in Terminal and opens the UI in the browser. The terminal shows the link (Cmd-click it to open it again),
the data folder and the log. Quit with Ctrl+C or by closing the window. macOS asks once whether Terminal may
access the local network: allow it, the CDJs and the console need it.

**From the source** (Java 21 and Maven: `brew install openjdk@21 maven`):

```bash
mvn clean package
java -jar target/auto-ma3.jar --open      # live with CDJs (Mac on the Pro DJ Link network by Ethernet)
java -jar target/auto-ma3.jar --sim       # simulated DJ set, no CDJs needed
```

The UI is at http://127.0.0.1:8081/ (8080 is left free for grandMA3's own web interface).

Options: `--open` (open the UI in the browser), `--sim`, `--replay FILE` (a session from `recordings/`), `--speed 4`
(sim/replay speed), `--dry-run` (send nothing), `--record`, `--config FILE`, `--port N`,
`--analyze FILE` (print the waveform analysis of a track from `analysis/`). `--help` lists them.

Settings, setups, recordings, analyses and the log (`logs/automa3.log`, the run before as `automa3.previous.log`)
live in `~/Library/Application Support/AutoMA3` (`--config` uses another folder). Everything is edited in the UI.

In VS Code, **F5** runs the entry selected in Run and Debug: **Run** (live, simulator, replay of the last
recording), **Build** (release zip, or jar + tests) and **Debug** (breakpoints, needs the Java extension).

## Exchanging looks with other tools

Looks tab → **Export looks** / **Import looks…** writes and reads an `automa3-looks` JSON file: each look's
grandMA3 location (page, executor, sequence), role, description and all AutoMA3 settings, plus free `tags`/`meta`
for other tools. Imports can be partial (only some looks, only some fields) and are previewed before they land in
the editor. Other programs can also import directly into a running AutoMA3 (`POST /api/looks/import`).
Format and API: [docs/looks-format.md](docs/looks-format.md).

## Show creation (Show tab)

Optional: AutoMA3 works the same without it. It builds groups, effect sequences (from grandMA3's predefined
phasers) and executor assignments in **grandMA3 onPC on this Mac**, and hands the looks to the Looks tab, so a new
rig needs only its patch and a few words for the planner.

1. **Rig**: *Read patch from grandMA3* reads fixtures, types, attributes and positions from the open show (an MVR
   export can be added to cross-check positions).
2. **Look list**: AutoMA3's current looks, a look file, or the defaults.
3. **Plan**: the planner (Claude Code, installed and logged in) designs groups, effects and looks and checks its plan
   until it is valid. Ask for changes in plain words; it continues the same conversation.
4. **Build in grandMA3**: deletes and rebuilds the reserved ranges (default groups, sequences and MAtricks
   501–599) and assigns the executors (page 1, 101–199). Nothing outside them is touched.
5. **Use in AutoMA3…**: the built looks open in the Looks tab's import preview; import, then Save changes.

Strobe looks use the fixtures' own strobe channel (a frequency in Hz) where they have one, and grandMA3's dimmer
strobe only on fixtures without; only effects that follow the beat run on the BPM speed master (static looks and
strobes keep their own speed).

Shows are kept in `shows/` of the data folder, **separately from the setups**. **Copy…** makes another version of a
show (patch, look list and plan; the original stays as it is), e.g. a harder variant during a show. Building
replaces what a previous build put in the reserved ranges, also during a show.

The build uses the console at 127.0.0.1 from the Consoles tab (its version picks the command syntax; the build
commands can be overridden there too) and needs grandMA3's OSC input ("Receive Command") and the ShowBuilder plugin
imported once (docs/showcreator/TESTING.md). With **Dry run** on nothing is sent; the command to paste is shown
instead. Settings → Show creation sets the ranges, page, FOH side and the Claude Code path. Notes on the grandMA3
syntax (verified vs. assumed): docs/showcreator/MA3_NOTES.md; design decisions: docs/showcreator/DECISIONS.md.

## Releases and updates

`packaging/build-mac-release.sh` (or F5 → "Build: Mac release") builds `target/dist/AutoMA3/` and
`target/dist/AutoMA3-<version>-mac-arm64.zip`: `AutoMA3.command`, the app and a small Java runtime of its own.
Built for the CPU of the building Mac (Apple Silicon).

Pushing to the branch `release` (e.g. merging `main` into it) makes GitHub build it
(`.github/workflows/release.yml`): tests, then the zip, published as a GitHub Release. Versions are `major.minor`
from `pom.xml` plus the build number, e.g. `1.0.7`. Raise `major.minor` in `pom.xml` for bigger steps.

```bash
git checkout release && git merge main && git push && git checkout main
```

When a newer release exists, the terminal says so at the start with the link to it (nothing is shown without
internet). To update, unzip the new release and use its `AutoMA3.command`; settings stay where they are.

## Setup on the console

The **MA3 setup** tab in the web UI shows the exact steps for your settings. In short:

1. Menu → In & Out → OSC: line 1 *Receive Command = Yes* on port 8000 (the app sends to it). Line 2 *Send = Yes*, destination = this Mac, port 8001 (feedback and control buttons). Then turn on Enable Input and Enable Output.
2. Control macros, e.g. `SendOSC 2 "/automa3/hold,N"`, `SendOSC 2 "/automa3/drop,i,1"`.
3. Enter each executor (page, executor number, role) on the **Looks** tab and press **Test** on each one.
4. Assign speed master 3.1 (configurable) to your phasers and chases.

## Minimal vs. complex shows

A minimal show needs one executor per role: BASE, COLOR, MOVEMENT, EFFECT, RISER, ACCENT, STROBE, BLINDER, BLACKOUT, HAZE, FOG (SPECIAL optional). Missing roles are skipped.
For richer shows, add more looks per role and limit them by section or energy (the engine picks with variety). You can also add layers, e.g. a MOVEMENT layer "Spots" and a MOVEMENT layer "Washes" that run at the same time.

## grandMA3 versions

Command syntax was compared across the official manuals 2.0 – 2.5 (help.malighting.com):

| Profile | Basis | Notes |
|---|---|---|
| 1.x | unverified | Manuals no longer published, so the 2.0 syntax is assumed |
| 2.0 | manual | `/cmd` works. No pool-object OSC feedback documented, so no touch detection; use the control macros |
| 2.1 – 2.3 | manual | Playback feedback (`/13.13.1.6.X`), 15 speed masters |
| 2.4 – 2.5 | manual | 16 speed masters; 16 is the audio BPM master and is never driven |
| 2.6+ | assumed = 2.5 | Test with the Test buttons; override templates if anything changed |

The executor commands (`Go+`, `Off`, `Flash On/Off`, `Temp On/Off`, `FaderMaster … At … Fade`, `Master 3.x At BPM`) are identical in all documented versions. Executors are addressed as `Page P.E`, the form the manual uses in `FaderMaster Page 1.201` and `Assign … At Page 6.209`.

## How sections are found

1. **rekordbox phrase analysis** (PSSI, analyse with *Phrase* enabled and export to the USB). Repeated phrases are
   merged; Down/Bridge → breakdown, Up before a Chorus → build, Chorus after an Intro/Up/Down/Bridge → drop (then
   peak), Verse → groove. The waveform adds detail inside long phrases (where the build really starts, groove vs.
   breakdown).
2. **Waveform** (CDJ-3000 three-band or NXS2 colour), analysed per beat: every bar gets an energy level (no bass /
   bass / full). A drop is where the bass jumps (builds get loud early from snare rolls and risers, so their
   loudness is not used), the build is where mids and highs start rising before it, bars without bass are
   breakdowns. Bars with bass but no steady kick (single hits, a broken pattern; fewer than 3 of 4 beats with a
   kick) for 4 bars or more count as a build however loud the mids get: the drop is where the steady kick comes.
   A groove needs a steady kick; bass without one between other parts is a breakdown (or the build before a drop).
   Boundaries snap to the 4-bar phrase grid.
3. **Live audio** (optional): if the kick disappears for 2 bars (filter, EQ, cut), it's treated as a breakdown, and
   the kick returning after a long lull counts as a drop when it comes back at full energy (or there is no analysis).
   A groove after a breakdown stays a groove.
4. No analysis at all: groove with look rotation every 32 bars.

Inside the sections, the per-beat waveform also gives short **moments** (shown on the deck graph):

- **Breaks**: the bass stops for half a bar to two bars between stretches of kick (the 1-bar gaps in a techno peak,
  the bar before a drop), or the music goes silent. The BLACKOUT look runs for the break and an ACCENT hits when the
  kick comes back. In an intro or build a missing kick is not a break.
- **Bass hits**: single bass hits that repeat with the bar, without a running kick (an 808 on the one in a build).
  Each one gets a short ACCENT flash, in builds and breakdowns.
- **Build steps**: where a long build gets clearly harder from one 4-bar phrase to the next (more bass, a steadier
  kick, louder mids). One look layer changes there.

When the kick comes back after a break, one look layer changes too; after a whole bar or a stop every layer gets a
new look (the colour stays per track), so long peaks with regular breaks keep moving. The same happens when the drop's
blinder hit (every 8 bars) is over. The timed change (every 16 bars in a drop or peak, 32 in a groove) counts from the
last change, so changes never come right after each other. This needs more than one look on a layer (e.g. two
MOVEMENT looks for the loud parts). If the end of a build is a break or a stop, it stays dark there and the strobe starts on the drop instead.

The blackout, accent and flash for breaks and bass hits, the new look after long breaks and the change after the
blinder hit can be switched off in Settings → Engine.

When no deck drives the lights any more (deck paused or stopped, track ran out or ejected, every fader down in
*mixer* mode, player gone from the network), AutoMA3 stops everything it started after 3 seconds: the scene looks,
the riser and the haze. Flashes end on their own. With the next beat the looks come back (in a build with the riser,
haze at the section's level). Nothing is touched with AUTO off or in HOLD. Settings → Engine sets the delay
(0 = never).

The **Analysis** dropdown in the header picks what drives the show: *Auto* (rekordbox phrases when the track has
them), *rekordbox phrases* or *Waveform*. Both analyses are kept per track, so switching is instant. Each deck
shows a per-beat graph of what the waveform analysis sees.

## Development

```bash
mvn clean test
```

The tests cover OSC encoding, version profiles, phrase and waveform analysis (including real CDJ-3000 / rekordbox
data of tracks in `src/test/resources/tracks`), full engine runs on virtual time (drop timing, riser, fog lead,
strobe safety, hold, operator locks, console controls, analysis mode), look export/import, the update check and
show creation (parity with the Python show creator it replaces, the ShowBuilder plugin run in Lua against a console
mock if `lua` is installed, a fake console over OSC, the planner with a stand-in for Claude Code).
`--sim` runs three synthetic tracks that cycle through phrase / waveform / no analysis, with DJ-style overlaps.
Use `--record` at a gig and `--replay` afterwards to debug with the real data; every analysed track is also saved
to `analysis/` and can be inspected with `--analyze`.

Built on [beat-link](https://github.com/Deep-Symmetry/beat-link) by Deep Symmetry, the library behind Beat Link Trigger.
