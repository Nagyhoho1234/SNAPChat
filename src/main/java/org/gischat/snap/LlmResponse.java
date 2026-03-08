package org.gischat.snap;

import java.util.ArrayList;
import java.util.List;

public class LlmResponse {
    public String text = "";
    public List<ToolCallInfo> toolCalls = new ArrayList<>();

    /** Convenience: first tool call or null. */
    public ToolCallInfo firstToolCall() {
        return toolCalls.isEmpty() ? null : toolCalls.get(0);
    }

    public boolean hasToolCall() {
        return !toolCalls.isEmpty();
    }

    // Legacy accessors for backward compatibility
    public String getToolCallId() {
        var tc = firstToolCall();
        return tc != null ? tc.id : null;
    }

    public String getToolCallName() {
        var tc = firstToolCall();
        return tc != null ? tc.name : null;
    }

    public String getToolCallCommand() {
        var tc = firstToolCall();
        return tc != null ? tc.command : null;
    }

    public String getToolCallExplanation() {
        var tc = firstToolCall();
        return tc != null ? tc.explanation : null;
    }

    public String getToolCallType() {
        var tc = firstToolCall();
        return tc != null ? tc.type : null;
    }

    public static class ToolCallInfo {
        public String id;
        public String name;
        public String command;
        public String explanation;
        public String type; // "gpt" or "python"

        public ToolCallInfo(String id, String name, String command, String explanation, String type) {
            this.id = id;
            this.name = name;
            this.command = command;
            this.explanation = explanation;
            this.type = type;
        }
    }
}
