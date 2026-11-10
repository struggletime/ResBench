package com.resbench.manager.config;

import java.time.Duration;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {
  @Bean
  @Primary
  public RestTemplate restTemplate() {
    return new RestTemplate();
  }

  @Bean("monitoringRestTemplate")
  public RestTemplate monitoringRestTemplate(RestTemplateBuilder builder) {
    return builder
        .setConnectTimeout(Duration.ofSeconds(2))
        .setReadTimeout(Duration.ofSeconds(5))
        .build();
  }
}
