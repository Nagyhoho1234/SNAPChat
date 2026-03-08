package org.gischat.snap;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes SNAP GPT commands or Python/snappy scripts.
 */
public class CommandExecutor {

    private static final String GPT_PATH = findGpt();
    private static final String SNAP_HOME = findSnapHome();

    public static ExecutionResult runGpt(String command) {
        try {
            // Split command string into arguments, respecting quoted strings
            List<String> args = new ArrayList<>();
            args.add(GPT_PATH);
            args.addAll(splitCommand(command));

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            pb.environment().put("JAVA_HOME", System.getProperty("java.home"));

            Process process = pb.start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return new ExecutionResult(true, output.isEmpty() ? "Command completed successfully." : output, null);
            } else {
                return new ExecutionResult(false, output, "GPT exited with code " + exitCode);
            }
        } catch (Exception e) {
            return new ExecutionResult(false, "", "Failed to execute GPT: " + e.getMessage());
        }
    }

    public static ExecutionResult runPython(String code) {
        try {
            Path tempScript = Files.createTempFile("gischat_", ".py");
            Files.writeString(tempScript, code);

            ProcessBuilder pb = new ProcessBuilder("python", tempScript.toString());
            pb.redirectErrorStream(true);

            // Set SNAP_HOME so esa_snappy can find the SNAP installation
            if (SNAP_HOME != null && !SNAP_HOME.isBlank()) {
                pb.environment().put("SNAP_HOME", SNAP_HOME);
            }

            Process process = pb.start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            Files.deleteIfExists(tempScript);

            if (exitCode == 0) {
                return new ExecutionResult(true, output.isEmpty() ? "Script completed successfully." : output, null);
            } else {
                return new ExecutionResult(false, output, "Python exited with code " + exitCode);
            }
        } catch (Exception e) {
            return new ExecutionResult(false, "", "Failed to execute Python: " + e.getMessage());
        }
    }

    /**
     * Find the GPT executable. Returns an unquoted absolute path.
     * ProcessBuilder handles spaces in paths natively — no quoting needed.
     */
    private static String findGpt() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String gptName = isWindows ? "gpt.exe" : "gpt";
        String sep = File.separator;

        String[] candidates = {
                // 1. SNAP system property (most reliable — set by SNAP itself)
                System.getProperty("snap.home", "") + sep + "bin" + sep + gptName,
                // 2. Common Windows install locations
                "C:\\Program Files\\esa-snap\\bin\\gpt.exe",
                "C:\\Program Files (x86)\\esa-snap\\bin\\gpt.exe",
                // 3. Common Linux/Mac locations
                "/usr/local/snap/bin/gpt",
                "/opt/snap/bin/gpt",
                System.getProperty("user.home", "") + "/esa-snap/bin/gpt",
                // 4. Fallback to PATH
                isWindows ? "gpt.exe" : "gpt",
        };
        for (String path : candidates) {
            if (!path.isBlank() && new File(path).exists()) {
                return path;  // No quoting — ProcessBuilder handles spaces
            }
        }
        return isWindows ? "gpt.exe" : "gpt";
    }

    /**
     * Find the SNAP installation directory for setting SNAP_HOME.
     */
    private static String findSnapHome() {
        // 1. System property (set by SNAP when running inside SNAP Desktop)
        String snapHome = System.getProperty("snap.home", "");
        if (!snapHome.isBlank() && new File(snapHome).isDirectory()) return snapHome;

        // 2. Derive from GPT path
        File gptFile = new File(GPT_PATH);
        if (gptFile.exists()) {
            File snapDir = gptFile.getParentFile().getParentFile(); // gpt.exe -> bin -> esa-snap
            if (snapDir != null && snapDir.isDirectory()) return snapDir.getAbsolutePath();
        }

        // 3. Common locations
        String[] candidates = {
                "C:\\Program Files\\esa-snap",
                "C:\\Program Files (x86)\\esa-snap",
                "/usr/local/snap",
                "/opt/snap",
                System.getProperty("user.home", "") + "/esa-snap",
        };
        for (String path : candidates) {
            if (new File(path).isDirectory()) return path;
        }
        return "";
    }

    /**
     * Split a command string into arguments, respecting quoted strings.
     * E.g. {@code CreateStack -t "C:\path with spaces\out.dim"} splits correctly.
     */
    private static List<String> splitCommand(String command) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) args.add(current.toString());
        return args;
    }

    public record ExecutionResult(boolean success, String output, String error) {
        @Override
        public String toString() {
            if (success) return output.isBlank() ? "Completed successfully." : output.trim();
            return "Error: " + (error != null ? error : "Unknown") + "\n" + output;
        }
    }
}
