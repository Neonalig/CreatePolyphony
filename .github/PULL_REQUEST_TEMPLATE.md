## Summary

<!-- One sentence describing what this PR does. -->

## Type of Change

<!-- Check all that apply. -->

- [ ] Bug fix
- [ ] New feature / instrument
- [ ] Refactor (no behaviour change)
- [ ] Documentation / wiki
- [ ] Build / CI change
- [ ] Other: <!-- describe -->

## Related Issues

<!-- Link any issues this PR closes or relates to, e.g. "Closes #42". -->

Closes #

## Description

<!-- What changed and why. Include any design decisions worth noting. -->

## Testing

<!-- Describe how you tested this change. Include Minecraft/NeoForge versions used. -->

- Minecraft version:
- NeoForge version:
- Create version:
- Create:Sound of Steam version:

## Checklist

- [ ] I have read [CONTRIBUTING.md](https://github.com/Neonalig/CreatePolyphony/wiki/Contributing).
- [ ] Server-side routing logic remains deterministic and runs on the server thread only.
- [ ] Client audio code does not mutate shared state from a non-client thread.
- [ ] New content is data-driven where possible (`src/main/resources/data/createpolyphony/`).
- [ ] No breaking changes to existing item NBT / link data format (or migration is handled).
- [ ] If adding an instrument: recipe, advancement, lang entry, texture, and model are all included.
- [ ] I have tested in a dev environment with both Create and Create:Sound of Steam present.
