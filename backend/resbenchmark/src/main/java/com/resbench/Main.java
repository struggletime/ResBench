package com.resbench;

public class Main {
  public static void main(String[] args) {
    try {
      DBWorkload.exec(args);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
