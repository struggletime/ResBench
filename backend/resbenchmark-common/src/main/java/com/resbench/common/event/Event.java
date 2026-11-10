package com.resbench.common.event;

import java.time.Instant;

/**
 * 事件基类，定义事件的基本属性和方法
 */
public interface Event {

    /**
     * 获取事件类型
     * @return 事件类型字符串
     */
    String getEventType();

    /**
     * 获取事件发生时间
     * @return 事件发生时间戳
     */
    Instant getTimestamp();
}