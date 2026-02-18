-- post-load.lua
-- POST with body: POST /load
-- wrk flags: -t12 -c100 -d30s

wrk.method = "POST"
wrk.body = "This is a small body for a load test request."
wrk.headers["Content-Type"] = "text/plain"

dofile(os.getenv("SCENARIO_DIR") .. "/json-report.lua")
