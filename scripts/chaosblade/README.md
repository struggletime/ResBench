English | [简体中文](README_cn.md)

# chaosblade Deployment Inside Containers (memory faults measured at container scope)

`memory-percent` yields "container scope" only when it reads the **cgroup memory limit** inside the container. chaosblade
reads `/proc/meminfo` by default (the container does not isolate that file), so the value it computes is the host total; switching to cgroup scope requires all of the following:

1. chaosblade inside the container ≥ **1.7.4** (`--channel nsexec` / `--ns_target` became available starting with exec-os 1.6.0);
2. **add `ns_target`** to the mem load section of `yaml/chaosblade-os-spec-<ver>.yaml` inside the container
   (the official 1.7.4 yaml omits it as well, and the CLI first reports `unknown flag: --ns_target`);
3. place the `blade-cg` wrapper script (see below) in the container and let it append `--cgroup-root`.

When `containerName` is non-empty, the engine automatically switches to `/opt/chaosblade/blade-cg create mem load ... --channel nsexec --ns_target 1`,
while host deployments still use `/opt/chaosblade/blade` (whole-machine scope).

## 1. Install 1.7.4

Run the following **on the host** (the host needs internet access; if there is no external network, copy the tarball over):

```shell
curl -fL -o /tmp/chaosblade-1.7.4-linux-amd64.tar.gz \
  https://github.com/chaosblade-io/chaosblade/releases/download/v1.7.4/chaosblade-1.7.4-linux-amd64.tar.gz

# Back up the old version in the container first
docker exec <container> tar czf /opt/chaosblade-backup.tgz -C /opt chaosblade

docker cp /tmp/chaosblade-1.7.4-linux-amd64.tar.gz <container>:/opt/cb174.tar.gz
docker exec <container> sh -c 'cd /opt && tar xzf cb174.tar.gz && rm cb174.tar.gz \
  && mv chaosblade chaosblade-old && mv chaosblade-1.7.4 chaosblade'
docker exec <container> /opt/chaosblade/blade version   # expect version: 1.7.4
```

## 2. Add ns_target to the yaml

```shell
# On the host, first extract the yaml, apply the patch, then copy it back to the container
mkdir -p /tmp/cb174 && tar xzf /tmp/chaosblade-1.7.4-linux-amd64.tar.gz -C /tmp/cb174 \
  chaosblade-1.7.4/yaml/chaosblade-os-spec-1.7.4.yaml
python3 scripts/chaosblade/patch-os-spec.py /tmp/cb174/chaosblade-1.7.4/yaml/chaosblade-os-spec-1.7.4.yaml
docker cp /tmp/cb174/chaosblade-1.7.4/yaml/chaosblade-os-spec-1.7.4.yaml \
  <container>:/opt/chaosblade/yaml/

# Verify that the CLI recognizes this flag
docker exec <container> /opt/chaosblade/blade create mem load -h | grep ns_target
#   --ns_target string   target pid to enter its namespaces, used with channel nsexec
```

## 3. Install the blade-cg wrapper script

```shell
docker cp scripts/chaosblade/blade-cg <container>:/opt/chaosblade/blade-cg
docker exec <container> chmod 755 /opt/chaosblade/blade-cg
```

**Why it is needed**: chaosblade uses `cgroups.Load()` to read the target process's cgroup, with the path
`<cgroup-root>/memory/<memory path from /proc/PID/cgroup>`. When a container uses the host cgroup namespace:

- `/proc/1/cgroup` contains `/docker/<id>`;
- `/sys/fs/cgroup/memory` is nevertheless mounted at the **container's own** cgroup directory (the files sit directly at this level).

As a result, the assembled `/sys/fs/cgroup/memory/docker/<id>` does not exist, and chaos_os immediately reports
`level=fatal msg="calculate memsize err, load cgroup error, cgroups: cgroup deleted"`,
and does not allocate any memory at all.

On each invocation, `blade-cg` reads the current id from `/proc/1/cgroup` and creates, under
`/opt/chaosblade/cgroot/memory/docker/<id>`, a symlink pointing to `/sys/fs/cgroup/memory`;
it then appends `--cgroup-root /opt/chaosblade/cgroot` to `create`. Even if the container is recreated and the id changes, the script needs no modification.
(The container lacks `CAP_SYS_ADMIN`, so `unshare --cgroup` is unavailable; hence the symlink approach.)

## 4. Smoke test

```shell
# 20% will consume about limit*20% of memory (about 8 GB for a 40 GiB container); after 45 seconds --timeout reclaims it automatically
docker exec <container> /opt/chaosblade/blade-cg create mem load \
  --mode ram --mem-percent 20 --channel nsexec --ns_target 1 --timeout 45

# Open another window to watch the container cgroup
watch -n 2 'cat /sys/fs/cgroup/memory/memory.usage_in_bytes /sys/fs/cgroup/memory/memory.stat | head -3'
```

Expected:

- the return value is `{"code":200,"success":true,"result":"<uid>"}`;
- the RSS of the `chaos_os` process grows by +100 MB per second and essentially levels off at about `limit*percent/100`;
- `total_rss` rises in step (showing that the container cgroup is being measured, not the host's 540 GB);
- when the timeout is reached, the log shows `mode: destroy, target: mem, action: load`, RSS returns to zero, and `total_rss` falls back.

Cleanup (when the timeout has not been reached):

```shell
docker exec <container> /opt/chaosblade/blade destroy <uid>
```

> `blade destroy` only accepts a uid. `blade destroy mem load --mode ram ...` treats `mem` as the uid
> and queries the database, which fails — that is why the engine uses `autoRecovery=1` for mem faults and relies solely on `--timeout` for cleanup.

## 5. Common pitfalls

| Symptom | Cause / Handling |
|---|---|
| `unknown flag: --ns_target` | container chaosblade < 1.7.4, or the yaml has not been patched (step 2) |
| `cgroups: cgroup deleted` (chaosblade.log) | `blade-cg` was not used; the `--cgroup-root` symlink is missing (step 3) |
| `unknown flag: --mode` (destroy) | `blade destroy` does not support selecting by target/action and only accepts a uid |
| injection succeeds but container memory does not grow | confirm that `docker exec <container> cat /proc/1/cgroup` contains `/docker/<id>` and that `blade-cg` is installed |
| injection fails after the container is recreated | reinstall `/opt/chaosblade` (the container filesystem is not preserved); `blade-cg` will pick up the new id automatically |
