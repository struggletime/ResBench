package com.resbench.common.fault;

/** 自检逐项证据（设计文档 §4.2 CheckEvidence）。 */
public class CheckEvidence {
  private String name;
  private String observed;
  private String expected;
  private boolean matched;

  public CheckEvidence() {}

  public CheckEvidence(String name, String observed, String expected, boolean matched) {
    this.name = name;
    this.observed = observed;
    this.expected = expected;
    this.matched = matched;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getObserved() {
    return observed;
  }

  public void setObserved(String observed) {
    this.observed = observed;
  }

  public String getExpected() {
    return expected;
  }

  public void setExpected(String expected) {
    this.expected = expected;
  }

  public boolean isMatched() {
    return matched;
  }

  public void setMatched(boolean matched) {
    this.matched = matched;
  }

  @Override
  public String toString() {
    return "CheckEvidence{name='" + name + "', observed='" + observed + "', expected='" + expected
        + "', matched=" + matched + '}';
  }
}