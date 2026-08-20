package org.example.util;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 路径安全工具
 */
public final class SafePaths {

    private SafePaths() {
        // 工具类，禁止实例化
    }

    /**
     * 将客户端提供的文件名安全地解析到 baseDir 内。
     * <p>
     * 采用"fail-closed"策略：只接受【纯文件名】，任何带路径分隔符（/ 或 \）、
     * 遍历段（"." / ".."）或空字符的输入一律拒绝，而不是猜测客户端意图去裁剪。
     * 合法的浏览器上传（RFC 6266 下只发送文件名）不受影响。
     * <p>
     * 即便通过了上述检查，resolve + normalize 之后仍会兜底校验结果必须落在
     * baseDir 内，双保险。
     *
     * @param baseDir          基准目录（建议传入方已 toAbsolutePath().normalize()）
     * @param originalFilename 客户端提供的原始文件名
     * @return 安全的目标路径；非法时返回 empty
     */
    public static Optional<Path> resolveInside(Path baseDir, String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return Optional.empty();
        }
        // 拒绝任何包含路径分隔符、遍历段或空字符的文件名（跨平台一致，fail-closed）
        if (originalFilename.indexOf('/') >= 0
                || originalFilename.indexOf('\\') >= 0
                || originalFilename.indexOf('\0') >= 0
                || "..".equals(originalFilename)
                || ".".equals(originalFilename)) {
            return Optional.empty();
        }
        try {
            Path fileNamePart = Path.of(originalFilename).getFileName();
            if (fileNamePart == null) {
                return Optional.empty();
            }
            Path resolved = baseDir.resolve(fileNamePart.toString()).normalize();
            if (!resolved.startsWith(baseDir) || resolved.equals(baseDir)) {
                return Optional.empty();
            }
            return Optional.of(resolved);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
