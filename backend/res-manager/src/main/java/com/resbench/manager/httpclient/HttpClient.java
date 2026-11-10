package com.resbench.manager.httpclient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class HttpClient {
  private final RestTemplate restTemplate;

  @Autowired
  public HttpClient(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  /**
   * 通用请求方法，支持 baseUrl 和 uri 分开传入
   *
   * @param baseUrl      主机地址，必须不带路径，示例：https://api.example.com:8080
   * @param uri          接口路径，示例：/users/list 或 users/list，自动处理斜杠
   * @param method       请求方法
   * @param headers      请求头，可传 null
   * @param queryParams  查询参数，拼接在 URL 后，可传 null
   * @param body         请求体，POST/PUT 时使用，可传 null
   * @param responseType 返回类型
   * @param <T>          返回泛型类型
   * @return 响应实体
   */
  public <T> ResponseEntity<T> request(
    String baseUrl,
    String uri,
    HttpMethod method,
    HttpHeaders headers,
    Map<String, String> queryParams,
    Object body,
    Class<T> responseType) {

    if (baseUrl == null || baseUrl.isEmpty()) {
      throw new IllegalArgumentException("baseUrl must not be empty");
    }

    // 处理 uri 前后斜杠，确保拼接时只有一个斜杠
    String normalizedUri = (uri == null) ? "" : uri.trim();
    if (!normalizedUri.startsWith("/")) {
      normalizedUri = "/" + normalizedUri;
    }

    StringBuilder urlBuilder = new StringBuilder(baseUrl);
    // 如果 baseUrl 以 / 结尾，去掉末尾斜杠，避免双斜杠
    if (urlBuilder.charAt(urlBuilder.length() - 1) == '/') {
      urlBuilder.deleteCharAt(urlBuilder.length() - 1);
    }
    urlBuilder.append(normalizedUri);

    // 拼接查询参数（值做 URL 编码，避免中文/空格/逗号把 URL 拼坏）
    if (queryParams != null && !queryParams.isEmpty()) {
      urlBuilder.append("?");
      queryParams.forEach((k, v) -> urlBuilder.append(k).append("=")
          .append(v == null ? "" : URLEncoder.encode(v, StandardCharsets.UTF_8)).append("&"));
      urlBuilder.deleteCharAt(urlBuilder.length() - 1); // 去掉最后一个 &
    }

    String url = urlBuilder.toString();

    if (headers == null) {
      headers = new HttpHeaders();
    }

    // 根据请求体类型自动设置 Content-Type
    if (body != null) {
      if (body instanceof Map) {
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> formMap = new LinkedMultiValueMap<>();
        ((Map<?, ?>) body).forEach((k, v) -> formMap.add(String.valueOf(k), String.valueOf(v)));
        body = formMap;
      } else {
        headers.setContentType(MediaType.APPLICATION_JSON);
      }
    }

    HttpEntity<?> entity = new HttpEntity<>(body, headers);

    // 注意：url 里的查询参数已手工 URL 编码，必须用 URI 传参；
    // 传 String 会被 RestTemplate 当作 URI 模板再编码一次（%2C -> %252C、中文同理），
    // 导致逗号分隔的 include 与中文检索值失效。
    return restTemplate.exchange(URI.create(url), method, entity, responseType);
  }

  /**
   * @param baseUrl      地址
   * @param uri          URL
   * @param queryParams  请求参数
   * @param headers      请求头
   * @param responseType 响应格式
   * @param <T>          响应体
   * @return ResponseEntity
   */
  public <T> ResponseEntity<T> get(String baseUrl, String uri, Map<String, String> queryParams, HttpHeaders headers, Class<T> responseType) {
    return request(baseUrl, uri, HttpMethod.GET, headers, queryParams, null, responseType);
  }

  /**
   * @param baseUrl      地址
   * @param uri          URL
   * @param headers      请求头
   * @param responseType 响应格式
   * @param <T>          响应体
   * @return ResponseEntity
   */
  public <T> ResponseEntity<T> post(String baseUrl, String uri, Object body, HttpHeaders headers, Class<T> responseType) {
    return request(baseUrl, uri, HttpMethod.POST, headers, null, body, responseType);
  }
}
