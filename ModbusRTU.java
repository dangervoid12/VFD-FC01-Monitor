// ModbusRTU.java
// Simple Modbus RTU helper using jSerialComm

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public class ModbusRTU {

    private final SerialPort serial;
    private final int slaveAddress;
    private final ReentrantLock commLock = new ReentrantLock();

    // timeout for reading response (ms)
    private int timeoutMs = 500;

    public ModbusRTU(SerialPort serial, int slaveAddress) {
        this.serial = serial;
        this.slaveAddress = slaveAddress & 0xFF;
    }

    public void setTimeout(int ms) {
        this.timeoutMs = ms;
    }

    /**
     * Read N words (16-bit) starting at register startAddr.
     * Returns int[] of length count with unsigned 16-bit values.
     */
    public int[] readHoldingRegisters(int startAddr, int count) throws IOException {
        if (count <= 0 || count > 125) throw new IllegalArgumentException("Count out of range");

        byte[] frame = new byte[8];
        frame[0] = (byte) slaveAddress;
        frame[1] = 0x03; // function code
        frame[2] = (byte) ((startAddr >> 8) & 0xFF);
        frame[3] = (byte) (startAddr & 0xFF);
        frame[4] = (byte) ((count >> 8) & 0xFF);
        frame[5] = (byte) (count & 0xFF);

        appendCRC(frame, 6);

        byte[] resp = transact(frame);

        // Expected response: addr, func, byteCount, data..., CRClo, CRChi
        if (resp == null || resp.length < 5) throw new IOException("Invalid response length");
        if ((resp[1] & 0xFF) == (0x83)) { // exception (0x80 + function)
            int ex = resp[2] & 0xFF;
            throw new IOException("Modbus exception response: " + ex);
        }
        if ((resp[1] & 0xFF) != 0x03) throw new IOException("Unexpected function in response");

        int byteCount = resp[2] & 0xFF;
        if (byteCount != count * 2) {
            throw new IOException("Unexpected byte count: " + byteCount + " expected " + (count*2));
        }

        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int hi = resp[3 + i*2] & 0xFF;
            int lo = resp[3 + i*2 + 1] & 0xFF;
            out[i] = (hi << 8) | lo;
        }

        return out;
    }

    /**
     * Write single register (function 0x06).
     * value is 16-bit unsigned.
     */
    public void writeSingleRegister(int addr, int value) throws IOException {
        byte[] frame = new byte[8];
        frame[0] = (byte) slaveAddress;
        frame[1] = 0x06;
        frame[2] = (byte) ((addr >> 8) & 0xFF);
        frame[3] = (byte) (addr & 0xFF);
        frame[4] = (byte) ((value >> 8) & 0xFF);
        frame[5] = (byte) (value & 0xFF);

        appendCRC(frame, 6);

        byte[] resp = transact(frame);

        // Response should mirror the request (same 8 bytes)
        if (resp == null || resp.length < 8) throw new IOException("Invalid write response");
        // check function code
        if ((resp[1] & 0xFF) == 0x86) { // exception for 0x06 (0x80 + 0x06 = 0x86)
            int ex = resp[2] & 0xFF;
            throw new IOException("Modbus exception write: " + ex);
        }
        if (resp[1] != 0x06) throw new IOException("Unexpected function in write response");
        // Could further validate address/value echo if needed
    }

    // Core transaction: write frame and read response (synchronized)
    private byte[] transact(byte[] frame) throws IOException {
        commLock.lock();
        try {
            flushInput();

            int written = serial.writeBytes(frame, frame.length);
            if (written != frame.length) {
                throw new IOException("Failed to write whole frame to serial (written=" + written + ")");
            }

            long start = System.currentTimeMillis();
            // Read loop until timeout or when we detect a full response (using minimal checks)
            // We'll accumulate bytes until no new bytes arrive for 30ms after at least 5 bytes
            byte[] buffer = new byte[512];
            int offset = 0;
            long lastReadTime = System.currentTimeMillis();

            while (System.currentTimeMillis() - start < timeoutMs) {
                int avail = serial.bytesAvailable();
                if (avail > 0) {
                    int toRead = Math.min(avail, buffer.length - offset);
                    int r = serial.readBytes(buffer, toRead, offset);
                    if (r > 0) {
                        offset += r;
                        lastReadTime = System.currentTimeMillis();
                    }
                } else {
                    // small delay to allow bytes to arrive
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                }

                // Heuristic: if we have >=5 bytes and no bytes for 30ms, assume frame complete
                if (offset >= 5 && (System.currentTimeMillis() - lastReadTime) > 30) break;
            }

            if (offset == 0) return null;

            byte[] resp = Arrays.copyOf(buffer, offset);

            // verify CRC
            if (offset >= 2) {
                int crcIndex = offset - 2;
                int calc = calcCRC(resp, 0, crcIndex);
                int recv = ((resp[crcIndex + 1] & 0xFF) << 8) | (resp[crcIndex] & 0xFF);
                if (calc != recv) {
                    throw new IOException("CRC mismatch: calc=" + Integer.toHexString(calc) + " recv=" + Integer.toHexString(recv));
                }
            } else {
                throw new IOException("Response too short for CRC check");
            }

            return resp;

        } finally {
            commLock.unlock();
        }
    }

    // small helper - clear input buffer
    private void flushInput() {
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        int avail = serial.bytesAvailable();
        if (avail > 0) {
            byte[] tmp = new byte[avail];
            serial.readBytes(tmp, tmp.length);
        }
    }

    // Append CRC low, CRC high into dest[ pos .. pos+1 ]
    private void appendCRC(byte[] dest, int pos) {
        int crc = calcCRC(dest, 0, pos);
        dest[pos] = (byte) (crc & 0xFF); // CRC low
        dest[pos + 1] = (byte) ((crc >> 8) & 0xFF); // CRC high
    }

    // Calculate CRC for bytes [off .. off+len-1]
    private int calcCRC(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x0001) != 0) crc = (crc >> 1) ^ 0xA001;
                else crc = (crc >> 1);
            }
        }
        return crc & 0xFFFF;
    }
}
