namespace CreatePolyphony.Converter.CLI.Services.Implementations;

public class MacFileDialogService : IFileDialogService
{
    public string? PromptForOpenFile(string filter)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = "osascript",
            Arguments = "-e \"POSIX path of (choose file with prompt \\\"Select SF2 or SFZ File\\\")\"",
            RedirectStandardOutput = true,
            UseShellExecute = false
        };

        using Process? process = Process.Start(startInfo);
        process?.WaitForExit();

        string? result = process?.StandardOutput.ReadToEnd().Trim();
        return string.IsNullOrEmpty(result) ? null : result;
    }

    public string? PromptForSaveFile(string filter, string defaultFileName)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = "osascript",
            RedirectStandardOutput = true,
            UseShellExecute = false
        };

        string escapedDefaultName = defaultFileName.Replace("\\", "\\\\").Replace("\"", "\\\"");
        string script = $"POSIX path of (choose file name with prompt \"Save Resource Pack Zip\" default name \"{escapedDefaultName}\")";
        startInfo.ArgumentList.Add("-e");
        startInfo.ArgumentList.Add(script);

        using Process? process = Process.Start(startInfo);
        process?.WaitForExit();

        string? result = process?.StandardOutput.ReadToEnd().Trim();
        return string.IsNullOrEmpty(result) ? null : result;
    }
}
