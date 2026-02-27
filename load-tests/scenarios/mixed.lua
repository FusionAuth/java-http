-- mixed.lua
-- Real-world mix: rotates all 5 endpoints
-- wrk flags: -t12 -c100 -d30s

local counter = 0
local endpoints = { "/", "/no-read", "/hello", "/file?size=1024", "/load" }
local bodies = { nil, nil, nil, nil, "benchmark payload data for load testing" }

request = function()
  counter = counter + 1
  local idx = (counter % #endpoints) + 1
  local path = endpoints[idx]
  local body = bodies[idx]
  if body then
    return wrk.format("POST", path, { ["Content-Type"] = "application/octet-stream" }, body)
  else
    return wrk.format("GET", path)
  end
end

dofile(os.getenv("SCENARIO_DIR") .. "/json-report.lua")
