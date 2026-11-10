package com.resbench.manager.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class SwaggerConfig {
  @Bean
  public OpenAPI customOpenAPI() {
    return new OpenAPI()
      .info(new Info()
        .title("My API")
        .version("1.0")
        .description("Spring Boot 3.5.3 示例文档"));
  }

  @Bean
  public GroupedOpenApi publicApi() {
    return GroupedOpenApi.builder()
      .group("public")
      .pathsToMatch("/**")
      .build();
  }
}
