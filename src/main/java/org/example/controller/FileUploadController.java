package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.FileUploadRes;
import org.example.exception.BusinessException;
import org.example.config.FileUploadConfig;
import org.example.service.VectorIndexService;
import org.example.util.SafePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<FileUploadRes>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "文件不能为空"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "文件名不能为空"));
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions()));
        }

        try {
            // 安全解析目标路径：只取文件名部分，且必须仍落在上传目录内，
            // 防止 "../xxx" 这类路径穿越导致目录外文件被删除/覆盖（见 SafePaths 及其测试）
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).toAbsolutePath().normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
            Path filePath = SafePaths.resolveInside(uploadDir, originalFilename).orElse(null);
            if (filePath == null) {
                logger.warn("拒绝疑似路径穿越的上传请求: {}", originalFilename);
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "非法文件名"));
            }

            // 如果文件已存在，先删除旧文件（实现覆盖更新）
            if (Files.exists(filePath)) {
                logger.info("文件已存在，将覆盖: {}", filePath);
                Files.delete(filePath);
            }

            Files.copy(file.getInputStream(), filePath);
            logger.info("文件上传成功: {}", filePath);

            // 文件上传成功后，自动调用向量索引服务；
            // 索引失败时明确返回错误（文件保留在磁盘，可重试），而不是静默"成功"
            try {
                logger.info("开始为上传文件创建向量索引: {}", filePath);
                vectorIndexService.indexSingleFile(filePath.toString());
                logger.info("向量索引创建成功: {}", filePath);
            } catch (Exception e) {
                logger.error("向量索引创建失败: {}", filePath, e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error(500, "文件已保存，但知识库索引失败，请重试"));
            }

            FileUploadRes response = new FileUploadRes(
                    originalFilename,
                    filePath.toString(),
                    file.getSize()
            );
            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (IOException e) {
            logger.error("文件上传失败: {}", originalFilename, e);
            throw BusinessException.internal("文件保存失败，请重试");
        }
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }
}
