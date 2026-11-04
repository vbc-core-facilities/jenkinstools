namespace Xen.JenkinsClient;

/// <summary>One Jenkins this client talks to, by a short name of the caller's choice (e.g. <c>vbc</c>, <c>testserver</c>).</summary>
public sealed class JenkinsSiteOptions
{
    /// <summary>Root URL of the Jenkins, e.g. <c>https://jenkins.example.org/</c>.</summary>
    public string BaseUrl { get; set; } = "";

    /// <summary>
    /// Jenkins user the API token belongs to. Empty: the OS user name of this process (<see cref="Environment.UserName"/>).
    /// Ignored when there is no <see cref="TokenReference"/>.
    /// </summary>
    public string? Username { get; set; }

    /// <summary>
    /// Where the API token is, never the token itself: <c>wincred:Target</c>, <c>op://vault/item/field</c>,
    /// <c>env:NAME</c> or <c>file:path</c> (see <see cref="SecretReferences"/>). Empty: anonymous.
    /// </summary>
    public string? TokenReference { get; set; }

    /// <summary>Per request timeout. Default 100 s.</summary>
    public TimeSpan? Timeout { get; set; }
}

/// <summary>The configuration section of this client: named sites and which one calls use when they name none.</summary>
public sealed class JenkinsClientOptions
{
    public Dictionary<string, JenkinsSiteOptions> Sites { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Site used when a call names none. Empty with exactly one site: that one.</summary>
    public string? DefaultSite { get; set; }
}

/// <summary>Resolves site names to clients; one <see cref="JenkinsClient"/> per site, created on first use.</summary>
public sealed class JenkinsSites : IDisposable
{
    private readonly JenkinsClientOptions _options;
    private readonly SecretReferences _secrets;
    private readonly Dictionary<string, JenkinsClient> _clients = new(StringComparer.OrdinalIgnoreCase);
    private readonly SemaphoreSlim _lock = new(1, 1);

    public JenkinsSites(JenkinsClientOptions options, SecretReferences? secrets = null)
    {
        _options = options;
        _secrets = secrets ?? new SecretReferences();
    }

    public IReadOnlyDictionary<string, JenkinsSiteOptions> Sites => _options.Sites;

    /// <summary>The site a call without a site name goes to.</summary>
    public string DefaultSiteName =>
        _options.DefaultSite is { Length: > 0 } name ? name
        : _options.Sites.Count == 1 ? _options.Sites.Keys.First()
        : throw new JenkinsException(JenkinsFailure.Configuration,
            $"No default site configured and {_options.Sites.Count} sites exist ({string.Join(", ", _options.Sites.Keys)}): name one.");

    /// <summary>The user name a site authenticates as (the configured one, else the OS user), or null when anonymous.</summary>
    public static string? EffectiveUsername(JenkinsSiteOptions site) =>
        string.IsNullOrWhiteSpace(site.TokenReference) ? null
        : string.IsNullOrWhiteSpace(site.Username) ? Environment.UserName
        : site.Username;

    public async Task<JenkinsClient> GetAsync(string? siteName, CancellationToken ct = default)
    {
        var name = string.IsNullOrWhiteSpace(siteName) ? DefaultSiteName : siteName.Trim();
        if (!_options.Sites.TryGetValue(name, out var site))
        {
            throw new JenkinsException(JenkinsFailure.Configuration,
                $"Unknown site '{name}'. Configured: {string.Join(", ", _options.Sites.Keys)}.");
        }

        await _lock.WaitAsync(ct);
        try
        {
            if (_clients.TryGetValue(name, out var existing))
            {
                return existing;
            }

            if (!Uri.TryCreate(site.BaseUrl, UriKind.Absolute, out var baseUri) || (baseUri.Scheme != Uri.UriSchemeHttps && baseUri.Scheme != Uri.UriSchemeHttp))
            {
                throw new JenkinsException(JenkinsFailure.Configuration, $"Site '{name}': BaseUrl '{site.BaseUrl}' is not an http(s) URL.");
            }

            string? token = null;
            if (!string.IsNullOrWhiteSpace(site.TokenReference))
            {
                token = await _secrets.ResolveAsync(site.TokenReference, ct);
                if (string.IsNullOrEmpty(token))
                {
                    throw new JenkinsException(JenkinsFailure.Configuration,
                        $"Site '{name}': the token reference {site.TokenReference} resolved to nothing.");
                }
            }

            var client = new JenkinsClient(name, baseUri, EffectiveUsername(site), token, site.Timeout);
            _clients[name] = client;
            return client;
        }
        finally
        {
            _lock.Release();
        }
    }

    public void Dispose()
    {
        foreach (var client in _clients.Values)
        {
            client.Dispose();
        }

        _lock.Dispose();
    }
}
