namespace CreatePolyphony.Converter.CLI.Models;

public record PackMeta(
    [property: JsonPropertyName("pack")] PackInfo Pack
);
