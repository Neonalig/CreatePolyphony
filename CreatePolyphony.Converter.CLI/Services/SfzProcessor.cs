using CreatePolyphony.Converter.CLI.Models;
using NAudio.Wave;

namespace CreatePolyphony.Converter.CLI.Services;

public partial class SfzProcessor(AudioEncoder encoder) : ISoundSourceProcessor
{
    private readonly int[] targetNotes = [36, 60, 84];
    public IReadOnlyCollection<string> SupportedExtensions { get; } = [".sfz"];

    public int EstimateWorkUnits(string sfzPath) => targetNotes.Length;

    async public Task ProcessAsync(
        string sfzPath,
        string soundsDir,
        Dictionary<string, SoundEventDefinition> soundEvents,
        IProgress<PackGenerationProgress>? progress = null,
        int completedWorkStart = 0,
        int totalWork = 0)
    {
        string instrumentName = Path.GetFileNameWithoutExtension(sfzPath).ToLowerInvariant();
        instrumentName = InstrumentNameRegex().Replace(instrumentName, "_");
        string instrumentId = FormatInstrumentId(1);

        string baseDir = Path.GetDirectoryName(sfzPath) ?? string.Empty;
        string instDir = Path.Combine(soundsDir, instrumentName);
        Directory.CreateDirectory(instDir);

        string[] lines = await File.ReadAllLinesAsync(sfzPath);
        List<(string Sample, int Key)> regions = ParseRegions(lines);
        int completedWork = completedWorkStart;

        foreach (int targetNote in targetNotes)
        {
            (string? Sample, int Key) bestRegion = FindBestRegion(regions, targetNote);
            string noteName = targetNote switch { 36 => "c2", 60 => "c4", 84 => "c6", _ => $"n{targetNote}" };

            if (bestRegion.Sample != null)
            {
                string samplePath = Path.Combine(baseDir, bestRegion.Sample.Replace('\\', Path.DirectorySeparatorChar));
                if (File.Exists(samplePath))
                {
                    string outPath = Path.Combine(instDir, $"{noteName}.ogg");

                    ConvertWavToOgg(samplePath, outPath);

                    string eventName = $"instruments.{instrumentId}.{noteName}";
                    soundEvents[eventName] = new SoundEventDefinition(
                        Constants.SFX_CATEGORY,
                        [$"{Constants.MOD_ID}:instruments/{instrumentName}/{noteName}"]
                    );
                }
            }

            completedWork++;
            progress?.Report(new PackGenerationProgress("Converting SFZ sample", instrumentName, completedWork, totalWork));
        }
    }

    private static List<(string Sample, int Key)> ParseRegions(string[] lines)
    {
        var regions = new List<(string Sample, int Key)>();
        string currentSample = string.Empty;
        int currentKey = 60; // Default middle C

        foreach (string line in lines)
        {
            string trimmed = line.Trim();
            if (trimmed.StartsWith("<region>"))
            {
                Match sampleMatch = Regex.Match(trimmed, @"sample=([^\s]+)");
                Match keyMatch = Regex.Match(trimmed, @"(?:key|pitch_keycenter)=(\d+)");

                if (sampleMatch.Success) currentSample = sampleMatch.Groups[1].Value;
                if (keyMatch.Success) currentKey = int.Parse(keyMatch.Groups[1].Value);

                if (!string.IsNullOrEmpty(currentSample))
                {
                    regions.Add((currentSample, currentKey));
                }
            }
        }
        return regions;
    }

    private static (string? Sample, int Key) FindBestRegion(List<(string Sample, int Key)> regions, int targetNote)
    {
        if (regions.Count == 0) return (string.Empty, 0);
        return regions.OrderBy(r => Math.Abs(r.Key - targetNote)).First();
    }

    private void ConvertWavToOgg(string wavPath, string oggPath)
    {
        using var reader = new AudioFileReader(wavPath);
        int sampleRate = reader.WaveFormat.SampleRate;
        int channels = reader.WaveFormat.Channels;
        
        long totalSamples = reader.Length / (reader.WaveFormat.BitsPerSample / 8);
        float[] buffer = new float[totalSamples];
        reader.Read(buffer, 0, buffer.Length);

        short[] left = new short[totalSamples / channels];
        short[] right = channels > 1 ? new short[totalSamples / channels] : left;

        for (int i = 0; i < left.Length; i++)
        {
            left[i] = (short)(buffer[i * channels] * short.MaxValue);
            if (channels > 1) right[i] = (short)(buffer[i * channels + 1] * short.MaxValue);
        }

        encoder.EncodeOgg(left, right, sampleRate, oggPath);
    }

    [GeneratedRegex(@"[^a-z0-9_]")]
    private static partial Regex InstrumentNameRegex();

    private static string FormatInstrumentId(int index)
    {
        return Math.Clamp(index, 1, 999).ToString("D3");
    }
}
