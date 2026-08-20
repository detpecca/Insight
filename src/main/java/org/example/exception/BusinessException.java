package org.example.exception;

/**
 * 业务异常：消息可安全展示给客户端。
 * 由 GlobalExceptionHandler 转换为对应 HTTP 状态码。
 */
public class BusinessException extends RuntimeException {

    private final int status;

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    public static BusinessException internal(String message) {
        return new BusinessException(500, message);
    }

    public int getStatus() {
        return status;
    }
}
