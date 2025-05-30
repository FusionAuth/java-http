#
# Build:
#   > docker pull ubuntu:noble
#   > docker buildx build --platform=linux/arm64 -t fusionauth/java-http-ab:latest .
#
# Note: Substitute your target platform architecture. The above example is targetting a 64-bit ARM platform.
#       To target an Intel based platform use --platform=linux/amd64.
#
# Run:
#  > docker run --rm fusionauth/java-http-ab -n 100 -c 100 http://host.docker.internal:8080/
#
FROM ubuntu:questing

RUN apt-get update \
    && apt-get -y install apache2-utils \
    && apt-get -y clean \
    && rm -rf /var/lib/apt/lists

RUN ulimit -S -n 1048576

ENV TERM=linux
ENTRYPOINT ["/usr/bin/ab"]
