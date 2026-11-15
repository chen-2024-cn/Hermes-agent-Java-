package com.cyk.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个已加载的提示词分段：manifest 元数据（id、文件名、requires）+ 段正文。
 *
 * <p>正文是 {@link PromptLoader} 读出的最终文本（已去首尾空白、已剥 BOM）；
 * 含 {@code {{var}}} 模板变量的段由 {@code PromptComposer}（期 3）在装配时替换。
 * 本 record 只承载数据，不做判定——判定逻辑全部留给 composer，
 * 便于单测单独验证「加载正确」与「装配正确」两件事。</p>
 *
 * @param id       段唯一标识（manifest 声明，如 {@code identity}），日志与 /context 明细用它
 * @param file     段文件名（如 {@code identity.md}），排查「用户覆盖是否生效」时定位来源
 * @param content  段正文（非空白）
 * @param requires 注入条件（非 null；恒注入段为 {@link PromptRequirements#ALWAYS}）
 */
public record PromptSegment(String id, String file, String content, PromptRequirements requires) {

    /** 模板变量语法：{{name}}，name 限 [a-zA-Z_][a-zA-Z0-9_]*（允许变量名两侧空白）。 */
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

    public PromptSegment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("段 id 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("段 " + id + " 的 content 不能为 null");
        }
        requires = requires == null ? PromptRequirements.ALWAYS : requires;
    }

    /** 正文字符数（P0-4 预算度量与 /context 分段明细的计数口径）。 */
    public int charCount() {
        return content.length();
    }

    /**
     * 正文中出现的全部模板变量名（按出现顺序，去重）。
     *
     * <p>两个用途：① 期 2 的单测用它锁定每个模板段的变量清单（防止改名后
     * composer 与 md 文件两边漂移）；② 期 3 装配后做残留检查——替换完成的结果里
     * 不应再出现任何 {@code {{...}}}，出现即为 composer 漏填。</p>
     */
    public List<String> templateVariables() {
        Matcher matcher = TEMPLATE_VAR.matcher(content);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }
}
