package com.cyk.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TokenEstimator} 测试（JH-SPEC-001 P0-4 R1）。
 *
 * <p>估算器的规则很简单：token 数 = ⌈字符数 ÷ 2.5⌉。这里把边界情况都钉死：
 * 空输入、刚好整除、需要向上取整、负数。</p>
 */
class TokenEstimatorTest {

    @Test
    void nullAndEmpty_shouldBeZero() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    @Test
    void shouldDivideBy2point5_andRoundUp() {
        // 25 个字符 ÷ 2.5 = 10，刚好整除
        assertThat(TokenEstimator.fromChars(25)).isEqualTo(10);
        // 26 个字符 ÷ 2.5 = 10.4，向上取整为 11
        assertThat(TokenEstimator.fromChars(26)).isEqualTo(11);
        // 5 个字符 ÷ 2.5 = 2
        assertThat(TokenEstimator.fromChars(5)).isEqualTo(2);
    }

    @Test
    void singleChar_shouldCountAsAtLeastOneToken() {
        // 向上取整的意义：1 个字符 ÷ 2.5 = 0.4，但不能当成 0（否则预算会被低估）
        assertThat(TokenEstimator.fromChars(1)).isEqualTo(1);
        assertThat(TokenEstimator.estimate("a")).isEqualTo(1);
    }

    @Test
    void negativeOrZeroChars_shouldBeZero() {
        assertThat(TokenEstimator.fromChars(0)).isZero();
        assertThat(TokenEstimator.fromChars(-10)).isZero();
    }

    @Test
    void estimate_shouldMatchFromCharsOfLength() {
        String text = "你好，Jhermes！这是一段混合中英文的测试文本。";
        assertThat(TokenEstimator.estimate(text)).isEqualTo(TokenEstimator.fromChars(text.length()));
    }

    @Test
    void baselineReference_6150TokensCorrespondsToAbout15375Chars() {
        // Phase 0 基线：满配首轮 6,150 tokens、2.5 字符/token → 约 15,375 字符。
        // 反向验证校准系数没有被误改。
        assertThat(TokenEstimator.fromChars(15_375)).isEqualTo(6_150);
    }
}
