package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话窗口与淘汰策略测试（P1-7）
 */
class ChatSessionServiceTest {

    private ChatSessionService newService(long idleMinutes, int maxSessions) {
        return new ChatSessionService(idleMinutes, maxSessions);
    }

    @Test
    void getOrCreateReturnsSameSessionForSameId() {
        ChatSessionService service = newService(30, 10);
        ChatSession a = service.getOrCreate("s1");
        ChatSession b = service.getOrCreate("s1");
        assertEquals(a, b);
        assertEquals(1, service.size());
    }

    @Test
    void blankSessionIdGeneratesNewSession() {
        ChatSessionService service = newService(30, 10);
        ChatSession a = service.getOrCreate(null);
        ChatSession b = service.getOrCreate("");
        assertTrue(a != b);
        assertEquals(2, service.size());
    }

    @Test
    void historyWindowKeepsOnlyLastNPairs() {
        ChatSessionService service = newService(30, 10);
        ChatSession session = service.getOrCreate("s");
        // 写入 MAX_WINDOW_SIZE + 3 对消息
        int total = ChatSessionService.MAX_WINDOW_SIZE + 3;
        for (int i = 0; i < total; i++) {
            session.addMessage("问题" + i, "回答" + i, ChatSessionService.MAX_WINDOW_SIZE);
        }
        assertEquals(ChatSessionService.MAX_WINDOW_SIZE, session.getMessagePairCount());

        List<Map<String, String>> history = session.getHistory();
        // 最旧的 3 对已被淘汰，剩下窗口内的第一条用户消息应是 "问题3"
        assertEquals("问题3", history.get(0).get("content"));
        // 最后一条是最新回答
        assertEquals("回答" + (total - 1), history.get(history.size() - 1).get("content"));
    }

    @Test
    void clearHistoryResetsSession() {
        ChatSessionService service = newService(30, 10);
        service.getOrCreate("s").addMessage("q", "a", ChatSessionService.MAX_WINDOW_SIZE);
        assertTrue(service.clearHistory("s"));
        assertEquals(0, service.getOrCreate("s").getMessagePairCount());
    }

    @Test
    void clearHistoryReturnsFalseForUnknownSession() {
        ChatSessionService service = newService(30, 10);
        assertEquals(false, service.clearHistory("nope"));
    }

    @Test
    void sessionCountIsCappedAtMaxSessions() {
        int max = 5;
        ChatSessionService service = newService(30, max);
        for (int i = 0; i < 20; i++) {
            service.getOrCreate("s" + i);
        }
        assertTrue(service.size() <= max, "会话数应不超过上限, 实际: " + service.size());
    }

    @Test
    void idleSessionsAreEvicted() throws InterruptedException {
        ChatSessionService service = newService(0, 100); // 0 分钟 => 立即过期
        service.getOrCreate("s1");
        Thread.sleep(20); // 确保 lastAccessTime 严格早于淘汰判定时刻
        service.evictIdleSessions();
        assertEquals(0, service.size());
    }

    @Test
    void nonIdleSessionsSurviveEviction() {
        ChatSessionService service = newService(60, 100);
        service.getOrCreate("s1");
        service.evictIdleSessions();
        assertEquals(1, service.size());
    }
}
