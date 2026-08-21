package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 清空会话请求。
 */
@Getter
@Setter
public class ClearRequest {

    @JsonProperty(value = "Id")
    @JsonAlias({"id", "ID"})
    private String Id;
}
