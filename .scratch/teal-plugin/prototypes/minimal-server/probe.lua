-- THROWAWAY probe for the ADR 0001 third amendment (minimal Lua server on tl).
-- Usage: lua probe.lua <project_root> <file.tl> [<file.tl> ...]
-- Answers: does tl load as a library here, how long does a check take (cold,
-- warm, repeated like keystrokes), and can references be answered in-process.

local clock = os.clock
local function ms(t0) return string.format("%7.1f ms", (clock() - t0) * 1000) end

local t0 = clock()
local ok, tl = pcall(require, "tl")
if not ok then
   print("FAIL require('tl') under " .. _VERSION .. ": " .. tostring(tl))
   os.exit(1)
end
print(string.format("%s, tl %s, loaded in %s", _VERSION, tl.version and tl.version() or "?", ms(t0)))

local root = assert(arg[1], "project root")
local cfg = {}
local cfg_chunk = loadfile(root .. "/tlconfig.lua")
if cfg_chunk then cfg = cfg_chunk() end
if cfg.source_dir then
   package.path = root .. "/" .. cfg.source_dir .. "/?.lua;" .. package.path
end
package.path = root .. "/?.lua;" .. package.path

local function new_env()
   local env = tl.new_env({ defaults = {}, predefined_modules = { cfg.global_env_def } })
   env.report_types = true
   tl.check_string("", env, "bootstrap.tl")
   return env
end

t0 = clock()
local env = new_env()
print("env bootstrap (global_env_def=" .. tostring(cfg.global_env_def) .. "): " .. ms(t0))

local function read(path)
   local f = assert(io.open(path)); local s = f:read("a"); f:close(); return s
end

local function check(src, path, e)
   local tks = tl.lex(src, path)
   local perrs = {}
   local ast = tl.parse_program(tks, perrs, path)
   local result = tl.check(ast, path, { feat_lax = "off", feat_arity = "on" }, e)
   return tks, result, perrs
end

-- Same algorithm as teal-language-server's Document:symbol_declaration_position.
local function declaration_of(symbols, name, y, x)
   local n = 0
   for i = 1, #symbols do
      local s = symbols[i]
      if s[1] < y or (s[1] == y and s[2] <= x) then n = i else break end
   end
   while n >= 1 do
      local s = symbols[n]
      if s[3] == "@{" then n = n - 1
      elseif s[3] == "@}" then n = s[4]
      elseif s[3] == name then return s[1], s[2]
      else n = n - 1 end
   end
end

for i = 2, #arg do
   local path = arg[i]
   local src = read(path)
   local lines = select(2, src:gsub("\n", "")) + 1
   print(string.format("\n== %s (%d lines)", path, lines))

   local fresh = new_env()
   t0 = clock()
   local tks, result, perrs = check(src, path, fresh)
   print(string.format("  cold check (own env):   %s  errors=%d parse_errors=%d warnings=%d",
      ms(t0), #result.type_errors, #perrs, #result.warnings))

   t0 = clock()
   check(src, path, fresh)
   print("  warm re-check:          " .. ms(t0))

   -- 20 "keystrokes": a trailing comment change each time, same env reused.
   collectgarbage("collect")
   local mem0 = collectgarbage("count")
   t0 = clock()
   for k = 1, 20 do check(src .. "\n-- edit " .. k .. "\n", path, fresh) end
   local per = (clock() - t0) * 1000 / 20
   collectgarbage("collect")
   print(string.format("  20 edits:               %7.1f ms avg, heap +%.0f KB after GC",
      per, collectgarbage("count") - mem0))

   t0 = clock()
   local tr = fresh.reporter:get_report()
   print("  type report:            " .. ms(t0))

   -- References feasibility: resolve every identifier token to its declaration
   -- in-process (what the proxy does with one definition RPC per token).
   local symbols = tr.symbols_by_file and tr.symbols_by_file[path]
   if not symbols then
      print("  symbols_by_file: MISSING for this path -- keys are:")
      for k in pairs(tr.symbols_by_file or {}) do print("    " .. k) end
   else
      t0 = clock()
      local idents, resolved, by_decl = 0, 0, {}
      for _, tk in ipairs(tks) do
         if tk.kind == "identifier" then
            idents = idents + 1
            local dy, dx = declaration_of(symbols, tk.tk, tk.y, tk.x)
            if dy then
               resolved = resolved + 1
               local key = tk.tk .. "@" .. dy .. ":" .. dx
               by_decl[key] = (by_decl[key] or 0) + 1
            end
         end
      end
      local top, top_n = nil, 0
      for k, n in pairs(by_decl) do if n > top_n then top, top_n = k, n end end
      print(string.format("  resolve all identifiers: %s  %d idents, %d resolved to a local decl; most-used: %s (%d refs)",
         ms(t0), idents, resolved, tostring(top), top_n))
   end
end
