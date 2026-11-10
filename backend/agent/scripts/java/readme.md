English | [简体中文](readme_cn.md)

# Fault Injection Commands

| Three-level Fault Mode | Fault ID |
| -------------------------------- | ------ |
| Business concurrency exceeds the database I/O capacity limit | 101 |
| Business concurrency exceeds the database memory capacity limit | 102 |
| Business concurrency exceeds the database CPU capacity limit | 103 |
| Handle exhaustion | 104 |
| Stored procedure stack overflow | 105 |
| Function stack overflow | 106 |
| Transaction stack overflow | 107 |
| Recursive call stack overflow | 108 |
| Massive number of subtransactions | / |
| Mass transaction rollback | 109 |
| max_prepared_transaction limit reached | 110 |
| High-concurrency updates to hot rows | / |
| Transactions not committed | 111 |
| Re-execution | 112 |
| Burst of massive connection requests | 113 |
| Error when connection count exceeds the limit | 114 |
| Active thread count of CN/DN exceeds the thread pool limit | 115 |
| Stream thread count exceeds the thread pool limit | 115 |
| max_dynamic_memory limit reached | 116 |



### Notes:

**The current fault scripts require Java version >= 21**

### Business Concurrency Exceeds the Database I/O Capacity Limit (101)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar MaxIOTest
```

```xml
<case>
    <id>105</id>
    <description>Database I/O Saturation</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>MaxIOTest</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>MaxIOTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



### Business Concurrency Exceeds the Database Memory Capacity Limit (102)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar MaxMemTest
```

```xml
<case>
    <id>105</id>
    <description>Database Mem Saturation</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>MaxMemTest</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>MaxMemTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



### Business Concurrency Exceeds the Database CPU Capacity Limit (103)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar MaxCPUTest
```

```xml
<case>
    <id>105</id>
    <description>Database CPU Saturation</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>MaxCPUTest</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>MaxCPUTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## Stored Procedure Stack Overflow (105)

```shell
 java -cp .:opengauss-jdbc-7.0.0-RC2.jar StackOverflowTest \
    -type proc_recurse\
    -duration 60000 \
    -interval 100
```

```xml
<case>
    <id>105</id>
    <description>Procedure Stack Overflow</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>StackOverflowTest</arg>
        <arg>-type</arg>
        <arg>proc_recurse</arg>
        <arg>-duration</arg>
        <arg>60000</arg>
        <arg>-interval</arg>
        <arg>100</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>StackOverflowTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## Function Stack Overflow (106)

```shell
 java -cp .:opengauss-jdbc-7.0.0-RC2.jar StackOverflowTest \
    -type func_recurse\
    -duration 60000 \
    -interval 100
```

```xml
<case>
    <id>106</id>
    <description>Function Stack Overflow</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>StackOverflowTest</arg>
        <arg>-type</arg>
        <arg>func_recurse</arg>
        <arg>-duration</arg>
        <arg>60000</arg>
        <arg>-interval</arg>
        <arg>100</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>StackOverflowTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## Transaction Stack Overflow (107)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar StackOverflowTest \
    -type trans_recurse\
    -duration 60000 \
    -interval 100
```

```xml
<case>
    <id>107</id>
    <description>Transaction Stack Overflow</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>StackOverflowTest</arg>
        <arg>-type</arg>
        <arg>trans_recurse</arg>
        <arg>-duration</arg>
        <arg>60000</arg>
        <arg>-interval</arg>
        <arg>100</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>StackOverflowTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## Recursive Call Stack Overflow (108)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar StackOverflowTest \
    -type sql_depth \
    -duration 60000 \
    -interval 100
```

```xml
<case>
    <id>108</id>
    <description>Recursive Stack Overflow</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>StackOverflowTest</arg>
        <arg>-type</arg>
        <arg>sql_depth</arg>
        <arg>-duration</arg>
        <arg>60000</arg>
        <arg>-interval</arg>
        <arg>100</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>StackOverflowTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## Mass Transaction Rollback (109)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar MassiveRollbackTest \
    -duration 120 \
    -threads 16 \
    -rate 0.7
```

```xml
<case>
    <id>109</id>
    <description>Massive Transaction Rollback Storm</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>MassiveRollbackTest</arg>
        <arg>-duration</arg>
        <arg>60</arg>
        <arg>-threads</arg>
        <arg>16</arg>
        <arg>-rate</arg>
        <arg>0.7</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>MassiveRollbackTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## max_prepared_transaction Limit Reached (110)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar UncommittedTransactionFaultInject -lockHoldingSessions 1 -waitingSessions 50 -lockHoldDuration 60 -lockRowsCount 10 -statementTimeoutSeconds 30
# lockHoldingSessions: number of transactions holding locks
# waitingSessions: number of waiting transactions
# lockHoldDuration: duration for which locks are held
# lockRowsCount: number of locked data rows
# statementTimeoutSeconds: timeout
```

```xml
<case>
    <id>110</id>
    <description>Max_prepared_transaction Limit Exceeded</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>UncommittedTransactionFaultInject</arg>
        <arg>-lockHoldingSessions</arg>
        <arg>1</arg>
        <arg>-waitingSessions</arg>
        <arg>50</arg>
        <arg>-lockHoldDuration</arg>
        <arg>60</arg>
        <arg>-lockRowsCount</arg>
        <arg>10</arg>
        <arg>-statementTimeoutSeconds</arg>
        <arg>30</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>UncommittedTransactionFaultInject</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## Transactions Not Committed (111)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar MaxPreparedTransactionFaultInject -target-prepared-count 201 -client-concurrency 100   -hold-duration-sec 60
# target-prepared-count: target total number of prepared transactions
# client-concurrency: client concurrency
# hold-duration-sec: duration of prepared transactions
```

```xml
<case>
    <id>111</id>
    <description>Uncommitted Transaction</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>MaxPreparedTransactionFaultInject</arg>
        <arg>-target-prepared-count</arg>
        <arg>201</arg>
        <arg>-client-concurrency</arg>
        <arg>100</arg>
        <arg>-hold-duration-sec</arg>
        <arg>60</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>MaxPreparedTransactionFaultInject</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## Re-execution (112)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar DuplicateTransactionFaultInject -concurrentSessions 50 -lockHoldDuration 30
# concurrentSessions: number of concurrent sessions
# lockHoldDuration: test duration
```

```xml
<case>
    <id>112</id>
    <description>Re-execution Transaction</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>DuplicateTransactionFaultInject</arg>
        <arg>-concurrentSessions</arg>
        <arg>50</arg>
        <arg>-lockHoldDuration</arg>
        <arg>30</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>DuplicateTransactionFaultInject</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```





## Burst of Massive Connection Requests (113)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar MaxConnectionTest \
-mode conn_storm -duration 120000
```

```xml
<case>
    <id>113</id>
    <description>Database Connection Storm</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>MaxConnectionTest</arg>
        <arg>-mode</arg>
        <arg>conn_storm</arg>
        <arg>-duration</arg>
        <arg>60000</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>MaxConnectionTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## Error When Connection Count Exceeds the Limit (114)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar MaxConnectionTest \
-mode conn_exhaustion -duration 120000
```

```xml
<case>
    <id>114</id>
    <description>Database Connection Limit Exceeded</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>MaxConnectionTest</arg>
        <arg>-mode</arg>
        <arg>conn_exhaustion</arg>
        <arg>-duration</arg>
        <arg>60000</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>MaxConnectionTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```



## Exceeding the Active Thread Pool Limit (115)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar MaxConnectionTest \
-mode thread_saturation -count 32 -duration 120000
```

```xml
<case>
    <id>115</id>
    <description>Database Active Thread Pool Saturation</description>
    <agents>
        <agent>master:8000</agent>
    </agents>
    <injection>
        <cmd>/opt/java-21/bin/java</cmd>
        <arg>-cp</arg>
        <arg>scripts/java/opengauss-jdbc-7.0.0-RC2.jar:scripts/java</arg>
        <arg>MaxConnectionTest</arg>
        <arg>-mode</arg>
        <arg>thread_saturation</arg>
        <arg>-count</arg>
        <arg>32</arg>
        <arg>-duration</arg>
        <arg>60000</arg>
    </injection>
    <recovery>
        <cmd>pkill</cmd>
        <arg>-f</arg>
        <arg>MaxConnectionTest</arg>
    </recovery>
    <autoRecovery>0</autoRecovery>
</case>
```





### Modifying fault-cases-generic.xml (Adding Fault Orchestration)

```xml
<testSuite>
    <case id="101">
        <description>Database I/O Saturation</description>
        <planning>120</planning>
        <during>60</during>
    </case>
    <case id="102">
        <description>Database Mem Saturation</description>
        <planning>200</planning>
        <during>60</during>
    </case>
    <case id="103">
        <description>Database CPU Saturation</description>
        <planning>280</planning>
        <during>60</during>
    </case>
    <case id="105">
        <description>Procedure Stack Overflow</description>
        <planning>360</planning>
        <during>60</during>
    </case>
    <case id="106">
        <description>Function Stack Overflow</description>
        <planning>440</planning>
        <during>60</during>
    </case>
    <case id="107">
        <description>Transaction Stack Overflow</description>
        <planning>520</planning>
        <during>60</during>
    </case>
    <case id="108">
        <description>Recursive Stack Overflow</description>
        <planning>600</planning>
        <during>60</during>
    </case>
    <case id="109">
        <description>Massive Transaction Rollback Storm</description>
        <planning>680</planning>
        <during>60</during>
    </case>
    <case id="110">
        <description>max_prepared_transaction Limit Exceeded</description>
        <planning>760</planning>
        <during>60</during>
    </case>
    <case id="111">
        <description>Uncommitted Transaction</description>
        <planning>840</planning>
        <during>60</during>
    </case>
    <case id="112">
        <description>Re-execution Transaction</description>
        <planning>920</planning>
        <during>60</during>
    </case>
    <case id="113">
        <description>Database Connection Storm</description>
        <planning>1000</planning>
        <during>60</during>
    </case>
    <case id="114">
        <description>Database Connection Limit Exceeded</description>
        <planning>1080</planning>
        <during>60</during>
    </case>
    <case id="115">
        <description>Database Active Thread Pool Saturation</description>
        <planning>1160</planning>
        <during>60</during>
    </case>
</testSuite>
```



## Modifying smallbankbbh-worker.xml

```xml
<works>
    <!-- Phase1 -->
    <work arrival="REGULAR">
        <time>1200</time>
        <rate>50000</rate>
        <weights>5,25,20,15,15,20</weights>
        <testSuite>base-test-bbhV2-suite</testSuite>
    </work>
</works>
```

