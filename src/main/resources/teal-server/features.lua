-- LSP request handlers for language features: hover, definition, type
-- definition, completion and signature help. Each takes the workspace, the
-- document and an LSP position (0-based line, character) and returns the
-- LSP result, or nil for "nothing here".

local tl = require("tl")
local json = require("json")
local lookup = require("lookup")
local requires = require("requires")

local codes = tl.typecodes
local features = {}

-- LSP CompletionItemKind
local KIND = { method = 2, ["function"] = 3, field = 5, variable = 6, class = 7, module = 9, enum = 13 }

local function tl_pos(position)
   return position.line + 1, position.character + 1
end

local function token_range(tk)
   return {
      start = { line = tk.y - 1, character = tk.x - 1 },
      ["end"] = { line = tk.y - 1, character = tk.x - 1 + #tk.tk },
   }
end

-- Formatting ------------------------------------------------------------------

local function type_str(tr, id)
   local t = id and tr.types[id]
   return t and t.str or "?"
end

-- Parameter labels for a function type: its argument types, prefixed with
-- the declared names when the declaration has them.
local function param_labels(workspace, doc, tr, fn)
   local args = fn.args or {}
   local names
   if fn.file and fn.y then
      local tokens = workspace:tokens_of(fn.file, doc)
      names = tokens and lookup.param_names(tokens, fn.y, fn.x)
      if names and #names ~= #args then names = nil end
   end
   local labels = {}
   for k, arg in ipairs(args) do
      local ty = type_str(tr, arg[1])
      local vararg = fn.vararg and k == #args
      if names then
         labels[k] = (vararg and "..." or names[k]) .. ": " .. ty
      else
         labels[k] = vararg and ("...: " .. ty) or ty
      end
   end
   return labels
end

local function returns_str(tr, fn)
   local rets = {}
   for k, ret in ipairs(fn.rets or {}) do
      rets[k] = type_str(tr, ret[1]) .. ((fn.varret and k == #fn.rets) and "..." or "")
   end
   if #rets == 0 then return "" end
   return ": " .. table.concat(rets, ", ")
end

-- `name(a: T, b: U): R`, plus each parameter's [start, end) offset in it.
local function signature(workspace, doc, tr, name, fn, skip_self)
   local labels = param_labels(workspace, doc, tr, fn)
   if skip_self then table.remove(labels, 1) end
   local label = name .. "("
   local params = json.array()
   for k, l in ipairs(labels) do
      if k > 1 then label = label .. ", " end
      params[k] = { label = json.array({ #label, #label + #l }) }
      label = label .. l
   end
   return label .. ")" .. returns_str(tr, fn), params
end

local function variants(tr, t)
   if t.t == codes.POLY then
      local out = {}
      for k, id in ipairs(t.types or {}) do out[k] = lookup.resolve(tr, id) end
      return out
   end
   return { t }
end

local function sorted_keys(t)
   local keys = {}
   for k in pairs(t) do keys[#keys + 1] = k end
   table.sort(keys)
   return keys
end

local function hover_text(workspace, doc, tr, name, id, method_access)
   local shown = tr.types[id]
   if not shown then return nil end
   local t = lookup.resolve(tr, id) or shown

   local blocks = {}
   if t.t == codes.FUNCTION or t.t == codes.POLY then
      for _, fn in ipairs(variants(tr, t)) do
         blocks[#blocks + 1] = "function " .. (signature(workspace, doc, tr, name, fn, method_access))
      end
   elseif t.t == codes.RECORD or t.t == codes.INTERFACE then
      local header = name .. ": " .. (shown.ref and shown.str or "record")
      if shown.str == name then header = "record " .. name end -- the type's own name
      local lines = { header }
      for _, field in ipairs(sorted_keys(t.fields or {})) do
         lines[#lines + 1] = "   " .. field .. ": " .. type_str(tr, t.fields[field])
      end
      blocks[1] = table.concat(lines, "\n")
   elseif t.t == codes.ENUM then
      local lines = { "enum " .. (shown.str or name) }
      for _, value in ipairs(t.enums or {}) do lines[#lines + 1] = '   "' .. value .. '"' end
      lines[#lines + 1] = "end"
      blocks[1] = table.concat(lines, "\n")
   else
      blocks[1] = name .. ": " .. (shown.str or t.str or "?")
   end

   local parts = {}
   for k, block in ipairs(blocks) do parts[k] = "```teal\n" .. block .. "\n```" end
   return table.concat(parts, "\nor\n")
end

-- Handlers --------------------------------------------------------------------

local function identifier_at(doc, position)
   local check = doc.check
   if not check or not check.tokens then return nil end
   local y, x = tl_pos(position)
   local i = lookup.token_at(check.tokens, y, x)
   if not i or check.tokens[i].kind ~= "identifier" then return nil end
   return check.tokens, i
end

function features.hover(workspace, doc, position)
   local tokens, i = identifier_at(doc, position)
   if not tokens then return nil end
   local tr = workspace:type_report()
   local id = lookup.type_at_token(tr, doc.path, tokens, i)
   local method_access = lookup.is_member_op(tokens, i - 1) and tokens[i - 1].tk == ":"
   local text = id and hover_text(workspace, doc, tr, tokens[i].tk, id, method_access)
   if not text then return nil end
   return { contents = { kind = "markdown", value = text }, range = token_range(tokens[i]) }
end

-- Where the identifier at tokens[i] was declared, as an LSP Location, or nil.
local function definition_at(workspace, doc, tr, tokens, i)
   local tk = tokens[i]
   local member = lookup.is_member_op(tokens, i - 1)
   local parent = member and tokens[i - 2] and tokens[i - 2].kind == "identifier" and i - 2

   if not member then
      local dy, dx = lookup.declaration(tr, doc.path, tk.tk, tk.y, tk.x)
      if dy then
         -- tl records `local function f` at `local`; land on the name itself.
         local d = lookup.token_at(tokens, dy, dx)
         while d and tokens[d].y == dy and tokens[d].tk ~= tk.tk do d = d + 1 end
         if d and tokens[d] and tokens[d].y == dy then dx = tokens[d].x end
         return workspace:location(doc.path, dy, dx, doc)
      end
   end

   if parent then
      -- A member of a named value: the field's type in the parent's type was
      -- created where the field was declared (`x: Point`, `f: function()`,
      -- `function M.f()`). tl's type at the `.` itself won't do: on the left
      -- of an assignment it is the assigned value's type.
      local parent_id = lookup.type_at_token(tr, doc.path, tokens, parent)
      local fields = lookup.fields_of(tr, parent_id)
      local field = fields and tr.types[fields[tk.tk] or -1]
      local loc = field and workspace:location(field.file, field.y, field.x, doc)
      if loc then return loc end
      -- Not a known field yet (`function M.f()` adding to `local M = {}`):
      -- tl's type at the `.` is then the value being declared.
      if not field then
         local t = tr.types[lookup.type_at_token(tr, doc.path, tokens, i) or -1]
         loc = t and not t.ref and workspace:location(t.file, t.y, t.x, doc)
         if loc then return loc end
      end
      -- A field of a type with no position of its own (`x: number`): go to
      -- the record it belongs to.
      local record = lookup.resolve(tr, parent_id)
      return record and workspace:location(record.file, record.y, record.x, doc)
   end

   -- A global, a type name, or a member of an unnamed value (`f().x`): go
   -- where its type was declared. A named type is a reference whose own
   -- position is wherever it was used, so follow it to the declaration.
   local t = tr.types[lookup.type_at_token(tr, doc.path, tokens, i) or -1]
   if t and t.ref then t = lookup.resolve(tr, t.ref) end
   return t and workspace:location(t.file, t.y, t.x, doc)
end

function features.definition(workspace, doc, position)
   local check = doc.check
   if not check or not check.tokens then return nil end

   -- A require's string literal, quotes included: the top of the required
   -- module's file (ticket 05).
   local y, x = tl_pos(position)
   local at = lookup.token_at(check.tokens, y, x)
   local module = at and requires.module_at(check.tokens, at)
   if module then
      local file = workspace.root and requires.resolve(workspace.root, module)
      return file and workspace:location(file, 1, 1, doc)
   end

   local tokens, i = identifier_at(doc, position)
   if not tokens then return nil end
   return definition_at(workspace, doc, workspace:type_report(), tokens, i)
end

-- Find Usages, within the current document: every same-named identifier that
-- resolves to the same place as the one at the cursor. The declaration site
-- is the occurrence its own definition points at.
function features.references(workspace, doc, position, include_declaration)
   local tokens, i = identifier_at(doc, position)
   if not tokens then return nil end
   local tr = workspace:type_report()
   local target = definition_at(workspace, doc, tr, tokens, i)
   if not target then return nil end

   local out = json.array()
   for k, tk in ipairs(tokens) do
      if tk.kind == "identifier" and tk.tk == tokens[i].tk then
         local loc = definition_at(workspace, doc, tr, tokens, k)
         if loc and loc.uri == target.uri
            and loc.range.start.line == target.range.start.line
            and loc.range.start.character == target.range.start.character then
            local is_declaration = loc.uri == doc.uri
               and loc.range.start.line == tk.y - 1 and loc.range.start.character == tk.x - 1
            if include_declaration or not is_declaration then
               out[#out + 1] = { uri = doc.uri, range = token_range(tk) }
            end
         end
      end
   end
   return out
end

-- The missing-require quick fix (ticket 04): for each `unknown type X`
-- diagnostic, one "Add require(...)" action per workspace module declaring a
-- global X that this file doesn't already require.
function features.code_action(workspace, doc, context)
   local actions = json.array()
   if not workspace.root or type(context) ~= "table" then return actions end

   local only = context.only
   if type(only) == "table" then
      local wanted = false
      for _, kind in ipairs(only) do
         if kind == "quickfix" or ("quickfix"):sub(1, #kind + 1) == kind .. "." then wanted = true end
      end
      if not wanted then return actions end
   end

   local already = requires.required_modules(doc.text)
   local line = requires.insertion_line(doc.text)
   local at = { line = line, character = 0 }
   for _, diagnostic in ipairs(context.diagnostics or {}) do
      local name = type(diagnostic.message) == "string" and requires.unknown_type_name(diagnostic.message)
      if name then
         for _, file in ipairs(requires.find_global_declarations(workspace.root, name, doc.path)) do
            local module = requires.module_name(workspace.root, file)
            if not already[module] then
               local statement = requires.statement(module)
               actions[#actions + 1] = {
                  title = "Add " .. statement,
                  kind = "quickfix",
                  diagnostics = json.array({ diagnostic }),
                  edit = { changes = { [doc.uri] = json.array({
                     { range = { start = at, ["end"] = at }, newText = statement .. "\n" },
                  }) } },
               }
            end
         end
      end
   end
   return actions
end

function features.type_definition(workspace, doc, position)
   local tokens, i = identifier_at(doc, position)
   if not tokens then return nil end
   local tr = workspace:type_report()
   local t = lookup.resolve(tr, lookup.type_at_token(tr, doc.path, tokens, i))
   return t and workspace:location(t.file, t.y, t.x, doc)
end

local function completion_item(tr, name, id, method_call, in_scope)
   local t = lookup.resolve(tr, id)
   local kind = in_scope and KIND.variable or KIND.field
   if t then
      if t.t == codes.FUNCTION or t.t == codes.POLY then
         kind = method_call and KIND.method or KIND["function"]
      elseif t.t == codes.RECORD or t.t == codes.INTERFACE then
         kind = in_scope and KIND.module or KIND.class
      elseif t.t == codes.ENUM then
         kind = KIND.enum
      end
   end
   return { label = name, kind = kind, detail = type_str(tr, id) }
end

function features.completion(workspace, doc, position)
   local check = doc.check
   if not check or not check.tokens then return nil end
   local tokens = check.tokens
   local tr = workspace:type_report()
   local y, x = tl_pos(position)

   -- The token just left of the cursor: a partly typed name, or the `.`/`:`.
   local i = lookup.token_left_of(tokens, y, x)
   if tokens[i] and tokens[i].kind == "identifier" and lookup.touches_cursor(tokens[i], y, x) then
      i = i - 1
   end

   local items = json.array()
   local op = tokens[i]
   -- Mid-edit, what follows a `:` can't tell a method call from a type
   -- annotation (see lookup.is_member_op), so every `:` is tried as a method
   -- call first. When that finds nothing, it is most likely an annotation
   -- (`local p: Po|`), and scope completion below offers the type names.
   if op and (op.tk == "." or op.tk == ":") then
      local object = tokens[i - 1]
      if not object or object.kind ~= "identifier" then return items end
      local fields = lookup.fields_of(tr, lookup.type_at_token(tr, doc.path, tokens, i - 1))
      local method_call = op.tk == ":"
      for _, name in ipairs(sorted_keys(fields or {})) do
         local t = lookup.resolve(tr, fields[name])
         if not method_call or (t and (t.t == codes.FUNCTION or t.t == codes.POLY)) then
            items[#items + 1] = completion_item(tr, name, fields[name], method_call, false)
         end
      end
      if op.tk == "." or #items > 0 then return items end
   end

   local seen = { ["..."] = true } -- the chunk's varargs, not a name
   for name, id in pairs(tl.symbols_in_scope(tr, y, x, doc.path)) do
      if not seen[name] then
         seen[name] = true
         items[#items + 1] = completion_item(tr, name, id, false, true)
      end
   end
   for name, id in pairs(tr.globals) do
      if not seen[name] then items[#items + 1] = completion_item(tr, name, id, false, true) end
   end
   return items
end

-- The call the cursor is inside: scans left for an unclosed `(`, counting
-- top-level commas. Returns the index of the token before `(` and the comma
-- count, or nil.
local function enclosing_call(tokens, i)
   local depth, commas = 0, 0
   local limit = math.max(1, i - 2000)
   while i >= limit do
      local tk = tokens[i].tk
      if tk == ")" or tk == "]" or tk == "}" then
         depth = depth + 1
      elseif tk == "(" or tk == "[" or tk == "{" then
         if depth == 0 then
            if tk == "(" then return i - 1, commas end
            return nil
         end
         depth = depth - 1
      elseif tk == "," and depth == 0 then
         commas = commas + 1
      elseif depth == 0 and (tk == "end" or tk == "then" or tk == "do" or tk == "local") then
         return nil
      end
      i = i - 1
   end
   return nil
end

function features.signature_help(workspace, doc, position)
   local check = doc.check
   if not check or not check.tokens then return nil end
   local tokens = check.tokens
   local y, x = tl_pos(position)

   local callee, commas = enclosing_call(tokens, lookup.token_left_of(tokens, y, x))
   if not callee or callee < 1 or tokens[callee].kind ~= "identifier" then return nil end

   local tr = workspace:type_report()
   local t = lookup.resolve(tr, lookup.type_at_token(tr, doc.path, tokens, callee))
   if not t or (t.t ~= codes.FUNCTION and t.t ~= codes.POLY) then return nil end

   local method_call = tokens[callee - 1] and tokens[callee - 1].tk == ":"
   local signatures = json.array()
   for _, fn in ipairs(variants(tr, t)) do
      local label, params = signature(workspace, doc, tr, tokens[callee].tk, fn, method_call)
      local active = commas
      if fn.vararg and #params > 0 and active >= #params then active = #params - 1 end
      signatures[#signatures + 1] = { label = label, parameters = params, activeParameter = active }
   end
   return { signatures = signatures, activeSignature = 0, activeParameter = commas }
end

return features
