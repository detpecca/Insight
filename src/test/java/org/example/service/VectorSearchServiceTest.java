package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量检索距离阈值过滤测试（P2-17）
 * L2 距离越小越相似。
 */
class VectorSearchServiceTest {

    private VectorSearchService.SearchResult resultWithScore(float score) {
        VectorSearchService.SearchResult r = new VectorSearchService.SearchResult();
        r.setId("id-" + score);
        r.setContent("content");
        r.setScore(score);
        return r;
    }

    @Test
    void zeroThresholdKeepsAllResults() {
        List<VectorSearchService.SearchResult> results = Arrays.asList(
                resultWithScore(0.5f), resultWithScore(3.0f), resultWithScore(10f));
        List<VectorSearchService.SearchResult> filtered = VectorSearchService.filterByDistance(results, 0);
        assertEquals(3, filtered.size(), "阈值为 0 时不应过滤");
        assertSame(results, filtered);
    }

    @Test
    void negativeThresholdKeepsAllResults() {
        List<VectorSearchService.SearchResult> results = Arrays.asList(
                resultWithScore(0.5f), resultWithScore(3.0f));
        assertEquals(2, VectorSearchService.filterByDistance(results, -1).size());
    }

    @Test
    void resultsAboveThresholdAreDropped() {
        List<VectorSearchService.SearchResult> results = Arrays.asList(
                resultWithScore(0.5f),   // 保留
                resultWithScore(1.2f),   // 保留
                resultWithScore(3.0f),   // 过滤
                resultWithScore(10.0f)); // 过滤
        List<VectorSearchService.SearchResult> filtered = VectorSearchService.filterByDistance(results, 2.0);
        assertEquals(2, filtered.size());
        assertTrue(filtered.get(0).getScore() < 2.0);
        assertTrue(filtered.get(1).getScore() < 2.0);
    }

    @Test
    void emptyAndNullAreHandled() {
        assertTrue(VectorSearchService.filterByDistance(Collections.emptyList(), 1.0).isEmpty());
        assertTrue(VectorSearchService.filterByDistance(null, 1.0).isEmpty());
        assertTrue(VectorSearchService.filterByDistance(null, 0).isEmpty());
    }

    @Test
    void allFilteredYieldsEmpty() {
        List<VectorSearchService.SearchResult> results = Arrays.asList(
                resultWithScore(5.0f), resultWithScore(8.0f));
        assertTrue(VectorSearchService.filterByDistance(results, 1.0).isEmpty(),
                "全部超过阈值时应返回空列表（让上层走'知识库无相关内容'分支）");
    }
}
