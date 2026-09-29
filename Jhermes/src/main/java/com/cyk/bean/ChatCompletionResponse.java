package com.cyk.bean;

public class ChatCompletionResponse {
    private ModelMessage message;
    private String finishReason;
    private Usage usage;

    public ModelMessage getMessage() {
        return message;
    }

    public void setMessage(ModelMessage message) {
        this.message = message;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public boolean hasToolCalls() {
        return message != null && message.getToolCalls() != null && !message.getToolCalls().isEmpty();
    }
}