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

local function fail(...: string)
   io.stderr:write("teal-server: ", table.concat({ ... }), "\n")
   os.exit(1)
end

local ok_tl, tl_err = pcall(require, "tl")
if not ok_tl then
   fail("cannot load the tl library (luarocks install tl): ", tostring(tl_err))
end
local tl = require("tl")

-- The numeric parts of a version string: "0.24.8" -> {0, 24, 8}.
local function version_parts(v: string): {integer}
   local parts: {integer} = {}
   for n in v:gmatch("%d+") do parts[#parts + 1] = tonumber(n) as integer end
   return parts
end

local function older(a: string, b: string): boolean
   local pa, pb = version_parts(a), version_parts(b)
   for k = 1, math.max(#pa, #pb) do
      local x, y = pa[k] or 0, pb[k] or 0
      if x ~= y then return x < y end
   end
   return false
end

-- The server is type-checked against one tl version (ADR 0002). An older tl
-- may lack what it relies on, so refuse it up front rather than fail on
-- some request later. Newer versions are accepted.
local pinned = require("tl_pin")
if older(tl.version(), pinned) then
   fail("tl ", tl.version(), " is too old, this server needs tl ", pinned, " or newer (luarocks install tl)")
end

local json = require("json")
local lsp = require("lsp")
local rpc = require("rpc")
local uri = require("uri")
local diagnostics = require("diagnostics")
local features = require("features")
local semantic = require("semantic")
local Workspace = require("workspace")

local function log(...: string)
   io.stderr:write("teal-server: ", table.concat({ ... }), "\n")
end

-- A JSON-RPC message as it comes off the wire. `params` is cast to its LSP
-- record by the handler for `method`.
local record Message
   method: string
   id: rpc.Id
   params: any
end

-- Handlers take the decoded params and return the result (requests) or
-- nothing (notifications).
local type Handler = function(params: any): any

local workspace: Workspace
local shutdown_requested = false

local function publish(doc: Workspace.Document)
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

local function root_from(params: lsp.InitializeParams): string
   local root_uri = params.rootUri
   if root_uri == json.null then root_uri = nil end
   if not root_uri and type(params.workspaceFolders) == "table" and params.workspaceFolders[1] then
      root_uri = params.workspaceFolders[1].uri
   end
   if root_uri then return uri.to_path(root_uri) end
   if type(params.rootPath) == "string" then return params.rootPath end
   return nil
end

local requests: {string:Handler} = {}
local notifications: {string:Handler} = {}

requests["initialize"] = function(params: any): any
   workspace = Workspace.new(root_from(params as lsp.InitializeParams), {
      rebuild_every = tonumber(os.getenv("TEAL_SERVER_REBUILD_EVERY")) as integer,
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
         semanticTokensProvider = {
            legend = { tokenTypes = semantic.TOKEN_TYPES, tokenModifiers = semantic.TOKEN_MODIFIERS },
            full = true,
         },
      },
      serverInfo = { name = "teal-server", version = "tl " .. tl.version() },
   }
end

requests["shutdown"] = function(): any
   shutdown_requested = true
   return json.null
end

local function document(td: lsp.TextDocumentIdentifier): Workspace.Document
   return td and workspace.documents[td.uri]
end

-- Requests about a position in an open document. A document the client
-- never opened has nothing to answer with.
local type Positional = function(Workspace, Workspace.Document, lsp.Position): any

local function positional(feature: Positional): Handler
   return function(params: any): any
      local p = params as lsp.TextDocumentPositionParams
      local doc = document(p.textDocument)
      if not doc or type(p.position) ~= "table" then return json.null end
      local result = feature(workspace, doc, p.position)
      if result == nil then return json.null end
      return result
   end
end

requests["textDocument/hover"] = positional(features.hover)
requests["textDocument/definition"] = positional(features.definition)
requests["textDocument/typeDefinition"] = positional(features.type_definition)
requests["textDocument/completion"] = positional(features.completion)
requests["textDocument/signatureHelp"] = positional(features.signature_help)

requests["textDocument/references"] = function(params: any): any
   local p = params as lsp.ReferenceParams
   local include = type(p.context) == "table" and p.context.includeDeclaration == true
   return positional(function(ws: Workspace, doc: Workspace.Document, position: lsp.Position): any
      return features.references(ws, doc, position, include)
   end)(params)
end

requests["textDocument/codeAction"] = function(params: any): any
   local p = params as lsp.CodeActionParams
   local doc = document(p.textDocument)
   if not doc then return json.array() end
   return features.code_action(workspace, doc, p.context)
end

requests["textDocument/semanticTokens/full"] = function(params: any): any
   local doc = document((params as lsp.TextDocumentParams).textDocument)
   if not doc then return { data = json.array() } end
   return semantic.full(workspace, doc)
end

notifications["initialized"] = function(): any end

notifications["exit"] = function(): any
   os.exit(shutdown_requested and 0 or 1)
end

notifications["textDocument/didOpen"] = function(params: any): any
   local td = (params as lsp.DidOpenTextDocumentParams).textDocument
   local path = uri.to_path(td.uri) or td.uri
   publish(workspace:open(td.uri, path, td.text, td.version))
end

notifications["textDocument/didChange"] = function(params: any): any
   local p = params as lsp.DidChangeTextDocumentParams
   local td = p.textDocument
   local changes = p.contentChanges
   local text = changes[#changes] and changes[#changes].text
   if type(text) ~= "string" then return end
   if workspace:change(td.uri, text, td.version) then
      publish_all()
   elseif workspace.documents[td.uri] then
      publish(workspace.documents[td.uri])
   end
end

notifications["textDocument/didSave"] = function(): any
   workspace:rebuild_env()
   publish_all()
end

notifications["textDocument/didClose"] = function(params: any): any
   local u = (params as lsp.TextDocumentParams).textDocument.uri
   workspace:close(u)
   rpc.notify("textDocument/publishDiagnostics", { uri = u, diagnostics = json.array() })
end

local function handle(message: Message)
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
      handle(message as Message)
   end
end
