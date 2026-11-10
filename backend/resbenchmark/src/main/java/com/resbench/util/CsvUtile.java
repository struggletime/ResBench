package com.resbench.util;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CsvUtile {
  /**
   * 解析 CSV 文件，每一行转换成 Map<String, String>
   *
   * @param filePath CSV 文件路径
   * @param delimiter 分隔符（如 , 或 ;）
   * @return List<Map<String, String>>，每个元素是一行数据
   */
  public static List<Map<String, String>> parse(String filePath, String delimiter) {
    List<Map<String, String>> result = new ArrayList<>();

    if (filePath == null) {
      return result;
    }

    try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        return result; // 空文件
      }

      // 提取列名
      String[] headers = headerLine.split(delimiter);

      String line;
      while ((line = reader.readLine()) != null) {
        String[] values = line.split(delimiter, -1); // -1 保留空列
        Map<String, String> rowMap = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
          String key = headers[i].trim();
          String value = i < values.length ? values[i].trim() : "";
          rowMap.put(key, value);
        }
        result.add(rowMap);
      }
    } catch (FileNotFoundException e) {
      log.warn("CSV file not found, skip parsing: {}", filePath);
    } catch (IOException e) {
      log.error("Error while reading CSV file", e);
    }
    return result;
  }
}
