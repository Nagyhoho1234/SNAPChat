package org.gischat.snap;

public enum LlmProvider {
    Anthropic("Anthropic (Claude)",
            "https://api.anthropic.com/v1/messages",
            true, "Get your key at console.anthropic.com/settings/keys",
            "claude-sonnet-4-6", "claude-haiku-4-5-20251001", "claude-opus-4-6"),
    OpenAI("OpenAI (GPT)",
            "https://api.openai.com/v1/chat/completions",
            true, "Get your key at platform.openai.com/api-keys",
            "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o3-mini"),
    GoogleGemini("Google Gemini (free tier available)",
            "https://generativelanguage.googleapis.com/v1beta",
            true, "Get your FREE key at aistudio.google.com/apikey",
            "gemini-2.0-flash", "gemini-2.0-pro", "gemini-1.5-flash"),
    Ollama("Ollama (local, completely free)",
            "http://localhost:11434/v1/chat/completions",
            false, "No API key needed! Install Ollama from ollama.com",
            "llama3.1", "mistral", "codellama", "deepseek-coder-v2"),
    OpenAICompatible("OpenAI-compatible (LM Studio, vLLM...)",
            "http://localhost:8080/v1/chat/completions",
            true, "Enter the API key for your endpoint (if required)",
            "default");

    public final String displayName;
    public final String defaultEndpoint;
    public final boolean needsKey;
    public final String keyHelp;
    public final String[] defaultModels;

    LlmProvider(String displayName, String defaultEndpoint, boolean needsKey,
                String keyHelp, String... defaultModels) {
        this.displayName = displayName;
        this.defaultEndpoint = defaultEndpoint;
        this.needsKey = needsKey;
        this.keyHelp = keyHelp;
        this.defaultModels = defaultModels;
    }
}
