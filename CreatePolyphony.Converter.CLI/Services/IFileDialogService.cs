namespace CreatePolyphony.Converter.CLI.Services;

public interface IFileDialogService
{
    string? PromptForOpenFile(string filter);

    string? PromptForSaveFile(string filter, string defaultFileName);
}
