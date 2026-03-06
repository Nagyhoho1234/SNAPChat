package org.gischat.snap;

import javax.swing.*;
import java.awt.*;

public class SettingsDialog extends JDialog {

    private final JComboBox<String> providerCombo;
    private final JPasswordField apiKeyField;
    private final JLabel apiKeyHelp;
    private final JComboBox<String> modelCombo;
    private final JTextField endpointField;
    private final JLabel endpointLabel;
    private final JSpinner maxTokensSpinner;
    private final JCheckBox confirmCheck;
    private final JCheckBox showCodeCheck;

    public SettingsDialog(Window owner) {
        super(owner, "GIS Chat - Settings", ModalityType.APPLICATION_MODAL);
        setLayout(new BorderLayout());
        setMinimumSize(new Dimension(450, 380));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        // Provider
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("AI Provider:"), gbc);
        providerCombo = new JComboBox<>();
        for (LlmProvider p : LlmProvider.values()) providerCombo.addItem(p.displayName);
        providerCombo.addActionListener(e -> onProviderChanged());
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(providerCombo, gbc);
        row++;

        // API Key
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("API Key:"), gbc);
        apiKeyField = new JPasswordField(30);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(apiKeyField, gbc);
        row++;

        apiKeyHelp = new JLabel();
        apiKeyHelp.setFont(apiKeyHelp.getFont().deriveFont(10f));
        apiKeyHelp.setForeground(Color.GRAY);
        gbc.gridx = 1; gbc.gridy = row;
        form.add(apiKeyHelp, gbc);
        row++;

        // Model
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Model:"), gbc);
        modelCombo = new JComboBox<>();
        modelCombo.setEditable(true);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(modelCombo, gbc);
        row++;

        // Endpoint
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        endpointLabel = new JLabel("Endpoint URL:");
        form.add(endpointLabel, gbc);
        endpointField = new JTextField(30);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(endpointField, gbc);
        row++;

        // Max tokens
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel("Max Tokens:"), gbc);
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(4096, 256, 32768, 256));
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(maxTokensSpinner, gbc);
        row++;

        // Options
        confirmCheck = new JCheckBox("Ask for confirmation before executing");
        gbc.gridx = 1; gbc.gridy = row;
        form.add(confirmCheck, gbc);
        row++;

        showCodeCheck = new JCheckBox("Show generated code in chat");
        gbc.gridx = 1; gbc.gridy = row;
        form.add(showCodeCheck, gbc);

        add(form, BorderLayout.CENTER);

        // Buttons
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okBtn = new JButton("OK");
        okBtn.addActionListener(e -> save());
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        buttons.add(okBtn);
        buttons.add(cancelBtn);
        add(buttons, BorderLayout.SOUTH);

        loadSettings();
        onProviderChanged();
        pack();
        setLocationRelativeTo(owner);
    }

    private void onProviderChanged() {
        int idx = providerCombo.getSelectedIndex();
        LlmProvider p = LlmProvider.values()[idx];

        apiKeyField.setVisible(p.needsKey);
        apiKeyHelp.setText(p.keyHelp);
        apiKeyHelp.setVisible(true);

        boolean showEndpoint = p == LlmProvider.Ollama || p == LlmProvider.OpenAICompatible;
        endpointField.setVisible(showEndpoint);
        endpointLabel.setVisible(showEndpoint);
        if (showEndpoint && endpointField.getText().isBlank()) {
            endpointField.setText(p.defaultEndpoint);
        }

        modelCombo.removeAllItems();
        for (String m : p.defaultModels) modelCombo.addItem(m);
    }

    private void loadSettings() {
        LlmProvider provider = ChatSettings.getProvider();
        providerCombo.setSelectedIndex(provider.ordinal());
        apiKeyField.setText(ChatSettings.getApiKey());

        String model = ChatSettings.getModel();
        modelCombo.setSelectedItem(model);

        endpointField.setText(ChatSettings.getEndpoint());
        maxTokensSpinner.setValue(ChatSettings.getMaxTokens());
        confirmCheck.setSelected(ChatSettings.getConfirmBeforeExecute());
        showCodeCheck.setSelected(ChatSettings.getShowGeneratedCode());
    }

    private void save() {
        int idx = providerCombo.getSelectedIndex();
        ChatSettings.setProvider(LlmProvider.values()[idx]);
        ChatSettings.setApiKey(new String(apiKeyField.getPassword()).trim());
        ChatSettings.setModel(((String) modelCombo.getSelectedItem()).trim());
        ChatSettings.setEndpoint(endpointField.getText().trim());
        ChatSettings.setMaxTokens((Integer) maxTokensSpinner.getValue());
        ChatSettings.setConfirmBeforeExecute(confirmCheck.isSelected());
        ChatSettings.setShowGeneratedCode(showCodeCheck.isSelected());
        dispose();
    }
}
