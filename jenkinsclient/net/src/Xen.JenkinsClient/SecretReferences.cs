using System.Diagnostics;

namespace Xen.JenkinsClient;

/// <summary>
/// Turns a secret reference into its value, so configuration never holds a token:
/// <list type="bullet">
/// <item><c>wincred:Target</c>: a generic credential in the Windows Credential Manager of the current user (DPAPI). Readable
/// without a prompt; store one with <c>jenkinsclient set-token &lt;site&gt;</c>.</item>
/// <item><c>op://vault/item/field</c>: the 1Password CLI (<c>op read</c>); needs the 1Password app unlocked and may prompt.</item>
/// <item><c>env:NAME</c>: an environment variable.</item>
/// <item><c>file:path</c>: the trimmed content of a file.</item>
/// </list>
/// Anything else is refused: a token written literally into configuration is exactly what this avoids.
/// </summary>
public class SecretReferences
{
    public const string WindowsCredentialPrefix = "wincred:";

    public virtual async Task<string?> ResolveAsync(string reference, CancellationToken ct = default)
    {
        reference = reference.Trim();
        if (reference.StartsWith(WindowsCredentialPrefix, StringComparison.Ordinal))
        {
            var target = reference[WindowsCredentialPrefix.Length..];
            if (!OperatingSystem.IsWindows())
            {
                throw new JenkinsException(JenkinsFailure.Configuration, $"{reference}: wincred references need Windows.");
            }

            return WindowsCredentials.Read(target)
                ?? throw new JenkinsException(JenkinsFailure.Configuration,
                    $"No Windows credential '{target}'. Store the token with: jenkinsclient set-token <site> (it prompts, nothing is echoed).");
        }

        if (reference.StartsWith("op://", StringComparison.Ordinal))
        {
            return await ReadOnePasswordAsync(reference, ct);
        }

        if (reference.StartsWith("env:", StringComparison.Ordinal))
        {
            return Environment.GetEnvironmentVariable(reference[4..]);
        }

        if (reference.StartsWith("file:", StringComparison.Ordinal))
        {
            var path = reference[5..];
            return File.Exists(path) ? (await File.ReadAllTextAsync(path, ct)).Trim() : null;
        }

        throw new JenkinsException(JenkinsFailure.Configuration,
            "A token reference must start with wincred:, op://, env: or file:. A token is never written into configuration itself.");
    }

    private static async Task<string> ReadOnePasswordAsync(string reference, CancellationToken ct)
    {
        var start = new ProcessStartInfo("op")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        start.ArgumentList.Add("read");
        start.ArgumentList.Add("--no-newline");
        start.ArgumentList.Add(reference);

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromMinutes(2));
        Process process;
        try
        {
            process = Process.Start(start) ?? throw new InvalidOperationException("op did not start");
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            throw new JenkinsException(JenkinsFailure.Configuration, $"{reference}: the 1Password CLI (op) could not be started: {e.Message}");
        }

        using (process)
        {
            var output = process.StandardOutput.ReadToEndAsync(timeout.Token);
            var error = process.StandardError.ReadToEndAsync(timeout.Token);
            await process.WaitForExitAsync(timeout.Token);
            if (process.ExitCode != 0)
            {
                throw new JenkinsException(JenkinsFailure.Configuration, $"1Password could not read {reference}: {(await error).Trim()}");
            }

            return (await output).TrimEnd('\r', '\n');
        }
    }
}
