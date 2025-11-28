#!/usr/bin/env python3
"""
vfd_monitor_tk.py  Tkinter VFD monitor
"""

import tkinter as tk
from tkinter import ttk, messagebox
import threading
import queue
import time
from datetime import datetime
import traceback

import matplotlib
matplotlib.use('TkAgg')
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
from matplotlib.figure import Figure

from vfd_modbus import VFDModbus

# -----------------------------------------------------------------
# Register addresses (FC01 VFD)
# -----------------------------------------------------------------
REG_FREQ = 0x3001
REG_BUS_VOLT = 0x3002
REG_OUT_VOLT = 0x3003
REG_OUT_CURR = 0x3004
REG_SPEED = 0x3005

REG_CONTROL = 0x2000
REG_SET_FREQ = 0x2001

DEFAULT_PORT = "/dev/ttyUSB0"
DEFAULT_BAUD = 9600
DEFAULT_PARITY = "E"
DEFAULT_ADDR = 1
DEFAULT_POLL_MS = 500


class VFDMonitorApp:
    def __init__(self, root):
        self.root = root
        root.title("FC01 VFD Monitor (Tkinter)")

        self.modbus = None
        self.poll_thread = None
        self.poll_stop_event = threading.Event()
        self.ui_queue = queue.Queue()
        self.last_fault_msg = None

        self.times = []
        self.freqs = []
        self.volts = []
        self.currs = []
        self.max_points = 300

        self.build_ui()
        self.root.after(150, self.process_ui_queue)

    # -----------------------------------------------------------------
    # Build UI
    # -----------------------------------------------------------------
    def build_ui(self):
        top = ttk.Frame(self.root, padding=6)
        top.pack(side=tk.TOP, fill=tk.X)

        ttk.Label(top, text="Port:").grid(row=0, column=0)
        self.port_entry = ttk.Entry(top, width=14)
        self.port_entry.grid(row=0, column=1, padx=4)
        self.port_entry.insert(0, DEFAULT_PORT)

        ttk.Label(top, text="Baud:").grid(row=0, column=2)
        self.baud_cb = ttk.Combobox(top, values=[9600,19200,38400,57600,115200], width=6)
        self.baud_cb.grid(row=0, column=3, padx=4)
        self.baud_cb.set(DEFAULT_BAUD)

        ttk.Label(top, text="Parity:").grid(row=0, column=4)
        self.parity_cb = ttk.Combobox(top, values=["N","E","O"], width=3)
        self.parity_cb.grid(row=0, column=5, padx=4)
        self.parity_cb.set(DEFAULT_PARITY)

        ttk.Label(top, text="Addr:").grid(row=0, column=6)
        self.addr_entry = ttk.Entry(top, width=4)
        self.addr_entry.grid(row=0, column=7, padx=4)
        self.addr_entry.insert(0, str(DEFAULT_ADDR))

        ttk.Label(top, text="Poll (ms):").grid(row=0, column=8)
        self.poll_entry = ttk.Entry(top, width=6)
        self.poll_entry.grid(row=0, column=9, padx=4)
        self.poll_entry.insert(0, str(DEFAULT_POLL_MS))

        self.connect_btn = ttk.Button(top, text="Connect", command=self.toggle_connect)
        self.connect_btn.grid(row=0, column=10, padx=6)

        # Status frame
        left = ttk.Frame(self.root, padding=6)
        left.pack(side=tk.LEFT, fill=tk.Y)

        status = ttk.LabelFrame(left, text="Status", padding=6)
        status.pack(fill=tk.X)
        labels = [
            ("Frequency (Hz):", "freq_var"),
            ("Bus Voltage:", "volt_var"),
            ("Current:", "curr_var"),
            ("Speed:", "speed_var")
        ]
        for r, (txt, var) in enumerate(labels):
            ttk.Label(status, text=txt).grid(row=r, column=0, sticky=tk.W)
            setattr(self, var, tk.StringVar(value="-"))
            ttk.Label(status, textvariable=getattr(self, var)).grid(row=r, column=1)

        # Controls
        ctl = ttk.LabelFrame(left, text="Controls", padding=6)
        ctl.pack(fill=tk.X, pady=(8,0))

        ttk.Button(ctl, text="Forward", command=lambda:self.send_control(0x0001)).pack(fill=tk.X, pady=2)
        ttk.Button(ctl, text="Reverse", command=lambda:self.send_control(0x0002)).pack(fill=tk.X, pady=2)
        ttk.Button(ctl, text="Stop",    command=lambda:self.send_control(0x0005)).pack(fill=tk.X, pady=2)
        ttk.Button(ctl, text="E-Stop",  command=lambda:self.send_control(0x0006)).pack(fill=tk.X, pady=2)
        ttk.Button(ctl, text="Reset",   command=lambda:self.send_control(0x0007)).pack(fill=tk.X, pady=2)

        freq_set_frame = ttk.Frame(left, padding=6)
        freq_set_frame.pack(fill=tk.X)
        ttk.Label(freq_set_frame, text="Set freq (x100):").grid(row=0, column=0)
        self.freq_set_entry = ttk.Entry(freq_set_frame, width=8)
        self.freq_set_entry.grid(row=0, column=1, padx=4)
        self.freq_set_entry.insert(0, "5000")
        ttk.Button(freq_set_frame, text="Set", command=self.set_freq).grid(row=0, column=2, padx=4)

        # Charts
        center = ttk.Frame(self.root, padding=6)
        center.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        self.fig = Figure(figsize=(7,6))
        self.ax_f = self.fig.add_subplot(311)
        self.ax_v = self.fig.add_subplot(312)
        self.ax_c = self.fig.add_subplot(313)

        self.ax_f.set_ylabel("Hz")
        self.ax_v.set_ylabel("V")
        self.ax_c.set_ylabel("A")

        self.canvas = FigureCanvasTkAgg(self.fig, master=center)
        self.canvas.get_tk_widget().pack(fill=tk.BOTH, expand=True)

        # Bottom: log + fault table
        bottom = ttk.Frame(self.root, padding=6)
        bottom.pack(side=tk.BOTTOM, fill=tk.X)

        log_frame = ttk.LabelFrame(bottom, text="Log", padding=6)
        log_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.log_text = tk.Text(log_frame, height=8)
        self.log_text.pack(fill=tk.BOTH, expand=True)

        fault_frame = ttk.LabelFrame(bottom, text="Fault Log", padding=6)
        fault_frame.pack(side=tk.RIGHT, fill=tk.BOTH)
        self.fault_tree = ttk.Treeview(
            fault_frame, columns=("time","msg"), show="headings", height=8
        )
        self.fault_tree.heading("time", text="Time")
        self.fault_tree.heading("msg", text="Message")
        self.fault_tree.column("time", width=120)
        self.fault_tree.column("msg", width=500)
        self.fault_tree.pack(fill=tk.BOTH, expand=True)

    # -----------------------------------------------------------------
    # Connect / Disconnect
    # -----------------------------------------------------------------
    def toggle_connect(self):
        try:
            if self.modbus is None:
                print("TOGGLE: Connecting")
                self.connect()
            else:
                print("TOGGLE: Disconnecting")
                self.disconnect()
        except Exception as e:
            print("ERROR in toggle_connect:", e)
            traceback.print_exc()
            messagebox.showerror("Error", str(e))

    def connect(self):
        port = self.port_entry.get()
        baud = int(self.baud_cb.get())
        parity = self.parity_cb.get()
        addr = int(self.addr_entry.get())

        print("=== CONNECTING ===")
        print(f"Port: {port}, Baud: {baud}, Parity: {parity}, Slave: {addr}")

        try:
            self.modbus = VFDModbus(
                port=port,
                baud=baud,
                parity=parity,
                stopbits=1,
                bytesize=8,
                slave=addr
            )
        except Exception as e:
            print("ERROR: Could not create VFDModbus instance:", e)
            traceback.print_exc()
            messagebox.showerror("Connection Error", f"Constructor error:\n{e}")
            self.modbus = None
            return

        try:
            ok = self.modbus.open()
            print("ModbusSerialClient.open() returned:", ok)
            if not ok:
                print("ERROR: Port did NOT open.")
                self.log("Failed to open serial port")
                messagebox.showerror("Serial Error", f"Could not open port {port}")
                self.modbus = None
                return
        except Exception as e:
            print("ERROR during ModbusSerialClient.connect():", e)
            traceback.print_exc()
            messagebox.showerror("Connection Error", f"Connect error:\n{e}")
            self.modbus = None
            return

        self.log(f"Connected to {port} @ {baud} parity={parity}")
        print(f"CONNECTED OK ? {port} @ {baud} {parity}")
        self.connect_btn.config(text="Disconnect")
        self.start_polling()

    def disconnect(self):
        if self.poll_thread:
            self.poll_stop_event.set()
            self.poll_thread.join(timeout=1.0)
        self.poll_thread = None
        if self.modbus:
            self.modbus.close()
        self.modbus = None
        self.connect_btn.config(text="Connect")
        self.log("Disconnected")

    # -----------------------------------------------------------------
    # Write operations
    # -----------------------------------------------------------------
    def send_control(self, code):
        if not self.modbus:
            self.log("Not connected")
            return

        def job():
            try:
                self.modbus.write_reg(REG_CONTROL, code)
                self.ui_queue.put(("log", f"Control 0x{code:04X} sent"))
            except Exception as e:
                self.ui_queue.put(("fault", f"Control failed: {e}"))
                print("ERROR sending control:", e)
                traceback.print_exc()

        threading.Thread(target=job, daemon=True).start()

    def set_freq(self):
        if not self.modbus:
            self.log("Not connected")
            return

        try:
            v = int(self.freq_set_entry.get())
        except:
            self.log("Invalid frequency input")
            return

        def job():
            try:
                self.modbus.write_reg(REG_SET_FREQ, v)
                self.ui_queue.put(("log", f"Freq set to {v}"))
            except Exception as e:
                self.ui_queue.put(("fault", f"Set freq failed: {e}"))
                print("ERROR setting frequency:", e)
                traceback.print_exc()

        threading.Thread(target=job, daemon=True).start()

    # -----------------------------------------------------------------
    # Polling loop
    # -----------------------------------------------------------------
    def start_polling(self):
        if self.poll_thread and self.poll_thread.is_alive():
            return

        interval_ms = int(self.poll_entry.get())
        self.poll_interval = interval_ms / 1000.0

        self.poll_stop_event.clear()
        self.poll_thread = threading.Thread(target=self.poll_loop, daemon=True)
        self.poll_thread.start()
        self.log(f"Started polling every {interval_ms} ms")

    def poll_loop(self):
        while not self.poll_stop_event.is_set() and self.modbus:
            t0 = time.time()
            try:
                regs = self.modbus.read_regs(REG_BUS_VOLT, 4)
                bus, outv, curr, speed = regs

                try:
                    freq_raw = self.modbus.read_regs(REG_FREQ, 1)[0]
                    freq = freq_raw / 100.0
                except Exception:
                    freq = None

                self.ui_queue.put(("data", {
                    "ts": time.time(),
                    "freq": freq,
                    "bus": bus,
                    "out": outv,
                    "curr": curr,
                    "speed": speed,
                }))
            except Exception as e:
                self.ui_queue.put(("fault", str(e)))

            time.sleep(max(0, self.poll_interval - (time.time() - t0)))

    # -----------------------------------------------------------------
    # UI queue processing
    # -----------------------------------------------------------------
    def process_ui_queue(self):
        try:
            while True:
                msgtype, payload = self.ui_queue.get_nowait()
                if msgtype == "data":
                    self.update_data(payload)
                elif msgtype == "fault":
                    self.show_fault(payload)
                elif msgtype == "log":
                    self.log(payload)
        except queue.Empty:
            pass
        self.root.after(150, self.process_ui_queue)

    # -----------------------------------------------------------------
    # Update data & redraw charts
    # -----------------------------------------------------------------
    def update_data(self, d):
        ts = d["ts"]
        freq = d["freq"]
        bus = d["bus"]
        out = d["out"]
        curr = d["curr"]
        speed = d["speed"]

        if freq is not None:
            self.freq_var.set(f"{freq:.2f}")
        self.volt_var.set(str(out))
        self.curr_var.set(str(curr))
        self.speed_var.set(str(speed))

        self.times.append(ts)
        self.freqs.append(freq if freq is not None else float('nan'))
        self.volts.append(out)
        self.currs.append(curr)

        if len(self.times) > self.max_points:
            self.times = self.times[-self.max_points:]
            self.freqs = self.freqs[-self.max_points:]
            self.volts = self.volts[-self.max_points:]
            self.currs = self.currs[-self.max_points:]

        self.redraw_charts()

    def redraw_charts(self):
        if not self.times:
            return

        t0 = self.times[0]
        xs = [t - t0 for t in self.times]

        self.ax_f.cla()
        self.ax_v.cla()
        self.ax_c.cla()

        self.ax_f.set_ylabel("Hz")
        self.ax_v.set_ylabel("V")
        self.ax_c.set_ylabel("A")
        self.ax_c.set_xlabel("Time (s)")

        self.ax_f.plot(xs, self.freqs)
        self.ax_v.plot(xs, self.volts)
        self.ax_c.plot(xs, self.currs)

        self.canvas.draw_idle()

    # -----------------------------------------------------------------
    # Fault popup + console log
    # -----------------------------------------------------------------
    def show_fault(self, msg):
        now = datetime.now().strftime("%H:%M:%S")
        print("=== FAULT RECEIVED ===")
        print(f"[{now}] {msg}")
        try:
            traceback.print_exc()
        except:
            pass

        self.log("? " + msg)
        self.fault_tree.insert("", "end", values=(now, msg))

        if msg != self.last_fault_msg:
            self.last_fault_msg = msg
            messagebox.showerror("Modbus / VFD Fault", msg)

    # -----------------------------------------------------------------
    # Logging
    # -----------------------------------------------------------------
    def log(self, msg):
        t = datetime.now().strftime("%H:%M:%S")
        self.log_text.insert(tk.END, f"[{t}] {msg}\n")
        self.log_text.see(tk.END)


# -----------------------------------------------------------------
# Main
# -----------------------------------------------------------------
def main():
    root = tk.Tk()
    app = VFDMonitorApp(root)
    root.protocol("WM_DELETE_WINDOW", lambda: (app.disconnect(), root.destroy()))
    root.mainloop()


if __name__ == "__main__":
    main()
