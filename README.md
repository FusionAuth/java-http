## FusionAuth HTTP client and server

**NOTE:** This project is in progress.

The goal of this project is to build a full-featured HTTP server and client in plain Java without the use of any libraries. The general
requirements and roadmap are as follows:

### Server tasks

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
* [ ] Support trailers
* [ ] Support HTTP 2

### Client tasks

* [ ] Basic HTTP 1.1
* [ ] Support Keep-Alive
* [ ] Support HTTP 2
* [ ] Support Expect-Continue 100
* [ ] Support chunked request and response
* [ ] Support streaming entity bodies
* [ ] Support form data
* [ ] Support multipart form data

## Examples Usages:

Creating a server is simple:

```java
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.HTTPHandler;

public class Example {
  public static void main(String... args) {
    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    HTTPServer server = new HTTPServer().withHandler(handler).withPort(4242);
    server.start();
    // Use server
    server.close();
  }
}
```

Since the `HTTPServer` class implements `java.io.Closeable`, you can also use a try-resource block like this:

```java
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.HTTPHandler;

public class Example {
  public static void main(String... args) {
    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    try (HTTPServer server = new HTTPServer().withHandler(handler).withPort(4242)) {
      server.start();
      // When this block exits, the server will be shutdown
    }
  }
}
```

You can also set various options on the server using the `with` methods on the class like this:

```java
import java.time.Duration;

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
                                        .withPort(4242);
    server.start();
    // Use server
    server.close();
  }
}
```

### Helping out

We are looking for Java developers that are interested in helping us build the client and server. If you know a ton about networks and
protocols and love writing clean, high-performance Java, contact us at dev@fusionauth.io.

### Building with Savant

**Note:** This project uses the Savant build tool. To compile using Savant, follow these instructions:

```bash
$ mkdir ~/savant
$ cd ~/savant
$ wget http://savant.inversoft.org/org/savantbuild/savant-core/1.0.0/savant-1.0.0.tar.gz
$ tar xvfz savant-1.0.0.tar.gz
$ ln -s ./savant-1.0.0 current
$ export PATH=$PATH:~/savant/current/bin/
```
