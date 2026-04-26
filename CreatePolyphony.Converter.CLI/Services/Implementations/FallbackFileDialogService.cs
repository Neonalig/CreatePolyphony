namespace CreatePolyphony.Converter.CLI.Services.Implementations;

public class FallbackFileDialogService : IFileDialogService
{
    public string? PromptForOpenFile(string filter) => null;

    public string? PromptForSaveFile(string filter, string defaultFileName) => null;
}
