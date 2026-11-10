package com.resbench.manager.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Slf4j
public class JsonUtils {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    MAPPER.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    MAPPER.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
  }

  /**
   * 将一个对象转为Json字符串
   *
   * @param object
   *            要转换的对象
   * @return 转换之后的json字符串
   */
  public static String toJson(Object object) {
    String result = "";
    if (object == null) {
      return result;
    }
    try {
      result = MAPPER.writeValueAsString(object);
    } catch (Exception e) {
      log.warn("class conver to JosnString is error.{}", e.getMessage());
    }

    return result;
  }

  /**
   * 将字符串反系列化为JAVA对象
   *
   * @param requestStr
   *            json字符串
   * @param clazz
   *            对象类
   * @param <T>
   *            对象类类型
   * @return 反系列化之后的对象
   */
  public static <T> T fromJson(String requestStr, Class<T> clazz) {
    T object = null;

    try {
      object = MAPPER.readValue(requestStr, clazz);
    } catch (Exception e) {
      log.warn("JosnString conver to Class is error.{}", e.getMessage());
    }

    return object;
  }

  /**
   * 将 JSON 字符串转换为指定类型（带泛型支持）
   */
  public static <T> T fromJson(String json, TypeReference<T> typeReference) {
    try {
      return MAPPER.readValue(json, typeReference);
    } catch (Exception e) {
      throw new RuntimeException("JSON反序列化失败", e);
    }
  }


  /**
   * 将字符串反系列化为JAVA对象
   *
   * @param requestStr
   *            json字符串
   * @param clazz
   *            对象类
   * @param <T>
   *            对象类类型
   * @return 反系列化之后的对象
   * @throws JsonProcessingException JsonProcessingException
   */
  public static <T> T fromJsonUnsafe(String requestStr, Class<T> clazz) throws JsonProcessingException {
    return MAPPER.readValue(requestStr, clazz);
  }

  /**
   * 将 JSON 字符串反序列化为 List<T>
   *
   * @param json  JSON 字符串
   * @param <T>   元素泛型类型
   * @return List<T> 对象列表
   */
  public static <T> List<T> fromJsonToList(String json, Class<T> elementClass) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      // 构造 TypeReference，泛型擦除的解决方案
      JavaType type = MAPPER.getTypeFactory()
        .constructCollectionType(List.class, elementClass);
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new RuntimeException("Json String convert to Class is error: " + e.getMessage(), e);
    }
  }

  /**
   * 将 JSON 字符串反序列化为 SET<T>
   *
   * @param json  JSON 字符串
   * @param <T>   元素泛型类型
   * @return List<T> 对象列表
   */
  public static <T> Set<T> fromJsonToSet(String json, Class<T> elementClass) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      // 构造 TypeReference，泛型擦除的解决方案
      JavaType type = MAPPER.getTypeFactory()
              .constructCollectionType(Set.class, elementClass);
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new RuntimeException("Json String convert to Class is error: " + e.getMessage(), e);
    }
  }

  /**
   * 将字符串反系列化为JAVA对象
   *
   * @param requestStr
   *            json字符串
   * @param collectionClazz
   *            Collection类
   * @param elementClazzes
   *            元素类
   * @param <T>
   *            Collection泛型类
   * @param <V>
   *            元素泛型类
   * @return Collection类实例
   */
  public static <T, V> T fromJson(String requestStr, Class<T> collectionClazz, Class<V>... elementClazzes) {
    T object = null;
    try {
      JavaType javaType = MAPPER.getTypeFactory().constructParametricType(collectionClazz, elementClazzes);
      object = MAPPER.readValue(requestStr, javaType);
    } catch (Exception e) {
      log.warn("Class {} conver to Json String {} error!", collectionClazz.getSimpleName(), requestStr);
    }

    return object;
  }

  /**
   * 获取某个Json字符串中的某个子串，调用toString返回
   *
   * @param json
   *            Json字符串
   * @param field
   *            某个子串
   * @return 子串的内容
   */
  public static String getField(String json, String field) {
    String ret = "";
    JsonNode node = getJsonNode(json, field);
    if (node != null && node.get(field) != null) {
      ret = node.get(field).toString();
    }
    return ret;
  }

  /**
   * 获取某个Json字符串中的某个子串,原样返回节点的值
   *
   * @param json
   *            Json字符串
   * @param field
   *            某个子串
   * @return 子串的内容
   */
  public static String getFieldAsText(String json, String field) {
    String ret = "";
    JsonNode node = getJsonNode(json, field);
    if (node != null && node.get(field) != null) {
      ret = node.get(field).asText();
    }
    return ret;
  }

  /**
   * getJsonNode
   *
   * @param json json
   * @param field field
   * @return JsonNode
   */
  public static JsonNode getJsonNode(String json, String field) {
    JsonNode node = null;
    if (StringUtils.isEmpty(json) || StringUtils.isEmpty(field)) {
      return node;
    }
    try {
      node = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      log.warn("get Filed {} from JsonString {} error!", field, json);
    }
    return node;
  }

  /**
   * 获取Jackson Mapper对象
   *
   * @return Mapper对象
   */
  public static ObjectMapper getMapper() {
    return MAPPER;
  }

  /**
   * getFieldNames
   *
   * @param json json
   * @return Iterator<String>
   */
  public static Iterator<String> getFieldNames(String json) {
    Iterator<String> ret = null;

    JsonNode node = null;
    try {
      node = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      log.warn("Mapper the json is failed!");
    }
    if (node != null) {
      ret = node.fieldNames();
    }
    return ret;
  }

  /**
   * getFieldNamesUnsafe
   *
   * @param json json
   * @return Iterator<String>
   * @throws JsonProcessingException JsonProcessingException
   */
  public static Iterator<String> getFieldNamesUnsafe(String json) throws JsonProcessingException {
    Iterator<String> ret = null;
    JsonNode node = MAPPER.readTree(json);
    if (node != null) {
      ret = node.fieldNames();
    }
    return ret;
  }

  /**
   * getElements
   *
   * @param json json
   * @return List<String>
   */
  public static List<String> getElements(String json) {
    List<String> list = new ArrayList<>();
    JsonNode node = null;
    try {
      node = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      log.warn("Mapper the json is failed,This json is {}", json);
    }
    if (node != null) {
      Iterator<JsonNode> elements = node.elements();
      while (elements.hasNext()) {
        JsonNode jsonNode = elements.next();
        list.add(jsonNode.toString());
      }
    }
    return list;
  }

  /**
   * 判断是否是json结构
   *
   * @param requestStr requestStr
   * @param clazz clazz
   * @return boolean
   */
  public static <T> boolean isJsonNode(String requestStr, Class<T> clazz) {
    try {
      MAPPER.readValue(requestStr, clazz);
    } catch (Exception e) {
      return false;
    }
    return true;
  }

  /**
   * isJsonTypeValid
   *
   * @param jsonStr jsonStr
   * @return boolean
   */
  public static boolean isJsonTypeValid(String jsonStr) {
    try {
      MAPPER.readTree(jsonStr);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
