package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 统一聊天响应格式
 * 适用于所有普通返回模式的对话接口
 */
@Getter
@Setter
public class ChatResponse {

    private boolean success;
    private String answer;
    private String errorMessage;

    public static ChatResponse success(String answer) {
        ChatResponse response = new ChatResponse();
        response.setSuccess(true);
        response.setAnswer(answer);
        return response;
    }

    public static ChatResponse error(String errorMessage) {
        ChatResponse response = new ChatResponse();
        response.setSuccess(false);
        response.setErrorMessage(errorMessage);
        return response;
    }
}
