package com.resbench.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.resbench.pojo.dto.OrchestrationCheckRequest;
import com.resbench.pojo.dto.OrchestrationCheckResult;
import com.resbench.service.KgOrchestrationCheckService;

/**
 * 故障编排合理性校验接口。
 *
 * <p>只读接口：生成临时 XML 并调用 kg_orchestration.py 查询 Neo4j 知识图谱，
 * 不启动测试、不注入故障、不修改任何运行状态。
 */
@RestController
@RequestMapping("/web")
public class OrchestrationCheckController {

  private final KgOrchestrationCheckService checkService;

  public OrchestrationCheckController(KgOrchestrationCheckService checkService) {
    this.checkService = checkService;
  }

  @PostMapping("/fault/orchestration/check")
  public ResponseEntity<OrchestrationCheckResult> check(
      @RequestBody(required = false) OrchestrationCheckRequest request) {
    // 与 startByForm 一致：异常/图谱不可用也返回 200，由 body 里的 checked 区分
    return ResponseEntity.ok(checkService.check(request));
  }
}
