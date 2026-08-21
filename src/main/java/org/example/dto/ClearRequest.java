package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 清空会话请求。
 */
@Getter
@Setter
public class ClearRequest {

    @NotBlank(message = "会话ID不能为空")
    @JsonProperty(value = "Id")
    @JsonAlias({"id", "ID"})
    private String Id;
}
