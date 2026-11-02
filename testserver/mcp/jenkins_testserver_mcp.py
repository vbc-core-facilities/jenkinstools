#!/usr/bin/env python3
"""MCP server (stdio) for the Jenkins test server in ../ (testserver/).

The same operations as testserver.sh, as MCP tools, for Claude Code and any other MCP client. Needs a Python 3 interpreter
and no packages: standard library only, on purpose. Run it with `python -I` (isolated mode).

Builds take minutes, longer than a client waits for one tool call. So a build is started (start_build) and then waited
for in slices (wait_build, at most WAIT_SLICE_MAX seconds per call), which a client can repeat.

Environment:
  TESTSERVER_URL          default http://127.0.0.1:18090
  TESTSERVER_COMPOSE_DIR  folder of the compose.yaml that server_up/server_down use (an organisation preset, e.g.
                          vbc_deployment/JenkinsTestServerVbc); default: the generic testserver folder
"""

import http.cookiejar
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SERVER_NAME = "jenkins-testserver"
SERVER_VERSION = "1.0.0"
SUPPORTED_PROTOCOL_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")

URL = os.environ.get("TESTSERVER_URL", "http://127.0.0.1:18090").rstrip("/")
COMPOSE_DIR = os.environ.get("TESTSERVER_COMPOSE_DIR") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WAIT_SLICE_MAX = 240
HTTP_TIMEOUT = 30

# the log lines testserver.sh prints after a build: mimic and Tower decisions, image coherence, test aggregation, warnings
KEY_LINE = re.compile(r"MIMIC|TOWER|coherence check|JUnit aggregation|^  (OK|NONCONFORM) |WARNING|ERROR|unstable|Finished:")


class ToolError(Exception):
    """A failure the client should see as the tool's result (isError), not as a protocol error."""


# ---------------------------------------------------------------- Jenkins HTTP

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_cookies = http.cookiejar.CookieJar()
_opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_cookies), _NoRedirect())


def _request(url, method="GET", data=None, headers=None):
    request = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with _opener.open(request, timeout=HTTP_TIMEOUT) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()
    except (urllib.error.URLError, OSError) as error:
        raise ToolError(f"test server not reachable at {URL}: {error}. Start it with server_up.") from error


def get_json(path_or_url):
    url = path_or_url if path_or_url.startswith("http") else URL + path_or_url
    status, _, body = _request(url)
    if status == 404:
        return None
    if status >= 400:
        raise ToolError(f"GET {url}: HTTP {status}")
    return json.loads(body)


def get_text(url):
    status, _, body = _request(url)
    if status >= 400:
        raise ToolError(f"GET {url}: HTTP {status}")
    return body.decode("utf-8", errors="replace")


def post(url, form=None):
    """POSTs with a CSRF crumb (from the same session); returns the Location header."""
    crumb = get_json("/crumbIssuer/api/json")
    headers = {crumb["crumbRequestField"]: crumb["crumb"]} if crumb else {}
    data = urllib.parse.urlencode(form or {}).encode()
    headers["Content-Type"] = "application/x-www-form-urlencoded"
    status, response_headers, _ = _request(url, method="POST", data=data, headers=headers)
    if status >= 400:
        raise ToolError(f"POST {url}: HTTP {status}")
    return response_headers.get("Location", "")


def job_url(job, branch=None):
    url = f"{URL}/job/{urllib.parse.quote(job, safe='')}"
    if branch:
        # multibranch item names are URL-encoded once by Jenkins and once more in the URL
        url += "/job/" + urllib.parse.quote(urllib.parse.quote(branch, safe=""), safe="")
    return url


def resolve_build(job, branch, number):
    if number:
        return int(number)
    data = get_json(job_url(job, branch) + "/api/json?tree=lastBuild%5Bnumber%5D")
    if not data:
        raise ToolError(f"no branch '{branch}' in job '{job}'")
    if not data.get("lastBuild"):
        raise ToolError(f"{job} {branch}: no builds yet")
    return data["lastBuild"]["number"]


# ---------------------------------------------------------------- tools

VERSION_TAG = re.compile(r"^v?\d+(\.\d+)+")


def tool_status(args):
    data = get_json("/api/json?tree=jobs%5Bname,jobs%5Bname,color%5D%5D")
    lines = [f"test server: {URL}"]
    for job in data["jobs"]:
        if args.get("job") and job["name"] != args["job"]:
            continue
        items = [(urllib.parse.unquote(b["name"]), b.get("color", "")) for b in job.get("jobs", [])]
        # without a job filter, version tags that were never built are only counted: repos have many
        hidden = [] if args.get("job") else [n for n, c in items if VERSION_TAG.match(n) and c == "notbuilt"]
        shown = ", ".join(f"{n} ({c})" for n, c in items if n not in hidden)
        lines.append(f"{job['name']}: {shown or '(no branches discovered)'}"
                     + (f"; {len(hidden)} unbuilt version tags (status with job= lists them)" if hidden else ""))
    return "\n".join(lines)


def tool_scan(args):
    post(job_url(args["job"]) + "/build?delay=0")
    return f"scan of {args['job']} started; new branches appear in status within a minute"


def tool_start_build(args):
    job, branch = args["job"], args["branch"]
    parameters = args.get("parameters") or {}
    data = get_json(job_url(job, branch) + "/api/json?tree=property%5B_class%5D")
    if not data:
        raise ToolError(f"no branch '{branch}' in job '{job}': commit it locally, then scan")
    has_parameters = any("ParametersDefinitionProperty" in (p.get("_class") or "") for p in data.get("property", []))
    # a job with parameters (declared by its Jenkinsfile after its first build) only starts through buildWithParameters
    if parameters or has_parameters:
        queue = post(job_url(job, branch) + "/buildWithParameters?delay=0", {k: str(v) for k, v in parameters.items()})
    else:
        queue = post(job_url(job, branch) + "/build?delay=0")
    if not queue:
        raise ToolError("the build was not queued")
    for _ in range(60):
        item = get_json(queue.rstrip("/") + "/api/json")
        if item and item.get("executable"):
            number = item["executable"]["number"]
            return (f"started {job} {branch} #{number}: {job_url(job, branch)}/{number}/\n"
                    f"wait for it with wait_build(job, branch, number={number})")
        if item and item.get("cancelled"):
            raise ToolError("the queued build was cancelled")
        time.sleep(2)
    raise ToolError(f"queued but not started after 120 s: {queue}")


def _build_summary(job, branch, number, data):
    build_url = f"{job_url(job, branch)}/{number}"
    lines = [f"{job} {branch} #{number}: {data['result']} ({data['duration'] / 1000:.0f} s) {build_url}/", ""]
    lines.append(tests_text(build_url, include_passed=False))
    key_lines = [line for line in get_text(build_url + "/consoleText").splitlines() if KEY_LINE.search(line)]
    lines += ["", "key log lines:"] + [f"  {line}" for line in key_lines[:60]]
    return "\n".join(lines)


def tool_build_status(args):
    job, branch = args["job"], args["branch"]
    number = resolve_build(job, branch, args.get("number"))
    data = get_json(f"{job_url(job, branch)}/{number}/api/json?tree=building,result,duration,estimatedDuration,timestamp")
    if not data:
        raise ToolError(f"no build #{number} of {job} {branch}")
    if data["building"]:
        elapsed = time.time() - data["timestamp"] / 1000
        return f"{job} {branch} #{number}: running for {elapsed:.0f} s (estimated total {data['estimatedDuration'] / 1000:.0f} s)"
    return _build_summary(job, branch, number, data)


def tool_wait_build(args):
    job, branch = args["job"], args["branch"]
    number = resolve_build(job, branch, args.get("number"))
    seconds = min(int(args.get("timeout_seconds") or WAIT_SLICE_MAX), WAIT_SLICE_MAX)
    deadline = time.time() + seconds
    while True:
        data = get_json(f"{job_url(job, branch)}/{number}/api/json?tree=building,result,duration,timestamp")
        if not data:
            raise ToolError(f"no build #{number} of {job} {branch}")
        if not data["building"]:
            return _build_summary(job, branch, number, data)
        if time.time() >= deadline:
            elapsed = time.time() - data["timestamp"] / 1000
            return (f"{job} {branch} #{number}: still running after {elapsed:.0f} s. "
                    f"Call wait_build again with number={number}.")
        time.sleep(10)


def tests_text(build_url, include_passed, max_output_chars=0):
    report = get_json(build_url + "/testReport/api/json?tree=passCount,failCount,skipCount,"
                                  "suites%5Bname,cases%5BclassName,name,status,duration,errorDetails,errorStackTrace,stdout%5D%5D")
    if not report:
        return "tests: no test report"
    lines = [f"tests: {report['passCount']} passed, {report['failCount']} failed, {report['skipCount']} skipped"]
    for suite in report.get("suites", []):
        for case in suite.get("cases", []):
            failed = case["status"] in ("FAILED", "REGRESSION")
            if not failed and not include_passed:
                continue
            lines.append(f"  {case['status']:<10} {case['className']}.{case['name']} ({case['duration']:.3f} s)")
            if failed and case.get("errorDetails"):
                lines.append("      " + case["errorDetails"].strip().replace("\n", "\n      ")[:1500])
            if max_output_chars and case.get("stdout"):
                lines.append("      output: " + case["stdout"].strip()[:max_output_chars].replace("\n", "\n      "))
    return "\n".join(lines)


def tool_test_report(args):
    job, branch = args["job"], args["branch"]
    number = resolve_build(job, branch, args.get("number"))
    return tests_text(f"{job_url(job, branch)}/{number}", bool(args.get("include_passed")),
                      int(args.get("max_output_chars") or 0))


def tool_console_log(args):
    job, branch = args["job"], args["branch"]
    number = resolve_build(job, branch, args.get("number"))
    lines = get_text(f"{job_url(job, branch)}/{number}/consoleText").splitlines()
    if args.get("pattern"):
        try:
            pattern = re.compile(args["pattern"])
        except re.error as error:
            raise ToolError(f"pattern is not a valid regular expression: {error}") from error
        lines = [line for line in lines if pattern.search(line)]
    tail = int(args.get("tail_lines") or 400)
    shown = lines[-tail:]
    header = f"{job} {branch} #{number}: {len(shown)} of {len(lines)} lines"
    return header + "\n" + "\n".join(shown)


def _compose(*arguments):
    try:
        completed = subprocess.run(["docker", "compose", *arguments], cwd=COMPOSE_DIR, capture_output=True, text=True,
                                   timeout=1800)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ToolError(f"docker compose {' '.join(arguments)} failed in {COMPOSE_DIR}: {error}") from error
    output = (completed.stdout + completed.stderr).strip().splitlines()[-30:]
    if completed.returncode != 0:
        raise ToolError(f"docker compose {' '.join(arguments)} failed in {COMPOSE_DIR}:\n" + "\n".join(output))
    return output


def tool_server_up(args):
    output = _compose("up", "-d", "--build")
    for _ in range(90):
        try:
            if _request(URL + "/api/json")[0] == 200:
                return f"test server up: {URL} (compose: {COMPOSE_DIR})\n" + "\n".join(output)
        except ToolError:
            pass
        time.sleep(2)
    raise ToolError(f"started, but Jenkins does not answer at {URL}; see docker compose logs jenkins in {COMPOSE_DIR}")


def tool_server_down(args):
    _compose("down")
    return f"test server stopped (compose: {COMPOSE_DIR}); Jenkins home, images and registry volumes are kept"


JOB = {"type": "string", "description": "multibranch job name, e.g. hive.proteomicstime (see status)"}
BRANCH = {"type": "string", "description": "branch or tag name as in the repo, '/' allowed, e.g. feat/x or v1.2.3"}
NUMBER = {"type": "integer", "description": "build number; default: the last build"}

TOOLS = {
    "status": (tool_status, "Is the test server up? Lists its jobs and their discovered branches and tags (with the "
                            "colour of the last build: blue ok, yellow unstable, red failed, notbuilt).",
               {"job": {"type": "string", "description": "only this job, with all its tags"}}, [], True),
    "scan": (tool_scan, "Re-scan a job's repo now, so a branch committed locally a moment ago appears (otherwise every "
                        "2 minutes). Only committed state is visible to Jenkins.",
             {"job": JOB}, ["job"], False),
    "start_build": (tool_start_build, "Start a build of a branch or tag and return its number without waiting. Then "
                                      "call wait_build. Builds are never started automatically on the test server.",
                    {"job": JOB, "branch": BRANCH,
                     "parameters": {"type": "object", "additionalProperties": {"type": ["string", "number", "boolean"]},
                                    "description": "build parameters NAME: value; parameters not given keep their defaults"}},
                    ["job", "branch"], False),
    "wait_build": (tool_wait_build, f"Wait up to timeout_seconds (max {WAIT_SLICE_MAX}) for a build. When it has "
                                    "finished: result, test summary with failing tests, and the key log lines (mimic, "
                                    "Tower, coherence check, aggregation). Otherwise says it is still running: call again.",
                   {"job": JOB, "branch": BRANCH, "number": NUMBER,
                    "timeout_seconds": {"type": "integer", "description": f"default and max {WAIT_SLICE_MAX}"}},
                   ["job", "branch"], True),
    "build_status": (tool_build_status, "Status of a build without waiting: running (elapsed, estimate) or the same "
                                        "summary as wait_build.",
                     {"job": JOB, "branch": BRANCH, "number": NUMBER}, ["job", "branch"], True),
    "test_report": (tool_test_report, "Test report of a build as Jenkins shows it: counts, failing tests with messages; "
                                      "optionally passing tests and each test's captured output (aggregated tests carry "
                                      "their case summary there).",
                    {"job": JOB, "branch": BRANCH, "number": NUMBER,
                     "include_passed": {"type": "boolean", "description": "list passing and skipped tests too"},
                     "max_output_chars": {"type": "integer", "description": "include up to this many characters of each "
                                                                            "listed test's output (default 0: none)"}},
                    ["job", "branch"], True),
    "console_log": (tool_console_log, "Console log of a build, optionally filtered by a regular expression, last "
                                      "tail_lines lines (default 400).",
                    {"job": JOB, "branch": BRANCH, "number": NUMBER,
                     "pattern": {"type": "string", "description": "Python regular expression; only matching lines"},
                     "tail_lines": {"type": "integer", "description": "default 400"}},
                    ["job", "branch"], True),
    "server_up": (tool_server_up, "Start or rebuild the test server (docker compose up -d --build in "
                                  "TESTSERVER_COMPOSE_DIR) and wait until Jenkins answers. First start takes minutes.",
                  {}, [], False),
    "server_down": (tool_server_down, "Stop the test server (docker compose down). Volumes are kept.",
                    {}, [], False),
}


def tools_list():
    return [{
        "name": name,
        "description": description,
        "inputSchema": {"type": "object", "properties": properties, "required": required, "additionalProperties": False},
        "annotations": {"readOnlyHint": read_only, "destructiveHint": False, "openWorldHint": False},
    } for name, (_, description, properties, required, read_only) in TOOLS.items()]


# ---------------------------------------------------------------- MCP over stdio (newline-delimited JSON-RPC 2.0)

def handle(message):
    method = message.get("method")
    params = message.get("params") or {}
    if method == "initialize":
        requested = params.get("protocolVersion")
        version = requested if requested in SUPPORTED_PROTOCOL_VERSIONS else SUPPORTED_PROTOCOL_VERSIONS[0]
        return {"protocolVersion": version, "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                "instructions": f"Drives the local Jenkins test server at {URL}. Commit before building (Jenkins "
                                "clones the local repos), scan, start_build, then wait_build until it reports a result."}
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": tools_list()}
    if method == "tools/call":
        name = params.get("name")
        if name not in TOOLS:
            raise LookupError(f"unknown tool: {name}")
        function = TOOLS[name][0]
        try:
            text, is_error = function(params.get("arguments") or {}), False
        except ToolError as error:
            text, is_error = str(error), True
        except KeyError as error:
            text, is_error = f"missing argument: {error}", True
        return {"content": [{"type": "text", "text": text}], "isError": is_error}
    raise LookupError(f"method not found: {method}")


def main():
    output = sys.stdout.buffer
    for raw in sys.stdin.buffer:
        if not raw.strip():
            continue
        try:
            message = json.loads(raw)
        except json.JSONDecodeError:
            response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}}
        else:
            if "id" not in message:  # a notification (e.g. notifications/initialized): no response
                continue
            try:
                response = {"jsonrpc": "2.0", "id": message["id"], "result": handle(message)}
            except LookupError as error:
                response = {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": str(error)}}
            except Exception as error:  # noqa: BLE001 - a tool bug must not end the server
                response = {"jsonrpc": "2.0", "id": message["id"],
                            "error": {"code": -32603, "message": f"{type(error).__name__}: {error}"}}
        output.write(json.dumps(response).encode("utf-8") + b"\n")
        output.flush()


if __name__ == "__main__":
    main()
