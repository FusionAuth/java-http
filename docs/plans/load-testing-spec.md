# Load Testing and Benchmarking Framework Spec

Originally extracted from the HTTP/2 design spec (`degroff/http2` branch, `docs/plans/2026-02-13-http2-design.md`, Section 15)
and the implementation plan (`docs/plans/2026-02-13-http2-implementation.md`, Phases 6 and 8).

Implemented on the `degroff/load_tests` branch.

---

## Goal

A self-contained, reproducible benchmark suite that:
1. Tests java-http against multiple competing Java HTTP servers with identical workloads
2. Produces structured, machine-readable JSON results with system metadata
3. Auto-generates README performance table from the latest results
4. Can be run locally via a single script

## Benchmark Tools

### wrk (primary)

C-based HTTP benchmark tool using kqueue (macOS) / epoll (Linux). Very fast — not the bottleneck. Provides latency percentiles (p50, p90, p99) via Lua `done()` callback that outputs JSON. Scenarios are defined as Lua files in `load-tests/scenarios/`.

Install: `brew install wrk` (macOS), `apt install wrk` (Linux).

### fusionauth-load-tests (secondary comparison)

Java-based load generator using virtual threads and the JDK HttpClient (`~/dev/fusionauth/fusionauth-load-tests`). Provides a "real Java client" perspective. Achieves lower absolute RPS (~50-60K vs ~110K for wrk) due to Java client overhead. Sends a small request body with each GET request.

Useful for cross-validating relative server rankings. The `--tool both` flag runs both tools against each server.

### Decisions

- **Dropped Apache Bench**: Single-threaded, bottlenecks at ~30-50K req/s.
- **Dropped h2load**: Originally planned for HTTP/2 benchmarks. Will reconsider when HTTP/2 lands.
- **Dropped GitHub Actions workflow**: GHA shared runners (2 vCPU, 7GB RAM) produce poor and noisy performance numbers. Not useful for benchmarking. Benchmarks should be run on dedicated hardware.

## Vendor Servers

All servers implement the same 5 endpoints on port 8080:

| Server | Directory | Implementation | Status |
|---|---|---|---|
| java-http | `load-tests/self/` | LoadHandler (5 endpoints) | Done |
| JDK HttpServer | `load-tests/jdk-httpserver/` | `com.sun.net.httpserver.HttpServer` | Done |
| Jetty | `load-tests/jetty/` | Jetty 12.0.x embedded | Done |
| Netty | `load-tests/netty/` | Netty 4.1.x with HTTP codec | Done |
| Apache Tomcat | `load-tests/tomcat/` | Tomcat 8.5.x embedded | Done (kept at 8.5.x; upgrade to 10.x deferred to HTTP/2 work) |

Endpoints:
- `GET /` — No-op (reads body, returns empty 200)
- `GET /no-read` — No-op (does not read body, returns empty 200)
- `GET /hello` — Returns "Hello world"
- `GET /file?size=N` — Returns N bytes of generated content (default 1MB)
- `POST /load` — Base64-encodes request body and returns it

Each server follows the same pattern:
- `build.savant` — Savant build config with proper dependency resolution (including `maven()` fetch for transitive deps)
- `src/main/java/io/fusionauth/http/load/` — Server implementation
- `src/main/script/start.sh` — Startup script

## Benchmark Scenarios

| Scenario | Method | Endpoint | wrk Threads | Connections | Purpose |
|---|---|---|---|---|---|
| `baseline` | GET | `/` | 12 | 100 | No-op throughput ceiling |
| `hello` | GET | `/hello` | 12 | 100 | Small response body |
| `post-load` | POST | `/load` | 12 | 100 | POST with body, Base64 response |
| `large-file` | GET | `/file?size=1048576` | 4 | 10 | 1MB response throughput |
| `high-concurrency` | GET | `/` | 12 | 1000 | Connection pressure |
| `mixed` | Mixed | Rotates all endpoints | 12 | 100 | Real-world mix (wrk only) |

Note: The `mixed` scenario is skipped for fusionauth-load-tests since it only supports a single URL per configuration.

## Scripts

### run-benchmarks.sh

Main orchestrator. Builds each server via Savant, starts it, runs benchmarks, stops it, aggregates JSON results.

```
./run-benchmarks.sh [OPTIONS]

Options:
  --servers <list>     Comma-separated server list (default: all)
  --scenarios <list>   Comma-separated scenario list (default: all)
  --tool <name>        Benchmark tool: wrk, fusionauth, or both (default: wrk)
  --label <name>       Label for the results file
  --output <dir>       Output directory (default: load-tests/results/)
  --duration <time>    Duration per scenario (default: 30s)
```

### update-readme.sh

Reads the latest JSON from `load-tests/results/`, generates a markdown performance table, and replaces the `## Performance` section in the project root `README.md`.

### compare-results.sh

Compares two result JSON files side-by-side with normalized ratios. Useful for detecting regressions or improvements between runs.

## Structured Output Format

Results are saved as JSON with ISO timestamps: `results/YYYY-MM-DDTHH-MM-SSZ.json`

Results are `.gitignore`d — they are machine-specific and not committed to the repo.

```json
{
  "version": 1,
  "timestamp": "2026-02-18T16:35:25Z",
  "system": {
    "os": "Darwin",
    "arch": "arm64",
    "cpuModel": "Apple M4",
    "cpuCores": 10,
    "ramGB": 24,
    "javaVersion": "openjdk version \"21.0.10\" 2026-01-20",
    "description": "Local benchmark"
  },
  "tools": {
    "selected": "wrk",
    "wrkVersion": "wrk 4.2.0 [kqueue] ..."
  },
  "results": [
    {
      "server": "self",
      "tool": "wrk",
      "protocol": "http/1.1",
      "scenario": "baseline",
      "config": {
        "threads": 12,
        "connections": 100,
        "duration": "30s",
        "endpoint": "/"
      },
      "metrics": {
        "requests": 1117484,
        "duration_us": 10100310,
        "rps": 110638.58,
        "avg_latency_us": 885.05,
        "p50_us": 833,
        "p90_us": 979,
        "p99_us": 2331,
        "max_us": 89174,
        "errors_connect": 0,
        "errors_read": 0,
        "errors_write": 0,
        "errors_timeout": 0
      }
    }
  ]
}
```

## Directory Structure

```
load-tests/
  .gitignore                       # Ignores *.iml files
  README.md                        # Usage documentation
  run-benchmarks.sh                # Main orchestrator script
  update-readme.sh                 # Parses results, updates project README
  compare-results.sh               # Compares two result files
  results/                         # JSON results (gitignored)
  scenarios/                       # wrk Lua scenario files
    baseline.lua
    hello.lua
    post-load.lua
    large-file.lua
    high-concurrency.lua
    mixed.lua
    json-report.lua                # Shared done() function for JSON output
  self/                            # java-http
  jdk-httpserver/                  # JDK built-in HttpServer
  jetty/                           # Eclipse Jetty 12.0.x
  netty/                           # Netty 4.1.x
  tomcat/                          # Apache Tomcat 8.5.x
```

## Performance Optimization Investigation

Once we have several benchmark runs collected, investigate optimizations to get java-http consistently #1 in RPS across all scenarios.

Areas to investigate:
- Profile under load with `async-profiler` or JDK Flight Recorder (lock contention, allocation pressure, syscall overhead)
- Compare request processing paths against Netty and Jetty
- Thread scheduling and virtual thread usage — blocking where we could be non-blocking?
- Socket/channel configuration (TCP_NODELAY, SO_REUSEPORT, buffer sizes)
- Read/write loop for unnecessary copies or allocations per request
- Selector strategy and worker thread pool sizing for high-connection-count scenarios

## Future Work

- **HTTP/2 benchmarks**: Add h2load scenarios when HTTP/2 lands on the `degroff/http2` branch. Upgrade Tomcat to 10.x for HTTP/2 support.
- **Performance optimization**: Profile and optimize java-http based on benchmark data.
