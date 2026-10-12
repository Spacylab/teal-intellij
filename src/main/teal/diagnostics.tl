-- Turns a document's lex/parse/type-check results into LSP diagnostics,
-- following teal-language-server's rules: lex errors take priority over
-- parse errors, which take priority over type errors and warnings. Only this
-- file's own errors are reported, not those of modules it requires.
--
-- Positions are tl's 1-based line and byte column, converted to 0-based LSP
-- positions. LSP columns are UTF-16 units, so the two agree only on ASCII lines.

local tl = require("tl")
local json = require("json")
local lsp = require("lsp")

local type Workspace = require("workspace")

local record diagnostics
end

local function diagnostic(tokens: {tl.Token}, err: tl.Error, severity: lsp.DiagnosticSeverity): lsp.Diagnostic
   local token = tokens and tl.get_token_at(tokens, err.y, err.x)
   -- "$EOF$" is tl's end-of-input marker, not text in the file.
   local length = (token and token ~= "$EOF$") and #token or 0
   return {
      range = {
         start = { line = err.y - 1, character = err.x - 1 },
         ["end"] = { line = err.y - 1, character = err.x - 1 + length },
      },
      severity = lsp.severity[severity],
      source = "tl",
      message = err.msg,
   }
end

local function set_of(list: {string}): {string:boolean}
   local s: {string:boolean} = {}
   for _, v in ipairs(list or {}) do s[v] = true end
   return s
end

function diagnostics.from_check(check: Workspace.Check, path: string, config: Workspace.Config): {lsp.Diagnostic}
   local out: {lsp.Diagnostic} = json.array()

   if #check.lex_errors > 0 then
      for _, err in ipairs(check.lex_errors) do
         out[#out + 1] = diagnostic(nil, err, "error")
      end
      return out
   end

   if #check.parse_errors > 0 then
      for _, err in ipairs(check.parse_errors) do
         out[#out + 1] = diagnostic(check.tokens, err, "error")
      end
      return out
   end

   local disabled = set_of(config.disable_warnings)
   local as_errors = set_of(config.warning_error)
   local result = check.result

   for _, w in ipairs(result.warnings or {}) do
      if w.filename == path and not disabled[w.tag] then
         out[#out + 1] = diagnostic(check.tokens, w, as_errors[w.tag] and "error" or "warning")
      end
   end
   for _, err in ipairs(result.type_errors or {}) do
      if err.filename == path then
         out[#out + 1] = diagnostic(check.tokens, err, "error")
      end
   end
   return out
end

return diagnostics
