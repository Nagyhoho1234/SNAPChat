# Changelog

## 1.1.0 (2026-03-08)

### Added
- **Google Earth Engine integration** -- configure your GEE project in Settings, and ask the AI to query, process, and download GEE data
- **Adaptive tiled GEE downloading** -- automatically splits large downloads into spatial tiles to stay under the 50 MB API limit, retrying with finer grids until each tile fits
- GEE project field in Settings dialog with help text
- Dynamic system prompt: appends GEE-specific instructions (with full code template) only when a project is configured
- **Error recovery feedback**: orange "Analyzing error and working on a fix..." message appears below errors when the AI is retrying, plus status bar shows "Fixing error... (attempt N)"
- Multi-tool execution: handles multiple tool calls per response (fixes Anthropic tool_use/tool_result contract)
- Recursive follow-up processing for complex multi-step tasks (up to 15 rounds)
- Conversation history truncation (max 40 messages) with smart tool_use/tool_result boundary handling
- Debug JSONL logging of conversation history (`%APPDATA%/SNAPChat/logs/conversation_*.jsonl`)
- Rollback error recovery instead of full history clear on tool sync errors
- Enhanced raster context: band names, pixel types, dimensions

### Fixed
- **Code type auto-detection** (`detectCodeType`): Always auto-detect whether AI output is GPT command or Python code from content, never rely on parsed `type` field (which was frequently wrong due to multiple `"type":` keys in Anthropic JSON responses). Multi-line = always Python; single-line matching `^[A-Z][a-zA-Z-]+` = GPT; default = Python (safer).
- **GPT path with spaces**: `CommandExecutor` now uses `ProcessBuilder` argument list instead of `cmd.exe /c` string concatenation -- fixes `"C:\Program Files\..." is not recognized` errors
- **esa_snappy SNAP_HOME**: `CommandExecutor.runPython()` now sets `SNAP_HOME` environment variable automatically, so esa_snappy can find the SNAP installation without manual config
- **Cross-platform GPT discovery**: `findGpt()` checks `snap.home` system property, common Windows/Linux/Mac paths, and PATH fallback
- **Markdown rendering in chat**: Added `markdownToHtml()` converter for JEditorPane -- renders headers, bold, inline code, code blocks, tables, lists, blockquotes, and horizontal rules instead of showing raw markdown
- **Response formatting**: System prompt now instructs AI to be concise and show output file paths prominently on their own line
- **GEE download robustness**: System prompt includes concrete tiled download code template with `gdal.UseExceptions()`, TIFF warning suppression (`CPL_LOG=NUL`), correct `gdal.Warp` syntax, and per-band native resolution rules (S1=10m, S2=10/20/60m depending on band)
- System prompt now prevents the AI from using `subprocess` with `sys.executable` (which may launch a new SNAP instance)
- System prompt warns against emoji in print() (Windows cp1252 crashes)
- System prompt lists valid esa_snappy imports (prevents `ImportError` on GUI classes like `ColorPaletteDef`, `SnapApp`)
- System prompt warns that GeoTIFF band names become `band_1` not original names
- System prompt warns that Offset-Tracking needs SLC not GRD
- System prompt documents esa_snappy `readPixels`/`loadRasterData` "Cannot construct DataBuffer" workaround (use GDAL for reading, snappy for writing)
- System prompt advises user-writable output paths, `jpy.array()` for band data, and harmless "no product reader" INFO messages
- Critical bug: only the last tool_use block was captured when the AI returned multiple tool calls in one response
- Follow-up responses containing tool calls were silently dropped instead of being processed recursively

### Changed
- Version bumped to 1.1.0 across pom.xml, manifest, and module metadata

## 1.0.0 (2026-03-06)

Initial release.

- Chat panel for ESA SNAP Desktop (docked in properties area)
- Multi-provider LLM support: Anthropic, OpenAI, Google Gemini, Ollama, OpenAI-compatible
- GPT (Graph Processing Tool) command generation and execution
- Python/snappy code generation and execution
- Automatic SNAP context awareness (open products, bands, CRS)
- Settings dialog for provider selection, API key, model, and preferences
- Confirmation dialog before executing operations
- Zero external dependencies (pure JDK HTTP and JSON handling)
