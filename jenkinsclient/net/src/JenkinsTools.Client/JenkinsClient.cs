using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace JenkinsTools.Client;

/// <summary>
/// One Jenkins, through its public REST API only (<c>.../api/json</c>, <c>consoleText</c>, <c>build</c>, <c>stop</c>, the
/// queue): never job configuration, the script console or credentials. Authenticates with HTTP Basic (user + API token),
/// which Jenkins exempts from CSRF crumbs; without a token it is anonymous and fetches a crumb for each POST.
/// </summary>
public sealed class JenkinsClient : IDisposable
{
    private static readonly JsonDocumentOptions JsonOptions = new() { AllowTrailingCommas = true };

    private readonly HttpClient _http;
    private readonly bool _authenticated;

    public JenkinsClient(string site, Uri baseUrl, string? username, string? token, TimeSpan? timeout = null)
        : this(site, baseUrl, username, token, new HttpClientHandler { CookieContainer = new CookieContainer(), AllowAutoRedirect = false }, timeout)
    {
    }

    /// <summary>With a handler of the caller's (tests). The handler must keep cookies for anonymous POSTs (crumbs are per session).</summary>
    public JenkinsClient(string site, Uri baseUrl, string? username, string? token, HttpMessageHandler handler, TimeSpan? timeout = null)
    {
        Site = site;
        BaseUrl = new Uri(baseUrl.AbsoluteUri.TrimEnd('/') + "/");
        Username = token is null ? null : username;
        _authenticated = token is not null;
        _http = new HttpClient(handler) { BaseAddress = BaseUrl, Timeout = timeout ?? TimeSpan.FromSeconds(100) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd($"JenkinsTools.Client/{typeof(JenkinsClient).Assembly.GetName().Version}");
        if (token is not null)
        {
            _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic",
                Convert.ToBase64String(Encoding.UTF8.GetBytes($"{username}:{token}")));
        }
    }

    public string Site { get; }

    public Uri BaseUrl { get; }

    /// <summary>The user this client authenticates as; null when anonymous.</summary>
    public string? Username { get; }

    // ---- who ----

    public async Task<WhoAmI> WhoAmIAsync(CancellationToken ct = default)
    {
        using var doc = await GetJsonAsync("whoAmI/api/json", ct);
        var root = doc.RootElement;
        return new WhoAmI(
            Site,
            BaseUrl.AbsoluteUri,
            Username,
            Str(root, "name") ?? "?",
            Bool(root, "authenticated"),
            Bool(root, "anonymous"),
            root.TryGetProperty("authorities", out var a) && a.ValueKind == JsonValueKind.Array
                ? a.EnumerateArray().Select(x => x.GetString() ?? "").Where(x => x.Length > 0).ToList()
                : []);
    }

    // ---- jobs ----

    /// <summary>A job, folder or multibranch job: its children (jobs, branches, tags), its last builds, its parameters.</summary>
    public async Task<JobInfo> GetJobAsync(JobPath job, int builds = 10, CancellationToken ct = default)
    {
        builds = Math.Clamp(builds, 0, 100);
        var tree = $"_class,fullName,displayName,color,url,buildable,inQueue,"
                   + "property[parameterDefinitions[name,type,description,defaultParameterValue[value]]],"
                   + $"jobs[_class,name,color,url,lastBuild[{BuildSummaryFields}]],"
                   + $"builds[{BuildSummaryFields}]{{0,{builds}}}";
        using var doc = await GetJsonAsync($"{job.UrlPath}/api/json?tree={tree}", ct);
        var root = doc.RootElement;
        return new JobInfo(
            Str(root, "fullName") ?? job.FullName,
            Str(root, "displayName") ?? job.Names[^1],
            Kind(Str(root, "_class")),
            Str(root, "color"),
            Str(root, "url") ?? "",
            Bool(root, "buildable"),
            Bool(root, "inQueue"),
            ParseParameterDefinitions(root),
            Array(root, "jobs").Select(ParseJobSummary).ToList(),
            Array(root, "builds").Select(ParseBuildSummary).ToList());
    }

    /// <summary>The jobs of the site root, or of a folder.</summary>
    public async Task<IReadOnlyList<JobSummary>> ListJobsAsync(JobPath? folder, CancellationToken ct = default)
    {
        var prefix = folder is null ? "" : folder.UrlPath + "/";
        using var doc = await GetJsonAsync($"{prefix}api/json?tree=jobs[_class,name,color,url,lastBuild[{BuildSummaryFields}]]", ct);
        return Array(doc.RootElement, "jobs").Select(ParseJobSummary).ToList();
    }

    // ---- builds ----

    /// <summary>A build; <paramref name="number"/> null = the last one.</summary>
    public async Task<BuildInfo> GetBuildAsync(JobPath job, int? number = null, CancellationToken ct = default)
    {
        const string tree = "number,result,building,timestamp,duration,estimatedDuration,url,builtOn,displayName,description,"
                            + "actions[_class,causes[shortDescription],parameters[_class,name,value],lastBuiltRevision[SHA1,branch[name]],remoteUrls],"
                            + "artifacts[relativePath]";
        using var doc = await GetJsonAsync($"{BuildPath(job, number)}/api/json?tree={tree}", ct);
        var root = doc.RootElement;
        var causes = new List<string>();
        var parameters = new Dictionary<string, string?>(StringComparer.Ordinal);
        var checkouts = new List<Checkout>();
        foreach (var action in Array(root, "actions"))
        {
            foreach (var cause in Array(action, "causes"))
            {
                if (Str(cause, "shortDescription") is { } text)
                {
                    causes.Add(text);
                }
            }

            foreach (var parameter in Array(action, "parameters"))
            {
                if (Str(parameter, "name") is not { } name)
                {
                    continue;
                }

                // a password parameter's value never leaves Jenkins; anything that still looks secret is masked
                var secret = (Str(parameter, "_class") ?? "").Contains("Password", StringComparison.Ordinal);
                parameters[name] = secret ? "<masked>" : Scalar(parameter, "value");
            }

            // one per git checkout of the build: shared libraries first, the job's own repository (checkout scm) later
            if (action.TryGetProperty("lastBuiltRevision", out var revision) && revision.ValueKind == JsonValueKind.Object
                && Str(revision, "SHA1") is { } sha)
            {
                checkouts.Add(new Checkout(
                    Array(action, "remoteUrls").Select(u => u.GetString()).OfType<string>().FirstOrDefault(),
                    sha,
                    Array(revision, "branch").Select(b => Str(b, "name")).OfType<string>().ToList()));
            }
        }

        return new BuildInfo(
            job.FullName,
            Int(root, "number") ?? 0,
            Str(root, "result"),
            Bool(root, "building"),
            Time(root, "timestamp"),
            Span(root, "duration"),
            Span(root, "estimatedDuration"),
            Str(root, "url") ?? "",
            Str(root, "builtOn") is { Length: > 0 } node ? node : null,
            Str(root, "displayName"),
            Str(root, "description"),
            causes,
            parameters,
            OwnCheckout(job, checkouts)?.Commit,
            OwnCheckout(job, checkouts)?.Branches ?? [],
            checkouts,
            Array(root, "artifacts").Select(a => Str(a, "relativePath")).OfType<string>().ToList());
    }

    /// <summary>
    /// The console log, optionally only lines matching <paramref name="pattern"/> (a .NET regular expression), at most the
    /// last <paramref name="tailLines"/> of them (or the first, with <paramref name="fromStart"/>).
    /// </summary>
    public async Task<ConsoleLog> GetConsoleLogAsync(JobPath job, int? number = null, string? pattern = null, int tailLines = 400,
        bool fromStart = false, CancellationToken ct = default)
    {
        tailLines = Math.Clamp(tailLines, 1, 20000);
        var build = await GetBuildAsync(job, number, ct);
        var filter = pattern is { Length: > 0 }
            ? new Regex(pattern, RegexOptions.CultureInvariant, TimeSpan.FromSeconds(2))
            : null;

        using var response = await SendAsync(HttpMethod.Get, $"{BuildPath(job, build.Number)}/consoleText", null, ct);
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var reader = new StreamReader(stream, Encoding.UTF8);
        var kept = new Queue<string>();
        int total = 0, matching = 0;
        while (await reader.ReadLineAsync(ct) is { } line)
        {
            total++;
            if (filter is not null && !filter.IsMatch(line))
            {
                continue;
            }

            matching++;
            if (fromStart)
            {
                if (kept.Count < tailLines)
                {
                    kept.Enqueue(line);
                }
            }
            else
            {
                kept.Enqueue(line);
                if (kept.Count > tailLines)
                {
                    kept.Dequeue();
                }
            }
        }

        return new ConsoleLog(job.FullName, build.Number, build.Building, total, matching, kept.Count, matching > kept.Count,
            string.Join('\n', kept));
    }

    /// <summary>The JUnit report of a build, with its failing cases; null when the build has none.</summary>
    public async Task<TestReport?> GetTestReportAsync(JobPath job, int? number = null, int maxFailures = 50, CancellationToken ct = default)
    {
        var build = await GetBuildAsync(job, number, ct);
        JsonDocument doc;
        try
        {
            doc = await GetJsonAsync($"{BuildPath(job, build.Number)}/testReport/api/json"
                                     + "?tree=passCount,failCount,skipCount,suites[cases[className,name,status,duration,errorDetails]]", ct);
        }
        catch (JenkinsException e) when (e.Failure == JenkinsFailure.NotFound)
        {
            return null;
        }

        using (doc)
        {
            var root = doc.RootElement;
            var failures = new List<TestCaseResult>();
            var failed = 0;
            foreach (var testCase in Array(root, "suites").SelectMany(s => Array(s, "cases")))
            {
                var status = Str(testCase, "status") ?? "";
                if (status is not ("FAILED" or "REGRESSION"))
                {
                    continue;
                }

                failed++;
                if (failures.Count < maxFailures)
                {
                    var details = Str(testCase, "errorDetails");
                    failures.Add(new TestCaseResult(Str(testCase, "className") ?? "", Str(testCase, "name") ?? "", status,
                        testCase.TryGetProperty("duration", out var d) && d.ValueKind == JsonValueKind.Number ? d.GetDouble() : null,
                        details is { Length: > 1000 } ? details[..1000] + " ..." : details));
                }
            }

            return new TestReport(job.FullName, build.Number, Int(root, "passCount") ?? 0, Int(root, "failCount") ?? failed,
                Int(root, "skipCount") ?? 0, failures, failed > failures.Count);
        }
    }

    // ---- queue ----

    public async Task<IReadOnlyList<QueueItem>> GetQueueAsync(CancellationToken ct = default)
    {
        using var doc = await GetJsonAsync("queue/api/json?tree=items[id,why,inQueueSince,blocked,buildable,stuck,task[name,url]]", ct);
        return Array(doc.RootElement, "items").Select(ParseQueueItem).ToList();
    }

    public async Task<QueueItem> GetQueueItemAsync(long id, CancellationToken ct = default)
    {
        using var doc = await GetJsonAsync($"queue/item/{id}/api/json?tree=id,why,inQueueSince,blocked,buildable,stuck,cancelled,task[name,url],executable[number,url]", ct);
        return ParseQueueItem(doc.RootElement);
    }

    // ---- actions (the only writes) ----

    /// <summary>
    /// Asks for a build. With <paramref name="parameters"/>, or when the job declares parameters, through
    /// <c>buildWithParameters</c> (parameters not given keep their defaults). Waits up to <paramref name="waitForStart"/>
    /// for the build number; past that the result carries the queue's reason instead.
    /// </summary>
    public async Task<StartedBuild> StartBuildAsync(JobPath job, IReadOnlyDictionary<string, string>? parameters = null,
        TimeSpan? waitForStart = null, CancellationToken ct = default)
    {
        var info = await GetJobAsync(job, builds: 0, ct);
        if (info.Kind is "multibranch" or "organization folder" or "folder")
        {
            throw new JenkinsException(JenkinsFailure.InvalidArgument,
                $"{job} is a {info.Kind}: name the branch or tag to build (use scan to discover new ones).");
        }

        var hasParameters = parameters is { Count: > 0 };
        if (hasParameters && info.Parameters.Count == 0)
        {
            throw new JenkinsException(JenkinsFailure.InvalidArgument,
                $"{job} declares no parameters yet. A Jenkinsfile's parameters are registered by its first build: start one without parameters, then again with them.");
        }

        HttpContent? content = null;
        string endpoint;
        if (info.Parameters.Count > 0)
        {
            endpoint = "buildWithParameters";
            content = new FormUrlEncodedContent(parameters ?? new Dictionary<string, string>());
        }
        else
        {
            endpoint = "build";
        }

        using var response = await SendAsync(HttpMethod.Post, $"{job.UrlPath}/{endpoint}?delay=0sec", content, ct);
        var queueId = QueueIdFromLocation(response.Headers.Location);
        if (queueId is null)
        {
            return new StartedBuild(job.FullName, null, null, null, $"Jenkins accepted the request (HTTP {(int)response.StatusCode}) but named no queue item.");
        }

        var deadline = DateTimeOffset.UtcNow + (waitForStart ?? TimeSpan.FromSeconds(30));
        QueueItem item;
        while (true)
        {
            item = await GetQueueItemAsync(queueId.Value, ct);
            if (item.BuildNumber is not null || item.Cancelled || DateTimeOffset.UtcNow >= deadline)
            {
                break;
            }

            await Task.Delay(TimeSpan.FromSeconds(2), ct);
        }

        return new StartedBuild(job.FullName, queueId, item.BuildNumber, item.BuildUrl,
            item.Cancelled ? "cancelled in the queue" : item.BuildNumber is null ? item.Why ?? "waiting in the queue" : null);
    }

    /// <summary>Re-scans a multibranch job or organization folder (branch and tag discovery). Returns at once.</summary>
    public async Task ScanAsync(JobPath job, CancellationToken ct = default)
    {
        var info = await GetJobAsync(job, builds: 0, ct);
        if (info.Kind is not ("multibranch" or "organization folder"))
        {
            throw new JenkinsException(JenkinsFailure.InvalidArgument, $"{job} is a {info.Kind}; only a multibranch job or an organization folder can be scanned.");
        }

        using var _ = await SendAsync(HttpMethod.Post, $"{job.UrlPath}/build?delay=0", null, ct);
    }

    /// <summary>The log of the last scan of a multibranch job (<c>indexing</c>) or organization folder (<c>computation</c>).</summary>
    public async Task<ScanLog> GetScanLogAsync(JobPath job, int tailLines = 200, CancellationToken ct = default)
    {
        string text;
        try
        {
            text = await GetTextAsync($"{job.UrlPath}/indexing/consoleText", ct);
        }
        catch (JenkinsException e) when (e.Failure == JenkinsFailure.NotFound)
        {
            text = await GetTextAsync($"{job.UrlPath}/computation/consoleText", ct);
        }

        var lines = text.Replace("\r\n", "\n").Split('\n');
        var kept = lines.TakeLast(Math.Clamp(tailLines, 1, 5000)).ToList();
        return new ScanLog(job.FullName, lines.Length, kept.Count, string.Join('\n', kept));
    }

    /// <summary>Polls until the build has finished or <paramref name="timeout"/> has passed (then the running build is returned).</summary>
    public async Task<BuildInfo> WaitForBuildAsync(JobPath job, int? number, TimeSpan timeout, TimeSpan? pollInterval = null, CancellationToken ct = default)
    {
        var deadline = DateTimeOffset.UtcNow + timeout;
        var interval = pollInterval ?? TimeSpan.FromSeconds(5);
        while (true)
        {
            BuildInfo? build = null;
            try
            {
                build = await GetBuildAsync(job, number, ct);
            }
            catch (JenkinsException e) when (e.Failure == JenkinsFailure.NotFound && number is not null && DateTimeOffset.UtcNow < deadline)
            {
                // the build may not exist yet (just left the queue)
            }

            if (build is not null && (!build.Building || DateTimeOffset.UtcNow >= deadline))
            {
                return build;
            }

            if (DateTimeOffset.UtcNow >= deadline)
            {
                throw new JenkinsException(JenkinsFailure.Timeout, $"{job} #{number}: no such build after {timeout.TotalSeconds:0} s");
            }

            await Task.Delay(interval, ct);
        }
    }

    /// <summary>Aborts a running build (<c>stop</c>). A finished build is left as it is.</summary>
    public async Task<BuildInfo> AbortBuildAsync(JobPath job, int number, CancellationToken ct = default)
    {
        var build = await GetBuildAsync(job, number, ct);
        if (!build.Building)
        {
            return build;
        }

        using var _ = await SendAsync(HttpMethod.Post, $"{BuildPath(job, number)}/stop", null, ct);
        return await GetBuildAsync(job, number, ct);
    }

    public void Dispose() => _http.Dispose();

    // ---- plumbing ----

    private const string BuildSummaryFields = "number,result,building,timestamp,duration,url";

    private static string BuildPath(JobPath job, int? number) => $"{job.UrlPath}/{(number is null ? "lastBuild" : number.Value.ToString(System.Globalization.CultureInfo.InvariantCulture))}";

    /// <summary>
    /// The checkout of the job's own repository: in a branch job of a multibranch job the one whose branch is the job's
    /// name, otherwise the last one (library checkouts come first).
    /// </summary>
    internal static Checkout? OwnCheckout(JobPath job, IReadOnlyList<Checkout> checkouts)
    {
        var branch = JobPath.Display(job.Names[^1]);
        return checkouts.LastOrDefault(c => c.Branches.Any(b => b == branch || b.EndsWith("/" + branch, StringComparison.Ordinal)))
               ?? checkouts.LastOrDefault();
    }

    internal static long? QueueIdFromLocation(Uri? location)
    {
        if (location is null)
        {
            return null;
        }

        var match = Regex.Match(location.OriginalString, @"/queue/item/(\d+)/?$", RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
        return match.Success ? long.Parse(match.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture) : null;
    }

    private async Task<JsonDocument> GetJsonAsync(string relative, CancellationToken ct)
    {
        using var response = await SendAsync(HttpMethod.Get, relative, null, ct);
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        return await JsonDocument.ParseAsync(stream, JsonOptions, ct);
    }

    private async Task<string> GetTextAsync(string relative, CancellationToken ct)
    {
        using var response = await SendAsync(HttpMethod.Get, relative, null, ct);
        return await response.Content.ReadAsStringAsync(ct);
    }

    private async Task<HttpResponseMessage> SendAsync(HttpMethod method, string relative, HttpContent? content, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(method, relative) { Content = content };
        if (method == HttpMethod.Post && !_authenticated && await GetCrumbAsync(ct) is { } crumb)
        {
            request.Headers.TryAddWithoutValidation(crumb.Field, crumb.Value);
        }

        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        }
        catch (TaskCanceledException e) when (!ct.IsCancellationRequested)
        {
            throw new JenkinsException(JenkinsFailure.Timeout, $"{Site}: {method} {relative} timed out", inner: e);
        }
        catch (HttpRequestException e)
        {
            throw new JenkinsException(JenkinsFailure.Failed, $"{Site}: {method} {BaseUrl}{relative} failed: {e.Message}", inner: e);
        }

        // POSTs answer 201 (queued) or 302/303 (back to the job page): both mean accepted
        if (response.IsSuccessStatusCode || (method == HttpMethod.Post && (int)response.StatusCode is 301 or 302 or 303))
        {
            return response;
        }

        using (response)
        {
            var status = response.StatusCode;
            var who = Username is null ? "anonymously" : $"as {Username}";
            var url = $"{BaseUrl}{relative.Split('?')[0]}";
            throw status switch
            {
                HttpStatusCode.Unauthorized => new JenkinsException(JenkinsFailure.Unauthorized,
                    $"{Site}: 401 {who} for {url}: the user or the API token is wrong (or missing).", status),
                HttpStatusCode.Forbidden => new JenkinsException(JenkinsFailure.Forbidden,
                    $"{Site}: 403 {who} for {url}: no permission{(Username is null ? " (anonymous access is denied: configure a token)" : "")}.", status),
                HttpStatusCode.NotFound => new JenkinsException(JenkinsFailure.NotFound, $"{Site}: not found: {url}", status),
                _ => new JenkinsException(JenkinsFailure.Failed,
                    $"{Site}: HTTP {(int)status} for {method} {url}: {Shorten(await response.Content.ReadAsStringAsync(ct))}", status),
            };
        }
    }

    private async Task<(string Field, string Value)?> GetCrumbAsync(CancellationToken ct)
    {
        using var response = await _http.GetAsync("crumbIssuer/api/json", ct);
        if (!response.IsSuccessStatusCode)
        {
            return null; // CSRF protection off, or no crumb issuer: post without one
        }

        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct), JsonOptions);
        return Str(doc.RootElement, "crumbRequestField") is { } field && Str(doc.RootElement, "crumb") is { } value ? (field, value) : null;
    }

    private static string Shorten(string body)
    {
        var text = Regex.Replace(body, "<[^>]+>", " ", RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
        text = Regex.Replace(text, @"\s+", " ", RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1)).Trim();
        return text.Length > 300 ? text[..300] + " ..." : text;
    }

    private static JobSummary ParseJobSummary(JsonElement job)
    {
        var name = Str(job, "name") ?? "";
        return new JobSummary(JobPath.Display(name), name, Kind(Str(job, "_class")), Str(job, "color"), Str(job, "url") ?? "",
            job.TryGetProperty("lastBuild", out var last) && last.ValueKind == JsonValueKind.Object ? ParseBuildSummary(last) : null);
    }

    private static BuildSummary ParseBuildSummary(JsonElement build) =>
        new(Int(build, "number") ?? 0, Str(build, "result"), Bool(build, "building"), Time(build, "timestamp"), Span(build, "duration"), Str(build, "url") ?? "");

    private static IReadOnlyList<ParameterDefinition> ParseParameterDefinitions(JsonElement job) =>
        Array(job, "property")
            .SelectMany(p => Array(p, "parameterDefinitions"))
            .Select(d => new ParameterDefinition(
                Str(d, "name") ?? "",
                Str(d, "type"),
                d.TryGetProperty("defaultParameterValue", out var v) && v.ValueKind == JsonValueKind.Object
                    ? ((Str(d, "type") ?? "").Contains("Password", StringComparison.Ordinal) ? "<masked>" : Scalar(v, "value"))
                    : null,
                Str(d, "description")))
            .Where(d => d.Name.Length > 0)
            .ToList();

    private static QueueItem ParseQueueItem(JsonElement item)
    {
        JsonElement task = item.TryGetProperty("task", out var t) && t.ValueKind == JsonValueKind.Object ? t : default;
        JsonElement executable = item.TryGetProperty("executable", out var e) && e.ValueKind == JsonValueKind.Object ? e : default;
        return new QueueItem(
            item.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.Number ? id.GetInt64() : 0,
            task.ValueKind == JsonValueKind.Object ? JobPath.Display(Str(task, "name") ?? "") : null,
            task.ValueKind == JsonValueKind.Object ? Str(task, "url") : null,
            Str(item, "why"),
            Time(item, "inQueueSince"),
            Bool(item, "blocked"),
            Bool(item, "buildable"),
            Bool(item, "stuck"),
            Bool(item, "cancelled"),
            executable.ValueKind == JsonValueKind.Object ? Int(executable, "number") : null,
            executable.ValueKind == JsonValueKind.Object ? Str(executable, "url") : null);
    }

    /// <summary>Short kind of a Jenkins job class.</summary>
    internal static string Kind(string? jenkinsClass) => jenkinsClass switch
    {
        null => "unknown",
        _ when jenkinsClass.EndsWith("WorkflowMultiBranchProject", StringComparison.Ordinal) => "multibranch",
        _ when jenkinsClass.EndsWith("OrganizationFolder", StringComparison.Ordinal) => "organization folder",
        _ when jenkinsClass.EndsWith(".Folder", StringComparison.Ordinal) => "folder",
        _ when jenkinsClass.EndsWith("WorkflowJob", StringComparison.Ordinal) => "pipeline",
        _ when jenkinsClass.EndsWith("FreeStyleProject", StringComparison.Ordinal) => "freestyle",
        _ => jenkinsClass[(jenkinsClass.LastIndexOf('.') + 1)..],
    };

    private static IEnumerable<JsonElement> Array(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Array
            ? value.EnumerateArray()
            : [];

    private static string? Str(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static string? Scalar(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value)
            ? value.ValueKind switch
            {
                JsonValueKind.String => value.GetString(),
                JsonValueKind.Null or JsonValueKind.Undefined => null,
                _ => value.GetRawText(),
            }
            : null;

    private static bool Bool(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.True;

    private static int? Int(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var i) ? i : null;

    private static DateTimeOffset? Time(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number && value.GetInt64() > 0
            ? DateTimeOffset.FromUnixTimeMilliseconds(value.GetInt64())
            : null;

    private static TimeSpan? Span(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number && value.GetInt64() >= 0
            ? TimeSpan.FromMilliseconds(value.GetInt64())
            : null;
}
