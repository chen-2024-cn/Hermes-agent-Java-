package com.cyk.bean;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SessionUserInputTest {
    @Test void absentOrWhitespaceInputIsNotAConversation() {
        Session session = new Session("empty");
        assertThat(session.hasUserInput()).isFalse();
        session.addMessage("system", "规则");
        session.addMessage("assistant", "欢迎");
        session.addMessage("tool", "结果");
        session.addMessage("user", " \n\t ");
        assertThat(session.hasUserInput()).isFalse();
        assertThat(Message.hasUserInput(null, "x", null)).isFalse();
    }

    @Test void userTextAndMultimodalTextCount() {
        assertThat(Message.hasUserInput("user", "文字", null)).isTrue();
        assertThat(Message.hasUserInput("user", null, List.of(ContentPart.text("文字")))).isTrue();
        assertThat(Message.hasUserInput("assistant", "文字", List.of(ContentPart.image("img")))).isFalse();
    }

    @Test void onlyARealNonblankImagePartCounts() {
        assertThat(Message.hasUserInput("user", "", List.of(ContentPart.image("data:image/png;base64,image")))).isTrue();
        assertThat(Message.hasUserInput("user", null, Arrays.asList(null, ContentPart.text(" "),
                ContentPart.image(" "), new ContentPart()))).isFalse();
        ContentPart part = new ContentPart();
        part.setType("image_url");
        assertThat(Message.hasUserInput("user", "", List.of(part))).isFalse();
    }
}
