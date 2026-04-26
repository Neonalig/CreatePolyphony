namespace CreatePolyphony.Converter.CLI.Services.Implementations;

public class LinuxFileDialogService : IFileDialogService
{
    public string? PromptForOpenFile(string filter)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = "zenity",
            Arguments = "--file-selection --title=\"Select SF2 or SFZ File\"",
            RedirectStandardOutput = true,
            UseShellExecute = false
        };

        try
        {
            using Process? process = Process.Start(startInfo);
            process?.WaitForExit();
            string? result = process?.StandardOutput.ReadToEnd().Trim();
            return string.IsNullOrEmpty(result) ? null : result;
        }
        catch
        {
            return null; // Zenity not installed
        }
    }

    public string? PromptForSaveFile(string filter, string defaultFileName)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = "zenity",
            UseShellExecute = false,
            RedirectStandardOutput = true
        };

        startInfo.ArgumentList.Add("--file-selection");
        startInfo.ArgumentList.Add("--save");
        startInfo.ArgumentList.Add("--confirm-overwrite");
        startInfo.ArgumentList.Add("--title=Save Resource Pack Zip");
        if (!string.IsNullOrWhiteSpace(defaultFileName))
        {
            startInfo.ArgumentList.Add($"--filename={defaultFileName}");
        }

        try
        {
            using Process? process = Process.Start(startInfo);
            process?.WaitForExit();
            string? result = process?.StandardOutput.ReadToEnd().Trim();
            return string.IsNullOrEmpty(result) ? null : result;
        }
        catch
        {
            return null; // Zenity not installed
        }
    }
}
