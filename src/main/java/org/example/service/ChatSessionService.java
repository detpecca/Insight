package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话管理服务。
 * 相比旧实现增加了：空闲超时淘汰、会话数上限、定时清理，防止 Map 无限增长导致 OOM。
 */
@Service
public class ChatSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionService.class);

    /** 最大历史消息窗口大小（成对计算：用户消息+AI回复=1对） */
    public static final int MAX_WINDOW_SIZE = 6;

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final Duration idleTimeout;
    private final int maxSessions;
    private final Random random = new Random();

    public ChatSessionService(
            @Value("${session.idle-timeout-minutes:30}") long idleTimeoutMinutes,
            @Value("${session.max-sessions:1000}") int maxSessions) {
        this.idleTimeout = Duration.ofMinutes(idleTimeoutMinutes);
        this.maxSessions = maxSessions;
        logger.info("会话服务初始化: 空闲超时={}分钟, 会话上限={}", idleTimeoutMinutes, maxSessions);
    }

    /**
     * 获取或创建会话。创建前先执行容量控制。
     */
    public ChatSession getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        String id = sessionId;
        ChatSession session = sessions.get(id);
        if (session != null) {
            session.touch();
            return session;
        }
        enforceCapacity();
        return sessions.computeIfAbsent(id, ChatSession::new);
    }

    public Optional<ChatSession> get(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        ChatSession session = sessions.get(sessionId);
        if (session != null) {
            session.touch();
        }
        return Optional.ofNullable(session);
    }

    /**
     * 清空指定会话的历史，返回是否找到该会话
     */
    public boolean clearHistory(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        session.clearHistory();
        return true;
    }

    /**
     * 定时清理空闲超时的会话（每 5 分钟执行一次）
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void evictIdleSessions() {
        long cutoff = System.currentTimeMillis() - idleTimeout.toMillis();
        List<String> removed = new ArrayList<>();
        sessions.forEach((id, session) -> {
            if (session.getLastAccessTime() < cutoff) {
                removed.add(id);
                sessions.remove(id, session);
            }
        });
        if (!removed.isEmpty()) {
            logger.info("已淘汰 {} 个空闲超时会话, 剩余 {}", removed.size(), sessions.size());
        }
        enforceCapacity();
    }

    /**
     * 容量控制：达到上限时先随机淘汰（近似 LRU，避免全量排序开销），
     * 保证 sessions.size() 始终不超过 maxSessions
     */
    private void enforceCapacity() {
        while (sessions.size() >= maxSessions && !sessions.isEmpty()) {
            List<String> ids = new ArrayList<>(sessions.keySet());
            String victim = ids.get(random.nextInt(ids.size()));
            if (sessions.remove(victim) != null) {
                logger.info("会话数达到上限 {}, 随机淘汰会话: {}", maxSessions, victim);
            }
        }
    }

    /** 当前会话数（用于监控与测试） */
    public int size() {
        return sessions.size();
    }
}
