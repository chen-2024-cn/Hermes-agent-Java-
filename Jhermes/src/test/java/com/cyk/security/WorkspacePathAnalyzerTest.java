package com.cyk.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>工作区路径语义分析测试（JH-SPEC-001 P0-2 R9 + 平台感知缺陷修复）</h1>
 *
 * <p>本类锁定三类契约：</p>
 * <ol>
 *   <li><b>缺陷修复生效</b>：Windows cmd 开关（{@code /t}）与非路径环境变量
 *       （{@code %date%}）不再被判为越界——这是「Windows 上取个日期都要确认」的根因；</li>
 *   <li><b>保守性未被放宽</b>：真实越界（{@code ..}、{@code ~}、{@code %userprofile%}、
 *       {@code %temp%}、绝对路径 {@code /tmp}）仍判越界；未知环境变量仍判越界；
 *       Unix 语义零变化（{@code /t} 在非 Windows 平台仍是绝对路径）；</li>
 *   <li><b>灾难目标判定完全未受影响</b>：{@code /t} 这类短 token 在递归删除场景
 *       仍按灾难目标 BLOCKED——升档豁免<b>只</b>作用于"越界一档"，不动最严的那条线。</li>
 * </ol>
 */
class WorkspacePathAnalyzerTest {

    private static final Path WORKDIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /** Windows 语义下的越界判定。 */
    private static boolean escapesOnWindows(String raw) {
        return WorkspacePathAnalyzer.escapesWorkspace(raw, WORKDIR, true);
    }

    /** Unix 语义（等价于旧的两参重载）下的越界判定。 */
    private static boolean escapesOnUnix(String raw) {
        return WorkspacePathAnalyzer.escapesWorkspace(raw, WORKDIR, false);
    }

    // =========================================================================
    // 缺陷修复：Windows cmd 开关
    // =========================================================================

    @Test
    @DisplayName("Windows：/t、/s、/f 等 1~2 字符开关不算路径（date /t 因此不再升档）")
    void windowsSwitchesAreNotPaths() {
        for (String sw : new String[]{"/t", "/s", "/f", "/q", "/v", "/a", "/ad", "/nh", "/T", "/S"}) {
            assertThat(escapesOnWindows(sw))
                    .as("Windows 下 '%s' 是参数开关，不应判为越界", sw).isFalse();
        }
    }

    @Test
    @DisplayName("C-2：Unix 语义零变化——/t 仍是绝对路径、仍判越界")
    void unixSemanticsUnchangedForShortSlashTokens() {
        assertThat(escapesOnUnix("/t")).as("Unix 上 /t 是绝对路径").isTrue();
        assertThat(escapesOnUnix("/s")).isTrue();
        // 旧的两参重载等价于 unix 语义（兼容既有调用方）
        assertThat(WorkspacePathAnalyzer.escapesWorkspace("/t", WORKDIR)).isTrue();
    }

    @Test
    @DisplayName("限长 1~2 字符：/tmp、/etc、/var、/usr 等真实路径仍按路径处理（两平台一致）")
    void realAbsolutePathsStillDetectedOnBothPlatforms() {
        for (String real : new String[]{"/tmp", "/etc", "/var", "/usr", "/bin", "/dev", "/home",
                "/tmp/x.txt", "/etc/passwd", "/var/log"}) {
            assertThat(escapesOnWindows(real))
                    .as("'%s' 是真实绝对路径，Windows 下也不得豁免", real).isTrue();
            assertThat(escapesOnUnix(real)).as("'%s' Unix 下应判越界", real).isTrue();
        }
    }

    // =========================================================================
    // 环境变量三态（C-3：未知即保守）
    // =========================================================================

    @Test
    @DisplayName("非路径变量 %date%/%time%/%cd% 不判越界（cmd 与 PowerShell 两种写法一致）")
    void nonPathEnvVarsDoNotEscape() {
        for (String v : new String[]{"%date%", "%time%", "%cd%", "%random%", "%errorlevel%",
                "%username%", "%os%", "%computername%", "%DATE%", "%Time%"}) {
            assertThat(escapesOnWindows(v)).as("'%s' 是标量值不是位置，不应判越界", v).isFalse();
            assertThat(escapesOnUnix(v)).as("'%s' 两平台结论应一致", v).isFalse();
        }
        // PowerShell 写法（$env:）同样识别
        assertThat(escapesOnWindows("$env:date")).isFalse();
        assertThat(escapesOnWindows("$env:path")).isFalse();
    }

    @Test
    @DisplayName("已知指向工作区外的变量仍判越界：%userprofile%/%temp%/%appdata%/%windir%")
    void outsideWorkspaceEnvVarsStillEscape() {
        for (String v : new String[]{"%userprofile%", "%temp%", "%tmp%", "%appdata%",
                "%localappdata%", "%windir%", "%systemroot%", "%programfiles%"}) {
            assertThat(escapesOnWindows(v)).as("'%s' 指向工作区外，必须仍判越界", v).isTrue();
            assertThat(escapesOnUnix(v)).as("'%s' 两平台结论应一致", v).isTrue();
        }
        assertThat(escapesOnWindows("$env:userprofile")).isTrue();
        assertThat(escapesOnWindows("$env:temp")).isTrue();
        assertThat(escapesOnWindows("$env:APPDATA")).as("大小写无关").isTrue();
    }

    @Test
    @DisplayName("C-3：未知环境变量保守判越界（宁可误报，绝不因不认识而放宽）")
    void unknownEnvVarsStayConservative() {
        for (String v : new String[]{"%myvar%", "%custom_home%", "%deploy_root%", "$env:WHATEVER"}) {
            assertThat(escapesOnWindows(v)).as("未知变量 '%s' 必须保守判越界", v).isTrue();
            assertThat(escapesOnUnix(v)).as("未知变量 '%s' 两平台一致", v).isTrue();
        }
    }

    @Test
    @DisplayName("嵌入式引用同样识别：%temp%\\x.txt 与 /var/%cd%/x 不会因前后缀漏判")
    void embeddedEnvVarsAreDetected() {
        assertThat(escapesOnWindows("%temp%\\x.txt")).as("嵌入式 %temp% 仍判越界").isTrue();
        assertThat(escapesOnWindows("C:\\Users\\%username%\\docs"))
                .as("绝对路径优先于变量判定，仍判越界").isTrue();
        // %cd% 是当前目录（即工作区本身）：嵌在更长路径里也不算越界
        assertThat(escapesOnWindows("%cd%\\sub")).isFalse();
    }

    // =========================================================================
    // 保守性：家目录 / 相对穿越 未受影响
    // =========================================================================

    @Test
    @DisplayName("~ 与 .. 穿越在两平台都仍判越界（本次修复未触碰这两类判定）")
    void homeAndTraversalStillEscape() {
        for (String p : new String[]{"~", "~/x", "~\\x", "$home", "${home}",
                "..", "../", "..\\", "../secret/config.yml"}) {
            assertThat(escapesOnWindows(p)).as("'%s' Windows 下应判越界", p).isTrue();
            assertThat(escapesOnUnix(p)).as("'%s' Unix 下应判越界", p).isTrue();
        }
    }

    @Test
    @DisplayName("工作区内相对路径不判越界（不误伤正常操作）")
    void inWorkspaceRelativePathsDoNotEscape() {
        for (String p : new String[]{"pom.xml", "./target", "src/main/java", "build"}) {
            assertThat(escapesOnWindows(p)).as("'%s' 在工作区内", p).isFalse();
            assertThat(escapesOnUnix(p)).isFalse();
        }
    }

    @Test
    @DisplayName("空/null 输入安全返回 false（不崩溃、不升档）")
    void nullAndBlankAreSafe() {
        assertThat(WorkspacePathAnalyzer.escapesWorkspace(null, WORKDIR, true)).isFalse();
        assertThat(WorkspacePathAnalyzer.escapesWorkspace("   ", WORKDIR, true)).isFalse();
        assertThat(WorkspacePathAnalyzer.escapesWorkspace(null, WORKDIR)).isFalse();
    }

    @Test
    @DisplayName("引号包裹的路径先脱引号再判定（\"/tmp\" 与 /tmp 结论一致）")
    void quotedPathsAreUnwrappedBeforeAnalysis() {
        assertThat(escapesOnWindows("\"/tmp\"")).isTrue();
        assertThat(escapesOnWindows("'/tmp'")).isTrue();
        assertThat(escapesOnWindows("\"pom.xml\"")).isFalse();
    }

    // =========================================================================
    // 灾难目标判定：刻意未放宽（安全边界的最后一道）
    // =========================================================================

    @Test
    @DisplayName("isCatastrophicDeleteTarget 完全未变：递归删除仍按最严档处理")
    void catastrophicTargetsAreDeliberatelyUnchanged() {
        // 本次的开关豁免只作用于「越界升一档」，不作用于灾难目标——
        // /t 在递归删除场景仍视为绝对路径目标（BLOCKED）。这是有意保留的保守性：
        // 少一次确认的收益，不值得为它放宽最严的那条线。
        for (String catastrophic : new String[]{"/", "/*", "~", "$HOME", "%userprofile%",
                "C:\\", "c:/", "/tmp", "/t", ".", "./"}) {
            assertThat(WorkspacePathAnalyzer.isCatastrophicDeleteTarget(catastrophic))
                    .as("'%s' 仍须判为递归删除的灾难目标", catastrophic).isTrue();
        }
        // 工作区内目标不是灾难（AC3 误杀防护的既有契约）
        assertThat(WorkspacePathAnalyzer.isCatastrophicDeleteTarget("./target")).isFalse();
        assertThat(WorkspacePathAnalyzer.isCatastrophicDeleteTarget("build")).isFalse();
        assertThat(WorkspacePathAnalyzer.isCatastrophicDeleteTarget(null)).isFalse();
    }
}
