// VFDMonitorApp.java

// - Modbus RTU communication

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

    // -------- CONFIGURABLE PARAMETERS --------
    private int baudRate = 9600;
    private String parity = "Even";
    private int slaveAddress = 1;
    private int POLL_MS = 500;

    // -------- UI ELEMENTS --------
    private ComboBox<String> portCombo;
    private Button connectBtn;
    private Button startPollBtn;
    private Button stopPollBtn;

    private Label lblFreq = new Label("-");
    private Label lblVolt = new Label("-");
    private Label lblCurr = new Label("-");
    private Label lblSpeed = new Label("-");

    private TextArea logArea = new TextArea();

    // -------- CHARTING --------
    private LineChart<Number, Number> freqChart;
    private LineChart<Number, Number> voltChart;
    private LineChart<Number, Number> currChart;

    private XYChart.Series<Number, Number> freqSeries = new XYChart.Series<>();
    private XYChart.Series<Number, Number> voltSeries = new XYChart.Series<>();
    private XYChart.Series<Number, Number> currSeries = new XYChart.Series<>();

    // -------- RUNTIME --------
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollTask;

    private SerialPort activePort;
    private ModbusRTU modbus;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("FC01 VFD Monitor");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(8));

        // ----- TOP BAR -----
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
        addrField.setPrefWidth(50);

        TextField pollField = new TextField("500");
        pollField.setPrefWidth(70);

        connectBtn = new Button("Connect");
        connectBtn.setOnAction(e -> {
            baudRate = baudCombo.getValue();
            parity = parityCombo.getValue();
            slaveAddress = Integer.parseInt(addrField.getText());
            POLL_MS = Integer.parseInt(pollField.getText());
            toggleConnect();
        });

        startPollBtn = new Button("Start Polling");
        startPollBtn.setDisable(true);
        startPollBtn.setOnAction(e -> startPolling());

        stopPollBtn = new Button("Stop Polling");
        stopPollBtn.setDisable(true);
        stopPollBtn.setOnAction(e -> stopPolling());

        top.getChildren().addAll(
                new Label("Port:"), portCombo,
                new Label("Baud:"), baudCombo,
                new Label("Parity:"), parityCombo,
                new Label("Address:"), addrField,
                new Label("Poll (ms):"), pollField,
                connectBtn, startPollBtn, stopPollBtn
        );

        root.setTop(top);

        // -------- LEFT PANEL: STATUS + CONTROLS --------
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
        freqSet.setPromptText("Freq x100 (5000=50Hz)");

        Button setFreqBtn = new Button("Set Freq");
        setFreqBtn.setOnAction(e -> {
            try {
                int v = Integer.parseInt(freqSet.getText());
                writeRegister(0x2001, v);
            } catch (Exception ex) {
                appendLog("Invalid frequency entry");
            }
        });

        VBox controls = new VBox(6,
                fwdBtn, revBtn, stopBtn,
                estopBtn, resetBtn,
                new HBox(6, freqSet, setFreqBtn)
        );

        left.getChildren().addAll(
                new Label("Status"), stats,
                new Separator(),
                new Label("Controls"), controls
        );

        root.setLeft(left);

        // -------- CHARTS --------
        VBox center = new VBox(8);
        center.setPadding(new Insets(8));

        freqChart = createChart("Frequency (Hz)", "time", "Hz");
        voltChart = createChart("Output Voltage (V)", "time", "V");
        currChart = createChart("Output Current", "time", "A");

        freqSeries.setName("Freq");
        voltSeries.setName("Volt");
        currSeries.setName("Curr");

        freqChart.getData().add(freqSeries);
        voltChart.getData().add(voltSeries);
        currChart.getData().add(currSeries);

        center.getChildren().addAll(freqChart, voltChart, currChart);
        root.setCenter(center);

        // -------- LOG --------
        logArea.setEditable(false);
        logArea.setPrefHeight(120);
        root.setBottom(new VBox(new Label("Log"), logArea));

        Scene scene = new Scene(root, 1150, 920);
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

    // -------- UI BUILDING HELPERS --------

    private LineChart<Number, Number> createChart(String title, String xLabel, String yLabel) {
        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel(xLabel);
        yAxis.setLabel(yLabel);

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(title);
        chart.setCreateSymbols(false);
        chart.setPrefHeight(260);
        return chart
