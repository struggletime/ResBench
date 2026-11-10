package com.resbench.manager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author
 * @description : 跨域配置类
 * @since : 2025/7/18
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")  // 所有路径
      .allowedOriginPatterns("*")  // 所有来源都允许
      .allowedMethods("*")
      .allowedHeaders("*")
      .allowCredentials(true) // 允许携带 Cookie 等认证信息
      .maxAge(3600); // 1 小时内不用再预检
  }
}