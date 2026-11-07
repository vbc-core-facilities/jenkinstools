using System.Diagnostics;
using System.Runtime.InteropServices;

namespace JenkinsTools.Client.Host;

/// <summary>
/// Started through a link (the installer's <c>current</c> junction), the program runs the copy the link points to now
/// as a child, with the same arguments and the inherited stdin/stdout/stderr, and returns its exit code.
/// </summary>
/// <remarks>
/// A process started through the junction opens every file by its ...\current\... path, also the assemblies .NET loads
/// lazily, long after the start. The installer re-points the junction on every update, so a server running through it
/// could load the new version's files halfway through (tested: also with a single-file publish, whose bundle the runtime
/// reopens by path). The relay loads nothing more once the child runs, and the child's path names its version folder.
/// Same scheme as AzureTools (StdioHost.RunFromLinkTarget).
/// </remarks>
internal static class LinkRelay
{
    /// <summary>The child's exit code, or null when the program path has no link in it: then this process does the work.</summary>
    public static int? RunFromLinkTarget(string[] args)
    {
        if (Environment.ProcessPath is not { } started)
        {
            return null;
        }

        var resolved = ResolveLinks(started);
        if (string.Equals(resolved, Path.GetFullPath(started), StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        var start = new ProcessStartInfo(resolved) { UseShellExecute = false };
        foreach (var arg in args)
        {
            start.ArgumentList.Add(arg);
        }

        // the child dies with the relay (an MCP client may kill the process it started instead of closing its stdin)
        var job = KillOnCloseJob.Create();
        using var child = Process.Start(start) ?? throw new InvalidOperationException($"Could not start {resolved}.");
        job?.Add(child);
        child.WaitForExit();
        GC.KeepAlive(job);
        return child.ExitCode;
    }

    /// <summary>A Windows job object that kills its processes when its last handle closes, i.e. when the relay ends.</summary>
    private sealed class KillOnCloseJob
    {
        private const int ExtendedLimitInformation = 9;
        private const uint KillOnJobClose = 0x2000;
        private readonly nint _handle;

        private KillOnCloseJob(nint handle) => _handle = handle;

        public static KillOnCloseJob? Create()
        {
            if (!OperatingSystem.IsWindows())
            {
                return null;
            }

            var handle = CreateJobObjectW(0, null);
            if (handle == 0)
            {
                return null;
            }

            var info = new ExtendedLimits { LimitFlags = KillOnJobClose };
            return SetInformationJobObject(handle, ExtendedLimitInformation, ref info, (uint)Marshal.SizeOf<ExtendedLimits>())
                ? new KillOnCloseJob(handle)
                : null;
        }

        public void Add(Process process) => AssignProcessToJobObject(_handle, process.Handle);

        [StructLayout(LayoutKind.Sequential)]
        private struct ExtendedLimits
        {
            public long PerProcessUserTimeLimit;
            public long PerJobUserTimeLimit;
            public uint LimitFlags;
            public nuint MinimumWorkingSetSize;
            public nuint MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public nuint Affinity;
            public uint PriorityClass;
            public uint SchedulingClass;
            public ulong ReadOperationCount;
            public ulong WriteOperationCount;
            public ulong OtherOperationCount;
            public ulong ReadTransferCount;
            public ulong WriteTransferCount;
            public ulong OtherTransferCount;
            public nuint ProcessMemoryLimit;
            public nuint JobMemoryLimit;
            public nuint PeakProcessMemoryUsed;
            public nuint PeakJobMemoryUsed;
        }

#pragma warning disable SYSLIB1054 // DllImport is fine here: no source-generated marshalling needed for three calls
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        private static extern nint CreateJobObjectW(nint attributes, string? name);

        [DllImport("kernel32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool SetInformationJobObject(nint job, int infoClass, ref ExtendedLimits info, uint length);

        [DllImport("kernel32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool AssignProcessToJobObject(nint job, nint process);
#pragma warning restore SYSLIB1054
    }

    /// <summary>The path with every link (symbolic link, junction) in it replaced by its final target.</summary>
    private static string ResolveLinks(string path)
    {
        var full = Path.GetFullPath(path);
        var root = Path.GetPathRoot(full) ?? string.Empty;
        var current = root;
        foreach (var part in full[root.Length..].Split(Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries))
        {
            current = Path.Combine(current, part);
            FileSystemInfo item = Directory.Exists(current) ? new DirectoryInfo(current) : new FileInfo(current);
            if (item.ResolveLinkTarget(returnFinalTarget: true) is { } target)
            {
                current = target.FullName;
            }
        }

        return current;
    }
}
