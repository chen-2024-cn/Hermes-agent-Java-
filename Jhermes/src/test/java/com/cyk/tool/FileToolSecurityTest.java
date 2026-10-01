package com.cyk.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link FileTool} 敏感凭据路径拦截测试。
 *
 * <p>验证四个能触达文件系统的入口（read/write/search/grep）都接上了
 * {@link com.cyk.util.SensitivePathGuard} 的黑名单——堵住「run_command 拦住了
 * Get-Content ~/.ssh/id_rsa，但模型改用 read_file 照样读得到」的防护不对齐口子。</p>
 *
 * <p>拦截都发生在任何真实 IO 之前（Files.size / createDirectories / walkFileTree 之前），
 * 因此这些用例既不会读到真实凭据，也不会落盘探针文件。tearDown 再兜一层清理以防回归。</p>
 */
class FileToolSecurityTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String HOME = System.getProperty("user.home");

    /** 写入探针文件路径：断言拦截生效=不落盘；万一回归，tearDown 兜底清理 */
    private static final Path WRITE_PROBE = Paths.get(HOME, ".ssh", "hermes_filetool_probe_should_not_exist.txt");

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(WRITE_PROBE);
    }

    /** 反序列化 error 字段，确认是「安全策略拦截」而非其它错误（如文件不存在） */
    private void assertBlockedBySensitive(String json) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = mapper.readValue(json, Map.class);
        assertThat(map).containsKey("error");
        assertThat(String.valueOf(map.get("error")))
                .contains("安全策略拦截")
                .contains("敏感凭据");
    }

    @Test
    void readFileShouldBlockSshKey() throws Exception {
        String result = FileTool.readFile(Map.of("path", Paths.get(HOME, ".ssh", "id_rsa").toString()));
        assertBlockedBySensitive(result);
    }

    @Test
    void readFileShouldBlockHermesConfigBothDirNames() throws Exception {
        // 新旧数据目录都要拦：迁移失败时凭据仍在旧的 .skhermes 下
        assertBlockedBySensitive(FileTool.readFile(Map.of("path", Paths.get(HOME, ".jhermes", "config.yaml").toString())));
        assertBlockedBySensitive(FileTool.readFile(Map.of("path", Paths.get(HOME, ".skhermes", "config.yaml").toString())));
    }

    @Test
    void writeFileShouldBlockCredentialPathAndNotTouchDisk() throws Exception {
        String result = FileTool.writeFile(Map.of(
                "path", WRITE_PROBE.toString(),
                "content", "malicious"));
        assertBlockedBySensitive(result);
        // 拦截必须在 createDirectories/writeString 之前，磁盘上不应留下任何痕迹
        assertThat(Files.exists(WRITE_PROBE)).isFalse();
    }

    @Test
    void searchFilesShouldBlockWhenRootIsCredentialDir() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("pattern", "*");
        args.put("path", Paths.get(HOME, ".ssh").toString());
        assertBlockedBySensitive(FileTool.searchFiles(args));
    }

    @Test
    void grepFilesShouldBlockWhenRootIsCredentialDir() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("pattern", ".*");
        args.put("path", Paths.get(HOME, ".aws", "credentials").toString());
        assertBlockedBySensitive(FileTool.grepFiles(args));
    }

    @Test
    void normalProjectPathShouldPassThrough() throws Exception {
        // 项目自身源码路径绝不能被误拦（否则 read_file 直接残废）：
        // 这里只断言「不是敏感拦截」，文件是否真实存在由其它逻辑决定
        String result = FileTool.readFile(Map.of(
                "path", Paths.get(HOME, "Desktop", "Hermes-agent-Java-", "Jhermes", "pom.xml").toString()));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = mapper.readValue(result, Map.class);
        // 要么正常读到 content，要么因 offset/limit 默认只读 1 行仍是 content；总之不含敏感拦截
        assertThat(String.valueOf(map.getOrDefault("error", "")))
                .doesNotContain("敏感凭据");
    }
}
