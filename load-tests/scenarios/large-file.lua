-- large-file.lua
-- 1MB response throughput: GET /file?size=1048576
-- wrk flags: -t4 -c10 -d30s

wrk.method = "GET"

dofile(os.getenv("SCENARIO_DIR") .. "/json-report.lua")
