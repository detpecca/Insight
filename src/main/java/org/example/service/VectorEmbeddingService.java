package org.example.service;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量嵌入服务。
 * 走 Spring AI 的 {@link DashScopeEmbeddingModel}（由 spring-ai-alibaba-starter-dashscope
 * 自动装配，api-key 取自 spring.ai.dashscope.api-key），与聊天路径统一为一条
 * DashScope 集成链路，不再依赖原生 dashscope-sdk-java 及其全局静态状态。
 */
@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    private final DashScopeEmbeddingModel embeddingModel;

    @Autowired
    public VectorEmbeddingService(DashScopeEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        logger.info("向量嵌入服务初始化完成（Spring AI DashScopeEmbeddingModel）");
    }

    /**
     * 生成向量嵌入
     *
     * @param content 文本内容
     * @return 向量嵌入（浮点数列表）
     */
    public List<Float> generateEmbedding(String content) {
        if (content == null || content.trim().isEmpty()) {
            logger.warn("内容为空，无法生成向量");
            throw new IllegalArgumentException("内容不能为空");
        }

        try {
            List<Float> vector = toFloatList(embeddingModel.embed(content));
            if (vector.isEmpty()) {
                throw new RuntimeException("Embedding API 返回空向量");
            }
            logger.debug("成功生成向量嵌入, 内容长度: {} 字符, 向量维度: {}",
                    content.length(), vector.size());
            return vector;
        } catch (Exception e) {
            logger.error("生成向量嵌入失败, 内容长度: {}", content.length(), e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量生成向量嵌入（单次 Embedding API 调用）。
     *
     * @param contents 文本内容列表
     * @return 向量嵌入列表，与输入顺序一一对应
     */
    public List<List<Float>> generateEmbeddings(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            logger.warn("内容列表为空，无法生成向量");
            return Collections.emptyList();
        }

        try {
            logger.info("开始批量生成向量嵌入, 数量: {}", contents.size());
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(contents, null));

            List<List<Float>> embeddings = new ArrayList<>();
            response.getResults().forEach(r -> embeddings.add(toFloatList(r.getOutput())));

            logger.info("成功批量生成向量嵌入, 数量: {}, 维度: {}",
                    embeddings.size(), embeddings.isEmpty() ? 0 : embeddings.get(0).size());
            return embeddings;
        } catch (Exception e) {
            logger.error("批量生成向量嵌入失败", e);
            throw new RuntimeException("批量生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成查询向量
     *
     * @param query 查询文本
     * @return 向量嵌入
     */
    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }

    /**
     * 计算两个向量的余弦相似度
     *
     * @param vector1 向量1
     * @param vector2 向量2
     * @return 余弦相似度 [-1, 1]
     */
    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }

        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private static List<Float> toFloatList(float[] raw) {
        List<Float> result = new ArrayList<>(raw.length);
        for (float v : raw) {
            result.add(v);
        }
        return result;
    }
}
