-- The Teal language server: a minimal LSP server over stdio, built on `tl`
-- as a library (ADR 0001, third amendment). Run it as `lua server.lua`.
-- It needs the `tl` rock on the interpreter's package.path and nothing else.
--
-- The loop is single-threaded and blocking: it reads one message, handles it
-- to completion, then reads the next. Checks are fast enough (tens of ms on
-- real files) that no debouncing is needed.
--
-- Environment:
--   TEAL_SERVER_REBUILD_EVERY  edits between env rebuilds (default 100)

local script_dir = (arg and arg[0] or ""):match("^(.*)[/\\]") or "."
package.path = script_dir .. "/?.lua;" .. package.path

local ok_tl, tl = pcall(require, "tl")
if not ok_tl then
   io.stderr:write("teal-server: cannot load the tl library (luarocks install tl): ", tostring(tl), "\n")
   os.exit(1)
end

local json = require("json")
local rpc = require("rpc")
local uri = require("uri")
local diagnostics = require("diagnostics")
local features = require("features")
local Workspace = require("workspace")

local function log(...)
   io.stderr:write("teal-server: ", table.concat({ ... }), "\n")
end

local workspace
local shutdown_requested = false

local function publish(doc)
   local check = workspace:check(doc)
   rpc.notify("textDocument/publishDiagnostics", {
      uri = doc.uri,
      version = doc.version,
      diagnostics = diagnostics.from_check(check, doc.path, workspace.config),
   })
end

local function publish_all()
   for _, doc in pairs(workspace.documents) do publish(doc) end
end

local function root_from(params)
   local root_uri = params.rootUri
   if root_uri == json.null then root_uri = nil end
   if not root_uri and type(params.workspaceFolders) == "table" and params.workspaceFolders[1] then
      root_uri = params.workspaceFolders[1].uri
   end
   if root_uri then return uri.to_path(root_uri) end
   if type(params.rootPath) == "string" then return params.rootPath end
   return nil
end

local requests = {}
local notifications = {}

function requests.initialize(params)
   workspace = Workspace.new(root_from(params), {
      rebuild_every = tonumber(os.getenv("TEAL_SERVER_REBUILD_EVERY")),
   })
   return {
      capabilities = {
         textDocumentSync = {
            openClose = true,
            change = 1, -- full document
            save = { includeText = false },
         },
         hoverProvider = true,
         definitionProvider = true,
         typeDefinitionProvider = true,
         completionProvider = { triggerCharacters = { ".", ":" } },
         signatureHelpProvider = { triggerCharacters = { "(", "," } },
         referencesProvider = true,
         codeActionProvider = { codeActionKinds = { "quickfix" } },
      },
      serverInfo = { name = "teal-server", version = "tl " .. tl.version() },
   }
end

function requests.shutdown()
   shutdown_requested = true
   return json.null
end

-- Requests about a position in an open document. A document the client
-- never opened has nothing to answer with.
local function positional(feature)
   return function(params)
      local doc = params.textDocument and workspace.documents[params.textDocument.uri]
      if not doc or type(params.position) ~= "table" then return json.null end
      local result = feature(workspace, doc, params.position)
      if result == nil then return json.null end
      return result
   end
end

requests["textDocument/hover"] = positional(features.hover)
requests["textDocument/definition"] = positional(features.definition)
requests["textDocument/typeDefinition"] = positional(features.type_definition)
requests["textDocument/completion"] = positional(features.completion)
requests["textDocument/signatureHelp"] = positional(features.signature_help)

requests["textDocument/references"] = function(params)
   local include = type(params.context) == "table" and params.context.includeDeclaration == true
   return positional(function(ws, doc, position)
      return features.references(ws, doc, position, include)
   end)(params)
end

requests["textDocument/codeAction"] = function(params)
   local doc = params.textDocument and workspace.documents[params.textDocument.uri]
   if not doc then return json.array() end
   return features.code_action(workspace, doc, params.context)
end

notifications["initialized"] = function() end

notifications["exit"] = function()
   os.exit(shutdown_requested and 0 or 1)
end

notifications["textDocument/didOpen"] = function(params)
   local td = params.textDocument
   local path = uri.to_path(td.uri) or td.uri
   publish(workspace:open(td.uri, path, td.text, td.version))
end

notifications["textDocument/didChange"] = function(params)
   local td = params.textDocument
   local changes = params.contentChanges
   local text = changes[#changes] and changes[#changes].text
   if type(text) ~= "string" then return end
   if workspace:change(td.uri, text, td.version) then
      publish_all()
   elseif workspace.documents[td.uri] then
      publish(workspace.documents[td.uri])
   end
end

notifications["textDocument/didSave"] = function()
   workspace:rebuild_env()
   publish_all()
end

notifications["textDocument/didClose"] = function(params)
   local u = params.textDocument.uri
   workspace:close(u)
   rpc.notify("textDocument/publishDiagnostics", { uri = u, diagnostics = json.array() })
end

local function handle(message)
   local method, id, params = message.method, message.id, message.params
   if params == json.null then params = nil end
   params = params or {}

   if method == nil then return end -- a response to something we never send

   if id ~= nil then
      local handler = requests[method]
      if not handler then
         rpc.respond_error(id, -32601, "method not found: " .. method)
      elseif not workspace and method ~= "initialize" then
         rpc.respond_error(id, -32002, "server not initialized")
      else
         local ok, result = pcall(handler, params)
         if ok then
            rpc.respond(id, result)
         else
            log(method, " failed: ", tostring(result))
            rpc.respond_error(id, -32603, tostring(result))
         end
      end
      return
   end

   local handler = notifications[method]
   if handler and (workspace or method == "exit") then
      local ok, err = pcall(handler, params)
      if not ok then log(method, " failed: ", tostring(err)) end
   end
end

while true do
   local ok, message = pcall(rpc.read)
   if not ok then
      log("unreadable message: ", tostring(message))
   elseif message == nil then
      os.exit(shutdown_requested and 0 or 1)
   else
      handle(message)
   end
end
