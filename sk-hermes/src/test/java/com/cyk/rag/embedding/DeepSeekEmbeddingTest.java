package com.cyk.rag.embedding;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeepSeekEmbeddingTest {

    @Test
    void dimensionShouldReturnConfiguredValue() {
        DeepSeekEmbedding client = new DeepSeekEmbedding(
            "https://api.deepseek.com", "text-embedding-3-small", "sk-test", 1536
        );
        assertThat(client.dimension()).isEqualTo(1536);
    }
}
