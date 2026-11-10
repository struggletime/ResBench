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

/**
 * SendPayment Procedure
 *
 * @author pavlo
 */
public class SendPayment extends Procedure {

  public final SQLStmt GetAccount =
      new SQLStmt("SELECT * FROM " + SmallBankConstants.TABLENAME_ACCOUNTS + " WHERE custid = ?");

  public final SQLStmt GetCheckingBalance =
      new SQLStmt("SELECT bal FROM " + SmallBankConstants.TABLENAME_CHECKING + " WHERE custid = ?");

  public final SQLStmt UpdateCheckingBalance =
      new SQLStmt(
          "UPDATE "
              + SmallBankConstants.TABLENAME_CHECKING
              + "   SET bal = bal + ? , ts = ?, ver = ver + 1 "
              + " WHERE custid = ?");

  public void run(Connection conn, long sendAcct, long destAcct, long amount) throws SQLException {
    // Get Account Information
    try (PreparedStatement stmt0 = this.getPreparedStatement(conn, GetAccount, sendAcct)) {
      try (ResultSet r0 = stmt0.executeQuery()) {
        if (!r0.next()) {
          String msg = "Invalid account '" + sendAcct + "'";
          throw new UserAbortException(msg);
        }
      }
    }

    try (PreparedStatement stmt1 = this.getPreparedStatement(conn, GetAccount, destAcct)) {
      try (ResultSet r1 = stmt1.executeQuery()) {
        if (!r1.next()) {
          String msg = "Invalid account '" + destAcct + "'";
          throw new UserAbortException(msg);
        }
      }
    }

    // Get the sender's account balance
    long balanceSender;

    try (PreparedStatement balStmt0 =
        this.getPreparedStatement(conn, GetCheckingBalance, sendAcct)) {
      try (ResultSet balRes0 = balStmt0.executeQuery()) {
        if (!balRes0.next()) {
          String msg =
              String.format(
                  "No %s for customer #%d", SmallBankConstants.TABLENAME_CHECKING, sendAcct);
          throw new UserAbortException(msg);
        }
        balanceSender = balRes0.getLong(1);
      }
    }

    // Make sure that they have enough money to send
    if (balanceSender < amount) {
      String msg =
          String.format(
              "Insufficient %s funds for customer #%d",
              SmallBankConstants.TABLENAME_CHECKING, sendAcct);
      throw new UserAbortException(msg);
    }

    long currTs = this.getExecTs();

    // Debt
    try (PreparedStatement updateStmt =
        this.getPreparedStatement(conn, UpdateCheckingBalance, amount * -1d, currTs, sendAcct)) {
      updateStmt.executeUpdate();
      this.prepareStoreData(
          SmallBankConstants.TABLENAME_CHECKING, sendAcct, balanceSender - amount, currTs);
    }

    // Credit
    // Get the dest's account balance
    long balanceDest;

    try (PreparedStatement balStmt0 =
        this.getPreparedStatement(conn, GetCheckingBalance, destAcct)) {
      try (ResultSet balRes0 = balStmt0.executeQuery()) {
        if (!balRes0.next()) {
          String msg =
              String.format(
                  "No %s for customer #%d", SmallBankConstants.TABLENAME_CHECKING, destAcct);
          throw new UserAbortException(msg);
        }
        balanceDest = balRes0.getLong(1);
      }
    }
    try (PreparedStatement updateStmt =
        this.getPreparedStatement(conn, UpdateCheckingBalance, amount, currTs, destAcct)) {
      updateStmt.executeUpdate();
      // destAcct balance is not the same with the sendAcct balance
      this.prepareStoreData(
          SmallBankConstants.TABLENAME_CHECKING, destAcct, balanceDest + amount, currTs);
    }
  }
}
