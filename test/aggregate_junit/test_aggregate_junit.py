"""
Tests for resources/jenkinstools/aggregate_junit.py. Run from the repo root:

    python -I -m unittest discover -s test/aggregate_junit -v

The fixtures are real output, not hand-made: xunit.xml from JunitXml.TestLogger 8.0.0 (MethodFormat=Default,
FailureBodyFormat=Verbose) over xunit-source.cs.txt; pytest.xml from pytest 9.1.1 with the ProteomicsEcosystemStandardV1
flags over pytest-source.py.txt. Machine paths, host names and timestamps were replaced.
"""

import os
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "resources", "jenkinstools"))

import aggregate_junit  # noqa: E402

FIXTURES = os.path.join(HERE, "fixtures")


def run(*files):
    source = tempfile.mkdtemp()
    target = tempfile.mkdtemp()
    for name in files:
        shutil.copy(os.path.join(FIXTURES, name), source)
    code = aggregate_junit.main(["--input", source, "--output", target])
    results = {}
    for name in files:
        path = os.path.join(target, name)
        results[name] = path
    return code, results


def testcases(path):
    return {tc.get("name"): tc for tc in ET.parse(path).getroot().iter("testcase")}


def prop(testcase, name):
    for p in testcase.find("properties"):
        if p.get("name") == name:
            return p.get("value")
    return None


class SplitName(unittest.TestCase):
    def test_bare_name_has_no_case(self):
        self.assertEqual(aggregate_junit.split_name("PlainPasses"), ("PlainPasses", ""))

    def test_dotnet_case(self):
        self.assertEqual(aggregate_junit.split_name("Retries(a: 1, b: \"x[y]\")"), ("Retries", "(a: 1, b: \"x[y]\")"))

    def test_pytest_case_with_brackets_inside(self):
        self.assertEqual(aggregate_junit.split_name("test_names[a(b)[c].d]"), ("test_names", "[a(b)[c].d]"))

    def test_empty_pytest_id(self):
        self.assertEqual(aggregate_junit.split_name("test_names[]"), ("test_names", "[]"))


class XunitFixture(unittest.TestCase):
    def setUp(self):
        self.code, paths = run("xunit.xml")
        self.cases = testcases(paths["xunit.xml"])
        self.suite = ET.parse(paths["xunit.xml"]).getroot().find("testsuite")

    def test_exit_code_ok(self):
        self.assertEqual(self.code, 0)

    def test_one_testcase_per_test(self):
        self.assertEqual(sorted(self.cases), ["NamesWithBrackets", "PlainFails", "PlainPasses", "RetriesBackOff", "Skipped", "SlowOne"])

    def test_one_failing_case_fails_the_test(self):
        failure = self.cases["RetriesBackOff"].find("failure")
        self.assertIsNotNone(failure)
        self.assertEqual(failure.get("message"), "1/4 cases failed: (consecutiveFailures: 3, expectedSeconds: 20)")
        self.assertIn("case 3/20 deliberately fails", failure.text)
        self.assertIn("Stack Trace:", failure.text)

    def test_summary_counts_and_every_case(self):
        out = self.cases["RetriesBackOff"].find("system-out").text
        self.assertTrue(out.startswith("=== AGGREGATED TEST: RetriesBackOff"))
        self.assertIn("4 cases: 3 passed, 1 failed, 0 errored, 0 skipped", out)
        for n in (0, 1, 3, 4):
            self.assertIn("helper output for %d" % n, out)
        self.assertEqual(out.count("=== CASE "), 4)

    def test_time_is_the_sum(self):
        self.assertAlmostEqual(float(self.cases["RetriesBackOff"].get("time")), 0.0993876 + 0.0010463 + 0.0000360 + 0.0000083, places=6)

    def test_properties(self):
        tc = self.cases["RetriesBackOff"]
        self.assertEqual(prop(tc, "aggregation.cases"), "4")
        self.assertEqual(prop(tc, "aggregation.failed"), "1")
        self.assertEqual(prop(tc, "aggregation.format"), "ProteomicsEcosystemStandardV1")

    def test_all_passing_cases_pass(self):
        tc = self.cases["NamesWithBrackets"]
        self.assertIsNone(tc.find("failure"))
        self.assertIsNone(tc.find("skipped"))
        self.assertEqual(prop(tc, "aggregation.cases"), "2")

    def test_plain_tests_pass_through_unchanged(self):
        self.assertIsNone(self.cases["PlainPasses"].find("properties"))
        self.assertEqual(self.cases["SlowOne"].get("time"), "1.2133164")
        self.assertIsNotNone(self.cases["PlainFails"].find("failure"))
        self.assertIsNotNone(self.cases["Skipped"].find("skipped"))

    def test_suite_counts_are_recomputed(self):
        self.assertEqual(self.suite.get("tests"), "6")
        self.assertEqual(self.suite.get("failures"), "2")
        self.assertEqual(self.suite.get("skipped"), "1")

    def test_suite_output_is_kept(self):
        self.assertIn("console output for 4", self.suite.find("system-out").text)


class PytestFixture(unittest.TestCase):
    def setUp(self):
        self.code, paths = run("pytest.xml")
        self.cases = testcases(paths["pytest.xml"])

    def test_grouped_per_class_and_function(self):
        self.assertEqual(sorted(self.cases), ["test_backoff", "test_error_in_fixture", "test_names", "test_plain", "test_skipped", "test_slow"])

    def test_failing_case_named_by_its_id(self):
        self.assertEqual(self.cases["test_names"].find("failure").get("message"), "1/4 cases failed: [  ]")

    def test_stderr_is_collected(self):
        err = self.cases["test_backoff"].find("system-err").text
        self.assertIn("stderr for 3", err)


class NonConformingInput(unittest.TestCase):
    def test_copied_unchanged_and_reported(self):
        code, paths = run("handwritten-without-time.xml", "not-wellformed.xml", "xunit.xml")
        self.assertEqual(code, 2)
        for name in ("handwritten-without-time.xml", "not-wellformed.xml"):
            with open(os.path.join(FIXTURES, name), "rb") as a, open(paths[name], "rb") as b:
                self.assertEqual(a.read(), b.read())
        self.assertIn("RetriesBackOff", testcases(paths["xunit.xml"]))

    def test_unknown_format_is_a_usage_error(self):
        self.assertEqual(aggregate_junit.main(["--input", FIXTURES, "--output", tempfile.mkdtemp(), "--format", "Nope"]), 1)


if __name__ == "__main__":
    unittest.main()
