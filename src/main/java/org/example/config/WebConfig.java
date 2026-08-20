package org.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web MVC 配置（中文编码）
 * <p>
 * 使用 extendMessageConverters 而非 configureMessageConverters：
 * 后者一旦添加自定义 converter，Spring Boot 默认注册的一整套 converter 将不再加载，
 * 容易引入难以察觉的序列化差异。这里只在默认列表基础上增强字符串编码。
 * <p>
 * 注意：不再手动注册 ObjectMapper Bean，以免覆盖 Spring Boot 自动配置的
 * Jackson 定制（如 JSR-310 时间序列化等）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 将默认 StringHttpMessageConverter 的字符集改为 UTF-8（默认 ISO-8859-1，中文会乱码）
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof StringHttpMessageConverter stringConverter) {
                stringConverter.setDefaultCharset(StandardCharsets.UTF_8);
                stringConverter.setWriteAcceptCharset(false);
            }
        }
    }
}
