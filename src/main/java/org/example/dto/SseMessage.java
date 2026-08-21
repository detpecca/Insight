package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 统一 SSE 流式消息格式
 * 适用于所有 SSE 流式返回模式的对话接口
 */
@Getter
@Setter
public class SseMessage {

    /** content: 内容块, error: 错误, done: 完成, progress: 进度事件 */
    private String type;
    private String data;

    public static SseMessage content(String data) {
        SseMessage message = new SseMessage();
        message.setType("content");
        message.setData(data);
        return message;
    }

    public static SseMessage error(String errorMessage) {
        SseMessage message = new SseMessage();
        message.setType("error");
        message.setData(errorMessage);
        return message;
    }

    public static SseMessage done() {
        SseMessage message = new SseMessage();
        message.setType("done");
        message.setData(null);
        return message;
    }

    /**
     * 进度事件（如 AIOps 各节点完成通知）。
     * 既有前端对未知 type 会静默忽略，因此向后兼容。
     */
    public static SseMessage progress(String data) {
        SseMessage message = new SseMessage();
        message.setType("progress");
        message.setData(data);
        return message;
    }
}
