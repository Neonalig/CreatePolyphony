package org.neonalig.createpolyphony.synth.meltysynth;

final class ChannelState {
    final boolean percussion;
    int bankMsb;
    int program;
    int pitchBend = 8192;
    float volume = 1F;
    float expression = 1F;
    float pan = 0F;
    float reverbSend;
    float chorusSend;
    float outputGain = 1F;
    boolean sustain;

    ChannelState(boolean percussion) {
        this.percussion = percussion;
        if (percussion) {
            bankMsb = 128;
        }
    }

    void resetForNewBank() {
        if (percussion) {
            bankMsb = 128;
        }
        program = 0;
        pitchBend = 8192;
        volume = 1F;
        expression = 1F;
        pan = 0F;
        reverbSend = 0F;
        chorusSend = 0F;
        outputGain = 1F;
        sustain = false;
    }

    void recomputeOutputGain() {
        outputGain = volume * expression;
    }
}

