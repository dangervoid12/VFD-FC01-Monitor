// VFDMonitorApp.java
// JavaFX application that uses ModbusRTU to poll the FC01 VFD and display graphs and controls.

import com.fazecast.jSerialComm.SerialPort;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.*;

public class VFDMonitorApp extends Application {

    private ComboBox<String> portCombo;
    private Button connectBtn;
    private Button startPollBtn;
    private Button stopPollBtn;

    private Label lblFreq = new Label("-");
    private Label lblVolt = new Label("-");
    private Label lblCurr = new Label("-");
    private Label lblSpeed = new Label("-");
    private TextArea logArea = new TextArea();

    private LineChart<Number, Number> freqChart;
    private LineChart<Number, Number> voltChart;
    private LineChart<Number, Number> currChart;

    private XYChart.Series<Number, Number> freqSeries = new XYChart.Series<>();
    private XYChart.Series<Number, Number> voltSeries = new XYChart.Series<>();
    private XYChart.Series<Number, Number> currSeries = new XYChart.Series<>();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollTask;

    private SerialPort activePort;
    private ModbusRTU modbus;

    private final int VFD_ADDR = 1; // default address, change if required
    private final int POLL_MS = 500;

    // Register addresses from FC01 manual:
    // communication control command 2000H, communication setting frequency 2001H
    // read set /measure: 3001H (setting freq?), manual uses 3001H etc. We'll read 3002..3004 for volt/curr/speed etc.
    // We'll read 3002 (Bus voltage), 3003 (Output voltage), 3004 (Output current), 3005 (Operation speed)
    // For frequency reading some models set frequency reading at 3001H or 3005H; we attempt 3001H first.

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("FC01 VFD Monitor (JavaFX)");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(8));

        // Top controls
        HBox top = new HBox(8);
        top.setPadding(new Insets(8));
        portCombo = new ComboBox<>(FXCollections.observableArrayList(listSerialPorts()));
        portCombo.setPrefWidth(180);
        connectBtn = new Button("Connect");
        connectBtn.setOnAction(e -> toggleConnect());

        startPollBtn = new Button("Start Polling");
        startPollBtn.setDisable(true);
        startPollBtn.setOnAction(e -> startPolling());

        stopPollBtn = new Button("Stop Polling");
        stopPollBtn.setDisable(true);
        stopPollBtn.setOnAction(e -> stopPolling());

        top.getChildren().addAll(new Label("COM Port:"), portCombo, connectBtn, startPollBtn, stopPollBtn);
        root.setTop(top);

        // Left: numeric readouts + controls
        VBox left = new VBox(10);
        left.setPadding(new Insets(8));
        left.setPrefWidth(240);

        GridPane stats = new GridPane();
        stats.setVgap(8);
        stats.setHgap(8);
        stats.add(new Label("Frequency (Hz):"), 0, 0);
        stats.add(lblFreq, 1, 0);
        stats.add(new Label("Output Volt (V):"), 0, 1);
        stats.add(lblVolt, 1, 1);
        stats.add(new Label("Output Curr (A):"), 0, 2);
        stats.add(lblCurr, 1, 2);
        stats.add(new Label("Speed (rpm):"), 0, 3);
        stats.add(lblSpeed, 1, 3);

        // Write controls:
        Button fwdBtn = new Button("Forward Run");
        fwdBtn.setOnAction(e -> writeControl(0x0001));
        Button revBtn = new Button("Reverse Run");
        revBtn.setOnAction(e -> writeControl(0x0002));
        Button stopBtn = new Button("Stop");
        stopBtn.setOnAction(e -> writeControl(0x0005));
        Button estopBtn = new Button("E-Stop");
        estopBtn.setOnAction(e -> writeControl(0x0006));
        Button resetBtn = new Button("Fault Reset");
        resetBtn.setOnAction(e -> writeControl(0x0007));

        TextField freqSet = new TextField();
        freqSet.setPromptText("Freq x100 (e.g. 5000=50.00Hz)");
        Button setFreqBtn = new Button("Set Frequency");
        setFreqBtn.setOnAction(e -> {
            String txt = freqSet.getText();
            try {
                int v = Integer.parseInt(txt);
                writeRegister(0x2001, v);
            } catch (NumberFormatException ex) {
                appendLog("Invalid freq value");
            }
        });

        VBox controls = new VBox(6, fwdBtn, revBtn, stopBtn, estopBtn, resetBtn, new HBox(6, freqSet, setFreqBtn));
        left.getChildren().addAll(new Label("Status"), stats, new Separator(), new Label("Controls"), controls);

        root.setLeft(left);

        // Center: charts
        VBox center = new VBox(8);
        center.setPadding(new Insets(8));

        freqChart = createChart("Frequency (Hz)", "time", "Hz");
        voltChart = createChart("Output Voltage (V)", "time", "V");
        currChart = createChart("Output Current (units)", "time", "I");

        freqSeries.setName("Freq");
        voltSeries.setName("Volt");
        currSeries.setName("Curr");

        freqChart.getData().add(freqSeries);
        voltChart.getData().add(voltSeries);
        currChart.getData().add(currSeries);

        center.getChildren().addAll(freqChart, voltChart, currChart);

        root.setCenter(center);

        // Bottom: log
        logArea.setEditable(false);
        logArea.setPrefHeight(120);
        root.setBottom(new VBox(new Label("Log"), logArea));

        Scene scene = new Scene(root, 1100, 900);
        primaryStage.setScene(scene);
        primaryStage.show();

        primaryStage.setOnCloseRequest(ev -> {
            stopPolling();
            disconnect();
            if (scheduler != null) scheduler.shutdownNow();
        });

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vfd-poller");
            t.setDaemon(true);
            return t;
        });
    }

    private LineChart<Number, Number> createChart(String title, String xLabel, String yLabel) {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel(xLabel);
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel(yLabel);
        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(title);
        chart.setCreateSymbols(false);
        chart.setPrefHeight(240);
        return chart;
    }

    private String[] listSerialPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] names = new String[ports.length];
        for (int i = 0; i < ports.length; i++) names[i] = ports[i].getSystemPortName();
        return names;
    }

    private void toggleConnect() {
        if (activePort != null && activePort.isOpen()) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        String p = portCombo.getValue();
        if (p == null || p.isEmpty()) {
            appendLog("Select COM port first");
            return;
        }
        activePort = SerialPort.getCommPort(p);
        activePort.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.EVEN_PARITY);
        activePort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (activePort.openPort()) {
            appendLog("Opened " + p);
            modbus = new ModbusRTU(activePort, VFD_ADDR);
            connectBtn.setText("Disconnect");
            startPollBtn.setDisable(false);
        } else {
            appendLog("Failed to open " + p);
            activePort = null;
        }
    }

    private void disconnect() {
        if (activePort != null) {
            try {
                if (activePort.isOpen()) activePort.closePort();
            } catch (Exception ignored) {}
            appendLog("Disconnected");
            activePort = null;
        }
        connectBtn.setText("Connect");
        startPollBtn.setDisable(true);
        stopPollBtn.setDisable(true);
    }

    private void startPolling() {
        if (modbus == null) {
            appendLog("Not connected");
            return;
        }
        // schedule polling task
        pollTask = scheduler.scheduleAtFixedRate(this::pollOnce, 0, POLL_MS, TimeUnit.MILLISECONDS);
        appendLog("Started polling every " + POLL_MS + "ms");
        startPollBtn.setDisable(true);
        stopPollBtn.setDisable(false);
    }

    private void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(true);
            pollTask = null;
            appendLog("Stopped polling");
        }
        startPollBtn.setDisable(false);
        stopPollBtn.setDisable(true);
    }

    private void pollOnce() {
        if (modbus == null) return;
        try {
            // We'll try to read several registers in one call if possible.
            // Example: read 4 registers starting 3002H (bus volt, out volt, out curr, op speed)
            int start = 0x3002;
            int count = 4;
            int[] vals = modbus.readHoldingRegisters(start, count);

            // Interpret fieldbus ratio: manual states values sometimes need scaling.
            // Many of these registers are raw integers representing real values.
            // For FC01: Bus voltage (3002H) is likely in 0.1 or 1 units; user should confirm.
            // We'll assume:
            // - Bus/Output voltage: raw value / 100 for decimals? But manual examples show 50.12Hz -> 5012 (x100)
            // We'll assume frequency uses x100 in some registers; for voltage/current, we'll display raw values
            final double busVolt = vals[0] / 1.0; // adjust scaling if needed
            final double outVolt = vals[1] / 1.0;
            final double outCurr = vals[2] / 1.0;
            final double speed = vals[3] / 1.0;

            long t = Instant.now().toEpochMilli();

            Platform.runLater(() -> {
                lblVolt.setText(String.format("%.2f", outVolt));
                lblCurr.setText(String.format("%.2f", outCurr));
                lblSpeed.setText(String.format("%.0f", speed));

                addPointToSeries(freqSeries, t, (double) 0); // placeholder if freq not read here
                addPointToSeries(voltSeries, t, busVolt);
                addPointToSeries(currSeries, t, outCurr);

                appendLog(String.format("Poll OK: Vbus=%s Vout=%s I=%s Sp=%s", busVolt, outVolt, outCurr, speed));
            });

            // Additionally try to read frequency separately (3001H) if exists
            try {
                int[] f = modbus.readHoldingRegisters(0x3001, 1);
                final double freqRaw = f[0] / 100.0; // manual often uses x100 for Hz e.g. 50.12 -> 5012
                Platform.runLater(() -> {
                    lblFreq.setText(String.format("%.2f", freqRaw));
                    // update freq series too
                    addPointToSeries(freqSeries, Instant.now().toEpochMilli(), freqRaw);
                });
            } catch (Exception ex) {
                // Not fatal — some VFDs use different registers; just log quietly
            }

        } catch (Exception ex) {
            Platform.runLater(() -> appendLog("Poll error: " + ex.getMessage()));
        }
    }

    private void addPointToSeries(XYChart.Series<Number, Number> s, long tMs, double y) {
        // use elapsed seconds / or ms as X axis; to keep numbers small use epoch seconds
        final double x = tMs / 1000.0;
        s.getData().add(new XYChart.Data<>(x, y));
        // cap points
        if (s.getData().size() > 200) s.getData().remove(0);
    }

    private void writeControl(int code) {
        if (modbus == null) {
            appendLog("Not connected");
            return;
        }
        // Write to 2000H control register
        scheduler.execute(() -> {
            try {
                modbus.writeSingleRegister(0x2000, code);
                Platform.runLater(() -> appendLog("Wrote control code: " + code));
            } catch (IOException e) {
                Platform.runLater(() -> appendLog("Write control failed: " + e.getMessage()));
            }
        });
    }

    private void writeRegister(int addr, int value) {
        if (modbus == null) {
            appendLog("Not connected");
            return;
        }
        scheduler.execute(() -> {
            try {
                modbus.writeSingleRegister(addr, value);
                Platform.runLater(() -> appendLog(String.format("Wrote reg 0x%04X = %d", addr, value)));
            } catch (IOException e) {
                Platform.runLater(() -> appendLog("Write failed: " + e.getMessage()));
            }
        });
    }

    private void appendLog(String msg) {
        logArea.appendText("[" + java.time.LocalTime.now().withNano(0) + "] " + msg + "\n");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
