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
 * <p>排序按技能名字典序（前缀稳定性，P0-4 R8）；<b>双重上限</b>——条数上限
 * {@link #MAX_ENTRIES} 与字符预算 {@link #MAX_BODY_CHARS}，先到先截：</p>
 * <ul>
 *   <li>条数上限防「技能数量多」；</li>
 *   <li>字符预算防「描述写得长」——预算的真实单位是 token（代理为字符），
 *       只限条数会让 20 个长描述技能突破 T1 冻结线（期 6 实测：30 条索引把
 *       整份提示词顶到 7,140 字符，超过 80% 线 419）。</li>
 * </ul>
 * <p>因此描述短的技能能列更多条、描述长的自动少列，并统一给出
 * 「等共 N 项，用 skill_manage list 查看」的截断提示（模型知道清单不完整，
 * 且有确定性的补全途径）。</p>
 */
public final class SkillIndexBuilder {

    /** 索引条数上限（第一道护栏：技能数量）。 */
    static final int MAX_ENTRIES = 30;

    /**
     * 索引正文字符预算（第二道护栏，期 6 增）：超出部分不再列出，改为截断提示。
     *
     * <p>取值依据：满配静态段在基线记忆口径下离 T1 冻结线（authored ≤ 4,197）
     * 余量有限，实测单条约 20 字符，460 字符可列出约 20 个常规技能。</p>
     */
    static final int MAX_BODY_CHARS = 460;

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

        int limit = Math.min(sorted.size(), MAX_ENTRIES);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < limit; i++) {
            String line = "- " + nameOf(sorted.get(i)) + ": " + oneLine(sorted.get(i).getDescription());
            // 为「本条之后仍有未列条目」预留截断提示的空间，
            // 否则预算会被正文吃满、提示行反而使整段超线
            boolean needHint = i < sorted.size() - 1;
            int projected = sb.length() + (shown == 0 ? 0 : 1)
                    + line.length() + (needHint ? truncationHint(sorted.size()).length() + 1 : 0);
            // 至少列出一条（极端长描述也不致于索引全空）
            if (shown > 0 && projected > MAX_BODY_CHARS) {
                break;
            }
            if (shown > 0) {
                sb.append('\n');
            }
            sb.append(line);
            shown++;
        }
        if (shown < sorted.size()) {
            sb.append('\n').append(truncationHint(sorted.size()));
        }
        return sb.toString();
    }

    /** 截断提示：告知总数与确定性补全途径（不让模型以为清单就是全部）。 */
    private static String truncationHint(int total) {
        return "…等共 " + total + " 项，已截断，用 skill_manage list 查看完整清单";
    }

    private static String nameOf(Skill skill) {
        return skill.getName() != null ? skill.getName() : "(unnamed)";
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
