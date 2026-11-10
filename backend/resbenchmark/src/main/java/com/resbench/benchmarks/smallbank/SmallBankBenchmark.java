/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.resbench.benchmarks.smallbank;

import com.resbench.WorkloadConfiguration;
import com.resbench.api.BenchmarkModule;
import com.resbench.api.Loader;
import com.resbench.api.Worker;
import com.resbench.benchmarks.smallbank.procedures.Amalgamate;
import com.resbench.catalog.Column;
import com.resbench.catalog.Table;
import com.resbench.util.SQLUtil;
import java.util.ArrayList;
import java.util.List;

public final class SmallBankBenchmark extends BenchmarkModule {

  protected final long numAccounts;

  public SmallBankBenchmark(WorkloadConfiguration workConf) {
    super(workConf);
    this.numAccounts =
        (int) Math.round(SmallBankConstants.NUM_ACCOUNTS * workConf.getScaleFactor());
  }

  @Override
  protected List<Worker<? extends BenchmarkModule>> makeWorkersImpl() {
    List<Worker<? extends BenchmarkModule>> workers = new ArrayList<>();
    for (int i = 0; i < workConf.getTerminals(); ++i) {
      workers.add(new SmallBankWorker(this, i));
    }
    return workers;
  }

  @Override
  protected Loader<SmallBankBenchmark> makeLoaderImpl() {
    return new SmallBankLoader(this);
  }

  @Override
  protected Package getProcedurePackageImpl() {
    return Amalgamate.class.getPackage();
  }

  /**
   * For the given table, return the length of the first VARCHAR attribute
   *
   * @param acctsTbl
   * @return
   */
  public static int getCustomerNameLength(Table acctsTbl) {
    int acctNameLength = -1;
    for (Column col : acctsTbl.getColumns()) {
      if (SQLUtil.isStringType(col.getType())) {
        acctNameLength = col.getSize();
        break;
      }
    }

    return (acctNameLength);
  }
}
