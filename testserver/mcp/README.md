# MCP server for the Jenkins test server

`jenkins_testserver_mcp.py` offers the operations of `../testserver.sh` as MCP tools (stdio), so Claude Code and other MCP
clients drive the test server without a shell. Python standard library only: nothing to install. Made 2026-11-02.

| Tool | What |
| --- | --- |
| `status` | server reachable? jobs, branches and tags with the colour of their last build (unbuilt version tags only counted, unless `job` is given) |
| `scan` | re-scan a job's repo now (a freshly committed local branch appears) |
| `start_build` | start a build, optionally with `parameters`; returns the build number at once |
| `wait_build` | wait up to 240 s; when finished: result, failing tests, key log lines (mimic, Tower, coherence check, aggregation). Repeat while it says "still running" |
| `build_status` | the same without waiting |
| `test_report` | counts, failing tests with messages; optionally passing tests and each test's captured output |
| `console_log` | console log, filtered by a regular expression, last N lines |
| `server_up` / `server_down` | `docker compose up -d --build` / `down` in `TESTSERVER_COMPOSE_DIR`; volumes are kept |

Builds take minutes, more than an MCP client waits for one call, hence `start_build` + `wait_build` instead of one
blocking `build` as in the script.

## Register

Inside the jenkinstools repo, Claude Code picks it up from `../../.mcp.json` (project scope, asks once). To use it from
any repo (the usual case: you work in an app repo and test its branch), register it for your user, with absolute paths:

```bash
claude mcp add -s user jenkins-testserver \
  -e TESTSERVER_COMPOSE_DIR=<repos>/vbc_deployment/JenkinsTestServerVbc \
  -- python -I <repos>/jenkinstools/testserver/mcp/jenkins_testserver_mcp.py
```

Use `python3` where `python` is not Python 3. The server is read from the jenkinstools checkout as it is: switching that
checkout to a branch without this file breaks the registration until you switch back.

| Variable | Default |
| --- | --- |
| `TESTSERVER_URL` | `http://127.0.0.1:18090` |
| `TESTSERVER_COMPOSE_DIR` | the generic `testserver/` folder; for the VBC preset, `vbc_deployment/JenkinsTestServerVbc` |

## Check it by hand

```bash
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"status","arguments":{}}}' \
  | python -I testserver/mcp/jenkins_testserver_mcp.py
```
