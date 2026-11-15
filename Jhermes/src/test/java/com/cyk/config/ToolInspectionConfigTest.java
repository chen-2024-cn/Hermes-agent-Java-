package com.cyk.config;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ToolInspectionConfigTest {
    @Test void inspectionRetainsConfiguredToolSwitchesAndDoesNotMutateTheOriginal() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("tools", Map.of("terminal_enabled", false, "disabled_toolsets", List.of("memory")));
        source.put("rag", Map.of("enabled", true));
        source.put("security", Map.of("audit_enabled", true));
        HermesConfig original = new HermesConfig(null, source);
        HermesConfig preview = original.copyForToolInspection();
        assertThat(preview.isTerminalToolEnabled()).isFalse();
        assertThat(preview.getDisabledToolsets()).containsExactly("memory");
        assertThat(preview.isAuditEnabled()).isFalse();
        assertThat((Boolean) preview.getFromYml("rag.enabled", true)).isFalse();
        assertThat((Boolean) original.getFromYml("rag.enabled", false)).isTrue();
        assertThat(original.isAuditEnabled()).isTrue();
    }
}
