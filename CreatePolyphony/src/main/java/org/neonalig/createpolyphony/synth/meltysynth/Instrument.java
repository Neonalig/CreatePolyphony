package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.IOException;
import java.util.Arrays;

public final class Instrument {
	static final Instrument DEFAULT = new Instrument();

	private final String name;
	private final InstrumentRegion[] regions;

	private Instrument() {
		name = "Default";
		regions = new InstrumentRegion[0];
	}

	private Instrument(InstrumentInfo info, Zone[] zones, SampleHeader[] samples) throws IOException {
		this.name = info.name();
		int zoneCount = info.zoneEndIndex() - info.zoneStartIndex() + 1;
		if (zoneCount <= 0) {
			throw new IOException("The instrument '" + info.name() + "' has no zone.");
		}
		Zone[] zoneSpan = Arrays.copyOfRange(zones, info.zoneStartIndex(), info.zoneStartIndex() + zoneCount);
		this.regions = InstrumentRegion.create(this, zoneSpan, samples);
	}

	static Instrument[] create(InstrumentInfo[] infos, Zone[] zones, SampleHeader[] samples) throws IOException {
		if (infos.length <= 1) {
			throw new IOException("No valid instrument was found.");
		}
		Instrument[] instruments = new Instrument[infos.length - 1];
		for (int i = 0; i < instruments.length; i++) {
			instruments[i] = new Instrument(infos[i], zones, samples);
		}
		return instruments;
	}

	@Override
	public String toString() {
		return name;
	}

	public String name() { return name; }
	public InstrumentRegion[] regions() { return regions; }
	InstrumentRegion[] regionArray() { return regions; }
}

