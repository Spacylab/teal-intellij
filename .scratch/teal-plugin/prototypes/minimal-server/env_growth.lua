-- THROWAWAY: does reusing one tl env across re-checks accumulate state, and
-- does a fresh env per check (or per N checks) avoid it?
local tl = require("tl")
local root, path = arg[1], arg[2]
package.path = root .. "/src/?.lua;" .. root .. "/?.lua;" .. package.path
local f = assert(io.open(path)); local src = f:read("a"); f:close()

local function new_env()
   local env = tl.new_env({ defaults = {}, predefined_modules = { "love" } })
   env.report_types = true
   tl.check_string("", env, "bootstrap.tl")
   return env
end
local function check(s, e)
   local perrs = {}
   local ast = tl.parse_program(tl.lex(s, path), perrs, path)
   return tl.check(ast, path, { feat_lax = "off", feat_arity = "on" }, e)
end
local function nsyms(e) local r = e.reporter:get_report(); return #(r.symbols_by_file[path] or {}), #r.types end

for _, mode in ipairs({ "reuse", "fresh" }) do
   collectgarbage("collect"); local m0 = collectgarbage("count")
   local env = new_env()
   local t0 = os.clock()
   for k = 1, 50 do
      if mode == "fresh" then env = new_env() end
      check(src .. "\n-- " .. k .. "\n", env)
      if k == 1 or k == 50 then
         local s, t = nsyms(env)
         print(string.format("%-5s edit %2d: symbols=%d types=%d", mode, k, s, t))
      end
   end
   local per = (os.clock() - t0) * 1000 / 50
   env = nil; collectgarbage("collect")
   print(string.format("%-5s: %.1f ms/edit, heap +%.0f KB after 50 edits (env dropped)", mode, per, collectgarbage("count") - m0))
end

-- Sanity: a real type error surfaces with a usable position.
local env = new_env()
local r = check(src .. "\nlocal broken: integer = \"nope\"\n", env)
for _, e in ipairs(r.type_errors) do print(string.format("error %d:%d %s", e.y, e.x, e.msg)) end
