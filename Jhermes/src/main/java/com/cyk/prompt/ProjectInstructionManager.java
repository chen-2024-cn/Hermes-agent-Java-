package com.cyk.prompt;

import java.util.function.Consumer;

/** Refresh only at a new task boundary; a model/tool turn always sees one immutable snapshot. */
public final class ProjectInstructionManager {
    private final ProjectInstructionLoader loader;
    private ProjectInstructions snapshot;

    public ProjectInstructionManager(ProjectInstructionLoader loader) {
        this.loader = loader;
        snapshot = loader.load();
    }

    public ProjectInstructions snapshot() { return snapshot; }

    public boolean refresh(Consumer<String> output) {
        ProjectInstructions next = loader.load();
        if (next.equals(snapshot)) return false;
        boolean promptChanged = !next.renderPrompt().equals(snapshot.renderPrompt());
        snapshot = next;
        output.accept(snapshot.startupStatus());
        for (var document : next.documents()) {
            if (!document.ready()) output.accept(document.source() + ": " + document.status());
        }
        return promptChanged;
    }

    public void initialize(Consumer<String> output) {
        output.accept("目的: 在项目根目录创建 JHERMES.md 固定模板。\n"
                + "预期结果: 新建可手工填写的文件; 不覆盖已有规则, 不调用模型。\n"
                + loader.initialize());
    }
}
