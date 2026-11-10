// 故障关系知识图谱种子数据（与 engine 内置内存假图谱 FakeKnowledgeGraphSeed 保持一致）
//
// 用法：
//   cypher-shell -a bolt://127.0.0.1:7687 -u neo4j -p <password> -f scripts/neo4j/seed-fault-relations.cypher
//
// schema：(:Fault {nameKey}), (:Fault)-[:MUTEX|DUPLICATE]->(:Fault)
// nameKey 与 fault-cases.yaml / 前端故障目录中的 nameKey 完全一致。

// 互斥：连接数暴涨与连接数耗尽不能同时注入
MERGE (a:Fault {nameKey: 'faultinject.fault.connection.surge'})
MERGE (b:Fault {nameKey: 'faultinject.fault.connection.limit.exceeded'})
MERGE (a)-[:MUTEX]->(b);

// 语义重复：两种事务栈溢出只是触发入口不同，同时注入没有额外意义
MERGE (c:Fault {nameKey: 'faultinject.fault.sql.stack_overflow.sql_depth'})
MERGE (d:Fault {nameKey: 'faultinject.fault.sql.stack_overflow.view_nest'})
MERGE (c)-[:DUPLICATE]->(d);
