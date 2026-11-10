package com.resbench.manager.model.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class PageResultResponse<T> {
  private List<T> data;

  // 总数据
  private long total;

  // 当前页
  private int pageNum;

  // 每页数据
  private int pageSize;

  // 总页数
  private long totalPages;

  public PageResultResponse() {
    this.data = Collections.emptyList();
    this.total = 0;
    this.pageNum = 1;
    this.pageSize = 10;
    this.totalPages = 0;
  }

  public PageResultResponse(List<T> data, long total, int pageNum, int pageSize) {
    this.data = data == null ? Collections.emptyList() : data;
    this.total = total;
    this.pageNum = pageNum;
    this.pageSize = pageSize;
    this.totalPages = (total + pageSize - 1) / pageSize;
  }
}
