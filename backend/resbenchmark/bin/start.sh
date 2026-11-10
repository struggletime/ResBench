#!/bin/sh
base=$(cd `dirname $0`; pwd)
home=${base}/..
port=8089
JAVA_OPTS="-Xmx2048M"

nohup java ${JAVA_OPTS} -cp "${home}/resbenchmark.jar:${home}/lib/*" com.resbench.Application server --server.port=${port} --server.address=0.0.0.0 > /dev/null 2>&1 &
echo "${port} had been started";
