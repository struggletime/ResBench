English | [简体中文](README_cn.md)

# "Reorder Rate" Metric Rollout Notes (qdisc_exporter)

Goal: a **NIC reorder rate** appears on the monitoring page, and it must rise during reorder fault injection.

The data comes from `requeues` in the output of `tc -s qdisc show dev ens17f0` — the number of packets that netem determines "need reordering". It is not a general-purpose kernel counter; only netem records it as reorder hits: it is always 0 when no reorder rule is attached, and grows with traffic during injection.

> ⚠ Interpretation must always be done in pairs: a high `net.qdisc.reorder.rate` does **not** mean reordering actually occurred.
> When percent ≥ 90, netem marks almost all packets as "send immediately", and delay is collapsed to 0ms;
> the hit rate is 100% while the peer sees no reordering at all (measured locally: 2000/2000 hits, delay 0.06ms, reordering 0;
> only 30–50% produces real reordering). Therefore `net.qdisc.delay` must be included on the same screen.

## 1. First determine which path the target machine uses (run on YOUR_DB_HOST)

```bash
ss -ltnp | grep 19091                      # Find the Agent's PID
tr '\0' '\n' < /proc/<PID>/cmdline | grep -i textfile
```

- If the output contains `--collector.textfile.directory=...` → use **A. textfile** (no configuration changes needed)
- If not → use **B. standalone endpoint** (change the scrape job in prometheus.yml once, then reload; no restart needed)

## 2. Place the script on the target machine

```bash
install -m 0755 qdisc_exporter.py /usr/local/bin/qdisc_exporter.py
```

The script uses only the standard library and must run as root (`tc` requires CAP_NET_ADMIN). The NIC name must match the `interface` of the engine's network fault (default `ens17f0`, changeable with `--dev`).

### A. textfile mode

```bash
# Verify in the foreground and check that the output is correct first
python3 /usr/local/bin/qdisc_exporter.py --textfile /var/lib/node_exporter/textfile/qdisc.prom --interval 3 &
sleep 3 && cat /var/lib/node_exporter/textfile/qdisc.prom
curl -s http://127.0.0.1:19091/metrics | grep netem_requeues_total   # Should be visible

# For long-term operation: set it up as a systemd service
cat >/etc/systemd/system/qdisc-exporter.service <<'EOF'
[Unit]
Description=netem qdisc metrics exporter
[Service]
ExecStart=/usr/bin/python3 /usr/local/bin/qdisc_exporter.py --textfile /var/lib/node_exporter/textfile/qdisc.prom --interval 3
Restart=always
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now qdisc-exporter
```

### B. standalone endpoint mode

```bash
cat >/etc/systemd/system/qdisc-exporter.service <<'EOF'
[Unit]
Description=netem qdisc metrics exporter
[Service]
ExecStart=/usr/bin/python3 /usr/local/bin/qdisc_exporter.py --serve 0.0.0.0:19200
Restart=always
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now qdisc-exporter
curl -s http://127.0.0.1:19200/metrics | head
```

Then add a scrape job to `prometheus.yml`. **The instance must be pinned to 19091**, otherwise the `instance="{{instance}}"` template in the catalog will not match:

```yaml
  - job_name: resbench-qdisc
    scrape_interval: 3s
    static_configs:
      - targets: ["127.0.0.1:19200"]
        labels:
          instance: "127.0.0.1:19091"
```

Reload (no data loss, no restart):

```bash
curl -X POST http://127.0.0.1:9090/-/reload     # requires --web.enable-lifecycle
# If lifecycle is not enabled, send SIGHUP: kill -HUP $(pidof prometheus)
```

## 3. Verification

```bash
# Should be queryable in Prometheus immediately (0 before injection)
curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=max(netem_present)' | head -c 300
```

Attach a rule manually and you will see it rise without waiting for a full benchmark round:

```bash
tc qdisc add dev ens17f0 root netem delay 10ms reorder 30% 80%
ping -f -c 2000 <PEER_IP> >/dev/null 2>&1 || true
curl -s 'http://127.0.0.1:9090/api/v1/query' \
  --data-urlencode 'query=rate(netem_requeues_total[1m])' | head -c 300
tc qdisc del dev ens17f0 root
```

> Do not run `tc qdisc del` manually in production: it will override the rules that chaosblade is currently managing.
> The command above is only for smoke testing when you have confirmed that no benchmark is running.

## 4. Enable the metrics

`MonitoringCatalog.java` already registers three metrics (`net.qdisc.reorder.rate` is checked by default):

| metricId | Name | Purpose |
|---|---|---|
| `net.qdisc.reorder.rate` | NIC reorder rate | `rate(netem_requeues_total[1m])`, rises during injection |
| `net.qdisc.delay` | netem configured delay | Prevents a no-op false positive where "the hit rate is high but delay collapses to 0" |
| `net.qdisc.reorder.percent` | netem reorder percentage | Confirms the level actually applied |

They have no `collectorId`: the series are exposed directly by this script and are not in the agent's collector list.
`MonitoringService.supports()` permits such metrics; when the exporter is not actually deployed, they are shown as an empty curve/QUERY_FAILED.

res-manager must be restarted to load the new catalog (the process on 8090).

## 5. Two sampling limitations

1. **The scrape interval must not be faster than the exporter's refresh interval.** The `openinsight-agent` job is 3s; the `cadvisor` job was first set to 3s on 2026-10-25, and on the same day, because "a 30s CPU fault took 20 seconds to saturate the curve", it was changed again to **1s**, while the cadvisor container was changed from `--housekeeping_interval=10s` to `1s` (measured: the source-side timestamp step went from 16.3s to 1.2~1.7s, and Prometheus data points per minute went from 4 → 40). The qdisc job is also set to 3s, with `--interval` set to 3.
   As long as scraping is faster than the source-side refresh, Prometheus only stores the same value repeatedly: the curve looks dense but has not actually changed — the cadvisor case of 3s scraping + 16s source refresh is typical: only 4 real samples per minute.
2. **A fault duration of 120s is recommended.** netstat-style counters on the host refresh every 60s, so a 30s fault is already undersampled; short-window faults such as reordering especially need to be extended to be identified reliably by the criteria.
