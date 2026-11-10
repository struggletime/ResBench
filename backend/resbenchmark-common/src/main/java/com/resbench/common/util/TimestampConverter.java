package com.resbench.common.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class TimestampConverter {
  public static String convertTimestamp(long timestampMillis) {
    // 转为 ZonedDateTime，使用默认系统时区
    ZonedDateTime dateTime = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault());

    // 格式化输出
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    return dateTime.format(formatter);
  }
}
