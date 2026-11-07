using System.Net;
using System.Text;
using System.Text.Json;
using JenkinsTools.Client;
using JenkinsTools.Client.Host;
using JenkinsTools.Client.Mcp;

// jenkinsclient --stdio                 MCP over stdin/stdout (started by the MCP client, e.g. Claude Code)
// jenkinsclient serve [--port 5190]     MCP over streamable HTTP at http://127.0.0.1:<port>/mcp (loopback only)
// jenkinsclient check [site]            who each site (or one) sees us as
// jenkinsclient sites                   the configured sites and where the settings come from
// jenkinsclient set-token <site>        prompts for the site's API token (not echoed), stores it in the Windows Credential Manager

// started through the installer's current junction: run the copy it points to now (see LinkRelay)
if (LinkRelay.RunFromLinkTarget(args) is { } relayed)
{
    return relayed;
}

var command = args.FirstOrDefault() ?? "help";
var rest = args.Skip(1).ToArray();

try
{
    return command switch
    {
        "--stdio" => await Modes.StdioAsync(rest),
        "serve" => await Modes.ServeAsync(rest),
        "check" => await Commands.CheckAsync(rest),
        "sites" => Commands.Sites(rest),
        "set-token" => Commands.SetToken(rest),
        _ => Commands.Help(),
    };
}
catch (JenkinsException e)
{
    Console.Error.WriteLine($"{e.Failure}: {e.Message}");
    return 2;
}

namespace JenkinsTools.Client.Host
{
    internal static class Settings
    {
        /// <summary>The user settings file: JENKINSCLIENT_SETTINGS, else %LOCALAPPDATA%/JenkinsClient/settings.json.</summary>
        public static string UserFile =>
            Environment.GetEnvironmentVariable("JENKINSCLIENT_SETTINGS") is { Length: > 0 } path
                ? path
                : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "JenkinsClient", "settings.json");

        /// <summary>appsettings.json next to the program, the user file, environment variables, the command line: last wins.</summary>
        public static void Configure(IConfigurationBuilder configuration, string[] args)
        {
            configuration.Sources.Clear();
            configuration.SetBasePath(AppContext.BaseDirectory)
                .AddJsonFile("appsettings.json", optional: true)
                .AddJsonFile(UserFile, optional: true)
                .AddEnvironmentVariables()
                .AddCommandLine(args);
        }

        public static IConfiguration Load(string[] args)
        {
            var builder = new ConfigurationBuilder();
            Configure(builder, args);
            return builder.Build();
        }

        public static JenkinsClientOptions Options(IConfiguration configuration)
        {
            var options = configuration.GetSection("JenkinsClient").Get<JenkinsClientOptions>() ?? new JenkinsClientOptions();
            // the binder creates a case-sensitive dictionary: site names are case-insensitive
            options.Sites = new Dictionary<string, JenkinsSiteOptions>(options.Sites, StringComparer.OrdinalIgnoreCase);
            return options;
        }
    }

    internal static class Modes
    {
        public static async Task<int> StdioAsync(string[] args)
        {
            var builder = Microsoft.Extensions.Hosting.Host.CreateApplicationBuilder(new HostApplicationBuilderSettings { Args = args, ContentRootPath = AppContext.BaseDirectory });
            Settings.Configure(builder.Configuration, args);
            // stdout carries the MCP messages: every log line goes to stderr
            builder.Logging.ClearProviders();
            builder.Logging.AddConsole(o => o.LogToStandardErrorThreshold = LogLevel.Trace);
            builder.Services.AddSingleton(_ => new JenkinsSites(Settings.Options(builder.Configuration)));
            builder.Services.AddJenkinsClientMcpStdio();
            await builder.Build().RunAsync();
            return 0;
        }

        public static async Task<int> ServeAsync(string[] args)
        {
            var port = 5190;
            for (var i = 0; i < args.Length - 1; i++)
            {
                if (args[i] == "--port" && int.TryParse(args[i + 1], out var p))
                {
                    port = p;
                }
            }

            var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = args, ContentRootPath = AppContext.BaseDirectory });
            Settings.Configure(builder.Configuration, args);
            // loopback only: the server acts with this user's tokens, so nobody else may reach it
            builder.WebHost.ConfigureKestrel(k => k.Listen(IPAddress.Loopback, port));
            builder.Services.AddSingleton(_ => new JenkinsSites(Settings.Options(builder.Configuration)));
            builder.Services.AddJenkinsClientMcpHttp();

            var app = builder.Build();
            // DNS rebinding guard: a browser page cannot reach a loopback server under another host name
            app.Use(async (context, next) =>
            {
                var host = context.Request.Host.Host;
                if (host is not ("localhost" or "127.0.0.1" or "[::1]" or "::1"))
                {
                    context.Response.StatusCode = StatusCodes.Status421MisdirectedRequest;
                    return;
                }

                await next();
            });
            app.MapGet("/healthz", () => Results.Ok(new { status = "ok" }));
            app.MapJenkinsClientMcp("/mcp");
            Console.Error.WriteLine($"jenkinsclient: MCP at http://127.0.0.1:{port}/mcp (loopback only)");
            await app.RunAsync();
            return 0;
        }
    }

    internal static class Commands
    {
        public static int Help()
        {
            Console.WriteLine("""
                jenkinsclient --stdio              MCP server over stdin/stdout (for an MCP client that starts it)
                jenkinsclient serve [--port 5190]  MCP server over HTTP at http://127.0.0.1:<port>/mcp (loopback only)
                jenkinsclient check [site]         who each site sees us as (checks URL, user and token)
                jenkinsclient sites                the configured sites, and the settings file
                jenkinsclient set-token <site>     prompt for the site's API token (not echoed) and store it in the Windows
                                                   Credential Manager under the site's wincred: reference

                Settings: appsettings.json next to the program, then the user file (JENKINSCLIENT_SETTINGS or
                %LOCALAPPDATA%/JenkinsClient/settings.json), then environment variables (JenkinsClient__Sites__<site>__Username=...),
                then the command line (--JenkinsClient:Sites:<site>:Username=...). Last wins.
                """);
            return 1;
        }

        public static int Sites(string[] args)
        {
            var options = Settings.Options(Settings.Load(args));
            Console.WriteLine($"settings file: {Settings.UserFile}{(File.Exists(Settings.UserFile) ? "" : " (missing)")}");
            Console.WriteLine($"default site:  {(options.DefaultSite is { Length: > 0 } d ? d : "(none)")}");
            foreach (var (name, site) in options.Sites)
            {
                Console.WriteLine($"{name,-14} {site.BaseUrl}  user={JenkinsSites.EffectiveUsername(site) ?? "(anonymous)"}  token={site.TokenReference ?? "(none)"}");
            }

            return 0;
        }

        public static async Task<int> CheckAsync(string[] args)
        {
            var options = Settings.Options(Settings.Load(args.Where(a => a.StartsWith("--", StringComparison.Ordinal)).ToArray()));
            var only = args.FirstOrDefault(a => !a.StartsWith("--", StringComparison.Ordinal));
            using var sites = new JenkinsSites(options);
            var failed = 0;
            foreach (var name in options.Sites.Keys.Where(n => only is null || string.Equals(n, only, StringComparison.OrdinalIgnoreCase)))
            {
                try
                {
                    var client = await sites.GetAsync(name);
                    var me = await client.WhoAmIAsync();
                    Console.WriteLine($"{name,-14} {me.BaseUrl}  seen as '{me.Name}' (authenticated={me.Authenticated}, anonymous={me.Anonymous})");
                    if (client.Username is not null && me.Anonymous)
                    {
                        Console.WriteLine($"{"",-14} configured user '{client.Username}' was not accepted");
                        failed++;
                    }
                }
                catch (JenkinsException e)
                {
                    Console.WriteLine($"{name,-14} {e.Failure}: {e.Message}");
                    failed++;
                }
            }

            return failed == 0 ? 0 : 2;
        }

        public static int SetToken(string[] args)
        {
            if (args.Length < 1)
            {
                Console.Error.WriteLine("usage: jenkinsclient set-token <site>");
                return 1;
            }

            if (!OperatingSystem.IsWindows())
            {
                Console.Error.WriteLine("set-token stores into the Windows Credential Manager; elsewhere point TokenReference at env: or file:.");
                return 1;
            }

            var options = Settings.Options(Settings.Load(args.Skip(1).ToArray()));
            if (!options.Sites.TryGetValue(args[0], out var site))
            {
                Console.Error.WriteLine($"Unknown site '{args[0]}'. Configured: {string.Join(", ", options.Sites.Keys)} (settings: {Settings.UserFile}).");
                return 1;
            }

            if (site.TokenReference is not { } reference || !reference.StartsWith(SecretReferences.WindowsCredentialPrefix, StringComparison.Ordinal))
            {
                Console.Error.WriteLine($"Site '{args[0]}' has TokenReference '{site.TokenReference ?? "(none)"}'. set-token only fills wincred: references; set one (e.g. wincred:JenkinsClient/{args[0]}) in {Settings.UserFile} first.");
                return 1;
            }

            if (Console.IsInputRedirected)
            {
                Console.Error.WriteLine("set-token needs an interactive terminal (the token is typed, not piped).");
                return 1;
            }

            var user = JenkinsSites.EffectiveUsername(site)!;
            Console.Write($"API token for {user} at {site.BaseUrl} (create one on your user page, Security, 'Add new Token'): ");
            var token = ReadHidden();
            if (token.Length == 0)
            {
                Console.Error.WriteLine("nothing entered, nothing stored");
                return 1;
            }

            var target = reference[SecretReferences.WindowsCredentialPrefix.Length..];
            WindowsCredentials.Write(target, user, token, $"jenkinsclient: API token for {site.BaseUrl}");
            Console.WriteLine($"stored as Windows credential '{target}'. Check it with: jenkinsclient check {args[0]}");
            return 0;
        }

        private static string ReadHidden()
        {
            var text = new StringBuilder();
            while (true)
            {
                var key = Console.ReadKey(intercept: true);
                if (key.Key == ConsoleKey.Enter)
                {
                    Console.WriteLine();
                    return text.ToString().Trim();
                }

                if (key.Key == ConsoleKey.Backspace)
                {
                    if (text.Length > 0)
                    {
                        text.Length--;
                    }
                }
                else if (!char.IsControl(key.KeyChar))
                {
                    text.Append(key.KeyChar);
                }
            }
        }
    }
}
