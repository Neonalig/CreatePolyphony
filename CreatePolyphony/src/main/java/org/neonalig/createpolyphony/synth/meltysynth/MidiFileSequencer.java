package org.neonalig.createpolyphony.synth.meltysynth;

import java.time.Duration;

public final class MidiFileSequencer implements IAudioRenderer {
    private final Synthesizer synthesizer;
    private float speed;
    private MidiFile midiFile;
    private boolean loop;
    private int blockWrote;
    private Duration currentTime;
    private int msgIndex;
    private int loopIndex;
    private MessageHook onSendMessage;

    public MidiFileSequencer(Synthesizer synthesizer) {
        if (synthesizer == null) {
            throw new IllegalArgumentException("synthesizer must not be null");
        }
        this.synthesizer = synthesizer;
        this.speed = 1F;
    }

    public void play(MidiFile midiFile, boolean loop) {
        if (midiFile == null) {
            throw new IllegalArgumentException("midiFile must not be null");
        }
        this.midiFile = midiFile;
        this.loop = loop;
        this.blockWrote = synthesizer.blockSize();
        this.currentTime = Duration.ZERO;
        this.msgIndex = 0;
        this.loopIndex = 0;
        synthesizer.reset();
    }

    public void stop() {
        midiFile = null;
        synthesizer.reset();
    }

    @Override
    public void render(float[] left, float[] right, int offset, int length) {
        if (length < 0 || offset < 0 || offset + length > left.length || offset + length > right.length) {
            throw new IllegalArgumentException("Invalid render range.");
        }
        int wrote = 0;
        while (wrote < length) {
            if (blockWrote == synthesizer.blockSize()) {
                processEvents();
                blockWrote = 0;
                currentTime = currentTime.plus(MidiFile.getTimeSpanFromSeconds((double) speed * synthesizer.blockSize() / synthesizer.sampleRate()));
            }
            int srcRem = synthesizer.blockSize() - blockWrote;
            int dstRem = length - wrote;
            int rem = Math.min(srcRem, dstRem);
            synthesizer.render(left, right, offset + wrote, rem);
            blockWrote += rem;
            wrote += rem;
        }
    }

    private void processEvents() {
        if (midiFile == null) {
            return;
        }
        while (msgIndex < midiFile.messages().length) {
            Duration time = midiFile.times()[msgIndex];
            MidiFile.Message msg = midiFile.messages()[msgIndex];
            if (time.compareTo(currentTime) <= 0) {
                if (msg.type() == MidiFile.MessageType.NORMAL) {
                    if (onSendMessage == null) {
                        synthesizer.processMidiMessage(msg.channel(), msg.command(), msg.data1(), msg.data2());
                    } else {
                        onSendMessage.handle(synthesizer, msg.channel(), msg.command(), msg.data1(), msg.data2());
                    }
                } else if (loop) {
                    if (msg.type() == MidiFile.MessageType.LOOP_START) {
                        loopIndex = msgIndex;
                    } else if (msg.type() == MidiFile.MessageType.LOOP_END) {
                        currentTime = midiFile.times()[loopIndex];
                        msgIndex = loopIndex;
                        synthesizer.noteOffAll(false);
                    }
                }
                msgIndex++;
            } else {
                break;
            }
        }
        if (msgIndex == midiFile.messages().length && loop) {
            currentTime = midiFile.times()[loopIndex];
            msgIndex = loopIndex;
            synthesizer.noteOffAll(false);
        }
    }

    public Synthesizer synthesizer() { return synthesizer; }
    public MidiFile midiFile() { return midiFile; }
    public Duration position() { return currentTime; }
    public boolean endOfSequence() { return midiFile == null || msgIndex == midiFile.messages().length; }
    public float speed() { return speed; }
    public void speed(float value) {
        if (value < 0) throw new IllegalArgumentException("The playback speed must be a non-negative value.");
        speed = value;
    }
    public MessageHook onSendMessage() { return onSendMessage; }
    public void onSendMessage(MessageHook hook) { onSendMessage = hook; }

    @FunctionalInterface
    public interface MessageHook {
        void handle(Synthesizer synthesizer, int channel, int command, int data1, int data2);
    }
}

