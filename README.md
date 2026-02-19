## Java HTTP client and server ![semver 2.0.0 compliant](http://img.shields.io/badge/semver-2.0.0-brightgreen.svg?style=flat-square) [![test](https://github.com/FusionAuth/java-http/actions/workflows/test.yml/badge.svg?branch=main)](https://github.com/FusionAuth/java-http/actions/workflows/test.yml)

### Latest versions

* Latest stable version: `1.4.0`
   * Now with 100% more virtual threads!
* Prior stable version `0.3.7` 


The goal of this project is to build a full-featured HTTP server and client in plain Java without the use of any libraries. The client and server will use Project Loom virtual threads and blocking I/O so that the Java VM will handle all the context switching between virtual threads as they block on I/O.

For more information about Project Loom and virtual threads, please review the following link.
* https://blogs.oracle.com/javamagazine/post/java-virtual-threads

## Project Goals

- Very fast
- Easy to make a simple web server like you can in Node.js
- No dependencies
- To not boil the ocean. This is a purpose built HTTP server that probably won't do everything.

## Installation

To add this library to your project, you can include this dependency in your Maven POM:

```xml
<dependency>
  <groupId>io.fusionauth</groupId>
  <artifactId>java-http</artifactId>
  <version>1.4.0</version>
</dependency>
```

If you are using Gradle, you can add this to your build file:

```groovy
implementation 'io.fusionauth:java-http:1.4.0'
```

If you are using Savant, you can add this to your build file:

```groovy
dependency(id: "io.fusionauth:java-http:1.4.0")
```

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

    HTTPServer server = new HTTPServer().withHandler(handler)
                                        .withListener(new HTTPListenerConfiguration(4242));
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

    try (HTTPServer server = new HTTPServer().withHandler(handler)
                                             .withListener(new HTTPListenerConfiguration(4242))) {
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
                                        .withShutdownDuration(Duration.ofSeconds(10L))
                                        .withListener(new HTTPListenerConfiguration(4242));
    server.start();
    // Use server
    server.close();
  }
}
```

### TLS

The HTTP server implements TLS `1.0-1.3` using the Java SSLEngine. To enable TLS for your server, you need to create an `HTTPListenerConfiguration` that includes a certificate and private key. Most production use-cases will use a proxy such as Apache, Nginx, ALBs, etc. In development, it is recommended that you set up self-signed certificates and load those into the HTTP server.

To set up self-signed certificates on macOS, you can use the program `mkcert` with the following example. 

```shell
brew install mkcert
mkcert -install
mkdir -p ~/dev/certificates
mkcert -cert-file ~/dev/certificates/example.org.pem -key-file ~/dev/certificates/example.org.key example.org
```

Note, if you are using Linux, once you install `mkcert` the instructions should be the same.

In production environments, your certificate will likely be signed by one or more intermediate Certificate Authorities. In addition to the server certificate, ensure that all intermediate CA certificates in the chain are included in your pem file.

Now you can load these into the HTTP server like this:

```java
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;

public class Example {
  private static String certificate;

  private static String privateKey;

  public static void main(String[] args) throws Exception {
    String homeDir = System.getProperty("user.home");
    certificate = Files.readString(Paths.get(homeDir + "/dev/certificates/example.org.pem"));
    privateKey = Files.readString(Paths.get(homeDir + "/dev/certificates/example.org.key"));

    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    HTTPServer server = new HTTPServer().withHandler(handler)
                                        .withListener(new HTTPListenerConfiguration(4242, certificate, privateKey));
    // Use server
    server.close();
  }
}
```

And finally, you'll need to add the domain name to your hosts file to ensure that the SNI lookup handles the certificate correctly. For this example, you would use this entry in the `/etc/hosts` file:

```text
127.0.0.1 example.org
```

Then you can open `https://example.org` in a browser or call it using an HTTP client (i.e. Insomnia, Postman, etc or in code).

## Performance

A key purpose for this project is to obtain screaming performance. Here are benchmark results comparing `java-http` against other Java HTTP servers.

All servers implement the same request handler that reads the request body and returns a `200`. All servers were tested over HTTP (no TLS) to isolate server performance.

| Server | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs java-http |
|--------|-------------:|-------------:|------------------:|------------------:|-------------:|
| java-http      |      112,838 |            0 |              0.85 |              1.37 |       100.0% |
| Jetty          |      100,210 |            0 |              1.51 |             18.18 |        88.8% |
| Netty          |       92,460 |            0 |              1.93 |             30.72 |        81.9% |
| JDK HttpServer |       77,726 |            0 |              1.23 |              2.55 |        68.8% |
| Apache Tomcat  |       77,516 |            0 |              1.87 |             19.85 |        68.6% |

#### Under stress (1,000 concurrent connections)

| Server | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs java-http |
|--------|-------------:|-------------:|------------------:|------------------:|-------------:|
| java-http      |      110,918 |            0 |              8.87 |             10.71 |       100.0% |
| Jetty          |       98,499 |            0 |             10.02 |             12.29 |        88.8% |
| Netty          |       91,186 |            0 |             10.74 |             11.85 |        82.2% |
| Apache Tomcat  |       86,110 |            0 |             11.37 |             23.70 |        77.6% |
| JDK HttpServer |       50,345 |      16943.4 |              6.12 |             10.40 |        45.3% |

_Benchmark performed 2026-02-19 on Darwin, arm64, 10 cores, Apple M4, 24GB RAM._
_Java: openjdk version "21.0.10" 2026-01-20._

To reproduce:
```bash
cd load-tests
./run-benchmarks.sh --tool both --scenarios hello,high-concurrency
./update-readme.sh
```

See [load-tests/README.md](load-tests/README.md) for full usage and options.

## Todos and Roadmap

### Server tasks

* [x] Basic HTTP 1.1
* [x] Support Accept-Encoding (gzip, deflate), by default and per response options.
* [x] Support Content-Encoding (gzip, deflate)
* [x] Support Keep-Alive
* [x] Support Expect-Continue 100
* [x] Support Transfer-Encoding: chunked on request for streaming.
* [x] Support Transfer-Encoding: chunked on response
* [x] Support cookies in request and response
* [x] Support form data
* [x] Support multipart form data
* [x] Support TLS
* [ ] Support trailers
* [ ] Support HTTP 2

### Client tasks

* [ ] Basic HTTP 1.1
* [ ] Support Keep-Alive
* [ ] Support TLS
* [ ] Support Expect-Continue 100
* [ ] Support chunked request and response
* [ ] Support streaming entity bodies
* [ ] Support form data
* [ ] Support multipart form data
* [ ] Support HTTP 2

## FAQ

### Why virtual threads and not NIO?

Let's face it, NIO is insanely complex to write and maintain. The first 3 versions of `java-http` used NIO with non-blocking selectors, and we encountered numerous bugs, performance issues, etc. If you compare the `0.3-maintenance` branch with `main` of this project, you'll quickly see that switching to virtual threads and standard blocking I/O made our code **MUCH** simpler.

## Helping out

We are looking for Java developers that are interested in helping us build the client and server. If you know a ton about networks and protocols and love writing clean, high-performance Java, contact us at `dev@fusionauth.io`.

## Building with Savant

**Note:** This project uses the Savant build tool. To compile using Savant, follow these instructions:

```bash
$ mkdir ~/savant
$ cd ~/savant
$ wget http://savant.inversoft.org/org/savantbuild/savant-core/2.0.2/savant-2.0.2.tar.gz
$ tar xvfz savant-2.0.2.tar.gz
$ ln -s ./savant-2.0.2 current
$ export PATH=$PATH:~/savant/current/bin/
```
