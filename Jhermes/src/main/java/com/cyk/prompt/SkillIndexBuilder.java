package com.cyk.prompt;

import com.cyk.bean.Skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <h1>紧凑技能索引构建器（JH-SPEC-001 P0-3 R9）</h1>
 *
 * <p>为 {@code skills-index.md} 段渲染 {@code {{skills_index}}} 变量：
 * 每个技能只占一行「名称 + 一句描述」，<b>不含正文</b>——正文动辄数千字符，
 * 全量进前缀会对每轮请求重复计费（token 雪球），且挤占预算。</p>
 *
 * <p><b>与旧 SkillMatcher 的本质区别</b>（附录 A19 缺陷）：旧实现按「恢复的历史文本」
 * 做关键词匹配决定注入哪个技能的<b>全文</b>，而新会话没有历史 → 恒匹配为空，
 * 技能系统对模型完全不可见。新方案无条件给出<b>索引</b>（便宜、恒在），
 * 模型需要时自行 {@code skill_manage get <名称>} 取正文（按需、精确）。
 * 判定「哪个技能相关」的智能从宿主的拙劣关键词匹配交还给模型本身。</p>
 *
 * <p>排序按技能名字典序（前缀稳定性，P0-4 R8）；条数上限 {@link #MAX_ENTRIES}，
 * 超限渲染截断提示——技能重度用户的索引膨胀由此封顶。</p>
 */
public final class SkillIndexBuilder {

    /** 索引条数上限：超出部分只显示计数（防技能库膨胀侵蚀 T1 预算）。 */
    static final int MAX_ENTRIES = 30;

    /** 单条描述截断长度：一句描述够用，长描述进正文（get 时可读全文）。 */
    static final int MAX_DESCRIPTION_CHARS = 60;

    private SkillIndexBuilder() {
    }

    /**
     * 渲染技能索引文本。
     *
     * @param skills 技能列表（null/空 → 返回空串，composer 据此跳过整段）
     * @return 每行 {@code - 名称: 描述} 的索引文本；无技能时为空串
     */
    public static String build(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        List<Skill> sorted = new ArrayList<>(skills);
        // 名称可能为 null（损坏的技能文件），排到最后并渲染为占位名
        sorted.sort(Comparator.comparing(s -> s.getName() == null ? "\uFFFF" : s.getName()));

        StringBuilder sb = new StringBuilder();
        int shown = Math.min(sorted.size(), MAX_ENTRIES);
        for (int i = 0; i < shown; i++) {
            Skill skill = sorted.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(skill.getName() != null ? skill.getName() : "(unnamed)")
                    .append(": ").append(oneLine(skill.getDescription()));
        }
        if (sorted.size() > MAX_ENTRIES) {
            sb.append("\n- …等共 ").append(sorted.size()).append(" 项，已截断，"
                    + "用 skill_manage list 查看完整清单");
        }
        return sb.toString();
    }

    /**
     * 描述压成一行：去换行（索引条目不允许破坏行结构）+ 截断 + 去首尾空白。
     * null/空白描述渲染为空（行尾只剩冒号可接受，模型以名称为准）。
     */
    private static String oneLine(String description) {
        if (description == null) {
            return "";
        }
        String flat = description.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').strip();
        if (flat.length() > MAX_DESCRIPTION_CHARS) {
            flat = flat.substring(0, MAX_DESCRIPTION_CHARS) + "…";
        }
        return flat;
    }
}
