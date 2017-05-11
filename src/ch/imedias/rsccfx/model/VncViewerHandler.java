package ch.imedias.rsccfx.model;

/**
 * This Class handles a VNC viewer.
 * The Thread keeps running as long as the VNCViewer is running.
 * Created by jp on 11/05/17.
 */
public class VncViewerHandler extends Thread {
  private final SystemCommander systemCommander;
  private final Rscc model;
  private final String vncViewerName = "vncviewer";
  private String hostAddress;
  private Integer vncViewerPort;
  private boolean listeningMode;

  /**
   * Constructor to instantiate a VNCViewer.
   * @param model The one and only Model.
   * @param hostAddress Address to connect to.
   * @param vncViewerPort Port to connect to.
   */
  public VncViewerHandler(Rscc model, String hostAddress,
                          Integer vncViewerPort, boolean listeningMode) {
    this.listeningMode = listeningMode;
    if (hostAddress == null || vncViewerPort == null) {
      throw new IllegalArgumentException();
    }
    this.model = model;
    this.hostAddress = hostAddress;
    this.vncViewerPort = vncViewerPort;
    this.systemCommander = model.getSystemCommander();
  }


  /**
   * Starts the VNCViewer in the given mode (Listener or normal).
   */
  public void run() {
    if (listeningMode) {
      startVncViewerListening();
    } else {
      startVncViewer();
    }
  }


  /**
   * Starts this VNCViewer connecting to <code>hostAddress</code> and <code>vncViewerPort</code>.
   * Loops until the VNCViewer is connected to the VNCServer.
   */
  private void startVncViewer() {
    String vncViewerAttributes = "-bgr233 " + " " + hostAddress + "::" + vncViewerPort;

    String command = systemCommander.commandStringGenerator(null,
        vncViewerName, vncViewerAttributes);

    String connectionStatus = null;

    for (int i = 0; i < 10; i++) {


    }
    do {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      connectionStatus = systemCommander.executeTerminalCommandAndUpdateModel(command, "Connected");
      System.out.println("VNCviewer: " + connectionStatus);

    } while (!connectionStatus.contains("Connected"));

    System.out.println("out of loop");
  }


  /**
   * Starts this VNCViewer in Listening mode.
   */
  private void startVncViewerListening() {
    String vncViewerAttributes = "-listen";

    String command = systemCommander.commandStringGenerator(null,
        vncViewerName, vncViewerAttributes);

    systemCommander.executeTerminalCommand(command);
  }


  /**
   * Kills all processes with the Name of the VNCViewer.
   */
  public void killVncViewer() {
    String command = systemCommander.commandStringGenerator(null,
        "killall", vncViewerName);
    systemCommander.executeTerminalCommand(command);
  }
}
