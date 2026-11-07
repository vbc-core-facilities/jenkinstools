using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using ModelContextProtocol.AspNetCore;

namespace JenkinsTools.Client.Mcp;

public static class McpRegistration
{
    private const string Instructions = """
        A client of one or more Jenkins servers (list_sites), through Jenkins' public REST API, as the configured user of
        each site (or anonymously). Reads: jobs, multibranch branches and tags, builds, console logs (filter with pattern),
        test reports, the queue, scan logs. Writes are few: scan (branch discovery), start_build, abort_build. A build runs
        the job's whole pipeline, deployments included: start only what the user asked for, and never abort without
        confirmation. Branch names go into 'branch' literally ('feat/x'); 'job' is the folder/job path.
        On Unauthorized: the site's token is missing or wrong; the user stores it with 'jenkinsclient set-token <site>'.
        """;

    /// <summary>The MCP server over stdin/stdout. Nothing but MCP messages may go to stdout: the host logs to stderr.</summary>
    public static IMcpServerBuilder AddJenkinsClientMcpStdio(this IServiceCollection services) =>
        AddServer(services).WithStdioServerTransport().WithTools<JenkinsTools>();

    /// <summary>The MCP server over streamable HTTP; map it with <see cref="MapJenkinsClientMcp"/>.</summary>
    public static IMcpServerBuilder AddJenkinsClientMcpHttp(this IServiceCollection services) =>
        AddServer(services).WithHttpTransport().WithTools<JenkinsTools>();

    public static IEndpointConventionBuilder MapJenkinsClientMcp(this IEndpointRouteBuilder endpoints, string pattern = "/mcp") =>
        endpoints.MapMcp(pattern);

    private static IMcpServerBuilder AddServer(IServiceCollection services) =>
        services.AddMcpServer(o =>
        {
            o.ServerInfo = new() { Name = "jenkinsclient", Version = typeof(McpRegistration).Assembly.GetName().Version?.ToString() ?? "0.0.0" };
            o.ServerInstructions = Instructions;
        });
}
