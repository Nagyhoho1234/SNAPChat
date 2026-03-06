package org.gischat.snap;

public class LlmResponse {
    public String text = "";
    public String toolCallId;
    public String toolCallName;
    public String toolCallCommand;
    public String toolCallExplanation;
    public String toolCallType; // "gpt" or "python"

    public boolean hasToolCall() {
        return toolCallName != null && toolCallCommand != null;
    }
}
