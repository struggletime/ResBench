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

package com.resbench.benchmarks.smallbank;

public abstract class SmallBankConstants {

  // ----------------------------------------------------------------
  // TABLE NAMES
  // ----------------------------------------------------------------
  public static final String TABLENAME_ACCOUNTS = "accounts";
  public static final String TABLENAME_SAVINGS = "savings";
  public static final String TABLENAME_CHECKING = "checking";

  // ----------------------------------------------------------------
  // ACCOUNT INFORMATION
  // ----------------------------------------------------------------

  // Default number of customers in bank
  public static final int NUM_ACCOUNTS = 10000;
  public static final int NUM_ACCOUNTS_SPECIAL = 10;
  public static final String PREFIX_ACCOUNTS_SPECIAL = "SPECIAL_ACCOUNT_";

  public enum DATA_CK_TYPE {
    ONLY_SPECIAL,
    ALL_DATA
  };

  public static final boolean HOTSPOT_USE_FIXED_SIZE = false;
  public static final double HOTSPOT_PERCENTAGE = 25; // [0% - 100%]
  public static final int HOTSPOT_FIXED_SIZE = 100; // fixed number of tuples

  // ----------------------------------------------------------------
  // ADDITIONAL CONFIGURATION SETTINGS
  // ----------------------------------------------------------------

  // Initial balance amount
  // We'll just make it really big so that they never run out of money
  public static final int MIN_BALANCE = 10000;
  public static final int MAX_BALANCE = 50000;

  // ----------------------------------------------------------------
  // PROCEDURE PARAMETERS
  // These amounts are from the original code
  // ----------------------------------------------------------------
  public static final long PARAM_SEND_PAYMENT_AMOUNT = 2;
  public static final long PARAM_DEPOSIT_CHECKING_AMOUNT = 8;
  public static final long PARAM_TRANSACT_SAVINGS_AMOUNT = 4;
  public static final long PARAM_WRITE_CHECK_AMOUNT = 3;

  // ----------------------------------------------------------------
  // TRANSACTION TYPES
  // ----------------------------------------------------------------
  public static final int TXN_DEPOSIT_CHECKING = 0;
  public static final int TXN_TRANSACT_SAVINGS = 1;
  public static final int TXN_SEND_PAYMENT = 2;
  public static final int TXN_WRITE_CHECK = 3;
  public static final int TXN_BALANCE = 4;
  public static final int TXN_AMALGAMATE = 5;
}
