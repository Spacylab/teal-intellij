-- Text-level logic for requires: the missing-require quick fix (an
-- `unknown type X` diagnostic -> add a bare require of each workspace module
-- declaring a global X) and require targets (a require's module name -> its
-- workspace file). Line scans, not parsing, as in tickets 04 and 05.

local requires = {}

local SEP = package.config:sub(1, 1)
local WINDOWS = SEP == "\\"

-- `require("x")`, `require "x"`, `require('x')`; captures the module name.
local REQUIRE_CALL = "%f[%w_]require%s*%(?%s*[\"']([^\"']+)[\"']"

-- Naive: a "--" inside a string also cuts the line, which at worst hides a
-- require on that line.
local function strip_comment(line)
   local at = line:find("--", 1, true)
   return at and line:sub(1, at - 1) or line
end

local function lines(text)
   local out = {}
   for line in (text .. "\n"):gmatch("([^\n]*)\n") do out[#out + 1] = line end
   return out
end

-- The type name in a typechecker `unknown type X` message, or nil.
function requires.unknown_type_name(message)
   return message:match("^%s*unknown type ([%a_][%w_]*)%s*$")
end

-- Module names passed to uncommented requires anywhere in text.
function requires.required_modules(text)
   local found = {}
   for _, line in ipairs(lines(text)) do
      for module in strip_comment(line):gmatch(REQUIRE_CALL) do found[module] = true end
   end
   return found
end

-- The 0-based line for a new require: right after the last require of the
-- file's leading block of requires (blank and comment lines may be
-- interleaved), or 0 if the file doesn't start with any.
function requires.insertion_line(text)
   local last = -1
   for index, raw in ipairs(lines(text)) do
      local line = strip_comment(raw):match("^%s*(.-)%s*$")
      if line ~= "" then
         if line:find(REQUIRE_CALL) then last = index - 1 else break end
      end
   end
   return last + 1
end

function requires.statement(module)
   return 'require("' .. module .. '")'
end

local function shell_quote(s)
   if WINDOWS then return '"' .. s .. '"' end
   return "'" .. s:gsub("'", "'\\''") .. "'"
end

-- Every .tl file under root, skipping hidden directories. Plain Lua can't
-- list directories, so this asks the OS.
local function tl_files(root)
   local cmd
   if WINDOWS then
      cmd = "dir /s /b /a-d " .. shell_quote(root .. "\\*.tl") .. " 2>nul"
   else
      cmd = "find " .. shell_quote(root) .. " -mindepth 1 -name '.*' -prune -o -type f -name '*.tl' -print 2>/dev/null"
   end
   local files = {}
   local pipe = io.popen(cmd)
   if not pipe then return files end
   for path in pipe:lines() do
      local rel = path:sub(#root + 2)
      if not (WINDOWS and ("\\" .. rel):find("\\%.")) then files[#files + 1] = path end
   end
   pipe:close()
   table.sort(files)
   return files
end

local function declares_global(path, name)
   local f = io.open(path, "r")
   if not f then return false end
   for line in f:lines() do
      for _, kind in ipairs({ "record", "enum", "interface", "type" }) do
         if line:find("^%s*global%s+" .. kind .. "%s+" .. name .. "%f[^%w_]") then
            f:close()
            return true
         end
      end
   end
   f:close()
   return false
end

-- Workspace .tl files with a `global record|enum|interface|type <name>`
-- declaration, excluding exclude_path. Scanned on demand; there's no index.
function requires.find_global_declarations(root, name, exclude_path)
   local found = {}
   for _, path in ipairs(tl_files(root)) do
      if path ~= exclude_path and declares_global(path, name) then found[#found + 1] = path end
   end
   return found
end

-- `<root>/src/entities/player.tl` -> `src.entities.player`.
function requires.module_name(root, path)
   local rel = path:sub(#root + 2)
   rel = rel:gsub("%.d%.tl$", ""):gsub("%.tl$", "")
   return (rel:gsub("[/\\]", "."))
end

-- The module name of a require's string token, or nil if tokens[i] is not the
-- argument of a require (`require("x")` or `require "x"`).
function requires.module_at(tokens, i)
   local tk = tokens[i]
   if not tk or tk.kind ~= "string" then return nil end
   local prev = tokens[i - 1]
   if prev and prev.tk == "(" then prev = tokens[i - 2] end
   if not prev or prev.tk ~= "require" then return nil end
   return tk.tk:match("^[\"'](.*)[\"']$")
end

-- The workspace file a module name resolves to: the first existing of
-- <path>.tl, <path>.d.tl, <path>/init.tl, <path>.lua under root.
function requires.resolve(root, module)
   local base = root .. SEP .. module:gsub("%.", SEP)
   for _, suffix in ipairs({ ".tl", ".d.tl", SEP .. "init.tl", ".lua" }) do
      local f = io.open(base .. suffix, "r")
      if f then
         f:close()
         return base .. suffix
      end
   end
   return nil
end

return requires
