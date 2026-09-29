-- Unit tests for requires.lua, run by TealServerLuaTest as
-- `lua requires_test.lua <server scripts dir> <empty temp dir>`.
-- Exits non-zero on the first failure, printing which check failed.

local scripts, root = arg[1], arg[2]
package.path = scripts .. "/?.lua;" .. package.path
local requires = require("requires")

local SEP = package.config:sub(1, 1)

local function check(name, cond, detail)
   if not cond then
      io.stderr:write("FAIL: ", name, detail and (" -- " .. detail) or "", "\n")
      os.exit(1)
   end
   print("ok: " .. name)
end

local function eq(name, expected, actual)
   check(name, expected == actual, "expected " .. tostring(expected) .. ", got " .. tostring(actual))
end

local function write(path, text)
   local full = root .. SEP .. path:gsub("/", SEP)
   local dir = full:match("^(.*)[/\\]")
   os.execute((SEP == "\\" and 'mkdir "' .. dir .. '" 2>nul' or "mkdir -p '" .. dir .. "'"))
   local f = assert(io.open(full, "w"))
   f:write(text)
   f:close()
   return full
end

local function modules(paths)
   local out = {}
   for k, p in ipairs(paths) do out[k] = requires.module_name(root, p) end
   return table.concat(out, ",")
end

-- unknown_type_name
eq("extracts the type name", "Player", requires.unknown_type_name("unknown type Player"))
eq("ignores unknown variables", nil, requires.unknown_type_name("unknown variable Player"))
eq("ignores qualified names", nil, requires.unknown_type_name("unknown type a.B"))

-- find_global_declarations
write("src/entities/player.tl", "global record Player\n  name: string\nend\n")
write("src/entities/kinds.tl", "global enum Player\n  \"a\"\nend\n")
write("src/local_player.tl", "local record Player\nend\nreturn Player\n")
write(".scratch/player.tl", "global record Player\nend\n")
write("src/commented.tl", "-- global record Player\n")
write("src/stats.tl", "global record PlayerStats\nend\n")
local current = write("src/engine/run.tl", "global record Player\nend\n")
eq("finds globals of any kind, skipping locals, hidden dirs, comments, longer names and the current file",
   "src.entities.kinds,src.entities.player",
   modules(requires.find_global_declarations(root, "Player", current)))

-- module_name
eq("drops the whole .d.tl suffix", "types.love", requires.module_name(root, write("types/love.d.tl", "")))

-- required_modules
local required = requires.required_modules([[
require("src.engine.run_types")
-- require("src.entities.player")
local M = require 'src.data.decks'
local a, b = require("x.a"), require("x.b")
]])
check("finds required modules in every form",
   required["src.engine.run_types"] and required["src.data.decks"] and required["x.a"] and required["x.b"])
check("ignores commented-out requires", not required["src.entities.player"])

-- insertion_line
eq("inserts after the last leading require, across blank and comment lines", 5, requires.insertion_line([[
-- header
require("a")
-- require("b")

local C = require("c")

local M = {}
local late = require("late")]]))
eq("inserts at line 0 when the file starts with no requires", 0,
   requires.insertion_line("-- header\nlocal M = {}\nreturn M\n"))

-- module_at: a require argument, quotes included; not other strings
local tl = require("tl")
local function module_at(line, col)
   local tokens = tl.lex(line, "x.tl")
   for i, tk in ipairs(tokens) do
      if tk.x <= col and col < tk.x + #tk.tk then return requires.module_at(tokens, i) end
   end
end
local line = [[local a, b = require("x.a"), require("x.b")]]
eq("picks the require under the cursor", "x.b", module_at(line, line:find("x.b", 1, true)))
eq("covers the opening quote", "x.a", module_at(line, line:find('"', 1, true)))
eq("not the require keyword", nil, module_at(line, line:find("require", 1, true)))
eq("the no-parens form", "a.b", module_at([[require "a.b"]], 10))
eq("single quotes", "a.b", module_at([[require('a.b')]], 10))
eq("not a string passed to anything else", nil, module_at([[print("src.engine.run")]], 10))

-- resolve: .tl, .d.tl, /init.tl, .lua, in that order
write("vendor/json.lua", "")
eq("falls back to a lua file", root .. SEP .. "vendor" .. SEP .. "json.lua", requires.resolve(root, "vendor.json"))
write("a/b/init.tl", "")
write("a/b.lua", "")
eq("init.tl before .lua", root .. SEP .. "a" .. SEP .. "b" .. SEP .. "init.tl", requires.resolve(root, "a.b"))
write("a/b.d.tl", "")
eq(".d.tl before init.tl", root .. SEP .. "a" .. SEP .. "b.d.tl", requires.resolve(root, "a.b"))
write("a/b.tl", "")
eq(".tl first", root .. SEP .. "a" .. SEP .. "b.tl", requires.resolve(root, "a.b"))
eq("nothing outside the workspace", nil, requires.resolve(root, "string"))
