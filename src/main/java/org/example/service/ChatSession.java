package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单个会话的历史消息管理。
 * 线程安全；按"消息对"（用户消息 + AI 回复）滑动窗口。
 */
public class ChatSession {

    private static final Logger logger = LoggerFactory.getLogger(ChatSession.class);

    private final String sessionId;
    private final long createTime;
    private final ReentrantLock lock = new ReentrantLock();

    // 以消息对为单位的队列，保证成对淘汰
    private final Deque<Map<String, String>> messagePairs = new ArrayDeque<>();
    private volatile long lastAccessTime;

    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
        this.createTime = System.currentTimeMillis();
        this.lastAccessTime = this.createTime;
    }

    /**
     * 添加一对消息（用户问题 + AI 回复），并保持窗口大小
     */
    public void addMessage(String userQuestion, String aiAnswer, int maxWindowPairs) {
        lock.lock();
        try {
            Map<String, String> pair = new HashMap<>();
            pair.put("user", userQuestion);
            pair.put("assistant", aiAnswer);
            messagePairs.addLast(pair);

            while (messagePairs.size() > maxWindowPairs) {
                messagePairs.removeFirst();
            }
            touch();

            logger.debug("会话 {} 更新历史消息，当前消息对数: {}", sessionId, messagePairs.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取历史消息副本（展开为 role/content 列表）
     */
    public List<Map<String, String>> getHistory() {
        lock.lock();
        try {
            touch();
            List<Map<String, String>> history = new ArrayList<>(messagePairs.size() * 2);
            for (Map<String, String> pair : messagePairs) {
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", pair.get("user"));
                history.add(userMsg);

                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", pair.get("assistant"));
                history.add(assistantMsg);
            }
            return history;
        } finally {
            lock.unlock();
        }
    }

    public void clearHistory() {
        lock.lock();
        try {
            messagePairs.clear();
            touch();
            logger.info("会话 {} 历史消息已清空", sessionId);
        } finally {
            lock.unlock();
        }
    }

    public int getMessagePairCount() {
        lock.lock();
        try {
            return messagePairs.size();
        } finally {
            lock.unlock();
        }
    }

    /** 仅刷新访问时间，不返回数据 */
    public void touch() {
        this.lastAccessTime = System.currentTimeMillis();
    }

    public long getLastAccessTime() {
        return lastAccessTime;
    }

    public long getCreateTime() {
        return createTime;
    }

    public String getSessionId() {
        return sessionId;
    }
}
