package com.cyk.bean;

/**
 * 一次模型请求的 token 用量（来自 API 响应里的 {@code usage} 字段，是服务商的真实计费口径）。
 *
 * <p>字段含义：</p>
 * <ul>
 *   <li>{@code promptTokens}：输入 token 数（system 提示词 + 工具定义 + 全部历史消息）</li>
 *   <li>{@code completionTokens}：输出 token 数（模型这次生成的内容）</li>
 *   <li>{@code totalTokens}：前两者之和</li>
 *   <li>{@code cachedTokens}：输入里<b>命中了服务端前缀缓存</b>的那部分 token 数（P0-4 R3 新增）</li>
 * </ul>
 *
 * <p><b>什么是缓存命中？</b>每次请求，服务商会把"请求开头相同的那一段"的计算结果缓存起来，
 * 下次请求如果开头一模一样，这一段就不用重算——更快也更便宜。{@code cachedTokens}
 * 就是告诉我们"这次有多少输入 token 是直接用缓存的"。它越大，说明我们的提示词前缀越稳定、
 * 省得越多；一直是 0 则说明要么供应商不支持缓存，要么我们的前缀每次都在变。</p>
 *
 * <p>注意：{@code cachedTokens} 默认为 0，所以"供应商不支持缓存"和"支持但这次没命中"
 * 在这里看起来是一样的。要区分两者，需要看原始响应里有没有缓存字段（见 AC4 的取证要求）。</p>
 */
public class Usage {
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    /** 命中前缀缓存的输入 token 数；供应商没返回该字段时保持 0。 */
    private int cachedTokens;

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public int getCachedTokens() {
        return cachedTokens;
    }

    public void setCachedTokens(int cachedTokens) {
        this.cachedTokens = cachedTokens;
    }
}
