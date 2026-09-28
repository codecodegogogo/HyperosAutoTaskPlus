package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 动作链的先进先出（FIFO）执行队列，纯逻辑，不依赖 Android，方便 JVM 测试。
 *
 * 约定：一次只跑一条链。有链在跑时新触发的进入等待队列；队列满则丢弃。
 * 真正的回放和通知由 ActionChainPlayer 驱动，这里只管"谁在跑、谁在等、下一个是谁"。
 */
final class ActionChainQueue {

    static final int MAX_WAITING = 5;

    /** 一次排队请求：链 id + 触发它的任务 uuid（用来查任务名做通知） */
    static final class Request {
        final String chainId;
        final String taskUuid;
        Request(String chainId, String taskUuid) {
            this.chainId = chainId;
            this.taskUuid = taskUuid;
        }
    }

    enum Result { STARTED, QUEUED, DROPPED }

    private final Deque<Request> waiting = new ArrayDeque<>();
    private Request running;

    /**
     * 提交一次触发。
     *   STARTED 队列空闲，应立即开始这条；
     *   QUEUED  已有链在跑，进入等待；
     *   DROPPED 等待队列已满，丢弃。
     */
    synchronized Result submit(Request request) {
        if (running == null && waiting.isEmpty()) {
            running = request;
            return Result.STARTED;
        }
        if (waiting.size() >= MAX_WAITING) return Result.DROPPED;
        waiting.addLast(request);
        return Result.QUEUED;
    }

    /** 当前这条跑完，取出下一条（没有则返回 null，队列转为空闲）。 */
    synchronized Request advance() {
        running = waiting.pollFirst();
        return running;
    }

    synchronized Request running() { return running; }
    synchronized int waitingCount() { return waiting.size(); }

    /** 清空（进程回收 / 出错兜底）。 */
    synchronized void clear() {
        running = null;
        waiting.clear();
    }
}
