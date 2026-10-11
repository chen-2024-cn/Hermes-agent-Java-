package com.cyk.tool;

import com.cyk.config.HermesConfig;
import com.cyk.security.SecurityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>TerminalTool 命令门禁集成（JH-SPEC-001 P0-2 AC5/AC7）</h1>
 *
 * <p>在真实 {@code runCommand} 路径上验证分级处置（不执行任何危险命令）：</p>
 * <ul>
 *   <li>BLOCKED → 硬拦，返回错误，绝不到达 ProcessBuilder；</li>
 *   <li>DANGEROUS/WRITE 在<b>非交互</b>环境（surefire 无 {@code System.console()}）→ 一律拒绝，
 *       返回可读错误给模型（AC5）；</li>
 *   <li>敏感路径命令 → 硬拦。</li>
 * </ul>
 *
 * <p>测试初始化 SecurityContext 时关闭审计（{@code security.audit_enabled=false}），
 * 避免向真实 {@code ~/.jhermes/logs} 写测试条目；用例结束后 {@code resetForTest()} 复位，
 * 不污染其它测试类的惰性默认策略。</p>
 */
class TerminalToolPolicyIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void initSecurityContext() {
        Map<String, Object> cfg = new HashMap<>();
        Map<String, Object> security = new HashMap<>();
        security.put("audit_enabled", false);// 不写真实审计文件
        cfg.put("security", security);
        cfg.put("tools", Map.of("terminal_enabled", true));
        SecurityContext.init(new HermesConfig(null, cfg));
    }

    @AfterEach
    void resetSecurityContext() {
        SecurityContext.resetForTest();
        TerminalTool.resetCommandCounts();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String command) throws Exception {
        String json = TerminalTool.runCommand(Map.of("command", command));
        return MAPPER.readValue(json, Map.class);
    }

    @Test
    @DisplayName("BLOCKED（rm -rf /）被硬拦，不执行，返回策略拦截错误")
    void blockedCommandIsRefusedWithoutExecuting() throws Exception {
        Map<String, Object> r = run("rm -rf /");

        assertThat(r).containsKey("error");
        assertThat((String) r.get("error")).contains("安全策略拦截");
        assertThat(r).as("被拦命令不应有执行结果字段").doesNotContainKey("exit_code");
    }

    @Test
    @DisplayName("BLOCKED（format C:）被硬拦")
    void formatDiskIsRefused() throws Exception {
        Map<String, Object> r = run("format C:");

        assertThat((String) r.get("error")).contains("安全策略拦截");
    }

    @Test
    @DisplayName("AC5：DANGEROUS 在非交互环境被拒，返回错误给模型")
    void dangerousDeniedInNonInteractive() throws Exception {
        // surefire 环境 System.console()==null → 非交互 → 需确认即拒
        Map<String, Object> r = run("git push -f origin main");

        assertThat(r).containsKey("error");
        assertThat((String) r.get("error")).contains("非交互");
    }

    @Test
    @DisplayName("AC5：WRITE（未知命令）在非交互环境被拒")
    void writeDeniedInNonInteractive() throws Exception {
        Map<String, Object> r = run("npm run build");

        assertThat(r).containsKey("error");
        assertThat((String) r.get("error")).contains("非交互");
    }

    @Test
    @DisplayName("敏感路径命令被 SensitivePathGuard 硬拦（AC8 在 run_command 路径）")
    void sensitivePathRefused() throws Exception {
        Map<String, Object> r = run("Get-Content ~/.ssh/id_rsa");

        assertThat((String) r.get("error")).contains("敏感凭据");
    }

    @Test
    @DisplayName("策略文件目录被拒（模型不得改写自己的规则，AC8）")
    void policyFileWriteRefused() throws Exception {
        Map<String, Object> r = run("echo x >> ~/.jhermes/security/rules.yaml");

        assertThat(r).containsKey("error");
        assertThat((String) r.get("error")).contains("敏感");
    }

    @Test
    @DisplayName("--yolo 默认关闭（AC7：默认不放行）")
    void yoloDefaultsOff() {
        assertThat(new HermesConfig(null, new HashMap<>()).isYolo())
                .as("--yolo 缺省必须为关").isFalse();
        assertThat(new HermesConfig(null, new HashMap<>()).isAuditPolicyOnly())
                .as("--policy=audit 缺省必须为关").isFalse();
    }

    @Test
    @DisplayName("空命令仍被拒（参数校验先于策略）")
    void blankCommandRejected() throws Exception {
        Map<String, Object> r = run("   ");
        assertThat(r).containsKey("error");
    }
}
