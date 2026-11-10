package com.resbench;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;

import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.util.Arrays;

public class Application {
  /**
   * springboot启动函数
   *
   * @param args 应用入参
   */
  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("请指定启动类型: job | server");
      System.exit(1);
      return;
    }
    String mode = args[0].toLowerCase();
    String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
    switch (mode) {
      case "job":
        try {
          // 设置job启动时使用的 日志配置文件
          LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
          context.reset(); // 重置现有配置
          JoranConfigurator configurator = new JoranConfigurator();
          configurator.setContext(context);
          configurator.doConfigure(Application.class.getClassLoader().getResourceAsStream("logback-job.xml"));
          DBWorkload.exec(subArgs);
        } catch (Exception e) {
          // 写入异常输出流， 供主程序读取
          System.err.println(
              "执行异常，执行命令:" + Arrays.stream(args).toList() + "异常信息为: " + e.getMessage());
          e.printStackTrace();
          System.exit(1);
        }
        System.exit(0);
        break;
      case "server":
        // In Maven exec runs on Windows, resolving classpath:logback-spring.xml can be unstable.
        // Prefer an explicit file URL from target/classes, then fall back to classpath.
        Path logbackInTarget = Path.of("target", "classes", "logback-spring.xml");
        if (Files.exists(logbackInTarget)) {
          System.setProperty("logging.config", logbackInTarget.toAbsolutePath().toUri().toString());
        } else {
          URL classpathLogback = Application.class.getClassLoader().getResource("logback-spring.xml");
          if (classpathLogback != null) {
            System.setProperty("logging.config", classpathLogback.toString());
          }
        }
        ServerApplication.main(subArgs);
        break;
      default:
        System.out.println("未知模式: " + mode);
        System.exit(1);
    }
  }
}
