#!/bin/bash

if [ -z "$1" ]; then
  echo "用法: $0 <进程名>"
  exit 1
fi

PROCESS_NAME=$1

PIDS=$(ps -ef | grep "$PROCESS_NAME" | grep -v grep | awk '{print $2}')

if [ -z "$PIDS" ]; then
  echo "未找到匹配的进程：$PROCESS_NAME"
  exit 0
fi

echo "将挂起以下进程: $PIDS"
echo "$PIDS" | xargs -n1 kill -STOP
echo "已挂起完成"