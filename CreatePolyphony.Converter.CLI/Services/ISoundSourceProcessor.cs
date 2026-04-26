using CreatePolyphony.Converter.CLI.Models;

namespace CreatePolyphony.Converter.CLI.Services;

public interface ISoundSourceProcessor
{
    IReadOnlyCollection<string> SupportedExtensions { get; }

    int EstimateWorkUnits(string inputPath);

    Task ProcessAsync(
        string inputPath,
        string soundsDir,
        Dictionary<string, SoundEventDefinition> soundEvents,
        IProgress<PackGenerationProgress>? progress = null,
        int completedWorkStart = 0,
        int totalWork = 0);
}
