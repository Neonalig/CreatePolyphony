# CreatePolyphony.Converter.CLI

Small helper CLI for turning SF2 or SFZ sources into a Minecraft resource pack for Create: Polyphony.

## What it does

- reads a soundfont file (`.sf2` or `.sfz`)
- generates the pack structure and audio assets
- writes `sounds.json` with stable, padded ids
- writes `pack.mcmeta` using the chosen pack name

## Inputs

You can provide an input file in either of these ways:

- command line: a path to an `.sf2` or `.sfz` file
- dialogues: if no args are provided, the app prompts for the input file

## Outputs

The tool creates a resource pack containing the generated audio and metadata.

- `--zip` produces a Minecraft-ready zip archive
- `--folder` writes the unpacked pack to a folder
- dialogue-driven runs always finalise to a zip

## Command line usage

Usage syntax: `CreatePolyphony.Converter.CLI.exe <input.sf2|input.sfz> [--zip <output.zip> | --folder <output-folder>] [--name <pack name>]`

Notes:

- `--name` is optional; if omitted, the pack name defaults to the input file name
- `--zip` and `--folder` are mutually exclusive
- when using dialogues, the save name also becomes the default pack name

## Example

```powershell
CreatePolyphony.Converter.CLI.exe "C:\Samples\grand_piano.sf2" --zip "C:\Exports\grand_piano.zip" --name "Grand Piano"
```

