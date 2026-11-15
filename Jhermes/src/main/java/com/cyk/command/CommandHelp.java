package com.cyk.command;

import picocli.CommandLine;

/** Shared help flag; usageHelp skips required-argument checks and command execution. */
public final class CommandHelp {
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "查看用法")
    private boolean help;
}
