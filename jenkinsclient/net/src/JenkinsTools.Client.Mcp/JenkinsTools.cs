using System.ComponentModel;
using Microsoft.Extensions.Logging;
using ModelContextProtocol;
using ModelContextProtocol.Server;

namespace JenkinsTools.Client.Mcp;

/// <summary>MCP face of <see cref="JenkinsSites"/>: argument parsing and error translation only.</summary>
[McpServerToolType]
public sealed class JenkinsTools(JenkinsSites sites, ILogger<JenkinsTools> logger)
{
    private const string SiteDescription = "Configured site name (see list_sites). Omit for the default site.";
    private const string JobDescription = "Job by folder/job names separated by '/', e.g. 'team-folder/repo' (a multibranch job), or a job URL of the site.";
    private const string BranchDescription = "For a multibranch job: the branch, tag or PR job name, taken literally ('feat/x' is fine). Omit when 'job' already names the job.";
    private const string NumberDescription = "Build number. Omit for the last build.";
    private const int MaxWaitSeconds = 240;

    [McpServerTool(Name = "list_sites", ReadOnly = true, Idempotent = true),
     Description("The configured Jenkins sites: name, URL, the user each authenticates as (null: anonymous), which one is the default.")]
    public object ListSites()
    {
        string? defaultSite;
        try
        {
            defaultSite = sites.DefaultSiteName;
        }
        catch (JenkinsException)
        {
            defaultSite = null;
        }

        return sites.Sites.Select(s => new
        {
            site = s.Key,
            url = s.Value.BaseUrl,
            user = JenkinsSites.EffectiveUsername(s.Value),
            tokenReference = s.Value.TokenReference,
            isDefault = string.Equals(s.Key, defaultSite, StringComparison.OrdinalIgnoreCase),
        }).ToList();
    }

    [McpServerTool(Name = "whoami", ReadOnly = true, Idempotent = true),
     Description("Who the site sees: the authenticated user and its authorities, or anonymous. Use it to check a token.")]
    public Task<WhoAmI> WhoAmI([Description(SiteDescription)] string? site = null, CancellationToken ct = default) =>
        RunAsync("whoami", site, c => c.WhoAmIAsync(ct), ct);

    [McpServerTool(Name = "list_jobs", ReadOnly = true, Idempotent = true),
     Description("The jobs at the site root or in a folder, with kind (folder, multibranch, pipeline, ...), colour and last build.")]
    public Task<IReadOnlyList<JobSummary>> ListJobs(
        [Description("Folder by names separated by '/'. Omit for the site root.")] string? folder = null,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync("list_jobs", site, c => c.ListJobsAsync(string.IsNullOrWhiteSpace(folder) ? null : JobPath.Parse(folder), ct), ct);

    [McpServerTool(Name = "get_job", ReadOnly = true, Idempotent = true),
     Description("A job, folder or multibranch job: its children (for a multibranch job its branches, tags and PRs with their last builds), its last builds and its parameters.")]
    public Task<JobInfo> GetJob(
        [Description(JobDescription)] string job,
        [Description(BranchDescription)] string? branch = null,
        [Description("How many recent builds to list (0-100, default 10).")] int builds = 10,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync("get_job", site, c => c.GetJobAsync(JobPath.Parse(job, branch), builds, ct), ct);

    [McpServerTool(Name = "get_build", ReadOnly = true, Idempotent = true),
     Description("One build: result (null while running), times, node, causes, parameters (secrets masked), the commit and branch of the job's own repository, every git checkout (shared libraries too), artifacts.")]
    public Task<BuildInfo> GetBuild(
        [Description(JobDescription)] string job,
        [Description(BranchDescription)] string? branch = null,
        [Description(NumberDescription)] int? number = null,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync("get_build", site, c => c.GetBuildAsync(JobPath.Parse(job, branch), number, ct), ct);

    [McpServerTool(Name = "console_log", ReadOnly = true, Idempotent = true),
     Description("The console log of a build: the last tail_lines lines (or the first, with from_start), optionally only lines matching a .NET regular expression. Says how many lines there are in total and whether output was cut.")]
    public Task<ConsoleLog> ConsoleLog(
        [Description(JobDescription)] string job,
        [Description(BranchDescription)] string? branch = null,
        [Description(NumberDescription)] int? number = null,
        [Description("Only lines matching this .NET regular expression.")] string? pattern = null,
        [Description("At most this many lines (1-20000, default 400).")] int tail_lines = 400,
        [Description("Take the first lines instead of the last.")] bool from_start = false,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync("console_log", site, c => c.GetConsoleLogAsync(JobPath.Parse(job, branch), number, pattern, tail_lines, from_start, ct), ct);

    [McpServerTool(Name = "test_report", ReadOnly = true, Idempotent = true),
     Description("The JUnit test report of a build: passed/failed/skipped counts and the failing cases with their error details. Null when the build published none.")]
    public Task<object> TestReport(
        [Description(JobDescription)] string job,
        [Description(BranchDescription)] string? branch = null,
        [Description(NumberDescription)] int? number = null,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync<object>("test_report", site, async c =>
            await c.GetTestReportAsync(JobPath.Parse(job, branch), number, 50, ct) as object
            ?? new { report = (object?)null, note = "this build published no test report" }, ct);

    [McpServerTool(Name = "queue", ReadOnly = true, Idempotent = true),
     Description("The build queue of the site: what waits, since when, and why (e.g. no agent with a label, waiting for a busy executor).")]
    public Task<IReadOnlyList<QueueItem>> Queue([Description(SiteDescription)] string? site = null, CancellationToken ct = default) =>
        RunAsync("queue", site, c => c.GetQueueAsync(ct), ct);

    [McpServerTool(Name = "scan_log", ReadOnly = true, Idempotent = true),
     Description("The log of the last branch/tag discovery scan of a multibranch job or organization folder: which branches met the criteria and whether builds were triggered.")]
    public Task<ScanLog> ScanLog(
        [Description(JobDescription)] string job,
        [Description("At most this many lines from the end (default 200).")] int tail_lines = 200,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync("scan_log", site, c => c.GetScanLogAsync(JobPath.Parse(job), tail_lines, ct), ct);

    [McpServerTool(Name = "scan", Destructive = false, Idempotent = true),
     Description("Starts a branch/tag discovery scan of a multibranch job or organization folder (Jenkins' 'Scan Repository Now'). Returns at once; read scan_log or get_job a little later.")]
    public Task<object> Scan(
        [Description(JobDescription)] string job,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync<object>("scan", site, async c =>
        {
            var path = JobPath.Parse(job);
            await c.ScanAsync(path, ct);
            return new { scanned = path.FullName, note = "scan started; new branches appear in get_job within a minute (scan_log shows the details)" };
        }, ct);

    [McpServerTool(Name = "start_build", Destructive = false, Idempotent = false),
     Description("Starts a build of a job, branch or tag, with parameters (NAME: value; parameters not given keep their defaults). This runs the job's pipeline as the configured user, including whatever it deploys: start only builds the user asked for. Waits up to wait_seconds for the build number; then use wait_build.")]
    public Task<StartedBuild> StartBuild(
        [Description(JobDescription)] string job,
        [Description(BranchDescription)] string? branch = null,
        [Description("Build parameters NAME: value.")] Dictionary<string, string>? parameters = null,
        [Description("How long to wait for the build to leave the queue (0-120 s, default 30).")] int wait_seconds = 30,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync("start_build", site, c => c.StartBuildAsync(JobPath.Parse(job, branch), parameters,
            TimeSpan.FromSeconds(Math.Clamp(wait_seconds, 0, 120)), ct), ct);

    [McpServerTool(Name = "wait_build", ReadOnly = true, Idempotent = true),
     Description("Waits up to timeout_seconds (max 240) for a build to finish and returns it; if it is still running, the result has building=true: call again.")]
    public Task<BuildInfo> WaitBuild(
        [Description(JobDescription)] string job,
        [Description(BranchDescription)] string? branch = null,
        [Description(NumberDescription)] int? number = null,
        [Description("Default and max 240.")] int timeout_seconds = MaxWaitSeconds,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync("wait_build", site, c => c.WaitForBuildAsync(JobPath.Parse(job, branch), number,
            TimeSpan.FromSeconds(Math.Clamp(timeout_seconds, 1, MaxWaitSeconds)), ct: ct), ct);

    [McpServerTool(Name = "abort_build", Destructive = true, Idempotent = true),
     Description("Aborts a running build (Jenkins' stop). A finished build is left unchanged. Confirm with the user first.")]
    public Task<BuildInfo> AbortBuild(
        [Description(JobDescription)] string job,
        [Description("Build number (required).")] int number,
        [Description(BranchDescription)] string? branch = null,
        [Description(SiteDescription)] string? site = null,
        CancellationToken ct = default) =>
        RunAsync("abort_build", site, c => c.AbortBuildAsync(JobPath.Parse(job, branch), number, ct), ct);

    private async Task<T> RunAsync<T>(string tool, string? site, Func<JenkinsClient, Task<T>> action, CancellationToken ct)
    {
        try
        {
            var client = await sites.GetAsync(site, ct);
            return await action(client);
        }
        catch (JenkinsException e)
        {
            logger.LogWarning("Tool {Tool} failed ({Failure}): {Message}", tool, e.Failure, e.Message);
            throw new McpException($"{e.Failure}: {e.Message}", e);
        }
        catch (System.Text.RegularExpressions.RegexParseException e)
        {
            throw new McpException($"InvalidArgument: pattern is not a valid regular expression: {e.Message}", e);
        }
    }
}
