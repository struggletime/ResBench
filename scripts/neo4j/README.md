# Neo4j for Fault Orchestration Validation

`kg_orchestration.py` reads the knowledge graph from Neo4j and decides whether the
selected fault orchestration is valid. The scripts in this directory install and
start a Neo4j Community instance from the official tarball under the ResBench
root. It listens on `127.0.0.1` only, and imports the graph automatically on the
first start.

## Quick start

```bash
bash scripts/neo4j/install-neo4j.sh   # first run: download, extract, generate password
bash scripts/neo4j/start-neo4j.sh     # start and import the graph (idempotent)
bash scripts/neo4j/stop-neo4j.sh      # stop
```

`start-dev.sh` runs these steps automatically when it starts ResBench. If Neo4j
is unavailable, it only logs a warning and does not block the Engine or Manager.

## Layout

| Path | Description |
| --- | --- |
| `neo4j/neo4j-community-2026.09.0/` | Extracted Neo4j distribution |
| `neo4j/current` | Symlink to the current version |
| `neo4j/data/` | Data directory (kept across upgrades) |
| `neo4j/logs/` | Log directory |
| `neo4j/.password` | Auto-generated password (mode 600) |

`neo4j/` is excluded by `.gitignore`.

## Python dependencies

```bash
<python> -m pip install -r scripts/neo4j/requirements.txt
```

`install-neo4j.sh` checks whether the current `python3` has the `neo4j` package
installed and only prints a hint; it never installs packages automatically.
The Engine interpreter can also be selected with `RESBENCH_PYTHON`.

## Graph

`graph/import.cypher` is derived from `resbench_kg_full_20261111`: 17
`AdverseEvent` nodes, 41 `RELATES_TO` relationships, and
`graph_version = resbench-kg-v1`. The file uses `CREATE`, so it can only be
imported into an empty database. `import-graph.sh` checks the node count first:
it skips the import when the graph is already complete, and refuses to import
twice when the data is incomplete, with an explanatory message.

When replacing the graph, update both `graph/import.cypher` and
`graph/import.cypher.sha256`, or set `RESBENCH_KG_IMPORT_SKIP_HASH=1`.

## Environment variables

| Variable | Default |
| --- | --- |
| `RESBENCH_NEO4J_VERSION` | `2026.09.0` |
| `RESBENCH_NEO4J_HOME` | `<root>/neo4j` |
| `RESBENCH_NEO4J_URL` | `https://dist.neo4j.org/neo4j-community-2026.09.0-unix.tar.gz` |
| `RESBENCH_NEO4J_ARCHIVE` | Empty; set to a local tarball path to skip the download (offline installs, or when the target host receives a 403 from CloudFront) |
| `RESBENCH_NEO4J_SHA256` | SHA256 of the tarball above |
| `RESBENCH_NEO4J_PASSWORD_FILE` | `<root>/neo4j/.password` |
| `RESBENCH_NEO4J_URI` | `bolt://127.0.0.1:7687` |
| `RESBENCH_KG_IMPORT_FILE` | `<root>/scripts/neo4j/graph/import.cypher` |
| `RESBENCH_SKIP_NEO4J` | `0`; set to `1` to skip Neo4j install/start in the launcher |
