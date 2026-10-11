package com.cyk.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>敏感数据脱敏（JH-SPEC-001 CC-4 / P0-2 R10）</h1>
 *
 * <p>审计、崩溃日志、轨迹都必须经此脱敏。核心断言：<b>明文密钥不得出现在结果里</b>，
 * 同时保留足够线索（前几位）让用户能辨认是哪个凭据——全掩死会让排查无从下手。</p>
 */
class SensitiveDataRedactorTest {

    @Test
    @DisplayName("sk- 系密钥被脱敏，明文不再出现（安全核心属性）")
    void skKeyIsRedacted() {
        // api_key="sk-..." 会被 k=v 规则整体掩掉；核心属性是「明文密钥不得保留」
        String input = "config api_key=\"sk-X9P5iGFuSY4tHUHFExMhZ4pM7RAiT\" 其它内容";
        String out = SensitiveDataRedactor.redact(input);

        assertThat(out).doesNotContain("sk-X9P5iGFuSY4tHUHFExMhZ4pM7RAiT");
        assertThat(out).contains("******");// 已掩码
        assertThat(out).contains("其它内容");// 非敏感部分原样保留
    }

    @Test
    @DisplayName("裸 sk- 密钥（非 k=v 上下文）保留前缀便于辨认")
    void bareSkKeyKeepsPrefix() {
        String out = SensitiveDataRedactor.redact("token is sk-X9P5iGFuSY4tHUHFExMhZ4pM7RAiT here");
        assertThat(out).doesNotContain("X9P5iGFuSY4tHUHFExMhZ4pM7RAiT");
        assertThat(out).contains("sk-").contains("******");
    }

    @Test
    @DisplayName("Bearer / Authorization 头被脱敏：token 明文消失")
    void bearerIsRedacted() {
        String input = "curl -H \"Authorization: Bearer abc123def456ghi789\" https://x";
        String out = SensitiveDataRedactor.redact(input);

        assertThat(out).as("Bearer token 明文不得保留").doesNotContain("abc123def456ghi789");
        assertThat(out).contains("******");
    }

    @Test
    @DisplayName("password= / token= / secret= 等 k=v 被脱敏")
    void keyValuePairIsRedacted() {
        assertThat(SensitiveDataRedactor.redact("mysql -u root -ppassword=Sup3rSecret!"))
                .doesNotContain("Sup3rSecret");
        assertThat(SensitiveDataRedactor.redact("export TOKEN=abcdef123456"))
                .doesNotContain("abcdef123456");
        assertThat(SensitiveDataRedactor.redact("api_key=abcdef123456"))
                .doesNotContain("abcdef123456");
    }

    @Test
    @DisplayName("AWS Access Key ID 被脱敏")
    void awsKeyIsRedacted() {
        String out = SensitiveDataRedactor.redact("AKIAIOSFODNN7EXAMPLE");
        assertThat(out).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(out).contains("AKIA");// 保留供应商前缀
    }

    @Test
    @DisplayName("URL 内嵌凭据 user:pass@host 被脱敏")
    void urlCredentialIsRedacted() {
        String out = SensitiveDataRedactor.redact("https://admin:secretpw@db.example.com/mydb");
        assertThat(out).doesNotContain("secretpw");
        assertThat(out).contains("admin:").contains("@db.example.com");
    }

    @Test
    @DisplayName("无敏感内容的普通命令原样返回")
    void benignCommandUnchanged() {
        String input = "git status && mvn clean package -DskipTests";
        assertThat(SensitiveDataRedactor.redact(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("null/空安全")
    void nullSafe() {
        assertThat(SensitiveDataRedactor.redact(null)).isNull();
        assertThat(SensitiveDataRedactor.redact("")).isEmpty();
    }

    @Test
    @DisplayName("mayContainSecret 快速判定：敏感命中、普通命令不命中")
    void mayContainSecretHeuristic() {
        assertThat(SensitiveDataRedactor.mayContainSecret("api_key=sk-abc123")).isTrue();
        assertThat(SensitiveDataRedactor.mayContainSecret("Bearer xyz")).isTrue();
        assertThat(SensitiveDataRedactor.mayContainSecret(null)).isFalse();
    }

    @Test
    @DisplayName("短值（<6）全掩，避免前缀+后缀泄露比例过高")
    void shortValueFullyMasked() {
        String out = SensitiveDataRedactor.redact("api_key=abc");
        assertThat(out).doesNotContain("abc");
    }
}
