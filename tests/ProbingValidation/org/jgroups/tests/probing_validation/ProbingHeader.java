package org.jgroups.tests.probing_validation;

import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.Header;
import org.jgroups.util.Bits;
import org.jgroups.util.Util;

import java.io.DataInput;
import java.io.DataOutput;

/**
 * Message header for probe messages.
 *
 * @author a7109534
 * @since 4.0
 */
public class ProbingHeader extends Header {
    public static final byte PROBING_PAST = 1;
    public static final byte PROBING_PRESENT = 2;

    private byte type = 0;
    private Address originator = null;
    private int timePeriod = -1;
    private long timeSent = -1;

    public ProbingHeader() {}

    public ProbingHeader(byte type, Address originator, int timePeriod, long timeSent) {
        this.type = type;
        this.originator = originator;
        this.timePeriod = timePeriod;
        this.timeSent = timeSent;
    }

    public byte getType() {
        return type;
    }

    public Address getOriginator() {
        return originator;
    }

    public int getTimePeriod() {
        return timePeriod;
    }

    public long getTimeSent() {
        return timeSent;
    }

    public boolean isPresent() {
        return type == PROBING_PRESENT;
    }

    @Override
    public int size() {
        return Global.BYTE_SIZE + Util.size(originator) + Global.INT_SIZE + Bits.size(timeSent);
    }

    @Override
    public void writeTo(DataOutput out) throws Exception {
        out.writeByte(type);
        Util.writeAddress(originator, out);
        out.writeInt(timePeriod);
        Bits.writeLong(timeSent, out);
    }

    @Override
    public void readFrom(DataInput in) throws Exception {
        type = in.readByte();
        originator = Util.readAddress(in);
        timePeriod = in.readInt();
        timeSent = Bits.readLong(in);
    }

    @Override
    public String toString() {
        return "ProbingHeader{" +
                "type=" + type2String(type) +
                ", originator=" + originator +
                ", timePeriod=" + timePeriod +
                ", timeSent=" + timeSent +
                '}';
    }

    private static String type2String(byte t) {
        switch (t) {
            case PROBING_PAST:
                return "PROBING_PAST";
            case PROBING_PRESENT:
                return "PROBING_PRESENT";
            default:
                return "UNDEFINED(" + t + ")";
        }
    }
}
