-- json-report.lua
-- Shared done() function that outputs JSON-formatted benchmark results.
-- Include this from scenario files via: dofile(os.getenv("SCENARIO_DIR") .. "/json-report.lua")

done = function(summary, latency, requests)
  io.write(string.format(
    '{"requests":%d,"duration_us":%d,"rps":%.2f,'
    .. '"avg_latency_us":%.2f,"p50_us":%d,"p90_us":%d,"p99_us":%d,'
    .. '"max_us":%d,"errors_connect":%d,"errors_read":%d,'
    .. '"errors_write":%d,"errors_timeout":%d}\n',
    summary.requests, summary.duration,
    summary.requests / (summary.duration / 1e6),
    latency.mean, latency:percentile(50), latency:percentile(90),
    latency:percentile(99), latency.max,
    summary.errors.connect, summary.errors.read,
    summary.errors.write, summary.errors.timeout))
end
