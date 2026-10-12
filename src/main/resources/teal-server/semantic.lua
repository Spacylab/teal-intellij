-- Semantic highlighting: textDocument/semanticTokens/full. Classifies each
-- identifier of a document from tl's view of the code, so the IDE can color
-- locals, parameters, globals, fields, functions and types differently. The
-- TextMate grammar colors everything else (keywords, literals, comments).
--
-- Whether a name is a value or a type, a field or a declaration, comes from
-- the node tl's parser puts at its position (`variable`, `identifier`,
-- `argument`, a `nominal` type...). What a value name refers to comes from
-- the type report: the file's declarations (symbols_by_file), the globals,
-- and field types.

local tl = require("tl")
local json = require("json")
local lookup = require("lookup")

local codes = tl.typecodes
local semantic = {}

semantic.TOKEN_TYPES = { "class", "enum", "interface", "type", "parameter", "variable", "property", "function", "method" }
semantic.TOKEN_MODIFIERS = { "declaration", "readonly", "defaultLibrary", "global" }

local TYPE = {}
for k, name in ipairs(semantic.TOKEN_TYPES) do TYPE[name] = k - 1 end
local DECLARATION, READONLY, DEFAULT_LIBRARY, GLOBAL = 1, 2, 4, 8

-- Words that are names to the lexer but keywords in context (`local record
-- R`, `global x`, `x as T`, `<const>`). They are real names only where the
-- parser made a node for them (`type(x)`).
local CONTEXTUAL = {
   record = true, interface = true, enum = true, type = true, userdata = true, metamethod = true,
   where = true, global = true, as = true, is = true, const = true, close = true, total = true,
}

-- Keywords that introduce a type declaration's name.
local TYPE_KEYWORDS = { record = "class", interface = "interface", enum = "enum", type = "type" }

-- Node kinds (and `type:<typename>` for type nodes) by position, from a
-- fresh parse of the tokens: at[y][x] = { [kind] = true }. The checked AST
-- would do too, but checking links its nodes to the env's types, which a
-- walk would then wander into.
local function node_kinds(tokens, path)
   local at, seen = {}, {}
   local ok, ast = pcall(tl.parse_program, tokens, {}, path)
   if not ok or type(ast) ~= "table" then return at end
   local function visit(n)
      seen[n] = true
      local kind = n.kind or (n.typename and "type:" .. n.typename)
      if kind and n.y and n.x then
         local line = at[n.y] or {}
         at[n.y] = line
         local set = line[n.x] or {}
         line[n.x] = set
         set[kind] = true
      end
      for key, v in pairs(n) do
         if type(v) == "table" and key ~= "parent" and not seen[v] then visit(v) end
      end
   end
   visit(ast)
   return at
end

local NONE = {}

local function is_function(t)
   return t ~= nil and (t.t == codes.FUNCTION or t.t == codes.POLY)
end

local function is_call(tokens, i)
   local nxt = tokens[i + 1]
   return nxt ~= nil and (nxt.tk == "(" or nxt.tk == "{" or nxt.kind == "string")
end

-- The token type for a declared type: from its keyword, or for `type X =`,
-- from what follows the `=`.
local function declared_type_kind(tokens, i)
   local kind = TYPE_KEYWORDS[tokens[i - 1].tk]
   if kind == "type" and tokens[i + 1] and tokens[i + 1].tk == "=" and tokens[i + 2] then
      local rhs = TYPE_KEYWORDS[tokens[i + 2].tk]
      if rhs and rhs ~= "type" then kind = rhs end
   end
   return kind
end

local function type_kind(t)
   if not t then return "class" end
   if t.t == codes.ENUM then return "enum" end
   if t.t == codes.INTERFACE then return "interface" end
   if t.t == codes.RECORD then return "class" end
   return "type"
end

-- "function" or "method" if tokens[i] is the name in a function declaration
-- header (`function f(`, `local function f(`, `function a.b:c<T>(`), else nil.
local function declared_function(tokens, i)
   local nxt = tokens[i + 1]
   if not nxt or (nxt.tk ~= "(" and nxt.tk ~= "<") then return nil end
   local j = i - 1
   while tokens[j] and (tokens[j].tk == "." or tokens[j].tk == ":")
      and tokens[j - 1] and tokens[j - 1].kind == "identifier" do
      j = j - 2
   end
   if not tokens[j] or tokens[j].tk ~= "function" then return nil end
   return tokens[i - 1].tk == ":" and "method" or "function"
end

-- True if the name declared at dy:dx is followed by `<const>`. tl records
-- `local function f` at `local`, so look for the name along the line.
local function is_const(tokens, name, dy, dx)
   local d = lookup.token_at(tokens, dy, dx)
   while d and tokens[d] and tokens[d].y == dy and tokens[d].tk ~= name do d = d + 1 end
   if not d or not tokens[d] or tokens[d].y ~= dy then return false end
   return tokens[d + 1] ~= nil and tokens[d + 1].tk == "<" and tokens[d + 2] ~= nil and tokens[d + 2].tk == "const"
end

local Classifier = {}
Classifier.__index = Classifier

function Classifier:kinds(y, x)
   local line = self.at[y]
   return line and line[x] or NONE
end

-- A name used as a value: a local (or parameter, or type) found through the
-- file's scopes, else a global.
function Classifier:reference(i)
   local tokens, tr, tk = self.tokens, self.tr, self.tokens[i]
   local name = tk.tk
   local dy, dx, n = lookup.declaration(tr, self.path, name, tk.y, tk.x)
   if dy then
      if name == "self" or self:kinds(dy, dx).argument then return "parameter", 0 end
      local d = lookup.token_at(tokens, dy, dx)
      if d and tokens[d - 1] and TYPE_KEYWORDS[tokens[d - 1].tk] then
         return type_kind(lookup.resolve(tr, self.symbols[n][4])), 0
      end
      if is_function(lookup.resolve(tr, self.symbols[n][4])) and is_call(tokens, i) then return "function", 0 end
      return "variable", is_const(tokens, name, dy, dx) and READONLY or 0
   end

   local id = tr.globals[name]
   if not id then return nil end
   local mods = GLOBAL
   local t = tr.types[id]
   if t and t.file == "stdlib.d.tl" then mods = mods + DEFAULT_LIBRARY end
   if is_function(lookup.resolve(tr, id)) and is_call(tokens, i) then return "function", mods end
   return "variable", mods
end

-- A type annotation (`p: Player`, `img: love.graphics.Image`): its first
-- name carries the `nominal` node. Every name of the dotted chain is a type,
-- the last one being the type itself.
function Classifier:type_reference(i)
   local tokens = self.tokens
   local last = i
   while tokens[last + 1] and tokens[last + 1].tk == "." and tokens[last + 2]
      and tokens[last + 2].kind == "identifier" do
      last = last + 2
      self.chain[last] = "class"
   end
   local id = lookup.by_pos(self.tr, self.path, tokens[i].y, tokens[i].x)
   self.chain[last] = type_kind(lookup.resolve(self.tr, id))
   return self.chain[i] or "class", 0
end

-- Returns the token type and modifier bits for the identifier at tokens[i],
-- or nil to leave it to the TextMate grammar.
function Classifier:classify(i)
   local tokens, tk = self.tokens, self.tokens[i]
   local k = self:kinds(tk.y, tk.x)
   local prev, nxt = tokens[i - 1], tokens[i + 1]

   local fn = declared_function(tokens, i)
   if fn then return fn, DECLARATION end
   if k.argument then return "parameter", DECLARATION end
   if self.chain[i] then return self.chain[i], 0 end
   if k["type:nominal"] then return self:type_reference(i) end

   if lookup.is_member_op(tokens, i - 1) then
      local t = lookup.resolve(self.tr, lookup.type_at_token(self.tr, self.path, tokens, i))
      if is_function(t) then return prev.tk == ":" and "method" or "function", 0 end
      return "property", 0
   end

   if k.variable or k.type_identifier then return self:reference(i) end

   if k.literal_table_item then return "property", DECLARATION end -- `{ key = v }`

   local declared_type = prev and declared_type_kind(tokens, i)
   if declared_type and (k.identifier or not CONTEXTUAL[tk.tk]) then return declared_type, DECLARATION end

   if k.identifier then
      -- A declared value name: `local x`, `global x`, `for k, v in`.
      if prev and prev.tk == "global" or k.global_declaration then
         return "variable", DECLARATION + GLOBAL + (is_const(tokens, tk.tk, tk.y, tk.x) and READONLY or 0)
      end
      return "variable", DECLARATION + (is_const(tokens, tk.tk, tk.y, tk.x) and READONLY or 0)
   end

   -- A record field declaration: `name: T` at the start of a line.
   if nxt and nxt.tk == ":" and (not prev or prev.y < tk.y) and not CONTEXTUAL[tk.tk] then
      local after = tokens[i + 2]
      return (after and after.tk == "function") and "method" or "property", DECLARATION
   end
   return nil
end

-- The LSP SemanticTokens for a document: { data = [deltaLine, deltaStart,
-- length, type, modifiers, ...] }.
--
-- A file that doesn't parse still gets tokens for whatever tl made sense of.
-- If nothing could be classified at all, the last result is returned again
-- rather than dropping every color while the user types.
function semantic.full(workspace, doc)
   local check = doc.check
   local tokens = check and check.tokens
   if not tokens then return doc.semantic_tokens or { data = json.array() } end

   local tr = workspace:type_report()
   local classifier = setmetatable({
      tokens = tokens,
      tr = tr,
      path = doc.path,
      symbols = tr.symbols_by_file[doc.path] or {},
      at = node_kinds(tokens, doc.path),
      chain = {}, -- token index -> type for the later names of a qualified type
   }, Classifier)

   local data = json.array()
   local line, col = 1, 1
   for i, tk in ipairs(tokens) do
      if tk.kind == "identifier" then
         local ty, mods = classifier:classify(i)
         if ty then
            data[#data + 1] = tk.y - line
            data[#data + 1] = tk.y == line and tk.x - col or tk.x - 1
            data[#data + 1] = #tk.tk
            data[#data + 1] = TYPE[ty]
            data[#data + 1] = mods
            line, col = tk.y, tk.x
         end
      end
   end

   if #data == 0 and doc.semantic_tokens then return doc.semantic_tokens end
   doc.semantic_tokens = { data = data }
   return doc.semantic_tokens
end

return semantic
