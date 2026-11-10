package com.resbench.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 事件总线实现类，用于事件的分发和处理
 */
public class SimpleEventBus implements EventBus {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleEventBus.class);
    
    // 事件类型到处理器列表的映射
    private final Map<Class<? extends Event>, List<EventHandler<? extends Event>>> handlers;
    
    // 用于处理事件的线程池
    private final ExecutorService executor;
    
    // 是否关闭标志
    private volatile boolean closed = false;

    /**
     * 构造函数
     */
    public SimpleEventBus() {
        this.handlers = new ConcurrentHashMap<>();
        this.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @Override
    public void publish(Event event) {
        if (closed) {
            LOG.warn("EventBus is closed, cannot publish event: {}", event.getEventType());
            return;
        }

        LOG.debug("Publishing event: {}", event.getEventType());
        
        // 获取事件类型对应的处理器列表
        List<EventHandler<? extends Event>> eventHandlers = handlers.get(event.getClass());
        if (eventHandlers == null || eventHandlers.isEmpty()) {
            LOG.debug("No handlers for event: {}", event.getEventType());
            return;
        }

        // 异步执行所有处理器
        for (EventHandler<? extends Event> handler : eventHandlers) {
            executor.submit(() -> {
                try {
                    @SuppressWarnings("unchecked")
                    EventHandler<Event> eventHandler = (EventHandler<Event>) handler;
                    eventHandler.handle(event);
                } catch (Exception e) {
                    LOG.error("Error handling event: {} in handler: {}", 
                            event.getEventType(), handler.getClass().getName(), e);
                }
            });
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void subscribe(Class<T> eventType, EventHandler<T> handler) {
        if (closed) {
            LOG.warn("EventBus is closed, cannot subscribe to event: {}", eventType.getSimpleName());
            return;
        }

        LOG.debug("Subscribing to event: {} with handler: {}", 
                eventType.getSimpleName(), handler.getClass().getName());
        
        // 获取或创建事件类型对应的处理器列表
        List<EventHandler<? extends Event>> eventHandlers = handlers.computeIfAbsent(
                eventType, k -> Collections.synchronizedList(new ArrayList<>()));
        
        // 添加处理器
        eventHandlers.add(handler);
    }

    @Override
    public <T extends Event> void unsubscribe(Class<T> eventType, EventHandler<T> handler) {
        LOG.debug("Unsubscribing from event: {} with handler: {}", 
                eventType.getSimpleName(), handler.getClass().getName());
        
        List<EventHandler<? extends Event>> eventHandlers = handlers.get(eventType);
        if (eventHandlers != null) {
            eventHandlers.remove(handler);
            if (eventHandlers.isEmpty()) {
                handlers.remove(eventType);
            }
        }
    }

    @Override
    public void close() {
        LOG.info("Closing EventBus...");
        
        closed = true;
        
        // 关闭线程池
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOG.error("Error waiting for EventBus executor to terminate", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 清空处理器映射
        handlers.clear();
        
        LOG.info("EventBus closed");
    }
}