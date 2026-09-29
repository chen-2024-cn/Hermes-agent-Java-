package com.cyk.bean;

/**
 * Represents a tool call from the LLM.
 */
public class ToolCall {
    
    private String id;
    private String type = "function";
    private Function function;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public Function getFunction() { return function; }
    public void setFunction(Function function) { this.function = function; }
    

}
