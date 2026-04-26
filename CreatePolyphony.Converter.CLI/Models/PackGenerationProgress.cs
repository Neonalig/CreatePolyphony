namespace CreatePolyphony.Converter.CLI.Models;

public record PackGenerationProgress(string Phase, string? Subtext, int CompletedWork, int TotalWork)
{
    public double Progress => TotalWork <= 0 ? 0d : Math.Clamp((double)CompletedWork / TotalWork, 0d, 1d);
}

