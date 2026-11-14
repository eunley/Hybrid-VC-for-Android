package com.svc.voice;

import java.io.DataInput;
import java.io.IOException;

/** Bounded, progress-only SVP1 stream. PCM travels on the separate capture socket. */
public final class VmicProgressWire {
    public static final int MAGIC = 0x53565031; // SVP1
    public static final int VERSION = 1;
    public static final int RATE = 48000;
    public static final int MAX_TRACKS = 8;
    public static final int PROGRESS = 1;
    public static final int HEAD_VALID = 1;
    public static final long STALE_NS = 1500000000L;
    public static boolean isFresh(long receivedNs, long nowNs) {
        return nowNs - receivedNs >= 0 && nowNs - receivedNs < STALE_NS;
    }
    private VmicProgressWire() { }

    public static final class Frame {
        public final long sequence;
        public final long[] written48, played48;
        public final boolean[] valid;
        Frame(long sequence, long[] written, long[] played, boolean[] valid) {
            this.sequence = sequence;
            this.written48 = written;
            this.played48 = played;
            this.valid = valid;
        }
        /** -1 means no track has demonstrated actual consumption. */
        public long effectiveHead48() {
            long max = -1;
            for (int i = 0; i < valid.length; i++) {
                if (valid[i] && played48[i] > 0) max = Math.max(max, played48[i]);
            }
            return max;
        }
    }

    /** One reader per connection; a fresh handshake resets every per-track counter. */
    public static final class Reader {
        private final DataInput in;
        private final int tracks;
        private final long[] lastWritten, lastPlayed;
        private long sequence;
        public Reader(DataInput in) throws IOException {
            this.in = in;
            if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readInt() != RATE)
                throw new IOException("bad progress handshake");
            tracks = in.readInt();
            if (tracks < 1 || tracks > MAX_TRACKS) throw new IOException("bad progress track count");
            lastWritten = new long[tracks];
            lastPlayed = new long[tracks];
        }
        public Frame read() throws IOException {
            int type = in.readInt();
            int length = in.readInt();
            // Check BEFORE reading/allocating the body. Unknown types cannot be PCM.
            if (type != PROGRESS || length != 12 + tracks * 20)
                throw new IOException("bad progress frame type/length");
            long next = in.readLong();
            if (next <= sequence || in.readInt() != tracks)
                throw new IOException("bad progress sequence/track count");
            long[] written = new long[tracks], played = new long[tracks];
            boolean[] valid = new boolean[tracks];
            for (int i = 0; i < tracks; i++) {
                written[i] = in.readLong();
                played[i] = in.readLong();
                int flags = in.readInt();
                if (written[i] < lastWritten[i] || played[i] < lastPlayed[i]
                        || played[i] > written[i] || (flags & ~HEAD_VALID) != 0)
                    throw new IOException("invalid progress counters/flags");
                valid[i] = (flags & HEAD_VALID) != 0;
            }
            // Commit only after the entire frame passes validation, including all tracks.
            System.arraycopy(written, 0, lastWritten, 0, tracks);
            System.arraycopy(played, 0, lastPlayed, 0, tracks);
            sequence = next;
            return new Frame(next, written, played, valid);
        }
    }

    /** Integer conversion after accumulation; no per-poll rounding drift. */
    public static long toInput16(long frames48) { return frames48 < 0 ? -1 : frames48 / 3; }
}
