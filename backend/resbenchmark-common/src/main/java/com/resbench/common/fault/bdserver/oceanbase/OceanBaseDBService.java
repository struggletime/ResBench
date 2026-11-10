package com.resbench.common.fault.bdserver.oceanbase;

import com.resbench.common.fault.bdserver.DBService;

import java.util.ArrayList;
import java.util.List;

public class OceanBaseDBService extends DBService {

  public OceanBaseDBService(String username, String password, List<String> nodes, String driverClass) {
    super(username, password, nodes, driverClass);
  }

  @Override
  public List<String> getClusteringMasters() {
    return new ArrayList<>();
  }

  @Override
  public List<String> getClusteringSlaves() {
    return new ArrayList<>();
  }
}
