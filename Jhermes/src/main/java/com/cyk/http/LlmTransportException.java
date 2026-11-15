package com.cyk.http;

import com.cyk.config.HermesConfig;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;

/**
 * <h1>LLM 传输层异常（JH-SPEC-001 P0-5 R3）</h1>
 *
 * <p>改造前 {@link ModelClient} 在 HTTP 非 2xx 与网络异常时一律抛裸
 * {@code RuntimeException}，而 {@code Agent.run()} 只捕获 {@code IOException}——
 * 后果是<b>一次 429 限流、一次网络抖动就让整个进程以「执行异常」退出，
 * 且 endSession 不被调用，整场对话、轨迹与知识提取全部丢失</b>（附录 A1）。</p>
 *
 * <p>本异常把「发生了什么」与「该不该重试」两件事一起承载，使上层的重试策略
 * 只需读一个布尔标记，不必在业务代码里散落状态码判断：</p>
 *
 * <table border="1">
 *   <caption>重试分类</caption>
 *   <tr><th>可重试</th><td>408、429（限流/余额）、500、502、503、504（服务端瞬时故障）、
 *       连接重置、读写超时等网络异常</td></tr>
 *   <tr><th>不可重试</th><td>400（请求体非法，重试结果相同）、401/403（凭据问题，
 *       重试只会重复失败并可能触发风控）、404（路径/模型名错）及其他 4xx</td></tr>
 * </table>
 *
 * <p>实证依据：Phase 0 基线采集时内网网关对本项目请求返回过瞬时 HTTP 500
 * （{@code is_bifrost_error:false, code:500001}），原样重试即成功——
 * 若当时没有重试，两次探针组合的 token 校准数据就直接丢失。</p>
 *
 * <p>同时接管原 {@code ModelClient.buildHttpErrorMessage} 的排查指引文案：
 * 异常自己知道如何向用户解释自己，避免「抛异常的地方」与「组织人话的地方」分离漂移。</p>
 */
public class LlmTransportException extends RuntimeException {

    /** 网络层失败（无 HTTP 响应）时的状态码占位值。 */
    public static final int NO_STATUS_CODE = 0;

    /**
     * 服务端瞬时故障与限流：重试有机会成功。
     *
     * <p>400 不在此列——请求体非法属于我们的 bug，重试一万次结果相同；
     * 401/403 也不在此列——凭据错误重试只会重复失败。</p>
     */
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 429, 500, 502, 503, 504);

    private final int statusCode;
    private final boolean retryable;

    private LlmTransportException(String message, Throwable cause, int statusCode, boolean retryable) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    /**
     * 构造 HTTP 非 2xx 异常：消息中带状态码、服务端响应体与针对常见状态码的自查指引，
     * 并按 {@link #RETRYABLE_STATUS_CODES} 自动分类是否可重试。
     *
     * @param prefix 场景前缀（如「请求失败」/「流式请求失败」），便于用户区分是哪条调用路径
     * @param code   HTTP 状态码
     * @param body   服务端响应体（已截断），可为空
     */
    public static LlmTransportException http(String prefix, int code, String body) {
        return new LlmTransportException(buildMessage(prefix, code, body), null,
                code, RETRYABLE_STATUS_CODES.contains(code));
    }

    /**
     * 构造网络层异常（连接失败、读写超时、连接重置、流中断）。
     *
     * <p>一律视为可重试：这类错误由网络抖动引起，与请求内容无关，重试大概率成功。</p>
     */
    public static LlmTransportException network(String message, Throwable cause) {
        return new LlmTransportException(message + "：" + describe(cause), cause,
                NO_STATUS_CODE, true);
    }

    /**
     * 构造请求体序列化异常。
     *
     * <p>不可重试：序列化失败是本地数据问题（如消息含非法字符），重发同样的历史必然再失败。</p>
     */
    public static LlmTransportException serialization(String message, Throwable cause) {
        return new LlmTransportException(message + "：" + describe(cause), cause,
                NO_STATUS_CODE, false);
    }

    /** @return HTTP 状态码；网络层失败时为 {@link #NO_STATUS_CODE} */
    public int statusCode() {
        return statusCode;
    }

    /** @return 是否值得指数退避重试（上层据此决定重试次数） */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * 判断某个网络异常是否属于「超时类」——用于给用户更准确的提示文案
     * （超时通常意味着模型思考过久或网关排队，与连接失败的原因不同）。
     */
    public static boolean isTimeout(Throwable cause) {
        return cause instanceof SocketTimeoutException
                || cause instanceof java.net.SocketException
                || (cause instanceof IOException && String.valueOf(cause.getMessage()).contains("timeout"));
    }

    /**
     * 组装人话错误信息：状态码 + 服务端响应体 + 针对常见状态码的排查指引。
     *
     * <p>401/403 几乎都是 api_key 没配或配错——直接在消息里告诉用户去哪修，
     * 而不是让用户对着一个状态码猜；405 则多半是 base_url 误填了完整端点导致路径重复。</p>
     */
    private static String buildMessage(String prefix, int code, String body) {
        StringBuilder sb = new StringBuilder(prefix).append(": HTTP ").append(code);
        if (body != null && !body.isBlank()) {
            sb.append("\n服务端返回: ").append(body);
        }
        switch (code) {
            case 401, 403 -> sb.append("\n排查: API Key 无效或未配置。请编辑 ")
                    .append(HermesConfig.getConfigPath())
                    .append("\n在 model.api_key 填入有效的密钥后重试（无需改代码，改完即生效）。")
                    .append("\n此类凭据错误不会自动重试。");
            case 404 -> sb.append("\n排查: base_url 或模型名不对。请检查配置中 model.base_url 与 model.model 是否匹配所用服务商。")
                    .append("\n此类配置错误不会自动重试。");
            case 405 -> sb.append("\n排查: 请求路径不被服务端接受。base_url 应填接口前缀（如 https://api.deepseek.com 或 https://xxx/v1），")
                    .append("程序会自动拼接 /chat/completions；若填了完整端点会导致路径重复。请核对配置 ")
                    .append(HermesConfig.getConfigPath());
            case 408, 429 -> sb.append("\n原因: 触发限流、排队或服务端请求超时。程序会自动指数退避重试；")
                    .append("若持续失败请检查服务商账户余额或稍后再试。");
            default -> {
                if (RETRYABLE_STATUS_CODES.contains(code)) {
                    sb.append("\n原因: 服务端瞬时故障，程序会自动重试。持续失败请稍后再试或更换服务商。");
                }
            }
        }
        return sb.toString();
    }

    /** 提取 cause 的简短描述：优先 message，退化到类名（部分异常 message 为 null）。 */
    private static String describe(Throwable cause) {
        if (cause == null) {
            return "未知网络错误";
        }
        String msg = cause.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cause.getClass().getSimpleName();
    }
}
