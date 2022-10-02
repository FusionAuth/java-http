## FusionAuth HTTP client and server

**NOTE:** This project is in progress.

The goal of this project is to build a full-featured HTTP server and client in plain Java without the use of any libraries. The client and server will use non-blocking NIO in order to provide the highest performance possible.

The general requirements and roadmap are as follows:

## Server tasks

* [x] Basic HTTP 1.1
* [x] Support Keep-Alive
* [x] Support Expect-Continue 100
* [x] Support chunked request
* [x] Support chunked response
* [x] Support streaming entity bodies (via chunking likely)
* [x] Support compression
* [x] Support cookies in request and response
* [x] Clean up HTTPRequest
* [x] Support form data
* [x] Support multipart form data
* [x] Support TLS
* [ ] Support trailers
* [ ] Support HTTP 2

## Client tasks

* [ ] Basic HTTP 1.1
* [ ] Support Keep-Alive
* [ ] Support TLS
* [ ] Support HTTP 2
* [ ] Support Expect-Continue 100
* [ ] Support chunked request and response
* [ ] Support streaming entity bodies
* [ ] Support form data
* [ ] Support multipart form data

## Examples Usages:

Creating a server is simple:

```java
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.HTTPHandler;

public class Example {
  public static void main(String... args) {
    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    HTTPServer server = new HTTPServer().withHandler(handler).withListener(new HTTPListenerConfiguration(4242));
    server.start();
    // Use server
    server.close();
  }
}
```

Since the `HTTPServer` class implements `java.io.Closeable`, you can also use a try-resource block like this:

```java
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.HTTPHandler;

public class Example {
  public static void main(String... args) {
    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    try (HTTPServer server = new HTTPServer().withHandler(handler).withListener(new HTTPListenerConfiguration(4242))) {
      server.start();
      // When this block exits, the server will be shutdown
    }
  }
}
```

You can also set various options on the server using the `with` methods on the class like this:

```java
import java.time.Duration;

import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.HTTPHandler;

public class Example {
  public static void main(String... args) {
    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    HTTPServer server = new HTTPServer().withHandler(handler)
                                        .withNumberOfWorkerThreads(42)
                                        .withShutdownDuration(Duration.ofSeconds(10L))
                                        .withListener(new HTTPListenerConfiguration(4242));
    server.start();
    // Use server
    server.close();
  }
}
```

## Performance

A key component for this project is to have awesome performance. Here are some basic metrics using the FusionAuth load test suite against a simple application using the Prime Framework MVC. The controller does nothing except return a simple 200. Here are some simple comparisons between `Tomcat`, `Netty`, and `java-http`.

The load test configuration is set to 10 clients with 500,000 requests each. The client is Restify which is a FusionAuth library that uses URLConnection under the hoods. All the servers were HTTP so that TLS would not introduce any additional latency. 

Here are the current test results:

| Server      | RPS    | Failures per second |
|-------------|--------|---------------------|
| `java-http` | 63,216 | 0                   |
| `Tomcat`    | 51,351 | 0.103               |
| `Netty`     | 540    | 1.818               |

Netty and Tomcat both seem to suffer from buffering and connection issues at very high scale. Regardless of the configuration, both servers always begins to fail with connection timeout problems at scale. `java-http` does not have these issues because of the way it handles connections via the selector. Connections don't back up and client connection pools can always be re-used with Keep-Alive.

## Helping out

We are looking for Java developers that are interested in helping us build the client and server. If you know a ton about networks and
protocols and love writing clean, high-performance Java, contact us at dev@fusionauth.io.

## Building with Savant

**Note:** This project uses the Savant build tool. To compile using Savant, follow these instructions:

```bash
$ mkdir ~/savant
$ cd ~/savant
$ wget http://savant.inversoft.org/org/savantbuild/savant-core/1.0.0/savant-1.0.0.tar.gz
$ tar xvfz savant-1.0.0.tar.gz
$ ln -s ./savant-1.0.0 current
$ export PATH=$PATH:~/savant/current/bin/
```
