-- Turns a document's lex/parse/type-check results into LSP diagnostics,
-- following teal-language-server's rules: lex errors take priority over
-- parse errors, which take priority over type errors and warnings. Only this
-- file's own errors are reported, not those of modules it requires.
--
-- Positions are tl's 1-based line and byte column, converted to 0-based LSP
-- positions. LSP columns are UTF-16 units, so the two agree only on ASCII lines.

local tl = require("tl")
local json = require("json")

local diagnostics = {}

local ERROR, WARNING = 1, 2

local function diagnostic(tokens, err, severity)
   local token = tokens and tl.get_token_at(tokens, err.y, err.x)
   -- "$EOF$" is tl's end-of-input marker, not text in the file.
   local length = (token and token ~= "$EOF$") and #token or 0
   return {
      range = {
         start = { line = err.y - 1, character = err.x - 1 },
         ["end"] = { line = err.y - 1, character = err.x - 1 + length },
      },
      severity = severity,
      source = "tl",
      message = err.msg,
   }
end

local function set_of(list)
   local s = {}
   for _, v in ipairs(list or {}) do s[v] = true end
   return s
end

-- `check` is the table returned by workspace's check: tokens, lex_errors,
-- parse_errors and result. `config` is the tlconfig table.
function diagnostics.from_check(check, path, config)
   local out = json.array()

   if #check.lex_errors > 0 then
      for _, tk in ipairs(check.lex_errors) do
         out[#out + 1] = diagnostic(nil, { y = tk.y, x = tk.x, msg = "unexpected token '" .. tostring(tk.tk) .. "'" }, ERROR)
      end
      return out
   end

   if #check.parse_errors > 0 then
      for _, err in ipairs(check.parse_errors) do
         out[#out + 1] = diagnostic(check.tokens, err, ERROR)
      end
      return out
   end

   local disabled = set_of(config.disable_warnings)
   local as_errors = set_of(config.warning_error)
   local result = check.result

   for _, w in ipairs(result.warnings or {}) do
      if w.filename == path and not disabled[w.tag] then
         out[#out + 1] = diagnostic(check.tokens, w, as_errors[w.tag] and ERROR or WARNING)
      end
   end
   for _, err in ipairs(result.type_errors or {}) do
      if err.filename == path then
         out[#out + 1] = diagnostic(check.tokens, err, ERROR)
      end
   end
   return out
end

return diagnostics
