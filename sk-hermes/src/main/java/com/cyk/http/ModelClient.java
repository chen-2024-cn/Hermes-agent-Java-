package com.cyk.http;

import com.cyk.bean.*;
import com.cyk.config.HermesConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ModelClient {
    private static final Logger logger = LoggerFactory.getLogger(ModelClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final HermesConfig config;
    private final OkHttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();//解析json数据

    public ModelClient(HermesConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 向LLm发送请求获取响应
     */
    public ChatCompletionResponse chatCompletion(List<ModelMessage> messages, List<Map<String, Object>> tools, boolean stream) {
        String apiKey = config.getApiKey();
        String baseUrl = config.getBaseUrl();
        String currentModel = config.getCurrentModel();

        try {
            //构建请求参数
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", currentModel);

            //创建历史会话记录
            ArrayNode messagesArray = objectMapper.createArrayNode();
            for (ModelMessage message : messages) {
                messagesArray.add(message.toJsonNode());
            }
            requestBody.set("messages", messagesArray);

            //处理工具
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsArray = objectMapper.createArrayNode();
                for (Map<String, Object> tool : tools) {
                    toolsArray.add(objectMapper.valueToTree(tool));
                }
                requestBody.set("tools", toolsArray);
            }

            requestBody.put("stream", stream);
            requestBody.put("temperature", config.getTemperature());
            requestBody.put("max_tokens", config.getMaxTokens());

            //将json数据放入请求体里面
            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

            //构建请求
            Request requestBuilder = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .method("POST", body)//将请求体放入请求
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build();

            //发送请求
            try (Response response = httpClient.newCall(requestBuilder).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("请求失败: {} {} {}", response.code(), response.message(), response.body());
                    throw new RuntimeException("请求失败: " + response.code() + " " + response.message() + " " + response.body());
                }

                String responseBody = response.body().string();

                JsonNode root = objectMapper.readTree(responseBody);

                return parseCompletionResponse(root);

            } catch (IOException e) {
                throw new RuntimeException("Chat completion request failed", e);
            }

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize chat completion request", e);
        }

    }

    /**
     * 解析llm返回的数据
     */
    private ChatCompletionResponse parseCompletionResponse(JsonNode root) {
        ChatCompletionResponse result = new ChatCompletionResponse();

        //解析choices
        JsonNode choicesNode = root.get("choices");
        if (choicesNode != null && choicesNode.isArray() && !choicesNode.isEmpty()) {
            JsonNode firstChoices = choicesNode.get(0);
            JsonNode messageNode = firstChoices.get("message");
            if (messageNode != null) {
                ModelMessage modelMessage = new ModelMessage();
                //解析role
                if (messageNode.has("role")) {
                    String role = messageNode.get("role").asText();
                    modelMessage.setRole(role);
                }

                //解析content
                if (messageNode.has("content")) {
                    String content = messageNode.get("content").asText();
                    modelMessage.setContent(content);
                }

                //解析tool call
                if (messageNode.has("tool_calls")) {
                    ArrayNode toolCalls = (ArrayNode) messageNode.get("tool_calls");

                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        List<ToolCall> toolCallList = new ArrayList<>();

                        //解析toolCalls 转成ToolCall对象
                        for (JsonNode toolCall : toolCalls) {
                            ToolCall tc = new ToolCall();
                            if (toolCall.has("id")) {
                                tc.setId(toolCall.get("id").asText());
                            }
                            if (toolCall.has("type")) {
                                tc.setType(toolCall.get("type").asText());
                            }

                            if (toolCall.has("function")) {
                                Function fc = new Function();
                                JsonNode function = toolCall.get("function");
                                if (function.has("name")) {
                                    fc.setName(function.get("name").asText());
                                }

                                if (function.has("arguments")) {
                                    fc.setArguments(function.get("arguments").asText());
                                }

                                tc.setFunction(fc);//拿到function对象
                            }
                            //ToolCall里面属性全部拿到，存入list里面
                            toolCallList.add(tc);
                        }
                        //将拿到的tool_calls放入modelMessage
                        modelMessage.setToolCalls(toolCallList);
                    }
                }

                result.setMessage(modelMessage);
            }

            if (firstChoices.has("finish_reason")) {
                result.setFinishReason(firstChoices.get("finish_reason").asText());
            }

        }

        //解析usage
        if (root.has("usage") && !root.get("usage").isNull()) {
            JsonNode usageNode = root.get("usage");
            Usage usage = new Usage();

            if (usageNode.has("completion_tokens")) {
                usage.setCompletionTokens(usageNode.get("completion_tokens").asInt());
            }

            if (usageNode.has("prompt_tokens")) {
                usage.setPromptTokens(usageNode.get("prompt_tokens").asInt());
            }

            if (usageNode.has("total_tokens")) {
                usage.setTotalTokens(usageNode.get("total_tokens").asInt());
            }

            result.setUsage(usage);
        }


        return result;
    }

    /**
     * 把信息发送给模型，让模型进行信息提取
     *
     * @param prompt
     * @param maxToken
     * @param temperature
     * @return
     */
    public String callExtractionModel(String prompt, int maxToken, double temperature) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", config.getCurrentModel());
            ArrayNode messageArray = objectMapper.createArrayNode();
            ObjectNode messageObject = objectMapper.createObjectNode();

            messageObject.put("role", "user");
            messageObject.put("content", prompt);
            messageArray.add(messageObject);

            requestBody.set("messages", messageArray);
            requestBody.put("max_token", maxToken);
            requestBody.put("temperature", temperature);

            //将json数据放入请求体里面
            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

            //构建请求
            Request requestBuilder = new Request.Builder()
                    .url(config.getBaseUrl() + "/chat/completions")
                    .method("POST", body)//将请求体放入请求
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .build();

            //发送请求
            try (Response response = httpClient.newCall(requestBuilder).execute()) {//获得模型返回的数据
                if (!response.isSuccessful()) {
                    logger.error("请求失败: {} {} {}", response.code(), response.message(), response.body());
                    throw new RuntimeException("请求失败: " + response.code() + " " + response.message() + " " + response.body());
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode choiceNode = root.get("choices");

                if (choiceNode != null && choiceNode.isArray()) {
                    JsonNode firstChoice = choiceNode.get(0);
                    JsonNode message = firstChoice.get("message");
                    return message.get("content").asText();
                }

            } catch (IOException e) {
                logger.error("Extraction model call failed: {}", e.getMessage(), e);
            }

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize extraction request", e);
        }

        return "";
    }
}
