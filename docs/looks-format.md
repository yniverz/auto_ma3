# AutoMA3 look file (`automa3-looks`, version 1)

A JSON file describing AutoMA3's looks: where each one lives in grandMA3 and how AutoMA3 uses it.
Export it in AutoMA3 (Looks tab → **Export looks**, or `GET /api/looks/export`), import it with
**Import looks…** or `POST /api/looks/import`. Meant for exchanging looks with other tools, e.g. a
program that builds the grandMA3 show.

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

## Fields of a look

| Field | Meaning | Default when missing (new look) |
|---|---|---|
| `id` | Stable id; used to match the look on import. | new id |
| `name` | Shown in AutoMA3. | empty (outlined in the UI) |
| `description` | What the look does, free text. | empty |
| `role` | What AutoMA3 uses it for, see below. | `EFFECT` (with a note) |
| `layer` | Scene roles run one look per layer at a time; several layers of the same role run together (e.g. "Spots" and "Washes"). | the role name |
| `ma3.page`, `ma3.exec` | Executor in grandMA3 (page.executor, e.g. 1.106). **`exec` is required for a new look.** `page`/`exec`/`sequence` may also be given at the top level. | page 1 |
| `ma3.sequence` | Number of the sequence on that executor; lets AutoMA3 notice when the operator touches it (MA3 2.1+). `null` = unknown. | `null` |
| `mode` | How it is switched: `TOGGLE` (Go+ / Off), `FLASH` (Flash On / Off), `TEMP` (Temp On / Off), `FADER` (FaderMaster level only). | the role's default |
| `sections` | Sections it may be used in: `INTRO`, `GROOVE`, `BUILD`, `BREAKDOWN`, `DROP`, `PEAK`, `OUTRO`. Empty = any. | any |
| `energy.min`, `energy.max` | Energy range 0..1 it fits (calm .. full). Also accepted as `energyMin` / `energyMax`. | 0 .. 1 |
| `weight` | Relative chance to be picked among fitting looks. | 1 |
| `flashBeats` | Flash looks: beats to stay on (0 = AutoMA3's default for the role). | 0 |
| `level` | Fader looks (haze, riser): maximum fader level 0..100. | 100 |
| `enabled` | Used by AutoMA3 at all. | `true` |
| `tags`, `meta` | Free data for other tools; AutoMA3 keeps it untouched and exports it again. | empty |

### Roles

| Role | Default mode | Used for |
|---|---|---|
| `BASE` | TOGGLE | base look / intensity state, always one running |
| `COLOR` | TOGGLE | colour, held for a whole track |
| `MOVEMENT` | TOGGLE | movement (pan/tilt phasers) |
| `EFFECT` | TOGGLE | dimmer / beam / gobo effects, optional per section |
| `RISER` | TOGGLE | build-up: started at the build, fader ramped up to the drop |
| `ACCENT` | FLASH | hit on the drop beat |
| `STROBE` | FLASH | strobe (safety limited) |
| `BLINDER` | FLASH | audience blinder (safety limited) |
| `BLACKOUT` | FLASH | blackout on the beat before a drop |
| `HAZE` | FADER | hazer level per section |
| `FOG` | FLASH | fog bursts before drops |
| `SPECIAL` | FLASH | CO2, sparkular… only when armed |

Executors used with `TOGGLE` should hold a sequence that runs until switched off; `FLASH` executors are
flashed for a number of beats. Phasers and chases should use speed master `context.speedMaster`
(Master 3.N): AutoMA3 sets it to the DJ's BPM.

## Context (optional)

`speedMaster`, `controlPrefix`, `oscInPort` and `controlSequences` (sequence number → `auto`, `hold`, `drop`,
`next`, `strobe`, `arm`, `energy`, `release`) describe how the show connects to AutoMA3, so a show generator can
assign the speed master and build the control macros (`SendOSC 2 "<controlPrefix>/hold,N"`, see the MA3 setup
tab). On import it is only applied when asked for.

## Importing

Import is partial and lenient:

- **merge** (default): a look in the file that matches an existing look **by `id`, or else by page + executor**,
  updates **only the fields present in the file**; all other fields stay. Looks without a match are added.
- **add**: every selected look is added as a new look (new ids where needed).
- **replace**: the selected looks replace all current looks.

A new look needs at least `ma3.exec` (and should have a `role`). Unknown sections or modes are ignored with a
note; an unknown role or a new look without an executor skips that entry. Fields AutoMA3 does not know are ignored.
In the UI the result lands in the editor unsaved, with looks without a name outlined, so names can be added before
saving.

## API (AutoMA3 running)

```bash
# export
curl -o looks.json http://127.0.0.1:8080/api/looks/export

# import into the running setup and save (mode: merge | add | replace; context=true also applies "context")
curl -X POST --data-binary @looks.json "http://127.0.0.1:8080/api/looks/import?mode=merge&context=false"
```

The import answers with what happened to each entry:
`{"added": 2, "updated": 1, "skipped": 0, "entries": [{"index": 0, "label": "Move fast [1.106]", "action": "updated", "messages": []}, …]}`.

Note: AutoMA3 cannot read the grandMA3 show back; it trusts the file about what is stored on each executor.
Use **Test** on the Looks tab to check the mapping on the console.
