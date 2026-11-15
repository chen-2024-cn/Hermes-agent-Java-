package com.cyk.command;

import com.cyk.HermesAgent;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandBehaviorTest {
    private record Result(int exit, String out, String err) { }

    private static Result execute(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = HermesAgent.commandLine()
                .setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
        return new Result(cli.execute(args), out.toString(), err.toString());
    }

    @Test void onlySupportedCommandsAreRegistered() {
        CommandLine cli = new CommandLine(new HermesAgent());
        assertThat(cli.getSubcommands().keySet()).containsExactlyInAnyOrder("chat", "sessions", "resume", "debug");
        assertThat(cli.getSubcommands().get("debug").getSubcommands().keySet()).containsExactly("tools-json");
    }

    @Test void helpWorksAtEveryRemainingLevelWithoutBusinessExecution() {
        for (String[] path : List.of(new String[]{"debug"}, new String[]{"debug", "tools-json"},
                new String[]{"chat"}, new String[]{"sessions"}, new String[]{"resume"})) {
            for (String help : List.of("-h", "--help")) {
                String[] args = Arrays.copyOf(path, path.length + 1);
                args[path.length] = help;
                Result result = execute(args);
                assertThat(result.exit()).as(String.join(" ", args)).isZero();
                assertThat(result.err()).isEmpty();
                assertThat(result.out()).contains("Usage: Jhermes " + String.join(" ", path));
                if (path[0].equals("debug")) {
                    assertThat(result.out()).contains("示例:");
                    assertThat(result.out().lines().count()).isLessThanOrEqualTo(24);
                }
            }
        }
    }

    @Test void debugWithoutSubcommandShowsOnlyToolInspection() {
        Result result = execute("debug");
        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("Usage: Jhermes debug", "tools-json")
                .doesNotContain("tool-eval", "tool-compare");
    }

    @Test void removedCommandsAreRejectedBeforeBusinessExecution() {
        for (String[] path : List.of(new String[]{"audit"}, new String[]{"security"},
                new String[]{"debug", "tool-eval"}, new String[]{"debug", "tool-compare"})) {
            Result result = execute(path);
            assertThat(result.exit()).as(String.join(" ", path)).isEqualTo(2);
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).isNotBlank();
            String[] args = Arrays.copyOf(path, path.length + 1);
            args[path.length] = "--help";
            Result withHelp = execute(args);
            // Registration, not documentation alone, is the source of supported commands.
            assertThat(withHelp.exit()).isEqualTo(2);
            assertThat(withHelp.out()).isEmpty();
        }
    }

    @Test void rootHelpListsRemainingCommandGroups() {
        Result result = execute("--help");
        assertThat(result.exit()).isZero();
        String commands = result.out().substring(result.out().indexOf("Commands:"));
        assertThat(commands).contains("chat", "sessions", "resume", "debug")
                .doesNotContain("audit", "security", "tool-eval", "tool-compare");
    }
}
