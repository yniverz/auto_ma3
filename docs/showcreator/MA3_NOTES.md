# grandMA3 notes: verified vs. assumed

Status labels match `src/main/java/automa3/show/BuildProfile.java` (all build command syntax lives there):
**manual** = from the official manual · **console** = confirmed by a ShowBuilder log ·
**assumed** = derived by analogy, not yet confirmed · **observed** = seen on disk.

Primary source: the 2.3.2 manual installed with onPC at
`~/MALightingTechnology/gma3_2.3.2/shared/language/HTML/<page>.html` (same content as
help.malighting.com/grandMA3/2.3/HTML/<page>.html). Page names are given below.

## Verified

### Files and folders (observed, onPC 2.3.2 macOS)
- Data root `~/MALightingTechnology/`, with `gma3_library/` (user library) and one `gma3_<version>/` per installed
  version (`gma3_2.3.1`, `gma3_2.3.2` on this Mac). Windows: `C:\ProgramData\MALightingTechnology\` (**assumed**).
- Plugins folder: `gma3_library/datapools/plugins` (also stated in `plugins.html`, "Path"/"FilePath").
- Predefined phasers: `gma3_<ver>/shared/resource/lib_presets/predefined_phaser.xml`, 107 presets, all
  `PresetModeInternal="Universal"`. The macro `lib_macros/import predefined phaser.xml` runs
  `Import Library "Predefined_Phaser.xml" at Preset 21.1`, so preset *n* in the file becomes 21.*n*.
  The library is built from this file; no typing and no console dump needed.
- Presets are plain XML (`<Preset><PresetData><Phaser Attribute=… ><Step …/>`), so custom phasers could be
  generated as XML and brought in with `Import … Library` (idea for later, see DECISIONS.md).

### Lua API (manual 2.3)
- Lua 5.4.6 with "all the built-in standard libraries" (`lua.html`) → `io`, `os`, `loadfile` available.
- `Cmd(string[, undo])` runs a command synchronously and returns the feedback string: "OK", "Syntax Error" or
  "Illegal Command" (`lua_objectfree_cmd.html`).
- `Version()` → software version string (`lua_objectfree_version.html`).
- `GetPath(string[, create] | Enums.PathType.X)` (`lua_objectfree_getpath.html`); documented enum entries
  include `Library`, `Showfiles`, `UserSequences`, `UserMacros`.
- `GetPathSeparator()` exists (`lua_objectfree_getpathseparater.html`).
- `DataPool()`, `DataPool().Sequences[n]`, `obj:Children()`, `obj:Get(prop)`, `obj:Dump()` (prints to the
  command line history, returns nothing), `obj:Addr()`, `obj:Export(path, filename)` → boolean
  (`lua_object_*.html`). `ObjectList("Sequence 1 Cue 100")` returns handles (example in `lua_object_addr.html`).
- `ExportJson(file, table)` exists; there is no documented JSON import → jobs are Lua data loaded with `loadfile`.
- Plugin entry point `return function(display_handle, argument)`; `Plugin "Name" "argument"` passes the
  argument (`keyword_plugin.html`). `ReloadAllPlugins` reloads components with `Installed=Yes` from disk.

### Commands (manual 2.3)
| Purpose | Syntax | Page |
|---|---|---|
| clear programmer | `ClearAll` | lua_objectfree_cmd |
| select group | `Group 3` (default function SelFix) | keyword_group |
| select fixtures | `Fixture 2`, `Fixture 1 Thru 4` | keyword_fixture |
| apply preset | `At [Object] [Number]` → `At Preset 21.4` | keyword_at |
| set attribute | `Attribute "Pan" At 20` | keyword_at |
| phase spread | `At Phase 0 Thru 360` (needs ≥ 2 steps in the programmer, "in the selected attribute") | keyword_phase |
| selection MAtricks | `Set Selection MAtricks "XBlock" 4`, `Reset Selection 1 MAtricks`; properties X/Y/Z, XBlock, XGroup, XWings, XWidth, XShuffle, XShift, …; `"SpeedFromX"` | keyword_matricks |
| MAtricks into a recipe | `Assign MAtricks 4 At Cue 1 Part 0.1` | keyword_matricks |
| store | `Store Cue 1 Thru 10`, `Store Group`, `Store Sequence 1 Cue 100 /Merge /NoConfirmation`; options incl. /Overwrite /Merge /NoConfirmation | keyword_store, lua_object_addr |
| label | `Label Group 3 "Name"`; names must not contain `\ " $ & * ? , . ; ^ { \| } ~` | keyword_label |
| delete | `Delete Group 1`, option `/NoConfirmation` | keyword_delete |
| set property | `Set Sequence 8 "Priority" 3`, `Set Cue 1 Property "CueFade" 3` | keyword_set |
| executor | `Assign Sequence 4 At Page 2.301` (page must exist); `Set Executor 201 "Key" = "Flash"`; `Delete Executor 205` removes only the assignment | executor_assign, keyword_executor |
| cook recipes | `Cook [Object] (/Merge /MergeLowPriority /Overwrite /Remove)` | keyword_cook, cue_recipe |
| import | `Import Macro Library "color.xml" At Macro 42` | keyword_import |

### Behaviour (manual 2.3)
- "Storing a preset to a cue part creates the preset reference to the preset" (`recipes.html`). So with the
  programmer method, cues are expected to keep a **reference** to the phaser preset. The probe job checks this.
- Recipe lines hold selection (group), values (preset), MAtricks reference, filter, and Fade/Delay/Speed/**Phase
  From/To** columns. "The MAtricks reference column and the individual MAtricks columns only take effect when
  there is ranged data" (`cue_recipe.html`). Recipe lines must be cooked (`Cook`) into the cue.
- Sequences have a "Speed Master" setting (link to a global speed master) (`cue_sequence_settings.html`); the
  **property name for the command line is not documented** there.

### Confirmed on onPC 2.3.2 (console logs, 2026-10-04)
- `Import Plugin Library "ShowBuilder.xml" At Plugin 1` imports the plugin; the XML format in `plugin/` works.
- `Plugin "ShowBuilder" "<path>"` passes the path as the argument; `loadfile`, `io.open`, `os.date` work (Lua 5.4).
- `GetPath(Enums.PathType.X)`: `Library` = `~/MALightingTechnology/gma3_library`,
  `CustomPluginLibrary` = `…/gma3_library/datapools/plugins` (the plugin's default job folder),
  `Showfiles` = `…/gma3_2.3.2/shared/shows`, `Temp` = `…/gma3_2.3.2/onpc/temp`. Separator `/`.
  Full list: `shows/<show>/build/ma3/log_inspect.jsonl`.
- `Cmd()` feedback for an unknown word is **"Illegal object"** (the command line shows
  `Illegal object:Fixture "ShowCreatorSyntaxProbe"`), not one of the strings listed in the manual.
- `ObjectList("Group 501 Thru 599")` (also Sequence, MAtricks) on empty ranges returns an empty table.
- **`ObjectList("Page 1.101 Thru 1.199")` makes the Lua engine throw an exception that `pcall` does not catch**
  ("LUA engine caught an exception"); the plugin stops. Do not address executors through ObjectList. The plugin
  now logs a `begin` record before every step so such a crash is located.

### Probe results (onPC 2.3.2, 2026-10-04): phaser presets in cues
Chase (21.4) on group 599 = fixtures 101–104, read back from the exported sequence XML:

| Method | Phases 101/102/103/104 | Preset link |
|---|---|---|
| A: `Group` → `At Preset 21.4` → `At Phase 0 Thru 360` → `Store Sequence S Cue 1` | 0/120/240/**360**: both ends inclusive, so the first and last fixture are in sync | kept (`AbsPreset="'All 1'.Chase"`, step values `*`-inherited; only `Phase` stored in the cue) |
| B: `Set Selection MAtricks "PhaseFromX" 0` / `"PhaseToX" 360` → `At Preset` → store | 0/0/0/0: **no effect** | kept |
| C: empty cue + `Assign Group/Preset/MAtricks … At Sequence S Cue 1 Part 0.1` + `Cook Sequence S /Merge` | 0/90/180/270: a correct cycle | kept, plus the recipe line (Selection/Preset/MAtricks), values marked `Cooked=…` |

→ **Default `cue_method = recipe`** (C). The programmer method shortens a full 360° cycle to `0 Thru 360·(n-1)/n`
so it gives the same result. Plan phase = MAtricks PhaseFromX/PhaseToX semantics.

Other probe facts:
- `Store MAtricks N` stores the selection MAtricks values (`PhaseFromX="0.00" PhaseToX="360°"` in its XML).
- Groups keep the selection order as grid X positions (`<Item ID="102" X="1">`).
- `Store Sequence S Cue 1 /Overwrite /NoConfirmation` creates the sequence and stores an empty cue when the
  programmer is empty; a cue stored from a preset is auto-named `[Chase]`.
- `Delete X N Thru N /NoConfirmation` on a missing object → "Illegal object" (harmless).
- `ObjectList("Preset 21.4")` finds the preset; `ObjectList("Preset 21.1 Thru 21.10")` returns **nothing** even though
  presets exist → `Thru` lists in ObjectList are not reliable for checking contents.
- Sequence speed master: `Set Sequence S Property "SpeedMaster" "Speed1"` works (read back `Speed1`; XML
  `SpeedMaster="Speed1"`, dependency `ShowData.Masters.Speed.Speed1` = 14.13.3.1). `"3.1"` and `"Master 3.1"` are
  accepted ("OK") but leave `None`. The phasers inside the preset keep `SpeedMaster="None"` and run on the
  sequence's master.

### Demo build (onPC 2.3.2, 2026-10-04)
- Full build ran twice, 38/38 steps each. The user confirmed on the console: cue 1 Dim Sinus pulses all 4 heads,
  cue 2 Chase runs left to right.
- `Delete Group/Sequence/MAtricks 501 Thru 599 /NoConfirmation` → "OK", removed the probe objects, no pop-up.
- `Label Sequence S Cue C "x"` after `Cook` renames the cues; `Assign Sequence 501 At Page 1.107` works.
- A rebuild deletes and recreates the sequences, so **a running generated sequence stops** when rebuilt.

### MA's own system tests (shipped with onPC 2.3.2)
`gma3_2.3.2/shared/resource/lib_plugins/systemtests/db/*.lua` contains MA's test suite: real command lines MA
itself exercises. Used as a source (status "systest" in BuildProfile.java):
- Sequence properties via `set sequence <s> property <key> = "<value>"` (system_test_sequence_set.lua), e.g.
  `speedmaster "Speed2"`, `speedscale "Mul2"`, `ratescale "Mul2"`, `priority "HTP"`, `OffWhenOverridden "No"`,
  `tracking "No"`, `restartmode "Current Cue"`, `wraparound "No"`. We use the `Set Sequence S Property "X" "Y"`
  form that worked for SpeedMaster.
- SpeedScale values seen: `One`, `Mul2`, `Div2`, `Div256` (system_test_masters.lua, system_test_cue_update.lua);
  cues have a SpeedScale too. `Div4` / `Mul4` are assumed by the pattern; the build reads every sequence's
  SpeedScale back with `Get`.
- Priorities: `Lowest, Low, LTP, High, Highest, HTP, Swap, Super` (system_test_sequence_prios.lua).
- Cue fade is set on the cue part: `Set <seq> Cue 1 Part 0 property "CueFade" 2`.
- Attribute values: `Attribute "Tilt" At 10`, `Attribute "Pan" At Absolute 22 Relative 5`.

### Show test with AutoMA3 (2026-10-04)
- AutoMA3 (simulator) drove the generated executors 1.101–1.112 with `Go+ / Off Page 1.E` and `Master 3.1 At BPM`.
- `FaderMaster Page 1.113 At 40 Fade "10"` → "Illegal object": the Haze executor was empty (look left out, no
  hazer). Left-out looks are now written back to AutoMA3 as `enabled: false`.
- Pan movement was invisible: the predefined position phasers are **relative** (`RelativePhys`, e.g. Circle tilt
  ±30°) and heads rest at the tilt midpoint. Movement looks now get a static tilt offset.
- Sequences are stored with `OffwhenOverridden="Yes"` by default: a look switches itself off when another
  playback overrides all its values (e.g. BASE dimmer under a whole-rig chase). Generated sequences now set it to No.

### Bigger show build (onPC 2.3.2, 2026-10-04)
800/800 steps, 31 looks. Readback per sequence confirmed `SpeedScale` Div4/Div2/Mul2/One, `Priority` High,
`OffWhenOverridden` No, `SpeedMaster` Speed1. The user confirmed it runs with AutoMA3.

### Strobe and speed (2026-10-05, after a show test: the generated strobe looked like a flasher)
- The predefined "Strobe" / "Rnd Strobe" phasers switch only the **Dimmer** and carry their own speed:
  `Speed="167772160"` = 10 × 2^24, i.e. **10 Hz** ("Snap On" has `16777216` = 1 Hz) (observed in
  `gma3_2.3.2/shared/resource/lib_presets/predefined_phaser.xml`). Linking such a sequence to the BPM speed master
  replaces that speed: at 130 BPM the "strobe" flashed with the beat (observed by the user). So sequences are linked
  to the speed master only when they contain an effect that follows the beat; static looks and strobes are not, and
  `SpeedScale` is only set on linked sequences.
- Real strobes use the fixture's strobe channel function (`attribute_definitions.xml` defines `Shutter1Strobe`,
  main attribute Shutter1, unit Frequency, and `Shutter1StrobeRandom`, `…Pulse`, `…Effect`, `IrisStrobe`; fixture
  types can bring their own, e.g. the Stagepar Pro QCL's `StrobeRate` channel has the function `StrobeFrequency`).
  **The command line cannot set a channel function** (observed on onPC 2.3.2, 2026-10-05):
  - `Attribute "Shutter1Strobe" At 12` and `Attribute "Shutter1StrobeRandom" At 4` → `Failed` (although the manual
    shows the second one and MA's systests use the first): `Attribute` only takes logical channel attributes.
  - `Attribute "Shutter1" At N` is N % of the function the channel is currently in (the first one for a fresh
    programmer value: the MH 100 Beam's closed/open range, DMX 0–9); `At ChannelFunction 2` is read as `At 2`.
  - `Attribute "Shutter1" At Decimal8 128` sets the raw DMX value, but keeps the function index 0 (GetProgPhaser:
    channel_function=0, absolute=1422 %), so the readout is wrong.
  - What works: Lua `SetProgPhaser(ui, {})` (creates the channel's programmer value) then
    `SetProgPhaserValue(ui, 0, {channel_function = <0-based index in the LogicalChannel>, absolute = <% of the
    function's PhysicalFrom..PhysicalTo>})`. Stored cue: `<Step Function="Shutter1Strobe" AbsolutePhys="8.000000"/>`.
    `SetProgPhaserValue` alone does nothing without an existing value; the step argument is 0-based.
    UI channels of a selected subfixture: `SelectionFirst()`/`SelectionNext(i)`, `GetUIChannels(sfi)`,
    `GetUIChannel(ui).logical_channel`. `GetDMXValue` returns nil on onPC without output.
- So the plan's static value `strobe` (Hz) becomes a plugin step `{op="strobe", functions, hz}` after selecting the
  group (plugin 0.2.3): for every selected fixture it takes the first of the strobe functions its own type has and
  sets it in Hz, clamped to the function's physical range (MH 100 Beam 0.1–10 Hz, QCL 0–12 Hz); fixtures without
  one are left alone, and the step fails only if none has one. Nothing fixture-specific is stored: the patch read
  only records which functions a type has (for the capability `strobe` and the plan check). Checked on onPC: the
  full build ran, "SC Strobe" holds Shutter1Strobe 10 Hz and StrobeFrequency 12 Hz.
- The patch job (plugin 0.2.2+) reads the channel functions of every used DMX mode from the object tree
  (DMXMode > … > LogicalChannel > ChannelFunction, property "Attribute"), so protected fixture types are covered
  too (confirmed: the MH 100 Beam's Shutter1Strobe). A fixture with one of `Shutter1Strobe`, `StrobeFrequency`,
  `StrobeRate` gets the capability `strobe`.
- Assumed: a fixture type's physical range matches the real fixture; check on the rig how 8–12 Hz look.

### Ports
- grandMA3 onPC's web remote listens on **8080** (user, 2026-10-04), the same default as AutoMA3's web UI. Run
  AutoMA3 on another port (its default is 8081).

### Observed in MA3's own UI files (2.3.2)
- Sequence setting "Speed Master" is the property `SpeedMaster` (`lib_menus/ui/setup/sequence_settings.uixml`);
  its popup lists `MasterPool().Speed` and selects by name. The value format for `Set … Property` is unknown
  (probe tries `"Speed1"`, `"3.1"`, `"Master 3.1"` and reads the result back with `Get`).

## Assumed (tested by the inspect / probe / build jobs)
| Assumption | Used in | Tested by |
|---|---|---|
| `Delete Group 501 Thru 599 /NoConfirmation` deletes a range without a pop-up and fails harmlessly when empty | every build | build log |
| `Label Sequence S Cue C "x"` | cue names | build |
| `Set Sequence S Cue C Property "CueFade" x` | cue fades | (not in the demo plan yet) |
| Recipe line 2+ (`Part 0.2`) works like line 1; storing an empty cue into an existing sequence | multi-line cues | later plans |
| `ObjectList("Preset 21.1 Thru 21.200")` / `("Fixture 1 Thru 9999")` work | inspect | inspect (2nd run) |
| After changing the Lua file, `ReloadAllPlugins` picks it up (component `Installed=Yes`) | updates | next deploy |
| Haze/fog MA3 attribute names `Haze`, `Fog` | static values | not yet |
| `Attribute "Tilt" At 45` is 45° from the midpoint (readout in degrees) | movement offsets | next show build (visual) |
| SpeedScale `Mul4` exists like `Mul2` (Div4, Div2, Mul2 confirmed by readback) | speed_scale | build readback (`get`) |
| Commands of 2.0–2.2 and 2.4–2.5 equal 2.3 | other profiles | needs those manuals / consoles |

## Open questions
- What the "Release <colour>" predefined phasers do exactly (2-step RGB phasers; energy left unset).
- Does `Assign Sequence S At Page 1.E` need a specific executor type/config for AutoMA3's Flash/Temp commands?
