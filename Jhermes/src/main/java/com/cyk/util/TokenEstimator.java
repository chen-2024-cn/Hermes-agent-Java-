package com.cyk.util;

/**
 * <h1>token 估算器（JH-SPEC-001 P0-4 R1）</h1>
 *
 * <p>把"字符数"粗略换算成"token 数"，不需要联网、不需要调用模型，零成本。</p>
 *
 * <h2>为什么需要估算？</h2>
 * <p>真实的 token 数只有模型服务商才知道（响应里的 {@code usage.prompt_tokens}），
 * 但那是请求发出<b>之后</b>才拿得到的。我们想在发请求<b>之前</b>就知道
 * "system 提示词大概多少 token、工具定义大概多少 token"，用来做预算控制和
 * {@code /context} 诊断——这时只能估算。</p>
 *
 * <h2>为什么是 2.5 字符/token？</h2>
 * <p>Phase 0 基线测量时，用真实请求的 {@code prompt_tokens} 反推：Jhermes 的提示词里
 * 中文、英文、JSON 符号混在一起，平均每 token 约对应 2.5 个字符（见 docs/baseline-report.md）。
 * 对照参考：纯英文约 4 字符/token，纯中文约 1~1.5 字符/token，混合内容介于两者之间。</p>
 *
 * <h2>它有多准？</h2>
 * <p><b>只是估算，不是精确值。</b>展示给用户时必须标注"估算"；
 * 规范要求估算值与 API 实测值的偏差经校准后控制在 10% 以内（P0-4 AC1）。
 * 如果以后换了模型、偏差变大，只需要改 {@link #CHARS_PER_TOKEN} 这一个常量重新校准即可。</p>
 *
 * <p>本类是纯静态工具类（没有状态、不需要 new），所以构造方法私有。</p>
 */
public final class TokenEstimator {

    /** 平均每个 token 对应的字符数（Phase 0 实测校准值）。 */
    public static final double CHARS_PER_TOKEN = 2.5;

    private TokenEstimator() {
        // 工具类不允许被实例化
    }

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 任意文本；null 或空串视为 0 个 token
     * @return 估算的 token 数（向上取整，非负）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return fromChars(text.length());
    }

    /**
     * 直接用字符数估算 token 数（已经有字符数时，免去再传整段文本）。
     *
     * <p>向上取整的原因：宁可略微高估也不低估——预算守卫的目的是"别超标"，
     * 低估会让超标的请求溜过去。例如 1 个字符也会占用至少 1 个 token。</p>
     *
     * @param chars 字符数；小于等于 0 视为 0
     * @return 估算的 token 数
     */
    public static int fromChars(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (int) Math.ceil(chars / CHARS_PER_TOKEN);
    }
}
