using System.Net;

namespace JenkinsTools.Client;

public enum JenkinsFailure
{
    /// <summary>The site or its credential is not configured properly.</summary>
    Configuration,
    InvalidArgument,
    /// <summary>401: no token, a wrong token or a wrong user.</summary>
    Unauthorized,
    /// <summary>403: the user lacks the permission (or the site denies anonymous access).</summary>
    Forbidden,
    NotFound,
    /// <summary>Jenkins answered with another error, or could not be reached.</summary>
    Failed,
    Timeout,
}

public sealed class JenkinsException(JenkinsFailure failure, string message, HttpStatusCode? status = null, Exception? inner = null)
    : Exception(message, inner)
{
    public JenkinsFailure Failure { get; } = failure;

    public HttpStatusCode? Status { get; } = status;
}
