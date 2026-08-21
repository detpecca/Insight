package org.example.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * DashScope 连接配置。
 * <p>
 * 标准对话模型由 spring-ai-alibaba 自动装配（spring.ai.dashscope.chat.options），
 * 这里补充两个手动 Bean：
 * 1. {@link DashScopeApi} — 全局单例（含 HTTP 超时），取代旧的"每请求新建"；
 * 2. {@code aiOpsChatModel} — AIOps 专用模型（temperature 0.3 / maxToken 8000），
 *    从 ChatController 下沉到配置层。
 */
@Configuration
public class DashScopeConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Value("${spring.ai.dashscope.chat.options.timeout:180000}")
    private long timeout;

    /**
     * 配置 RestClient.Builder，设置超时时间。
     * Spring AI 的自动装配（chat/embedding）与下方手动创建的 DashScopeApi 都会使用它。
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        // 创建自定义的 OkHttpClient，设置超时时间
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(timeout))
                .readTimeout(Duration.ofMillis(timeout))
                .writeTimeout(Duration.ofMillis(timeout))
                .callTimeout(Duration.ofMillis(timeout))
                .build();

        // 创建 RestClient.Builder 并配置 OkHttpClient
        return RestClient.builder()
                .requestFactory(new OkHttp3ClientHttpRequestFactory(okHttpClient));
    }

    /**
     * 全局单例 DashScopeApi（HTTP 客户端不再每请求重建）。
     */
    @Bean
    public DashScopeApi dashScopeApi(RestClient.Builder restClientBuilder) {
        return DashScopeApi.builder()
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();
    }

    /**
     * AIOps 多 Agent 编排专用模型：低温度、大输出窗口。
     */
    @Bean("aiOpsChatModel")
    public DashScopeChatModel aiOpsChatModel(DashScopeApi dashScopeApi) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.3)
                        .withMaxToken(8000)
                        .withTopP(0.9)
                        .build())
                .build();
    }
}
