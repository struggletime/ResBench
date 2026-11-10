package com.resbench.buffer;

import com.resbench.pojo.dto.BenchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

public class BenchResultBuffer {
  private static final int FIXED_SIZE = 20;

  private final Queue<BenchResult> queue = new ArrayBlockingQueue<>(FIXED_SIZE);

  private static volatile BenchResultBuffer instance;

  private BenchResultBuffer() {
    // Private constructor to prevent external instantiation.
  }

  public static BenchResultBuffer getInstance() {
    if (instance == null) {
      synchronized (BenchResultBuffer.class) {
        if (instance == null) {
          instance = new BenchResultBuffer();
        }
      }
    }
    return instance;
  }

  public synchronized void add(BenchResult element) {
    if (!queue.offer(element)) {
      queue.poll();
      queue.offer(element);
    }
  }

  public int size() {
    return queue.size();
  }

  public BenchResult peek() {
    return queue.peek();
  }

  public BenchResult poll() {
    return queue.poll();
  }

  public List<BenchResult> getAllElements() {
    return new ArrayList<>(queue);
  }
}
