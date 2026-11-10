package com.resbench.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.resbench.pojo.dto.OrchestrationCheckRequest;
import com.resbench.pojo.dto.OrchestrationCheckResult;
import com.resbench.pojo.dto.StartByFormRequest;
import com.resbench.service.OrchestrationReasonablenessChecker;

/**
 * 故障编排合理性校验接口。
 *
 * <p>只读接口：不启动测试、不注入故障、不修改任何状态。
 */
@RestController
@RequestMapping("/web")
public class OrchestrationCheckController {

  private final OrchestrationReasonablenessChecker checker;

  public OrchestrationCheckController(OrchestrationReasonablenessChecker checker) {
    this.checker = checker;
  }

  @PostMapping("/fault/orchestration/check")
  public ResponseEntity<OrchestrationCheckResult> check(
      @RequestBody(required = false) OrchestrationCheckRequest request) {
    List<StartByFormRequest.FaultItem> faults =
        (request == null || request.getFaults() == null)
            ? List.of()
            : request.getFaults();
    // 与 startByForm 一致：异常/不可用也返回 200，由 body 里的 checked 区分
    return ResponseEntity.ok(checker.check(faults));
  }
}
