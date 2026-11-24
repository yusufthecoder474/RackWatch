# RackWatch — root Makefile.
#   make build   — daemon binary + API jar
#   make test    — daemon tests (unit, sanitizers, smoke) + API verify
#                  (unit tests + Testcontainers integration tests +
#                  the JaCoCo 80% line-coverage gate). Needs Docker
#                  for the integration tests and Maven Central.
#   make up      — start the stack (needs a .env, see below)
#   make down    — stop the stack (data in the named volumes is kept)
#   make report  — regenerate docs/evaluation.md from the replay test
#   make clean   — remove build outputs
.PHONY: build test up down report clean help

help:
	@echo "Targets: build test up down report clean"

build:
	cd daemon && $(MAKE) all
	mvn -f api/pom.xml -DskipTests package

test:
	cd daemon && $(MAKE) test
	cd daemon && $(MAKE) test-asan
	cd daemon && $(MAKE) test-integration
	mvn -f api/pom.xml verify

up:
	@test -f .env || (echo "Missing .env. Create it first with:"; echo '  printf "POSTGRES_PASSWORD=%s\nRACKWATCH_API_KEY=%s\n" "$$(openssl rand -hex 16)" "$$(openssl rand -hex 32)" > .env'; echo "Do not commit .env."; exit 1)
	docker compose up -d --build

down:
	docker compose down

report:
	mvn -f api/pom.xml -Dtest=EvaluationReplayTest test

clean:
	cd daemon && $(MAKE) clean
	mvn -f api/pom.xml clean
