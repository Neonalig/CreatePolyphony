namespace CreatePolyphony.Converter.CLI.Models;

[JsonSourceGenerationOptions(WriteIndented = true)]
[JsonSerializable(typeof(PackMeta))]
[JsonSerializable(typeof(Dictionary<string, SoundEventDefinition>))]
public partial class AppJsonContext : JsonSerializerContext;
