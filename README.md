# GIS Chat for ESA SNAP

AI-powered chat assistant for [ESA SNAP Desktop](https://step.esa.int/main/toolboxes/snap/) that executes remote sensing operations via natural language.

![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)
![SNAP 13.0](https://img.shields.io/badge/SNAP-13.0-green.svg)
![Java 21](https://img.shields.io/badge/Java-21-orange.svg)

## Features

- Chat panel docked inside SNAP Desktop (Tools menu)
- Natural language to GPT (Graph Processing Tool) command translation
- Python/snappy code generation and execution
- Automatic context awareness (open products, bands, CRS, dimensions)
- Confirmation dialog before executing operations

### Supported AI Providers

| Provider | Cost | Notes |
|----------|------|-------|
| Google Gemini | Free tier available | Recommended to start |
| Ollama | Free (local) | Runs on your machine, fully offline |
| Anthropic (Claude) | Paid | claude-sonnet-4-6, claude-opus-4-6 |
| OpenAI (GPT) | Paid | gpt-4o, gpt-4o-mini |
| OpenAI-compatible | Varies | LM Studio, vLLM, any compatible endpoint |

## Installation

### Manual Install

1. Download `org-gischat-snap.jar` from [Releases](../../releases)
2. Copy the JAR to your SNAP user modules directory:
   - **Windows:** `%USERPROFILE%\.snap\snap-desktop\modules\`
   - **Linux/Mac:** `~/.snap/snap-desktop/modules/`
3. Create the module config file at:
   - **Windows:** `%USERPROFILE%\.snap\snap-desktop\config\Modules\org-gischat-snap.xml`
   - **Linux/Mac:** `~/.snap/snap-desktop/config/Modules/org-gischat-snap.xml`

   With this content:
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <!DOCTYPE module PUBLIC "-//NetBeans//DTD Module Status 1.0//EN"
                           "http://www.netbeans.org/dtds/module-status-1_0.dtd">
   <module name="org.gischat.snap">
       <param name="autoload">false</param>
       <param name="eager">false</param>
       <param name="enabled">true</param>
       <param name="jar">modules/org-gischat-snap.jar</param>
       <param name="reloadable">false</param>
   </module>
   ```
4. Restart SNAP Desktop
5. Open via **Tools > GIS Chat**

### Build from Source

Requirements: Java 21+, Maven 3.9+

```bash
git clone https://github.com/Nagyhoho1234/SNAPChat.git
cd SNAPChat
mvn package -DskipTests
```

The JAR is produced at `target/org-gischat-snap.jar`. Copy it to the modules directory and create the config XML as described above.

## Usage

1. Open SNAP and load a satellite product
2. Open **Tools > GIS Chat**
3. Click **Settings** to choose your AI provider and enter an API key (if needed)
4. Type a natural language request, for example:
   - "Apply radiometric calibration to this product"
   - "Calculate NDVI from bands B8 and B4"
   - "Subset this product to a bounding box"
   - "What bands does this product have?"

The assistant reads your current SNAP state (open products, bands, CRS) and generates the appropriate GPT command or Python/snappy code.

## Project Structure

```
SNAPChat/
  pom.xml                         # Maven build (Java 21, SNAP 13.0)
  src/main/java/org/gischat/snap/
    ChatTopComponent.java          # NetBeans TopComponent (UI panel)
    SettingsDialog.java            # Settings dialog (provider, key, model)
    LlmService.java                # Multi-provider LLM client (no dependencies)
    LlmProvider.java               # Provider enum (endpoints, models)
    LlmResponse.java               # Response model with tool call support
    ChatSettings.java              # Persistent settings (java.util.prefs)
    SnapContextService.java        # Reads SNAP state (products, bands, CRS)
    CommandExecutor.java           # Runs GPT commands and Python scripts
  src/main/resources/org/gischat/snap/
    layer.xml                      # NetBeans filesystem registration
    icon24.png                     # Toolbar icon
```

## Security

- API keys are stored locally in Java Preferences (`java.util.prefs`)
- Keys are never transmitted anywhere except to the configured AI provider endpoint
- All LLM communication uses HTTPS (except local Ollama)

## License

[MIT](LICENSE) - Zsolt Zoltan Feher
