#!/usr/bin/env python3
"""给 chaosblade 的 os 规格 yaml 的 mem load 动作补一条 ns_target flag。

chaosblade 1.7.4 的 `bin/chaos_os` 本身支持 `--ns_target`（exec-os 1.6.0 起用于按
容器 cgroup 计算），但 CLI（`blade`）按 `yaml/chaosblade-os-spec-<ver>.yaml` 注册
flag，规格里没有就报 `unknown flag: --ns_target`。这个脚本把该 flag 补进 mem load
的 flags 列表，CLI 才会接受并把参数透传给 chaos_os。

用法：
    python3 scripts/chaosblade/patch-os-spec.py chaosblade-os-spec-1.7.4.yaml

原地修改，重复执行不会插入多条。改完 docker cp 回容器：
    docker cp chaosblade-os-spec-1.7.4.yaml <容器>:/opt/chaosblade/yaml/
验证：
    docker exec <容器> /opt/chaosblade/blade create mem load -h | grep ns_target
"""
import sys

FLAG_BLOCK = """    - name: ns_target
      desc: target pid to enter its namespaces, used with channel nsexec
      noArgs: false
      required: false
      requiredWhenDestroyed: false
"""


def patch(lines):
    start = next(i for i, l in enumerate(lines) if l.strip() == "- target: mem")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("- target:")), len(lines))
    action = next(i for i in range(start, end) if lines[i].strip() == "- action: load")
    channel = next(i for i in range(action, end) if lines[i].strip() == "- name: channel")

    # channel 这一段到下一个 flag（或缩进更浅的行）为止
    indent = len(lines[channel]) - len(lines[channel].lstrip())
    block_end = channel + 1
    while block_end < end:
        line = lines[block_end]
        if line.strip().startswith("- name:"):
            break
        if line.strip() and (len(line) - len(line.lstrip())) < indent:
            break
        block_end += 1

    if any(lines[i].strip() == "- name: ns_target" for i in range(action, end)):
        return lines, False
    return lines[:block_end] + FLAG_BLOCK.splitlines(keepends=True) + lines[block_end:], True


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    path = sys.argv[1]
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()
    patched, changed = patch(lines)
    if not changed:
        print(f"{path}: ns_target 已存在，未修改")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.writelines(patched)
    print(f"{path}: 已在 mem load 下补入 ns_target")


if __name__ == "__main__":
    main()
