## Java HTTP client and server ![semver 2.0.0 compliant](http://img.shields.io/badge/semver-2.0.0-brightgreen.svg?style=flat-square) [![test](https://github.com/FusionAuth/java-http/actions/workflows/test.yml/badge.svg)](https://github.com/FusionAuth/java-http/actions/workflows/test.yml)

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

A key purpose for this project is obtain screaming performance. Here are some basic metrics using the FusionAuth load test suite against a boilerplate request handler. The request handler simply returns a `200`. Here are some simple comparisons between `apache-tomcat`, `Netty`, and `java-http`.

The load test configuration is set to `100` clients with `100,000` requests each per worker. This means the entire test will execute `10,000,000` requests. The HTTP client is [Restify](https://github.com/inversoft/restify) which is a FusionAuth library that uses `HttpURLConnection` under the hoods. This REST client is used because it is considerably faster than the native Java REST client. In a real life example, depending upon your application, this performance may not matter. For the purposes of a load test, we have attempted to remove as many limitations to pushing the server as hard as we can. 

All the servers were HTTP so that TLS would not introduce any additional latency.

Here are the current test results: (in progress...)

| Server         | Avg requests per second   | Failures per second   | Avg latency in ms       | Normalized Performance (%) |
|----------------|---------------------------|-----------------------|-------------------------|----------------------------|
| java-http      | 101,317                   | 0                     | 0.350                   | 100%                       |
| Apache Tomcat  | 83,463                    | 0                     | 0.702                   | 82.3%                      | 
| Netty          | ?                         | ?                     | ?                       |                            |
| OkHttp         | ?                         | ?                     | ?                       |                            |
| JDK HttpServer | ?                         | ?                     | ?                       |                            |

Note the JDK HTTP Server is `com.sun.net.httpserver.HttpServer`. I don't know that anyone would use this in production, the JDK has not yet made a version of this using a public API. It is included here for reference only. 

Load test last performed May 30, 2025. Using the [fusionauth-load-test](https://github.com/fusionauth/fusionauth-load-tests) library.

### Running load tests

Start the HTTP server to test.

#### java-http

Start the HTTP server. Run the following commands from the `java-http` repo.

```bash
cd load-tests/self
sb clean start
```

#### Apache Tomcat

Start the HTTP server. Run the following commands from the `java-http` repo.

```bash
cd load-tests/tomcat
sb clean start
```

Once you have the server started you wish to test, start the load test. Run the following commands from the `fusionauth-load-tests` repo.

```bash
sb clean int
./load-test.sh HTTP.json
```

Netty and Tomcat both seem to suffer from buffering and connection issues at very high scale. Regardless of the configuration, both servers always begins to fail with connection timeout problems at scale. `java-http` does not have these issues because of the way it handles connections via the selector. Connections don't back up and client connection pools can always be re-used with Keep-Alive.

The general requirements and roadmap are as follows:

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
