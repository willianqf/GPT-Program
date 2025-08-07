package me.alexisprado.gchatgpt;

import org.json.JSONObject;

public class ChatMessage {
    private final String role;
    private final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public JSONObject toJson() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("role", role);
        jsonObject.put("content", content);
        return jsonObject;
    }
}