namespace CreatePolyphony.Converter.CLI.Models;

public record SoundEventDefinition(
    [property: JsonPropertyName("category")] string Category,
    [property: JsonPropertyName("sounds")] string[] Sounds
);
