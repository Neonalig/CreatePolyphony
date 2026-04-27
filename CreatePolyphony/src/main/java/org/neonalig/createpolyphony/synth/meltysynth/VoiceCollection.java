package org.neonalig.createpolyphony.synth.meltysynth;

import java.util.Iterator;
import java.util.NoSuchElementException;

final class VoiceCollection implements Iterable<Voice> {
    private final Voice[] voices;
    private int activeVoiceCount;

    VoiceCollection(Synthesizer synthesizer, int maxActiveVoiceCount) {
        voices = new Voice[maxActiveVoiceCount];
        for (int i = 0; i < voices.length; i++) {
            voices[i] = new Voice(synthesizer);
        }
        activeVoiceCount = 0;
    }

    Voice requestNew(InstrumentRegion region, int channel) {
        int exclusiveClass = region.exclusiveClass();
        if (exclusiveClass != 0) {
            for (int i = 0; i < activeVoiceCount; i++) {
                Voice voice = voices[i];
                if (voice.exclusiveClass() == exclusiveClass && voice.channel() == channel) {
                    return voice;
                }
            }
        }
        if (activeVoiceCount < voices.length) {
            Voice free = voices[activeVoiceCount];
            activeVoiceCount++;
            return free;
        }
        Voice candidate = null;
        float lowestPriority = Float.MAX_VALUE;
        for (int i = 0; i < activeVoiceCount; i++) {
            Voice voice = voices[i];
            float priority = voice.priority();
            if (priority < lowestPriority) {
                lowestPriority = priority;
                candidate = voice;
            } else if (priority == lowestPriority && candidate != null && voice.voiceLength() > candidate.voiceLength()) {
                candidate = voice;
            }
        }
        return candidate;
    }

    void process() {
        int i = 0;
        while (i < activeVoiceCount) {
            if (voices[i].process()) {
                i++;
            } else {
                activeVoiceCount--;
                Voice tmp = voices[i];
                voices[i] = voices[activeVoiceCount];
                voices[activeVoiceCount] = tmp;
            }
        }
    }

    void clear() {
        activeVoiceCount = 0;
    }

    int activeVoiceCount() { return activeVoiceCount; }

    @Override
    public Iterator<Voice> iterator() {
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < activeVoiceCount;
            }

            @Override
            public Voice next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return voices[index++];
            }
        };
    }
}

