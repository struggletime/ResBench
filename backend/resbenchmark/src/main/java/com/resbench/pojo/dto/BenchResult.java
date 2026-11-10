package com.resbench.pojo.dto;

import java.sql.Timestamp;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BenchResult {
  private Timestamp timestamp;

  private Integer faultId;

  private String faultMessage;

  private Double tps;
}
