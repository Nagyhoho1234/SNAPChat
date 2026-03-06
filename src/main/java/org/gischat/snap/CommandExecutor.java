package org.gischat.snap;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Executes SNAP GPT commands or Python/snappy scripts.
 */
public class CommandExecutor {

    private static final String GPT_PATH = findGpt();

    public static ExecutionResult runGpt(String command) {
        try {
            // GPT commands run as external processes
            String fullCommand = GPT_PATH + " " + command;
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", fullCommand);
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
            // Write code to temp file and run with SNAP's Python
            Path tempScript = Files.createTempFile("gischat_", ".py");
            Files.writeString(tempScript, code);

            // Try to find snappy-compatible Python
            ProcessBuilder pb = new ProcessBuilder("python", tempScript.toString());
            pb.redirectErrorStream(true);

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

    private static String findGpt() {
        // Try common SNAP GPT locations
        String[] candidates = {
                System.getProperty("snap.home", "") + File.separator + "bin" + File.separator + "gpt.exe",
                "C:\\Program Files\\esa-snap\\bin\\gpt.exe",
                "gpt" // fallback to PATH
        };
        for (String path : candidates) {
            if (new File(path).exists()) return path;
        }
        return "gpt";
    }

    public record ExecutionResult(boolean success, String output, String error) {
        @Override
        public String toString() {
            if (success) return output.isBlank() ? "Completed successfully." : output.trim();
            return "Error: " + (error != null ? error : "Unknown") + "\n" + output;
        }
    }
}
