package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 会话信息响应
 */
@Getter
@Setter
public class SessionInfoResponse {

    private String sessionId;
    private int messagePairCount;
    private long createTime;
}
