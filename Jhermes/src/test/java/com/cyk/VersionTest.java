package com.cyk;

import com.cyk.constant.Constants;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>版本单一事实源校验（JH-SPEC-001 P0-1 R2 / AC2）</h1>
 *
 * <p>改造目标：pom {@code <version>} 是唯一事实源，构建期 resource filtering
 * 生成 classpath 根下的 {@code version.properties}，{@link Constants#VERSION}
 * 与 picocli {@code --version} 输出都从它读取。历史上版本号曾在
 * Constants（2.1.0）、pom（1.0-SNAPSHOT）、发布 tag（v2.1.0）三处漂移。</p>
 *
 * <p>本测试把三处钉在一起：任何一处改动未同步，CI 立即失败。</p>
 */
class VersionTest {

    /** 从 pom.xml 提取项目自身 <version>（artifactId 之后出现的第一个 version 元素，容忍其间夹注释）。 */
    private static String pomVersion() throws Exception {
        // surefire 的工作目录即模块根（Jhermes/）
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        // 本 pom 无 <parent>，故 artifactId=Jhermes 之后的第一个 <version> 即项目版本。
        // (?s) 开 DOTALL 以跨过 artifactId 与 version 之间可能存在的 XML 注释块。
        Matcher m = Pattern
                .compile("(?s)<artifactId>Jhermes</artifactId>.*?<version>([^<]+)</version>")
                .matcher(pom);
        assertThat(m.find()).as("pom.xml 应包含 Jhermes 自身的 <version>").isTrue();
        return m.group(1).trim();
    }

    @Test
    void constantsVersionMustEqualPomVersion() throws Exception {
        assertThat(Constants.VERSION)
                .as("Constants.VERSION 必须来自构建产物 version.properties（单一事实源 = pom <version>）；"
                        + "若为 \"dev\" 说明 classpath 缺少 version.properties（未经 Maven 构建或 filtering 配置被破坏）")
                .isEqualTo(pomVersion());
    }

    @Test
    void cliVersionFlagMustPrintPomVersion() throws Exception {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new HermesAgent());
        cmd.setOut(new PrintWriter(sw));

        int exit = cmd.execute("--version");

        assertThat(exit).isZero();
        // picocli 的 --version 输出经 versionProvider（JhermesVersionProvider），
        // 期望形如 "Jhermes 2.1.0"
        assertThat(sw.toString()).contains(pomVersion());
        assertThat(sw.toString()).contains(Constants.CLI_NAME);
    }
}
