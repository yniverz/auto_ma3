-- Minimal stand-in for the grandMA3 Lua API, to run ShowBuilder.lua outside the console.
-- usage: lua mock_ma3.lua <ShowBuilder.lua> <job.lua>
-- env: MOCK_MA3_VERSION (default 2.3.2.0), MOCK_FAIL (substring: Cmd returns "Syntax Error")
local plugin_path, job_path = ...

Enums = { PathType = { Library = 1, Showfiles = 2, CustomPluginLibrary = 3 }, Roles = { Edit = 2 } }
function GetPath(i) return "/mock/gma3_library" end
function GetPathSeparator() return "/" end
function Version() return os.getenv("MOCK_MA3_VERSION") or "2.3.2.0" end
function Printf(fmt, ...) end
function ErrPrintf(fmt, ...) io.stderr:write(string.format(fmt, ...), "\n") end

local fail = os.getenv("MOCK_FAIL")
function Cmd(c)
  if c:find("ShowCreatorSyntaxProbe", 1, true) or (fail and fail ~= "" and c:find(fail, 1, true)) then
    return "Syntax Error"
  end
  return "OK"
end

local Obj = {}
Obj.__index = Obj
function Obj:Addr() return "13.13.1." .. self.no end
function Obj:GetClass() return "MockObject" end
function Obj:Get(prop, role) return role and ("text " .. prop) or prop end
function Obj:Export(dir, file)
  local f = assert(io.open(dir .. "/" .. file, "w"))
  f:write("<GMA3><Mock Name=\"", self.name, "\"/></GMA3>")
  f:close()
  return true
end
function ObjectList(addr)
  local o = setmetatable({ name = "Mock " .. addr, no = 1 }, Obj)
  o.ModeDirect = setmetatable({ name = "Mode 1", no = 1 }, Obj)
  return { o }
end

if os.getenv("MOCK_CRASH") then
  local crash = os.getenv("MOCK_CRASH")
  local orig = ObjectList
  ObjectList = function(addr)
    if addr:find(crash, 1, true) then os.exit(3) end  -- simulates an uncatchable engine exception
    return orig(addr)
  end
end

local FT = setmetatable({ name = "Mock Spot", no = 1 }, Obj)
local function node(cls, props, kids)
  return { GetClass = function() return cls end, Children = function() return kids or {} end,
           Get = function(_, p) return props and props[p] end }
end
local MODE = node("DMXMode", nil, { node("DMXChannels", nil, {
  node("DMXChannel", nil, { node("LogicalChannel", { Attribute = "Pan" }) }),
  node("DMXChannel", nil, { node("LogicalChannel", { Attribute = "Tilt" }) }),
  node("DMXChannel", nil, { node("LogicalChannel", { Attribute = "Shutter1" }, {
    node("ChannelFunction", { Attribute = "Shutter1" }), node("ChannelFunction", { Attribute = "Shutter1Strobe" }),
    node("ChannelFunction", { Attribute = "NoFeature" }) }) }),
  node("DMXChannel", nil, { node("LogicalChannel", { Attribute = "Pan" }) }) }) })
MODE.Used, MODE.name = 2, "Mode 1"
FT.DmxModes = { Children = function() return { MODE, { Used = 0, name = "unused" } } end }
function Patch()
  return { FixtureTypes = { Children = function() return { FT } end } }
end
function Obj:FindParent(cls) return FT end

-- the programmer: subfixtures 30 (a spot with a shutter strobe, 0.1-10 Hz) and 31 (a par without) are selected
local STROBE_LC = node("LogicalChannel", { Attribute = "Shutter1" }, {
  node("ChannelFunction", { Attribute = "Shutter1", PhysicalFrom = "   1.0000000000", PhysicalTo = "   1.0000000000" }),
  node("ChannelFunction", { Attribute = "Shutter1Strobe", PhysicalFrom = "   0.1000000000", PhysicalTo = "  10.0000000000" }),
  node("ChannelFunction", { Attribute = "Shutter1StrobeRandom", PhysicalFrom = "  10.0000000000", PhysicalTo = "  10.0000000000" }) })
local UI = { [1] = { logical_channel = node("LogicalChannel", { Attribute = "Dimmer" }, {
               node("ChannelFunction", { Attribute = "Dimmer" }) }) },
             [2] = { logical_channel = STROBE_LC },
             [3] = { logical_channel = node("LogicalChannel", { Attribute = "Dimmer" }, {
               node("ChannelFunction", { Attribute = "Dimmer" }) }) } }
local CHANNELS = { [30] = { 1, 2 }, [31] = { 3 } }
local NEXT = { [30] = 31 }
function SelectionFirst() return 30 end
function SelectionNext(i) return NEXT[i] end
function GetUIChannels(i) return CHANNELS[i] end
function GetUIChannel(ui) return UI[ui] end
local created = {}
function SetProgPhaser(ui, t) created[ui] = true end
function SetProgPhaserValue(ui, step, t)
  assert(created[ui], "SetProgPhaserValue without a programmer value") -- as on the console: it does nothing then
  assert(step == 0)
end

local main = dofile(plugin_path)
main(nil, job_path)
