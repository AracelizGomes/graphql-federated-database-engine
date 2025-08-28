# GFDE — GraphQL Federated Database Engine

> Kernel-level federation for GraphQL with direct memory execution

- --

## 🚀 Overview

- *GFDE (GraphQL Federated Database Engine)** is an open source project that reimagines GraphQL federation as a **database-native concept**.

Instead of stitching services at the middleware layer (e.g., Apollo Federation), GFDE integrates federation directly into the **execution kernel** and introduces a **Direct Memory Execution Protocol (DMEP)** for fast, low-latency query traversal across distributed data sources.

GFDE’s mission is to eliminate the **N+1 problem**, reduce network overhead, and provide **scalable GraphQL federation** purpose-built for modern distributed systems.

- --

## Key Features 

- **Kernel-Level Federation** – GraphQL federation logic is embedded in the query engine itself.
- **Direct Memory Execution Protocol (DMEP)** – Sub-queries are joined in memory, bypassing network marshalling overhead.
- **Federated Query Planner** – Optimized decomposition of GraphQL queries across heterogeneous data sources (SQL, NoSQL, KV, Graph).
- **Unified Schema Registry** – Maintains cross-service schema consistency and runtime conflict resolution.
- **Performance Benchmarking Harness** – Compare GFDE against Apollo Federation and monolithic GraphQL.
- --

## GFDE Performance in Context

In simulated benchmarks (identical schema/data/hardware):

| Architecture | p95 @ 200 RPS | p95 @ 800 RPS | Peak QPS under 200ms p95 | Notes |

|--------------|---------------|---------------|--------------------------|-------|

| Apollo Federation | ~120ms | ~480ms | 400 QPS | Hop overhead & N+1 bottlenecks |

| Monolithic GraphQL | ~95ms | ~280ms | 700 QPS | Strong mid-load, bottlenecks at scale |

| **GFDE** | **70ms** | **160ms** | **1100 QPS** | ~3× Apollo throughput |

GFDE demonstrates that **federation doesn’t have to mean slow**.

- --

# **🧩 Architecture**

+--------------------------+

|   GraphQL Query Parser   |

+--------------------------+

|

v

+--------------------------+

|  Federated Query Planner |

+--------------------------+

|

v

+--------------------------+

|  Execution Engine (DMEP) |

+--------------------------+

|

v

+--------------------------+

|   Storage Abstraction    |

|  (SQL / KV / Doc / Graph)|

+--------------------------+

# **License**

GFDE is open-sourced under the [Apache 2.0 License](notion://www.notion.so/thelatinainvestor/LICENSE).

# **Acknowledgments**

This project draws inspiration from:

- Apollo Federation (middleware federation)
- DGraph, Neo4j (graph-native DBs)
- Presto/Trino (distributed query engines)

GFDE builds upon these ideas but pushes federation into the database kernel itself.

- --

- --