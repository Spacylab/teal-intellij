-- THROWAWAY: live heap and per-edit time on a reused env, to pick N for
-- "rebuild the env after N edits".
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
   tl.check(tl.parse_program(tl.lex(s, path), perrs, path), path, { feat_lax = "off", feat_arity = "on" }, e)
end
collectgarbage("collect"); local m0 = collectgarbage("count")
local env = new_env()
local t0, last = os.clock(), 0
for k = 1, 400 do
   check(src .. "\n-- " .. k .. "\n", env)
   if k == 1 or k == 50 or k == 100 or k == 200 or k == 400 then
      local now = os.clock()
      collectgarbage("collect")
      print(string.format("edit %3d: live heap +%6.0f KB, %.1f ms/edit since last mark",
         k, collectgarbage("count") - m0, (now - t0) * 1000 / (k - last)))
      t0, last = os.clock(), k
   end
end
