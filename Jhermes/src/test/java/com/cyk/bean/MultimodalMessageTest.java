package com.cyk.bean;

import com.cyk.prompt.ContextReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultimodalMessageTest {
    private static final String IMAGE = "data:image/png;base64,test-payload";

    @Test void serializationUsesVisionProtocolNotJavaFieldNames() {
        var json = ModelMessage.userMultimodal("说明\n第二行", List.of(IMAGE)).toJsonNode();
        assertThat(json.path("content").isArray()).isTrue();
        assertThat(json.path("content").get(0).path("text").asText()).isEqualTo("说明\n第二行");
        assertThat(json.path("content").get(1).path("image_url").path("url").asText()).isEqualTo(IMAGE);
        assertThat(json.toString()).doesNotContain("imageUrl", "contentParts", "\"text\":null");
    }

    @Test void imageOnlyAndPlainTextBothRemainValid() {
        assertThat(ModelMessage.userMultimodal("", List.of(IMAGE)).toJsonNode().path("content").size()).isEqualTo(1);
        assertThat(ModelMessage.userMultimodal("旧文本", List.of()).toJsonNode().path("content").asText()).isEqualTo("旧文本");
    }

    @Test void sessionRoundTripKeepsImagesWhileOldSessionsRemainCompatible() {
        Session session = new Session("test");
        session.addMessage(ModelMessage.userMultimodal("图", List.of(IMAGE)));
        Session restored = Session.fromJson("test", session.toJson());
        assertThat(restored.messages.get(0).toModelMessage().toJsonNode())
                .isEqualTo(session.messages.get(0).toModelMessage().toJsonNode());
        Session old = new Session("old");
        old.addMessage("user", "旧格式");
        assertThat(Session.fromJson("old", old.toJson()).messages.get(0).contentParts()).isNull();
    }

    @Test void learningProjectionAndContextReportNeverExpandBase64() {
        ModelMessage original = ModelMessage.userMultimodal("问题", List.of(IMAGE));
        assertThat(original.textOnlyCopy().toJsonNode().toString()).contains("附 1 张图片").doesNotContain(IMAGE);
        assertThat(original.imageCount()).isEqualTo(1);
        String report = ContextReport.render(null, 0, 0, List.of(original), null);
        assertThat(report).contains("不含视觉 token").doesNotContain(IMAGE);
    }
}
