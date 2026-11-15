package com.cyk.command;

import picocli.CommandLine;

/** Diagnostics for the tool definitions exposed to the model. */
@CommandLine.Command(name = "debug", description = "工具诊断",
        footer = "示例: Jhermes debug tools-json",
        subcommands = {DebugToolsJsonCommand.class})
public class DebugCommand implements Runnable {
    @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
    @CommandLine.Mixin private CommandHelp help;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
