// ModbusRTU.java
// 

import com.fazecast.jSerialComm.SerialPort;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public class ModbusRTU {

    private final SerialPort serial;
    private final int slaveAddress;
    private final ReentrantLock commLock = new ReentrantLock();

    private int timeoutMs = 600;

    public ModbusRTU(SerialPort serial, int slaveAddress) {
        this.serial = serial;
        this.slaveAddress = slaveAddress & 0xFF;
    }

    public void setTimeout(int ms) {
        timeoutMs = ms;
    }

    // -------------------------------------------------------------------------
    //  Exception code decoder (from FC01-Series-VFD.pdf, page 87)
    // -------------------------------------------------------------------------
    private String decodeExceptionCode(int code) {
        return switch (code) {
            case 0x01 -> "Illegal command";
            case 0x02 -> "Illegal data address";
            case 0x03 -> "Illegal value / bad frame";
            case 0x04 -> "Operation failed";
            case 0x05 -> "Password error";
            case 0x06 -> "Data frame error / CRC mismatch";
            case 0x07 -> "Write not allowed";
            case 0x08 -> "Parameter cannot be modified during running";
            case 0x09 -> "Password protection active";
            default -> "Unknown exception code: " + code;
        };
    }

    // -------------------------------------------------------------------------
    //  READ HOLDING REGISTERS (0x03)
    // -------------------------------------------------------------------------
    public int[] readHoldingRegisters(int startAddr, int count) throws IOException {
        if (count < 1 || count > 125)
            throw new IOException("Invalid register count: " + count);

        byte[] frame = new byte[8];
        frame[0] = (byte) slaveAddress;
        frame[1] = 0x03;
        frame[2] = (byte) (startAddr >> 8);
        frame[3] = (byte) (startAddr & 0xFF);
        frame[4] = (byte) (count >> 8);
        frame[5] = (byte) (count & 0xFF);
        appendCRC(frame, 6);

        byte[] resp = transact(frame);

        if ((resp[1] & 0x80) != 0) {   // Exception response
            int ex = resp[2] & 0xFF;
            throw new IOException("VFD Exception: " + decodeExceptionCode(ex));
        }

        if (resp[1] != 0x03)
            throw new IOException("Unexpected function: " + resp[1]);

        int byteCount = resp[2] & 0xFF;
        if (byteCount != count * 2)
            throw new IOException("Incorrect byte count");

        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int hi = resp[3 + i * 2] & 0xFF;
            int lo = resp[3 + i * 2 + 1] & 0xFF;
            out[i] = (hi << 8) | lo;
        }

        return out;
    }

    // -------------------------------------------------------------------------
    //  WRITE SINGLE REGISTER (0x06)
    // -------------------------------------------------------------------------
    public void writeSingleRegister(int addr, int value) throws IOException {
        byte[] frame = new byte[8];
        frame[0] = (byte) slaveAddress;
        frame[1] = 0x06;
        frame[2] = (byte) (addr >> 8);
        frame[3] = (byte) (addr & 0xFF);
        frame[4] = (byte) (value >> 8);
        frame[5] = (byte) (value & 0xFF);
        appendCRC(frame, 6);

        byte[] resp = transact(frame);

        if ((resp[1] & 0x80) != 0) {
            int ex = resp[2] & 0xFF;
            throw new IOException("VFD Exception: " + decodeExceptionCode(ex));
        }

        if (resp[1] != 0x06)
            throw new IOException("Unexpected write response");
    }

    // -------------------------------------------------------------------------
    //  CORE TRANSACTION
    // -------------------------------------------------------------------------
    private byte[] transact(byte[] frame) throws IOException {
        commLock.lock();
        try {
            flushInput();

            int written = serial.writeBytes(frame, frame.length);
            if (written != frame.length)
                throw new IOException("Write failed (serial error)");

            long start = System.currentTimeMillis();
            byte[] buf = new byte[256];
            int offset = 0;
            long last = System.currentTimeMillis();

            while (System.currentTimeMillis() - start < timeoutMs) {
                int avail = serial.bytesAvailable();
                if (avail > 0) {
                    int r = serial.readBytes(buf, avail, offset);
                    if (r > 0) {
                        offset += r;
                        last = System.currentTimeMillis();
                    }
                }
                if (offset >= 5 && System.currentTimeMillis() - last > 40)
                    break;

                try { Thread.sleep(5); } catch (Exception ignored) {}
            }

            if (offset < 3)
                throw new IOException("No response from device");

            byte[] resp = Arrays.copyOf(buf, offset);

            int crcPos = offset - 2;
            int crcCalc = calcCRC(resp, 0, crcPos);
            int crcRecv = ((resp[crcPos + 1] & 0xFF) << 8) | (resp[crcPos] & 0xFF);
            if (crcCalc != crcRecv)
                throw new IOException("CRC mismatch");

            return resp;

        } finally {
            commLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    //  HELPERS
    // -------------------------------------------------------------------------
    private void flushInput() {
        try { Thread.sleep(3); } catch (Exception ignored) {}
        int n = serial.bytesAvailable();
        if (n > 0) {
            byte[] tmp = new byte[n];
            serial.readBytes(tmp, n);
        }
    }

    private void appendCRC(byte[] arr, int pos) {
        int crc = calcCRC(arr, 0, pos);
        arr[pos] = (byte) (crc & 0xFF);
        arr[pos + 1] = (byte) ((crc >> 8) & 0xFF);
    }

    private int calcCRC(byte[] arr, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (arr[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) crc = (crc >> 1) ^ 0xA001;
                else crc >>= 1;
            }
        }
        return crc & 0xFFFF;
    }
}
