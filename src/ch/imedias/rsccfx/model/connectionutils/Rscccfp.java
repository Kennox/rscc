package ch.imedias.rsccfx.model.connectionutils;

import ch.imedias.rsccfx.model.Rscc;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.logging.Logger;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.harvest.StunCandidateHarvester;

/**
 * Remote Support Client Connection Control Flow Protocol
 * This is the Protocol to see if a UPD Connection between the Clients is possible.
 * It uses a TCP-connection between the clients to exchange SDP-Dumps and run the ICE-Framework
 * Created by jp on 08/05/17.
 */

public class Rscccfp extends Thread {
  private static final Logger LOGGER = Logger.getLogger(Rscccfp.class.getName());

  private final Rscc model;

  private Socket connectionSocket;
  private ServerSocket serverSocket;
  private DataOutputStream outputStream;
  private BufferedReader inputStream;
  private boolean isServer;
  private String mySdp;
  private String remoteSdp;
  private Agent agent;
  private IceStateListener iceStateListener;

  /**
   * This is the Protocol to see if a UPD Connection  between the Clients is possible.
   *
   * @param model    The one and only Model.
   * @param isServer Specifies if protocol is startet in server or client mode.
   */
  public Rscccfp(Rscc model, boolean isServer) {
    this.model = model;
    this.isServer = isServer;
    this.agent = new Agent(); // A simple ICE Agent
    agent.setControlling(true);
  }


  /**
   * Starts the Protocol as Server or Client depending on isServer.
   */
  public void run() {
    if (isServer) {
      try {
        startRscccfpServer();
      } catch (Throwable throwable) {
        throwable.printStackTrace();
      }
    } else {
      try {
        startRscccfpClient("127.0.0.1");
      } catch (Throwable throwable) {
        throwable.printStackTrace();
      }
    }
  }


  /**
   * Starts the TCP - Server.
   *
   * @throws Throwable When connection is not possible.
   */
  public void startRscccfpServer() throws Throwable {

    //Start TCP-Server
    LOGGER.info("RSCCCFP: start server");
    serverSocket = new ServerSocket(model.getVncPort());

    //Wait for connection
    LOGGER.info("RSCCCFP: wait for client");

    connectionSocket = serverSocket.accept();

    inputStream = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
    outputStream = new DataOutputStream(connectionSocket.getOutputStream());
    LOGGER.info("RSCCCFP: Client connected");

    runIceMagic();
  }

  /**
   * Starts the TCP-Client.
   *
   * @param host Host address to connect to.
   * @throws Throwable When connection is not possible.
   */
  public void startRscccfpClient(String host) throws Throwable {

    //connect to server
    LOGGER.info("start client");
    connectionSocket = new Socket(host, model.getVncPort());
    connectionSocket.setTcpNoDelay(false);
    LOGGER.info("RSCCCFP: Connected to server");
    outputStream = new DataOutputStream(connectionSocket.getOutputStream());
    inputStream = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));

    runIceMagic();

  }


  /**
   * Runs SDP exchange and ICE Magic over the established TCP-connection.
   *
   * @throws Throwable to be removed.
   */
  private void runIceMagic() throws Throwable {
    //Exchange isServermode?
    model.setConnectionStatus("Trying to establish a direct connection (ICE).", 1);

    LOGGER.info("RSCCCFP: Handling ServerMode");
    outputStream.writeBoolean(model.getIsForcingServerMode());

    try {
      int remoteForcesServerMode = inputStream.read();
      if (remoteForcesServerMode == 1) {
        LOGGER.info("RSCCCFP: Remote forces ServerMode");
        model.setIsForcingServerMode(true);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    if (model.getIsForcingServerMode()) {
      model.setRemoteIceSuccessful(false);
      model.setLocalIceSuccessful(false);
      agent.free();
      closeConnection();
      LOGGER.info("RSCCCFP: Stopping, ServerMode forced");
      return;
    }

    //Start ICE Agent
    startStun();

    //create SDP dump
    mySdp = createSdp();

    //send my SDP
    sendMySdp(mySdp);

    //receive other SDP
    receiveRemoteSdp();

    //Stun Magic
    SdpUtils.parseSdp(agent, remoteSdp);
    iceStateListener = new IceStateListener();
    agent.addStateChangeListener(iceStateListener);
    Component iceComponent = startIceConnectivityEstablishment();
    if (iceComponent != null) {
      model.setRemoteClientPort(iceComponent
          .getSelectedPair().getRemoteCandidate().getTransportAddress().getPort());
      model.setRemoteClientIpAddress(iceComponent
          .getSelectedPair().getRemoteCandidate().getTransportAddress().getAddress());
      model.setLocalIceSuccessful(true);
      model.setConnectionStatus("ICE sucessful", 1);

    } else {
      model.setLocalIceSuccessful(false);
    }

    agent.free();

    //send results
    sendMyIceProcessingState();

    //receive results
    receiveOtherIceProcessingState();

    closeConnection();

    LOGGER.info("myState: " + model.isLocalIceSuccessful());
    LOGGER.info("remoteState: " + model.isRemoteIceSuccessful());
  }


  /**
   * Receive other state.
   */
  private void receiveOtherIceProcessingState() {
    LOGGER.info("RSCCCFP: wait for other state");
    try {
      int remoteIceProcessState = inputStream.read();
      if (remoteIceProcessState == 1) {
        model.setRemoteIceSuccessful(true);
      } else {
        model.setRemoteIceSuccessful(false);
      }
      LOGGER.info("RSCCCFP: received other state");
      model.setConnectionStatus("Received remote state", 1);


    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Sends ICE-Result to opposite.
   */
  public void sendMyIceProcessingState() {
    LOGGER.info("RSCCCFP: Send myIceProcessingState");
    try {
      outputStream.writeBoolean(model.isLocalIceSuccessful());
      outputStream.flush();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * creates SDP.
   */
  private String createSdp() throws Throwable {
    return SdpUtils.createSdp(agent);
  }

  /**
   * Sends SDP-Dump to opposite.
   *
   * @param sdpDump the generated SDP as String.
   */
  public void sendMySdp(String sdpDump) {
    LOGGER.info("RSCCCFP: Sending this SDP:");
    LOGGER.info(mySdp);

    try {
      outputStream.writeBytes("sdpStart" + '\n');
      outputStream.writeBytes(sdpDump + '\n');
      outputStream.writeBytes("sdpEnd" + '\n');
      outputStream.flush();
      LOGGER.info("RSCCCFP: sent sdp");
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * reads SDP-Dump from opposite.
   */
  private void receiveRemoteSdp() {

    LOGGER.info("RSCCCFP: wait for other sdp");
    model.setConnectionStatus("waiting for client to send possible connection-points", 1);

    StringBuilder receivedSdp = new StringBuilder();
    try {
      //wait for starting line
      String sdpLine = inputStream.readLine();
      if (sdpLine.equals("ForcingServerMode")) {
        LOGGER.info("RSCCCFP: Servermode is forced from opposite");
        return;
      }

      while (!sdpLine.equals("sdpStart")) {
        sdpLine = inputStream.readLine();
      }
      sdpLine = inputStream.readLine();

      while (!sdpLine.equals("sdpEnd")) {

        receivedSdp.append(sdpLine);
        receivedSdp.append('\n');
        sdpLine = inputStream.readLine();
      }

      LOGGER.info("RSCCCFP: received sdp:");
      LOGGER.info("RSCCCFP:" + receivedSdp.toString());

      remoteSdp = receivedSdp.toString();
      LOGGER.info("RSCCCFP: received other sdp");

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Closes All Connections.
   */
  public void closeConnection() {
    try {
      if (serverSocket != null && !serverSocket.isClosed()) {
        try {
          serverSocket.close();
        } catch (SocketException e) {
          LOGGER.info("RSCCCFP: Server Socket closed");
        }
      }
      if (connectionSocket != null && !connectionSocket.isClosed()) {
        connectionSocket.close();
      }
      if (outputStream != null) {
        outputStream.close();
      }
      if (inputStream != null) {
        inputStream.close();
      }
      agent.free();
    } catch (IOException e) {
      e.printStackTrace();
    }

  }


  /**
   * Ask public Stunservers for its own public IP and Port,
   * both active and passive have to do this.
   */
  private void startStun() throws Throwable {
    for (String hostname : model.getStunServers()) {
      try {
        InetAddress address = InetAddress.getByName(hostname);
        TransportAddress ta = new TransportAddress(
            address, model.getStunServerPort(), Transport.UDP);
        agent.addCandidateHarvester(new StunCandidateHarvester(ta));
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    IceMediaStream stream = agent.createMediaStream("data");
    agent.createComponent(
        stream, Transport.UDP, model.getIcePort(),
        model.getIcePort(), model.getIcePort() + 100);
  }

  /**
   * Checks if a connection is possible (only active part does it).
   *
   * @return Component contains all Information to build a UDP socket, null if ICE failed.
   */

  private Component startIceConnectivityEstablishment() throws Throwable {
    // You need to listen for state change so that once connected you can then use the socket.
    agent.startConnectivityEstablishment(); // This will do all the work for you to connect

    while (agent.getState() != IceProcessingState.TERMINATED) {
      Thread.sleep(1000);
      LOGGER.info("ICE Process running");


      if (agent.getState() == IceProcessingState.FAILED) {
        LOGGER.info("ICE Failed");
        model.setConnectionStatus("ICE unsucessful", 3);
        return null;
      }
    }

    LOGGER.info("Got a working socket");
    model.setConnectionStatus("ICE sucessful", 2);

    Component rtpComponent = iceStateListener.rtpComponent;

    return rtpComponent;
  }

}
