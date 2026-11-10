/*
 *  Copyright (C) 2013 by H-Store Project
 *  Brown University
 *  Massachusetts Institute of Technology
 *  Yale University
 *
 *  Permission is hereby granted, free of charge, to any person obtaining
 *  a copy of this software and associated documentation files (the
 *  "Software"), to deal in the Software without restriction, including
 *  without limitation the rights to use, copy, modify, merge, publish,
 *  distribute, sublicense, and/or sell copies of the Software, and to
 *  permit persons to whom the Software is furnished to do so, subject to
 *  the following conditions:
 *
 *  The above copyright notice and this permission notice shall be
 *  included in all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 *  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 *  MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 *  IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 *  OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 *  ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 *  OTHER DEALINGS IN THE SOFTWARE.
 */
package com.resbench.benchmarks.smallbank.procedures;

import com.resbench.api.Procedure;
import com.resbench.api.SQLStmt;
import com.resbench.benchmarks.smallbank.SmallBankConstants;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Balance extends Procedure {

  public final SQLStmt GetAccount =
      new SQLStmt("SELECT * FROM " + SmallBankConstants.TABLENAME_ACCOUNTS + " WHERE name = ?");

  public final SQLStmt GetSavingsBalance =
      new SQLStmt(
          "SELECT bal, ts, ver FROM " + SmallBankConstants.TABLENAME_SAVINGS + " WHERE custid = ?");

  public final SQLStmt GetCheckingBalance =
      new SQLStmt(
          "SELECT bal, ts, ver FROM "
              + SmallBankConstants.TABLENAME_CHECKING
              + " WHERE custid = ?");

  public long[] getSavingsBalance(Connection conn, long custId) throws SQLException {
    long savingsBalance, ts, ver;
    try (PreparedStatement balStmt0 = this.getPreparedStatement(conn, GetSavingsBalance, custId)) {
      try (ResultSet balRes0 = balStmt0.executeQuery()) {
        if (!balRes0.next()) {
          String msg =
              String.format("No %s for customer #%d", SmallBankConstants.TABLENAME_SAVINGS, custId);
          throw new UserAbortException(msg);
        }
        savingsBalance = balRes0.getLong(1);
        ts = balRes0.getLong(2);
        ver = balRes0.getLong(3);
      }
    }
    return new long[] {savingsBalance, ts, ver};
  }

  public long[] getCheckingBalance(Connection conn, long custId) throws SQLException {
    long checkingBalance, ts, ver;
    try (PreparedStatement balStmt1 = this.getPreparedStatement(conn, GetCheckingBalance, custId)) {
      try (ResultSet balRes1 = balStmt1.executeQuery()) {
        if (!balRes1.next()) {
          String msg =
              String.format(
                  "No %s for customer #%d", SmallBankConstants.TABLENAME_CHECKING, custId);
          throw new UserAbortException(msg);
        }
        checkingBalance = balRes1.getLong(1);
        ts = balRes1.getLong(2);
        ver = balRes1.getLong(3);
      }
    }

    return new long[] {checkingBalance, ts, ver};
  }

  public long run(Connection conn, String custName) throws SQLException {
    // First convert the acctName to the acctId
    long custId;

    try (PreparedStatement stmt0 = this.getPreparedStatement(conn, GetAccount, custName)) {
      try (ResultSet r0 = stmt0.executeQuery()) {
        if (!r0.next()) {
          String msg = "Invalid account '" + custName + "'";
          throw new UserAbortException(msg);
        }
        custId = r0.getLong(1);
      }
    }

    long checkingBalance = getCheckingBalance(conn, custId)[0];
    long savingsBalance = getSavingsBalance(conn, custId)[0];

    return checkingBalance + savingsBalance;
  }
}
