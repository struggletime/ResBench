package com.resbench.common.event;

/**
 * 事件处理器接口，用于处理特定类型的事件
 * @param <T> 事件类型泛型
 */
public interface EventHandler<T extends Event> {

    /**
     * 处理事件
     * @param event 事件对象
     */
    void handle(T event);
}