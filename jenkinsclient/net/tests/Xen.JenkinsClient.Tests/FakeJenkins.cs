using System.Net;
using System.Text;

namespace Xen.JenkinsClient.Tests;

/// <summary>A scripted Jenkins: answers by method and path (+query), records every request.</summary>
internal sealed class FakeJenkins : HttpMessageHandler
{
    private readonly List<(HttpMethod Method, string PathAndQueryPrefix, Func<HttpRequestMessage, HttpResponseMessage> Respond)> _routes = [];

    public List<(HttpMethod Method, string PathAndQuery, string? Authorization, string? Crumb, string? Body)> Requests { get; } = [];

    public FakeJenkins On(HttpMethod method, string pathAndQueryPrefix, Func<HttpRequestMessage, HttpResponseMessage> respond)
    {
        _routes.Add((method, pathAndQueryPrefix, respond));
        return this;
    }

    public FakeJenkins OnJson(HttpMethod method, string pathAndQueryPrefix, string json) =>
        On(method, pathAndQueryPrefix, _ => Json(json));

    public static HttpResponseMessage Json(string json, HttpStatusCode status = HttpStatusCode.OK) =>
        new(status) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

    public static HttpResponseMessage Text(string text, HttpStatusCode status = HttpStatusCode.OK) =>
        new(status) { Content = new StringContent(text, Encoding.UTF8, "text/plain") };

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var pathAndQuery = Uri.UnescapeDataString(request.RequestUri!.PathAndQuery);
        var raw = request.RequestUri!.PathAndQuery;
        Requests.Add((request.Method, raw,
            request.Headers.Authorization?.ToString(),
            request.Headers.TryGetValues("Jenkins-Crumb", out var crumbs) ? crumbs.First() : null,
            request.Content is null ? null : await request.Content.ReadAsStringAsync(cancellationToken)));
        foreach (var route in _routes)
        {
            if (route.Method == request.Method && (raw.StartsWith(route.PathAndQueryPrefix, StringComparison.Ordinal)
                                                   || pathAndQuery.StartsWith(route.PathAndQueryPrefix, StringComparison.Ordinal)))
            {
                return route.Respond(request);
            }
        }

        return Text($"no route for {request.Method} {raw}", HttpStatusCode.NotFound);
    }
}
