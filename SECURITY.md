# RackWatch — Security

This is a student final-year project, not a hardened production
system. This file says what it does against the OWASP Top 10
(2021), and — just as importantly — what it does not do. The
limitations are stated here so nobody deploys it believing it is
something it is not.

## OWASP Top 10 (2021) mapping

| # | Risk | What RackWatch does |
|---|------|---------------------|
| A01 | Broken Access Control | Every `/api/**` endpoint requires the `X-API-Key` header except exactly one public path, `/actuator/health` (exact match, not a prefix — `/actuator/healthz` is not public). The static dashboard files (`/`, `/index.html`, `/css/dashboard.css`, `/js/dashboard.js`, `/js/chart.umd.min.js`, `/favicon.ico`) are public, but they carry no data: every data call goes through the key. There are no user accounts or roles — one shared key for the whole deployment (see Limitations). |
| A02 | Cryptographic Failures | No passwords are stored anywhere. The API key is compared in constant time (`MessageDigest.isEqual`), is never logged, and is never put in a URL. The honest gap: the daemon posts over plain HTTP by default, so the key travels in plaintext on the wire (see Limitations). TLS is a reverse proxy in front of the API, not built in. |
| A03 | Injection | No SQL is built from strings. Reads and writes go through Spring Data JPA (parameterised queries) and `JdbcTemplate` batch updates with bind parameters. The only native SQL is a fixed `INSERT ... ON CONFLICT DO NOTHING` with no user input in it. JSON is parsed by Jackson with the DTO's validation annotations, not by hand. |
| A04 | Insecure Design | Alert rules are designed to fail safe: a sensor fault freezes the threshold and rate rules (no alert on data that cannot be trusted), and a bad batch is quarantined instead of blocking good data. There is no rate limiting on the API (see Limitations). |
| A05 | Security Misconfiguration | The API fails fast when `RACKWATCH_API_KEY` or the database password is unset — there are no default secrets. Error responses are generic (`{"message": ...}`) with no stack traces. The dashboard gets a Content-Security-Policy (`default-src 'self'` and friends) plus `X-Content-Type-Options: nosniff`. Actuator exposes only `health` and `info`. Both containers run as non-root users (API UID 10001, daemon user `rackwatch`). |
| A06 | Vulnerable and Outdated Components | Versions are pinned and their support dates are recorded in `api/pom.xml`, `api/src/main/resources/application.yml` and the README. The `dependency-check` GitHub workflow runs OWASP dependency-check on every push and weekly, and fails the build on any dependency vulnerability with CVSS >= 7. Chart.js is vendored at a pinned version (4.6.1) instead of loaded from a CDN. The daemon's C library (libcurl) comes from the OS package manager, so it is only as current as the base image — keep the image updated (see Limitations). |
| A07 | Identification and Authentication Failures | One shared API key (see Limitations). The key is a long random value set by the operator, compared in constant time, and the dashboard keeps it in `sessionStorage` (this tab only, cleared when the tab closes) rather than `localStorage`. Failed authentication returns a bare 401 with no hint about what was wrong. |
| A08 | Software and Data Integrity Failures | Dependencies come from Maven Central and jsDelivr; the CI workflows pin their GitHub Actions by major version (`@v4`). There is no artifact signing and no lockfile for the Maven build — a compromised or withdrawn dependency version would be noticed only by the dependency-check workflow or a failed build (see Limitations). |
| A09 | Security Logging and Monitoring Failures | The daemon logs buffer, quarantine, auth and retry events to syslog; the API logs at the framework's default level. Failed authentication attempts are NOT logged by the API — there is no audit trail for someone guessing the key (see Limitations). |
| A10 | Server-Side Request Forgery | The daemon only ever POSTs to the one URL given with `--url` (default localhost:8080). libcurl is restricted to the `http` and `https` protocols, and the daemon never follows a redirect to another host (redirect protocols are restricted the same way). The API makes no outbound requests at all. |

## Honest limitations (do not deploy past these without fixing them)

- **HTTP-only by default.** The API key travels in plaintext between
  the daemon and the API. On one host or one private compose network
  that is acceptable; across an untrusted network, put a TLS reverse
  proxy in front of the API and point the daemon at `https://`.
- **One shared API key, no per-user accounts, no rotation.** Anyone
  with the key can read everything and acknowledge any alert. There
  is no way to revoke one person's access without rotating the key
  for everyone, and no expiry. If you need per-user access, add real
  authentication (out of scope for this project).
- **No rate limiting.** The API does not throttle requests. A client
  posting large batches in a loop can fill the database; the 64 KB
  request-size limit and the 100-reading batch cap are the only
  brakes.
- **Failed logins are not logged.** The API returns 401 without
  recording the attempt, so a key-guessing attack leaves no trace in
  the logs.
- **No artifact signing or dependency lockfile.** The Maven build
  resolves dependency versions at build time; a tampered or
  withdrawn version would only be caught by a failed build or by the
  weekly dependency-check run.
- **The daemon's libcurl is only as current as its base image.**
  The image is rebuilt on every `docker compose up --build`, but
  nobody is notified when a new libcurl CVE appears — the
  dependency-check workflow covers the Java dependencies only.
- **The dashboard key lives in `sessionStorage`.** Any script running
  on the dashboard origin could read it. That is contained by the
  Content-Security-Policy and by building all alert text with
  `textContent` (never `innerHTML`), but a stored-XSS bug would
  still leak the key.
- **Audit log is append-only but not tamper-proof.** Rows are never
  updated or deleted by the code, but anyone with database access
  can change them; there is no hash chain or separate audit store.

## Reporting a problem

This is a personal project with no security response process. If you
find a vulnerability, fix it in a branch and say what you changed —
or, if you are reviewing it for a course, note it in your report
next to the limitation above that it belongs to.
