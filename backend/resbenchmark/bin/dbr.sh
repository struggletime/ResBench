#!/bin/bash
base=$(cd `dirname $0`; pwd)
home=${base}/..

export SERVER_PORT=8282
DBR_NAME=resbenchmark
DEBUG_OPTS="-Xdebug  -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=0.0.0.0:3992"

JAVA_OPTS="-Xmx2048M"

function shutdown()
{
        pids=`ps -ef | grep ${DBR_NAME} | grep -v grep | awk '{print $2}'`
        for pid in $pids
        do
                kill -9 ${pid}
        done
}

function main()
{
        if [ $# -lt 1 ];
        then
                echo -e "Usage:"
                echo -e "\t\t$0 [start|stop|debug]"
                exit 0;
        fi

        case $1 in

        start)
                     nohup java ${JAVA_OPTS} ${SPRING_OPTS}  -jar ${home}/${DBR_NAME}.jar server > /dev/null 2>&1 & ;;

        job)
                     shift
                     java ${JAVA_OPTS} ${SPRING_OPTS}  -jar ${home}/${DBR_NAME}.jar job $@;;
        job-debug)
                     shift
                     java ${JAVA_OPTS} ${DEBUG_OPTS} ${SPRING_OPTS}  -jar ${home}/${DBR_NAME}.jar job $@;;

        stop)
                shutdown;;
        debug)
                nohup java ${JAVA_OPTS} ${DEBUG_OPTS}  -jar ${home}/${DBR_NAME}.jar server > /dev/null 2>&1 &;;
        *)
                echo "ERROR:wrong parameter."
                exit 1;;
        esac
        exit 0
}

cd ${home}
main $@
cd -

