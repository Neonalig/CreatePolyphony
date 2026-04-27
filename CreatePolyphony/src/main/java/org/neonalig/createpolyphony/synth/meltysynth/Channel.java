package org.neonalig.createpolyphony.synth.meltysynth;

final class Channel {
    private final boolean percussionChannel;

    private int bankNumber;
    private int patchNumber;
    private short modulation;
    private short volume;
    private short pan;
    private short expression;
    private boolean holdPedal;
    private byte reverbSend;
    private byte chorusSend;
    private short rpn;
    private short pitchBendRange;
    private short coarseTune;
    private short fineTune;
    private float pitchBend;
    private DataType lastDataType;

    Channel(boolean percussionChannel) {
        this.percussionChannel = percussionChannel;
        reset();
    }

    void reset() {
        bankNumber = percussionChannel ? 128 : 0;
        patchNumber = 0;
        modulation = 0;
        volume = (short) (100 << 7);
        pan = (short) (64 << 7);
        expression = (short) (127 << 7);
        holdPedal = false;
        reverbSend = 40;
        chorusSend = 0;
        rpn = -1;
        pitchBendRange = (short) (2 << 7);
        coarseTune = 0;
        fineTune = 8192;
        pitchBend = 0F;
        lastDataType = DataType.NONE;
    }

    void resetAllControllers() {
        modulation = 0;
        expression = (short) (127 << 7);
        holdPedal = false;
        rpn = -1;
        pitchBend = 0F;
    }

    void setBank(int value) {
        bankNumber = percussionChannel ? value + 128 : value;
    }

    void setPatch(int value) { patchNumber = value; }
    void setModulationCoarse(int value) { modulation = (short) ((modulation & 0x7F) | (value << 7)); }
    void setModulationFine(int value) { modulation = (short) ((modulation & 0xFF80) | value); }
    void setVolumeCoarse(int value) { volume = (short) ((volume & 0x7F) | (value << 7)); }
    void setVolumeFine(int value) { volume = (short) ((volume & 0xFF80) | value); }
    void setPanCoarse(int value) { pan = (short) ((pan & 0x7F) | (value << 7)); }
    void setPanFine(int value) { pan = (short) ((pan & 0xFF80) | value); }
    void setExpressionCoarse(int value) { expression = (short) ((expression & 0x7F) | (value << 7)); }
    void setExpressionFine(int value) { expression = (short) ((expression & 0xFF80) | value); }
    void setHoldPedal(int value) { holdPedal = value >= 64; }
    void setReverbSend(int value) { reverbSend = (byte) value; }
    void setChorusSend(int value) { chorusSend = (byte) value; }
    void setRpnCoarse(int value) { rpn = (short) ((rpn & 0x7F) | (value << 7)); lastDataType = DataType.RPN; }
    void setRpnFine(int value) { rpn = (short) ((rpn & 0xFF80) | value); lastDataType = DataType.RPN; }
    void setNrpnCoarse(int value) { lastDataType = DataType.NRPN; }
    void setNrpnFine(int value) { lastDataType = DataType.NRPN; }

    void dataEntryCoarse(int value) {
        if (lastDataType != DataType.RPN) return;
        switch (rpn) {
            case 0 -> pitchBendRange = (short) ((pitchBendRange & 0x7F) | (value << 7));
            case 1 -> fineTune = (short) ((fineTune & 0x7F) | (value << 7));
            case 2 -> coarseTune = (short) (value - 64);
            default -> {}
        }
    }

    void dataEntryFine(int value) {
        if (lastDataType != DataType.RPN) return;
        switch (rpn) {
            case 0 -> pitchBendRange = (short) ((pitchBendRange & 0xFF80) | value);
            case 1 -> fineTune = (short) ((fineTune & 0xFF80) | value);
            default -> {}
        }
    }

    void setPitchBend(int value1, int value2) {
        pitchBend = (1F / 8192F) * ((value1 | (value2 << 7)) - 8192);
    }

    boolean percussionChannel() { return percussionChannel; }
    int bankNumber() { return bankNumber; }
    int patchNumber() { return patchNumber; }
    float modulation() { return (50F / 16383F) * modulation; }
    float volume() { return (1F / 16383F) * volume; }
    float pan() { return (100F / 16383F) * pan - 50F; }
    float expression() { return (1F / 16383F) * expression; }
    boolean holdPedal() { return holdPedal; }
    float reverbSend() { return (1F / 127F) * (reverbSend & 0xFF); }
    float chorusSend() { return (1F / 127F) * (chorusSend & 0xFF); }
    float pitchBendRange() { return (pitchBendRange >> 7) + 0.01F * (pitchBendRange & 0x7F); }
    float tune() { return coarseTune + (1F / 8192F) * (fineTune - 8192); }
    float pitchBend() { return pitchBendRange() * pitchBend; }

    private enum DataType { NONE, RPN, NRPN }
}

