package com.cyk.security;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * <h1>工作区路径语义分析（JH-SPEC-001 P0-2 R9）</h1>
 *
 * <p>递归删除、越界访问是否危险，取决于<b>目标路径的语义</b>，而不是命令串里有没有出现
 * 某个子串。改造前的实现是「整串小写化跑正则」，既会误杀（引号里的字样）也会漏过
 * （变量写法）。本类把路径判定收敛为精确语义，供 {@link CommandPolicy} 调用。</p>
 *
 * <h2>两类判定</h2>
 * <ul>
 *   <li>{@link #isCatastrophicDeleteTarget} 递归删除的<b>灾难目标</b>：根目录 {@code /}、
 *       家目录 {@code ~}/<code>$HOME</code>、整盘 {@code C:\}、裸 {@code .}/{@code ./}、
 *       任意绝对路径（Unix {@code /...} 或 Windows 盘符）——命中即 BLOCKED。<br>
 *       「任意绝对路径」与改造前行为一致：递归删绝对位置极可能是灾难，宁可保守拦截。</li>
 *   <li>{@link #escapesWorkspace} <b>越界</b>（但不灾难）：{@code ..} 穿越、{@code ~}、
 *       环境变量家目录、绝对路径但不在工作区内——命中则升一档（READ_ONLY→WRITE，WRITE→DANGEROUS）。
 *       这是 R9 的「尽力检测」，文档明示非隔离。</li>
 * </ul>
 */
public final class WorkspacePathAnalyzer {

    private WorkspacePathAnalyzer() {
    }

    /**
     * 判断一个路径参数是否为「递归删除的灾难目标」。
     *
     * @param rawPath 原始路径参数（可能带引号、大小写混杂）
     * @return true 表示灾难目标，应 BLOCKED
     */
    public static boolean isCatastrophicDeleteTarget(String rawPath) {
        String p = normalize(rawPath);
        if (p.isEmpty()) {
            return false;
        }
        // 根目录及通配
        if (p.equals("/") || p.equals("/*") || p.startsWith("//") || p.equals("*")) {
            return true;
        }
        // 家目录
        if (p.equals("~") || p.startsWith("~/") || p.startsWith("~\\")
                || p.equals("$home") || p.equals("${home}") || p.equals("%userprofile%")
                || p.startsWith("$env:userprofile")) {
            return true;
        }
        // 裸当前目录的递归删除（rm -rf . / ./）
        if (p.equals(".") || p.equals("./") || p.equals(".\\")) {
            return true;
        }
        // Windows 盘符根：c: / c:\ / c:/
        if (p.matches("[a-z]:[\\\\/]?$")) {
            return true;
        }
        // 任意绝对路径（Unix / 或 Windows 盘符开头）：递归删除绝对位置一律视为灾难
        if (p.startsWith("/") || p.matches("[a-z]:[\\\\/].*")) {
            return true;
        }
        // 关键系统目录（即使写成相对形式也拦）
        if (isSystemDir(p)) {
            return true;
        }
        return false;
    }

    /**
     * 判断路径是否越出工作区（但不属于灾难目标）。
     *
     * @param rawPath 原始路径参数
     * @param workDir 工作区根（启动目录）；null 表示未知，按越界保守处理绝对路径
     * @return true 表示越界，应升一档
     */
    public static boolean escapesWorkspace(String rawPath, Path workDir) {
        String p = normalize(rawPath);
        if (p.isEmpty()) {
            return false;
        }
        // 家目录 / 环境变量：几乎必然在工作区外
        if (p.startsWith("~") || p.equals("$home") || p.equals("${home}")
                || p.contains("%userprofile%") || p.contains("$env:") || p.startsWith("%")) {
            return true;
        }
        // 相对路径穿越
        if (p.equals("..") || p.startsWith("../") || p.startsWith("..\\")) {
            return true;
        }
        // 绝对路径：判断是否在工作区内
        if (p.startsWith("/") || p.matches("[a-z]:[\\\\/].*")) {
            if (workDir == null) {
                return true;// 工作区未知时，绝对路径按越界保守处理
            }
            try {
                Path target = Paths.get(rawPathUnquote(rawPath)).toAbsolutePath().normalize();
                Path root = workDir.toAbsolutePath().normalize();
                return !target.startsWith(root);
            } catch (RuntimeException e) {
                return true;// 无法解析的路径按越界处理（保守）
            }
        }
        return false;
    }

    /** 关键系统目录（相对写法的兜底拦截）。 */
    private static boolean isSystemDir(String p) {
        String stripped = p.replaceAll("[\\\\/]+$", "");
        return switch (stripped) {
            case "etc", "usr", "bin", "sbin", "boot", "dev", "proc", "sys",
                 "var", "home", "root", "windows", "system32", "program files" -> true;
            default -> false;
        };
    }

    /** 归一化：去包裹引号、小写化、去首尾空白（用于关键词与模式比对）。 */
    private static String normalize(String raw) {
        return rawPathUnquote(raw).toLowerCase(Locale.ROOT).trim();
    }

    /** 去掉首尾成对的引号（双引号或单引号），保留内部原样。 */
    private static String rawPathUnquote(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
