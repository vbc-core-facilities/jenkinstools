using Xunit;
using System.Net;
using System.Text;

namespace Xen.JenkinsClient.Tests;

public sealed class JobPathTests
{
    [Fact]
    public void BranchWithSlashIsEncodedTwiceInTheUrl()
    {
        var path = JobPath.Parse("vbcf-protein-chemistry/jenkins-vbc-test", "feat/x");
        Assert.Equal(["vbcf-protein-chemistry", "jenkins-vbc-test", "feat%2Fx"], path.Names);
        Assert.Equal("job/vbcf-protein-chemistry/job/jenkins-vbc-test/job/feat%252Fx", path.UrlPath);
        Assert.Equal("vbcf-protein-chemistry/jenkins-vbc-test/feat%2Fx", path.FullName);
    }

    [Fact]
    public void JobUrlIsParsedBackToNames()
    {
        var path = JobPath.Parse("https://jenkins.example.org/job/team/job/repo/job/feat%252Fx/12/console");
        Assert.Equal(["team", "repo", "feat%2Fx"], path.Names);
        Assert.Equal(JobPath.Parse("team/repo", "feat/x").UrlPath, path.UrlPath);
    }

    [Fact]
    public void SlashesAroundTheJobAreIgnored() =>
        Assert.Equal(["a", "b"], JobPath.Parse("/a/b/").Names);

    [Fact]
    public void EmptyJobIsRefused() =>
        Assert.Equal(JenkinsFailure.InvalidArgument, Assert.Throws<JenkinsException>(() => JobPath.Parse(" ")).Failure);

    [Fact]
    public void DisplayDecodesBranchJobNames() => Assert.Equal("feat/x", JobPath.Display("feat%2Fx"));
}

public sealed class JenkinsClientTests
{
    private static readonly Uri Base = new("https://jenkins.example.org/");

    private const string FreestyleJob = """{"_class":"org.jenkinsci.plugins.workflow.job.WorkflowJob","fullName":"team/repo/main","property":[]}""";

    [Fact]
    public void QueueIdIsTakenFromTheLocationHeader()
    {
        Assert.Equal(1234, JenkinsClient.QueueIdFromLocation(new Uri("https://jenkins.example.org/queue/item/1234/")));
        Assert.Null(JenkinsClient.QueueIdFromLocation(new Uri("https://jenkins.example.org/job/x/")));
    }

    [Theory]
    [InlineData("org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject", "multibranch")]
    [InlineData("jenkins.branch.OrganizationFolder", "organization folder")]
    [InlineData("com.cloudbees.hudson.plugins.folder.Folder", "folder")]
    [InlineData("org.jenkinsci.plugins.workflow.job.WorkflowJob", "pipeline")]
    [InlineData("hudson.model.Something", "Something")]
    public void KindOfJobClasses(string jenkinsClass, string kind) => Assert.Equal(kind, JenkinsClient.Kind(jenkinsClass));

    [Fact]
    public async Task WithATokenRequestsUseBasicAuthAndNoCrumb()
    {
        var fake = new FakeJenkins()
            .OnJson(HttpMethod.Get, "/job/team/job/repo/job/main/api/json", FreestyleJob)
            .On(HttpMethod.Post, "/job/team/job/repo/job/main/build", _ => Located("https://jenkins.example.org/queue/item/7/"))
            .OnJson(HttpMethod.Get, "/queue/item/7/api/json", """{"id":7,"executable":{"number":42,"url":"https://jenkins.example.org/job/team/job/repo/job/main/42/"}}""");
        using var client = new JenkinsClient("t", Base, "alice", "s3cret", fake);

        var started = await client.StartBuildAsync(JobPath.Parse("team/repo/main"), waitForStart: TimeSpan.FromSeconds(1));

        Assert.Equal(42, started.BuildNumber);
        Assert.Equal(7, started.QueueId);
        var post = fake.Requests.Single(r => r.Method == HttpMethod.Post);
        Assert.Equal("Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes("alice:s3cret")), post.Authorization);
        Assert.Null(post.Crumb);
        Assert.DoesNotContain(fake.Requests, r => r.PathAndQuery.StartsWith("/crumbIssuer", StringComparison.Ordinal));
    }

    [Fact]
    public async Task AnonymousPostsCarryACrumb()
    {
        var fake = new FakeJenkins()
            .OnJson(HttpMethod.Get, "/crumbIssuer/api/json", """{"crumbRequestField":"Jenkins-Crumb","crumb":"abc"}""")
            .OnJson(HttpMethod.Get, "/job/team/job/repo/api/json", """{"_class":"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject"}""")
            .On(HttpMethod.Post, "/job/team/job/repo/build", _ => new HttpResponseMessage(HttpStatusCode.Found));
        using var client = new JenkinsClient("t", Base, null, null, fake);

        await client.ScanAsync(JobPath.Parse("team/repo"));

        var post = fake.Requests.Single(r => r.Method == HttpMethod.Post);
        Assert.Equal("abc", post.Crumb);
        Assert.Null(post.Authorization);
    }

    [Fact]
    public async Task ParametersGoThroughBuildWithParameters()
    {
        var fake = new FakeJenkins()
            .OnJson(HttpMethod.Get, "/job/p/api/json", """
                {"_class":"org.jenkinsci.plugins.workflow.job.WorkflowJob","property":[{"parameterDefinitions":[
                  {"name":"AGENT_LABEL","type":"StringParameterDefinition","defaultParameterValue":{"value":""}},
                  {"name":"SECRET","type":"PasswordParameterDefinition","defaultParameterValue":{"value":"xx"}}]}]}
                """)
            .On(HttpMethod.Post, "/job/p/buildWithParameters", _ => Located("https://jenkins.example.org/queue/item/9/"))
            .OnJson(HttpMethod.Get, "/queue/item/9/api/json", """{"id":9,"why":"Waiting for next available executor"}""");
        using var client = new JenkinsClient("t", Base, "u", "t", fake);

        var job = await client.GetJobAsync(JobPath.Parse("p"));
        Assert.Equal("<masked>", job.Parameters.Single(p => p.Name == "SECRET").DefaultValue);

        var started = await client.StartBuildAsync(JobPath.Parse("p"), new Dictionary<string, string> { ["AGENT_LABEL"] = "singularity" }, TimeSpan.Zero);

        Assert.Null(started.BuildNumber);
        Assert.Equal("Waiting for next available executor", started.Why);
        Assert.Equal("AGENT_LABEL=singularity", fake.Requests.Single(r => r.Method == HttpMethod.Post).Body);
    }

    [Fact]
    public async Task ParametersForAJobWithoutDeclaredParametersAreRefused()
    {
        var fake = new FakeJenkins().OnJson(HttpMethod.Get, "/job/p/api/json", FreestyleJob);
        using var client = new JenkinsClient("t", Base, "u", "t", fake);

        var e = await Assert.ThrowsAsync<JenkinsException>(() =>
            client.StartBuildAsync(JobPath.Parse("p"), new Dictionary<string, string> { ["X"] = "1" }));
        Assert.Equal(JenkinsFailure.InvalidArgument, e.Failure);
        Assert.Contains("first build", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task StartingAMultibranchJobAsksForTheBranch()
    {
        var fake = new FakeJenkins().OnJson(HttpMethod.Get, "/job/team/job/repo/api/json",
            """{"_class":"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject"}""");
        using var client = new JenkinsClient("t", Base, "u", "t", fake);

        var e = await Assert.ThrowsAsync<JenkinsException>(() => client.StartBuildAsync(JobPath.Parse("team/repo")));
        Assert.Contains("name the branch", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ConsoleLogFiltersThenTails()
    {
        var log = string.Join('\n', Enumerable.Range(1, 10).Select(i => i % 2 == 0 ? $"even {i}" : $"odd {i}"));
        var fake = new FakeJenkins()
            .OnJson(HttpMethod.Get, "/job/j/lastBuild/api/json", """{"number":5,"building":false,"result":"SUCCESS"}""")
            .On(HttpMethod.Get, "/job/j/5/consoleText", _ => FakeJenkins.Text(log));
        using var client = new JenkinsClient("t", Base, "u", "t", fake);

        var tail = await client.GetConsoleLogAsync(JobPath.Parse("j"), pattern: "^even", tailLines: 2);

        Assert.Equal(5, tail.Number);
        Assert.Equal(10, tail.TotalLines);
        Assert.Equal(5, tail.MatchingLines);
        Assert.Equal("even 8\neven 10", tail.Text);
        Assert.True(tail.Truncated);

        var head = await client.GetConsoleLogAsync(JobPath.Parse("j"), tailLines: 2, fromStart: true);
        Assert.Equal("odd 1\neven 2", head.Text);
    }

    [Fact]
    public async Task BuildDetailsCarryCommitParametersAndCauses()
    {
        var fake = new FakeJenkins().OnJson(HttpMethod.Get, "/job/team/job/repo/job/main/3/api/json", """
            {"number":3,"result":"UNSTABLE","building":false,"timestamp":1762300000000,"duration":61000,"url":"u","builtOn":"",
             "actions":[{"causes":[{"shortDescription":"Started by user Alice"}]},
                        {"_class":"hudson.model.ParametersAction","parameters":[
                           {"_class":"hudson.model.StringParameterValue","name":"A","value":"1"},
                           {"_class":"hudson.model.BooleanParameterValue","name":"B","value":true},
                           {"_class":"hudson.model.PasswordParameterValue","name":"P"}]},
                        {"lastBuiltRevision":{"SHA1":"lib999","branch":[{"name":"develop"}]},"remoteUrls":["file:///lib"]},
                        {"lastBuiltRevision":{"SHA1":"abc123","branch":[{"name":"main"}]},"remoteUrls":["https://git/repo"]}],
             "artifacts":[{"relativePath":"out/report.xml"}]}
            """);
        using var client = new JenkinsClient("t", Base, "u", "t", fake);

        var build = await client.GetBuildAsync(JobPath.Parse("team/repo", "main"), 3);

        Assert.Equal("UNSTABLE", build.Result);
        Assert.Null(build.BuiltOn);
        Assert.Equal(TimeSpan.FromSeconds(61), build.Duration);
        Assert.Equal(["Started by user Alice"], build.Causes);
        Assert.Equal("1", build.Parameters["A"]);
        Assert.Equal("true", build.Parameters["B"]);
        Assert.Equal("<masked>", build.Parameters["P"]);
        Assert.Equal("abc123", build.Commit);
        Assert.Equal(["main"], build.Branches);
        Assert.Equal(2, build.Checkouts.Count);
        Assert.Equal(["out/report.xml"], build.Artifacts);
    }

    [Fact]
    public async Task MissingTestReportIsNull()
    {
        var fake = new FakeJenkins().OnJson(HttpMethod.Get, "/job/j/lastBuild/api/json", """{"number":1}""");
        using var client = new JenkinsClient("t", Base, "u", "t", fake);

        Assert.Null(await client.GetTestReportAsync(JobPath.Parse("j")));
    }

    [Fact]
    public async Task TestReportListsOnlyFailures()
    {
        var fake = new FakeJenkins()
            .OnJson(HttpMethod.Get, "/job/j/lastBuild/api/json", """{"number":2}""")
            .OnJson(HttpMethod.Get, "/job/j/2/testReport/api/json", """
                {"passCount":1,"failCount":1,"skipCount":0,"suites":[{"cases":[
                  {"className":"C","name":"ok","status":"PASSED","duration":0.1},
                  {"className":"C","name":"bad","status":"REGRESSION","duration":0.2,"errorDetails":"boom"}]}]}
                """);
        using var client = new JenkinsClient("t", Base, "u", "t", fake);

        var report = await client.GetTestReportAsync(JobPath.Parse("j"));

        Assert.NotNull(report);
        Assert.Equal(1, report.Failed);
        Assert.Equal("bad", Assert.Single(report.Failures).Name);
    }

    [Fact]
    public async Task UnauthorizedSaysWhoWasRejected()
    {
        var fake = new FakeJenkins().On(HttpMethod.Get, "/whoAmI/api/json", _ => FakeJenkins.Text("", HttpStatusCode.Unauthorized));
        using var client = new JenkinsClient("vbc", Base, "alice", "wrong", fake);

        var e = await Assert.ThrowsAsync<JenkinsException>(() => client.WhoAmIAsync());
        Assert.Equal(JenkinsFailure.Unauthorized, e.Failure);
        Assert.Contains("as alice", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task WaitReturnsTheRunningBuildAtTheTimeout()
    {
        var fake = new FakeJenkins().OnJson(HttpMethod.Get, "/job/j/4/api/json", """{"number":4,"building":true}""");
        using var client = new JenkinsClient("t", Base, "u", "t", fake);

        var build = await client.WaitForBuildAsync(JobPath.Parse("j"), 4, TimeSpan.FromMilliseconds(50), TimeSpan.FromMilliseconds(10));
        Assert.True(build.Building);
    }

    private static HttpResponseMessage Located(string location)
    {
        var response = new HttpResponseMessage(HttpStatusCode.Created);
        response.Headers.Location = new Uri(location);
        return response;
    }
}

public sealed class SitesAndSecretsTests
{
    [Fact]
    public async Task EnvAndFileReferencesResolve()
    {
        var secrets = new SecretReferences();
        var name = "JENKINSCLIENT_TEST_" + Guid.NewGuid().ToString("N");
        Environment.SetEnvironmentVariable(name, "from-env");
        var file = Path.GetTempFileName();
        await File.WriteAllTextAsync(file, "  from-file \n");
        try
        {
            Assert.Equal("from-env", await secrets.ResolveAsync($"env:{name}"));
            Assert.Equal("from-file", await secrets.ResolveAsync($"file:{file}"));
        }
        finally
        {
            Environment.SetEnvironmentVariable(name, null);
            File.Delete(file);
        }
    }

    [Fact]
    public async Task ALiteralTokenIsRefused()
    {
        var e = await Assert.ThrowsAsync<JenkinsException>(() => new SecretReferences().ResolveAsync("11aabbccddeeff"));
        Assert.Equal(JenkinsFailure.Configuration, e.Failure);
    }

    [Fact]
    public void UsernameFallsBackToTheOsUserOnlyWithAToken()
    {
        Assert.Null(JenkinsSites.EffectiveUsername(new JenkinsSiteOptions { BaseUrl = "http://x/" }));
        Assert.Equal(Environment.UserName, JenkinsSites.EffectiveUsername(new JenkinsSiteOptions { BaseUrl = "http://x/", TokenReference = "env:X" }));
        Assert.Equal("bob", JenkinsSites.EffectiveUsername(new JenkinsSiteOptions { BaseUrl = "http://x/", Username = "bob", TokenReference = "env:X" }));
    }

    [Fact]
    public void DefaultSite()
    {
        var one = new JenkinsSites(new JenkinsClientOptions { Sites = new(StringComparer.OrdinalIgnoreCase) { ["only"] = new() { BaseUrl = "http://x/" } } });
        Assert.Equal("only", one.DefaultSiteName);

        var two = new JenkinsSites(new JenkinsClientOptions
        {
            Sites = new(StringComparer.OrdinalIgnoreCase) { ["a"] = new() { BaseUrl = "http://a/" }, ["b"] = new() { BaseUrl = "http://b/" } },
        });
        Assert.Equal(JenkinsFailure.Configuration, Assert.Throws<JenkinsException>(() => two.DefaultSiteName).Failure);
    }

    [Fact]
    public async Task UnknownSiteIsRefused()
    {
        using var sites = new JenkinsSites(new JenkinsClientOptions { Sites = new(StringComparer.OrdinalIgnoreCase) { ["a"] = new() { BaseUrl = "http://a/" } } });
        var e = await Assert.ThrowsAsync<JenkinsException>(() => sites.GetAsync("nope"));
        Assert.Contains("Configured: a", e.Message, StringComparison.Ordinal);
    }
}
