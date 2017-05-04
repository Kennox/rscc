package ch.imedias.ice4j.MRUDP;

/**
 * Created by pwg on 20.04.17.
 */

import ch.imedias.ice4j.IceProcess;
import net.rudp.ReliableServerSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class SimpleProxyViewerMRUDP {

    //Used by this person who gives support and runs xtightvncclient 127.0.0.1::2601

    public static final String OWNNAME = "RSCCViewer";
    public static final String REMOTECOMPUTERNAME = "RSCCRequester";
    public static final int LOCALFORWARDINGPORT = 2601;
    public static final int ICEPORT = 5060;

    public static void main(String[] args) throws Throwable {
        try {
            IceProcess.startIcePassive(ICEPORT, OWNNAME, REMOTECOMPUTERNAME);
            System.out.println("Ice done, starting MRUDP");


            runServer(); // never returns

        } catch (Exception e) {
            System.err.println(e);
        }
    }

    /**
     * runs a single-threaded proxy server o16384n
     * the specified local VNCPORT. It never returns.
     */
    public static void runServer()
            throws IOException {

        ServerSocket tcpServerSocket = new ServerSocket(LOCALFORWARDINGPORT);
        Socket tcpSocket;

        ReliableServerSocket mrudpServerSocket = new ReliableServerSocket(ICEPORT);
        Socket rudpSocket;

/*Possibly not needed as this is the passive part
        //Extract rtp Component
        DatagramSocket udpSocket = rtpComponent.getSocket();
        CandidatePair candidatePair = rtpComponent.getSelectedPair();
        TransportAddress transportAddress = candidatePair.getRemoteCandidate().getTransportAddress();
        InetAddress remoteAddress = transportAddress.getAddress();
        String remoteAddressAsString = remoteAddress.getHostAddress();
        int remotePort = transportAddress.getPort();
*/
        final byte[] request = new byte[1024];
        byte[] reply = new byte[16384];
/*
        SystemCommander startVNCServer = new SystemCommander();
        startVNCServer.executeTerminalCommand("x11vnc -forever");
*/
        // Create a ServerSocket to listen for connections with
        // ServerSocket ss = new ServerSocket(VNCPORT);

        while (true) {


            try {

                rudpSocket = mrudpServerSocket.accept();


                final InputStream streamFromServer = rudpSocket.getInputStream();
                final OutputStream streamToServer = rudpSocket.getOutputStream();

                /*TODO: does not work yet: maybe needs multithreading??
                     SystemCommander startxTightVncViewer=new SystemCommander();
                     startxTightVncViewer.executeTerminalCommand("xtightvncviewer 127.0.0.1::2601");
                   Problem: accept lässt warten? anschliessend kann Kommando evt nicht mehr ausgeführt werden?
                    */
                tcpSocket = tcpServerSocket.accept();
                tcpSocket.setTcpNoDelay(true);





                    //create VNC receiving Data





                // Get server streams.

                final InputStream streamFromClient = tcpSocket.getInputStream();
                final OutputStream streamToClient = tcpSocket.getOutputStream();

                // a thread to read the client's requests and pass them
                // to the server. A separate thread for asynchronous.
                Thread t = new Thread() {
                    public void run() {
                        int bytesRead;
                        try {
                            while ((bytesRead = streamFromClient.read(request)) != -1) {
                                streamToServer.write(request, 0, bytesRead);
                                streamToServer.flush();
                            }
                        } catch (IOException e) {
                        }

                        // the client closed the connection to us, so close our
                        // connection to the server.
                        try {
                            streamToServer.close();
                        } catch (IOException e) {
                        }
                    }
                };

                // Start the client-to-server request thread running
                t.start();

                // Read the server's responses
                // and pass them back to the client.
                int bytesRead;
                try {
                    while ((bytesRead = streamFromServer.read(reply)) != -1) {
                        streamToClient.write(reply, 0, bytesRead);
                        streamToClient.flush();
                    }
                } catch (IOException e) {
                }

                // The server closed its connection to us, so we close our
                // connection to our client.
                streamToClient.close();
            } catch (Exception e) {
                System.err.println(e);
            } finally {
               /* try {

                    if (rudpSocket != null) {
                        rudpSocket.close();

                       if (tcpSocket != null) {
                            tcpSocket.close();
                        }
                    }

                }catch(Exception e){}*/

            }
        }
    }
}



