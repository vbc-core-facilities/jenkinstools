#!/usr/bin/env python3
"""
Aggregates the cases of parameterized tests in JUnit XML files into one testcase per test.

Part of jenkinstools (deployService). Standard library only; run it with `python3 -I`.

Why: JUnit XML has only testsuite > testcase, so an xUnit [Theory] or a pytest parametrize shows up in Jenkins as one
test per case. A test with ten cases is one expectation, and fails as a whole when one case fails.

Input format: ProteomicsEcosystemStandardV1 (proteomics_ecosystem/docs/ecosystem-mandates.md). What this script relies on:
  - testcase `name` is the bare test name, optionally followed directly by a case suffix starting with '(' or '['.
    Everything before the first '(' or '[' is the test; the rest is the case.
  - every testcase has a numeric `time` in seconds.

Grouping key: the testsuite, the testcase `classname`, and the test name. A testcase without a case suffix is passed through
unchanged, unless other cases of the same test exist (then it joins the group as a case with an empty suffix).

The combined testcase:
  - fails (failure) if any case failed or errored; is skipped only if every case was skipped; passes otherwise
  - time: the sum of its cases
  - failure message: "<failed>/<total> cases failed: <case>, <case>, ..." (one line, shortened if long)
  - failure body: every failing case with its own message and body
  - system-out: a summary (counts, total time, every case with its result and time), then the raw details of every case,
    including its own output; system-err: every case's own system-err, each under a header
  - properties: aggregation.format, aggregation.cases, aggregation.passed, aggregation.failed, aggregation.skipped

A file that breaks the format (unparseable XML, a testcase without a numeric time, a name that starts with the case
delimiter) is copied unchanged and reported; nothing is dropped.

Usage:
  aggregate_junit.py --input <dir> --output <dir> [--format ProteomicsEcosystemStandardV1] [--report <file>]
Exit codes: 0 all files aggregated; 2 at least one file did not conform and was copied unchanged; 1 usage or I/O error.
"""

import argparse
import glob
import os
import shutil
import sys
import xml.etree.ElementTree as ET

KNOWN_FORMATS = ("ProteomicsEcosystemStandardV1",)
CASE_DELIMITERS = "(["
MAX_MESSAGE_CASES = 10
MAX_MESSAGE_LENGTH = 1000

PASSED, FAILED, ERRORED, SKIPPED = "passed", "failed", "errored", "skipped"


class NonConformance(Exception):
    pass


def split_name(name):
    """'Test(a: 1)' -> ('Test', '(a: 1)'); 'Test' -> ('Test', '')."""
    positions = [name.find(d) for d in CASE_DELIMITERS if name.find(d) >= 0]
    if not positions:
        return name, ""
    cut = min(positions)
    return name[:cut], name[cut:]


def parse_time(testcase):
    raw = testcase.get("time")
    if raw is None:
        raise NonConformance("testcase %r has no time attribute" % testcase.get("name"))
    try:
        return float(raw)
    except ValueError:
        raise NonConformance("testcase %r has a non-numeric time %r" % (testcase.get("name"), raw))


def outcome_of(testcase):
    if testcase.find("error") is not None:
        return ERRORED
    if testcase.find("failure") is not None:
        return FAILED
    if testcase.find("skipped") is not None:
        return SKIPPED
    return PASSED


def text_of(element):
    return element.text if element is not None and element.text else ""


def format_seconds(seconds):
    # Sub-second cases are the common case, so they get millisecond resolution instead of a flat "0.000 s".
    if seconds < 1:
        return "%.2f ms" % (seconds * 1000)
    return "%.3f s" % seconds


def case_label(case):
    return case["suffix"] or "(no case suffix)"


class Case(object):
    def __init__(self, testcase, suffix):
        self.element = testcase
        self.suffix = suffix
        self.time = parse_time(testcase)
        self.outcome = outcome_of(testcase)

    def __getitem__(self, key):
        return getattr(self, key)

    def problem(self):
        """(message, type, body) of its failure, error or skip, or None."""
        for tag in ("error", "failure", "skipped"):
            node = self.element.find(tag)
            if node is not None:
                return node.get("message") or "", node.get("type") or tag, text_of(node)
        return None


def one_line(text):
    return " ".join((text or "").split())


def build_message(cases, failing):
    labels = [case_label(c) for c in failing[:MAX_MESSAGE_CASES]]
    more = len(failing) - len(labels)
    message = "%d/%d cases failed: %s" % (len(failing), len(cases), ", ".join(labels))
    if more > 0:
        message += ", and %d more" % more
    if len(message) > MAX_MESSAGE_LENGTH:
        message = message[:MAX_MESSAGE_LENGTH - 3] + "..."
    return message


def build_summary(test_name, cases, data_format):
    counts = dict((o, sum(1 for c in cases if c.outcome == o)) for o in (PASSED, FAILED, ERRORED, SKIPPED))
    total = sum(c.time for c in cases)
    lines = [
        "=== AGGREGATED TEST: %s" % test_name,
        "    %d cases: %d passed, %d failed, %d errored, %d skipped; total time %s"
        % (len(cases), counts[PASSED], counts[FAILED], counts[ERRORED], counts[SKIPPED], format_seconds(total)),
        "    aggregated by jenkinstools from format %s; each case below, in the order the framework reported them."
        % data_format,
        "",
    ]
    width = max(len(o) for o in (PASSED, FAILED, ERRORED, SKIPPED))
    for index, case in enumerate(cases, 1):
        lines.append("    %3d. %-*s %10s  %s" % (index, width, case.outcome.upper(), format_seconds(case.time), case_label(case)))
    return lines


def build_details(cases):
    lines = []
    for index, case in enumerate(cases, 1):
        lines.append("")
        lines.append("=== CASE %d/%d: %s  [%s, %s]" % (index, len(cases), case_label(case), case.outcome.upper(), format_seconds(case.time)))
        problem = case.problem()
        if problem:
            message, kind, body = problem
            lines.append("--- %s (type %s): %s" % (case.outcome, kind, message))
            if body.strip():
                lines.append(body.rstrip())
        out = text_of(case.element.find("system-out"))
        if out.strip():
            lines.append("--- system-out")
            lines.append(out.rstrip())
        err = text_of(case.element.find("system-err"))
        if err.strip():
            lines.append("--- system-err (also in this testcase's system-err)")
            lines.append(err.rstrip())
        if not problem and not out.strip() and not err.strip():
            lines.append("(no output recorded)")
    return lines


def combine(classname, test_name, cases, data_format):
    combined = ET.Element("testcase", {
        "classname": classname,
        "name": test_name,
        "time": "%.6f" % sum(c.time for c in cases),
    })
    for attribute in ("file", "line"):
        value = cases[0].element.get(attribute)
        if value is not None:
            combined.set(attribute, value)

    failing = [c for c in cases if c.outcome in (FAILED, ERRORED)]
    skipped = [c for c in cases if c.outcome == SKIPPED]

    properties = ET.SubElement(combined, "properties")
    for key, value in (
        ("aggregation.format", data_format),
        ("aggregation.cases", len(cases)),
        ("aggregation.passed", sum(1 for c in cases if c.outcome == PASSED)),
        ("aggregation.failed", len(failing)),
        ("aggregation.skipped", len(skipped)),
    ):
        ET.SubElement(properties, "property", {"name": key, "value": str(value)})

    if failing:
        body = []
        for case in failing:
            message, kind, text = case.problem()
            body.append("=== %s case %s  [%s]" % (case.outcome.upper(), case_label(case), format_seconds(case.time)))
            body.append("%s: %s" % (kind, message) if message else kind)
            if text.strip():
                body.append(text.rstrip())
            body.append("")
        ET.SubElement(combined, "failure", {
            "message": build_message(cases, failing),
            "type": "AggregatedCaseFailure",
        }).text = "\n".join(body).rstrip() + "\n"
    elif len(skipped) == len(cases):
        reasons = sorted(set(one_line(c.problem()[0]) for c in skipped if c.problem()[0]))
        message = "all %d cases skipped" % len(cases)
        if reasons:
            message += ": " + "; ".join(reasons)
        ET.SubElement(combined, "skipped", {"message": message[:MAX_MESSAGE_LENGTH]})

    ET.SubElement(combined, "system-out").text = "\n".join(build_summary(test_name, cases, data_format) + build_details(cases)) + "\n"

    errs = []
    for case in cases:
        err = text_of(case.element.find("system-err"))
        if err.strip():
            errs.append("=== CASE %s" % case_label(case))
            errs.append(err.rstrip())
    if errs:
        ET.SubElement(combined, "system-err").text = "\n".join(errs) + "\n"
    return combined


def aggregate_suite(suite, data_format):
    """Rewrites one testsuite element in place. Returns (cases_in, tests_out)."""
    children = list(suite)
    testcases = [c for c in children if c.tag == "testcase"]
    groups = {}
    order = []
    for testcase in testcases:
        name = testcase.get("name") or ""
        test_name, suffix = split_name(name)
        if not test_name:
            raise NonConformance("testcase name %r has no test name before its case suffix" % name)
        key = (testcase.get("classname") or "", test_name)
        if key not in groups:
            groups[key] = []
            order.append(key)
        groups[key].append(Case(testcase, suffix))

    replacement = {}
    for key in order:
        cases = groups[key]
        if len(cases) == 1 and not cases[0].suffix:
            replacement[key] = cases[0].element
        else:
            replacement[key] = combine(key[0], key[1], cases, data_format)

    # Rebuild children: non-testcase elements keep their place, each group takes the place of its first case.
    emitted = set()
    for child in children:
        suite.remove(child)
    for child in children:
        if child.tag != "testcase":
            suite.append(child)
            continue
        key = (child.get("classname") or "", split_name(child.get("name") or "")[0])
        if key not in emitted:
            emitted.add(key)
            suite.append(replacement[key])

    outputs = [replacement[k] for k in order]
    suite.set("tests", str(len(outputs)))
    suite.set("failures", str(sum(1 for t in outputs if t.find("failure") is not None)))
    suite.set("errors", str(sum(1 for t in outputs if t.find("error") is not None)))
    suite.set("skipped", str(sum(1 for t in outputs if t.find("skipped") is not None)))
    properties = suite.find("properties")
    if properties is None:
        properties = ET.Element("properties")
        suite.insert(0, properties)
    ET.SubElement(properties, "property", {"name": "aggregation.format", "value": data_format})
    ET.SubElement(properties, "property", {"name": "aggregation.casesBeforeAggregation", "value": str(len(testcases))})
    return len(testcases), len(outputs)


def aggregate_file(source, target, data_format):
    try:
        tree = ET.parse(source)
    except ET.ParseError as error:
        raise NonConformance("not well-formed XML: %s" % error)
    root = tree.getroot()
    if root.tag == "testsuite":
        suites = [root]
    elif root.tag == "testsuites":
        suites = list(root.iter("testsuite"))
    else:
        raise NonConformance("root element is <%s>, expected <testsuites> or <testsuite>" % root.tag)

    cases_in = tests_out = 0
    for suite in suites:
        before, after = aggregate_suite(suite, data_format)
        cases_in += before
        tests_out += after
    if root.tag == "testsuites":
        for attribute in ("tests", "failures", "errors", "skipped"):
            if root.get(attribute) is not None:
                root.set(attribute, str(sum(int(s.get(attribute) or 0) for s in suites)))
    tree.write(target, encoding="utf-8", xml_declaration=True)
    return cases_in, tests_out


def main(argv):
    parser = argparse.ArgumentParser(description="Aggregate parameterized test cases in JUnit XML files.")
    parser.add_argument("--input", required=True, help="folder with the JUnit XML files (*.xml, not recursive)")
    parser.add_argument("--output", required=True, help="folder for the aggregated files (same file names)")
    parser.add_argument("--format", default=KNOWN_FORMATS[0], help="input format; known: " + ", ".join(KNOWN_FORMATS))
    parser.add_argument("--report", help="also write the report printed on stdout to this file")
    arguments = parser.parse_args(argv)

    if arguments.format not in KNOWN_FORMATS:
        print("aggregate_junit: unknown format %r; known: %s" % (arguments.format, ", ".join(KNOWN_FORMATS)), file=sys.stderr)
        return 1
    if not os.path.isdir(arguments.input):
        print("aggregate_junit: input folder %r does not exist" % arguments.input, file=sys.stderr)
        return 1
    os.makedirs(arguments.output, exist_ok=True)

    report = ["JUnit aggregation (format %s): %s -> %s" % (arguments.format, arguments.input, arguments.output)]
    nonconforming = 0
    files = sorted(glob.glob(os.path.join(arguments.input, "*.xml")))
    if not files:
        report.append("  no *.xml files found")
    for source in files:
        target = os.path.join(arguments.output, os.path.basename(source))
        try:
            cases_in, tests_out = aggregate_file(source, target, arguments.format)
            report.append("  OK          %s: %d testcases -> %d tests" % (os.path.basename(source), cases_in, tests_out))
        except NonConformance as problem:
            nonconforming += 1
            shutil.copyfile(source, target)
            report.append("  NONCONFORM  %s: %s; copied unchanged" % (os.path.basename(source), problem))
    text = "\n".join(report)
    print(text)
    if arguments.report:
        with open(arguments.report, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    return 2 if nonconforming else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
