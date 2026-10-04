# AutoMA3 look file — format reference

`automa3-looks`, version 1. JSON Schema: [looks.schema.json](looks.schema.json).

A look file describes AutoMA3's **looks**: grandMA3 executors, where they are in the show, and how AutoMA3 uses
them. It is meant for exchanging looks with other tools, for example a program that builds the grandMA3 show:

- **AutoMA3 → tool**: AutoMA3 exports its looks (Looks tab → **Export looks**, or `GET /api/looks/export`), the tool
  reads where each executor is and what it should contain.
- **tool → AutoMA3**: the tool writes a look file for the executors it created and imports it (Looks tab →
  **Import looks…**, or `POST /api/looks/import`). The file may be partial: only some looks, only some fields.

AutoMA3 cannot read the grandMA3 show back (grandMA3 only accepts commands over OSC). It trusts the file about what
is stored on each executor; **Test** on the Looks tab fires a look so it can be checked on the console.

## Contents

1. [Example](#example)
2. [File](#file)
3. [Look](#look)
4. [Roles and what the executor should contain](#roles-and-what-the-executor-should-contain)
5. [Modes](#modes)
6. [Sections and energy](#sections-and-energy)
7. [Context](#context)
8. [Import rules](#import-rules)
9. [API](#api)
10. [Workflow for a show generator](#workflow-for-a-show-generator)
11. [Compatibility](#compatibility)

## Example

A complete look as AutoMA3 exports it:

```json
{
  "format": "automa3-looks",
  "version": 1,
  "exportedBy": "AutoMA3 1.0.7",
  "exportedAt": "2026-10-04T19:12:00Z",
  "context": {
    "speedMaster": 1,
    "controlPrefix": "/automa3",
    "oscInPort": 8001,
    "controlSequences": { "900": "auto" }
  },
  "looks": [
    {
      "id": "3f2a9c1b",
      "name": "Move fast",
      "description": "fast circle on the spots",
      "role": "MOVEMENT",
      "layer": "MOVEMENT",
      "ma3": { "page": 1, "exec": 106, "sequence": 42 },
      "mode": "TOGGLE",
      "sections": ["DROP", "PEAK"],
      "energy": { "min": 0.5, "max": 1.0 },
      "weight": 1.0,
      "flashBeats": 0,
      "level": 100,
      "enabled": true,
      "tags": ["spots"],
      "meta": { "generator": { "preset": "4.12" } }
    }
  ]
}
```

A minimal import file, as a show generator might write it (everything not given gets a default):

```json
{
  "format": "automa3-looks",
  "looks": [
    { "role": "STROBE", "ma3": { "page": 2, "exec": 201 } },
    { "role": "MOVEMENT", "name": "Spots circle", "ma3": { "page": 2, "exec": 202, "sequence": 120 }, "sections": ["DROP", "PEAK"] }
  ]
}
```

## File

| Field | Type | Required | Meaning |
|---|---|---|---|
| `format` | string | yes | Always `"automa3-looks"`. Other values are rejected. |
| `version` | integer | no (1) | Format version. See [Compatibility](#compatibility). |
| `exportedBy` | string | no | Program that wrote the file. Informational. |
| `exportedAt` | string | no | ISO 8601 time of writing. Informational. |
| `context` | object | no | How the show connects to AutoMA3, see [Context](#context). |
| `looks` | array of look | yes | The looks, in display order. |

## Look

All fields are optional on import except a location for new looks (see [Import rules](#import-rules)). An export
always contains every field.

| Field | Type | Default (new look) | Meaning |
|---|---|---|---|
| `id` | string | generated | Stable id (AutoMA3 uses 8 hex characters, any string works). Matches existing looks on import. |
| `name` | string | `""` | Name shown in AutoMA3. Looks without a name are outlined in the UI after an import. |
| `description` | string | `""` | What the look does, free text. |
| `role` | string, [role](#roles-and-what-the-executor-should-contain) | `EFFECT` (with a note) | What AutoMA3 uses the look for. Case-insensitive. |
| `layer` | string | the role name | Scene roles (`BASE`, `COLOR`, `MOVEMENT`, `EFFECT`) keep **one running look per layer**. Looks of the same role in different layers run at the same time (e.g. MOVEMENT layers `Spots` and `Washes`). Empty or equal to the role name = default layer. |
| `ma3.page` | integer ≥ 1 | `1` | grandMA3 page of the executor. |
| `ma3.exec` | integer ≥ 1 | — | Executor number on that page (e.g. `101`, `201`). **Required for a new look.** |
| `ma3.sequence` | integer ≥ 1 or `null` | `null` | Number of the sequence on that executor. With grandMA3 2.1+ AutoMA3 then notices when the operator touches the executor and leaves its layer alone for a while. |
| `mode` | string, [mode](#modes) | the role's default | How AutoMA3 switches the executor. Case-insensitive. |
| `sections` | array of [section](#sections-and-energy) | `[]` (any) | Sections the look may be used in. |
| `energy.min`, `energy.max` | number 0..1 | `0`, `1` | Energy range the look fits. |
| `weight` | number > 0 | `1` | Relative chance to be picked among fitting looks of the same layer. |
| `flashBeats` | number ≥ 0 | `0` | Flash looks: beats to stay on; `0` = AutoMA3's default for the role. |
| `level` | integer 0..100 | `100` | Looks AutoMA3 controls by fader (`HAZE`, `RISER`, mode `FADER`): the maximum fader level. |
| `enabled` | boolean | `true` | Whether AutoMA3 uses the look at all. |
| `tags` | array of string | `[]` | Free tags. Kept and exported again; AutoMA3 does not interpret them. |
| `meta` | object | `{}` | Free data for other tools (any JSON). Kept untouched and exported again. Use it to remember what you created, e.g. `{"generator": {"preset": "4.12", "group": 3}}`. |

Aliases accepted on import: `page`, `exec`, `sequence` at the top level of the look instead of inside `ma3`;
`energyMin` / `energyMax` instead of `energy.min` / `energy.max`. Numbers may also be given as numeric strings.

## Roles and what the executor should contain

| Role | Default mode | AutoMA3 uses it for | The executor should hold |
|---|---|---|---|
| `BASE` | TOGGLE | base look / intensity, one always running | a sequence with the base state (intensity, maybe position) |
| `COLOR` | TOGGLE | colour, held for a whole track (new colour per track) | a colour sequence or preset |
| `MOVEMENT` | TOGGLE | movement | pan/tilt phasers |
| `EFFECT` | TOGGLE | dimmer / beam / gobo effects; the layer goes dark when no effect fits | effect phasers or chases |
| `RISER` | TOGGLE | build-up: started at the build, its fader master is ramped from 0 to `level` until the drop | something that grows with intensity (e.g. a sweep or chase that gets brighter) |
| `ACCENT` | FLASH | hit on the drop beat | a full-intensity hit |
| `STROBE` | FLASH | strobe at the end of builds and on drops (safety limited) | strobe |
| `BLINDER` | FLASH | audience blinder hits (safety limited) | blinders at full |
| `BLACKOUT` | FLASH | blackout on the beat before a drop | everything at 0 (high priority) |
| `HAZE` | FADER | hazer level per section | the hazer; its fader master sets the output |
| `FOG` | FLASH | fog bursts before drops (cooldown) | the fog machine |
| `SPECIAL` | FLASH | CO2, sparkular, confetti… on drops, only when armed | the special effect |

Phasers and chases should run on the speed master from the [context](#context) (`Master 3.<speedMaster>`):
AutoMA3 sets it to the DJ's BPM.

A minimal show has one look per role (`SPECIAL` optional); missing roles are skipped. More looks per role (limited
by sections and energy) and extra layers make the show richer; AutoMA3 picks among fitting looks with variety.

## Modes

| Mode | Start | Stop | Typical use |
|---|---|---|---|
| `TOGGLE` | `Go+ Page P.E` | `Off Page P.E` | sequences that run until switched off |
| `FLASH` | `Flash On Page P.E` | `Flash Off Page P.E` | hits held for some beats |
| `TEMP` | `Temp On Page P.E` | `Temp Off Page P.E` | like FLASH, with temp behaviour |
| `FADER` | `FaderMaster Page P.E At <level>` | `FaderMaster Page P.E At 0` | level only (hazer) |

The exact command syntax depends on the console's grandMA3 version and can be overridden per console in AutoMA3.

## Sections and energy

Sections AutoMA3 detects in the music: `INTRO`, `GROOVE` (kick running), `BUILD`, `BREAKDOWN` (no bass), `DROP`
(the first bars after a build), `PEAK` (rest of a high-energy part), `OUTRO`. A look with `sections` is only used in
those sections; an empty list means any section.

Energy runs from 0 (calm) to 1 (full). It comes from the section (e.g. breakdown 0.2, groove 0.55, drop 1.0), the
waveform and the operator's calmer/harder slider. A look is used when the current energy is within
`energy.min`..`energy.max`. If no look of a scene layer fits the energy, AutoMA3 takes the one with the closest energy
range among the looks allowed in the section; if no look of the layer is allowed in the section, the layer goes dark.
`EFFECT` layers go dark as soon as no look fits both section and energy.

## Context

Optional block describing how the show connects to AutoMA3. AutoMA3 writes it on export; on import it is only
applied when asked for (UI checkbox, or `context=true` in the API).

| Field | Type | Meaning |
|---|---|---|
| `speedMaster` | integer 1..16 | Speed master AutoMA3 sets to the DJ's BPM (`Master 3.N`). Assign it to phasers and chases. On grandMA3 2.4+ master 16 is the audio BPM master and is not driven. |
| `controlPrefix` | string | Address prefix of the operator controls, e.g. `/automa3`. Control macros on the console send `SendOSC <line> "<prefix>/<action>,N"` (toggle) or `"<prefix>/<action>,i,1"`; actions: `auto`, `hold`, `drop`, `next`, `strobe`, `arm`, `release`, `energy` (`,f,0..1`). |
| `oscInPort` | integer | UDP port AutoMA3 listens on for feedback and controls (the console's OSC line that sends to AutoMA3). |
| `controlSequences` | object | Sequence number (as string) → control action, for controls built as executors instead of macros (grandMA3 2.1+). `energy` on a fader sets the calmer/harder level. |

## Import rules

Per look in the file, in order:

1. **Selection.** In the UI only ticked looks are imported (API: all). Others are reported as `skipped`.
2. **Matching** (mode `merge` only): the look updates an existing look with the same `id`; otherwise one on the same
   page and executor (`ma3.page` defaults to 1 for this). Modes `add` and `replace` never match.
3. **Existing look (update):** only the fields present in the file are changed; all other fields keep their values.
   A field that is present but `null` resets it where that makes sense (`ma3.sequence`, `mode`, `layer`).
4. **New look:** needs `ma3.exec`, otherwise the entry is an `error`. Missing fields get the defaults from the
   [look table](#look). A given `id` is kept unless another look already uses it.
5. **Validation:** an unknown `role` makes the entry an `error` (nothing changes for it). Unknown sections are dropped
   and an unknown mode falls back to the role's default, each with a note. Unknown fields are ignored.

Modes:

| Mode | Effect |
|---|---|
| `merge` (default) | update matching looks, add the others |
| `add` | add every selected look as a new look (a new id when its id is taken) |
| `replace` | the selected looks replace all current looks |

The result of each entry is `added`, `updated`, `skipped` or `error`, with notes:

| Note | Meaning |
|---|---|
| `no name` | the look has no name (outlined in the UI) |
| `no role given: set to EFFECT` | new look without `role` |
| `unknown section "X" ignored` | value not in the section list |
| `unknown mode "X": role default used` | value not in the mode list |
| `file format version N is newer than this app: unknown fields ignored` | `version` > 1 |
| `unknown role "X"` (error) | value not in the role list |
| `no MA3 executor (ma3.exec) and no matching existing look` (error) | new look without location |
| `not selected` (skipped) | not ticked in the UI |

In the UI the result lands in the editor **unsaved**: names can be added (unnamed looks are outlined) before
**Save changes**. The API saves directly.

## API

AutoMA3 serves its API on the web UI port (default 8080, bound to 127.0.0.1 unless set otherwise in Settings).

### `GET /api/looks/export`

Returns the look file of the running setup (`Content-Disposition: attachment; filename="automa3-looks.json"`).

```bash
curl -o looks.json http://127.0.0.1:8080/api/looks/export
```

### `POST /api/looks/import?mode=merge&context=false`

Imports a look file into the running setup **and saves it**. Body: the look file. Query parameters:
`mode` = `merge` (default) | `add` | `replace`; `context` = `false` (default) | `true` to also apply the file's
context.

```bash
curl -X POST --data-binary @looks.json "http://127.0.0.1:8080/api/looks/import?mode=merge"
```

Response `200`:

```json
{
  "added": 1,
  "updated": 1,
  "skipped": 0,
  "entries": [
    { "index": 0, "label": "Move fast [1.106]", "action": "updated", "messages": [] },
    { "index": 1, "label": "STROBE [2.201]", "action": "added", "messages": ["no name"] }
  ]
}
```

`index` is the position in the file's `looks` list, `label` the look's name (or role) and location. Entries with
`error` are not applied; the rest of the file still is.

Response `400` with `{"error": "…"}` when the body is not JSON, `format` is not `automa3-looks`, `looks` is missing,
or `mode` is unknown. Nothing is changed then.

### `POST /api/looks/import/preview`

Used by the web UI: computes an import without saving. Body:
`{"file": <look file>, "base": [<current looks>], "mode": "merge", "select": [0, 2] | null}`. Response: the
resulting `looks`, the per-entry `entries`, `added` / `updated` / `skipped` and the file's `context`.

## Workflow for a show generator

1. Optionally export AutoMA3's looks to see the current layout (pages, executors, roles), or start from scratch.
2. Create the sequences and executors in grandMA3 (e.g. via the console's command line or a Lua plugin).
3. Write a look file with one look per executor: at least `role` and `ma3` (`page`, `exec`, ideally `sequence`), plus
   `name`, `description`, `sections` / `energy` as known. Put the generator's own ids in `meta` (or use a stable
   `id`), so the next run can update instead of duplicate.
4. Import it: `POST /api/looks/import?mode=merge` (or with the UI). Re-running with the same ids or locations
   updates those looks; fields the generator does not write (e.g. names typed in AutoMA3) are kept.
5. Use **Test** on the Looks tab to check each executor on the console.

Assign speed master `context.speedMaster` to the generated phasers, and build the control macros from
`context.controlPrefix` if wanted.

## Compatibility

- AutoMA3 ignores fields it does not know, so tools may add their own (prefer `meta` for tool data).
- A file with a higher `version` is still imported, with a note; fields of the newer version are ignored.
- New optional fields may be added in version 1 later; required fields and the meaning of existing fields only change
  with a new version number.
- Exports always contain every field of the current version.
