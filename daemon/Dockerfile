# RackWatch daemon — Dockerfile.
#
# Build from the repository root of the daemon directory:
#   docker build -t rackwatchd daemon/
#
# Multi-stage: the builder has the compiler and libcurl headers, the
# runtime image has only the libcurl runtime, procps (ps/top, for
# inspecting the daemon process inside the container) and CA
# certificates. The daemon runs as the non-root `rackwatch` user.
#
# The API key is NOT baked into the image: pass it at run time, for
# example with `docker run -e RACKWATCH_API_KEY=<key> ...` or a
# compose `environment:` entry. When the API runs in the same compose
# stack, point --url at the API service name (http://api:8080); see
# Part 6 for the compose wiring.
FROM debian:bookworm-slim AS builder
RUN apt-get update && apt-get install -y --no-install-recommends \
        gcc make libc6-dev libcurl4-openssl-dev pkg-config ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY . .
RUN make clean && make

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
        libcurl4 procps ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --no-create-home --home-dir /var/lib/rackwatchd rackwatch \
    && mkdir -p /var/lib/rackwatchd /etc/rackwatchd \
    && chown rackwatch:rackwatch /var/lib/rackwatchd
COPY --from=builder /build/rackwatchd /usr/local/bin/rackwatchd
COPY --from=builder /build/sensors.conf /etc/rackwatchd/sensors.conf
USER rackwatch
ENTRYPOINT ["/usr/local/bin/rackwatchd"]
CMD ["--config", "/etc/rackwatchd/sensors.conf", "--url", "http://api:8080", "--foreground"]
