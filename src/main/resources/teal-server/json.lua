-- Minimal JSON for the Teal server's JSON-RPC traffic. Pure Lua, with no
-- dependency on cjson or any other native module, and written to run on
-- Lua 5.1 through 5.5.
--
-- JSON null decodes to `json.null`, so nulls inside arrays keep their slot.
-- An empty Lua table is ambiguous, so it encodes as `{}` unless it was built
-- with `json.array()`, which makes it encode as `[]`.

local json = {}

json.null = setmetatable({}, { __tostring = function() return "null" end })

local array_mt = { __jsontype = "array" }

function json.array(t)
   return setmetatable(t or {}, array_mt)
end

-- Decoding -------------------------------------------------------------------

local escapes = { ['"'] = '"', ["\\"] = "\\", ["/"] = "/", b = "\b", f = "\f", n = "\n", r = "\r", t = "\t" }

local function utf8_char(cp)
   if cp < 0x80 then
      return string.char(cp)
   elseif cp < 0x800 then
      return string.char(0xC0 + math.floor(cp / 0x40), 0x80 + cp % 0x40)
   elseif cp < 0x10000 then
      return string.char(0xE0 + math.floor(cp / 0x1000), 0x80 + math.floor(cp / 0x40) % 0x40, 0x80 + cp % 0x40)
   else
      return string.char(0xF0 + math.floor(cp / 0x40000), 0x80 + math.floor(cp / 0x1000) % 0x40,
         0x80 + math.floor(cp / 0x40) % 0x40, 0x80 + cp % 0x40)
   end
end

local decode_value

local function decode_error(str, pos, what)
   error(string.format("json: %s at position %d", what, pos), 0)
end

local function skip_ws(str, pos)
   return str:find("[^ \t\r\n]", pos) or #str + 1
end

local function decode_string(str, pos)
   -- pos is at the opening quote
   local parts = {}
   local i = pos + 1
   while true do
      local j = str:find('["\\]', i)
      if not j then decode_error(str, pos, "unterminated string") end
      parts[#parts + 1] = str:sub(i, j - 1)
      if str:sub(j, j) == '"' then
         return table.concat(parts), j + 1
      end
      local esc = str:sub(j + 1, j + 1)
      if esc == "u" then
         local cp = tonumber(str:sub(j + 2, j + 5), 16)
         if not cp then decode_error(str, j, "bad \\u escape") end
         local next_i = j + 6
         if cp >= 0xD800 and cp <= 0xDBFF and str:sub(next_i, next_i + 1) == "\\u" then
            local low = tonumber(str:sub(next_i + 2, next_i + 5), 16)
            if low and low >= 0xDC00 and low <= 0xDFFF then
               cp = 0x10000 + (cp - 0xD800) * 0x400 + (low - 0xDC00)
               next_i = next_i + 6
            end
         end
         parts[#parts + 1] = utf8_char(cp)
         i = next_i
      else
         local ch = escapes[esc]
         if not ch then decode_error(str, j, "bad escape") end
         parts[#parts + 1] = ch
         i = j + 2
      end
   end
end

local function decode_array(str, pos)
   local arr = json.array()
   local n = 0
   pos = skip_ws(str, pos + 1)
   if str:sub(pos, pos) == "]" then return arr, pos + 1 end
   while true do
      local v
      v, pos = decode_value(str, pos)
      n = n + 1
      arr[n] = v
      pos = skip_ws(str, pos)
      local c = str:sub(pos, pos)
      if c == "]" then return arr, pos + 1 end
      if c ~= "," then decode_error(str, pos, "expected ',' or ']'") end
      pos = skip_ws(str, pos + 1)
   end
end

local function decode_object(str, pos)
   local obj = {}
   pos = skip_ws(str, pos + 1)
   if str:sub(pos, pos) == "}" then return obj, pos + 1 end
   while true do
      if str:sub(pos, pos) ~= '"' then decode_error(str, pos, "expected string key") end
      local key
      key, pos = decode_string(str, pos)
      pos = skip_ws(str, pos)
      if str:sub(pos, pos) ~= ":" then decode_error(str, pos, "expected ':'") end
      local v
      v, pos = decode_value(str, skip_ws(str, pos + 1))
      obj[key] = v
      pos = skip_ws(str, pos)
      local c = str:sub(pos, pos)
      if c == "}" then return obj, pos + 1 end
      if c ~= "," then decode_error(str, pos, "expected ',' or '}'") end
      pos = skip_ws(str, pos + 1)
   end
end

local literals = { ["true"] = true, ["false"] = false, ["null"] = json.null }

decode_value = function(str, pos)
   pos = skip_ws(str, pos)
   local c = str:sub(pos, pos)
   if c == "{" then return decode_object(str, pos) end
   if c == "[" then return decode_array(str, pos) end
   if c == '"' then return decode_string(str, pos) end
   local num = str:match("^-?%d+%.?%d*[eE]?[-+]?%d*", pos)
   if num and #num > 0 then
      local v = tonumber(num)
      if v == nil then decode_error(str, pos, "bad number") end
      return v, pos + #num
   end
   for word, v in pairs(literals) do
      if str:sub(pos, pos + #word - 1) == word then return v, pos + #word end
   end
   decode_error(str, pos, "unexpected character")
end

function json.decode(str)
   local v, pos = decode_value(str, 1)
   pos = skip_ws(str, pos)
   if pos <= #str then decode_error(str, pos, "trailing data") end
   return v
end

-- Encoding -------------------------------------------------------------------

local function encode_string(s)
   return '"' .. s:gsub('[%c"\\]', function(ch)
      if ch == '"' then return '\\"' end
      if ch == "\\" then return "\\\\" end
      if ch == "\n" then return "\\n" end
      if ch == "\r" then return "\\r" end
      if ch == "\t" then return "\\t" end
      return string.format("\\u%04x", ch:byte())
   end) .. '"'
end

local function is_array(t)
   if getmetatable(t) == array_mt then return true end
   local n = #t
   if n == 0 then return false end
   for k in pairs(t) do
      if type(k) ~= "number" or k < 1 or k > n or k % 1 ~= 0 then return false end
   end
   return true
end

local encode_value

local function encode_table(t, out)
   if is_array(t) then
      out[#out + 1] = "["
      for i = 1, #t do
         if i > 1 then out[#out + 1] = "," end
         encode_value(t[i], out)
      end
      out[#out + 1] = "]"
   else
      out[#out + 1] = "{"
      local first = true
      for k, v in pairs(t) do
         if not first then out[#out + 1] = "," end
         first = false
         out[#out + 1] = encode_string(tostring(k))
         out[#out + 1] = ":"
         encode_value(v, out)
      end
      out[#out + 1] = "}"
   end
end

encode_value = function(v, out)
   local tv = type(v)
   if v == nil or v == json.null then
      out[#out + 1] = "null"
   elseif tv == "boolean" then
      out[#out + 1] = v and "true" or "false"
   elseif tv == "number" then
      if v ~= v or v == math.huge or v == -math.huge then
         out[#out + 1] = "null"
      elseif v % 1 == 0 and v > -2 ^ 53 and v < 2 ^ 53 then
         out[#out + 1] = string.format("%d", v)
      else
         out[#out + 1] = string.format("%.17g", v)
      end
   elseif tv == "string" then
      out[#out + 1] = encode_string(v)
   elseif tv == "table" then
      encode_table(v, out)
   else
      error("json: cannot encode a " .. tv, 0)
   end
end

function json.encode(v)
   local out = {}
   encode_value(v, out)
   return table.concat(out)
end

return json
