# 容器内 chaosblade 部署（内存故障按容器口径）

`memory-percent` 只有在容器里读到 **cgroup 的 memory limit** 才是「容器口径」。chaosblade
默认读 `/proc/meminfo`（容器不隔离该文件），算出来是宿主机总量；要切到 cgroup 口径必须同时满足：

1. 容器内 chaosblade ≥ **1.7.4**（exec-os 1.6.0 起才有 `--channel nsexec` / `--ns_target`）；
2. 容器内 `yaml/chaosblade-os-spec-<ver>.yaml` 的 mem load 里**补上 `ns_target`**
   （1.7.4 官方 yaml 也没写，CLI 会先报 `unknown flag: --ns_target`）；
3. 容器里放 `blade-cg` 包装脚本（见下），由它补 `--cgroup-root`。

引擎在 `containerName` 非空时自动改用 `/opt/chaosblade/blade-cg create mem load ... --channel nsexec --ns_target 1`，
宿主部署仍走 `/opt/chaosblade/blade`（整机口径）。

## 1. 装 1.7.4

在**宿主机**上执行（宿主机要能上网；没有外网就把 tarball 拷过去）：

```shell
curl -fL -o /tmp/chaosblade-1.7.4-linux-amd64.tar.gz \
  https://github.com/chaosblade-io/chaosblade/releases/download/v1.7.4/chaosblade-1.7.4-linux-amd64.tar.gz

# 先备份容器里的旧版本
docker exec <容器> tar czf /opt/chaosblade-backup.tgz -C /opt chaosblade

docker cp /tmp/chaosblade-1.7.4-linux-amd64.tar.gz <容器>:/opt/cb174.tar.gz
docker exec <容器> sh -c 'cd /opt && tar xzf cb174.tar.gz && rm cb174.tar.gz \
  && mv chaosblade chaosblade-old && mv chaosblade-1.7.4 chaosblade'
docker exec <容器> /opt/chaosblade/blade version   # 期望 version: 1.7.4
```

## 2. 给 yaml 补 ns_target

```shell
# 宿主机上先解出 yaml、打补丁，再拷回容器
mkdir -p /tmp/cb174 && tar xzf /tmp/chaosblade-1.7.4-linux-amd64.tar.gz -C /tmp/cb174 \
  chaosblade-1.7.4/yaml/chaosblade-os-spec-1.7.4.yaml
python3 scripts/chaosblade/patch-os-spec.py /tmp/cb174/chaosblade-1.7.4/yaml/chaosblade-os-spec-1.7.4.yaml
docker cp /tmp/cb174/chaosblade-1.7.4/yaml/chaosblade-os-spec-1.7.4.yaml \
  <容器>:/opt/chaosblade/yaml/

# 验证 CLI 已认这个 flag
docker exec <容器> /opt/chaosblade/blade create mem load -h | grep ns_target
#   --ns_target string   target pid to enter its namespaces, used with channel nsexec
```

## 3. 装 blade-cg 包装脚本

```shell
docker cp scripts/chaosblade/blade-cg <容器>:/opt/chaosblade/blade-cg
docker exec <容器> chmod 755 /opt/chaosblade/blade-cg
```

**为什么要它**：chaosblade 用 `cgroups.Load()` 读目标进程的 cgroup，路径是
`<cgroup-root>/memory/</proc/PID/cgroup 里的 memory 路径>`。容器用宿主 cgroup 命名空间时

- `/proc/1/cgroup` 写的是 `/docker/<id>`；
- `/sys/fs/cgroup/memory` 挂载的却已经是**容器自己的** cgroup 目录（文件直接在这一层）。

于是拼出来的 `/sys/fs/cgroup/memory/docker/<id>` 不存在，chaos_os 立刻
`level=fatal msg="calculate memsize err, load cgroup error, cgroups: cgroup deleted"`，
一行内存都不分配。

`blade-cg` 每次调用时从 `/proc/1/cgroup` 取当前 id，在
`/opt/chaosblade/cgroot/memory/docker/<id>` 建一个指向 `/sys/fs/cgroup/memory` 的符号链接，
再给 `create` 追加 `--cgroup-root /opt/chaosblade/cgroot`。容器重建后 id 变了也不用改脚本。
（容器没有 `CAP_SYS_ADMIN`，`unshare --cgroup` 不可用，所以走符号链接。）

## 4. 冒烟验证

```shell
# 20% 会占约 limit*20% 的内存（40 GiB 容器约 8 GB），45 秒后 --timeout 自动回收
docker exec <容器> /opt/chaosblade/blade-cg create mem load \
  --mode ram --mem-percent 20 --channel nsexec --ns_target 1 --timeout 45

# 另开一个窗口看容器 cgroup
watch -n 2 'cat /sys/fs/cgroup/memory/memory.usage_in_bytes /sys/fs/cgroup/memory/memory.stat | head -3'
```

期望：

- 返回值 `{"code":200,"success":true,"result":"<uid>"}`；
- `chaos_os` 进程 RSS 每秒 +100 MB，涨到约 `limit*percent/100` 后基本稳住；
- `total_rss` 同步上升（说明算的是容器 cgroup，不是宿主机 540 GB）；
- 到点后日志出现 `mode: destroy, target: mem, action: load`，RSS 归零、`total_rss` 回落。

清理（没等到 timeout 时）：

```shell
docker exec <容器> /opt/chaosblade/blade destroy <uid>
```

> `blade destroy` 只接受 uid。`blade destroy mem load --mode ram ...` 会把 `mem` 当 uid
> 查库而失败——所以引擎对 mem 故障用 `autoRecovery=1`，只靠 `--timeout` 回收。

## 5. 常见坑

| 现象 | 原因 / 处理 |
|---|---|
| `unknown flag: --ns_target` | 容器 chaosblade < 1.7.4，或 yaml 没打补丁（第 2 步） |
| `cgroups: cgroup deleted`（chaosblade.log） | 没走 `blade-cg`，缺 `--cgroup-root` 符号链接（第 3 步） |
| `unknown flag: --mode`（destroy） | `blade destroy` 不支持按 target/action，只能用 uid |
| 注入成功但容器内存不涨 | 确认 `docker exec <容器> cat /proc/1/cgroup` 里有 `/docker/<id>`，且 `blade-cg` 已装 |
| 容器重建后注入失败 | 重装 `/opt/chaosblade`（容器文件系统不会保留），`blade-cg` 会自动取新 id |
