package com.resbench.common.event;

/**
 * 事件总线接口，用于事件的分发和处理
 */
public interface EventBus {

    /**
     * 发布事件
     * @param event 事件对象
     */
    void publish(Event event);

    /**
     * 订阅特定类型的事件
     * @param eventType 事件类型
     * @param handler 事件处理器
     * @param <T> 事件类型泛型
     */
    <T extends Event> void subscribe(Class<T> eventType, EventHandler<T> handler);

    /**
     * 取消订阅特定类型的事件
     * @param eventType 事件类型
     * @param handler 事件处理器
     * @param <T> 事件类型泛型
     */
    <T extends Event> void unsubscribe(Class<T> eventType, EventHandler<T> handler);

    /**
     * 关闭事件总线
     */
    void close();
}