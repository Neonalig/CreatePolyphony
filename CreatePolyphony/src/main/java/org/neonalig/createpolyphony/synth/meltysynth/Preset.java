package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;
import java.util.Arrays;

public final class Preset {
	static final Preset DEFAULT = new Preset();

	private final String name;
	private final int patchNumber;
	private final int bankNumber;
	private final int library;
	private final int genre;
	private final int morphology;
	private final PresetRegion[] regions;

	private Preset() {
		name = "Default";
		patchNumber = 0;
		bankNumber = 0;
		library = 0;
		genre = 0;
		morphology = 0;
		regions = new PresetRegion[0];
	}

	private Preset(PresetInfo info, Zone[] zones, Instrument[] instruments) throws IOException {
		this.name = info.name();
		this.patchNumber = info.patchNumber();
		this.bankNumber = info.bankNumber();
		this.library = info.library();
		this.genre = info.genre();
		this.morphology = info.morphology();
		int zoneCount = info.zoneEndIndex() - info.zoneStartIndex() + 1;
		if (zoneCount <= 0) {
			throw new IOException("The preset '" + info.name() + "' has no zone.");
		}
		Zone[] zoneSpan = Arrays.copyOfRange(zones, info.zoneStartIndex(), info.zoneStartIndex() + zoneCount);
		this.regions = PresetRegion.create(this, zoneSpan, instruments);
	}

	static Preset[] create(PresetInfo[] infos, Zone[] zones, Instrument[] instruments) throws IOException {
		if (infos.length <= 1) {
			throw new IOException("No valid preset was found.");
		}
		Preset[] presets = new Preset[infos.length - 1];
		for (int i = 0; i < presets.length; i++) {
			presets[i] = new Preset(infos[i], zones, instruments);
		}
		return presets;
	}

	@Override
	public String toString() {
		return name;
	}

	public String name() { return name; }
	public int program() { return patchNumber; }
	public int bank() { return bankNumber; }
	public PresetRegion[] regions() { return regions; }
	PresetRegion[] regionArray() { return regions; }
}

