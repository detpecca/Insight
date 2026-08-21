package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.UpsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
                    
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     * 
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        // 将字符串路径解析为 Path 对象
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);

        // 1. 读取文件内容
        String content = Files.readString(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());

        // 2. 删除该文件的旧数据（如果存在）
        deleteExistingData(path.toString());

        // 3. 文档分片
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        if (chunks.isEmpty()) {
            logger.warn("文档分片结果为空，跳过索引: {}", filePath);
            return;
        }

        // 4. 为所有分片生成向量（逐条调用 embedding API），然后一次性批量写入 Milvus
        List<String> ids = new ArrayList<>(chunks.size());
        List<String> contents = new ArrayList<>(chunks.size());
        List<List<Float>> vectors = new ArrayList<>(chunks.size());
        List<com.google.gson.JsonObject> metadataJsons = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            try {
                // 防御：content 字段在 schema 中有长度上限，超长分片直接拒绝，
                // 避免整批 upsert 在 Milvus 端失败
                if (chunk.getContent() != null && chunk.getContent().length() > MilvusConstants.CONTENT_MAX_LENGTH) {
                    throw new IllegalStateException(String.format(
                            "分片 %d 长度 %d 超过 Milvus content 字段上限 %d",
                            chunk.getChunkIndex(), chunk.getContent().length(), MilvusConstants.CONTENT_MAX_LENGTH));
                }
                List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());
                Map<String, Object> metadata = buildMetadata(path.toString(), chunk, chunks.size());

                // 确定性 ID：_source + 分片索引，保证重复索引时按主键覆盖而不是产生重复行
                String source = (String) metadata.get("_source");
                String id = UUID.nameUUIDFromBytes((source + "_" + chunk.getChunkIndex()).getBytes()).toString();

                com.google.gson.Gson gson = new com.google.gson.Gson();
                ids.add(id);
                contents.add(chunk.getContent());
                vectors.add(vector);
                metadataJsons.add(gson.toJsonTree(metadata).getAsJsonObject());
            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 处理失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }

        try {
            int upserted = batchUpsert(ids, contents, vectors, metadataJsons);
            logger.info("文件索引完成: {}, 共 {} 个分片, 写入 {} 条向量", filePath, chunks.size(), upserted);
        } catch (Exception e) {
            logger.error("批量写入 Milvus 失败: {}", filePath, e);
            throw new RuntimeException("批量写入 Milvus 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除文件的旧数据（根据 metadata._source）
     * collection 已在应用启动时统一加载（见 MilvusClientFactory），此处无需重复加载。
     */
    private void deleteExistingData(String filePath) {
        try {
            // 使用统一的路径分隔符（正斜杠）用于Milvus存储，避免表达式解析错误
            // 将系统路径转换为统一格式
            Path path = Paths.get(filePath).normalize();
            String normalizedPath = path.toString().replace(File.separator, "/");

            // 构建删除表达式：metadata["_source"] == "xxx"
            String expr = String.format("metadata[\"_source\"] == \"%s\"", normalizedPath);

            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);

            if (response.getStatus() != 0) {
                logger.warn("删除旧数据时出现警告: {}", response.getMessage());
            } else {
                long deletedCount = response.getData().getDeleteCnt();
                logger.info("✓ 已删除文件的旧数据: {}, 删除记录数: {}", normalizedPath, deletedCount);
            }

        } catch (Exception e) {
            logger.warn("删除旧数据失败（可能是首次索引）: {}", e.getMessage());
        }
    }

    /**
     * 构建元数据（包含文件信息）
     */
    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();
        
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separator, "/");
        
        // 文件信息
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        
        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        
        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        
        return metadata;
    }

    /**
     * 批量 upsert 向量到 Milvus（单次 RPC）。
     * 相比旧的逐条 insert：
     * 1. 一次 RPC 完成，减少网络往返；
     * 2. upsert 按主键覆盖，配合确定性 ID（_source + chunkIndex），
     *    重复索引同一文件不会产生重复数据，也规避了 delete 异步生效带来的竞态。
     * collection 已在应用启动时统一加载（见 MilvusClientFactory）。
     *
     * @return 写入的记录数
     */
    private int batchUpsert(List<String> ids, List<String> contents,
                            List<List<Float>> vectors, List<com.google.gson.JsonObject> metadataJsons) {
        List<UpsertParam.Field> fields = new ArrayList<>();
        fields.add(new UpsertParam.Field("id", ids));
        fields.add(new UpsertParam.Field("content", contents));
        fields.add(new UpsertParam.Field("vector", vectors));
        fields.add(new UpsertParam.Field("metadata", metadataJsons));

        UpsertParam upsertParam = UpsertParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFields(fields)
                .build();

        R<MutationResult> response = milvusClient.upsert(upsertParam);

        if (response.getStatus() != 0) {
            throw new RuntimeException("upsert 向量失败: " + response.getMessage());
        }

        long count = response.getData().getUpsertCnt();
        logger.info("批量 upsert 完成, 请求 {} 条, 实际 {} 条", ids.size(), count);
        return (int) count;
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
