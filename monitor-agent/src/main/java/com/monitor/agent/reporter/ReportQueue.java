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

    public static List<Object> drain(int maxSize) {
        List<Object> batch = new ArrayList<>(Math.min(queue.size(), maxSize));
        queue.drainTo(batch, maxSize);
        return batch;
    }

    public static int size() {
        return queue.size();
    }
}
