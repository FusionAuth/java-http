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
ab -n 100000 -c 100 http://localhost:8080/
```

- -n 100000 → Total number of requests (100,000)
- -c 50 → Number of concurrent users (50)
- http://localhost:8080/ → URL of the static test page


### Results

Test run on:
- MacBook Pro 16-inch 2021, Apple M1 Max, 64 GB

| Server        | Requests per second (RPS) | Latency (ms) | Normalized Performance (%) |
|---------------|---------------------------|--------------|----------------------------|
| java-http     | 20,265                    | 2.681        | -                          | 
| apache tomcat | 19,142                    | 5.224        | -                          | 

Note that increasing the worker count to 100 produces very similar RPS with a higher latency.

Note that I believe the results found at the above link were generated w/out using the `-k` parameter of `ab` which would enable HTTP 1.0 Keep-Alive. So this test is actually testing true discrete users where each socket handles a single HTTP request from a client.

For fun, here is the same test using the `-k` parameter. In practice this may be the better test because most web services sit behind an HTTP proxy that will almost certainly use persistent connections in a pool to the HTTP server even if the connection to the client is not using Keep Alive. 


| Server        | Requests per second (RPS) | Latency (ms) | Normalized Performance (%) |
|---------------|---------------------------|--------------|----------------------------|
| java-http     | 82,443                    | 0.606        | -                          | 
| apache tomcat | 75,516                    | 1.324        | -                          | 

## Test 2: Large File Transfers

### Purpose of the Test

> Serving large files efficiently is critical for websites that deliver downloads, streaming media, or large assets such as high-resolution images or software packages. This test evaluates how well each web server handles the transfer of a 10MB file under concurrent requests. A well-optimized server should maintain high transfer rates while keeping CPU and memory usage minimal.

### Command
The test was conducted using Apache Benchmark (`ab`) with the following command:

```sh
ab -n 500 -c 10 http://localhost:8080/file?size=10485760
```

- -n 500 → Total number of requests (500)
- -c 10 → Number of concurrent users (10)
- http://localhost:8080/file?size=10485760 → URL of the 10MB test file

### Results

Test run on:
- MacBook Pro 16-inch 2021, Apple M1 Max, 64 GB

| Server        | Requests per second (RPS) | Latency (ms) | Transfer Rate (MB/sec) | Normalized Performance (%) |
|---------------|---------------------------|--------------|------------------------|----------------------------|
| java-http     | 625                       | 15.998       | 6,251                  |                            | 
| apache tomcat | 503                       | 19.857       | 5,036                  |                            |  

Note to calculate the Transfer Rate in MB/sec, I am taking the `ab` result of `Kbytes/sec` and dividing by `1024`.

Same test with `-k` which assumes we are using an HTTP proxy with Keep Alive.


| Server        | Requests per second (RPS) | Latency (ms) | Transfer Rate (MB/sec) | Normalized Performance (%) |
|---------------|---------------------------|--------------|------------------------|----------------------------|
| java-http     | 682                       | 14.647       | 6,632                  |                            | 
| apache tomcat | 229                       | 43.518       | 2,297                  |                            | 

It is unexpected that Apache Tomcat would perform worse with `-k` enabled. It is possible that Tomcat is not handling HTTP/1.0 keep alive properly which could cause the socket to timeout reducing throughput. This is only a guess.

## Test 2: High Concurrency Performance

### Purpose of the Test

> Web servers must efficiently handle high traffic volumes, especially during peak loads. This test measures how well each server performs when faced with 1,000 simultaneous users making requests to a simple HTML page. A well-optimized server should maintain a high request rate with minimal latency and avoid excessive CPU and memory consumption. This test is crucial for sites experiencing traffic spikes, such as e-commerce platforms, news websites, and online services.

### Command
The test was conducted using Apache Benchmark (`ab`) with the following command:

```sh
ab -n 20000 -c 1000 http://localhost:8080/
```

- -n 20000 → Total number of requests (20,000)
- -c 1000 → Number of concurrent users (1,000)
- http://localhost:8080/ → URL of the test page

Note, I have been unable to run this command on macOS. I receive an error `socket: Too many open files (24)`. The default on macOS is `256` (`ulimit -n -S`). If I increase the local limit using `ulimit -n -S 16384`, I get past this error and receive another error. `apr_socket_recv: Connection reset by peer (54)`. My guess is the server cannot accept this many sockets connections all at once and the Server Socket is reaching the configured backlog and puking. 
