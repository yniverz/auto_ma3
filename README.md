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
- **Multiple consoles, multiple MA3 versions**: each console gets commands in the syntax of its own version profile, with per-console overrides.
- **Safety**: strobe and blinder max on-time, minimum gap and duty cycle. Specials are disarmed by default.

## Run

The easiest way is the Mac app (see below). From the source (Java 21 and Maven: `brew install openjdk@21 maven`):

```bash
mvn clean package
java -jar target/auto-ma3.jar             # live with CDJs (Mac on the Pro DJ Link network by Ethernet)
java -jar target/auto-ma3.jar --sim       # simulated DJ set, no CDJs needed
```

Then open http://localhost:8081/ (8080 is left free for grandMA3's own web interface).

Options: `--sim`, `--replay recordings/session-....jsonl`, `--speed 4` (sim/replay speed), `--dry-run` (send nothing),
`--record`, `--config FILE`, `--port N`, `--analyze analysis/<track>.json` (print the waveform analysis of a track).

Run from the source, settings live in `config.json` next to where it is started (created on first start); the Mac
app keeps them in `~/Library/Application Support/AutoMA3`. Everything is edited in the UI.

In VS Code, **F5** runs the entry selected in Run and Debug: **App** (the desktop window), **Build** (Mac app +
dmg, or jar + tests), **Terminal** (simulator, live with recording, replay of the last recording in the browser UI)
and **Debug** (breakpoints, needs the Java extension).

## Exchanging looks with other tools

Looks tab → **Export looks** / **Import looks…** writes and reads an `automa3-looks` JSON file: each look's
grandMA3 location (page, executor, sequence), role, description and all AutoMA3 settings, plus free `tags`/`meta`
for other tools. Imports can be partial (only some looks, only some fields) and are previewed before they land in
the editor. Other programs can also import directly into a running AutoMA3 (`POST /api/looks/import`).
Format and API: [docs/looks-format.md](docs/looks-format.md).

## Mac app

`packaging/build-mac-app.sh` (or F5 → "Build: Mac app + dmg") builds `target/dist/AutoMA3.app`,
`target/dist/AutoMA3-<version>.dmg` and the updater zip. The app contains its own Java runtime: the target Mac
needs nothing else. Releases are built by GitHub (see below).

- One window, backend inside: closing the window (or Cmd+Q) stops everything.
- Menu **Source**: Live CDJs, Simulator, Replay Recording…; menu **View**: Reload (Cmd+R), Open in Browser,
  Open Data Folder, Open Log, Check for Updates…. The reload button in the page header reloads too.
- Settings, setups, recordings, analyses and the log live in `~/Library/Application Support/AutoMA3`.
- Built for the CPU of the building Mac (Apple Silicon). The app is signed ad hoc, not by an Apple developer
  account: on another Mac open it the first time with right-click → Open (or allow it in System Settings →
  Privacy & Security). macOS asks once for local network access: allow it, the CDJs need it.

## Releases and updates

Pushing to the branch `release` (e.g. merging `main` into it) makes GitHub build the Mac app
(`.github/workflows/release.yml`): tests, then `AutoMA3.app`, published as a GitHub Release with the `.dmg`
(first install) and `AutoMA3-<version>-mac-arm64.zip` (for the updater). Versions are `major.minor` from
`pom.xml` plus the build number, e.g. `1.0.7`. Raise `major.minor` in `pom.xml` for bigger steps.

```bash
git checkout release && git merge main && git push && git checkout main
```

The installed app checks for a newer release when it starts (and on View → Check for Updates…) and asks
before updating. On "Update and restart" it downloads the new version, quits, swaps itself and starts again.
A copy that cannot replace itself (e.g. started from the disk image) opens the download page instead.

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
   bass / full). A drop is where bass **and** mids jump together (builds get loud early from snare rolls and
   risers, so the highs are ignored), the build is where mids and highs start rising before it, bars without bass
   are breakdowns. Boundaries snap to the 4-bar phrase grid.
3. **Live audio** (optional): if the kick disappears for 2 bars (filter, EQ, cut), it's treated as a breakdown, and the kick returning after a long lull counts as a drop.
4. No analysis at all: groove with look rotation every 32 bars.

The **Analysis** dropdown in the header picks what drives the show: *Auto* (rekordbox phrases when the track has
them), *rekordbox phrases* or *Waveform*. Both analyses are kept per track, so switching is instant. Each deck
shows a per-beat graph of what the waveform analysis sees.

## Development

```bash
mvn clean test
```

The tests cover OSC encoding, version profiles, phrase and waveform analysis (including real CDJ-3000 / rekordbox
data of tracks in `src/test/resources/tracks`), full engine runs on virtual time (drop timing, riser, fog lead,
strobe safety, hold, operator locks, console controls, analysis mode), look export/import and the updater.
`--sim` runs three synthetic tracks that cycle through phrase / waveform / no analysis, with DJ-style overlaps.
Use `--record` at a gig and `--replay` afterwards to debug with the real data; every analysed track is also saved
to `analysis/` and can be inspected with `--analyze`.

Built on [beat-link](https://github.com/Deep-Symmetry/beat-link) by Deep Symmetry, the library behind Beat Link Trigger.
