package com.cyk.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>Constants 净化守卫（JH-SPEC-001 P0-3 AC5）</h1>
 *
 * <p>期 3 把全部长文本提示词从 {@code Constants} 迁出到 {@code resources/prompts/}
 * 之后，本测试是防止「长文本回流」的常驻守卫——两道断言：</p>
 * <ol>
 *   <li><b>静态扫描</b>：src/main/java 全目录不得再出现旧提示词常量的命名特征
 *       （以 GUIDANCE 为后缀的常量名、平台提示映射名）——不只是 Constants，
 *       任何类里重新硬编码提示词都会被抓住；</li>
 *   <li><b>反射断言</b>：Constants 仅存的字符串字段必须是短标量（版本/目录名/CLI 名等），
 *       不含换行、长度受限；且不得再有 Map 类型字段（平台提示的形态特征）。</li>
 * </ol>
 *
 * <p>为什么需要常驻守卫而不是一次性验收：提示词硬编码是「顺手就能犯」的倒退——
 * 未来加一条新指引时最省事的写法就是往 Constants 塞个字符串。CI 红灯会把
 * 贡献者导向正确路径（resources/prompts/*.md + manifest.yaml）。</p>
 */
class ConstantsPurgeTest {

    /** 单个真常量允许的最大长度（版本号/目录名/文件名级别；留足余量防误报）。 */
    private static final int MAX_CONSTANT_LENGTH = 100;

    @Test
    @DisplayName("src/main/java 全目录无提示词常量命名特征残留（静态扫描）")
    void mainSourcesShouldContainNoGuidanceConstants() throws IOException {
        Path mainSourceRoot = Path.of("src/main/java"); // surefire 工作目录 = 模块根
        assertThat(mainSourceRoot).as("测试须从模块根目录运行").exists();

        List<Path> offenders;
        try (Stream<Path> paths = Files.walk(mainSourceRoot)) {
            offenders = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> containsGuidanceNaming(p))
                    .toList();
        }
        assertThat(offenders)
                .as("以下源文件重新出现了提示词常量命名特征（提示词应放 resources/prompts/*.md）")
                .isEmpty();
    }

    private static boolean containsGuidanceNaming(Path javaFile) {
        try {
            String text = Files.readString(javaFile, StandardCharsets.UTF_8);
            // 命名特征：*_GUIDANCE 常量与平台提示映射 PLATFORM_HINTS。
            // 用命名特征而非「长字符串」做判据：短文案常量（如 UI 提示语）是合法的，
            // 会被下方反射断言的长度上限另行把守。
            return text.contains("_GUIDANCE") || text.contains("PLATFORM_HINTS");
        } catch (IOException e) {
            throw new UncheckedIOException("读取源文件失败: " + javaFile, e);
        }
    }

    @Test
    @DisplayName("Constants 反射断言：字符串常量均为短标量，无 Map 字段")
    void constantsFieldsShouldBeShortScalarsOnly() {
        for (Field field : Constants.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertThat(field.getType())
                    .as("Constants 不得再持有 Map 类型常量（平台提示映射的形态）")
                    .isNotEqualTo(Map.class);

            if (field.getType() == String.class) {
                String value;
                try {
                    value = (String) field.get(null);
                } catch (IllegalAccessException e) {
                    throw new AssertionError("反射读取 Constants." + field.getName() + " 失败", e);
                }
                if (value == null) {
                    continue;
                }
                assertThat(value)
                        .as("Constants.%s 应为单行短常量（提示词/长文本请在 resources/prompts 维护）",
                                field.getName())
                        .doesNotContain("\n");
                assertThat(value.length())
                        .as("Constants.%s 长度应 <= %d", field.getName(), MAX_CONSTANT_LENGTH)
                        .isLessThanOrEqualTo(MAX_CONSTANT_LENGTH);
            }
        }
    }

    @Test
    @DisplayName("Constants 保留的真常量仍在（防过度删除）")
    void essentialConstantsShouldSurvivePurge() {
        // 净化只针对提示词长文本；版本、目录、配置文件名、CLI 名、超时缺一不可
        assertThat(Constants.VERSION).isNotBlank();
        assertThat(Constants.DEFAULT_HERMES_HOME).isEqualTo(".jhermes");
        assertThat(Constants.LEGACY_HERMES_HOME).isEqualTo(".skhermes");
        assertThat(Constants.DEFAULT_CONFIG_FILE).isEqualTo("config.yaml");
        assertThat(Constants.CLI_NAME).isEqualTo("Jhermes");
        assertThat(Constants.DEFAULT_MAX_ITERATIONS).isPositive();
        assertThat(Constants.DEFAULT_TIMEOUT_SECONDS).isPositive();
        assertThat(Constants.DEFAULT_SKILL_SCRIPT_TIMEOUT_SECONDS).isPositive();

        // 公开静态 String 字段总数应维持在「真常量」量级（当前 6 个左右），
        // 数量激增即提示词回流的早期信号
        long publicStringFields = Arrays.stream(Constants.class.getFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .count();
        assertThat(publicStringFields).isLessThanOrEqualTo(10);
    }
}
