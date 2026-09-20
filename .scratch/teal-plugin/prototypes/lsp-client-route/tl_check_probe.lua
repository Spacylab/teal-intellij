local tl = require("tl")

local fname = "/Users/hedi/Documents/Github/picolo-rpg/src/conf.tl"
local src = [[
function love.conf(t: love.Configuration)
  t.window.resizable = "not a boolean"
end
]]

local env = tl.new_env({ defaults = {}, predefined_modules = { "love" } })
env.report_types = true
tl.check_string("", env, "bootstrap.tl")

local tks, err_tks = tl.lex(src, fname)
print("lex errors:", #err_tks)

local parse_errors = {}
local ast = tl.parse_program(tks, parse_errors, fname)
print("parse errors:", #parse_errors)

local result = tl.check(ast, fname, { feat_lax = "off", feat_arity = "on" }, env)
print("type_errors count:", #result.type_errors)
for i, e in ipairs(result.type_errors) do
  print(i, "filename=" .. tostring(e.filename), "msg=" .. tostring(e.msg), "y=" .. tostring(e.y), "x=" .. tostring(e.x))
end
print("warnings count:", #result.warnings)
for i, w in ipairs(result.warnings) do
  print(i, "filename=" .. tostring(w.filename), "msg=" .. tostring(w.msg))
end
print("passed fname to tl.check was:", fname)
