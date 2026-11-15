package com.cyk.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link SensitivePathGuard} 测试：凭据路径黑名单的命中与误伤边界。
 *
 * <p>本类是终端工具与文件工具共享的安全基线，任何 MARKERS 调整都必须在这里补用例——
 * 黑名单最怕的不是「不够全」，而是「改了没人知道另一处行为跟着变了」。</p>
 */
class SensitivePathGuardTest {

    private static final String HOME = System.getProperty("user.home");

    // =========================================================================
    // 命中：各类凭据文件/目录
    // =========================================================================

    @Test
    void shouldDetectSshKeysInBothSlashStyles() {
        assertThat(SensitivePathGuard.check("cat ~/.ssh/id_rsa")).isNotNull();
        assertThat(SensitivePathGuard.check("type C:\\Users\\admin\\.ssh\\id_rsa")).isNotNull();
        assertThat(SensitivePathGuard.checkPath(Paths.get(HOME, ".ssh", "config"))).isNotNull();
    }

    @Test
    void shouldDetectCloudAndVcsCredentials() {
        assertThat(SensitivePathGuard.check("cat ~/.aws/credentials")).isNotNull();
        assertThat(SensitivePathGuard.check("Get-Content $env:USERPROFILE\\.kube\\config")).isNotNull();
        assertThat(SensitivePathGuard.check("cat ~/.docker/config.json")).isNotNull();
        assertThat(SensitivePathGuard.check("type C:\\Users\\admin\\.git-credentials")).isNotNull();
        assertThat(SensitivePathGuard.check("cat ~/.netrc")).isNotNull();
        assertThat(SensitivePathGuard.check("cat ~/.npmrc")).isNotNull();
        assertThat(SensitivePathGuard.check("cat ~/.pypirc")).isNotNull();
        assertThat(SensitivePathGuard.check("cat ~/.gnupg/secring.gpg")).isNotNull();
        assertThat(SensitivePathGuard.check("cat /home/user/.ssh/id_ed25519")).isNotNull();
    }

    @Test
    void shouldDetectAiToolsAndHermesConfig() {
        // 真实事故来源：2026-09-28 模型 Get-Content 了 .meta.json 导致 Feishu token 进对话历史
        assertThat(SensitivePathGuard.check("Get-Content C:\\Users\\admin\\.ai_tools\\.meta.json -Raw")).isNotNull();
        assertThat(SensitivePathGuard.check("cat ~/.jhermes/config.yaml")).isNotNull();
        assertThat(SensitivePathGuard.checkPath(Paths.get(HOME, ".jhermes", "config.yaml"))).isNotNull();
    }

    @Test
    void shouldBeCaseInsensitiveBecauseWindowsPathsAre() {
        assertThat(SensitivePathGuard.check("TYPE C:\\USERS\\ADMIN\\.SSH\\ID_RSA")).isNotNull();
        assertThat(SensitivePathGuard.check("Get-Content ~/.NPMRC")).isNotNull();
    }

    // =========================================================================
    // 不命中：右边界校验防子串误伤
    // =========================================================================

    @Test
    void shouldNotFlagLookalikeNames() {
        // .sshare 含 .ssh 字样但不是 SSH 目录；aws-cli-guide 含 aws 但不是 credentials
        assertThat(SensitivePathGuard.check("dir C:\\projects\\.sshare")).isNull();
        assertThat(SensitivePathGuard.check("echo aws-cli-guide")).isNull();
        assertThat(SensitivePathGuard.check("git status")).isNull();
        assertThat(SensitivePathGuard.check("mvn clean package")).isNull();
        // 项目自己的源码路径绝不能被拦（否则 read_file 直接残废）
        assertThat(SensitivePathGuard.checkPath(
                Paths.get(HOME, "Desktop", "Hermes-agent-Java-", "Jhermes", "src", "main", "java"))).isNull();
    }

    @Test
    void shouldTreatNullAndEmptyAsSafe() {
        // 工具参数缺失时不应抛 NPE，统一按「安全」放行，由后续 Files 操作给出真实的文件不存在错误
        assertThat(SensitivePathGuard.check(null)).isNull();
        assertThat(SensitivePathGuard.check("")).isNull();
        assertThat(SensitivePathGuard.checkPath(null)).isNull();
        assertThat(SensitivePathGuard.isSensitivePath(null)).isFalse();
    }

    // =========================================================================
    // P0-2 R11 新增标记：环境变量密钥 / 私钥证书 / 云凭据 / 自我保护目录
    // =========================================================================

    @Test
    void shouldDetectEnvFilesAndKeyMaterial() {
        // .env 系列（右边界校验：.env 后跟 '/' 或结尾命中，".environment" 不误伤）
        assertThat(SensitivePathGuard.check("Get-Content .env")).isNotNull();
        assertThat(SensitivePathGuard.check("cat .env.production")).isNotNull();
        assertThat(SensitivePathGuard.check("cat config/.env.local")).isNotNull();
        // 私钥与密钥库材料
        assertThat(SensitivePathGuard.check("Get-Content server.pem")).isNotNull();
        assertThat(SensitivePathGuard.check("type tls.key")).isNotNull();
        assertThat(SensitivePathGuard.check("keytool -list -keystore app.jks")).isNotNull();
        assertThat(SensitivePathGuard.check("cat cert.p12")).isNotNull();
    }

    @Test
    void shouldDetectCloudVendorCredentialDirs() {
        assertThat(SensitivePathGuard.check("cat ~/.azure/accessTokens.json")).isNotNull();
        assertThat(SensitivePathGuard.check("cat ~/.config/gcloud/credentials.db")).isNotNull();
        assertThat(SensitivePathGuard.check(
                "type $env:APPDATA\\gcloud\\application_default_credentials.json")).isNotNull();
    }

    @Test
    void shouldProtectPolicyAndAuditDirsFromModelTampering() {
        // R11：模型不得读写自己的命令规则文件，也不得篡改/删除审计留痕
        assertThat(SensitivePathGuard.check(
                "echo evil > ~/.jhermes/security/rules.yaml")).isNotNull();
        assertThat(SensitivePathGuard.check(
                "Get-Content ~/.jhermes/security/rules.yaml")).isNotNull();
        assertThat(SensitivePathGuard.check(
                "Remove-Item ~/.jhermes/logs/audit-2026-10-09.jsonl")).isNotNull();
        assertThat(SensitivePathGuard.checkPath(
                Paths.get(HOME, ".jhermes", "security", "rules.yaml"))).isNotNull();
        assertThat(SensitivePathGuard.isSensitivePath(
                Paths.get(HOME, ".jhermes", "logs"))).isTrue();
    }

    @Test
    void r11MarkersShouldNotFlagLookalikes() {
        // 右边界校验：普通词不得因含 .env/.pem/.key 子串而误伤
        assertThat(SensitivePathGuard.check("cat environment.txt")).isNull();
        assertThat(SensitivePathGuard.check("echo monkey.jpg")).isNull();
        assertThat(SensitivePathGuard.check("cat .keys.json")).isNull();// .key 后跟 s → 不命中
        assertThat(SensitivePathGuard.check("grep pemfile notes.txt")).isNull();
    }

    @Test
    void shouldProtectUserPromptOverrideDirFromModelTampering() {
        // P0-3 D-3：~/.jhermes/prompts 是提示词覆盖通道——模型改写它等价于
        // 植入跨会话持久的提示词注入，读写均须硬拦（两种斜杠风格）
        assertThat(SensitivePathGuard.check(
                "echo evil > ~/.jhermes/prompts/identity.md")).isNotNull();
        assertThat(SensitivePathGuard.check(
                "Set-Content C:\\Users\\admin\\.jhermes\\prompts\\manifest.yaml")).isNotNull();
        assertThat(SensitivePathGuard.checkPath(
                Paths.get(HOME, ".jhermes", "prompts", "execution.md"))).isNotNull();
        assertThat(SensitivePathGuard.isSensitivePath(
                Paths.get(HOME, ".jhermes", "prompts"))).isTrue();
        // 右边界：prompts 后紧跟字母的无关目录不误伤（.jhermes/promptscript 等）
        assertThat(SensitivePathGuard.check("cat ~/.jhermes/promptscript/run.sh")).isNull();
    }

    // =========================================================================
    // 布尔形式与字符串形式必须一致（两套 API 同源于 check）
    // =========================================================================

    @Test
    void booleanFormShouldAgreeWithMarkerForm() {
        String hit = "cat ~/.ssh/id_rsa";
        String safe = "ls ./src";
        assertThat(SensitivePathGuard.isSensitive(hit)).isTrue();
        assertThat(SensitivePathGuard.check(hit)).isNotNull();
        assertThat(SensitivePathGuard.isSensitive(safe)).isFalse();
        assertThat(SensitivePathGuard.check(safe)).isNull();

        Path hitPath = Paths.get(HOME, ".ssh", "id_rsa");
        Path safePath = Paths.get(HOME, "Desktop", "notes.md");
        assertThat(SensitivePathGuard.isSensitivePath(hitPath)).isTrue();
        assertThat(SensitivePathGuard.checkPath(hitPath)).isNotNull();
        assertThat(SensitivePathGuard.isSensitivePath(safePath)).isFalse();
    }

    @Test
    void markersListShouldBeLowercaseAndNonEmpty() {
        // 黑名单自身的一致性：check() 只把入参小写化，标记若含大写将永远匹配不上（静默失效）
        assertThat(SensitivePathGuard.MARKERS).isNotEmpty();
        for (String marker : SensitivePathGuard.MARKERS) {
            assertThat(marker).isNotBlank();
            assertThat(marker).isEqualTo(marker.toLowerCase(java.util.Locale.ROOT));
        }
    }
}
