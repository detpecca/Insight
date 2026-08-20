package org.example.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径穿越防护测试（P0-2 的核心安全逻辑）
 */
class SafePathsTest {

    private final Path baseDir = Path.of("D:/workspace/Insight/uploads").normalize();

    @Test
    void normalFileNameIsAccepted() {
        Optional<Path> result = SafePaths.resolveInside(baseDir, "runbook.md");
        assertTrue(result.isPresent());
        assertEquals(baseDir.resolve("runbook.md"), result.get());
    }

    @Test
    void dotDotTraversalIsRejected() {
        assertTrue(SafePaths.resolveInside(baseDir, "../server.pid").isEmpty());
        assertTrue(SafePaths.resolveInside(baseDir, "..\\..\\Windows\\System32\\cmd.exe").isEmpty());
        assertTrue(SafePaths.resolveInside(baseDir, "a/b/../../..").isEmpty());
        assertTrue(SafePaths.resolveInside(baseDir, "..").isEmpty());
        assertTrue(SafePaths.resolveInside(baseDir, ".").isEmpty());
    }

    @Test
    void absoluteAndNestedPathsAreRejected() {
        // 任何带分隔符的名字都拒绝（fail-closed），而不是替客户端"猜"文件名
        assertTrue(SafePaths.resolveInside(baseDir, "C:/evil/hack.md").isEmpty());
        assertTrue(SafePaths.resolveInside(baseDir, "a/b.md").isEmpty());
        assertTrue(SafePaths.resolveInside(baseDir, "/etc/passwd").isEmpty());
    }

    @Test
    void emptyAndNullAreRejected() {
        assertTrue(SafePaths.resolveInside(baseDir, null).isEmpty());
        assertTrue(SafePaths.resolveInside(baseDir, "").isEmpty());
        assertTrue(SafePaths.resolveInside(baseDir, "   ").isEmpty());
    }

    @Test
    void nullByteIsRejected() {
        assertTrue(SafePaths.resolveInside(baseDir, "file\u0000.md").isEmpty());
    }

    @Test
    void resultNeverEscapesBaseDir() {
        // 任意构造的恶意名字，解析结果要么为空，要么必须在 baseDir 内
        String[] malicious = {
                "..", "./..", "...", "....//", "a/../b/../../..",
                "file\u0000.md", "/etc/passwd", "\\windows\\system32", "..%2f..%2fetc"
        };
        for (String name : malicious) {
            Optional<Path> result = SafePaths.resolveInside(baseDir, name);
            result.ifPresent(p -> assertTrue(p.startsWith(baseDir),
                    "恶意文件名逃逸了 baseDir: " + name + " -> " + p));
        }
    }
}
