package org.gischat.snap;

import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.windows.TopComponent;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

@TopComponent.Description(
        preferredID = "GISChatTopComponent",
        persistenceType = TopComponent.PERSISTENCE_ALWAYS
)
@TopComponent.Registration(mode = "properties", openAtStartup = false)
@ActionID(category = "Window", id = "org.gischat.snap.ChatTopComponent")
@ActionReference(path = "Menu/Tools", position = 1000)
@TopComponent.OpenActionRegistration(displayName = "GIS Chat", preferredID = "GISChatTopComponent")
public class ChatTopComponent extends TopComponent {

    private final LlmService llm = new LlmService();
    private final JEditorPane chatDisplay;
    private final JTextField inputField;
    private final JButton sendButton;
    private final JLabel statusLabel;
    private final StringBuilder chatHtml = new StringBuilder();
    private String pendingMapContext = "";
    private volatile boolean isProcessing = false;
    private int toolDepth = 0;
    private static final int MAX_TOOL_ROUND_TRIPS = 15;

    public ChatTopComponent() {
        setName("GIS Chat");
        setToolTipText("AI-powered chat assistant for SNAP");
        setLayout(new BorderLayout(4, 4));

        // Status bar
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        statusLabel = new JLabel("Ready");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setForeground(Color.GRAY);
        JButton clearBtn = new JButton("Clear");
        clearBtn.setMargin(new Insets(1, 6, 1, 6));
        clearBtn.addActionListener(e -> clearChat());
        JButton settingsBtn = new JButton("Settings");
        settingsBtn.setMargin(new Insets(1, 6, 1, 6));
        settingsBtn.addActionListener(e -> openSettings());
        statusBar.add(statusLabel);
        statusBar.add(Box.createHorizontalGlue());
        statusBar.add(clearBtn);
        statusBar.add(settingsBtn);
        add(statusBar, BorderLayout.NORTH);

        // Chat display
        chatDisplay = new JEditorPane();
        chatDisplay.setEditorKit(new HTMLEditorKit());
        chatDisplay.setEditable(false);
        chatDisplay.setContentType("text/html");
        chatDisplay.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        chatDisplay.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        JScrollPane scrollPane = new JScrollPane(chatDisplay);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        add(scrollPane, BorderLayout.CENTER);

        // Input area
        JPanel inputPanel = new JPanel(new BorderLayout(4, 0));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputField = new JTextField();
        inputField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        inputField.addActionListener(e -> sendMessage());
        sendButton = new JButton("Send");
        sendButton.setBackground(new Color(21, 101, 192));
        sendButton.setForeground(Color.WHITE);
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD));
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        add(inputPanel, BorderLayout.SOUTH);

        // Welcome message
        appendSystem("Welcome! Using " + ChatSettings.getProvider().displayName + ".\n"
                + "Ask me to perform remote sensing tasks. For example:\n"
                + "  \"Apply radiometric calibration to this product\"\n"
                + "  \"Calculate NDVI from bands B8 and B4\"\n"
                + "  \"Subset this product to a bounding box\"");
    }

    private void clearChat() {
        chatHtml.setLength(0);
        chatDisplay.setText("");
        llm.clearHistory();
        appendSystem("Chat cleared. Using " + ChatSettings.getProvider().displayName + ".");
    }

    private void openSettings() {
        SettingsDialog dlg = new SettingsDialog(SwingUtilities.getWindowAncestor(this));
        dlg.setVisible(true);
        statusLabel.setText("Provider: " + ChatSettings.getProvider().displayName);
    }

    private void setProcessing(boolean active) {
        isProcessing = active;
        sendButton.setEnabled(!active);
        inputField.setEnabled(!active);
        statusLabel.setText(active ? "Thinking..." : "Connected to " + ChatSettings.getProvider().displayName);
        statusLabel.setForeground(active ? new Color(255, 160, 0) : new Color(76, 175, 80));
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty() || isProcessing) return;

        appendMsg("You", text, "#1565C0");
        inputField.setText("");
        setProcessing(true);

        LlmProvider provider = ChatSettings.getProvider();
        if (provider.needsKey && ChatSettings.getApiKey().isBlank()) {
            appendSystem("API key not set for " + provider.displayName
                    + ".\nGo to Settings to configure.");
            setProcessing(false);
            return;
        }

        String mapContext = SnapContextService.getContext();
        pendingMapContext = mapContext;
        toolDepth = 0;

        String userMsg = text;
        new Thread(() -> {
            try {
                LlmResponse response = llm.send(userMsg, mapContext);
                SwingUtilities.invokeLater(() -> handleResponse(response));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("Error: " + e.getMessage());
                    if (e.getMessage() != null && (e.getMessage().contains("tool_result") || e.getMessage().contains("tool_use"))) {
                        llm.rollbackHistory(1);
                        appendSystem("History sync error — last message rolled back. Please try again.");
                    }
                    setProcessing(false);
                });
            }
        }, "GISChat-LLM").start();
    }

    private void handleResponse(LlmResponse response) {
        if (!response.hasToolCall()) {
            if (!response.text.isEmpty()) {
                appendMsg("GIS Chat", response.text, "#2E7D32");
            }
            setProcessing(false);
            return;
        }

        if (toolDepth >= MAX_TOOL_ROUND_TRIPS) {
            appendSystem("Stopped: too many consecutive tool calls.");
            setProcessing(false);
            return;
        }

        // Execute ALL tool calls and collect results
        List<LlmService.ToolResult> toolResults = new ArrayList<>();

        for (int i = 0; i < response.toolCalls.size(); i++) {
            var tc = response.toolCalls.get(i);
            if (!"run_gpt".equals(tc.name)) {
                toolResults.add(new LlmService.ToolResult(tc.id, "Unknown tool: " + tc.name));
                continue;
            }

            String explanation = tc.explanation != null ? tc.explanation : "";
            String command = tc.command != null ? tc.command : "";
            // Always auto-detect type from content. The parsed tc.type is unreliable
            // because "type" appears many times in the JSON (tool_use, text, etc.)
            // and the hand-rolled parser grabs the wrong one.
            String type = detectCodeType(command);

            // Show text before first tool call
            if (i == 0 && !response.text.isEmpty()) {
                appendMsg("GIS Chat", response.text, "#2E7D32");
            }

            String display = !explanation.isEmpty() ? explanation : "Executing...";
            appendMsg("GIS Chat", display, "#2E7D32");

            if (ChatSettings.getShowGeneratedCode() && !command.isEmpty()) {
                appendCode(command);
            }

            if (ChatSettings.getConfirmBeforeExecute()) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Execute this operation?\n\n" + explanation,
                        "GIS Chat - Confirm", JOptionPane.YES_NO_OPTION);
                if (choice != JOptionPane.YES_OPTION) {
                    appendResult("Cancelled by user.", false);
                    toolResults.add(new LlmService.ToolResult(tc.id, "Cancelled by user."));
                    continue;
                }
            }

            // Execute
            CommandExecutor.ExecutionResult result = "python".equals(type)
                    ? CommandExecutor.runPython(command)
                    : CommandExecutor.runGpt(command);
            appendResult(result.toString(), result.success());
            toolResults.add(new LlmService.ToolResult(tc.id, result.toString()));
        }

        // Send ALL tool results back and process follow-up recursively
        toolDepth++;
        sendToolResults(toolResults);
    }

    private void sendToolResults(List<LlmService.ToolResult> results) {
        // Check if any result is an error — update status to show we're fixing it
        boolean hasError = results.stream().anyMatch(r -> r.result().startsWith("Error:"));
        if (hasError) {
            SwingUtilities.invokeLater(() -> statusLabel.setText("Fixing error... (attempt " + (toolDepth + 1) + ")"));
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText("Processing follow-up..."));
        }
        new Thread(() -> {
            try {
                LlmResponse followUp = llm.sendToolResults(results, pendingMapContext);
                SwingUtilities.invokeLater(() -> handleResponse(followUp));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("Error: " + e.getMessage());
                    if (e.getMessage() != null && (e.getMessage().contains("tool_result") || e.getMessage().contains("tool_use"))) {
                        llm.rollbackHistory(1);
                        appendSystem("History sync error — last message rolled back. Please try again.");
                    }
                    setProcessing(false);
                });
            }
        }, "GISChat-ToolResult").start();
    }

    // ---- HTML rendering ----

    private void appendMsg(String label, String text, String color) {
        chatHtml.append("<p style='margin:6px 0 2px 0;'><b style='color:").append(color).append(";'>")
                .append(esc(label)).append("</b></p>")
                .append("<div style='margin:0 0 4px 8px;'>").append(markdownToHtml(text)).append("</div>");
        updateDisplay();
    }

    private void appendSystem(String text) {
        chatHtml.append("<p style='margin:4px 0; color:#888; font-style:italic;'>").append(esc(text)).append("</p>");
        updateDisplay();
    }

    private void appendCode(String code) {
        chatHtml.append("<pre style='background:#F5F5F5; border:1px solid #DDD; padding:6px; margin:2px 8px; font-size:12px;'>")
                .append(esc(code)).append("</pre>");
        updateDisplay();
    }

    private void appendResult(String text, boolean success) {
        String color = success ? "#2E7D32" : "#C62828";
        String prefix = success ? "Result" : "Error";
        chatHtml.append("<p style='margin:2px 8px; color:").append(color).append("; font-size:12px;'><b>")
                .append(prefix).append(":</b> ").append(esc(text)).append("</p>");
        if (!success && toolDepth < MAX_TOOL_ROUND_TRIPS) {
            chatHtml.append("<p style='margin:2px 8px; color:#F57C00; font-size:12px; font-style:italic;'>")
                    .append("Analyzing error and working on a fix...</p>");
        }
        updateDisplay();
    }

    private void updateDisplay() {
        chatDisplay.setText("<html><body style='font-family:Segoe UI,sans-serif; font-size:13px; padding:6px;'>"
                + chatHtml + "</body></html>");
        SwingUtilities.invokeLater(() -> chatDisplay.setCaretPosition(chatDisplay.getDocument().getLength()));
    }

    /**
     * Auto-detect whether code is Python or a GPT command.
     * This is the PRIMARY type detection — never rely on the parsed type field.
     *
     * GPT commands are single-line, starting with a SNAP operator name
     * (e.g. "Subset -Pregion=... -t output.dim input.dim").
     *
     * Everything else (multi-line code, Python keywords, etc.) is Python.
     */
    private static String detectCodeType(String code) {
        if (code == null || code.isBlank()) return "gpt";
        String trimmed = code.trim();

        // Multi-line = always Python (GPT commands are single-line)
        if (trimmed.contains("\n")) return "python";

        // Python keywords / patterns
        String lower = trimmed.toLowerCase();
        if (trimmed.startsWith("import ") || trimmed.startsWith("from ")
                || trimmed.startsWith("#") || trimmed.startsWith("def ")
                || trimmed.startsWith("class ") || trimmed.startsWith("if ")
                || trimmed.startsWith("for ") || trimmed.startsWith("while ")
                || lower.contains("print(") || lower.contains("os.path")
                || lower.contains("os.makedirs") || lower.contains("open(")
                || lower.contains("ee.initialize") || lower.contains("ee.image")
                || lower.contains("numpy") || lower.contains("rasterio")
                || lower.contains("productio") || lower.contains("esa_snappy")
                || lower.contains("subprocess") || lower.contains("gdal")
                || lower.contains("pip._internal") || lower.contains("requests.get")) {
            return "python";
        }

        // Valid GPT commands start with a known operator name pattern:
        // single word or hyphenated word, followed by space and flags/paths
        // e.g. "Subset -Pregion=..." or "Terrain-Correction -t ..."
        if (trimmed.matches("^[A-Z][a-zA-Z-]+(\\s.*)?$")) {
            return "gpt";
        }

        // Default: if it doesn't look like a GPT operator, assume Python
        return "python";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Convert basic markdown to HTML for display in JEditorPane.
     * Handles: headers, bold, inline code, code blocks, lists, tables, line breaks.
     */
    private static String markdownToHtml(String md) {
        String escaped = esc(md);
        StringBuilder html = new StringBuilder();
        String[] lines = escaped.split("\n");
        boolean inCodeBlock = false;
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Code blocks (```)
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    html.append("</pre>");
                    inCodeBlock = false;
                } else {
                    html.append("<pre style='background:#F5F5F5; border:1px solid #DDD; padding:6px; margin:4px 0; font-size:12px;'>");
                    inCodeBlock = true;
                }
                continue;
            }
            if (inCodeBlock) {
                html.append(line).append("\n");
                continue;
            }

            // Skip table separator rows (|---|---|)
            if (trimmed.matches("\\|[\\s\\-:|]+\\|")) {
                continue;
            }

            // Table rows (| col | col |)
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                if (!inTable) {
                    html.append("<table style='border-collapse:collapse; margin:4px 0; font-size:12px;'>");
                    inTable = true;
                }
                html.append("<tr>");
                String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|");
                for (String cell : cells) {
                    html.append("<td style='border:1px solid #CCC; padding:2px 6px;'>").append(cell.trim()).append("</td>");
                }
                html.append("</tr>");
                continue;
            } else if (inTable) {
                html.append("</table>");
                inTable = false;
            }

            // Headers
            if (trimmed.startsWith("### ")) {
                html.append("<p style='margin:6px 0 2px 0; font-weight:bold; font-size:13px;'>").append(trimmed.substring(4)).append("</p>");
                continue;
            }
            if (trimmed.startsWith("## ")) {
                html.append("<p style='margin:8px 0 2px 0; font-weight:bold; font-size:14px;'>").append(trimmed.substring(3)).append("</p>");
                continue;
            }
            if (trimmed.startsWith("# ")) {
                html.append("<p style='margin:8px 0 2px 0; font-weight:bold; font-size:15px;'>").append(trimmed.substring(2)).append("</p>");
                continue;
            }

            // Horizontal rule
            if (trimmed.equals("---") || trimmed.equals("***")) {
                html.append("<hr style='margin:4px 0;'>");
                continue;
            }

            // List items
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                String content = inlineFormat(trimmed.substring(2));
                html.append("<p style='margin:1px 0 1px 16px;'>&bull; ").append(content).append("</p>");
                continue;
            }
            if (trimmed.matches("^\\d+\\.\\s.*")) {
                String content = inlineFormat(trimmed.replaceFirst("^\\d+\\.\\s", ""));
                html.append("<p style='margin:1px 0 1px 16px;'>").append(trimmed.split("\\s", 2)[0]).append(" ").append(content).append("</p>");
                continue;
            }

            // Blockquote
            if (trimmed.startsWith("&gt; ")) {
                html.append("<p style='margin:2px 0; padding-left:8px; border-left:3px solid #CCC; color:#666;'>")
                        .append(inlineFormat(trimmed.substring(5))).append("</p>");
                continue;
            }

            // Empty line
            if (trimmed.isEmpty()) {
                html.append("<br>");
                continue;
            }

            // Normal paragraph
            html.append("<p style='margin:2px 0;'>").append(inlineFormat(line)).append("</p>");
        }

        if (inCodeBlock) html.append("</pre>");
        if (inTable) html.append("</table>");
        return html.toString();
    }

    /** Convert inline markdown: **bold**, `code`, file paths. */
    private static String inlineFormat(String text) {
        // Bold: **text**
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        // Inline code: `text`
        text = text.replaceAll("`(.+?)`", "<code style='background:#F0F0F0; padding:0 3px; font-size:12px;'>$1</code>");
        return text;
    }
}
