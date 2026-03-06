package org.gischat.snap;

import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.windows.TopComponent;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;

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

        String userMsg = text;
        new Thread(() -> {
            try {
                LlmResponse response = llm.send(userMsg, mapContext);
                SwingUtilities.invokeLater(() -> handleResponse(response));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("Error: " + e.getMessage());
                    if (e.getMessage() != null && (e.getMessage().contains("tool_result") || e.getMessage().contains("tool_use"))) {
                        llm.clearHistory();
                        appendSystem("Conversation history reset. You can continue chatting.");
                    }
                    setProcessing(false);
                });
            }
        }, "GISChat-LLM").start();
    }

    private void handleResponse(LlmResponse response) {
        if (response.hasToolCall() && "run_gpt".equals(response.toolCallName)) {
            String explanation = response.toolCallExplanation != null ? response.toolCallExplanation : "";
            String command = response.toolCallCommand != null ? response.toolCallCommand : "";
            String type = response.toolCallType != null ? response.toolCallType : "gpt";

            String display = !explanation.isEmpty() ? explanation : response.text;
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
                    sendToolResult(response.toolCallId, "Cancelled by user.");
                    return;
                }
            }

            // Execute
            CommandExecutor.ExecutionResult result = "python".equals(type)
                    ? CommandExecutor.runPython(command)
                    : CommandExecutor.runGpt(command);
            appendResult(result.toString(), result.success());

            sendToolResult(response.toolCallId, result.toString());
            return;
        }

        if (!response.text.isEmpty()) {
            appendMsg("GIS Chat", response.text, "#2E7D32");
        }
        setProcessing(false);
    }

    private void sendToolResult(String toolCallId, String result) {
        new Thread(() -> {
            try {
                LlmResponse followUp = llm.sendToolResult(toolCallId, result, pendingMapContext);
                SwingUtilities.invokeLater(() -> {
                    if (!followUp.text.isEmpty()) {
                        appendMsg("GIS Chat", followUp.text, "#2E7D32");
                    }
                    setProcessing(false);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("Error: " + e.getMessage());
                    setProcessing(false);
                });
            }
        }, "GISChat-ToolResult").start();
    }

    // ---- HTML rendering ----

    private void appendMsg(String label, String text, String color) {
        chatHtml.append("<p style='margin:6px 0 2px 0;'><b style='color:").append(color).append(";'>")
                .append(esc(label)).append("</b></p>")
                .append("<p style='margin:0 0 4px 8px; white-space:pre-wrap;'>").append(esc(text)).append("</p>");
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
        updateDisplay();
    }

    private void updateDisplay() {
        chatDisplay.setText("<html><body style='font-family:Segoe UI,sans-serif; font-size:13px; padding:6px;'>"
                + chatHtml + "</body></html>");
        SwingUtilities.invokeLater(() -> chatDisplay.setCaretPosition(chatDisplay.getDocument().getLength()));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
