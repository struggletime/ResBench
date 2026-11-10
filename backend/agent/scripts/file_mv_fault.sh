#!/bin/bash

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
    tmp_file_mv="/tmp/file_mv_fault_$(basename "$target").tmp"
  else
    target="$2"
    if [ -z "$target" ] ; then
      echo "错误：请输入目标文件名"
      exit 1
    fi

    tmp_file_mv="/tmp/file_mv_fault_$(basename "$target").tmp"
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
  data_path=$(su - "$db_user" -c "cm_ctl query -Cvd | tr '|' '\n' | grep \"Primary Normal\" | awk '{print \$4}'")
    if [ -z "$data_path" ]; then
      echo "错误：无法获得数据库路径"
      exit 1
    fi
    echo "数据库路径：$data_path"

  case "$target_type" in
    "data")
      handle_data_inject "$db_user" "$target" "$data_path" "${@:5}"
      ;;
    "file")
      handle_file_inject "$target" "$data_path"
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
  local data_path="$3"
  local database_name="$4"
  local db_port="$5"
  if [ -z "$database_name" ] || [ -z "$db_port" ]; then
    echo "错误：请输入数据库名和port"
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
  echo "${file_path}:${file_path}_bak" >> "$tmp_file_mv"
  mv "$file_path" "${file_path}_bak"
  echo "已将文件移动为：${file_path}_bak"

}

handle_file_inject(){
  local target="$1"
  local data_path="$2"
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
  echo "${file_path}:${file_path}_bak" >> "$tmp_file_mv"
  mv "$file_path" "${file_path}_bak"
  echo "已将文件移动为：${file_path}_bak"

}

handle_recovery(){
  if [ ! -f "$tmp_file_mv" ]; then
    echo "没有需要恢复的文件记录"
    return
  fi

  while IFS= read -r line;do
    file_origin=$(echo "$line" |cut -d':' -f1)
    file_new=$(echo "$line" |cut -d':' -f2)
    if [ -e "$file_new" ]; then
      mv "$file_new" "$file_origin"
      echo "已恢复文件$file_new为:$file_origin"
    else
      echo "警告：文件$file_new不存在"
    fi
    grep -v "$file_origin" "$tmp_file_mv" >"$tmp_file_mv.tmp"
    mv "$tmp_file_mv.tmp" "$tmp_file_mv" 2>/dev/null || true
  done <"$tmp_file_mv"

  if [ ! -s "$tmp_file_mv" ]; then
    rm -f "$tmp_file_mv"
  fi
}

main "$@"
