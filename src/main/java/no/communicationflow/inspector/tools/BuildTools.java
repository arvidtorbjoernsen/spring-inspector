package no.communicationflow.inspector.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.communicationflow.inspector.InspectorProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * MCP tools that invoke Maven commands in the {@code apps/api} directory.
 *
 * <p>Each tool delegates to {@link #runMaven(List, Duration)} which spawns
 * a child process using the {@code mvnw} wrapper, captures merged stdout/stderr,
 * and returns a concise formatted summary.</p>
 *
 * <p>The Maven working directory is resolved from
 * {@link InspectorProperties#getApiDir()} — in VS Code the env var
 * {@code INSPECTOR_API_DIR} is set to {@code ${workspaceFolder}/apps/api}.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BuildTools {

    /** Cap how many lines are returned to Copilot to stay within context limits. */
    private static final int MAX_OUTPUT_LINES = 200;

    private final InspectorProperties props;

    // ─── Tools ───────────────────────────────────────────────────────────────────

    @Tool(description = """
            Run Maven tests for the api module and return the test output.
            Shows compile errors, test results (passed/failed/skipped), and failure details.
            Optionally filter to a specific test class or pattern.
            """)
    public String mvnTest(
            @ToolParam(description = """
                    Test class / method pattern (optional, Surefire -Dtest syntax).
                    Examples: 'UserServiceTest', 'UserService*', 'UserServiceTest#shouldReturnUser'.
                    Leave empty to run all tests.""")
            String testPattern,
            @ToolParam(description = """
                    Extra Maven arguments (optional). Example: '-Dsurefire.failIfNoSpecifiedTests=false'.
                    Leave empty for defaults.""")
            String extraArgs) {
        List<String> cmd = new ArrayList<>(List.of("./mvnw", "test", "-B", "--no-transfer-progress"));
        if (testPattern != null && !testPattern.isBlank()) {
            cmd.add("-Dtest=" + testPattern.trim());
        }
        if (extraArgs != null && !extraArgs.isBlank()) {
            cmd.addAll(List.of(extraArgs.trim().split("\\s+")));
        }
        return runMaven(cmd, Duration.ofMinutes(5));
    }

    @Tool(description = """
            Run the full Maven verify lifecycle for the api module:
            compile → test → package → verify (including JaCoCo coverage gates).
            Use before committing to verify all quality checks pass.
            """)
    public String mvnVerify() {
        return runMaven(List.of("./mvnw", "verify", "-B", "--no-transfer-progress"), Duration.ofMinutes(10));
    }

    @Tool(description = """
            Compile the api module (skips tests) and return compiler output.
            Use this for a fast check of whether changes introduce compile errors.
            """)
    public String mvnCompile() {
        return runMaven(List.of("./mvnw", "compile", "-B", "--no-transfer-progress"), Duration.ofMinutes(3));
    }

    @Tool(description = """
            Run a custom Maven goal or flags in the api module.
            Example goals: 'clean compile', 'dependency:tree', 'checkstyle:check', 'spotbugs:check'.
            """)
    public String mvnRun(
            @ToolParam(description = """
                    Space-separated Maven goals and flags to pass after ./mvnw.
                    Example: 'clean package -DskipTests -Pproduction'""")
            String goalsAndFlags) {
        if (goalsAndFlags == null || goalsAndFlags.isBlank()) {
            return "❌ goalsAndFlags is required. Example: 'clean package -DskipTests'";
        }
        List<String> cmd = new ArrayList<>(List.of("./mvnw"));
        cmd.addAll(List.of(goalsAndFlags.trim().split("\\s+")));
        cmd.add("-B");
        cmd.add("--no-transfer-progress");
        return runMaven(cmd, Duration.ofMinutes(15));
    }

    // ─── Internals ───────────────────────────────────────────────────────────────

    private String runMaven(List<String> cmd, Duration timeout) {
        Path apiDir = resolveApiDir();
        log.info("Running {} in {}", cmd, apiDir);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(apiDir.toFile())
                .redirectErrorStream(true); // merge stderr into stdout

        long startMs = System.currentTimeMillis();
        try {
            Process process = pb.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }

            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return formatResult(cmd, -1, lines, System.currentTimeMillis() - startMs,
                        "⚠️  Process TIMED OUT after " + timeout.toMinutes() + " minutes and was killed.");
            }
            return formatResult(cmd, process.exitValue(), lines, System.currentTimeMillis() - startMs, null);

        } catch (IOException e) {
            return "❌ Failed to start Maven process: " + e.getMessage()
                    + "\n  Working dir: " + apiDir
                    + "\n  Command:     " + String.join(" ", cmd)
                    + "\n  Ensure 'mvnw' exists and is executable (chmod +x mvnw).";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "❌ Interrupted while waiting for Maven.";
        }
    }

    /** Resolve {@code inspector.api-dir} to an absolute path. */
    private Path resolveApiDir() {
        String configured = props.getApiDir();
        if (configured == null || configured.isBlank()) {
            configured = "apps/api";
        }
        Path p = Path.of(configured);
        if (p.isAbsolute() && Files.exists(p.resolve("pom.xml"))) {
            return p;
        }
        Path rel = Path.of(System.getProperty("user.dir")).resolve(configured).normalize();
        if (Files.exists(rel.resolve("pom.xml"))) {
            return rel;
        }
        // Return best-guess and let ProcessBuilder surface a clear error
        return p.isAbsolute() ? p : rel;
    }

    private String formatResult(List<String> cmd, int exitCode, List<String> lines, long elapsedMs, String note) {
        String status = exitCode == 0 ? "✅ SUCCESS" : "❌ FAILED";
        String header = "%s — `%s`  (exit=%d, %.1fs)".formatted(
                status, String.join(" ", cmd), exitCode, elapsedMs / 1000.0);

        // Tail to MAX_OUTPUT_LINES to avoid overflowing Copilot's context
        List<String> tail = lines.size() > MAX_OUTPUT_LINES
                ? lines.subList(lines.size() - MAX_OUTPUT_LINES, lines.size())
                : lines;

        StringJoiner out = new StringJoiner("\n");
        out.add(header);
        if (note != null) out.add(note);
        if (lines.size() > MAX_OUTPUT_LINES) {
            out.add("(showing last %d of %d lines)".formatted(MAX_OUTPUT_LINES, lines.size()));
        }
        out.add("---");
        tail.forEach(out::add);
        return out.toString();
    }
}
