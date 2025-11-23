// VFDMonitorApp.java
// Full updated version including:
// - Real-time polling charts
// - Configurable baud/parity/address/poll interval
// - Modbus exception popup alerts
// - Fault log table with timestamps
// - Forward/Reverse/Stop/Fault Reset
// - Frequency setting
// - Uses ModbusRTU.java

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
import java.time.LocalTime;
import java.util.concurrent.*;

public class VFDMonitorApp extends Application {

    // -------------------------------------------------------------------------
    // Configurable communication settings
    // -------------------------------------------------------------------------
    private int baudRate = 9600;
    private String parity = "Even";
    private int slaveAddress = 1;
    private int POLL_MS = 500;

    // -------------------------------------------------------------------------
    // UI elements
    // -------------------------------------------------------------------------
    private ComboBox<String> portCombo;
    private Button connectBtn;
    private Button startPollBtn;
    private Button stopPollBtn;

    private Label lblFreq = new Label("-");
    private Label lblVolt = new Label("-");
    private Label lblCurr = new Label("-");
    private Label lblSpeed = new Label("-");

    private TextArea logArea = new TextArea();

    // Charts
    private LineChart<Number, Number> freqChart;
    private LineChart<Number, Number> voltChart;
    private LineChart<Number, Number> currChart;

    private XYChart.Series<Number, Number> freqSeries = new XYChart.Series<>();
    private XYChart.Series<Number, Number> voltSeries = new XYChart.Series<>();
    private XYChart.Series<Number, Number> currSeries = new XYChart.Series<>();

    // -------------------------------------------------------------------------
    // Fault logging and popup
    // -------------------------------------------------------------------------
    private String lastFaultMessage = null;
    private TableView<FaultEntry> faultTable = new TableView<>();

    public static class FaultEntry {
        private final String time;
        private final String message;

        public FaultEntry(String time, String message) {
            this.time = time;
            this.message = message;
        }
        public String getTime() { return time; }
        public String getMessage() { return message; }
    }

    // -------------------------------------------------------------------------
    // Runtime components
    // -------------------------------------------------------------------------
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollTask;
    private SerialPort activePort;
    private ModbusRTU modbus;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("FC01 VFD Monitor (JavaFX)");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(8));

        // ---------------------------------------------------------------------
        // TOP BAR - communication settings
        // ---------------------------------------------------------------------
        HBox top = new HBox(12);
        top.setPadding(new Insets(8));

        portCombo = new ComboBox<>(FXCollections.observableArrayList(listSerialPorts()));
        portCombo.setPrefWidth(150);

        ComboBox<Integer> baudCombo = new ComboBox<>();
        baudCombo.getItems().addAll(9600, 19200, 38400, 57600, 115200);
        baudCombo.setValue(9600);

        ComboBox<String> parityCombo = new ComboBox<>();
        parityCombo.getItems().addAll("None", "Even", "Odd");
        parityCombo.setValue("Even");

        TextField addrField = new TextField("1");
        addrField.setPrefWidth(40);

        TextField pollField = new TextField("500");
        pollField.setPrefWidth(60);

        connectBtn = new Button("Connect");
        connectBtn.setOnAction(e -> {
            baudRate = baudCombo.getValue();
            parity = parityCombo.getValue();
            slaveAddress = Integer.parseInt(addrField.getText());
            POLL_MS = Integer.parseInt(pollField.getText());
            toggleConnect();
        });

        startPollBtn = new Button("Start");
        startPollBtn.setDisable(true);
        startPollBtn.setOnAction(e -> startPolling());

        stopPollBtn = new Button("Stop");
        stopPollBtn.setDisable(true);
        stopPollBtn.setOnAction(e -> stopPolling());

        top.getChildren().addAll(
                new Label("Port:"), portCombo,
                new Label("Baud:"), baudCombo,
                new Label("Parity:"), parityCombo,
                new Label("Addr:"), addrField,
                new Label("Poll(ms):"), pollField,
                connectBtn, startPollBtn, stopPollBtn
        );

        root.setTop(top);

        // ---------------------------------------------------------------------
        // LEFT PANEL — status + controls
        // ---------------------------------------------------------------------
        VBox left = new VBox(10);
        left.setPadding(new Insets(8));
        left.setPrefWidth(250);

        GridPane stats = new GridPane();
        stats.setVgap(7);
        stats.setHgap(7);
        stats.add(new Label("Frequency (Hz):"), 0, 0);
        stats.add(lblFreq, 1, 0);
        stats.add(new Label("Bus Voltage:"), 0, 1);
        stats.add(lblVolt, 1, 1);
        stats.add(new Label("Current:"), 0, 2);
        stats.add(lblCurr, 1, 2);
        stats.add(new Label("Speed (rpm):"), 0, 3);
        stats.add(lblSpeed, 1, 3);

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
        freqSet.setPromptText("Freq x100 (5000=50.00)");
        Button setFreqBtn = new Button("Set");
        setFreqBtn.setOnAction(e -> {
            try {
                int v = Integer.parseInt(freqSet.getText());
                writeRegister(0x2001, v);
            } catch (Exception ex) {
                appendLog("Invalid frequency entry");
            }
        });

        VBox controls = new VBox(5,
                fwdBtn, revBtn, stopBtn, estopBtn, resetBtn,
                new HBox(5, freqSet, setFreqBtn)
        );

        left.getChildren().addAll(
                new Label("Status"), stats,
                new Separator(),
                new Label("Controls"), controls
        );

        root.setLeft(left);

        // ---------------------------------------------------------------------
        // CENTER — charts
        // ---------------------------------------------------------------------
        VBox center = new VBox(8);
        center.setPadding(new Insets(8));

        freqChart = createChart("Frequency (Hz)");
        voltChart = createChart("Bus Voltage (V)");
        currChart = createChart("Current");

        freqChart.getData().add(freqSeries);
        voltChart.getData().add(voltSeries);
        currChart.getData().add(currSeries);

        center.getChildren().addAll(freqChart, voltChart, currChart);
        root.setCenter(center);

        // ---------------------------------------------------------------------
        // FAULT LOG TABLE
        // ---------------------------------------------------------------------
        faultTable.setPrefHeight(200);

        TableColumn<FaultEntry, String> colTime = new TableColumn<>("Time");
        colTime.setPrefWidth(120);
        colTime.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getTime()));

        TableColumn<FaultEntry, String> colMsg = new TableColumn<>("Fault Message");
        colMsg.setPrefWidth(550);
        colMsg.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getMessage()));

        faultTable.getColumns().addAll(colTime, colMsg);

        // ---------------------------------------------------------------------
        // BOTTOM — logs + fault table
        // ---------------------------------------------------------------------
        logArea.setPrefHeight(120);
        logArea.setEditable(false);

        VBox bottom = new VBox(
                new Label("Output Log"), logArea,
                new Label("Fault Log"), faultTable
        );

        root.setBottom(bottom);

        // ---------------------------------------------------------------------
        // Final scene setup
        // ---------------------------------------------------------------------
        Scene scene = new Scene(root, 1200, 980);
        primaryStage.setScene(scene);
        primaryStage.show();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        primaryStage.setOnCloseRequest(e -> {
            stopPolling();
            disconnect();
            scheduler.shutdownNow();
        });
    }

    // -------------------------------------------------------------------------
    // CHART CREATOR
    // -------------------------------------------------------------------------
    private LineChart<Number, Number> createChart(String title) {
        NumberAxis x = new NumberAxis();
        NumberAxis y = new NumberAxis();
        x.setLabel("Time (s)");

        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setTitle(title);
        chart.setCreateSymbols(false);
        chart.setPrefHeight(250);
        return chart;
    }

    private String[] listSerialPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] out = new String[ports.length];
        for (int i = 0; i < ports.length; i++)
            out[i] = ports[i].getSystemPortName();
        return out;
    }

    // -------------------------------------------------------------------------
    // CONNECTION MANAGEMENT
    // -------------------------------------------------------------------------
    private void toggleConnect() {
        if (activePort != null && activePort.isOpen()) disconnect();
        else connect();
    }

    private void connect() {
        String name = portCombo.getValue();
        if (name == null || name.isEmpty()) {
            appendLog("Select a COM port first.");
            return;
        }

        activePort = SerialPort.getCommPort(name);
        activePort.setBaudRate(baudRate);
        activePort.setNumDataBits(8);

        switch (parity) {
            case "None": activePort.setParity(SerialPort.NO_PARITY); break;
            case "Odd": activePort.setParity(SerialPort.ODD_PARITY); break;
            default: activePort.setParity(SerialPort.EVEN_PARITY); break;
        }

        activePort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        activePort.setComPortTimeouts(
                SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

        if (activePort.openPort()) {
            appendLog("Connected to " + name);
            modbus = new ModbusRTU(activePort, slaveAddress);
            modbus.setTimeout(800);

            connectBtn.setText("Disconnect");
            startPollBtn.setDisable(false);
        } else {
            appendLog("Failed to open port");
            activePort = null;
        }
    }

    private void disconnect() {
        try {
            if (activePort != null && activePort.isOpen())
                activePort.closePort();
        } catch (Exception ignored) {}

        activePort = null;
        connectBtn.setText("Connect");
        startPollBtn.setDisable(true);
        stopPollBtn.setDisable(true);
        appendLog("Disconnected.");
    }

    // -------------------------------------------------------------------------
    // POLLING
    // -------------------------------------------------------------------------
    private void startPolling() {
        if (modbus == null) {
            appendLog("Not connected.");
            return;
        }

        if (pollTask != null && !pollTask.isCancelled())
            pollTask.cancel(true);

        pollTask = scheduler.scheduleAtFixedRate(
                this::pollOnce, 0, POLL_MS, TimeUnit.MILLISECONDS);
        appendLog("Polling started.");
        startPollBtn.setDisable(true);
        stopPollBtn.setDisable(false);
    }

    private void stopPolling() {
        if (pollTask != null)
            pollTask.cancel(true);

        pollTask = null;
        appendLog("Polling stopped.");
        startPollBtn.setDisable(false);
        stopPollBtn.setDisable(true);
    }

    private void pollOnce() {
        if (modbus == null) return;

        try {
            // Read 3002–3005
            int[] vals = modbus.readHoldingRegisters(0x3002, 4);

            double busVolt = vals[0];
            double outVolt = vals[1];
            double curr = vals[2];
            double speed = vals[3];

            long t = Instant.now().toEpochMilli();

            Platform.runLater(() -> {
                lblVolt.setText("" + busVolt);
                lblCurr.setText("" + curr);
                lblSpeed.setText("" + speed);

                addPoint(voltSeries, t, busVolt);
                addPoint(currSeries, t, curr);
            });

            // Read frequency 3001
            try {
                int[] f = modbus.readHoldingRegisters(0x3001, 1);
                double freq = f[0] / 100.0;
                Platform.runLater(() -> {
                    lblFreq.setText(String.format("%.2f", freq));
                    addPoint(freqSeries, Instant.now().toEpochMilli(), freq);
                });
            } catch (Exception ignored) {}

        } catch (Exception e) {
            Platform.runLater(() -> {
                String msg = "Poll error: " + e.getMessage();
                appendLog("⛔ " + msg);
                handleModbusFault(msg);
            });
        }
    }

    private void addPoint(XYChart.Series<Number, Number> s, long tMs, double y) {
        double x = tMs / 1000.0;
        s.getData().add(new XYChart.Data<>(x, y));
        if (s.getData().size() > 300)
            s.getData().remove(0);
    }

    // -------------------------------------------------------------------------
    // WRITE COMMANDS
    // -------------------------------------------------------------------------
    private void writeControl(int code) {
        if (modbus == null) {
            appendLog("Not connected.");
            return;
        }

        scheduler.execute(() -> {
            try {
                modbus.writeSingleRegister(0x2000, code);
                Platform.runLater(() -> appendLog("Wrote control code " + code));
            } catch (IOException e) {
                String msg = "Control write failed: " + e.getMessage();
                Platform.runLater(() -> {
                    appendLog("⛔ " + msg);
                    handleModbusFault(msg);
                });
            }
        });
    }

    private void writeRegister(int addr, int value) {
        if (modbus == null) {
            appendLog("Not connected.");
            return;
        }

        scheduler.execute(() -> {
            try {
                modbus.writeSingleRegister(addr, value);
                Platform.runLater(() ->
                        appendLog(String.format("Wrote reg 0x%04X = %d", addr, value)));
            } catch (IOException e) {
                String msg = "Write failed: " + e.getMessage();
                Platform.runLater(() -> {
                    appendLog("⛔ " + msg);
                    handleModbusFault(msg);
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // FAULT HANDLING (POPUP + TABLE)
    // -------------------------------------------------------------------------
    private void handleModbusFault(String message) {
        String time = LocalTime.now().withNano(0).toString();

        faultTable.getItems().add(new FaultEntry(time, message));

        if (message.equals(lastFaultMessage))
            return;
        lastFaultMessage = message;

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Modbus / VFD Fault");
        alert.setHeaderText("VFD Communication Exception");
        alert.setContentText(message);
        alert.show();
    }

    // -------------------------------------------------------------------------
    // LOGGING
    // -------------------------------------------------------------------------
    private void appendLog(String msg) {
        logArea.appendText("[" +
                LocalTime.now().withNano(0) + "] "
                + msg + "\n");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
