#!/bin/bash
if [ $# -eq 0 ]; then
    echo "请至少传入一个参数（用户）"
    exit 1
fi

user=$1

su - $user -c "
    if [[ \"\$USER\" == \"root\" ]]; then
        exit 1
    fi

    port=\$(cm_ctl view | grep etcd | grep etcdClientListenPort | tail -n 1 | awk -F ':' '{print \$2}')
    node=\$(cm_ctl query -Cv | tr '|' '\n' | grep \"Primary Normal\" | awk '{print \$1}')
    ip=\$(cm_ctl view | grep etcd | grep etcdClientListenIPs | awk -F ':' '{print \$2}' | tail -n 1)

    export ETCDCTL_API=3
    etcdctl \\
        --cacert \${GAUSSHOME}/share/sslcert/etcd/etcdca.crt \\
        --cert \${GAUSSHOME}/share/sslcert/etcd/client.crt \\
        --key \${GAUSSHOME}/share/sslcert/etcd/client.key \\
        --command-timeout 60s \\
        --endpoints https://\${ip}:\${port} \\
        put /\$USER/CMServer/primary_node_id \$node

    sleep 5
"

shift
args="$*"
$args

