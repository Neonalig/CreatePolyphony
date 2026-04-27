package org.neonalig.createpolyphony.synth.meltysynth;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class MidiFile {
    private Message[] messages;
    private Duration[] times;

    public MidiFile(InputStream stream) throws IOException { load(stream, 0, MidiFileLoopType.NONE); }
    public MidiFile(InputStream stream, int loopPoint) throws IOException { load(stream, loopPoint, MidiFileLoopType.NONE); }
    public MidiFile(InputStream stream, MidiFileLoopType loopType) throws IOException { load(stream, 0, loopType); }
    public MidiFile(String path) throws IOException { this(new FileInputStream(path)); }
    public MidiFile(String path, int loopPoint) throws IOException { try (InputStream in = new FileInputStream(path)) { load(in, loopPoint, MidiFileLoopType.NONE); } }
    public MidiFile(String path, MidiFileLoopType loopType) throws IOException { try (InputStream in = new FileInputStream(path)) { load(in, 0, loopType); } }
    public MidiFile(File file) throws IOException { this(new FileInputStream(file)); }

    static Duration getTimeSpanFromSeconds(double value) {
        return Duration.ofNanos((long) (value * 1_000_000_000L));
    }

    private void load(InputStream stream, int loopPoint, MidiFileLoopType loopType) throws IOException {
        try (DataInputStream reader = new DataInputStream(stream)) {
            String chunkType = BinaryReaderEx.readFourCC(reader);
            if (!"MThd".equals(chunkType)) {
                throw new IOException("The chunk type must be 'MThd', but was '" + chunkType + "'.");
            }
            int size = BinaryReaderEx.readInt32BigEndian(reader);
            if (size != 6) {
                throw new IOException("The MThd chunk has invalid data.");
            }
            short format = BinaryReaderEx.readInt16BigEndian(reader);
            if (!(format == 0 || format == 1)) {
                throw new UnsupportedOperationException("The format " + format + " is not supported.");
            }
            int trackCount = BinaryReaderEx.readInt16BigEndian(reader);
            int resolution = BinaryReaderEx.readInt16BigEndian(reader);

            @SuppressWarnings("unchecked")
            List<Message>[] messageLists = new List[trackCount];
            @SuppressWarnings("unchecked")
            List<Integer>[] tickLists = new List[trackCount];
            for (int i = 0; i < trackCount; i++) {
                TrackData track = readTrack(reader, loopType);
                messageLists[i] = track.messages();
                tickLists[i] = track.ticks();
            }

            if (loopPoint != 0) {
                List<Integer> tickList = tickLists[0];
                List<Message> messageList = messageLists[0];
                if (loopPoint <= tickList.get(tickList.size() - 1)) {
                    for (int i = 0; i < tickList.size(); i++) {
                        if (tickList.get(i) >= loopPoint) {
                            tickList.add(i, loopPoint);
                            messageList.add(i, Message.loopStart());
                            break;
                        }
                    }
                } else {
                    tickList.add(loopPoint);
                    messageList.add(Message.loopStart());
                }
            }

            MergedTracks merged = mergeTracks(messageLists, tickLists, resolution);
            messages = merged.messages();
            times = merged.times();
        }
    }

    private static TrackData readTrack(DataInputStream reader, MidiFileLoopType loopType) throws IOException {
        String chunkType = BinaryReaderEx.readFourCC(reader);
        if (!"MTrk".equals(chunkType)) {
            throw new IOException("The chunk type must be 'MTrk', but was '" + chunkType + "'.");
        }
        int size = BinaryReaderEx.readInt32BigEndian(reader);
        byte[] trackData = reader.readNBytes(size);
        if (trackData.length != size) {
            throw new IOException("Unexpected EOF while reading track.");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(trackData));
        List<Message> messages = new ArrayList<>();
        List<Integer> ticks = new ArrayList<>();
        int tick = 0;
        byte lastStatus = 0;
        while (in.available() > 0) {
            int delta = BinaryReaderEx.readIntVariableLength(in);
            int first = in.readUnsignedByte();
            tick = Math.addExact(tick, delta);
            if ((first & 0x80) == 0) {
                int command = lastStatus & 0xF0;
                if (command == 0xC0 || command == 0xD0) {
                    messages.add(Message.common(lastStatus, (byte) first));
                    ticks.add(tick);
                } else {
                    int data2 = in.readUnsignedByte();
                    messages.add(Message.common(lastStatus, (byte) first, (byte) data2, loopType));
                    ticks.add(tick);
                }
                continue;
            }
            switch (first) {
                case 0xF0, 0xF7 -> discardData(in);
                case 0xFF -> {
                    int metaType = in.readUnsignedByte();
                    switch (metaType) {
                        case 0x2F -> {
                            in.readUnsignedByte();
                            messages.add(Message.endOfTrack());
                            ticks.add(tick);
                            return new TrackData(messages, ticks);
                        }
                        case 0x51 -> {
                            messages.add(Message.tempoChange(readTempo(in)));
                            ticks.add(tick);
                        }
                        default -> discardData(in);
                    }
                }
                default -> {
                    int command = first & 0xF0;
                    if (command == 0xC0 || command == 0xD0) {
                        byte data1 = (byte) in.readUnsignedByte();
                        messages.add(Message.common((byte) first, data1));
                        ticks.add(tick);
                    } else {
                        byte data1 = (byte) in.readUnsignedByte();
                        byte data2 = (byte) in.readUnsignedByte();
                        messages.add(Message.common((byte) first, data1, data2, loopType));
                        ticks.add(tick);
                    }
                }
            }
            lastStatus = (byte) first;
        }
        return new TrackData(messages, ticks);
    }

    private static MergedTracks mergeTracks(List<Message>[] messageLists, List<Integer>[] tickLists, int resolution) {
        List<TimedMessage> all = new ArrayList<>();
        for (int ch = 0; ch < messageLists.length; ch++) {
            for (int i = 0; i < messageLists[ch].size(); i++) {
                all.add(new TimedMessage(tickLists[ch].get(i), messageLists[ch].get(i), ch, i));
            }
        }
        all.sort(Comparator.comparingInt(TimedMessage::tick).thenComparingInt(TimedMessage::track).thenComparingInt(TimedMessage::index));

        List<Message> mergedMessages = new ArrayList<>();
        List<Duration> mergedTimes = new ArrayList<>();
        int currentTick = 0;
        Duration currentTime = Duration.ZERO;
        double tempo = 120.0;
        for (TimedMessage entry : all) {
            int nextTick = entry.tick();
            int deltaTick = nextTick - currentTick;
            Duration deltaTime = getTimeSpanFromSeconds(60.0 / (resolution * tempo) * deltaTick);
            currentTick += deltaTick;
            currentTime = currentTime.plus(deltaTime);
            Message message = entry.message();
            if (message.type() == MessageType.TEMPO_CHANGE) {
                tempo = message.tempo();
            } else {
                mergedMessages.add(message);
                mergedTimes.add(currentTime);
            }
        }
        return new MergedTracks(mergedMessages.toArray(Message[]::new), mergedTimes.toArray(Duration[]::new));
    }

    private static int readTempo(DataInputStream in) throws IOException {
        int size = BinaryReaderEx.readIntVariableLength(in);
        if (size != 3) {
            throw new IOException("Failed to read the tempo value.");
        }
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return (b1 << 16) | (b2 << 8) | b3;
    }

    private static void discardData(DataInputStream in) throws IOException {
        int size = BinaryReaderEx.readIntVariableLength(in);
        int skipped = in.skipBytes(size);
        if (skipped != size) {
            throw new IOException("Unexpected EOF while discarding MIDI data.");
        }
    }

    public Duration length() { return times[times.length - 1]; }
    Message[] messages() { return messages; }
    Duration[] times() { return times; }

    private record TrackData(List<Message> messages, List<Integer> ticks) {}
    private record TimedMessage(int tick, Message message, int track, int index) {}
    private record MergedTracks(Message[] messages, Duration[] times) {}

    enum MessageType {
        NORMAL(0),
        TEMPO_CHANGE(252),
        LOOP_START(253),
        LOOP_END(254),
        END_OF_TRACK(255);

        private final int marker;

        MessageType(int marker) {
            this.marker = marker;
        }

        int marker() {
            return marker;
        }
    }

    static final class Message {
        private final byte channel;
        private final byte command;
        private final byte data1;
        private final byte data2;

        private Message(int channel, int command, int data1, int data2) {
            this.channel = (byte) channel;
            this.command = (byte) command;
            this.data1 = (byte) data1;
            this.data2 = (byte) data2;
        }

        static Message common(byte status, byte data1) {
            return new Message(status & 0x0F, status & 0xF0, data1 & 0xFF, 0);
        }

        static Message common(byte status, byte data1, byte data2, MidiFileLoopType loopType) {
            int channel = status & 0x0F;
            int command = status & 0xF0;
            if (command == 0xB0) {
                int d1 = data1 & 0xFF;
                return switch (loopType) {
                    case RPG_MAKER -> d1 == 111 ? loopStart() : new Message(channel, command, d1, data2 & 0xFF);
                    case INCREDIBLE_MACHINE -> {
                        if (d1 == 110) yield loopStart();
                        if (d1 == 111) yield loopEnd();
                        yield new Message(channel, command, d1, data2 & 0xFF);
                    }
                    case FINAL_FANTASY -> {
                        if (d1 == 116) yield loopStart();
                        if (d1 == 117) yield loopEnd();
                        yield new Message(channel, command, d1, data2 & 0xFF);
                    }
                    case NONE -> new Message(channel, command, d1, data2 & 0xFF);
                };
            }
            return new Message(channel, command, data1 & 0xFF, data2 & 0xFF);
        }

        static Message tempoChange(int tempo) {
            return new Message(MessageType.TEMPO_CHANGE.marker(), (tempo >> 16) & 0xFF, (tempo >> 8) & 0xFF, tempo & 0xFF);
        }
        static Message loopStart() { return new Message(MessageType.LOOP_START.marker(), 0, 0, 0); }
        static Message loopEnd() { return new Message(MessageType.LOOP_END.marker(), 0, 0, 0); }
        static Message endOfTrack() { return new Message(MessageType.END_OF_TRACK.marker(), 0, 0, 0); }

        MessageType type() {
            return switch (channel & 0xFF) {
                case 252 -> MessageType.TEMPO_CHANGE;
                case 253 -> MessageType.LOOP_START;
                case 254 -> MessageType.LOOP_END;
                case 255 -> MessageType.END_OF_TRACK;
                default -> MessageType.NORMAL;
            };
        }

        byte channel() { return channel; }
        byte command() { return command; }
        byte data1() { return data1; }
        byte data2() { return data2; }
        double tempo() { return 60000000.0 / (((command & 0xFF) << 16) | ((data1 & 0xFF) << 8) | (data2 & 0xFF)); }
    }
}

