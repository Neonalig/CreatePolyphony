package org.neonalig.createpolyphony.synth.meltysynth;

final class MidiEventQueue {

    private final int[] type;
    private final int[] channel;
    private final int[] data1;
    private final int[] data2;
    private final int capacity;

    private int head;
    private int tail;
    private int size;

    MidiEventQueue(int capacity) {
        this.capacity = Math.max(32, capacity);
        this.type = new int[this.capacity];
        this.channel = new int[this.capacity];
        this.data1 = new int[this.capacity];
        this.data2 = new int[this.capacity];
    }

    synchronized void offer(int eventType, int eventChannel, int eventData1, int eventData2) {
        if (size == capacity) {
            head = (head + 1) % capacity;
            size--;
        }
        type[tail] = eventType;
        channel[tail] = eventChannel;
        data1[tail] = eventData1;
        data2[tail] = eventData2;
        tail = (tail + 1) % capacity;
        size++;
    }

    synchronized boolean poll(int[] out) {
        if (size == 0) {
            return false;
        }
        out[0] = type[head];
        out[1] = channel[head];
        out[2] = data1[head];
        out[3] = data2[head];
        head = (head + 1) % capacity;
        size--;
        return true;
    }

    synchronized void clear() {
        head = 0;
        tail = 0;
        size = 0;
    }
}

