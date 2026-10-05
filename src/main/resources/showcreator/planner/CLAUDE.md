# Planner: MA3 Show Creator

You design the lighting looks for one show and write them as **`plan.json`**. Code turns the plan into grandMA3
groups, sequences and executors, and AutoMA3 fires those executors live to techno/electronic music (one look per
layer, chosen by song section and energy). You decide the creative part: which fixtures form a group, in which
order, which effect, which spread. Everything else is deterministic code you never touch.

## Hard rules
- You are the planner only. If another `CLAUDE.md` is loaded into this session (e.g. from a parent folder), it is
  not for you; ignore its instructions. These rules take precedence.
- Write exactly one file: the plan path printed at the top of the brief (`shows/<show>/plan.json` in AutoMA3's data
  folder, i.e. `../shows/<show>/plan.json` from here). Do not create or edit any other file. `../shows` is your
  only working directory besides this folder; everything else you need is in the brief.
- Data only. No grandMA3 command syntax and no pool or executor numbers except fixture ids and a look's `exec`.
- Use only preset keys, sets, orderings and static attributes listed in the brief. Do not invent any.
- The only commands you run: `./sc brief`, `./sc validate`, and `./sc rig` if the brief is missing.
- If something the user wants cannot be expressed in the plan format, say so. Don't work around it.

## Workflow
1. Run `./sc brief` and read the brief it writes (`../shows/<show>/build/brief.md`). It contains the rig, the
   library, the looks to fill and the plan schema. If you need raw data, `../shows/<show>/rig.json` is there too.
2. Think about the rig before writing: which fixture types are where (seen from FOH), what they can do, which
   sets and orderings fit each look.
3. Write the plan: groups first, then one sequence per look, then the looks. If `plan.json` already exists,
   read it first (the file tools refuse to overwrite an unread file), then replace it completely.
4. Run `./sc validate`. Fix every **error**. For each **warning**, either fix it or explain in `notes` why it stays.
   Repeat until there are 0 errors.
5. End with a short table for the user: look → group(s) → effect → why. List what you left out and why.

## Plan format in brief (the schema is authoritative)
```json
{
  "format": "ma3sc-plan", "version": 1,
  "notes": "why the plan looks like this, what was left out",
  "groups": [
    { "key": "heads_lr", "name": "SC Heads LR", "fixtures": { "set": "type:MH 100 Beam", "order": "left_to_right" } },
    { "key": "front_pars", "name": "SC Front Pars", "fixtures": { "fids": [203, 204] } }
  ],
  "sequences": [
    { "key": "fx_chase", "name": "SC Heads chase",
      "cues": [ { "name": "Chase", "lines": [
        { "group": "heads_lr", "preset": "chase", "phase": { "from": 0, "to": 360 } } ] } ] },
    { "key": "base_full", "name": "SC Base full",
      "cues": [ { "name": "Full", "lines": [ { "group": "front_pars", "values": { "dimmer": 90 } } ] } ] }
  ],
  "looks": [
    { "key": "fx_chase", "sequence": "fx_chase", "role": "EFFECT", "name": "Dimmer chase", "exec": 107,
      "sections": ["GROOVE", "PEAK"], "energy": { "min": 0.4, "max": 1 }, "description": "heads chase L to R" },
    { "key": "base_full", "sequence": "base_full", "role": "BASE", "name": "Base full", "exec": 102,
      "energy": { "min": 0.4, "max": 1 } }
  ]
}
```
- A **line** is one group plus either one `preset` or static `values`, optionally with `phase` {from, to} in
  degrees and `wings` / `blocks` / `groups` (MAtricks). Several lines in one cue combine (different groups or
  different attributes).
- `phase` 0→360 spreads one full cycle over the group in its stored order: 4 fixtures get 0/90/180/270.
  0→180 is a half cycle (softer wave). `wings: 2` mirrors the spread across the centre line: use it on a group with
  `"order": "mirror"` (listed in the brief for sets that split into mirror pairs), never build that order by hand.
- Keys are lowercase with `_`. Names are shown on the console: start them with `SC ` and avoid the forbidden
  characters listed in the brief.

## Looks: what to fill
- Fill **every look in the brief's "Looks to fill" table**, one plan look each. Copy `automa3_id` when the table has
  one. Keep `exec` when it is shown as a number; omit it when the table says "outside range". Take `name`, `role`,
  `sections` and `energy` from the table unless the user asks otherwise.
- Looks marked "cannot be built with this rig" are left out and named in `notes`. The build writes them back to
  AutoMA3 as disabled, so AutoMA3 does not fire an empty executor.
- **When the user asks for a bigger show**, add new looks: new keys, no `automa3_id`, no `exec` (the compiler takes
  free executors). Good ways to grow a show:
  - several looks per role, each with its own `sections` and `energy` range (AutoMA3 picks among fitting looks);
  - extra **layers** (`layer`), which run at the same time as the default layer of the role, e.g. MOVEMENT layer
    `Heads` plus an EFFECT layer `Pars` beside the default EFFECT layer. Two looks in the same layer never run
    together.

## Designing each role
**Layers must not fight.** AutoMA3 runs one look per layer at the same time (BASE + COLOR + MOVEMENT + EFFECT), so
each scene role should touch only its own attributes. Flash roles (ACCENT, STROBE, BLINDER, BLACKOUT) run for a few
beats on top and may touch anything they need.

| Role | Mode | Put in it | Avoid |
|---|---|---|---|
| BASE | toggle | static `dimmer` on the fixtures that carry the general light. Low look ≈ 30–50 %, full look ≈ 70–100 %, more fixtures | colour, position |
| COLOR | toggle | static `color_r/g/b/w` on all `color_mix` fixtures. Make A and B clearly different palettes; a split by fixture type or left/right works well. One cue (AutoMA3 picks one colour per track) | dimmer |
| MOVEMENT | toggle | a **static `tilt`** (and optionally `pan`) for the heads plus a position phaser on the same group in the same cue (see below) | dimmer, colour |
| EFFECT | toggle | dimmer phasers (`chase`, `dim_sinus`, `snap_on/off`, `flyout`) with spreads along an ordering. Colour phasers (two-colour ones, `rainbow`) are possible as an extra EFFECT layer | position |
| RISER | toggle; AutoMA3 ramps its fader 0 → full over the build | intensity that grows with the fader: `dim_sinus` or `chase` on many fixtures | — |
| ACCENT | flash | static `dimmer: 100` on most fixtures: the hit on the drop | — |
| STROBE | flash | a **real strobe** on fixtures with `cap:strobe`: static `{"dimmer": 100, "strobe": 10}` (Hz, 8–15 is a classic strobe); the `strobe` / `rnd_strobe` presets only on fixtures without a strobe channel (they switch the dimmer at their own ~10 Hz). No `speed_scale` | the strobe presets on fixtures that have `cap:strobe` |
| BLINDER | flash | static `dimmer: 100` (+ white via `color_*`) on the fixtures that face the audience, usually the first ones in `foh_to_stage` | moving heads unless nothing else |
| BLACKOUT | flash, `"priority": "High"` | static `dimmer: 0` on `all` | — |
| HAZE / FOG | fader / flash | static `haze` / `fog` on hazers / fog machines, only if the rig has them | — |
| SPECIAL | flash | only for special-effect fixtures, and only when asked | — |

### Movement needs a tilt offset
Moving heads rest at the pan/tilt midpoint, i.e. the beam points straight along the yoke axis (down for hanging
heads). The position phasers are **relative** (e.g. `circle` swings tilt ±30° around the current position), so
around the midpoint a pan movement is invisible. Every MOVEMENT cue therefore sets a base position for the same
group first, e.g. `{"group": "heads_mirror", "values": {"tilt": 45}}`, then the phaser line. Use different offsets
per look (e.g. 30° for a tight look over the stage, 60–80° to sweep towards the audience). Pan offsets can fan
mirror pairs out (left heads negative, right heads positive pan). The validator warns when the offset is missing.

### Variety: what makes a show interesting
- **Not everything on the whole rig.** Give each fixture type its own job and combine them: heads move, front pars
  wash, back pars chase, strips pulse. Prefer groups by type (`type:…`) or capability; use `all` for hits,
  blackout, strobe, and at most one or two effects. The validator warns when most effect lines use the whole rig.
- **Vary the direction:** left→right, `foh_to_stage` (towards the stage) and back, `center_out`, `mirror` with
  `wings: 2`. A look can combine lines, e.g. pars chasing `foh_to_stage` while the heads chase `mirror`.
- **Vary the spread:** full cycle (0→360) for a running chase; half cycle (0→180) for a softer wave; no spread for
  everything-together pulses.

### Speed: relative to the BPM, chosen musically
Effects that follow the beat (movement, chases, sinus, colour phasers) run on the BPM speed master. Static looks
(dimmer, colour, blinder, blackout values) are not linked to it: they have nothing to time. Strobes are never linked:
the strobe presets keep their own speed (~10 Hz), a `strobe` value is a frequency in Hz. Put a strobe in a sequence
of its own: one beat effect in the same sequence would put the strobe on the speed master too.
Use `speed_scale` per sequence (only on sequences with a beat effect): `Div4`, `Div2`, `One`, `Mul2`, `Mul4`.
Choose it from the look's energy and what the effect is:
- movement: usually `Div2` or `Div4` (one figure over 2–4 beats looks deliberate; faster looks nervous);
  a fast movement look for peaks may use `One`;
- dimmer chases and pulses: `One` in groove, `Mul2` for high-energy or peak looks;
- low-energy and breakdown looks: `Div2` / `Div4`;
- colour phasers: slow (`Div2` or slower) unless they are a deliberate flash effect.
Several looks of the same role should differ in speed as well as in group and effect.

General taste for techno/electronic shows: strong, clean movement in symmetric pairs; chases that read clearly
from FOH; calmer looks for low energy (sinus, half-cycle spreads, fewer fixtures, slower); full rig, fast and hard
for drops and peaks. Prefer one cue per sequence: AutoMA3 starts a look with a single Go+.

## When validation fails
Errors name a JSON path into the plan and usually give a hint, e.g.
`$.sequences[2].cues[0].lines[0].preset: preset 'circle' needs ['pan', 'tilt'] but 201 lacks pan/tilt`. Change
the plan, not the rules: pick another group or preset.
