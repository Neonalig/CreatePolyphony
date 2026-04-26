using CreatePolyphony.Converter.CLI.Models;
using MeltySynth;

namespace CreatePolyphony.Converter.CLI.Services;

public class Sf2Processor(AudioEncoder encoder) : ISoundSourceProcessor
{
    private readonly int[] targetNotes = [36, 60, 84]; // C2, C4, C6
    public IReadOnlyCollection<string> SupportedExtensions { get; } = [".sf2"];

    public int EstimateWorkUnits(string sf2Path)
    {
        var soundFont = new SoundFont(sf2Path);
        return soundFont.Presets.Count(p => !string.IsNullOrWhiteSpace(CleanName(p.Name))) * targetNotes.Length;
    }

    public Task ProcessAsync(
        string sf2Path,
        string soundsDir,
        Dictionary<string, SoundEventDefinition> soundEvents,
        IProgress<PackGenerationProgress>? progress = null,
        int completedWorkStart = 0,
        int totalWork = 0)
    {
        var soundFont = new SoundFont(sf2Path);
        var settings = new SynthesizerSettings(44100);
        int completedWork = completedWorkStart;

        foreach (Preset preset in soundFont.Presets)
        {
            string instrumentName = CleanName(preset.Name);
            if (string.IsNullOrWhiteSpace(instrumentName)) continue;

            string instDir = Path.Combine(soundsDir, instrumentName);
            Directory.CreateDirectory(instDir);
            string instrumentId = FormatInstrumentId(preset.PatchNumber + 1);

            foreach (int note in targetNotes)
            {
                string noteName = GetNoteName(note);
                string oggFilename = $"{noteName}.ogg";
                string outPath = Path.Combine(instDir, oggFilename);

                SynthesizeNote(soundFont, settings, preset, note, outPath);

                string eventName = $"instruments.{instrumentId}.{noteName}";
                soundEvents[eventName] = new SoundEventDefinition(
                    Constants.SFX_CATEGORY,
                    [$"{Constants.MOD_ID}:instruments/{instrumentName}/{noteName}"]
                );

                completedWork++;
                progress?.Report(new PackGenerationProgress("Rendering SF2 preset", instrumentName, completedWork, totalWork));
            }
        }

        return Task.CompletedTask;
    }

    private void SynthesizeNote(SoundFont font, SynthesizerSettings settings, Preset preset, int note, string outPath)
    {
        var synth = new Synthesizer(font, settings);
        synth.ProcessMidiMessage(0, 0xC0, preset.PatchNumber, 0); // Program change

        int sampleRate = settings.SampleRate;
        int holdSamples = sampleRate * 2; // 2 seconds
        int tailSamples = sampleRate * 1; // 1 second
        int totalSamples = holdSamples + tailSamples;

        // Allocate total memory once on the heap
        float[] leftBuffer = new float[totalSamples];
        float[] rightBuffer = new float[totalSamples];

        synth.NoteOn(0, note, 100);

        // Render attack/sustain directly into the first 2 seconds of the buffer
        synth.Render(
            leftBuffer.AsSpan(0, holdSamples),
            rightBuffer.AsSpan(0, holdSamples));

        synth.NoteOff(0, note);

        // Render the tail directly into the remaining 1 second of the buffer
        synth.Render(
            leftBuffer.AsSpan(holdSamples, tailSamples),
            rightBuffer.AsSpan(holdSamples, tailSamples));

        // Convert to short[] for the encoder, avoiding LINQ overhead on hot paths
        short[] shortLeft = new short[totalSamples];
        short[] shortRight = new short[totalSamples];

        for (int i = 0; i < totalSamples; i++)
        {
            // Clamp to prevent overflow clipping if the synth outputs > 1.0f or < -1.0f
            float l = Math.Clamp(leftBuffer[i], -1f, 1f);
            float r = Math.Clamp(rightBuffer[i], -1f, 1f);

            shortLeft[i] = (short)(l * short.MaxValue);
            shortRight[i] = (short)(r * short.MaxValue);
        }

        encoder.EncodeOgg(shortLeft, shortRight, sampleRate, outPath);
    }

    private static string CleanName(string input)
    {
        return Regex.Replace(input, @"[^a-z0-9_]", "_", RegexOptions.IgnoreCase).ToLowerInvariant();
    }

    private static string GetNoteName(int midiNote)
    {
        return midiNote switch
        {
            36 => "c2",
            60 => "c4",
            84 => "c6",
            _ => $"n{midiNote}"
        };
    }

    private static string FormatInstrumentId(int index)
    {
        return Math.Clamp(index, 1, 999).ToString("D3");
    }
}
