package com.resbench.manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableConfigurationProperties
@EnableScheduling
@SpringBootApplication
public class ManagerApplication {
  public static void main(String[] args) {
    SpringApplication.run(ManagerApplication.class, args);
  }
}