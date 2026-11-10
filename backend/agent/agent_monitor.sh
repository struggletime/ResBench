#!/bin/bash

PYTHON_CMD="python3"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

while getopts ":p:" opt; do
  case $opt in
    p)
      PYTHON_CMD=$OPTARG
      ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      exit 1
      ;;
  esac
done
shift $((OPTIND-1))

# 检查目录下是否存在 main.py
if [ ! -f "$SCRIPT_DIR/main.py" ]; then
    echo "Error: main.py does not exist in the current directory."
    exit 1
fi

check_and_start() {
    # 检查 main.py 是否在运行
    cd $SCRIPT_DIR
    if ! pgrep -f "$PYTHON_CMD $SCRIPT_DIR/main.py" > /dev/null; then
        echo "[$(date)] main.py is not running, starting it..." >> "$SCRIPT_DIR/agent.log"
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH
        nohup $PYTHON_CMD "$SCRIPT_DIR/main.py" >> "$SCRIPT_DIR/agent.log" 2>&1 &
    fi
}

check_and_crontab()
{
    if ! crontab -l | grep -q "$0 -p $PYTHON_CMD check"; then
        (crontab -l ; echo "*/1 * * * * $0 -p $PYTHON_CMD check") | crontab -
    fi
}


if [ "$1" == "check" ]; then
    check_and_crontab
    check_and_start
fi

exit 0
