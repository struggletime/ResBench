package com.resbench.manager.constant;

public interface URL {

  /**
   * 测试套相关URL
   */
  interface Suite {
    String ENV = "/suite/envinfo";

    String START = "/suite/start";

    String START_BY_FORM = "/suite/startByForm";

    String CONNECTION_TEST = "/suite/connection/test";

    String DB_PARAM = "/suite/db/params";

    String TPCC_WAREHOUSE_VERIFY = "/suite/tpcc/warehouse/verify";

    String FILE_FAULT_TARGETS = "/suite/faults/file-targets";

    String STOP = "/suite/stop";

    String STATUS = "/suite/status";

    String RESULT = "/suite/result";

    String MONITOR = "/suite/monitor";

    String MONITOR_PAGE = "/suite/monitorByPage";

    String HISTORY_LIST = "/suite/history/list";

    String HISTORY_QUERY = "/suite/history/query";

    String HISTORY_LABEL = "/suite/history/label";

    String HISTORY_EXPORT = "/suite/history/export";

    String EVENT = "/suite/event";

    String INJECT_INFO = "/suite/inject/info";

    String DB_TYPE = "/suite/dbtype";

    String DB_TYPE_ALL = "/suite/dbtype/all";

    String WORKLOAD = "/suite/workload";

    String WORKLOADS_ALL = "/suite/workload/all";

    String FAULT_CATALOG = "/suite/faults/catalog";

    String FAULT_VERIFY = "/suite/fault/verify";

    String ORCHESTRATION_CHECK = "/suite/fault/orchestration/check";
  }

  interface Fault {
    String FAULT_INFO = "/fault/info";
  }
}

