package com.cyk.http;

import com.cyk.bean.ChatCompletionResponse;
import com.cyk.bean.ModelMessage;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <h1>LLM 调用抽象（JH-SPEC-001 P0-5 R0）</h1>
 *
 * <p>把「如何把一轮对话发给模型」从 {@code Agent} 的对话循环里解耦出来：</p>
 * <ul>
 *   <li><b>生产实现</b> {@link ModelClient}：OkHttp + OpenAI 兼容 /chat/completions；</li>
 *   <li><b>测试实现</b>：手写桩（项目无 Mockito，见 CC-5），用于验证 429 重试、
 *       401 不重试、坏参数、工具异常等错误边界而不发起真实网络请求。</li>
 * </ul>
 *
 * <p>本接口是 P0-5 全部验收项与 P1-2（非交互模式）能做自动化测试的前提。</p>
 */
public interface LlmClient {

    /**
     * 非流式调用：整段返回。
     *
     * @param messages 完整对话历史
     * @param tools    function-calling 工具定义；null 或空表示不带工具
     * @param stream   兼容原调用签名的开关（实现按非流式语义处理）
     * @return 解析完成的响应
     * @throws LlmTransportException HTTP 或网络层失败（含状态码与是否可重试标记）
     */
    ChatCompletionResponse chatCompletion(List<ModelMessage> messages,
                                         List<Map<String, Object>> tools,
                                         boolean stream);

    /**
     * 流式（SSE）调用：文本增量经 {@code onContent} 实时回调，
     * 分散的 delta（含 tool_calls 参数碎片）在内部拼装为完整响应返回，
     * 使上层循环无需感知流式与非流式的差异。
     *
     * @param messages  完整对话历史
     * @param tools     function-calling 工具定义；null 或空表示不带工具
     * @param onContent 文本增量回调；null 表示不回调
     * @return 组装完成的完整响应（等价于一次性返回）
     * @throws LlmTransportException HTTP 或网络层失败（含状态码与是否可重试标记）
     */
    ChatCompletionResponse chatCompletionStream(List<ModelMessage> messages,
                                               List<Map<String, Object>> tools,
                                               Consumer<String> onContent);
}
