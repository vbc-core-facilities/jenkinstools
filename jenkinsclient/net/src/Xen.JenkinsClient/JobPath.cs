namespace Xen.JenkinsClient;

/// <summary>
/// Addresses a job: folder and job names separated by <c>/</c> (<c>team/repo</c>), plus, for a multibranch job, the branch
/// or tag taken literally (it may contain <c>/</c>, e.g. <c>feat/x</c>). Builds the URL path Jenkins expects: every name as
/// <c>job/&lt;name&gt;</c>, a branch name encoded twice (Jenkins names the branch job <c>feat%2Fx</c>, its URL holds
/// <c>feat%252Fx</c>).
/// </summary>
public sealed record JobPath(IReadOnlyList<string> Names)
{
    /// <summary>
    /// <paramref name="job"/>: <c>a/b/c</c>, or a job URL of the site (<c>https://host/job/a/job/b/</c>).
    /// <paramref name="branch"/>: optional, appended as the last name, literally.
    /// </summary>
    public static JobPath Parse(string job, string? branch = null)
    {
        if (string.IsNullOrWhiteSpace(job))
        {
            throw new JenkinsException(JenkinsFailure.InvalidArgument, "job is empty");
        }

        List<string> names;
        if (Uri.TryCreate(job, UriKind.Absolute, out var url) && (url.Scheme == Uri.UriSchemeHttp || url.Scheme == Uri.UriSchemeHttps))
        {
            names = FromUrlPath(url.AbsolutePath);
        }
        else
        {
            names = job.Trim().Trim('/').Split('/', StringSplitOptions.RemoveEmptyEntries).ToList();
        }

        if (!string.IsNullOrWhiteSpace(branch))
        {
            // a branch job's name is the branch with '/' (and '%') encoded
            names.Add(Uri.EscapeDataString(branch.Trim()));
        }

        if (names.Count == 0)
        {
            throw new JenkinsException(JenkinsFailure.InvalidArgument, $"'{job}' names no job");
        }

        return new JobPath(names);
    }

    /// <summary>Relative URL path, without leading or trailing slash: <c>job/a/job/b</c>.</summary>
    public string UrlPath => string.Join('/', Names.Select(n => "job/" + Uri.EscapeDataString(n)));

    /// <summary>As Jenkins writes the full name (<c>a/b/feat%2Fx</c>).</summary>
    public string FullName => string.Join('/', Names);

    public override string ToString() => FullName;

    /// <summary>Readable form of a job name: <c>feat%2Fx</c> as <c>feat/x</c>.</summary>
    public static string Display(string jobName) => Uri.UnescapeDataString(jobName);

    private static List<string> FromUrlPath(string absolutePath)
    {
        var segments = absolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        var names = new List<string>();
        for (var i = 0; i < segments.Length - 1; i++)
        {
            if (segments[i] == "job")
            {
                // one level of URL encoding off: the job's own name remains (feat%252Fx -> feat%2Fx)
                names.Add(Uri.UnescapeDataString(segments[i + 1]));
                i++;
            }
        }

        return names;
    }
}
