package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档分片测试（纯逻辑，不依赖外部服务）
 */
class DocumentChunkServiceTest {

    private DocumentChunkService service;
    private DocumentChunkConfig config;

    @BeforeEach
    void setUp() throws Exception {
        config = new DocumentChunkConfig();
        config.setMaxSize(200);
        config.setOverlap(50);
        service = new DocumentChunkService();
        // 注入配置（service 内部是 @Autowired 字段，测试中用反射装配）
        Field f = DocumentChunkService.class.getDeclaredField("chunkConfig");
        f.setAccessible(true);
        f.set(service, config);
    }

    @Test
    void emptyContentReturnsNoChunks() {
        assertTrue(service.chunkDocument(null, "x.md").isEmpty());
        assertTrue(service.chunkDocument("   ", "x.md").isEmpty());
    }

    @Test
    void shortDocumentIsSingleChunk() {
        List<org.example.dto.DocumentChunk> chunks = service.chunkDocument("短文档内容", "x.md");
        assertEquals(1, chunks.size());
        assertEquals("短文档内容", chunks.get(0).getContent());
    }

    @Test
    void markdownHeadingsSplitSections() {
        String doc = "# 第一章\n" + "A".repeat(50) + "\n\n# 第二章\n" + "B".repeat(50);
        List<org.example.dto.DocumentChunk> chunks = service.chunkDocument(doc, "x.md");
        assertTrue(chunks.size() >= 2, "按标题至少切成 2 节");
        // 第二章标题应出现在某个分片的 title 中
        assertTrue(chunks.stream().anyMatch(c -> "第二章".equals(c.getTitle())));
    }

    @Test
    void longSectionIsSplitWithSizeLimit() {
        // 无标题，一整段超长文本，按段落+大小限制切分
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("这是一个用于测试分片大小限制的段落").append(i).append("。\n\n");
        }
        List<org.example.dto.DocumentChunk> chunks = service.chunkDocument(sb.toString(), "x.md");
        assertTrue(chunks.size() > 1, "长文本应被切成多片");
        for (org.example.dto.DocumentChunk c : chunks) {
            assertNotNull(c.getContent());
            // 单片内容不应显著超过上限（段落本身不超限时成立）
            assertTrue(c.getContent().length() <= config.getMaxSize() + 50,
                    "分片过大: " + c.getContent().length());
        }
    }

    @Test
    void chunkIndexIsSequential() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("段落").append(i).append("内容填充。\n\n");
        }
        List<org.example.dto.DocumentChunk> chunks = service.chunkDocument(sb.toString(), "x.md");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getChunkIndex(), "chunkIndex 应连续递增");
        }
    }
}
