-- baseline.lua
-- No-op throughput baseline: GET /
-- wrk flags: -t12 -c100 -d30s

wrk.method = "GET"

dofile(os.getenv("SCENARIO_DIR") .. "/json-report.lua")
