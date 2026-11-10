#!/bin/bash

echo "将启动observer进程"
su - admin -c "cd /home/admin/observer; ./bin/observer"
echo "已完成"
exit 0