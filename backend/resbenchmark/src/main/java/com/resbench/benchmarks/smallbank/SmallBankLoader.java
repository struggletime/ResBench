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

import com.resbench.api.Loader;
import com.resbench.api.LoaderThread;
import com.resbench.catalog.Table;
import com.resbench.util.RandomDistribution.DiscreteRNG;
import com.resbench.util.RandomDistribution.Gaussian;
import com.resbench.util.SQLUtil;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SmallBankBenchmark Loader
 *
 * @author pavlo
 */
public final class SmallBankLoader extends Loader<SmallBankBenchmark> {
  private final Table catalogAccts;

  private final Table catalogSavings;

  private final Table catalogChecking;

  private final String sqlAccts;

  private final String sqlSavings;

  private final String sqlChecking;

  private final long numAccounts;

  private final int custNameLength;

  public SmallBankLoader(SmallBankBenchmark benchmark) {
    super(benchmark);

    this.catalogAccts = this.benchmark.getCatalog().getTable(SmallBankConstants.TABLENAME_ACCOUNTS);
    this.catalogSavings =
        this.benchmark.getCatalog().getTable(SmallBankConstants.TABLENAME_SAVINGS);
    this.catalogChecking =
        this.benchmark.getCatalog().getTable(SmallBankConstants.TABLENAME_CHECKING);

    this.sqlAccts = SQLUtil.getInsertSQL(this.catalogAccts, this.getDatabaseType());
    this.sqlSavings = SQLUtil.getInsertSQL(this.catalogSavings, this.getDatabaseType());
    this.sqlChecking = SQLUtil.getInsertSQL(this.catalogChecking, this.getDatabaseType());

    this.numAccounts = benchmark.numAccounts;
    this.custNameLength = SmallBankBenchmark.getCustomerNameLength(this.catalogAccts);
  }

  @Override
  public List<LoaderThread> createLoaderThreads() throws SQLException {
    List<LoaderThread> threads = new ArrayList<>();
    int batchSize = 1000;
    long start = 0;
    while (start < this.numAccounts) {
      long stop = Math.min(start + batchSize, this.numAccounts);
      threads.add(new Generator(start, stop));
      start = stop;
    }
    LOG.info(
        "With NUM_ACCOUNT="
            + this.numAccounts
            + ", batchSize="
            + batchSize
            + ", Loader threads.size="
            + threads.size());
    return (threads);
  }

  public void clear() {
    Generator.dataMissLoaded = false;
  }

  /** Thread that can generate a range of accounts */
  private class Generator extends LoaderThread {
    private final long start;

    private final long stop;

    private final DiscreteRNG randBalance;

    PreparedStatement stmtAccts;

    PreparedStatement stmtSavings;

    PreparedStatement stmtChecking;

    public Generator(long start, long stop) {
      super(benchmark);
      this.start = start;
      this.stop = stop;
      this.randBalance =
          new Gaussian(
              benchmark.rng(), SmallBankConstants.MIN_BALANCE, SmallBankConstants.MAX_BALANCE);
    }

    /**
     * 初始化客户[N1-N10] ，奇数客户储蓄账户有86400元，支票账户中0元；偶数客户，储蓄账户有0元，支票账户中86400元；
     * <li>奇数客户每秒存入支票账户1元，即DC(N,1)，储蓄账户支出1元TS(N, -1)
     * <li>偶数客户每秒存入储蓄账户1元，即TS(N, 1)，支票支出1元，即WC(N, 1)
     * <li>N1转账给N2，N2再转账回N1，每秒检查一次余额，即Bal(N)，期望输出86400元 <br>
     *     上面任何一个数据有变化的操作一旦commit成功则缓存记录已经提交的数值及提交时刻，故障恢复后查询该记录是否与缓存一致
     *
     * @param conn
     * @throws SQLException
     */
    public void loadDataForCheckDataMiss(Connection conn) throws SQLException {
      int specialCnt = -1 * SmallBankConstants.NUM_ACCOUNTS_SPECIAL;

      for (int acctId = specialCnt; acctId < 0; acctId++) {
        // ACCOUNT
        String acctName = SmallBankConstants.PREFIX_ACCOUNTS_SPECIAL + acctId;
        stmtAccts.setLong(1, acctId);
        stmtAccts.setString(2, acctName);
        stmtAccts.addBatch();

        // CHECKINGS
        stmtChecking.setLong(1, acctId);
        if (acctId % 2 == -1) {
          stmtChecking.setInt(2, 0);
        } else {
          stmtChecking.setInt(2, 86400);
        }
        stmtChecking.setLong(3, 0);
        stmtChecking.setLong(4, 0);
        stmtChecking.addBatch();

        // SAVINGS
        stmtSavings.setLong(1, acctId);
        if (acctId % 2 == -1) {
          stmtSavings.setInt(2, 86400);
        } else {
          stmtSavings.setInt(2, 0);
        }
        stmtSavings.setLong(3, 0);
        stmtSavings.setLong(4, 0);
        stmtSavings.addBatch();
      }
      this.loadTables(conn);
    }

    @Override
    public void load(Connection conn) {
      try {
        this.stmtAccts = conn.prepareStatement(SmallBankLoader.this.sqlAccts);
        this.stmtSavings = conn.prepareStatement(SmallBankLoader.this.sqlSavings);
        this.stmtChecking = conn.prepareStatement(SmallBankLoader.this.sqlChecking);

        final String acctNameFormat = "%0" + custNameLength + "d";
        int batchSize = 0;
        for (long acctId = this.start; acctId < this.stop; acctId++) {
          // ACCOUNT
          String acctName = String.format(acctNameFormat, acctId);
          stmtAccts.setLong(1, acctId);
          stmtAccts.setString(2, acctName);
          stmtAccts.addBatch();

          // CHECKINGS
          stmtChecking.setLong(1, acctId);
          stmtChecking.setInt(2, this.randBalance.nextInt());
          stmtChecking.setLong(3, 0);
          stmtChecking.setLong(4, 0);
          stmtChecking.addBatch();

          // SAVINGS
          stmtSavings.setLong(1, acctId);
          stmtSavings.setInt(2, this.randBalance.nextInt());
          stmtSavings.setLong(3, 0);
          stmtSavings.setLong(4, 0);
          stmtSavings.addBatch();

          if (++batchSize >= workConf.getBatchSize()) {
            this.loadTables(conn);
            batchSize = 0;
          }
        }
        if (batchSize > 0) {
          this.loadTables(conn);
        }
      } catch (SQLException ex) {
        LOG.error("Failed to load data", ex);
        throw new RuntimeException(ex);
      }
    }

    private void loadTables(Connection conn) throws SQLException {
      this.stmtAccts.executeBatch();
      this.stmtSavings.executeBatch();
      this.stmtChecking.executeBatch();
    }
  }
}
