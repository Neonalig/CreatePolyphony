using System.Runtime.InteropServices;
using CreatePolyphony.Converter.CLI.Models;
using CreatePolyphony.Converter.CLI.Services;
using CreatePolyphony.Converter.CLI.Services.Implementations;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace CreatePolyphony.Converter.CLI;

public static class Program
{
    private const string inputFilter = "SoundFont/SFZ Files\0*.sf2;*.sfz\0All Files\0*.*\0\0";
    private const string outputFilter = "Zip Files\0*.zip\0All Files\0*.*\0\0";
    private const string defaultPackPrefix = "CreatePolyphony";

    async public static Task Main(string[] args)
    {
        IHostBuilder builder = Host.CreateDefaultBuilder(args);

        builder.ConfigureServices((_, services) =>
        {
            RegisterOSSpecificServices(services);
            services.AddSingleton<AudioEncoder>();
            services.AddSingleton<ISoundSourceProcessor, Sf2Processor>();
            services.AddSingleton<ISoundSourceProcessor, SfzProcessor>();
            services.AddSingleton<PackGeneratorService>();
        });

        using IHost host = builder.Build();

        var generator = host.Services.GetRequiredService<PackGeneratorService>();
        var dialogService = host.Services.GetRequiredService<IFileDialogService>();

        string? inputPath;
        string? outputPath;
        bool finalizeToZip;
        string packName;

        if (args.Length == 0)
        {
            inputPath = ResolveInputPath(dialogService);
            if (string.IsNullOrWhiteSpace(inputPath))
            {
                Console.WriteLine("No input file provided or selected. Exiting.");
                return;
            }

            packName = GetDefaultPackName(inputPath);
            outputPath = ResolveDialogOutputPath(dialogService, packName);
            if (string.IsNullOrWhiteSpace(outputPath))
            {
                Console.WriteLine("No output destination provided or selected. Exiting.");
                return;
            }

            finalizeToZip = true;
        }
        else
        {
            if (!TryParseArguments(args, out inputPath, out outputPath, out finalizeToZip, out packName, out string errorMessage))
            {
                Console.WriteLine(errorMessage);
                return;
            }

            if (string.IsNullOrWhiteSpace(outputPath))
            {
                outputPath = ResolveDialogOutputPath(dialogService, packName);
                finalizeToZip = true;
            }
        }

        if (string.IsNullOrWhiteSpace(outputPath))
        {
            Console.WriteLine("No output destination provided or selected. Exiting.");
            return;
        }

        await generator.GeneratePackAsync(inputPath, outputPath, finalizeToZip, packName, new ConsoleProgressReporter());
    }

    private static void RegisterOSSpecificServices(IServiceCollection services)
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            services.AddSingleton<IFileDialogService, WindowsFileDialogService>();
        }
        else if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
        {
            services.AddSingleton<IFileDialogService, MacFileDialogService>();
        }
        else if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
        {
            services.AddSingleton<IFileDialogService, LinuxFileDialogService>();
        }
        else
        {
            services.AddSingleton<IFileDialogService, FallbackFileDialogService>();
        }
    }

    private static string? ResolveInputPath(IFileDialogService dialogService)
    {
        return dialogService.PromptForOpenFile(inputFilter);
    }

    private static string? ResolveDialogOutputPath(IFileDialogService dialogService, string packName)
    {
        string defaultZipName = $"{packName}.zip";
        string? outputPath = dialogService.PromptForSaveFile(outputFilter, defaultZipName);

        if (!string.IsNullOrWhiteSpace(outputPath) && !string.Equals(Path.GetExtension(outputPath), ".zip", StringComparison.OrdinalIgnoreCase))
        {
            outputPath = Path.ChangeExtension(outputPath, ".zip");
        }

        return outputPath;
    }

    private static bool TryParseArguments(
        string[] args,
        out string inputPath,
        out string? outputPath,
        out bool finalizeToZip,
        out string packName,
        out string errorMessage)
    {
        inputPath = string.Empty;
        outputPath = null;
        finalizeToZip = true;
        packName = string.Empty;
        errorMessage = string.Empty;

        if (args.Length == 0)
        {
            errorMessage = "No arguments supplied.";
            return false;
        }

        inputPath = args[0];
        if (!File.Exists(inputPath))
        {
            errorMessage = $"Input file not found: {inputPath}";
            return false;
        }

        packName = GetDefaultPackName(inputPath);

        bool sawZip = false;
        bool sawFolder = false;

        for (int i = 1; i < args.Length; i++)
        {
            string arg = args[i];

            if (string.Equals(arg, "--zip", StringComparison.OrdinalIgnoreCase))
            {
                if (sawFolder)
                {
                    errorMessage = "Specify either --zip or --folder, not both.";
                    return false;
                }

                if (++i >= args.Length || IsSwitch(args[i]))
                {
                    errorMessage = "Missing path after --zip.";
                    return false;
                }

                sawZip = true;
                finalizeToZip = true;
                outputPath = args[i];
                continue;
            }

            if (string.Equals(arg, "--folder", StringComparison.OrdinalIgnoreCase))
            {
                if (sawZip)
                {
                    errorMessage = "Specify either --zip or --folder, not both.";
                    return false;
                }

                if (++i >= args.Length || IsSwitch(args[i]))
                {
                    errorMessage = "Missing folder path after --folder.";
                    return false;
                }

                sawFolder = true;
                finalizeToZip = false;
                outputPath = args[i];
                continue;
            }

            if (string.Equals(arg, "--name", StringComparison.OrdinalIgnoreCase))
            {
                if (++i >= args.Length || IsSwitch(args[i]) || string.IsNullOrWhiteSpace(args[i]))
                {
                    errorMessage = "Missing pack name after --name.";
                    return false;
                }

                packName = args[i].Trim();
                continue;
            }

            errorMessage = $"Unknown argument: {arg}";
            return false;
        }

        return true;
    }

    private static bool IsSwitch(string value)
    {
        return value.StartsWith("--", StringComparison.Ordinal);
    }

    private static string GetDefaultPackName(string inputPath)
    {
        string baseName = Path.GetFileNameWithoutExtension(inputPath);
        return string.IsNullOrWhiteSpace(baseName) ? defaultPackPrefix : baseName.Trim();
    }

    private sealed class ConsoleProgressReporter : IProgress<PackGenerationProgress>
    {
        private readonly Stopwatch stopwatch = Stopwatch.StartNew();
        private int lastLineLength;
        private bool finished;

        public void Report(PackGenerationProgress value)
        {
            if (finished)
            {
                return;
            }

            string message = BuildMessage(value);

            if (Console.IsOutputRedirected)
            {
                Console.WriteLine(message);
            }
            else
            {
                int width = Math.Max(lastLineLength, message.Length);
                Console.Write($"\r{message.PadRight(width)}");
                lastLineLength = width;

                if (value.TotalWork > 0 && value.CompletedWork >= value.TotalWork)
                {
                    Console.WriteLine();
                    finished = true;
                }
            }
        }

        private string BuildMessage(PackGenerationProgress value)
        {
            double progress = value.Progress;
            const int barWidth = 24;
            int filled = (int)Math.Round(progress * barWidth);
            filled = Math.Clamp(filled, 0, barWidth);

            string bar = $"[{new string('█', filled)}{new string('░', barWidth - filled)}]";
            string eta = value.CompletedWork <= 0 ? "ETA --:--" : $"ETA {FormatEta(CalculateEta(value.CompletedWork, value.TotalWork))}";
            string subtext = string.IsNullOrWhiteSpace(value.Subtext) ? value.Phase : $"{value.Phase} | {value.Subtext}";
            return $"{bar} {progress:P0} {eta} {subtext}";
        }

        private TimeSpan CalculateEta(int completedWork, int totalWork)
        {
            completedWork = Math.Clamp(completedWork, 1, Math.Max(totalWork, 1));
            totalWork = Math.Max(totalWork, 1);

            TimeSpan elapsed = stopwatch.Elapsed;
            double remainingUnits = totalWork - completedWork;
            double secondsPerUnit = elapsed.TotalSeconds / completedWork;
            return TimeSpan.FromSeconds(Math.Max(0, remainingUnits * secondsPerUnit));
        }

        private static string FormatEta(TimeSpan eta)
        {
            return eta.TotalHours >= 1
                ? eta.ToString(@"hh\:mm\:ss")
                : eta.ToString(@"mm\:ss");
        }
    }
}