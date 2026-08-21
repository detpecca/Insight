package org.example.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    /** 非空校验 + 长度上限，防止超大输入直接灌进 LLM 烧 token */
    @NotBlank(message = "问题内容不能为空")
    @Size(max = 4000, message = "问题长度不能超过 4000 字符")
    @JsonProperty(value = "Question")
    @JsonAlias({"question", "QUESTION"})
    private String Question;
}
