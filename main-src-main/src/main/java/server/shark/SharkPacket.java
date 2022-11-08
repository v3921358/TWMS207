/*
 * This file was designed for Titanium.
 * Do not redistribute without explicit permission from the
 * developer(s).
 */
package server.shark;

import java.util.Arrays;
import tools.data.MaplePacketLittleEndianWriter;

public class SharkPacket {

    public final byte[] info;
    private final long timestamp;
    public final boolean outbound;
    public int opcode;
    private boolean invalid = false;

    public SharkPacket(byte[] info, boolean out) {
        this.info = info;
        this.timestamp = System.currentTimeMillis();
        this.outbound = out;
        try {
            this.opcode = (short) (((info[1] & 0xFF) << 8) + (info[0] & 0xFF));
        } catch (ArrayIndexOutOfBoundsException aiobe) {
            opcode = -1;
            this.invalid = true;
        }
    }

    public SharkPacket(byte[] info, int opcode, boolean out, long timestamp) {
        this.info = info;
        this.timestamp = timestamp;
        this.outbound = out;
        this.opcode = opcode;
    }

    public void dump(MaplePacketLittleEndianWriter mplew, int mapleSharkVersion) {
        if (invalid) {
            return;
        }

        int size = info.length - 2; // don't include opcode
        if (mapleSharkVersion < 0x2020) {
            if (outbound) {
                size |= 0x8000;
            }
        }

        mplew.writeLong(timestamp);
        if (mapleSharkVersion >= 0x2027) {
            mplew.writeInt(size);
        } else {
            mplew.writeShort(size);
        }
        mplew.writeShort(opcode);
        if (mapleSharkVersion >= 0x2020) {
            mplew.write(outbound);
        }
        if (info.length > 2) {
            mplew.write(Arrays.copyOfRange(info, 2, info.length));
        }
        if (mapleSharkVersion >= 0x2025) {
            mplew.writeInt(0);
            mplew.writeInt(0);
        }
    }
}
