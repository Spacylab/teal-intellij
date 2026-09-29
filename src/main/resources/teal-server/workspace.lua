-- The workspace: the project's tlconfig.lua, the open documents, and the one
-- `tl` env they are all checked against.
--
-- Env policy (ADR 0001, third amendment): the env is reused across checks
-- because building one costs tens of milliseconds. Every check leaves types
-- behind in it, and it caches every required module as it was on disk. So it
-- is rebuilt on any save (a saved file may be a module another file
-- requires) and after `rebuild_every` edits (a memory bound for long unsaved
-- sessions). A rebuild re-checks every open document.

local tl = require("tl")
local uri = require("uri")

local Workspace = {}
Workspace.__index = Workspace

local SEP = package.config:sub(1, 1)

local function join(a, b)
   if b:sub(1, 1) == "/" or b:match("^%a:[/\\]") then return b end
   return a .. SEP .. b
end

local function load_config(root)
   local chunk = loadfile(join(root, "tlconfig.lua"))
   if not chunk then return {} end
   local ok, cfg = pcall(chunk)
   if not ok or type(cfg) ~= "table" then return {} end
   return cfg
end

-- tl finds required modules through `tl.path`, a package.path-style template
-- list. Project directories come first; the interpreter's own package.path
-- stays last so declaration files from installed rocks are still found.
local function module_path(root, cfg)
   local dirs = {}
   for _, dir in ipairs(cfg.include_dir or {}) do dirs[#dirs + 1] = join(root, dir) end
   if cfg.source_dir then dirs[#dirs + 1] = join(root, cfg.source_dir) end
   dirs[#dirs + 1] = root

   local entries = {}
   for _, dir in ipairs(dirs) do
      entries[#entries + 1] = dir .. SEP .. "?.lua"
      entries[#entries + 1] = dir .. SEP .. "?" .. SEP .. "init.lua"
   end
   entries[#entries + 1] = package.path
   return table.concat(entries, ";")
end

function Workspace.new(root, opts)
   local self = setmetatable({}, Workspace)
   self.root = root
   self.config = root and load_config(root) or {}
   self.rebuild_every = opts and opts.rebuild_every or 100
   self.documents = {} -- uri -> { uri, path, text, version }
   self.edits_since_rebuild = 0
   if root then tl.path = module_path(root, self.config) end
   self:rebuild_env()
   return self
end

function Workspace:rebuild_env()
   local cfg = self.config
   local env, err = tl.new_env({
      defaults = { gen_compat = cfg.gen_compat, gen_target = cfg.gen_target },
      predefined_modules = { cfg.global_env_def },
   })
   if not env then error("could not create tl env: " .. tostring(err), 0) end
   env.report_types = true
   -- Loads the predefined modules (global_env_def) up front, so the first
   -- real check doesn't pay for it.
   tl.check_string("", env, "bootstrap.tl")
   self.env = env
   self.edits_since_rebuild = 0
   self.disk_tokens = {} -- path -> tokens of files read from disk, as of this env
end

-- Lexes, parses and type-checks one document's current text against the
-- shared env, and keeps the outcome as `doc.check` for request handlers.
-- Returns { tokens, lex_errors, parse_errors, result }; result is nil only
-- when lexing failed.
--
-- A file with parse errors is still type-checked: tl checks whatever it could
-- parse, so the type report stays current while the user is mid-edit. Its
-- type errors are not reported (see diagnostics.lua). Checking a partial AST
-- is not something tl promises to handle, hence the pcall.
function Workspace:check(doc)
   local tokens, lex_errors = tl.lex(doc.text, doc.path)
   local out = { tokens = tokens, lex_errors = lex_errors or {}, parse_errors = {} }
   doc.check = out
   if #out.lex_errors > 0 then return out end

   local ast = tl.parse_program(tokens, out.parse_errors, doc.path)
   local lax = doc.path:sub(-4) == ".lua"
   local ok, result = pcall(tl.check, ast, doc.path, { feat_lax = lax and "on" or "off", feat_arity = "on" }, self.env)
   if ok then
      out.result = result
   elseif #out.parse_errors == 0 then
      error(result, 0)
   end
   return out
end

function Workspace:type_report()
   return self.env.reporter:get_report()
end

function Workspace:open(uri, path, text, version)
   local doc = { uri = uri, path = path, text = text, version = version }
   self.documents[uri] = doc
   return doc
end

-- Records an edit. Returns true when this edit crossed the rebuild threshold
-- and the env was rebuilt, meaning every open document needs a re-check.
function Workspace:change(uri, text, version)
   local doc = self.documents[uri]
   if not doc then return false end
   doc.text, doc.version = text, version
   self.edits_since_rebuild = self.edits_since_rebuild + 1
   if self.edits_since_rebuild >= self.rebuild_every then
      self:rebuild_env()
      return true
   end
   return false
end

function Workspace:close(u)
   self.documents[u] = nil
end

local function exists(path)
   local f = io.open(path, "r")
   if f then f:close() end
   return f ~= nil
end

local function read(path)
   local f = io.open(path, "r")
   if not f then return nil end
   local text = f:read("*a")
   f:close()
   return text
end

function Workspace:document_at(path)
   for _, doc in pairs(self.documents) do
      if doc.path == path then return doc end
   end
   return nil
end

-- tl reports "" for the file being checked, and the paths it found modules
-- at otherwise (absolute, since tl.path is built from absolute directories).
function Workspace:absolute(file, from_doc)
   if file == nil then return nil end
   if file == "" then return from_doc.path end
   if self.root then return join(self.root, file) end
   return file
end

-- Tokens of any file: an open document's current ones, or the file as it is
-- on disk (lexed once per env, like the modules tl caches in it).
function Workspace:tokens_of(file, from_doc)
   local path = self:absolute(file, from_doc)
   local doc = path and self:document_at(path)
   if doc then return doc.check and doc.check.tokens end
   if not path then return nil end
   if self.disk_tokens[path] == nil then
      local text = read(path)
      self.disk_tokens[path] = text and tl.lex(text, path) or false
   end
   return self.disk_tokens[path] or nil
end

-- An LSP Location for a tl position, or nil if the file isn't a real one
-- (tl reports "stdlib.d.tl" for standard library declarations).
function Workspace:location(file, y, x, from_doc)
   if not y or not x then return nil end
   local path = self:absolute(file, from_doc)
   if not path then return nil end
   local doc = self:document_at(path)
   local target
   if doc then
      target = doc.uri
   elseif exists(path) then
      target = uri.from_path(path)
   else
      return nil
   end
   local pos = { line = y - 1, character = x - 1 }
   return { uri = target, range = { start = pos, ["end"] = pos } }
end

return Workspace
