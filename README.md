## FusionAuth HTTP client and server ![semver 2.0.0 compliant](http://img.shields.io/badge/semver-2.0.0-brightgreen.svg?style=flat-square) [![test](https://github.com/FusionAuth/java-http/actions/workflows/test.yml/badge.svg)](https://github.com/FusionAuth/java-http/actions/workflows/test.yml) [![Project Map](https://sourcespy.com/shield.svg)](https://sourcespy.com/github/fusionauthjavahttp/)

**NOTE:** This project is in progress.

The goal of this project is to build a full-featured HTTP server and client in plain Java without the use of any libraries. The client and server will use non-blocking NIO in order to provide the highest performance possible.

## Installation

To add this library to your project, you can include this dependency in your Maven POM:

```xml
<dependency>
  <groupId>io.fusionauth</groupId>
  <artifactId>java-http</artifactId>
  <version>0.2.10</version>
</dependency>
```

If you are using Gradle, you can add this to your build file:

```groovy
implementation 'io.fusionauth:java-http:0.2.10'
```

If you are using Savant, you can add this to your build file:

```groovy
dependency(id: "io.fusionauth:java-http:0.2.10")
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

### TLS

The HTTP server implements TLS 1.0-1.3 using the Java SSLEngine. To enable TLS for your server, you need to create an `HTTPListenerConfiguration` that includes a certificate and private key. Most production use-cases will use a proxy such as Apache, Nginx, ALBs, etc. In development, it is recommended that you set up self-signed certificates and load those into the HTTP server.

To set up self-signed certificates on macOS, you can use the program `mkcert`. Here is an example:

```shell
brew install mkcert
mkcert -install
mkdir -p ~/dev/certificates
mkcert -cert-file ~/dev/certificates/example.org.pem -key-file ~/dev/certificates/example.org.key example.org
```

In production environments, your certificate will likely be signed by one or more intermediate Certificate Authorities. In addition to the server certificate, ensure that all intermediate CA certificates in the chain are included in your pem file.

Now you can load these into the HTTP server like this:

```java
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;

public class Example {
  private String certificate;

  private String privateKey;

  public static void main(String[] args) {
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

A key component for this project is to have awesome performance. Here are some basic metrics using the FusionAuth load test suite against a simple application using the Prime Framework MVC. The controller does nothing except return a simple 200. Here are some simple comparisons between `Tomcat`, `Netty`, and `java-http`.

The load test configuration is set to 10 clients with 500,000 requests each. The client is Restify which is a FusionAuth library that uses `HttpURLConnection` under the hoods. All the servers were HTTP so that TLS would not introduce any additional latency.

Here are the current test results:

| Server      | RPS    | Failures per second |
|-------------|--------|---------------------|
| `java-http` | 63,216 | 0                   |
| `Tomcat`    | 51,351 | 0.103               |
| `Netty`     | 540    | 1.818               |

Netty and Tomcat both seem to suffer from buffering and connection issues at very high scale. Regardless of the configuration, both servers always begins to fail with connection timeout problems at scale. `java-http` does not have these issues because of the way it handles connections via the selector. Connections don't back up and client connection pools can always be re-used with Keep-Alive.

The general requirements and roadmap are as follows:

## Todos and Roadmap

### Server tasks

* [x] Basic HTTP 1.1
* [x] Support Keep-Alive
* [x] Support Expect-Continue 100
* [x] Support chunked request
* [x] Support chunked response
* [x] Support streaming entity bodies (via chunking likely)
* [x] Support compression (default and per response options)
* [x] Support cookies in request and response
* [x] Clean up HTTPRequest
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

### Why no Loom?

Project Loom is an exciting development which brings a lot of great new features to Java, such as fibers, continuations and more.

Loom is currently available in Java 19 as a preview feature. Therefore, you can't use it without compiled code that is difficult to use in future Java releases.

This project is anchored to the Java LTS releases to ensure compatibility. Loom will be evaluated once it is out of preview, and available in an LTS version of Java.  

The next scheduled LTS release will be Java 21 set to release in September 2023. We are looking forward to that release and to see if we can leverage the Loom features in this project. 

## Helping out

We are looking for Java developers that are interested in helping us build the client and server. If you know a ton about networks and protocols and love writing clean, high-performance Java, contact us at dev@fusionauth.io.

## Building with Savant

**Note:** This project uses the Savant build tool. To compile using Savant, follow these instructions:

```bash
$ mkdir ~/savant
$ cd ~/savant
$ wget http://savant.inversoft.org/org/savantbuild/savant-core/2.0.0-RC.6/savant-2.0.0-RC.6.tar.gz
$ tar xvfz savant-2.0.0-RC.6.tar.gz
$ ln -s ./savant-2.0.0-RC.6 current
$ export PATH=$PATH:~/savant/current/bin/
```
