package com.cyk.rag.embedding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpenAiEmbeddingTest {

    @Test
    void dimensionShouldReturnConfiguredValue() {
        OpenAiEmbedding client = new OpenAiEmbedding(
            "https://api.siliconflow.cn", "BAAI/bge-m3", "sk-test", 1024
        );
        assertThat(client.dimension()).isEqualTo(1024);
    }
}
