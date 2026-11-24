# RackWatch daemon — Makefile
#
#   make               build rackwatchd
#   make test          build and run the C unit tests (no network needed)
#   make test-asan     rebuild with AddressSanitizer + UBSan and run the
#                      tests plus a dry-run of the daemon under them
#   make test-integration
#                      run the mock-API smoke test (needs python3)
#   make install       install the binary, example config and systemd unit
#   make clean         remove build artefacts
#
# Build with -Wall -Wextra -Werror: warnings are errors here, so a
# sloppy build never ships. Requires gcc, make, pkg-config and
# libcurl4-openssl-dev (Debian/Ubuntu) or libcurl-devel (Fedora).

CC ?= gcc
CFLAGS ?= -std=c11 -O2 -Wall -Wextra -Werror -D_POSIX_C_SOURCE=200809L -D_DEFAULT_SOURCE
CPPFLAGS ?=
LDFLAGS ?=

CURL_CFLAGS := $(shell pkg-config --cflags libcurl 2>/dev/null)
CURL_LIBS := $(shell pkg-config --libs libcurl 2>/dev/null || echo -lcurl)

SRC := rackwatchd.c config.c simulate.c json.c buffer.c http.c reading.c
OBJ := $(SRC:.c=.o)
BIN := rackwatchd

TEST_BIN := test_rackwatchd
TEST_SRC := test/test_main.c config.c simulate.c json.c buffer.c http.c reading.c
TEST_OBJ := $(TEST_SRC:.c=.o)

PREFIX ?= /usr/local
SYSTEMD_UNIT_DIR ?= /lib/systemd/system

.PHONY: all test test-asan test-integration clean install

all: $(BIN)

$(BIN): $(OBJ)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(OBJ) $(CURL_LIBS) -lm

%.o: %.c
	$(CC) $(CFLAGS) $(CPPFLAGS) $(CURL_CFLAGS) -c $< -o $@

# Like test-asan, start from a clean tree: the plain and sanitizer
# builds share the same .o filenames, so switching between `make test`
# and `make test-asan` without a clean leaves mixed objects that fail
# to link (undefined __asan_* references). The rebuild costs ~2 s.
test: clean
	$(MAKE) $(TEST_BIN)
	./$(TEST_BIN)

$(TEST_BIN): $(TEST_OBJ)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(TEST_OBJ) $(CURL_LIBS) -lm

test/test_main.o: test/test_main.c
	$(CC) $(CFLAGS) $(CPPFLAGS) $(CURL_CFLAGS) -c $< -o $@

# Sanitizer run: clean first, because the .o files must be rebuilt with
# the sanitizer flags. Runs the unit tests and one dry-run of the daemon
# (no key, no server needed) under ASan+UBSan.
test-asan: clean
	$(MAKE) test CFLAGS="$(CFLAGS) -fsanitize=address,undefined -g -O1 -fno-omit-frame-pointer"
	$(MAKE) $(BIN) CFLAGS="$(CFLAGS) -fsanitize=address,undefined -g -O1 -fno-omit-frame-pointer"
	./$(BIN) --config sensors.conf --dry-run --once --seed 42 --foreground --buffer /tmp/rackwatchd-test/buffer.jsonl --quarantine /tmp/rackwatchd-test/quarantine.jsonl

test-integration: all
	test/smoke.sh

clean:
	rm -f $(BIN) $(TEST_BIN) $(OBJ) $(TEST_OBJ)

install: all
	install -d $(DESTDIR)$(PREFIX)/bin
	install -m 755 $(BIN) $(DESTDIR)$(PREFIX)/bin/$(BIN)
	install -d $(DESTDIR)/etc/rackwatchd
	@if [ ! -f $(DESTDIR)/etc/rackwatchd/sensors.conf ]; then \
		install -m 644 sensors.conf $(DESTDIR)/etc/rackwatchd/sensors.conf; \
		echo "installed /etc/rackwatchd/sensors.conf"; \
	else \
		echo "/etc/rackwatchd/sensors.conf already exists; not overwriting"; \
	fi
	install -d $(DESTDIR)$(SYSTEMD_UNIT_DIR)
	install -m 644 rackwatchd.service $(DESTDIR)$(SYSTEMD_UNIT_DIR)/rackwatchd.service
	@echo "Installed. Create the runtime user and the key file, then:"
	@echo "  useradd --system --no-create-home --home-dir /var/lib/rackwatchd rackwatch"
	@echo "  install -d -o rackwatch -g rackwatch -m 750 /etc/rackwatchd"
	@echo "  printf 'RACKWATCH_API_KEY=<key>\\n' > /etc/rackwatchd/env"
	@echo "  chmod 600 /etc/rackwatchd/env && chown rackwatch:rackwatch /etc/rackwatchd/env"
	@echo "  systemctl daemon-reload && systemctl enable --now rackwatchd"
