namespace Xen.JenkinsClient;

public sealed record WhoAmI(
    string Site,
    string BaseUrl,
    string? ConfiguredUser,
    string Name,
    bool Authenticated,
    bool Anonymous,
    IReadOnlyList<string> Authorities);

public sealed record BuildSummary(int Number, string? Result, bool Building, DateTimeOffset? Started, TimeSpan? Duration, string Url);

/// <summary>A child job: a job in a folder, or a branch / tag / pull request job of a multibranch job.</summary>
public sealed record JobSummary(string Name, string JobName, string Kind, string? Color, string Url, BuildSummary? LastBuild);

public sealed record ParameterDefinition(string Name, string? Type, string? DefaultValue, string? Description);

public sealed record JobInfo(
    string FullName,
    string DisplayName,
    string Kind,
    string? Color,
    string Url,
    bool Buildable,
    bool InQueue,
    IReadOnlyList<ParameterDefinition> Parameters,
    IReadOnlyList<JobSummary> Children,
    IReadOnlyList<BuildSummary> Builds);

public sealed record BuildInfo(
    string Job,
    int Number,
    string? Result,
    bool Building,
    DateTimeOffset? Started,
    TimeSpan? Duration,
    TimeSpan? EstimatedDuration,
    string Url,
    string? BuiltOn,
    string? DisplayName,
    string? Description,
    IReadOnlyList<string> Causes,
    IReadOnlyDictionary<string, string?> Parameters,
    string? Commit,
    IReadOnlyList<string> Branches,
    IReadOnlyList<Checkout> Checkouts,
    IReadOnlyList<string> Artifacts);

/// <summary>One git checkout of a build (the job's repository, or a shared library).</summary>
public sealed record Checkout(string? Remote, string Commit, IReadOnlyList<string> Branches);

public sealed record ConsoleLog(string Job, int Number, bool Building, int TotalLines, int MatchingLines, int ReturnedLines, bool Truncated, string Text);

public sealed record TestCaseResult(string ClassName, string Name, string Status, double? DurationSeconds, string? ErrorDetails);

public sealed record TestReport(string Job, int Number, int Passed, int Failed, int Skipped, IReadOnlyList<TestCaseResult> Failures, bool FailuresTruncated);

public sealed record QueueItem(
    long Id,
    string? Task,
    string? TaskUrl,
    string? Why,
    DateTimeOffset? InQueueSince,
    bool Blocked,
    bool Buildable,
    bool Stuck,
    bool Cancelled,
    int? BuildNumber,
    string? BuildUrl);

/// <summary>A build that was asked for. <see cref="BuildNumber"/> is null while it still waits in the queue (see <see cref="Why"/>).</summary>
public sealed record StartedBuild(string Job, long? QueueId, int? BuildNumber, string? BuildUrl, string? Why);

public sealed record ScanLog(string Job, int TotalLines, int ReturnedLines, string Text);
