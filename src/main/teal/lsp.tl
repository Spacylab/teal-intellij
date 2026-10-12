-- Teal records for the LSP messages the server reads and writes. Only what
-- the server uses is declared: fields it never reads or writes are left out.
-- Payloads arrive untyped from `json.decode` and are cast to these once.

local record lsp
   -- 0-based line, and 0-based column in UTF-16 units.
   record Position
      line: integer
      character: integer
   end

   record Range
      start: Position
      ["end"]: Position
   end

   enum DiagnosticSeverity
      -- Encoded as integers on the wire; see `lsp.severity`.
      "error" "warning"
   end

   record Location
      uri: string
      range: Range
   end

   record SemanticTokens
      data: {integer}
   end

   record Diagnostic
      range: Range
      severity: integer
      source: string
      message: string
   end

   record MarkupContent
      kind: string
      value: string
   end

   record Hover
      contents: MarkupContent
      range: Range
   end

   record CompletionItem
      label: string
      kind: integer -- CompletionItemKind
      detail: string
   end

   record ParameterInformation
      label: {integer} -- [start, end) offsets into the signature's label
   end

   record SignatureInformation
      label: string
      parameters: {ParameterInformation}
      activeParameter: integer
   end

   record SignatureHelp
      signatures: {SignatureInformation}
      activeSignature: integer
      activeParameter: integer
   end

   record TextEdit
      range: Range
      newText: string
   end

   record WorkspaceEdit
      changes: {string:{TextEdit}} -- by document uri
   end

   record CodeActionContext
      diagnostics: {Diagnostic}
      only: {string}
   end

   record CodeAction
      title: string
      kind: string
      diagnostics: {Diagnostic}
      edit: WorkspaceEdit
   end

   severity: {DiagnosticSeverity:integer}
end

lsp.severity = { error = 1, warning = 2 }

return lsp
