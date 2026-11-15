package com.cyk.agent;

import com.cyk.bean.Skill;
import com.cyk.manager.SkillManager;

import java.util.*;

/**
 * 技能关键词匹配器（<b>暂无调用方，保留待 P2-1 复用</b>——决策 D-2）。
 *
 * <p>P0-3 期 3 移除了本类在 {@code Agent#buildSkillContext} 的唯一调用点：
 * 旧机制按「恢复的历史文本」匹配技能并注入<b>全文</b>，而新会话没有历史
 * → 恒匹配为空，技能系统对模型完全不可见（附录 A19 缺陷）。
 * 替代方案是 {@code SkillIndexBuilder} 的无条件紧凑索引 + {@code skill_manage get}
 * 按需取正文（见 resources/prompts/skills-index.md）。</p>
 *
 * <p>保留本类的原因：P2-1 修复自进化闭环时，「按当前任务给技能加权排序」
 * 仍需要关键词/标签匹配能力，届时在索引渲染上叠加本类打分即可，避免重建成本。</p>
 */
public class SkillMatcher {

    private static final SkillManager skillManager = SkillManager.getInstance();
    private static final int MAX_RESULTS = 5;
    private static final double MIN_SCORE = 1.0;
    private static final int MAX_CONTENT_CHARS = 2000;

    public record Match(Skill skill, double score) implements Comparable<Match> {
        @Override
        public int compareTo(Match o) {
            return Double.compare(o.score, this.score);
        }
    }

    public List<Match> match(String userInput, Set<String> recentTags) {
        if (userInput == null || userInput.isBlank()) {
            return Collections.emptyList();
        }

        List<Skill> allSkills = skillManager.listAll();
        if (allSkills.isEmpty()) {
            return Collections.emptyList();
        }

        String lowerInput = userInput.toLowerCase();
        List<Match> results = new ArrayList<>();

        for (Skill skill : allSkills) {
            double score = 0;

            if (skill.getTags() != null) {
                for (String tag : skill.getTags()) {
                    if (lowerInput.contains(tag.toLowerCase())) {
                        score += 3;
                    }
                    if (recentTags != null && recentTags.contains(tag.toLowerCase())) {
                        score += 1;
                    }
                }
            }

            if (skill.getDescription() != null) {
                String[] descWords = skill.getDescription().toLowerCase().split("\\s+");
                for (String word : descWords) {
                    if (word.length() >= 3 && lowerInput.contains(word)) {
                        score += 2;
                    }
                }
            }

            if (skill.getName() != null) {
                String nameLower = skill.getName().toLowerCase().replace("-", " ");
                if (lowerInput.contains(nameLower)) {
                    score += 5;
                } else {
                    String[] nameWords = nameLower.split("\\s+");
                    for (String word : nameWords) {
                        if (word.length() >= 3 && lowerInput.contains(word)) {
                            score += 2;
                        }
                    }
                }
            }

            if ("workflow".equals(skill.getType())) {
                score *= 1.5;
            }

            if (score >= MIN_SCORE) {
                results.add(new Match(skill, score));
            }
        }

        results.sort(null);
        return results.subList(0, Math.min(results.size(), MAX_RESULTS));
    }

    public String buildSkillContext(List<Match> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Available Skills (auto-matched)\n\n");

        for (int i = 0; i < matches.size(); i++) {
            Match match = matches.get(i);
            Skill skill = match.skill();

            sb.append("### Skill ").append(i + 1).append(": ")
                    .append(skill.getName())
                    .append(" (").append(skill.getType()).append(")\n");

            if (skill.getDescription() != null) {
                sb.append("**Description:** ").append(skill.getDescription()).append("\n\n");
            }

            boolean includeFull = "workflow".equals(skill.getType())
                    || "tool_def".equals(skill.getType())
                    || match.score() >= 8.0;

            if (includeFull && skill.getContent() != null && !skill.getContent().isEmpty()) {
                sb.append("**Content:**\n\n");
                String truncated = truncate(skill.getContent(), MAX_CONTENT_CHARS);
                sb.append(truncated).append("\n\n");
            }

            if (i < matches.size() - 1) {
                sb.append("---\n\n");
            }
        }

        sb.append("**Guidance:** Use the 'skill_manage' tool to save new skills, update existing ones, or search for skills not shown here. For reference-type skills, use them as knowledge sources. For workflow-type skills, follow the steps exactly.\n");

        return sb.toString();
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n\n... (content truncated)";
    }
}
