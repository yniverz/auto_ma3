-- ShowBuilder: the generic grandMA3 plugin of MA3 Show Creator.
--
-- It contains no show logic. It loads a job file (a Lua data table written by the
-- Python compiler), runs its steps and writes one JSON line per step to a log file
-- next to the job, which the tooling reads back.
--
-- Usage (command line):  Plugin "ShowBuilder" "/full/path/to/job_build.lua"
-- Without an argument it looks for <library>/datapools/plugins/showcreator/job.lua.

local PLUGIN_VERSION = "0.2.3"

---------------------------------------------------------------- JSON (log output)
local function json_str(s)
  s = s:gsub('[%c"\\]', function(c)
    if c == '"' then return '\\"' end
    if c == "\\" then return "\\\\" end
    if c == "\n" then return "\\n" end
    if c == "\r" then return "\\r" end
    if c == "\t" then return "\\t" end
    return string.format("\\u%04x", c:byte())
  end)
  return '"' .. s .. '"'
end

local function json(v)
  local t = type(v)
  if v == nil then return "null" end
  if t == "boolean" then return v and "true" or "false" end
  if t == "number" then
    if v ~= v or v == math.huge or v == -math.huge then return "null" end
    return tostring(v)
  end
  if t == "string" then return json_str(v) end
  if t == "table" then
    local n = #v
    if n > 0 then
      local parts = {}
      for i = 1, n do parts[i] = json(v[i]) end
      return "[" .. table.concat(parts, ",") .. "]"
    end
    local parts = {}
    for k, x in pairs(v) do parts[#parts + 1] = json_str(tostring(k)) .. ":" .. json(x) end
    table.sort(parts)
    return "{" .. table.concat(parts, ",") .. "}"
  end
  return json_str(tostring(v))
end

---------------------------------------------------------------- helpers
local function sep()
  local ok, s = pcall(GetPathSeparator)
  if ok and type(s) == "string" and s ~= "" then return s end
  return "/"
end

local function dirname(p)
  return p:match("^(.*)[/\\][^/\\]*$") or "."
end

local function default_job_path()
  -- CustomPluginLibrary = <library>/datapools/plugins (observed on onPC 2.3.2)
  local ok, dir = pcall(GetPath, Enums.PathType.CustomPluginLibrary)
  if not ok or type(dir) ~= "string" or dir == "" then return nil end
  return dir .. sep() .. "showcreator" .. sep() .. "job.lua"
end

local function major_minor(v)
  return type(v) == "string" and v:match("^(%d+%.%d+)") or nil
end

local function cmd_ok(res)
  return type(res) == "string" and res:upper():sub(1, 2) == "OK"
end

local function describe(h)
  local d = {}
  pcall(function() d.name = h.name end)
  pcall(function() d.addr = h:Addr() end)
  pcall(function() d.class = h:GetClass() end)
  return d
end

---------------------------------------------------------------- step ops
local ops = {}

function ops.cmd(step)
  local ok, res = pcall(Cmd, step.cmd)
  if not ok then return false, { error = tostring(res) } end
  return cmd_ok(res), { result = tostring(res) }
end

function ops.list(step)
  local ok, list = pcall(ObjectList, step.addr)
  if not ok then return false, { error = tostring(list) } end
  local objects = {}
  for i, h in ipairs(list or {}) do objects[i] = describe(h) end
  return true, { count = #objects, objects = objects }
end

function ops.export(step, ctx)
  local ok, list = pcall(ObjectList, step.addr)
  if not ok then return false, { error = tostring(list) } end
  local h = list and list[1]
  if not h then return false, { error = "object not found" } end
  local file = ctx.export_prefix .. step.file
  local ok2, res = pcall(function() return h:Export(ctx.base, file) end)
  if not ok2 then return false, { error = tostring(res) } end
  return res ~= false, { file = file, result = tostring(res) }
end

function ops.get(step)
  local ok, list = pcall(ObjectList, step.addr)
  if not ok then return false, { error = tostring(list) } end
  local h = list and list[1]
  if not h then return false, { error = "object not found" } end
  local values = {}
  for _, prop in ipairs(step.props or {}) do
    local ok1, raw = pcall(function() return h:Get(prop) end)
    local ok2, text = pcall(function() return h:Get(prop, Enums.Roles.Edit) end)
    values[prop] = { raw = ok1 and tostring(raw) or ("error: " .. tostring(raw)),
                     text = ok2 and tostring(text) or ("error: " .. tostring(text)) }
  end
  return true, { values = values }
end

-- Attribute names of a DMX mode, read from the object tree (works for protected fixture types too,
-- whose XML export is encrypted). Tree as in exported XML: DMXMode > DMXChannels > DMXChannel > LogicalChannel
-- > ChannelFunction. Returns the logical channels' attributes and their channel functions' attributes
-- (sub-attributes such as "Shutter1Strobe", which can be set directly in their physical unit).
local function attribute_of(h)
  local name
  pcall(function() name = h:Get("Attribute", Enums.Roles.Edit) end)
  if name == nil or name == "" then pcall(function() name = tostring(h:Get("Attribute")) end) end
  if name == "nil" then name = nil end
  return name
end

local function mode_attributes(mode)
  local seen, out, fseen, functions = {}, {}, {}, {}
  local function walk(h, depth)
    if depth > 4 then return end
    local cls
    pcall(function() cls = h:GetClass() end)
    if cls == "LogicalChannel" then
      local name = attribute_of(h)
      if name and not seen[name] then seen[name] = true; out[#out + 1] = name end
      local ok, kids = pcall(function() return h:Children() end)
      if ok and kids then
        for _, k in ipairs(kids) do
          local kcls
          pcall(function() kcls = k:GetClass() end)
          if kcls == "ChannelFunction" then
            local f = attribute_of(k)
            if f and f ~= "NoFeature" and not fseen[f] then fseen[f] = true; functions[#functions + 1] = f end
          end
        end
      end
      return
    end
    local ok, kids = pcall(function() return h:Children() end)
    if ok and kids then for _, k in ipairs(kids) do walk(k, depth + 1) end end
  end
  walk(mode, 0)
  return out, functions
end

-- Fixture types (exported as XML when used) and every fixture with its type, mode and requested properties.
-- Object paths as used by MA's own UI code (lib_menus/ui/popups/dmx_mode_popup.lua).
function ops.patch(step, ctx)
  local types, fixtures = {}, {}
  local okt, terr = pcall(function()
    for i, ft in ipairs(Patch().FixtureTypes:Children()) do
      local t = { index = i }
      pcall(function() t.name = ft.name end)
      local used, modes = 0, {}
      pcall(function()
        for _, m in ipairs(ft.DmxModes:Children()) do
          local u = tonumber(m.Used) or 0
          used = used + u
          if u > 0 then
            local attrs, functions = mode_attributes(m)
            modes[#modes + 1] = { name = m.name, used = u, attributes = attrs, functions = functions }
          end
        end
      end)
      t.used, t.modes = used, modes
      if used > 0 then
        local file = ctx.export_prefix .. "fixturetype_" .. i .. ".xml"
        local oke, res = pcall(function() return ft:Export(ctx.base, file) end)
        t.file, t.exported = file, oke and res ~= false
      end
      types[#types + 1] = t
    end
  end)
  local okl, list = pcall(ObjectList, step.fixtures or "Fixture 1 Thru 9999")
  if okl then
    for _, h in ipairs(list or {}) do
      local d = describe(h)
      for _, prop in ipairs(step.props or {}) do
        local ok, v = pcall(function() return h:Get(prop) end)
        if ok and v ~= nil then d[prop] = tostring(v) end
      end
      pcall(function()
        local mode = h.ModeDirect
        if mode then
          d.mode = mode.name
          d.fixturetype = mode:FindParent("FixtureType").name
        end
      end)
      fixtures[#fixtures + 1] = d
    end
  end
  return okt and okl, { types = types, fixtures = fixtures,
                        error = (not okt and tostring(terr)) or (not okl and tostring(list)) or nil }
end

-- A strobe in Hz on every selected fixture: the first of step.functions (channel function attributes such as
-- "Shutter1Strobe") that the fixture's own type has, set in the programmer with the function chosen explicitly.
-- The command line cannot do this: `Attribute "Shutter1Strobe" At 12` fails and `Attribute "Shutter1" At 12` is
-- 12 % of whichever function the channel is in (observed on onPC 2.3.2). The value is a percent of the function's
-- physical range (PhysicalFrom..PhysicalTo, in Hz for a strobe), read from the fixture type.
local function physical(h, prop)
  local ok, v = pcall(function() return h:Get(prop, Enums.Roles.Edit) end)
  return ok and tonumber(v) or nil
end

function ops.strobe(step)
  local rank = {}
  for i, f in ipairs(step.functions or {}) do rank[f] = i end
  local hz = tonumber(step.hz) or 0
  local set, without = {}, 0
  local ok, err = pcall(function()
    local sfi = SelectionFirst()
    while sfi do
      local best
      for _, ui in ipairs(GetUIChannels(sfi) or {}) do
        local ch = GetUIChannel(ui)
        local lc = ch and ch.logical_channel
        if lc then
          for ci, cf in ipairs(lc:Children()) do
            local a = attribute_of(cf)
            if a and rank[a] and (not best or rank[a] < best.rank) then
              best = { ui = ui, index = ci - 1, cf = cf, rank = rank[a], attribute = a }
            end
          end
        end
      end
      if best then
        local from, to = physical(best.cf, "PhysicalFrom"), physical(best.cf, "PhysicalTo")
        local pct = 100
        if from and to and to > from then pct = math.max(0, math.min(100, (hz - from) / (to - from) * 100)) end
        SetProgPhaser(best.ui, {})   -- creates the channel's programmer value; SetProgPhaserValue needs one
        SetProgPhaserValue(best.ui, 0, { channel_function = best.index, absolute = pct })
        set[#set + 1] = { subfixture = sfi, ["function"] = best.attribute, channel_function = best.index,
                          hz = (from and to and to > from) and (from + pct / 100 * (to - from)) or nil }
      else
        without = without + 1
      end
      sfi = SelectionNext(sfi)
    end
  end)
  if not ok then return false, { error = tostring(err) } end
  if #set == 0 then return false, { error = "no selected fixture has one of the strobe functions" } end
  return true, { set = set, without = without }
end

function ops.paths()
  local paths = {}
  local ok, err = pcall(function()
    for k, v in pairs(Enums.PathType) do
      local ok2, p = pcall(GetPath, v)
      paths[tostring(k)] = ok2 and tostring(p) or ("error: " .. tostring(p))
    end
  end)
  return ok, { paths = paths, separator = sep(), error = (not ok) and tostring(err) or nil }
end

---------------------------------------------------------------- main
local function run(job_path)
  local chunk, lerr = loadfile(job_path, "t", {})   -- empty environment: the job is pure data
  if not chunk then
    ErrPrintf("ShowBuilder: cannot load job %s: %s", tostring(job_path), tostring(lerr))
    return
  end
  local okj, job = pcall(chunk)
  if not okj or type(job) ~= "table" or job.format ~= "ma3sc-job" then
    ErrPrintf("ShowBuilder: %s is not a ShowBuilder job (%s)", job_path, tostring(okj and "wrong format" or job))
    return
  end

  local base = dirname(job_path)
  local log_path = base .. sep() .. (job.log_file or "log.jsonl")
  local log, ferr = io.open(log_path, "w")
  if not log then
    ErrPrintf("ShowBuilder: cannot write log %s: %s", log_path, tostring(ferr))
    return
  end
  local function emit(rec)
    log:write(json(rec), "\n")
    log:flush()
  end

  local version = Version()
  emit({ ev = "start", job_id = job.job_id, kind = job.kind, plugin = PLUGIN_VERSION, ma3 = version,
         lua = _VERSION, job_path = job_path, steps = #job.steps, time = os.date("!%Y-%m-%dT%H:%M:%SZ") })
  Printf("ShowBuilder %s: job %s (%d steps) on grandMA3 %s", PLUGIN_VERSION, tostring(job.job_id), #job.steps, version)

  if job.strict_version and major_minor(version) ~= major_minor(job.ma3_version) then
    local msg = string.format("job was compiled for grandMA3 %s but this is %s; set ma3.version in config.toml and recompile",
                              tostring(job.ma3_version), tostring(version))
    emit({ ev = "end", ok = false, aborted = true, error = msg })
    log:close()
    ErrPrintf("ShowBuilder: %s", msg)
    return
  end

  local ctx = { base = base, export_prefix = job.export_prefix or "export_" }
  local failed, aborted, done = 0, false, 0
  for i, step in ipairs(job.steps) do
    -- written before the step runs: if the Lua engine dies (not catchable by pcall), the log shows where
    emit({ ev = "begin", i = i, op = step.op, cmd = step.cmd, addr = step.addr })
    local op = ops[step.op]
    local ok, info
    if op then
      ok, info = op(step, ctx)
    else
      ok, info = false, { error = "unknown op " .. tostring(step.op) }
    end
    local rec = { ev = "step", i = i, op = step.op, key = step.key, cmd = step.cmd, addr = step.addr, ok = ok }
    for k, v in pairs(info or {}) do rec[k] = v end
    if not ok and step.allow_fail then rec.tolerated = true end
    emit(rec)
    done = i
    if not ok and not step.allow_fail then
      failed = failed + 1
      if step.fatal then
        aborted = true
        ErrPrintf("ShowBuilder: step %d failed (%s), aborting", i, tostring(step.cmd or step.op))
        break
      end
    end
  end

  emit({ ev = "end", ok = (failed == 0), aborted = aborted, failed = failed, done = done, steps = #job.steps,
         time = os.date("!%Y-%m-%dT%H:%M:%SZ") })
  log:close()
  if aborted then
    ErrPrintf("ShowBuilder: aborted after %d/%d steps. Log: %s", done, #job.steps, log_path)
  else
    Printf("ShowBuilder: done, %d/%d steps, %d failed. Log: %s", done, #job.steps, failed, log_path)
  end
end

return function(display_handle, argument)
  local job_path = argument
  if type(job_path) ~= "string" or job_path == "" then job_path = default_job_path() end
  if not job_path then
    ErrPrintf('ShowBuilder: no job given. Use: Plugin "ShowBuilder" "/path/to/job.lua"')
    return
  end
  local ok, err = pcall(run, job_path)
  if not ok then ErrPrintf("ShowBuilder: internal error: %s", tostring(err)) end
end
