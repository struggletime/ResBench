# 故障注入命令

| 三级故障模式                     | 故障ID |
| -------------------------------- | ------ |
| 业务并发量超过数据库IO容量上限   | 101    |
| 业务并发量超过数据库内存容量上限 | 102    |
| 业务并发量超过数据库CPU容量上限  | 103    |
| 句柄耗尽                         | 104    |
| 存储过程爆栈                     | 105    |
| 函数爆栈                         | 106    |
| 事务爆栈                         | 107    |
| 递归调用爆栈                     | 108    |
| 海量子事务                       | /      |
| 大量事务回滚                     | 109    |
| max_prepared_transaction达到上限 | 110    |
| 对热点行高并发更新               | /      |
| 事务不提交                       | 111    |
| 重复执行                         | 112    |
| 突发大量连接请求                 | 113    |
| 连接数超过限制报错               | 114    |
| CN、DN活跃线程数超过线程池上限   | 115    |
| stream线程数超过线程池上限       | 115    |
| max_dynamic_memory达到上限       | 116    |



### 注意事项：

**当前故障脚本 java版本 >= 21**

### 业务并发量超过数据库IO容量上限(101)

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



### 业务并发量超过数据库内存容量上限(102)

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



### 业务并发量超过数据库CPU容量上限(103)

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



## 存储过程爆栈(105)

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



## 函数爆栈(106)

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



## 事务爆栈(107)

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



## 递归调用爆栈(108)

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



## 大量事务回滚(109)

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



## max_prepared_transaction达到上限(110)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar UncommittedTransactionFaultInject -lockHoldingSessions 1 -waitingSessions 50 -lockHoldDuration 60 -lockRowsCount 10 -statementTimeoutSeconds 30
# lockHoldingSessions：持有锁的事务数
# waitingSessions：等待事务数
# lockHoldDuration：持有锁持续时间
# lockRowsCount：锁住的数据行数
# statementTimeoutSeconds：超时时间
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



## 事务不提交(111)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar MaxPreparedTransactionFaultInject -target-prepared-count 201 -client-concurrency 100   -hold-duration-sec 60
# target-prepared-count：目标prepare事务总数
# client-concurrency：客户端并发数
# hold-duration-sec：prepare事务持续时间
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



## 重复执行(112)

```shell
java -cp .:opengauss-jdbc-7.0.0-RC2.jar DuplicateTransactionFaultInject -concurrentSessions 50 -lockHoldDuration 30
# concurrentSessions：并发会话数
# lockHoldDuration：测试持续时间
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





## 突发大量连接请求(113)

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



## 连接数超过限制报错(114)

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



## 超过活跃线程池上限(115)

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





### 修改fault-cases-generic.xml（新增故障编排）

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



## 修改smallbankbbh-worker.xml

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

