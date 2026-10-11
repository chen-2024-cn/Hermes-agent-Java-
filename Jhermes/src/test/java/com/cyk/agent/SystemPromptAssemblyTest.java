package com.cyk.agent;

import com.cyk.agent.AgentTestSupport.ScriptedLlm;
import com.cyk.bean.ModelMessage;
import com.cyk.config.HermesConfig;
import com.cyk.manager.MemoryManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>Agent 系统提示词装配快照测试（JH-SPEC-001 P0-3 期 3 AC1/AC2 的接线级证据）</h1>
 *
 * <p>与 {@code PromptComposerTest}（纯单元、桩注册表）互补：本测试走<b>真实接线</b>——
 * Agent 构造 → {@code ToolRegistry.initialize(testConfig)} 真实注册
 * （terminal_enabled=false、rag.enabled=false）→ {@code installSystemPrompt()} →
 * 检查装入对话历史首位的 system 消息全文。</p>
 *
 * <p>核心断言（对应报备的验收映射）：</p>
 * <ul>
 *   <li>AC2：RAG/终端未注册 → 装配结果不含任何 rag_ 前缀与 run_command 字样；</li>
 *   <li>旧「## Available Tools」文本清单已删（与 tools 数组纯重复）；</li>
 *   <li>旧 SESSION_SEARCH 指引已删（指向不存在的跨会话检索能力，R2）；</li>
 *   <li>合并去重后「执行规范」「终端输出规范」各只出现一次（旧版语义重复三处）；</li>
 *   <li>capabilities 权威清单如实反映注册实况；</li>
 *   <li>无 {{...}} 模板残留。</li>
 * </ul>
 *
 * <p>不对记忆尾段与技能索引内容做断言：MemoryManager/SkillManager 是全局单例，
 * 读取真实 {@code ~/.jhermes} 数据，CI 环境不可控（装配正确性已由 composer 单测覆盖）。</p>
 */
class SystemPromptAssemblyTest {

    @TempDir
    Path dataDir;

    private String systemPromptOfNewAgent() {
        ScriptedLlm llm = new ScriptedLlm();
        HermesConfig config = AgentTestSupport.testConfig(); // terminal 禁用 + rag 关闭
        Agent agent = AgentTestSupport.newAgent(dataDir, llm, config);

        agent.installSystemPrompt();

        ModelMessage system = agent.conversationHistory().get(0);
        assertThat(system.getRole()).isEqualTo("system");
        return system.getContent();
    }

    /**
     * 剔除动态记忆尾段后的装配结果。
     *
     * <p>MemoryManager 是全局单例，读真实 {@code ~/.jhermes} 的 MEMORY.md——
     * 开发机的用户记忆可能恰好含 rag_search 这类字样（实测就碰上了），
     * 而记忆尾段属于<b>用户数据</b>不在 AC2「装配指引不引用未注册工具」的约束范围
     *（若用户记忆里提到某工具而它未注册，模型调用会得到「未找到工具」并自行纠正）。
     * 故所有「不得引用」断言均先剔除此段再执行。</p>
     */
    private String promptWithoutMemoryTail(String prompt) {
        String memory = MemoryManager.getInstance().getSystemPromptSnapshot().strip();
        return memory.isEmpty() ? prompt : prompt.replace(memory, "");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int idx = text.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = text.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    @Test
    @DisplayName("AC2：rag/terminal 未注册 → 装配指引零引用其工具名与旧指引")
    void unregisteredCapabilitiesLeaveNoTraceInPrompt() {
        String prompt = promptWithoutMemoryTail(systemPromptOfNewAgent());

        // rag.enabled=false → RagEngine 不建、rag 工具不注册 → rag 段与清单均不得出现
        assertThat(prompt).doesNotContain("rag_search", "rag_list", "rag_index", "rag_delete");
        assertThat(prompt).doesNotContain("知识库检索与管理");
        // terminal_enabled=false → run_command 未注册 → 终端指引段与清单均不得出现
        assertThat(prompt).doesNotContain("run_command");
        assertThat(prompt).doesNotContain("终端命令执行（run_command）");
    }

    @Test
    @DisplayName("重复清除：Available Tools 清单与 SESSION_SEARCH 指引不再存在")
    void duplicatedAndPhantomSectionsAreGone() {
        String prompt = promptWithoutMemoryTail(systemPromptOfNewAgent());

        // 与 function-calling tools 数组纯重复的文本清单（旧版 1,548 字符）已删
        assertThat(prompt).doesNotContain("## Available Tools");
        // 指向不存在能力的旧指引（R2）已删
        assertThat(prompt).doesNotContain("跨会话上下文");
        // 无模板残留
        assertThat(prompt).doesNotContain("{{");
        // 无旧平台提示的遗留话术（cli 提示已并入 terminal-output 段）
        assertThat(prompt).doesNotContain("MEDIA:/absolute/path");
    }

    @Test
    @DisplayName("合并去重：执行规范与终端输出规范各只出现一次，关键规则未丢失")
    void mergedSectionsAppearExactlyOnceWithKeyRules() {
        String prompt = promptWithoutMemoryTail(systemPromptOfNewAgent());

        // 旧版 TOOL_USE_ENFORCEMENT + EXECUTION_DISCIPLINE 合并为 execution 段
        assertThat(occurrences(prompt, "执行规范（必须严格遵守）")).isEqualTo(1);
        assertThat(prompt).contains("【试错熔断】")
                .contains("【禁止凭记忆与心算作答，必须调用工具】")
                .contains("【前置检查与结果核验】")
                .contains("【直接执行，不反复问询】");
        // 旧版 TERMINAL_OUTPUT + PLATFORM_HINTS[cli] 合并为 terminal-output 段
        assertThat(occurrences(prompt, "终端输出规范（必须遵守）")).isEqualTo(1);
        assertThat(prompt).contains("禁止使用任何 Markdown 语法");
        // 旧版「工具使用强制规则」独立标题不再出现（内容已并入执行规范）
        assertThat(prompt).doesNotContain("工具使用强制规则");
    }

    @Test
    @DisplayName("capabilities 权威清单如实反映注册实况（AC1/AC3 同源前提）")
    void capabilitiesSectionMirrorsRegistry() {
        String prompt = promptWithoutMemoryTail(systemPromptOfNewAgent());

        assertThat(prompt).contains("当前可用能力（唯一权威清单）");
        // testConfig 下真实注册的工具集必须在清单里（字典序渲染）
        assertThat(prompt).contains("- file_operations: grep_files, read_file, search_files, write_file");
        assertThat(prompt).contains("- web: fetch_page, web_search");
        assertThat(prompt).contains("- memory: memory_delete, memory_get, memory_replace, memory_save");
        assertThat(prompt).contains("- skill: skill_manage");
        // 未注册的工具集不得出现在清单里
        assertThat(prompt).doesNotContain("- rag:").doesNotContain("- terminal:");
        // R5 硬约束在场（断言稳定语义片段，不绑定完整句式，便于文案微调）
        assertThat(prompt).contains("硬约束").contains("只能声称与使用清单内的工具")
                .contains("严禁编造工具名");
    }

    @Test
    @DisplayName("恒注入段与已注册能力段全部在场，顺序符合 manifest")
    void allExpectedSectionsPresentInManifestOrder() {
        String prompt = systemPromptOfNewAgent();

        int identity = prompt.indexOf("你是Jhermes智能体");
        int workdir = prompt.indexOf("## 当前工作环境");
        int execution = prompt.indexOf("执行规范（必须严格遵守）");
        int termOut = prompt.indexOf("终端输出规范（必须遵守）");
        int memory = prompt.indexOf("你拥有跨会话持久记忆功能");
        int capabilities = prompt.indexOf("当前可用能力（唯一权威清单）");

        assertThat(identity).isZero(); // 身份恒为首段
        assertThat(workdir).isGreaterThan(identity);
        assertThat(execution).isGreaterThan(workdir);
        assertThat(termOut).isGreaterThan(execution);
        assertThat(memory).isGreaterThan(termOut); // memory 工具集已注册 → 指引在场
        assertThat(capabilities).isGreaterThan(memory);

        // skill_manage 已注册 → 技能沉淀指引在场（重写版指向 skill_manage）
        assertThat(prompt).contains("技能沉淀与复用");
        // 工作目录快照来自真实 user.dir（surefire 工作目录 = 模块根）
        assertThat(prompt).contains("工作目录（当前项目根目录）: ")
                .contains("pom.xml");
    }
}
