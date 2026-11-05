package com.monitor.agent.reporter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 内存上报队列
 * 所有拦截器将数据放入此队列，后台线程批量上报
 */
public class ReportQueue {

    private static final int MAX_QUEUE_SIZE = 20000;
    private static final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    public static boolean offer(Object record) {
        boolean accepted = queue.offer(record);
        if (!accepted) {
            // 队列满了，丢弃最老的一条
            queue.poll();
            queue.offer(record);
        }
        return accepted;
    }

    /**
     * 上报失败时把整批数据放回队列，等待下一轮重试（队列满时按既有策略丢弃最老数据，内存依然有界）
     */
    public static void requeue(List<Object> batch) {
        if (batch == null) return;
        for (Object o : batch) {
            offer(o);
        }
    }

    public static List<Object> drain(int maxSize) {
        List<Object> batch = new ArrayList<>(Math.min(queue.size(), maxSize));
        queue.drainTo(batch, maxSize);
        return batch;
    }

    public static int size() {
        return queue.size();
    }
}
