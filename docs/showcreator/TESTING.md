# Show creation: tests on the console (onPC)

Everything below runs on a **test show** in grandMA3 onPC on this Mac. A build deletes groups, sequences and
MAtricks in the reserved ranges (default 501–599) and re-assigns executors 1.101–1.199. Save your real show first,
or load a copy.

Without a console, the tests in `src/test/java/automa3/show` cover the same path: `ShowServiceTest` sends the job
over OSC to a fake console that runs the real ShowBuilder plugin in Lua against a console mock (needs `lua`,
`brew install lua`), and `ParityTest` compares everything with the Python version this replaced.

## Once: import the plugin
1. Show tab → **Read patch from grandMA3** (or `show deploy inspect`, below) copies `ShowBuilder.xml/.lua` into
   `~/MALightingTechnology/gma3_library/datapools/plugins/`.
2. In onPC, import the plugin once: open a Plugins pool, edit an empty slot, use the import function and pick
   `ShowBuilder.xml` (command line, assumed syntax: `Import Plugin Library "ShowBuilder.xml" At Plugin 1`).
   The plugin has one Lua component; it must not be red (red = syntax error).
3. Preset pool 21 must hold the predefined phasers (macro "Import Predefined Phaser" if not).
4. grandMA3 In & Out → OSC: "Receive Command" on the port of the console in AutoMA3's Consoles tab (8000), so
   AutoMA3 can start the plugin. Without it, paste the command AutoMA3 shows.

## In the Show tab
1. **Read patch from grandMA3**: the log shows "Done in grandMA3: 1/1 steps", the rig table lists the fixtures.
2. **Look list**, then **Run planner** (needs Claude Code), until the check shows 0 errors.
3. **Build**: the log counts the steps; at the end "Done in grandMA3: N/N steps". Failed steps are listed with
   grandMA3's answer. The plugin's log and the exported groups/sequences are kept in the show's `build/ma3/`.
4. **Use in AutoMA3…**: the Looks tab's import preview; import, then Save changes.
5. Build a second time: the result must be identical, nothing outside the ranges changes.

## Console tests from the terminal (developers)
`show deploy <kind>` writes a job and the plugin and prints the command to paste into grandMA3; `show log <kind>`
reads the result. `<kind>`: `inspect` (changes nothing: paths, command feedback texts, what is in the ranges, preset
pool 21, the patch), `probe` (phaser-in-cue experiments at the end of the ranges: sequences end-2/end-1/end with
At Phase, MAtricks phase and a cue recipe, exported as XML), `patch`, `build`.

```bash
java -jar target/auto-ma3.jar show deploy inspect
java -jar target/auto-ma3.jar show log inspect
```

The show is the one selected in the Show tab (`--show NAME` for another one). Report back if something in onPC
looked wrong: an error in the command line history, a pop-up, or the plugin component being red.
