# LinuxConfig - Ultimate Web Server Benchmark
- https://linuxconfig.org/ultimate-web-server-benchmark-apache-nginx-litespeed-openlitespeed-caddy-lighttpd-compared

I asked about the `-k` parameter for the `ab` usages to ensure I understand how HTTP Keep Alive was being used in the RPS measurements. 
- https://forum.linuxconfig.org/t/ultimate-web-server-benchmark-apache-nginx-litespeed-openlitespeed-caddy-lighttpd-compared/8265/6

## Test 1: Static File Handling

### Purpose of the test

> Static file handling is a fundamental task for any web server. This test measures how efficiently each server serves a simple HTML page under concurrent requests. A web server optimized for static content should deliver high requests per second (RPS) with minimal latency and resource usage. This test is crucial for scenarios where websites serve mostly cached, pre-generated pages, such as blogs, documentation sites, and content delivery networks (CDNs).


### Command
The test was conducted using Apache Benchmark (`ab`) with the following command:


```sh
ab -n 10000 -c 100 http://localhost:4242/
```

- -n 10000 → Total number of requests (10,000)
- -c 100 → Number of concurrent users (100)
- http://localhost:4242/ → URL of the static test page

Sometimes this will fail to start with the following error
```text
Benchmarking localhost (be patient)
apr_socket_recv: Connection reset by peer (54)
```

When it does run, it fails to complete. Needs investigation. It seems to finish with a lower number of concurrent tests. 

```text
This is ApacheBench, Version 2.3 <$Revision: 1913912 $>
Copyright 1996 Adam Twiss, Zeus Technology Ltd, http://www.zeustech.net/
Licensed to The Apache Software Foundation, http://www.apache.org/

Benchmarking localhost (be patient)
Completed 1000 requests
Completed 2000 requests
apr_socket_recv: Connection reset by peer (54)
Total of 2900 requests completed
```

It has completed with 50 concurrent requests.

```text
This is ApacheBench, Version 2.3 <$Revision: 1913912 $>
Copyright 1996 Adam Twiss, Zeus Technology Ltd, http://www.zeustech.net/
Licensed to The Apache Software Foundation, http://www.apache.org/

Benchmarking localhost (be patient)
Completed 1000 requests
Completed 2000 requests
Completed 3000 requests
Completed 4000 requests
Completed 5000 requests
Completed 6000 requests
Completed 7000 requests
Completed 8000 requests
Completed 9000 requests
Completed 10000 requests
Finished 10000 requests


Server Software:
Server Hostname:        localhost
Server Port:            4242

Document Path:          /
Document Length:        0 bytes

Concurrency Level:      50
Time taken for tests:   602.010 seconds
Complete requests:      10000
Failed requests:        0
Total transferred:      600000 bytes
HTML transferred:       0 bytes
Requests per second:    16.61 [#/sec] (mean)
Time per request:       3010.051 [ms] (mean)
Time per request:       60.201 [ms] (mean, across all concurrent requests)
Transfer rate:          0.97 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0    2   1.5      2      12
Processing:  3000 3008   3.6   3008    3021
Waiting:        0    2   1.5      2      11
Total:       3001 3010   3.5   3010    3023

Percentage of the requests served within a certain time (ms)
  50%   3010
  66%   3011
  75%   3012
  80%   3013
  90%   3015
  95%   3016
  98%   3018
  99%   3018
 100%   3023 (longest request)
```

Changed: Remove purge during keep alive.

```text
This is ApacheBench, Version 2.3 <$Revision: 1913912 $>
Copyright 1996 Adam Twiss, Zeus Technology Ltd, http://www.zeustech.net/
Licensed to The Apache Software Foundation, http://www.apache.org/

Benchmarking localhost (be patient)
Completed 1000 requests
Completed 2000 requests
Completed 3000 requests
Completed 4000 requests
Completed 5000 requests
Completed 6000 requests
Completed 7000 requests
Completed 8000 requests
Completed 9000 requests
Completed 10000 requests
Finished 10000 requests


Server Software:
Server Hostname:        localhost
Server Port:            4242

Document Path:          /
Document Length:        0 bytes

Concurrency Level:      50
Time taken for tests:   602.062 seconds
Complete requests:      10000
Failed requests:        0
Total transferred:      600000 bytes
HTML transferred:       0 bytes
Requests per second:    16.61 [#/sec] (mean)
Time per request:       3010.309 [ms] (mean)
Time per request:       60.206 [ms] (mean, across all concurrent requests)
Transfer rate:          0.97 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0    2   1.7      2      12
Processing:  3000 3008   3.9   3008    3030
Waiting:        0    2   2.0      2      25
Total:       3001 3010   3.9   3010    3037

Percentage of the requests served within a certain time (ms)
  50%   3010
  66%   3011
  75%   3012
  80%   3013
  90%   3014
  95%   3016
  98%   3021
  99%   3026
 100%   3037 (longest request)
```

Note that by default `ab` is not using `keep-alive`. Enabling this is drastic. 

Using 10 workers, with `10,000,000` requests, we can get `85,608` requests per second. 

```sh
ab -n 10000000 -c 10 -k http://localhost:8080/
```

```text

This is ApacheBench, Version 2.3 <$Revision: 1913912 $>
Copyright 1996 Adam Twiss, Zeus Technology Ltd, http://www.zeustech.net/
Licensed to The Apache Software Foundation, http://www.apache.org/

Benchmarking localhost (be patient)
Completed 1000000 requests
Completed 2000000 requests
Completed 3000000 requests
Completed 4000000 requests
Completed 5000000 requests
Completed 6000000 requests
Completed 7000000 requests
Completed 8000000 requests
Completed 9000000 requests
Completed 10000000 requests
Finished 10000000 requests


Server Software:
Server Hostname:        localhost
Server Port:            8080

Document Path:          /
Document Length:        5 bytes

Concurrency Level:      10
Time taken for tests:   116.811 seconds
Complete requests:      10000000
Failed requests:        0
Keep-Alive requests:    10000000
Total transferred:      740000000 bytes
HTML transferred:       50000000 bytes
Requests per second:    85608.64 [#/sec] (mean)
Time per request:       0.117 [ms] (mean)
Time per request:       0.012 [ms] (mean, across all concurrent requests)
Transfer rate:          6186.56 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0    0   0.0      0       0
Processing:     0    0   0.0      0      21
Waiting:        0    0   0.0      0      21
Total:          0    0   0.0      0      21

Percentage of the requests served within a certain time (ms)
  50%      0
  66%      0
  75%      0
  80%      0
  90%      0
  95%      0
  98%      0
  99%      0
 100%     21 (longest request)
```

## Test 2: Large File Transfers

### Purpose of the Test

> Serving large files efficiently is critical for websites that deliver downloads, streaming media, or large assets such as high-resolution images or software packages. This test evaluates how well each web server handles the transfer of a 10MB file under concurrent requests. A well-optimized server should maintain high transfer rates while keeping CPU and memory usage minimal.

### Command
The test was conducted using Apache Benchmark (`ab`) with the following command:

```sh
ab -n 500 -c 10 http://server-ip/testfile10M.bin
```

- -n 500 → Total number of requests (500)
- -c 10 → Number of concurrent users (10)
- http://server-ip/testfile10M.bin → URL of the 10MB test file
