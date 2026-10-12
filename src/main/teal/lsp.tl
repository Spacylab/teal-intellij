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

   record Diagnostic
      range: Range
      severity: integer
      source: string
      message: string
   end

   severity: {DiagnosticSeverity:integer}
end

lsp.severity = { error = 1, warning = 2 }

return lsp
