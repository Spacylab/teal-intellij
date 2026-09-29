-- Conversion between `file://` URIs and filesystem paths. Replies about an
-- open document reuse the client's own URI string, so these conversions only
-- matter for reaching the filesystem and for files the client never sent.

local uri = {}

function uri.to_path(u)
   local path = u:match("^file://[^/]*(/.*)$") or u:match("^file:(/.*)$")
   if not path then return nil end
   path = path:gsub("%%(%x%x)", function(hex) return string.char(tonumber(hex, 16)) end)
   -- file:///C:/x -> C:/x
   if path:match("^/%a:") then path = path:sub(2) end
   return path
end

function uri.from_path(path)
   path = path:gsub("\\", "/")
   if not path:match("^/") then path = "/" .. path end
   return "file://" .. path:gsub("[^%w%-%._~/:]", function(ch)
      return string.format("%%%02X", ch:byte())
   end)
end

return uri
