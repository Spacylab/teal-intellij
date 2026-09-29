-- LSP stdio framing (`Content-Length: N\r\n\r\n<N bytes of JSON>`) on top of
-- blocking reads from stdin. The server handles one message at a time, so
-- there is no reader thread and no queue.

local json = require("json")

local rpc = {}

local input, output = io.stdin, io.stdout

-- Returns the decoded message, or nil at end of input.
function rpc.read()
   local length
   while true do
      local line = input:read("*l")
      if line == nil then return nil end
      line = line:gsub("\r$", "")
      if line == "" then break end
      local value = line:match("^[Cc]ontent%-[Ll]ength:%s*(%d+)")
      if value then length = tonumber(value) end
   end
   if not length then return nil end
   local body = input:read(length)
   if body == nil or #body < length then return nil end
   return json.decode(body)
end

function rpc.write(message)
   message.jsonrpc = "2.0"
   local body = json.encode(message)
   output:write("Content-Length: ", #body, "\r\n\r\n", body)
   output:flush()
end

function rpc.respond(id, result)
   rpc.write({ id = id, result = result == nil and json.null or result })
end

function rpc.respond_error(id, code, message)
   rpc.write({ id = id, error = { code = code, message = message } })
end

function rpc.notify(method, params)
   rpc.write({ method = method, params = params })
end

return rpc
