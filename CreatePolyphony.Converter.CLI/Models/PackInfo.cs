namespace CreatePolyphony.Converter.CLI.Models;

public record PackInfo(
    [property: JsonPropertyName("pack_format")] int PackFormat,
    [property: JsonPropertyName("description")] string Description
);
