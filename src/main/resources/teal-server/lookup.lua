-- Queries against a document's tokens and tl's type report, shared by the
-- request handlers in features.lua.
--
-- Positions here are tl's: 1-based line (y) and byte column (x).
--
-- The type report (`env.reporter:get_report()`) holds, per file:
--   by_pos[file][y][x]    type id of the expression starting at y:x. For
--                         `a.b` and `a:b`, the member's type is at the `.`/`:`.
--   symbols_by_file[file] declarations in source order as {y, x, name, type id},
--                         with "@{"/"@}" entries opening and closing scopes.
-- and globally `globals[name]` and `types[id]` ({ t = typecode, str, file, y,
-- x, ref, fields, args, rets, types, enums, ... }).

local tl = require("tl")

local lookup = {}

local function before(ay, ax, by, bx)
   return ay < by or (ay == by and ax < bx)
end

-- Index of the last token starting at or before y:x (binary search), or 0.
local function last_starting_at_or_before(tokens, y, x)
   local lo, hi, found = 1, #tokens, 0
   while lo <= hi do
      local mid = math.floor((lo + hi) / 2)
      local tk = tokens[mid]
      if before(y, x, tk.y, tk.x) then
         hi = mid - 1
      else
         found, lo = mid, mid + 1
      end
   end
   return found
end

-- Index of the token covering y:x, or nil.
function lookup.token_at(tokens, y, x)
   local i = last_starting_at_or_before(tokens, y, x)
   local tk = tokens[i]
   if tk and tk.y == y and x < tk.x + #tk.tk and tk.kind ~= "$EOF$" then return i end
   return nil
end

-- Index of the last token starting strictly before y:x -- the token just left
-- of a cursor placed at y:x -- or 0.
function lookup.token_left_of(tokens, y, x)
   local i = last_starting_at_or_before(tokens, y, x)
   local tk = tokens[i]
   if tk and tk.y == y and tk.x == x then i = i - 1 end
   if tokens[i] and tokens[i].kind == "$EOF$" then i = i - 1 end
   return i
end

-- True if a cursor at y:x sits inside or right at the end of token tk.
function lookup.touches_cursor(tk, y, x)
   return tk.y == y and tk.x + #tk.tk >= x
end

-- True if tokens[k] is a member access operator: any `.`, or a `:` that
-- starts a method call or declaration (`a:b(...)`, `a:b "..."`, `a:b {...}`).
-- A `:` followed by anything else is a type annotation (`local x: T`,
-- `(a: T)`, `): T`), whose type name is not a member of what precedes it.
function lookup.is_member_op(tokens, k)
   local tk = tokens[k]
   if not tk then return false end
   if tk.tk == "." then return true end
   if tk.tk ~= ":" then return false end
   local after = tokens[k + 2]
   return after ~= nil and (after.tk == "(" or after.tk == "{" or after.kind == "string")
end

function lookup.resolve(tr, id)
   local seen = 0
   local t = id and tr.types[id]
   while t and t.ref and seen < 50 do
      t = tr.types[t.ref]
      seen = seen + 1
   end
   return t
end

-- The string library's type, whose fields are the methods of every string.
function lookup.string_library(tr)
   return tr.types[tr.globals["string"]]
end

-- Fields of a value's type: its record/interface fields, or the string
-- library for strings. Returns a name -> type id table, or nil.
function lookup.fields_of(tr, id)
   local t = lookup.resolve(tr, id)
   if not t then return nil end
   -- tl.typecodes gives STRING and EMPTY_TABLE the same code; only a string
   -- is also named "string".
   if t.t == tl.typecodes.STRING and t.str == "string" then
      local lib = lookup.string_library(tr)
      return lib and lib.fields
   end
   return t.fields
end

local function by_pos(tr, path, y, x)
   local file = tr.by_pos[path]
   local line = file and file[y]
   return line and line[x]
end

-- Names of the dotted chain ending at token i (`a.b:c` -> {"a", "b", "c"}),
-- or nil if the chain starts with something other than a name (a call
-- result, an index expression, a string literal...).
function lookup.name_chain(tokens, i)
   local names = { tokens[i].tk }
   while lookup.is_member_op(tokens, i - 1) do
      local prev = tokens[i - 2]
      if not prev or prev.kind ~= "identifier" then return nil end
      table.insert(names, 1, prev.tk)
      i = i - 2
   end
   return names
end

-- Type id for a name chain, resolved by name the way teal-language-server
-- does it: the first name through the scope at y:x (or the globals), the rest
-- through record fields. Works on a stale report, since it matches names.
function lookup.type_of_chain(tr, path, names, y, x)
   local scope = tl.symbols_in_scope(tr, y, x, path)
   local id = scope[names[1]] or tr.globals[names[1]]
   for k = 2, #names do
      if not id then return nil end
      local fields = lookup.fields_of(tr, id)
      id = fields and fields[names[k]]
   end
   return id
end

-- Type id of the identifier token at i: tl's own answer at that position
-- when it has one, the name chain otherwise.
function lookup.type_at_token(tr, path, tokens, i)
   local tk = tokens[i]
   local op = tokens[i - 1]
   local id
   if lookup.is_member_op(tokens, i - 1) then
      id = by_pos(tr, path, op.y, op.x)
   else
      id = by_pos(tr, path, tk.y, tk.x)
      -- A qualified type name (`love.graphics.Image`) is recorded whole at
      -- its first name. That is the chain's type, not this name's.
      local t = id and tr.types[id]
      if t and t.ref and t.str and t.str:sub(1, #tk.tk + 1) == tk.tk .. "." then id = nil end
   end
   if id then return id end
   local names = lookup.name_chain(tokens, i)
   return names and lookup.type_of_chain(tr, path, names, tk.y, tk.x)
end

-- Where `name`, used at y:x, was declared in `path`: walks the file's
-- declarations backward from y:x, skipping closed scopes. Returns y, x or nil.
function lookup.declaration(tr, path, name, y, x)
   local symbols = tr.symbols_by_file[path]
   if not symbols then return nil end

   local lo, hi, n = 1, #symbols, 0
   while lo <= hi do
      local mid = math.floor((lo + hi) / 2)
      local s = symbols[mid]
      if before(y, x, s[1], s[2]) then hi = mid - 1 else n, lo = mid, mid + 1 end
   end

   while n >= 1 do
      local s = symbols[n]
      if s[3] == "@{" then
         n = n - 1
      elseif s[3] == "@}" then
         n = s[4] -- jump to the matching "@{"
      elseif s[3] == name then
         return s[1], s[2]
      else
         n = n - 1
      end
   end
   return nil
end

-- Parameter names of the function declared at y:x, read from the tokens
-- there: `function M:f(a: T, b?: U, ...: V)` -> {"self", "a", "b", "..."}.
-- Returns nil when the declaration has no names (a function type such as
-- `function(number): string`).
function lookup.param_names(tokens, y, x)
   local i = last_starting_at_or_before(tokens, y, x)
   if i == 0 then return nil end

   local method = false
   while tokens[i] and tokens[i].tk ~= "(" do
      local tk = tokens[i].tk
      if tk == ":" then method = true end
      if tk == ")" or tk == "end" or tokens[i].kind == "$EOF$" then return nil end
      i = i + 1
   end
   if not tokens[i] then return nil end

   local names = {}
   if method then names[1] = "self" end
   local depth = 0
   while tokens[i] do
      local tk = tokens[i].tk
      if tk == "(" or tk == "{" or tk == "<" then
         depth = depth + 1
      elseif tk == ")" or tk == "}" or tk == ">" then
         depth = depth - 1
         if depth == 0 then break end
      elseif depth == 1 and (tokens[i].kind == "identifier" or tk == "...") then
         local nxt = tokens[i + 1] and tokens[i + 1].tk
         local prev = tokens[i - 1] and tokens[i - 1].tk
         if (nxt == ":" or nxt == "?") and (prev == "(" or prev == ",") then
            names[#names + 1] = tk
         end
      end
      i = i + 1
   end
   return #names > (method and 1 or 0) and names or nil
end

return lookup
