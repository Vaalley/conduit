---
name: Testing the Conduit HTTP interface
description: How to start the Conduit dev server with the authenticated HTTP endpoints enabled and verify /status and /broadcast.
---

# Testing the Conduit HTTP interface

## Devin Secrets Needed
- None for local smoke testing. Set `CONDUIT_HTTP_TOKEN` to any non-empty shared token and `CONDUIT_HTTP_PORT` (default 8080).

## Preconditions
- Java 25 OpenJDK must be installed (`openjdk-25-jdk`).
- Repo must be on the relevant branch (e.g. `devin/http-status-broadcast`).
- `~/.deno/bin` may need to be on `PATH` for the Observer checks.

## Starting the server

From the repo root (`/home/ubuntu/repos/conduit`):

```bash
export CONDUIT_HTTP_TOKEN=dev-test-token
export CONDUIT_HTTP_PORT=8080
setsid ./gradlew runServer --no-daemon >/tmp/conduit-server.log 2>&1 &
```

Wait for the line:

```
HTTP interface listening on /127.0.0.1:8080
```

in `/tmp/conduit-server.log` or `run/logs/latest.log`.

The first start downloads dependencies, accepts the EULA (`run/eula.txt`), and generates the world, so it can take several minutes.

## Verifying the endpoints

```bash
# Should return 200 JSON with online, players, tps
curl -i -H "Authorization: Bearer dev-test-token" http://127.0.0.1:8080/status

# Should return 204 and log `[Discord] <sender>: <content>` to the server console
curl -i -H "Authorization: Bearer dev-test-token" \
  -H "Content-Type: application/json" \
  -d '{"sender":"Devin","content":"hello"}' \
  -X POST http://127.0.0.1:8080/broadcast
```

## Observer bot checks

From `/home/ubuntu/repos/observer` on the matching branch:

```bash
export PATH="$HOME/.deno/bin:$PATH"
deno task check
deno lint
deno fmt --check
```

## Teardown

```bash
pkill -f 'gradlew runServer'
pkill -f 'KnotServer'
```

## Common gotchas
- `CONDUIT_HTTP_TOKEN` must be non-empty; otherwise `HttpApi` logs a warning and the interface is disabled.
- `broadcastSystemMessage` logs the message to the server console, so the `[Discord] <sender>: <content>` line should appear in both `/tmp/conduit-server.log` and `run/logs/latest.log`.
- The `/status` response maps 1:1 to the `StatusResponse` interface expected by Observer (`online: number`, `players: string[]`, `tps: number`).
