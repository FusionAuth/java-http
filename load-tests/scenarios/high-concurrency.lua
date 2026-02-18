-- high-concurrency.lua
-- Connection pressure: GET /
-- wrk flags: -t12 -c1000 -d30s

wrk.method = "GET"

dofile(os.getenv("SCENARIO_DIR") .. "/json-report.lua")
