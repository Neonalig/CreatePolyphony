using System.IO.Compression;
using CreatePolyphony.Converter.CLI.Models;

namespace CreatePolyphony.Converter.CLI.Services;

public class PackGeneratorService(IEnumerable<ISoundSourceProcessor> processors)
{
    private readonly Dictionary<string, ISoundSourceProcessor> processorsByExtension = BuildProcessorMap(processors);

    async public Task GeneratePackAsync(
        string inputFilePath,
        string outputPath,
        bool finalizeToZip,
        string packName,
        IProgress<PackGenerationProgress>? progress = null)
    {
        Console.WriteLine($"Starting generation for: {inputFilePath}");

        string ext = Path.GetExtension(inputFilePath).ToLowerInvariant();
        var soundEvents = new Dictionary<string, SoundEventDefinition>();

        if (!processorsByExtension.TryGetValue(ext, out ISoundSourceProcessor? processor))
        {
            Console.WriteLine("Unsupported file format.");
            return;
        }

        int encodingWork = processor.EstimateWorkUnits(inputFilePath);
        int totalWork = encodingWork + 3;
        int completedWork = 0;

        string stagingRoot = CreateStagingDirectory();
        string assetsDir = Path.Combine(stagingRoot, "assets", Constants.MOD_ID);
        string soundsDir = Path.Combine(assetsDir, "sounds", "instruments");

        Directory.CreateDirectory(soundsDir);
        Report(progress, "Preparing pack", Path.GetFileName(inputFilePath), completedWork, totalWork);

        try
        {
            await processor.ProcessAsync(inputFilePath, soundsDir, soundEvents, progress, completedWork, totalWork);

            completedWork = encodingWork;

            Report(progress, "Writing pack metadata", null, ++completedWork, totalWork);
            WritePackMeta(stagingRoot, packName);

            Report(progress, "Writing sounds.json", null, ++completedWork, totalWork);
            WriteSoundsJson(assetsDir, soundEvents);

            if (finalizeToZip)
            {
                EnsureParentDirectory(outputPath);
                if (Directory.Exists(outputPath))
                {
                    Directory.Delete(outputPath, true);
                }

                if (File.Exists(outputPath))
                {
                    File.Delete(outputPath);
                }

                Report(progress, "Finalizing zip", Path.GetFileName(outputPath), ++completedWork, totalWork);
                ZipFile.CreateFromDirectory(stagingRoot, outputPath, CompressionLevel.Optimal, false);
            }
            else
            {
                if (File.Exists(outputPath))
                {
                    File.Delete(outputPath);
                }

                if (Directory.Exists(outputPath))
                {
                    Directory.Delete(outputPath, true);
                }

                Report(progress, "Finalizing folder output", Path.GetFileName(outputPath), ++completedWork, totalWork);
                Directory.CreateDirectory(outputPath);
                CopyDirectoryContents(stagingRoot, outputPath);
            }

            completedWork = totalWork;
            Report(progress, "Generation complete", Path.GetFileName(outputPath), completedWork, totalWork);
            Console.WriteLine($"Generation complete. Output saved to: {outputPath}");
        }
        finally
        {
            TryDeleteDirectory(stagingRoot);
        }
    }

    private static string CreateStagingDirectory()
    {
        string stagingRoot = Path.Combine(Path.GetTempPath(), "CreatePolyphony.Converter.CLI", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(stagingRoot);
        return stagingRoot;
    }

    private static void EnsureParentDirectory(string path)
    {
        string? parent = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(parent))
        {
            Directory.CreateDirectory(parent);
        }
    }

    private static void CopyDirectoryContents(string sourceDirectory, string destinationDirectory)
    {
        foreach (string directory in Directory.GetDirectories(sourceDirectory, "*", SearchOption.AllDirectories))
        {
            string relativeDirectory = Path.GetRelativePath(sourceDirectory, directory);
            Directory.CreateDirectory(Path.Combine(destinationDirectory, relativeDirectory));
        }

        foreach (string file in Directory.GetFiles(sourceDirectory, "*", SearchOption.AllDirectories))
        {
            string destinationFile = Path.Combine(destinationDirectory, Path.GetRelativePath(sourceDirectory, file));
            string? parent = Path.GetDirectoryName(destinationFile);
            if (!string.IsNullOrWhiteSpace(parent))
            {
                Directory.CreateDirectory(parent);
            }

            File.Copy(file, destinationFile, true);
        }
    }

    private static void TryDeleteDirectory(string directory)
    {
        try
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, true);
            }
        }
        catch
        {
            // Best-effort cleanup only.
        }
    }

    private static void Report(IProgress<PackGenerationProgress>? progress, string phase, string? subtext, int completedWork, int totalWork)
    {
        progress?.Report(new PackGenerationProgress(phase, subtext, completedWork, totalWork));
    }

    private static void WritePackMeta(string directory, string packName)
    {
        var meta = new PackMeta(new PackInfo(34, packName));
        string json = JsonSerializer.Serialize(meta, AppJsonContext.Default.PackMeta);
        File.WriteAllText(Path.Combine(directory, "pack.mcmeta"), json);
    }

    private static void WriteSoundsJson(string assetsDir, Dictionary<string, SoundEventDefinition> soundEvents)
    {
        Dictionary<string, SoundEventDefinition> orderedSoundEvents = soundEvents
            .OrderBy(kvp => kvp.Key, StringComparer.Ordinal)
            .ToDictionary(kvp => kvp.Key, kvp => kvp.Value, StringComparer.Ordinal);

        string json = JsonSerializer.Serialize(orderedSoundEvents, AppJsonContext.Default.DictionaryStringSoundEventDefinition);
        File.WriteAllText(Path.Combine(assetsDir, "sounds.json"), json);
    }

    private static Dictionary<string, ISoundSourceProcessor> BuildProcessorMap(IEnumerable<ISoundSourceProcessor> processors)
    {
        var map = new Dictionary<string, ISoundSourceProcessor>(StringComparer.OrdinalIgnoreCase);

        foreach (ISoundSourceProcessor processor in processors)
        {
            foreach (string extension in processor.SupportedExtensions)
            {
                if (string.IsNullOrWhiteSpace(extension))
                {
                    continue;
                }

                string normalized = extension.StartsWith('.') ? extension : $".{extension}";
                if (!map.TryAdd(normalized, processor))
                {
                    throw new InvalidOperationException($"A processor for extension '{normalized}' is already registered.");
                }
            }
        }

        return map;
    }
}
