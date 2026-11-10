package com.resbench.benchmarks.smallbank;

import com.resbench.api.Procedure;
import com.resbench.api.TransactionType;
import com.resbench.api.Worker;
import com.resbench.benchmarks.smallbank.procedures.*;
import com.resbench.types.TransactionStatus;
import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmallBankWorker extends Worker<SmallBankBenchmark> {
  private static final Logger LOG = LoggerFactory.getLogger(SmallBankWorker.class);

  private final DepositChecking procDepositChecking;
  private final TransactSavings procDepositSavings;
  private final SendPayment procSendPayment;
  private final WriteCheck procWriteCheck;
  private final Balance procBalance;
  private final Amalgamate procAmalgamate;

  public SmallBankWorker(SmallBankBenchmark benchmark, int id) {
    super(benchmark, id);
    this.procDepositChecking = new DepositChecking();
    this.procDepositSavings = new TransactSavings();
    this.procSendPayment = new SendPayment();
    this.procWriteCheck = new WriteCheck();
    this.procBalance = new Balance();
    this.procAmalgamate = new Amalgamate();
  }

  private String custIdToName(long custId) {
    return String.format("%06d", custId);
  }

  @Override
  protected TransactionStatus executeWork(Connection conn, TransactionType txnType)
      throws SQLException {
    Class<? extends Procedure> procClass = txnType.getProcedureClass();
    long numAccounts = getBenchmark().numAccounts;
    if (numAccounts <= 0) {
      throw new IllegalStateException("SmallBank must have at least one loaded account");
    }
    long custId = Math.floorMod(this.rng().nextLong(), numAccounts);
    long custId2 = Math.floorMod(this.rng().nextLong(), numAccounts);
    long value = Math.floorMod(this.rng().nextLong(), 100) + 1;

    if (procClass.equals(DepositChecking.class)) {
      procDepositChecking.run(conn, custIdToName(custId), value);
    } else if (procClass.equals(TransactSavings.class)) {
      procDepositSavings.run(conn, custIdToName(custId), value);
    } else if (procClass.equals(SendPayment.class)) {
      procSendPayment.run(conn, custId, custId2, value);
    } else if (procClass.equals(WriteCheck.class)) {
      procWriteCheck.run(conn, custIdToName(custId), value);
    } else if (procClass.equals(Balance.class)) {
      procBalance.run(conn, custIdToName(custId));
    } else if (procClass.equals(Amalgamate.class)) {
      procAmalgamate.run(conn, custId, custId2);
    } else {
      throw new RuntimeException("Unknown procedure class: " + procClass.getName());
    }
    return TransactionStatus.SUCCESS;
  }

  @Override
  public TransactionStatus executeWorkForDataCheck(int dataCheckType) throws Exception {
    return TransactionStatus.SUCCESS;
  }

  @Override
  public void checkDataMiss() throws SQLException {
    Connection _conn = getBenchmark().makeConnection();
    _conn.setAutoCommit(true);

    LOG.debug("URL: {}", _conn.getMetaData().getURL());
    LOG.debug("ClientInfo: {}", _conn.getClientInfo());

    try {
      LOG.debug("BenchmarkState.DATA_PERSISTENCE={}", com.resbench.BenchmarkState.DATA_PERSISTENCE);
      LOG.debug("BenchmarkState.DATA_VER={}", com.resbench.BenchmarkState.DATA_VER);

      StringBuilder needStoredData = new StringBuilder();
      java.util.Iterator<java.util.Map.Entry<String, Long>> iterator =
          com.resbench.BenchmarkState.DATA_PERSISTENCE.entrySet().iterator();
      int errCount = 0, succCount = 0;
      long dataLossSum = 0, userCount = 0;

      java.util.Map<Long, long[]> savingsBalances = new java.util.HashMap<>();
      java.util.Map<Long, long[]> checkingBalances = new java.util.HashMap<>();
      java.util.List<Long> savingsCustIds = new java.util.ArrayList<>();
      java.util.List<Long> checkingCustIds = new java.util.ArrayList<>();

      java.util.Map<String, java.util.Map.Entry<String, Long>> entriesByTable =
          new java.util.HashMap<>();
      while (iterator.hasNext()) {
        java.util.Map.Entry<String, Long> entry = iterator.next();
        String key = entry.getKey();
        String[] custId_tab = key.split("_");
        long custId = Long.parseLong(custId_tab[0]);
        if (custId_tab[1].equals(SmallBankConstants.TABLENAME_SAVINGS)) {
          if (!savingsBalances.containsKey(custId)) {
            savingsCustIds.add(custId);
          }
        } else if (custId_tab[1].equals(SmallBankConstants.TABLENAME_CHECKING)) {
          if (!checkingBalances.containsKey(custId)) {
            checkingCustIds.add(custId);
          }
        }
        entriesByTable.put(key, entry);
      }

      if (!savingsCustIds.isEmpty()) {
        String savingsInClause =
            savingsCustIds.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(","));
        String savingsQuery =
            "SELECT custid, bal, ts, ver FROM "
                + SmallBankConstants.TABLENAME_SAVINGS
                + " WHERE custid IN ("
                + savingsInClause
                + ")";
        try (java.sql.PreparedStatement stmt = _conn.prepareStatement(savingsQuery)) {
          for (int i = 0; i < savingsCustIds.size(); i++) {
            stmt.setLong(i + 1, savingsCustIds.get(i));
          }
          try (java.sql.ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
              long custId = rs.getLong(1);
              savingsBalances.put(custId, new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4)});
            }
          }
        }
      }

      if (!checkingCustIds.isEmpty()) {
        String checkingInClause =
            checkingCustIds.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(","));
        String checkingQuery =
            "SELECT custid, bal, ts, ver FROM "
                + SmallBankConstants.TABLENAME_CHECKING
                + " WHERE custid IN ("
                + checkingInClause
                + ")";
        try (java.sql.PreparedStatement stmt = _conn.prepareStatement(checkingQuery)) {
          for (int i = 0; i < checkingCustIds.size(); i++) {
            stmt.setLong(i + 1, checkingCustIds.get(i));
          }
          try (java.sql.ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
              long custId = rs.getLong(1);
              checkingBalances.put(
                  custId, new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4)});
            }
          }
        }
      }

      for (java.util.Map.Entry<String, java.util.Map.Entry<String, Long>> tableEntry :
          entriesByTable.entrySet()) {
        String key = tableEntry.getKey();
        java.util.Map.Entry<String, Long> entry = tableEntry.getValue();
        long expectedBal = entry.getValue();

        String[] custId_tab = key.split("_");
        long custId = Long.parseLong(custId_tab[0]);
        long ts = Long.parseLong(custId_tab[2]);

        long[] balTs = null;
        if (custId_tab[1].equals(SmallBankConstants.TABLENAME_SAVINGS)) {
          balTs = savingsBalances.get(custId);
        } else if (custId_tab[1].equals(SmallBankConstants.TABLENAME_CHECKING)) {
          balTs = checkingBalances.get(custId);
        }

        if (balTs == null) {
          LOG.error("No balance found for customer {} in table {}", custId, custId_tab[1]);
          continue;
        }

        userCount += 1;
        long missedTs =
            com.resbench.BenchmarkState.CUST_TAB_LATEST_TS.get(custId_tab[0] + "_" + custId_tab[1])
                - balTs[1];
        if (missedTs > 0) {
          com.resbench.BenchmarkState.DATA_MISSED.put(custId, missedTs);
          dataLossSum += missedTs;
          needStoredData.append(
              missedTs + "ms data committed not seen in database for cus " + custId);
          errCount++;
        } else if (missedTs < 0) {
          com.resbench.BenchmarkState.DATA_MISSED.put(custId, missedTs);
          dataLossSum += missedTs;
          needStoredData.append(missedTs + "ms data error, DB_ts > mem_TS for cus " + custId);
          errCount++;
        } else if (balTs[0] != expectedBal && balTs[1] == ts) {
          com.resbench.BenchmarkState.DATA_MISSED.put(custId, (long) errCount);
          LOG.error(missedTs + "ms data committed inconsistent with database for cus " + custId);
          errCount++;
          needStoredData
              .append(entry.getKey())
              .append(" expected: ")
              .append(expectedBal + "@")
              .append(ts)
              .append(",ver")
              .append(com.resbench.BenchmarkState.DATA_VER.get(key))
              .append(", in fact:")
              .append(balTs[0] + "@")
              .append(balTs[1])
              .append(",ver")
              .append(balTs[2])
              .append("\n");
        } else {
          succCount++;
        }
      }

      long millSecondTs = System.currentTimeMillis();
      com.resbench.BenchmarkState.addDataLossHistory(
          new com.resbench.BenchmarkState.DataLossResult(
              millSecondTs, userCount == 0 ? 0 : dataLossSum / userCount));

      LOG.info("Data check result: error count {}, succ count {}", errCount, succCount);
      if (errCount > 0) LOG.error(needStoredData.toString());
    } finally {
      _conn.close();
    }
  }
}
