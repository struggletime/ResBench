package com.resbench.benchmarks.smallbank;

import com.resbench.DBWorkload;

import org.junit.Ignore;
import org.junit.Test;

public class TestPostgresSmallbank {
  @Ignore
  @Test
  public void test() {
    String[] args = {
      "-b",
      "smallbank",
      "-im",
      "1000",
      "-c",
      "config/postgres/sample_smallbank_config.xml",
      "--create=true",
      "--load=true",
      "--execute=true"
    };
    try {
      DBWorkload.exec(args);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
