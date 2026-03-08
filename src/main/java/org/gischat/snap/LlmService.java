package org.gischat.snap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Multi-provider LLM client using org.json-style manual JSON building
 * (no external dependencies — uses simple string concatenation).
 */
public class LlmService {

    private static final String SYSTEM_PROMPT_BASE = """
            You are a remote sensing assistant embedded in ESA SNAP Desktop.
            You help users perform satellite image processing tasks using natural language.
            You have access to the current SNAP state (open products, bands, CRS).

            When the user asks you to perform an operation, call the run_gpt function/tool
            to generate a SNAP GPT (Graph Processing Tool) command.

            Guidelines for generated GPT commands:
            - Use SNAP operator names: Subset, Resample, BandMaths, Calibration, Terrain-Correction, etc.
            - Format: gpt <operator> -P<param>=<value> -t <target> <source>
            - For BandMaths, use -PtargetBand expressions
            - For Subset, use -Pregion=<x>,<y>,<w>,<h> or -PgeoRegion=POLYGON(...)
            - For multi-step processing, chain operators with -t intermediate files
            - Use the source product file paths from the map context
            - Print the output file path so the user knows where the result is

            You can also generate Python/snappy code if GPT is not sufficient.
            For snappy code, use esa_snappy imports and ProductIO for reading/writing.

            If the user asks a question that doesn't require processing, just answer with text.
            If you're unsure which product the user means, ask for clarification.
            If a task seems destructive (overwriting data), warn the user.

            IMPORTANT — Error recovery:
            When a command returns an error, try an alternative approach automatically.
            Only report failure after exhausting reasonable alternatives.

            IMPORTANT — Response formatting:
            Keep responses concise. Do NOT write long markdown tables or verbose summaries.
            When you produce an output file, state the path clearly on its own line, e.g.:
            **Output file:** `C:\\Users\\...\\result.dim`
            Then tell the user: Open in SNAP: File > Open Product (Ctrl+O)
            Do not repeat the same information in tables and bullet lists.

            CRITICAL — Windows / SNAP constraints:
            - sys.executable may NOT point to python.exe. NEVER use subprocess.run([sys.executable, ...]).
              For pip install, use: from pip._internal.cli.main import main as _pip; _pip(['install', 'package_name'])
            - NEVER use emoji in print() — Windows cp1252 console crashes with UnicodeEncodeError.
              Use plain ASCII markers like [OK], [ERROR], [DONE] instead.
            - When calling GPT from Python subprocess, always quote the path:
              subprocess.run([r'"C:\\Program Files\\esa-snap\\bin\\gpt.exe"', ...], shell=True)
              Or better: use the run_gpt tool instead of subprocess for GPT commands.
            - The esa_snappy Python bridge only exposes ProductIO, GPF, HashMap, Product, Band, ProductData, ProductUtils, jpy.
              Do NOT try to import SnapApp, ColorPaletteDef, ImageInfo, SnapDiagnostics, or any other GUI/RCP class.
              For advanced Java classes, use jpy.get_type('org.esa.snap.core.datamodel.ColorPaletteDef') instead.
            - You ABSOLUTELY CANNOT add products to the running SNAP Desktop window. SnapApp, OpenProductAction,
              Lookup, ProductManager — NONE of these work from external Python. Do NOT attempt it. Do NOT launch
              a new SNAP instance either. Simply write the product as BEAM-DIMAP (.dim) and tell the user:
              "Open it in SNAP: File > Open Product (Ctrl+O) > select the .dim file"
              This is the ONLY way. Do not try alternatives. Do not apologize and retry.
            - For raster I/O without esa_snappy, use GDAL: from osgeo import gdal
            - NEVER use emoji in text responses either — keep responses plain ASCII.

            CRITICAL — GPT operator gotchas:
            - GPT does NOT support --version. To test GPT, run: gpt -h
            - When GeoTIFFs are read by SNAP, band names become band_1, band_2, etc. (NOT the original names like VV, B4).
              Always check actual band names with: gpt BandNames <product> or read the product info first.
            - CreateStack -PmasterBands must use actual band names from the product (e.g. band_1), not assumed names.
            - For Offset-Tracking, inputs must be SLC (not GRD). GRD lacks phase information needed for coregistration.
              If only GRD is available, use Python-based optical flow (cv2.calcOpticalFlowFarneback) instead of GPT Offset-Tracking.""";

    private static String buildSystemPrompt() {
        String geeProject = ChatSettings.getGeeProject();
        if (geeProject == null || geeProject.isBlank()) {
            return SYSTEM_PROMPT_BASE;
        }
        return SYSTEM_PROMPT_BASE + "\n\n" +
                "Google Earth Engine Integration:\n" +
                "The user has GEE configured (project: " + geeProject + "). You can generate Python code that uses\n" +
                "the earthengine-api (ee) to query, process, and download GEE data.\n\n" +
                "CRITICAL GEE DOWNLOAD RULES — follow this EXACT pattern every time:\n" +
                "1. Initialize: import ee; ee.Initialize(project='" + geeProject + "')\n" +
                "2. Build your image server-side (compositing, indices, etc. — no size limit on server).\n" +
                "3. NEVER call getDownloadURL without tiling first. GEE has a 50 MB per-request hard limit.\n" +
                "4. NEVER degrade resolution to fit the limit. Use native scale (S1 GRD=10, S2 B2/B3/B4/B8=10, B5-B8A/B11/B12=20, B1/B9/B10=60).\n" +
                "5. ALWAYS use this adaptive tiled download pattern. It tries downloading and automatically doubles the grid if a tile exceeds the 50MB limit:\n\n" +
                "```python\n" +
                "import ee, os, math, urllib.request\n" +
                "from osgeo import gdal\n" +
                "gdal.UseExceptions()\n" +
                "gdal.SetConfigOption('CPL_LOG', 'NUL')  # suppress noisy TIFF warnings\n" +
                "ee.Initialize(project='" + geeProject + "')\n" +
                "# ... build image ...\n" +
                "SCALE = 10  # native resolution\n" +
                "region = [lon_min, lat_min, lon_max, lat_max]  # bounding box\n" +
                "out_dir = os.path.join(os.path.expanduser('~'), 'Documents', 'ProjectName')\n" +
                "os.makedirs(out_dir, exist_ok=True)\n\n" +
                "def download_tiled(image, region, scale, out_dir, grid=1):\n" +
                "    lon_min, lat_min, lon_max, lat_max = region\n" +
                "    lat_step = (lat_max - lat_min) / grid\n" +
                "    lon_step = (lon_max - lon_min) / grid\n" +
                "    tile_paths = []\n" +
                "    for r in range(grid):\n" +
                "        for c in range(grid):\n" +
                "            tile_region = ee.Geometry.Rectangle([\n" +
                "                lon_min + c*lon_step, lat_min + r*lat_step,\n" +
                "                lon_min + (c+1)*lon_step, lat_min + (r+1)*lat_step])\n" +
                "            tile_path = os.path.join(out_dir, f'tile_{r}_{c}.tif')\n" +
                "            try:\n" +
                "                url = image.getDownloadURL({'scale': scale, 'region': tile_region,\n" +
                "                      'format': 'GEO_TIFF', 'filePerBand': False})\n" +
                "                urllib.request.urlretrieve(url, tile_path)\n" +
                "                tile_paths.append(tile_path)\n" +
                "                sz = os.path.getsize(tile_path) / 1e6\n" +
                "                print(f'[OK] tile_{r}_{c}.tif ({sz:.1f} MB)')\n" +
                "            except Exception as e:\n" +
                "                if 'must be less than or equal to' in str(e):\n" +
                "                    print(f'[RETRY] Grid {grid}x{grid} too coarse -> {grid*2}x{grid*2}')\n" +
                "                    for p in tile_paths:\n" +
                "                        if os.path.exists(p): os.remove(p)\n" +
                "                    return download_tiled(image, region, scale, out_dir, grid*2)\n" +
                "                raise\n" +
                "    return tile_paths\n\n" +
                "tile_paths = download_tiled(image, region, SCALE, out_dir)\n" +
                "merged = os.path.join(out_dir, 'result.tif')\n" +
                "# IMPORTANT: gdal.Warp kwargs — do NOT pass options as a list!\n" +
                "gdal.Warp(merged, tile_paths)  # just merge, no extra options needed\n" +
                "for p in tile_paths: os.remove(p)\n" +
                "print(f'[DONE] {merged}')\n" +
                "```\n\n" +
                "IMPORTANT GDAL rules:\n" +
                "- Always call gdal.UseExceptions() and gdal.SetConfigOption('CPL_LOG', 'NUL') at the top to suppress warnings.\n" +
                "- gdal.Warp(dest, srcs) — do NOT pass options=['...'] as a positional/keyword list. If you need creation options, use: gdal.Warp(dest, srcs, creationOptions=['COMPRESS=LZW']). But plain gdal.Warp(dest, srcs) is fine for merging.\n\n" +
                "6. The final merged GeoTIFF can be opened DIRECTLY in SNAP via File > Open Product (Ctrl+O). Do NOT convert to BEAM-DIMAP — SNAP reads GeoTIFFs natively. Just tell the user the file path.\n\n" +
                "Other GEE rules:\n" +
                "- Server-side operations (mosaic, clip, compositing) have no size limit\n" +
                "- GEE List.get() only works for index 0-99. For collections >100 images, use .limit(N) or .aggregate_array() + .getInfo()\n" +
                "- Sentinel-1 system:index is the full product name (e.g. S1A_IW_GRDH_...), NOT a date. Use system:time_start (ms since epoch) for dates.\n" +
                "- Use datetime.datetime.fromtimestamp(ts/1000, tz=datetime.timezone.utc) instead of deprecated utcfromtimestamp()\n" +
                "- Always write output files to user-writable paths (e.g. ~/Documents). Do NOT write to C:\\Program Files.\n" +
                "- 'WARNING: org.esa.snap... no product reader found' INFO messages are harmless — ignore them";
    }

    private static final int MAX_HISTORY_LENGTH = 40;

    private final List<String> history = new ArrayList<>();

    public void clearHistory() {
        history.clear();
    }

    public void rollbackHistory(int count) {
        for (int i = 0; i < count && !history.isEmpty(); i++) {
            history.remove(history.size() - 1);
        }
    }

    public void trimHistory() {
        if (history.size() <= MAX_HISTORY_LENGTH) return;
        int cut = history.size() - MAX_HISTORY_LENGTH;
        // Don't cut in the middle of a tool_use/tool_result pair
        while (cut < history.size() - 2) {
            String entry = history.get(cut);
            if (entry.contains("\"tool_use\"") || entry.contains("\"tool_result\"")
                    || entry.contains("\"tool_calls\"") || entry.contains("\"role\":\"tool\"")) {
                cut++;
                continue;
            }
            break;
        }
        dumpHistoryToDebugLog("trim", history.size());
        history.subList(0, cut).clear();
    }

    private void dumpHistoryToDebugLog(String reason, int msgCount) {
        try {
            Path logDir;
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                logDir = Path.of(appData, "SNAPChat", "logs");
            } else {
                logDir = Path.of(System.getProperty("user.home"), ".local", "share", "SNAPChat", "logs");
            }
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("conversation_" + LocalDate.now() + ".jsonl");

            String entry = "{\"timestamp\":" + jsonStr(LocalDateTime.now().toString())
                    + ",\"reason\":" + jsonStr(reason)
                    + ",\"messageCount\":" + msgCount
                    + ",\"messages\":[" + String.join(",", history) + "]}\n";

            Files.writeString(logFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Debug logging must never break the main flow
        }
    }

    public LlmResponse send(String userMessage, String mapContext) throws IOException {
        trimHistory();
        LlmProvider provider = ChatSettings.getProvider();
        if (provider.needsKey && ChatSettings.getApiKey().isBlank()) {
            throw new IOException("API key not configured for " + provider.displayName +
                    ". Go to Tools > GIS Chat Settings to configure.");
        }
        return switch (provider) {
            case Anthropic -> sendAnthropic(userMessage, mapContext);
            case GoogleGemini -> sendGemini(userMessage, mapContext);
            default -> sendOpenAI(userMessage, mapContext);
        };
    }

    public LlmResponse sendToolResult(String toolCallId, String result, String mapContext) throws IOException {
        trimHistory();
        return switch (ChatSettings.getProvider()) {
            case Anthropic -> sendAnthropicToolResults(List.of(new ToolResult(toolCallId, result)), mapContext);
            case GoogleGemini -> sendGeminiToolResult(toolCallId, result, mapContext);
            default -> sendOpenAIToolResult(toolCallId, result, mapContext);
        };
    }

    /**
     * Send multiple tool results in one message (required by Anthropic API).
     */
    public LlmResponse sendToolResults(List<ToolResult> results, String mapContext) throws IOException {
        trimHistory();
        return switch (ChatSettings.getProvider()) {
            case Anthropic -> sendAnthropicToolResults(results, mapContext);
            case GoogleGemini -> {
                LlmResponse resp = new LlmResponse();
                for (var r : results) {
                    resp = sendGeminiToolResult(r.id, r.result, mapContext);
                }
                yield resp;
            }
            default -> {
                LlmResponse resp = new LlmResponse();
                for (var r : results) {
                    resp = sendOpenAIToolResult(r.id, r.result, mapContext);
                }
                yield resp;
            }
        };
    }

    public record ToolResult(String id, String result) {}

    // ---- HTTP ----

    private String httpPost(String url, java.util.Map<String, String> headers, String body) throws IOException {
        var conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(180_000);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        headers.forEach(conn::setRequestProperty);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        var stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

        if (code < 200 || code >= 300) {
            throw new IOException("API error (" + code + "): " + response);
        }
        return response;
    }

    // ---- Anthropic ----

    private LlmResponse sendAnthropic(String userMessage, String mapContext) throws IOException {
        history.add("{\"role\":\"user\",\"content\":" + jsonStr(userMessage) + "}");

        String messages = "[" + String.join(",", history) + "]";
        String body = "{\"model\":" + jsonStr(ChatSettings.getModel())
                + ",\"max_tokens\":" + ChatSettings.getMaxTokens()
                + ",\"system\":" + jsonStr(buildSystemPrompt() + "\n\nCurrent SNAP state:\n" + mapContext)
                + ",\"messages\":" + messages
                + ",\"tools\":[" + anthropicTool() + "]}";

        var headers = java.util.Map.of(
                "x-api-key", ChatSettings.getApiKey(),
                "anthropic-version", "2023-06-01"
        );

        String resp = httpPost(ChatSettings.getEffectiveEndpoint(), headers, body);
        String content = extractJsonArray(resp, "content");
        history.add("{\"role\":\"assistant\",\"content\":" + content + "}");
        return parseAnthropic(resp);
    }

    private LlmResponse sendAnthropicToolResults(List<ToolResult> results, String mapContext) throws IOException {
        // Build content array with all tool_result blocks
        StringBuilder content = new StringBuilder("[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) content.append(",");
            var r = results.get(i);
            content.append("{\"type\":\"tool_result\",\"tool_use_id\":")
                    .append(jsonStr(r.id))
                    .append(",\"content\":").append(jsonStr(r.result)).append("}");
        }
        content.append("]");

        history.add("{\"role\":\"user\",\"content\":" + content + "}");

        String messages = "[" + String.join(",", history) + "]";
        String body = "{\"model\":" + jsonStr(ChatSettings.getModel())
                + ",\"max_tokens\":" + ChatSettings.getMaxTokens()
                + ",\"system\":" + jsonStr(buildSystemPrompt() + "\n\nCurrent SNAP state:\n" + mapContext)
                + ",\"messages\":" + messages
                + ",\"tools\":[" + anthropicTool() + "]}";

        var headers = java.util.Map.of(
                "x-api-key", ChatSettings.getApiKey(),
                "anthropic-version", "2023-06-01"
        );

        String resp = httpPost(ChatSettings.getEffectiveEndpoint(), headers, body);
        String respContent = extractJsonArray(resp, "content");
        history.add("{\"role\":\"assistant\",\"content\":" + respContent + "}");
        return parseAnthropic(resp);
    }

    private static String anthropicTool() {
        return "{\"name\":\"run_gpt\",\"description\":\"Execute a SNAP GPT command or Python/snappy code.\","
                + "\"input_schema\":{\"type\":\"object\",\"properties\":{"
                + "\"command\":{\"type\":\"string\",\"description\":\"GPT command line or Python/snappy code to execute\"},"
                + "\"explanation\":{\"type\":\"string\",\"description\":\"Brief explanation of what this does\"},"
                + "\"type\":{\"type\":\"string\",\"enum\":[\"gpt\",\"python\"],\"description\":\"Whether this is a GPT command or Python code\"}"
                + "},\"required\":[\"command\",\"explanation\",\"type\"]}}";
    }

    private LlmResponse parseAnthropic(String json) {
        LlmResponse resp = new LlmResponse();
        int idx = 0;
        while ((idx = json.indexOf("\"type\":", idx)) >= 0) {
            if (hasValueAt(json, idx, "text")) {
                String text = extractStringAfter(json, "\"text\":", idx);
                if (text != null) resp.text += text;
            } else if (hasValueAt(json, idx, "tool_use")) {
                String id = extractStringAfter(json, "\"id\":", idx);
                String name = extractStringAfter(json, "\"name\":", idx);
                int inputIdx = json.indexOf("\"input\":", idx);
                String command = null, explanation = null, type = null;
                if (inputIdx >= 0) {
                    command = extractStringAfter(json, "\"command\":", inputIdx);
                    explanation = extractStringAfter(json, "\"explanation\":", inputIdx);
                    type = extractStringAfter(json, "\"type\":", inputIdx + 50);
                }
                resp.toolCalls.add(new LlmResponse.ToolCallInfo(id, name, command, explanation, type));
            }
            idx++;
        }
        return resp;
    }

    // ---- OpenAI / Ollama / Compatible ----

    private LlmResponse sendOpenAI(String userMessage, String mapContext) throws IOException {
        history.add("{\"role\":\"user\",\"content\":" + jsonStr(userMessage) + "}");

        String sysMsg = "{\"role\":\"system\",\"content\":" + jsonStr(buildSystemPrompt() + "\n\nCurrent SNAP state:\n" + mapContext) + "}";
        String messages = "[" + sysMsg + "," + String.join(",", history) + "]";
        String body = "{\"model\":" + jsonStr(ChatSettings.getModel())
                + ",\"messages\":" + messages
                + ",\"max_tokens\":" + ChatSettings.getMaxTokens()
                + ",\"tools\":[" + openaiTool() + "]}";

        var headers = new java.util.HashMap<String, String>();
        String key = ChatSettings.getApiKey();
        if (key != null && !key.isBlank()) headers.put("Authorization", "Bearer " + key);

        String resp = httpPost(ChatSettings.getEffectiveEndpoint(), headers, body);

        // Extract the message object and store in history
        int msgStart = resp.indexOf("\"message\":");
        if (msgStart >= 0) {
            int braceStart = resp.indexOf('{', msgStart + 10);
            String msg = extractObject(resp, braceStart);
            history.add(msg);
        }

        return parseOpenAI(resp);
    }

    private LlmResponse sendOpenAIToolResult(String toolCallId, String result, String mapContext) throws IOException {
        history.add("{\"role\":\"tool\",\"tool_call_id\":" + jsonStr(toolCallId) + ",\"content\":" + jsonStr(result) + "}");

        String sysMsg = "{\"role\":\"system\",\"content\":" + jsonStr(buildSystemPrompt() + "\n\nCurrent SNAP state:\n" + mapContext) + "}";
        String messages = "[" + sysMsg + "," + String.join(",", history) + "]";
        String body = "{\"model\":" + jsonStr(ChatSettings.getModel())
                + ",\"messages\":" + messages
                + ",\"max_tokens\":" + ChatSettings.getMaxTokens()
                + ",\"tools\":[" + openaiTool() + "]}";

        var headers = new java.util.HashMap<String, String>();
        String key = ChatSettings.getApiKey();
        if (key != null && !key.isBlank()) headers.put("Authorization", "Bearer " + key);

        String resp = httpPost(ChatSettings.getEffectiveEndpoint(), headers, body);
        int msgStart = resp.indexOf("\"message\":");
        if (msgStart >= 0) {
            int braceStart = resp.indexOf('{', msgStart + 10);
            history.add(extractObject(resp, braceStart));
        }
        return parseOpenAI(resp);
    }

    private static String openaiTool() {
        return "{\"type\":\"function\",\"function\":{\"name\":\"run_gpt\","
                + "\"description\":\"Execute a SNAP GPT command or Python/snappy code.\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{"
                + "\"command\":{\"type\":\"string\",\"description\":\"GPT command or Python code\"},"
                + "\"explanation\":{\"type\":\"string\",\"description\":\"Brief explanation\"},"
                + "\"type\":{\"type\":\"string\",\"enum\":[\"gpt\",\"python\"],\"description\":\"gpt or python\"}"
                + "},\"required\":[\"command\",\"explanation\",\"type\"]}}}";
    }

    private LlmResponse parseOpenAI(String json) {
        LlmResponse resp = new LlmResponse();
        resp.text = extractStringAfter(json, "\"content\":", 0);
        if (resp.text == null) resp.text = "";

        if (json.contains("\"tool_calls\"")) {
            // Parse all tool_calls in the array
            int searchFrom = json.indexOf("\"tool_calls\"");
            int tcArrEnd = findArrayEnd(json, searchFrom);

            int tcIdx = searchFrom;
            while ((tcIdx = json.indexOf("\"id\":", tcIdx)) >= 0 && tcIdx < tcArrEnd) {
                String id = extractStringAfter(json, "\"id\":", tcIdx);
                int fnIdx = json.indexOf("\"function\":", tcIdx);
                if (fnIdx >= 0 && fnIdx < tcArrEnd) {
                    String name = extractStringAfter(json, "\"name\":", fnIdx);
                    String args = extractStringAfter(json, "\"arguments\":", fnIdx);
                    String command = null, explanation = null, type = null;
                    if (args != null) {
                        command = extractStringAfter(args, "\"command\":", 0);
                        explanation = extractStringAfter(args, "\"explanation\":", 0);
                        type = extractStringAfter(args, "\"type\":", 0);
                    }
                    resp.toolCalls.add(new LlmResponse.ToolCallInfo(id, name, command, explanation, type));
                }
                tcIdx++;
            }
        }
        return resp;
    }

    // ---- Gemini ----

    private LlmResponse sendGemini(String userMessage, String mapContext) throws IOException {
        history.add("{\"role\":\"user\",\"parts\":[{\"text\":" + jsonStr(userMessage) + "}]}");

        String url = ChatSettings.getEffectiveEndpoint() + "/models/" + ChatSettings.getModel()
                + ":generateContent?key=" + ChatSettings.getApiKey();

        String sysContent = "{\"role\":\"user\",\"parts\":[{\"text\":" + jsonStr(buildSystemPrompt() + "\n\nCurrent SNAP state:\n" + mapContext) + "}]}";
        String ack = "{\"role\":\"model\",\"parts\":[{\"text\":\"Understood. Ready to help.\"}]}";
        String contents = "[" + sysContent + "," + ack + "," + String.join(",", history) + "]";

        String body = "{\"contents\":" + contents + ",\"tools\":[{\"function_declarations\":[" + geminiTool() + "]}]}";

        String resp = httpPost(url, java.util.Map.of(), body);

        // Store model response in history
        int partsIdx = resp.indexOf("\"parts\":");
        if (partsIdx >= 0) {
            String parts = extractJsonArray(resp, "parts");
            history.add("{\"role\":\"model\",\"parts\":" + parts + "}");
        }

        return parseGemini(resp);
    }

    private LlmResponse sendGeminiToolResult(String toolCallId, String result, String mapContext) throws IOException {
        history.add("{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":\"run_gpt\","
                + "\"response\":{\"result\":" + jsonStr(result) + "}}}]}");

        String url = ChatSettings.getEffectiveEndpoint() + "/models/" + ChatSettings.getModel()
                + ":generateContent?key=" + ChatSettings.getApiKey();

        String sysContent = "{\"role\":\"user\",\"parts\":[{\"text\":" + jsonStr(buildSystemPrompt() + "\n\nCurrent SNAP state:\n" + mapContext) + "}]}";
        String ack = "{\"role\":\"model\",\"parts\":[{\"text\":\"Understood.\"}]}";
        String contents = "[" + sysContent + "," + ack + "," + String.join(",", history) + "]";
        String body = "{\"contents\":" + contents + "}";

        String resp = httpPost(url, java.util.Map.of(), body);
        int partsIdx = resp.indexOf("\"parts\":");
        if (partsIdx >= 0) {
            String parts = extractJsonArray(resp, "parts");
            history.add("{\"role\":\"model\",\"parts\":" + parts + "}");
        }
        return parseGemini(resp);
    }

    private static String geminiTool() {
        return "{\"name\":\"run_gpt\",\"description\":\"Execute a SNAP GPT command or Python/snappy code.\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{"
                + "\"command\":{\"type\":\"string\",\"description\":\"GPT command or Python code\"},"
                + "\"explanation\":{\"type\":\"string\",\"description\":\"Brief explanation\"},"
                + "\"type\":{\"type\":\"string\",\"enum\":[\"gpt\",\"python\"],\"description\":\"gpt or python\"}"
                + "},\"required\":[\"command\",\"explanation\",\"type\"]}}";
    }

    private LlmResponse parseGemini(String json) {
        LlmResponse resp = new LlmResponse();
        // Look for text parts
        String text = extractStringAfter(json, "\"text\":", 0);
        if (text != null) resp.text = text;

        // Collect all functionCall blocks
        int searchFrom = 0;
        while (true) {
            int fcIdx = json.indexOf("\"functionCall\"", searchFrom);
            if (fcIdx < 0) break;

            String id = "gemini_" + UUID.randomUUID().toString().substring(0, 8);
            String name = extractStringAfter(json, "\"name\":", fcIdx);
            int argsIdx = json.indexOf("\"args\":", fcIdx);
            String command = null, explanation = null, type = null;
            if (argsIdx >= 0) {
                command = extractStringAfter(json, "\"command\":", argsIdx);
                explanation = extractStringAfter(json, "\"explanation\":", argsIdx);
                type = extractStringAfter(json, "\"type\":", argsIdx);
            }
            resp.toolCalls.add(new LlmResponse.ToolCallInfo(id, name, command, explanation, type));
            searchFrom = fcIdx + 1;
        }
        return resp;
    }

    // ---- JSON utilities (no external library) ----

    static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private boolean hasValueAt(String json, int idx, String value) {
        int qStart = json.indexOf('"', idx + 7);
        if (qStart < 0) return false;
        return json.startsWith(value, qStart + 1);
    }

    static String extractStringAfter(String json, String key, int fromIndex) {
        int keyIdx = json.indexOf(key, fromIndex);
        if (keyIdx < 0) return null;
        int valStart = keyIdx + key.length();
        // Skip whitespace
        while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) valStart++;
        if (valStart >= json.length()) return null;
        if (json.charAt(valStart) == '"') {
            // Parse string value
            StringBuilder sb = new StringBuilder();
            int i = valStart + 1;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    switch (next) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        default -> { sb.append('\\'); sb.append(next); }
                    }
                    i += 2;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }
        if (json.startsWith("null", valStart)) return null;
        return null;
    }

    private String extractJsonArray(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx < 0) return "[]";
        int arrStart = json.indexOf('[', idx);
        if (arrStart < 0) return "[]";
        int depth = 0;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return json.substring(arrStart, i + 1); }
        }
        return "[]";
    }

    private int findArrayEnd(String json, int fromIndex) {
        int arrStart = json.indexOf('[', fromIndex);
        if (arrStart < 0) return json.length();
        int depth = 0;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return json.length();
    }

    private String extractObject(String json, int braceStart) {
        if (braceStart < 0 || braceStart >= json.length()) return "{}";
        int depth = 0;
        for (int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return json.substring(braceStart, i + 1); }
        }
        return "{}";
    }
}
