package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    /**
     * L2 距离阈值：仅保留距离小于该值的结果（越小越严格）。
     * 0 或负数表示不过滤（保持 topK 语义）。
     * 启用后可避免知识库里没有相关内容时，模型仍基于无关文档作答（防幻觉）。
     */
    @Value("${rag.max-l2-distance:0}")
    private double maxL2Distance;

    /**
     * 搜索相似文档
     *
     * @param query 查询文本
     * @param topK  返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        try {
            logger.info("开始搜索相似文档, 查询: {}, topK: {}", query, topK);

            // 1. 将查询文本向量化 generateEmbedding
            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            // 2. 构建搜索参数
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(topK)
                    .withMetricType(io.milvus.param.MetricType.L2)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withParams("{\"nprobe\":10}")
                    .build();

            // 3. 执行搜索
            R<SearchResults> searchResponse = milvusClient.search(searchParam);

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
            }

            // 4. 解析搜索结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> results = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());

                // 解析 metadata
                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }

                results.add(result);
            }

            logger.info("搜索完成, 找到 {} 个相似文档", results.size());
            return filterByDistance(results, maxL2Distance);

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按 L2 距离阈值过滤结果（L2 越小越相似）。
     * maxDistance &lt;= 0 时不过滤。包级可见便于单元测试。
     */
    static List<SearchResult> filterByDistance(List<SearchResult> results, double maxDistance) {
        if (maxDistance <= 0 || results == null || results.isEmpty()) {
            return results == null ? Collections.emptyList() : results;
        }
        List<SearchResult> filtered = results.stream()
                .filter(r -> r.getScore() < maxDistance)
                .collect(Collectors.toList());
        if (filtered.size() < results.size()) {
            logger.info("距离阈值过滤: {} -> {} 条 (阈值 {})", results.size(), filtered.size(), maxDistance);
        }
        return filtered;
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;

    }
}
