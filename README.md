# graphql-federated-database-engine
## High-level Overview:

- **Form factor:** start as a **user-space engine** with kernel-bypass (io_uring/eBPF/DPDK).
    - Gradually evolve to a **unikernel** (Firecracker/KVM) once drivers & ops mature.
- **Abstractions:**
    - **GFDE Kernel** (scheduler, memory, log, indexes, replication),
    - **Subgraphs as modules/processes**,
    - **GFDE syscalls/ABI (Application Binary Interface)**
    - **on-wire GFDE Protocol** (planner IR, scans, deltas, snapshots).
- **Contract:**
    - GraphQL SDL = **typed memory contract**;
    - federation = **distributed address map**;
    - queries = **pointer walks** across typed regions;
    - mutations = **versioned writes**.

**`Execution Path (Query → Plan → Pointer Walk)`**

```java
Client
  │ GraphQL
  ▼
Router/Planner
  │  validate SDL/federation
  │  build GraphIR plan (subplans + joins + pushdowns)
  ▼
Kernel Executor
  │  for each step:
  │   - index scan / key fetch
  │   - arena pointer walk
  │   - field resolvers (in-process)
  ▼
Result Stitcher
  │  assemble typed result; apply policy filters
  ▼
Client Response
```

---

## **1) Node Anatomy (the “Kernel”)**

**Data-plane kernel services**

- **Typed Memory Manager:** arenas for entities; per-type regions; zero-copy slices; copy-on-write snapshots.
- **Index Service:** scalar/composite/graph/temporal indexes; pluggable B+Tree/LSM/bitmap; vector optional.
- **Transaction Engine:** MVCC baseline; CRDT lane for always-available fields; WAL/commit log.
- **Changefeed:** per-entity/version streams; backpressure; exactly/at-least-once knobs.
- **Replication:** Raft/Multi-Paxos for strong partitions; CRDT gossip for AP partitions.
- **Executor:** field resolvers as **in-kernel functions**; batch & fuse ops; adaptive prefetch.

**Control-plane kernel services**

- **Schema/Federation Registry:** @key/@provides/@requires resolution; capability map; rolling upgrades.
- **Planner:** GraphIR (internal IR), cost model (selectivity, fanout, latency), predicate pushdown.
- **Security:** capability-based authZ; per-field ABAC; OAuth/mTLS identities; audit log.
- **Telemetry:** per-field latency, plan traces, heatmaps, conflict rates, index pressure.

**`Kernel Big Picture (Data Plane vs Control Plane)`**

```java
                         ┌───────────────────────────────────────────┐
                         │                GFDE KERNEL                │
                         ├───────────────────┬───────────────────────┤
                         │   DATA PLANE      │     CONTROL PLANE     │
┌───────────┐            │                   │                       │
│  CLIENTS  │── GraphQL ─┤                   │                       │
└───────────┘  /GFDE RPC │                   │                       │
                         │                   │                       │
                         │  • Typed Memory   │  • Schema/Federation  │
                         │    Manager        │    Registry           │
                         │  • Index Service  │  • Planner (GraphIR)  │
                         │  • Txn Engine     │  • Security/Policies  │
                         │    (MVCC/CRDT)    │  • Telemetry/Tracing  │
                         │  • Commit Log &   │  • Node Membership &  │
                         │    Snapshots      │    Replication Control│
                         │  • Changefeeds    │  • Lifecycle (boot,   │
                         │  • Executor       │    upgrade, drain)    │
                         └───────────┬───────┴───────────────┬───────┘
                                     │                       │
                              ┌──────▼───────┐        ┌──────▼───────┐
                              │ SUBGRAPH MOD │        │ SUBGRAPH MOD │   … (N)
                              │ (WASM/JVM)   │        │ (WASM/JVM)   │
                              └──────┬───────┘        └──────┬───────┘
                                     │  GFDE Syscalls         │
                                     └──────────┬─────────────┘
                                                │
                                         ┌──────▼───────┐
                                         │ STORAGE API  │→ (RocksDB/LSM, B+Tree,
                                         │ + DRIVERS    │   LogDevice, S3, etc.)
                                         └──────────────┘
```

---

## **2) Process Model (Subgraphs as Modules)**

- **Subgraph Module = address space + capabilities.**
- Isolation options:
    - **WASM sandbox** (WASI): run resolvers close to data, language-agnostic.
    - **Isolated JVM** (GraalVM isolate) for Java/Spring code paths.
- **IPC:** lock-free ring buffers + shared memory; messages are GraphIR ops and EntityDelta events.
- **Lifecycle:** register schema → request memory/index capabilities → expose resolvers → hot reload with version gates.

---

## **3) GFDE Syscalls / ABI (Application Binary Interface — what subgraphs call)**

Minimal syscall surface (capability-scoped):

- `gf_alloc_region(typename, quota)`
- `gf_put(entity_id, version, bytes) / gf_get(entity_id[, version])`
- `gf_scan(index_id, predicate, limit, order)`
- `gf_begin_tx(mode) / gf_commit(tx_id) / gf_abort(tx_id)`
- `gf_subscribe(changefeed_spec)`
- `gf_snapshot(create|list|restore)`
- `gf_policy_eval(principal, entity_ref, field)`
- `gf_register_index(typename, fields[], kind)`
- `gf_register_crdt(field, type: GCounter|ORSet|LWW, clock: hybrid)`

Resolvers never malloc or hit the filesystem directly; they **ask the kernel** for typed memory and scans.

**`GFDE Syscalls / ABI Surface (for Subgraph Modules)`**

```java
+──────────────────────────────────────────────────────────+
|                      GFDE SYSCALLS                       |
+──────────────────────────────────────────────────────────+
| gf_alloc_region(typename, quota)                         |
| gf_get(typename, id[, version])                          |
| gf_put(typename, id, version, bytes)                     |
| gf_scan(index_id, predicate, limit, order)               |
| gf_begin_tx(mode) / gf_commit(tx) / gf_abort(tx)         |
| gf_register_index(typename, fields[], kind)              |
| gf_register_crdt(field, type, clock)                     |
| gf_subscribe(changefeed_spec)                            |
| gf_snapshot(create|list|restore)                         |
| gf_policy_eval(principal, entity_ref, field)             |
+──────────────────────────────────────────────────────────+
```

---

## **4) GFDE Wire Protocol (cluster & client/server)**

Layering:

- **Network:** QUIC (mTLS)
- **Transport:** GFDE-RPC (credit-based flow control, cancellation)
- **Encoding:** FlatBuffers/Cap’n Proto (zero-copy)
- **Messages:**
    - HELLO{caps, versions}, AUTH{proof}, SCHEMA_SYNC{hash}
    - PLAN{GraphIR}, EXEC_STEP{scan|keyFetch|join}, CANCEL
    - ENTITY_DELTA{type,id,prevVer,newVer,patch}
    - SNAPSHOT{chunk}, REPL_LOG{range}
    - HEALTH{qps, p99, cacheHit, lag}
- **Error model:** typed codes (PLAN_INVALID, CAP_MISSING, INDEX_STALE, TX_CONFLICT, CLOCK_SKEW).

**Compliance levels (for vendors/servers)**

- **L1 Query**: read-only, indexes, snapshot reads
- **L2 Transactional**: MVCC writes, serializable scopes, changefeeds
- **L3 AP-Replicated**: CRDT fields, offline writes, convergent merges

** `Wire Protocol (Cluster & Client)`**

```java
[QUIC + mTLS]
    └── GFDE-RPC (credit-based FC, cancel)
        ├─ HELLO{caps,version}
        ├─ AUTH{proof}
        ├─ SCHEMA_SYNC{hash,delta}
        ├─ PLAN{GraphIR}
        ├─ EXEC_STEP{scan|keyFetch|join|stream}
        ├─ ENTITY_DELTA{type,id,prevVer,newVer,patch}
        ├─ SNAPSHOT{start|chunk|end}
        ├─ REPL_LOG{range}
        └─ HEALTH{qps,p99,lag,cacheHit}
Errors: PLAN_INVALID | CAP_MISSING | INDEX_STALE | TX_CONFLICT | CLOCK_SKEW
```

**`Transaction & Consistency (MVCC / CRDT)`**

```java
                 ┌───────────────────────────────────────┐
                 │             TXN ENGINE                │
                 ├───────────────────────────────────────┤
                 │ MVCC lane:                            │
                 │  • begin(tx)                          │
                 │  • read(ver<=snapshot)                │
                 │  • write(CAS ev.ver → ev.ver+1)       │
                 │  • commit → WAL append                │
                 │                                       │
                 │ CRDT lane (per field via @crdt):      │
                 │  • accept concurrent updates          │
                 │  • merge with type law (LWW/ORSet/GC) │
                 │  • gossip/replicate                   │
                 └───────────────────────────────────────┘
```

---

## **5) Memory & Storage**

- **Typed Regions:** (typename, shard) → arena; per-core slabs to avoid contention.
- **Index layout:** log-structured (LSM) for write-heavy; B+Tree for read/seek; adjacency lists for graph edges; columnar sidecars for analytics prunes.
- **Durability:** append-only **Commit Log** + periodic **Snapshots**; checksummed & versioned; tiered storage (NVMe → S3).
- **Recovery:** parallel replay by type then by entity version; read-serve during catch-up with version fencing.

**`Typed Memory & Indexing (In-Kernel Layout)`**

```java
                ┌──────────────────────────────────────────┐
                │           TYPED MEMORY ARENAS            │
                ├──────────────────────────────────────────┤
                │  Type: User     → Arena U (slabs per CPU)│
                │  Type: Order    → Arena O                │
                │  Type: Product  → Arena P                │
                └───────┬──────────────────────────────────┘
                        │  EV = Entity Version
                        │  key = (typename,id) → {ver, bytes}
                        ▼
              ┌─────────────────────────────────┐
              │          INDEX SERVICE           │
              ├─────────────────────────────────┤
              │ Scalar:  User.email → B+Tree     │
              │ Composite: Order(status,created) │
              │ Graph: edges(User→Order)         │
              │ Temporal: EV.commitTime          │
              └─────────────────┬───────────────┘
                                │
                     ┌──────────▼──────────┐
                     │   COMMIT LOG (WAL)  │  append-only
                     ├──────────────────────┤
                     │  ENTITY_DELTA        │
                     │  SNAPSHOT_MARK       │
                     │  INDEX_MUTATION      │
                     └──────────┬───────────┘
                                │
                      ┌─────────▼──────────┐
                      │   SNAPSHOTS (S3)   │  periodic, checksummed
                      └────────────────────┘
```

---

## **6) Scheduling & Tail Latency**

- **Run-to-completion microtasks** (field/scan units) with per-core queues.
- **Admission control:** prioritize p95 tail reducers; shed/partial-defer via @defer/@stream.
- **Batching windows:** 50–200μs coalescing for hot keypath scans; dynamic under load.

**`Scheduling & Tail-Latency Controls`**

```java
Core-Local Queues  ──► Run-to-completion microtasks (field/scan)
   │                      ^
   │                      │ adaptive batching (50–200µs)
   └─ Admission Control ──┘
         • prioritize p95 tail reducers
         • shed work via @defer/@stream
         • isolate noisy tenants by keyspace
```

---

## **7) Security & Multi-tenancy**

- **Capabilities:** every module & remote peer has a signed capability set: types, fields, indexes, write scopes.
- **Tenancy:** keyspace prefixing; memory quotas; rate limits; per-tenant WALs.
- **Audit:** tamper-evident mutation logs; per-field access traces.

**`Security & Multi-tenancy (Capability Model)`**

```java
Principal/Module ──(signed capabilities)──► Kernel
    caps: { types, fields, indexes, writeScopes, quotas }

On access:
  • authenticate (mTLS/OIDC)
  • authorize via capability + policy engine (ABAC)
  • audit: append access/mutation records (tamper-evident)
Tenants:
  • keyspace prefixing
  • memory & IOPS quotas
  • per-tenant WALs and snapshots
```

---

## **8) Deployment Forms (pragmatic → purist)**

1. **User-space on Linux** (MVP)
    - Net: io_uring + busy-poll or DPDK (optional)
    - Storage: SPDK NVMe + RocksDB/LogDevice
    - Pros: fast to ship; easy ops.
2. **MicroVM / Unikernel** (V2)
    - Build with **Unikraft/IncludeOS**; run in **Firecracker/KVM**.
    - Kernel = GFDE only (drivers: virtio-net, virtio-blk).
    - Pros: tiny image, sealed attack surface, lower jitter.
3. **Verified microkernel base (seL4)** (research)
    - GFDE services in userland with capability security proven.
    - Pros: strongest isolation; Cons: complexity.

Kubernetes integration: run each GFDE node as a **DaemonSet** (user-space) or **Firecracker VM** via Kata/Weave Ignite; expose a **GFDE Service** and a **GFDE Sidecar** for app pods (mTLS + schema cache).

**Deployment Forms (Pragmatic → Purist)**

```java
[1] User-space on Linux
    - io_uring/DPDK, RocksDB, S3 snapshots
    - easiest to operate

[2] MicroVM / Unikernel
    - Firecracker/KVM + Unikraft
    - minimal drivers (virtio-net/blk)
    - sealed images, low jitter

[3] Verified Microkernel (research)
    - seL4 capabilities
    - strongest isolation, higher complexity
```

**`Boot & Recovery Lifecycle`**

```java
[BOOT]
  → Load SDL & federation map
  → Mount Commit Log
  → Rebuild/Load Indexes
  → Open Arenas (typed memory)
  → Start Replication & Changefeeds
  → Advertise Health/Costs to Router
  → Accept PLAN/EXEC

[RECOVERY]
  → Load latest Snapshot
  → Replay WAL to head
  → Fence reads by EV
  → Resume Serving (degraded if needed)
```

---

## **9) Standards & Protocol Governance**

- **SDL as DDL:** indexes, policies, CRDTs, TTLs via directives are **normative**.
- **TCK (compat suite):** golden plan snapshots, YCSB-GFDE workloads, failure injections.
- **Versioning:** semantic for protocol (gfde/1.x); data-plane (ev/2.x).
- **Migration:** rolling schema upgrades with dual-plan execution and write fencing.

---

## **10) What “OS” means here (practical stance)**

- You **do not** need a general-purpose OS first. Start as a **database-as-OS** (it owns its memory, scheduler, IO queues) **on top of Linux**.
- When the protocol + drivers stabilize, **collapse** into a **unikernel** so the “server adheres to the protocol” the way “SQL Servers adhere to SQL”—i.e., **GFDE-compliant nodes** implement the wire protocol + ABI and pass the TCK.

---

## **11) Example: Boot & Join**

1. **Boot:** load SDL+index plan → mount commit log → map typed regions → warm hot indexes.
2. **HELLO/AUTH:** exchange caps; fetch schema hash; reconcile.
3. **REPL_CATCHUP:** apply WAL since snapshot; verify EV fences.
4. **ADVERTISE:** planner costs, index stats, hot keypaths.
5. **SERVE:** accept PLAN/EXEC_STEP, emit changefeeds.

---

## **12) Incremental Build Plan**

**Phase A**

- User-space kernel: MVCC, commit log, scalar indexes, changefeed.
- Planner: static federation, batched key fetch, predicate pushdown.
- Protocol: QUIC+mTLS; PLAN/EXEC/DELTA/SNAPSHOT.
- TCK v0: plan snapshots, correctness suites.

**Phase B**

- Composite/temporal indexes, cost-based planner, tail-latency admission.
- CRDT lane (@crdt types), geo-replication.
- WASM sandbox for resolvers; policy engine; plan diffing.

**Phase C**

- Unikernel image; virtio drivers; sealed builds; conformance L2/L3.

---

## **13) Developer Experience**

- **SDL-first**: schema + directives are the **DDL**.
- **gfdectl** CLI: deploy schemas, create indexes, run plans, view traces, take snapshots.
- **Tracing UI**: plan tree with per-edge timing; heatmaps; cache keys.
- **Migration tooling**: dual-write fences; snapshot cutovers; index backfills with progress.

---

## **14) Risks & Mitigations**

- **Driver complexity (unikernel):** defer until V2; rely on virtio minimal set.
- **Consistency surprises:** default to **MONOTONIC** reads; allow @consistency per field/mutation.
- **Ecosystem lock-in:** keep **protocol open** + TCK; publish reference node.

---

