package org.gischat.snap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Multi-provider LLM client using org.json-style manual JSON building
 * (no external dependencies — uses simple string concatenation).
 */
public class LlmService {

    private static final String SYSTEM_PROMPT = """
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
            Only report failure after exhausting reasonable alternatives.""";

    private final List<String> history = new ArrayList<>();

    public void clearHistory() {
        history.clear();
    }

    public LlmResponse send(String userMessage, String mapContext) throws IOException {
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
        return switch (ChatSettings.getProvider()) {
            case Anthropic -> sendAnthropicToolResult(toolCallId, result, mapContext);
            case GoogleGemini -> sendGeminiToolResult(toolCallId, result, mapContext);
            default -> sendOpenAIToolResult(toolCallId, result, mapContext);
        };
    }

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
                + ",\"system\":" + jsonStr(SYSTEM_PROMPT + "\n\nCurrent SNAP state:\n" + mapContext)
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

    private LlmResponse sendAnthropicToolResult(String toolCallId, String result, String mapContext) throws IOException {
        history.add("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":"
                + jsonStr(toolCallId) + ",\"content\":" + jsonStr(result) + "}]}");

        String messages = "[" + String.join(",", history) + "]";
        String body = "{\"model\":" + jsonStr(ChatSettings.getModel())
                + ",\"max_tokens\":" + ChatSettings.getMaxTokens()
                + ",\"system\":" + jsonStr(SYSTEM_PROMPT + "\n\nCurrent SNAP state:\n" + mapContext)
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
        // Simple parsing for text blocks
        int idx = 0;
        while ((idx = json.indexOf("\"type\":", idx)) >= 0) {
            if (hasValueAt(json, idx, "text")) {
                String text = extractStringAfter(json, "\"text\":", idx);
                if (text != null) resp.text += text;
            } else if (hasValueAt(json, idx, "tool_use")) {
                resp.toolCallId = extractStringAfter(json, "\"id\":", idx);
                resp.toolCallName = extractStringAfter(json, "\"name\":", idx);
                int inputIdx = json.indexOf("\"input\":", idx);
                if (inputIdx >= 0) {
                    resp.toolCallCommand = extractStringAfter(json, "\"command\":", inputIdx);
                    resp.toolCallExplanation = extractStringAfter(json, "\"explanation\":", inputIdx);
                    resp.toolCallType = extractStringAfter(json, "\"type\":", inputIdx + 50);
                }
            }
            idx++;
        }
        return resp;
    }

    // ---- OpenAI / Ollama / Compatible ----

    private LlmResponse sendOpenAI(String userMessage, String mapContext) throws IOException {
        history.add("{\"role\":\"user\",\"content\":" + jsonStr(userMessage) + "}");

        String sysMsg = "{\"role\":\"system\",\"content\":" + jsonStr(SYSTEM_PROMPT + "\n\nCurrent SNAP state:\n" + mapContext) + "}";
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

        String sysMsg = "{\"role\":\"system\",\"content\":" + jsonStr(SYSTEM_PROMPT + "\n\nCurrent SNAP state:\n" + mapContext) + "}";
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
            resp.toolCallId = extractStringAfter(json, "\"id\":", json.indexOf("\"tool_calls\""));
            int fnIdx = json.indexOf("\"function\":", json.indexOf("\"tool_calls\""));
            if (fnIdx >= 0) {
                resp.toolCallName = extractStringAfter(json, "\"name\":", fnIdx);
                String args = extractStringAfter(json, "\"arguments\":", fnIdx);
                if (args != null) {
                    resp.toolCallCommand = extractStringAfter(args, "\"command\":", 0);
                    resp.toolCallExplanation = extractStringAfter(args, "\"explanation\":", 0);
                    resp.toolCallType = extractStringAfter(args, "\"type\":", 0);
                }
            }
        }
        return resp;
    }

    // ---- Gemini ----

    private LlmResponse sendGemini(String userMessage, String mapContext) throws IOException {
        history.add("{\"role\":\"user\",\"parts\":[{\"text\":" + jsonStr(userMessage) + "}]}");

        String url = ChatSettings.getEffectiveEndpoint() + "/models/" + ChatSettings.getModel()
                + ":generateContent?key=" + ChatSettings.getApiKey();

        String sysContent = "{\"role\":\"user\",\"parts\":[{\"text\":" + jsonStr(SYSTEM_PROMPT + "\n\nCurrent SNAP state:\n" + mapContext) + "}]}";
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

        String sysContent = "{\"role\":\"user\",\"parts\":[{\"text\":" + jsonStr(SYSTEM_PROMPT + "\n\nCurrent SNAP state:\n" + mapContext) + "}]}";
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

        if (json.contains("\"functionCall\"")) {
            int fcIdx = json.indexOf("\"functionCall\"");
            resp.toolCallId = "gemini_" + UUID.randomUUID().toString().substring(0, 8);
            resp.toolCallName = extractStringAfter(json, "\"name\":", fcIdx);
            int argsIdx = json.indexOf("\"args\":", fcIdx);
            if (argsIdx >= 0) {
                resp.toolCallCommand = extractStringAfter(json, "\"command\":", argsIdx);
                resp.toolCallExplanation = extractStringAfter(json, "\"explanation\":", argsIdx);
                resp.toolCallType = extractStringAfter(json, "\"type\":", argsIdx);
            }
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
