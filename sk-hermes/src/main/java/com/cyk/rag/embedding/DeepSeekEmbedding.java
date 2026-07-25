package com.cyk.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek Embedding API 客户端。
 * 复用 OkHttp 模式（与 ModelClient 一致）。
 */
public class DeepSeekEmbedding implements EmbeddingClient {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekEmbedding.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int dimension;

    public DeepSeekEmbedding(String baseUrl, String model, String apiKey, int dimension) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.dimension = dimension;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        if (results.isEmpty()) {
            throw new RuntimeException("Embedding returned empty result");
        }
        return results.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("input", objectMapper.valueToTree(texts));

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(baseUrl + "/embeddings")
                    .post(RequestBody.create(json, JSON))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("Embedding API error: {} {}", response.code(), response.message());
                    throw new RuntimeException("Embedding API error: " + response.code());
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode data = root.get("data");

                List<float[]> results = new ArrayList<>();
                if (data != null && data.isArray()) {
                    for (JsonNode item : data) {
                        JsonNode embedding = item.get("embedding");
                        if (embedding != null && embedding.isArray()) {
                            float[] vec = new float[embedding.size()];
                            for (int i = 0; i < embedding.size(); i++) {
                                vec[i] = (float) embedding.get(i).asDouble();
                            }
                            results.add(vec);
                        }
                    }
                }
                return results;
            }
        } catch (IOException e) {
            throw new RuntimeException("Embedding request failed", e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
