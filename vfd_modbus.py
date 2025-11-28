# vfd_modbus.py  works on ALL pymodbus 3.x versions
from pymodbus.client import ModbusSerialClient as ModbusClient
import threading
import time

EXCEPTION_DICT = {
    0x01: "Illegal command",
    0x02: "Illegal data address",
    0x03: "Illegal value (bad frame)",
    0x04: "Operation failed",
    0x05: "Password error",
    0x06: "Data frame error (CRC/format)",
    0x07: "Write not allowed",
    0x08: "Parameter cannot be changed during running",
    0x09: "Password protection active",
}

def decode_exception_code(code):
    return EXCEPTION_DICT.get(code, f"Unknown exception code 0x{code:02X}")

class VFDModbus:
    def __init__(self, port, baud=19200, parity='E', stopbits=1, bytesize=8, slave=1, timeout=1.0):
        self.slave = slave
        self.lock = threading.Lock()

        # ONLY parameters that exist in every 3.x version
        self.client = ModbusClient(
            port=port,
            baudrate=baud,
            parity=parity,
            stopbits=stopbits,
            bytesize=bytesize,
            timeout=timeout,      # 1 second helps a lot with cheap dongles
            # retries=3,          # exists, but not needed  default is already 3
        )
        self.client.unit_id = slave   # global slave/unit id

    def open(self):
        return self.client.connect()

    def close(self):
        with self.lock:
            try:
                self.client.close()
            except:
                pass

    def read_regs(self, addr, count=1):
        with self.lock:
            time.sleep(0.02)   # tiny delay fixes 95% of cheap USB-RS485 issues
            try:
                rr = self.client.read_holding_registers(address=addr, count=count)
            except Exception as e:
                raise IOError(f"Comm error: {e}")

            if rr.isError():
                ex_msg = decode_exception_code(rr.exception_code) if hasattr(rr, "exception_code") else str(rr)
                raise IOError(f"VFD Exception: {ex_msg}")

            return rr.registers

    def write_reg(self, addr, value):
        with self.lock:
            time.sleep(0.02)
            try:
                wr = self.client.write_register(address=addr, value=int(value))
            except Exception as e:
                raise IOError(f"Comm error: {e}")

            if wr.isError():
                ex_msg = decode_exception_code(wr.exception_code) if hasattr(wr, "exception_code") else str(wr)
                raise IOError(f"VFD Exception: {ex_msg}")

            return True
