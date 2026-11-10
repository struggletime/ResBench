#!/bin/sh
base=$(cd `dirname $0`; pwd)
home=${base}/..
VERSION=2024-SNAPSHOT
port=8090
SPRING_AGRS="--spring.config.location=${home}/conf/application.properties"
JAVA_OPTS="-Xmx2048M"

nohup java ${JAVA_OPTS} -jar ${home}/lib/res-manager-${VERSION}.jar --server.port=${port} ${SPRING_AGRS} > /dev/null 2>&1 &
echo echo "${port} had been started";
