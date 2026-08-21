package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 聊天请求。
 * 保留大写 Id / Question 主字段名以兼容既有前端契约（同时接受小写别名）。
 */
@Getter
@Setter
public class ChatRequest {

    @JsonProperty(value = "Id")
    @JsonAlias({"id", "ID"})
    private String Id;

    @JsonProperty(value = "Question")
    @JsonAlias({"question", "QUESTION"})
    private String Question;
}
