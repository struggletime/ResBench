#!/bin/bash
##示例：sh file_chmod_fault.sh inject data omm t1 400 postgres 26000
##示例：sh file_chmod_fault.sh inject file omm 400 global/pg_control
##示例：sh file_chmod_fault.sh recovery omm global/pg_control
##示例：sh file_chmod_fault.sh recovery omm t1
validate_input() {
  if [ "$operation_type" != "inject" ] && [ "$operation_type" != "recovery" ]; then
    echo "错误：操作类型必须是 'inject' 或 'recovery'"
    exit 1
  fi
}

main() {
  operation_type="$1"
  validate_input
  if [ "$operation_type" == "inject" ]; then
    target="$4"
    tmp_file_chmod="/tmp/file_mv_fault_$(basename "$target").tmp"
  else
    target="$2"
    tmp_file_chmod="/tmp/file_mv_fault_$(basename "$target").tmp"
  fi

  case "$operation_type" in
    "inject")
      handle_inject "$1" "$2" "$3" "$4" "$5" "${@:6}"
      ;;
    "recovery")
      handle_recovery
      ;;
    *)
      echo "错误：操作类型必须是 'inject' 或 'recovery'"
      exit 1
      ;;
  esac
}
handle_inject(){
  target_type="$2"
  db_user="$3"
  target="$4"
  chmod_values="$5"

  data_path=$(su - "$db_user" -c "cm_ctl query -Cvd | tr '|' '\n' | grep \"Primary Normal\" | awk '{print \$4}'")
    if [ -z "$data_path" ]; then
      echo "错误：无法获得数据库路径"
      exit 1
    fi
    echo "数据库路径：$data_path"

  case "$target_type" in
    "data")
      handle_data_inject "$db_user" "$target" "$chmod_values" "$data_path" "${@:6}"
      ;;
    "file")
      handle_file_inject "$target" "$chmod_values" "$data_path"
      ;;
    *)
      echo "无效目标类型，请指定 'data' 或 'file' "
      exit 1
      ;;
  esac

}
handle_data_inject(){
  local db_user="$1"
  local target="$2"
  local chmod_values="$3"
  local data_path="$4"
  local database_name="$5"
  local db_port="$6"

  if [ -z "$database_name" ] || [ -z "$db_port" ]; then
    echo "错误：操作类型必须是 'inject' 或 'recovery'"
    exit 1
  fi

  table_oid=$(su - $db_user -c "gsql -d "$database_name" -p "$db_port" -c \"select oid from pg_class where relname ='$target';\""|grep -oE '[0-9]{2,}')
  database_oid=$(su - $db_user -c "gsql -d "$database_name" -p "$db_port" -c \"select oid from pg_database where datname ='$database_name';\""|grep -oE '[0-9]{2,}')
  if [ -z "$database_oid" ]; then
    echo "未找到匹配的数据库：$database_name"
    exit 1
  fi

  if [ -z "$table_oid" ]; then
    echo "未找到匹配的表：$target"
    exit 1
  fi
  file_path="${data_path}/base/${database_oid}/${table_oid}"
  echo "目标文件路径:$file_path"
  if [ ! -e "$file_path" ]; then
    echo "错误：文件不存在:$file_path"
    exit 1
  fi

  origin_mark=$(stat -c "%a" "$file_path" 2>/dev/null)
  if [ -z "$origin_mark" ]; then
    echo "错误：无法获取文件原始权限"
    exit 1
  fi

  echo "${file_path}:${origin_mark}" >> "$tmp_file_chmod"
  echo "原始权限:$origin_mark"
  chmod "$chmod_values" "$file_path"

  echo "已将文件权限修改为：${chmod_values}"

}

handle_file_inject(){
  local target="$1"
  local chmod_values="$2"
  local data_path="$3"
  if [[ "$target" == *".."* ]]; then
    echo "错误：文件路径包含不安全的路径遍历"
    exit 1
  fi
  file_path="${data_path}/${target}"
  echo "目标文件路径:$file_path"
  if [ ! -e "$file_path" ]; then
    echo "错误：文件不存在:$file_path"
    exit 1
  fi
  origin_mark=$(stat -c "%a" "$file_path" 2>/dev/null)
  if [ -z "$origin_mark" ]; then
    echo "错误：无法获取文件原始权限"
    exit 1
  fi

  echo "${file_path}:${origin_mark}" >> "$tmp_file_chmod"
  echo "原始权限:$origin_mark"
  chmod "$chmod_values" "$file_path"

  echo "已将文件权限修改为：${chmod_values}"

}

handle_recovery(){
  if [ ! -f "$tmp_file_chmod" ]; then
    echo "没有需要恢复的文件记录"
    return
  fi

  while IFS= read -r line;do
    file_path=$(echo "$line" | cut -d':' -f1)
    origin_mark=$(echo "$line" | cut -d':' -f2)
    if [ -e "$file_path" ]; then
      chmod "$origin_mark" "$file_path"
      echo "已恢复文件$file_path 权限为:$origin_mark"
    else
      echo "警告：文件$file_path 不存在"
    fi
    grep -v "$file_path" "$tmp_file_chmod" >"$tmp_file_chmod.tmp"
    mv "$tmp_file_chmod.tmp" "$tmp_file_chmod" 2>/dev/null || true
  done <"$tmp_file_chmod"

  if [ ! -s "$tmp_file_chmod" ]; then
    rm -f "$tmp_file_chmod"
  fi
}

main "$@"
