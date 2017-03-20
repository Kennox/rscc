/*
 * SSLSocketToMe.java: add SSL encryption to Java VNC Viewer.
 *
 * Copyright (c) 2006 Karl J. Runge <runge@karlrunge.com>
 * All rights reserved.
 *
 *  This is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 *  USA.
 *
 */

import java.net.*;
import java.io.*;
import javax.net.ssl.*;
import java.util.*;

import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import java.awt.*;
import java.awt.event.*;

public class SSLSocketToMe {

	/* basic member data: */
	String host;
	int port;
	VncViewer viewer;

	boolean debug = true;
	boolean debug_certs = false;

	/* sockets */
	SSLSocket socket = null;
	SSLSocketFactory factory;

	/* fallback for Proxy connection */
	boolean proxy_in_use = false;
	boolean proxy_failure = false;
	public DataInputStream is = null;
	public OutputStream os = null;

	/* strings from user WRT proxy: */
	String proxy_auth_string = null;
	String proxy_dialog_host = null;
	int proxy_dialog_port = 0;

	Socket proxySock;
	DataInputStream proxy_is;
	OutputStream proxy_os;

	/* trust contexts */
	SSLContext trustloc_ctx;
	SSLContext trustall_ctx;
	SSLContext trustsrv_ctx;
	SSLContext trusturl_ctx;
	SSLContext trustone_ctx;

	/* corresponding trust managers */
	TrustManager[] trustAllCerts;
	TrustManager[] trustSrvCert;
	TrustManager[] trustUrlCert;
	TrustManager[] trustOneCert;

	/* client-side SSL auth key (oneTimeKey=...) */
	KeyManager[] mykey = null;

	boolean user_wants_to_see_cert = true;
	String cert_fail = null;

	/* cert(s) we retrieve from Web server, VNC server, or serverCert param: */
	java.security.cert.Certificate[] trustallCerts = null;
	java.security.cert.Certificate[] trustsrvCerts = null;
	java.security.cert.Certificate[] trusturlCerts = null;

	/* utility to decode hex oneTimeKey=... and serverCert=... */
	byte[] hex2bytes(String s) {
		byte[] bytes = new byte[s.length()/2];
		for (int i=0; i<s.length()/2; i++) {
			int j = 2*i;
			try {
				int val = Integer.parseInt(s.substring(j, j+2), 16);
				if (val > 127) {
					val -= 256;
				}
				Integer I = new Integer(val);
				bytes[i] = Byte.decode(I.toString()).byteValue();
				
			} catch (Exception e) {
				;
			}
		}
		return bytes;
	}

	SSLSocketToMe(String h, int p, VncViewer v) throws Exception {
		host = h;
		port = p;
		viewer = v;

		debug_certs = v.debugCerts;

		/* we will first try default factory for certification: */

		factory = (SSLSocketFactory) SSLSocketFactory.getDefault();

		dbg("SSL startup: " + host + " " + port);


		/* create trust managers to be used if initial handshake fails: */

		trustAllCerts = new TrustManager[] {
		    /*
		     * this one accepts everything.  Only used if user
		     * has disabled checking (trustAllVncCerts=yes)
		     * or when we grab the cert to show it to them in
		     * a dialog and ask them to manually verify/accept it.
		     */
		    new X509TrustManager() {
			public java.security.cert.X509Certificate[]
			    getAcceptedIssuers() {
				return null;
			}
			public void checkClientTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) {
				/* empty */
			}
			public void checkServerTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) {
				/* empty */
				dbg("ALL: an untrusted connect to grab cert.");
			}
		    }
		};

		trustUrlCert = new TrustManager[] {
		    /*
		     * this one accepts only the retrieved server
		     * cert by SSLSocket by this applet and stored in
		     * trusturlCerts.
		     */
		    new X509TrustManager() {
			public java.security.cert.X509Certificate[]
			    getAcceptedIssuers() {
				return null;
			}
			public void checkClientTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
				throw new CertificateException("No Clients (URL)");
			}
			public void checkServerTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
				/* we want to check 'certs' against 'trusturlCerts' */
				if (trusturlCerts == null) {
					throw new CertificateException(
					    "No Trust url Certs array.");
				}
				if (trusturlCerts.length < 1) {
					throw new CertificateException(
					    "No Trust url Certs.");
				}
				if (certs == null) {
					throw new CertificateException(
					    "No this-certs array.");
				}
				if (certs.length < 1) {
					throw new CertificateException(
					    "No this-certs Certs.");
				}
				if (certs.length != trusturlCerts.length) {
					throw new CertificateException(
					    "certs.length != trusturlCerts.length " + certs.length + " " + trusturlCerts.length);
				}
				boolean ok = true;
				for (int i = 0; i < certs.length; i++)  {
					if (! trusturlCerts[i].equals(certs[i])) {
						ok = false;
						dbg("URL: cert mismatch at i=" + i);
						dbg("URL: cert mismatch cert" + certs[i]);
						dbg("URL: cert mismatch  url" + trusturlCerts[i]);
						if (cert_fail == null) {
							cert_fail = "cert-mismatch";
						}
					}
					if (debug_certs) {
						dbg("\n***********************************************");
						dbg("URL: cert info at i=" + i);
						dbg("URL: cert info cert" + certs[i]);
						dbg("===============================================");
						dbg("URL: cert info  url" + trusturlCerts[i]);
						dbg("***********************************************");
					}
				}
				if (!ok) {
					throw new CertificateException(
					    "Server Cert Chain != URL Cert Chain.");
				}
				dbg("URL: trusturlCerts[i] matches certs[i] i=0:" + (certs.length-1));
			}
		    }
		};

		trustSrvCert = new TrustManager[] {
		    /*
		     * this one accepts cert given to us in the serverCert
		     * Applet Parameter we were started with.  It is
		     * currently a fatal error if the VNC Server's cert
		     * doesn't match it.
		     */
		    new X509TrustManager() {
			public java.security.cert.X509Certificate[]
			    getAcceptedIssuers() {
				return null;
			}
			public void checkClientTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
				throw new CertificateException("No Clients (SRV)");
			}
			public void checkServerTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
				/* we want to check 'certs' against 'trustsrvCerts' */
				if (trustsrvCerts == null) {
					throw new CertificateException(
					    "No Trust srv Certs array.");
				}
				if (trustsrvCerts.length < 1) {
					throw new CertificateException(
					    "No Trust srv Certs.");
				}
				if (certs == null) {
					throw new CertificateException(
					    "No this-certs array.");
				}
				if (certs.length < 1) {
					throw new CertificateException(
					    "No this-certs Certs.");
				}
				if (certs.length != trustsrvCerts.length) {
					throw new CertificateException(
					    "certs.length != trustsrvCerts.length " + certs.length + " " + trustsrvCerts.length);
				}
				boolean ok = true;
				for (int i = 0; i < certs.length; i++)  {
					if (! trustsrvCerts[i].equals(certs[i])) {
						ok = false;
						dbg("SRV: cert mismatch at i=" + i);
						dbg("SRV: cert mismatch cert" + certs[i]);
						dbg("SRV: cert mismatch  srv" + trustsrvCerts[i]);
						if (cert_fail == null) {
							cert_fail = "server-cert-mismatch";
						}
					}
					if (debug_certs) {
						dbg("\n***********************************************");
						dbg("SRV: cert info at i=" + i);
						dbg("SRV: cert info cert" + certs[i]);
						dbg("===============================================");
						dbg("SRV: cert info  srv" + trustsrvCerts[i]);
						dbg("***********************************************");
					}
				}
				if (!ok) {
					throw new CertificateException(
					    "Server Cert Chain != serverCert Applet Parameter Cert Chain.");
				}
				dbg("SRV: trustsrvCerts[i] matches certs[i] i=0:" + (certs.length-1));
			}
		    }
		};

		trustOneCert = new TrustManager[] {
		    /*
		     * this one accepts only the retrieved server
		     * cert by SSLSocket by this applet we stored in
		     * trustallCerts that user has accepted or applet
		     * parameter trustAllVncCerts=yes is set.  This is
		     * for when we reconnect after the user has manually
		     * accepted the trustall cert in the dialog (or set
		     * trustAllVncCerts=yes applet param.)
		     */
		    new X509TrustManager() {
			public java.security.cert.X509Certificate[]
			    getAcceptedIssuers() {
				return null;
			}
			public void checkClientTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
				throw new CertificateException("No Clients (ONE)");
			}
			public void checkServerTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
				/* we want to check 'certs' against 'trustallCerts' */
				if (trustallCerts == null) {
					throw new CertificateException(
					    "No Trust All Server Certs array.");
				}
				if (trustallCerts.length < 1) {
					throw new CertificateException(
					    "No Trust All Server Certs.");
				}
				if (certs == null) {
					throw new CertificateException(
					    "No this-certs array.");
				}
				if (certs.length < 1) {
					throw new CertificateException(
					    "No this-certs Certs.");
				}
				if (certs.length != trustallCerts.length) {
					throw new CertificateException(
					    "certs.length != trustallCerts.length " + certs.length + " " + trustallCerts.length);
				}
				boolean ok = true;
				for (int i = 0; i < certs.length; i++)  {
					if (! trustallCerts[i].equals(certs[i])) {
						ok = false;
						dbg("ONE: cert mismatch at i=" + i);
						dbg("ONE: cert mismatch cert" + certs[i]);
						dbg("ONE: cert mismatch  all" + trustallCerts[i]);
					}
					if (debug_certs) {
						dbg("\n***********************************************");
						dbg("ONE: cert info at i=" + i);
						dbg("ONE: cert info cert" + certs[i]);
						dbg("===============================================");
						dbg("ONE: cert info  all" + trustallCerts[i]);
						dbg("***********************************************");
					}
				}
				if (!ok) {
					throw new CertificateException(
					    "Server Cert Chain != TRUSTALL Cert Chain.");
				}
				dbg("ONE: trustallCerts[i] matches certs[i] i=0:" + (certs.length-1));
			}
		    }
		};

		/* 
		 * The above TrustManagers are used:
		 *
		 * 1) to retrieve the server cert in case of failure to
		 *    display it to the user in a dialog.
		 * 2) to subsequently connect to the server if user agrees.
		 */

		/*
		 * build oneTimeKey cert+key if supplied in applet parameter:
		 */
		if (viewer.oneTimeKey != null && viewer.oneTimeKey.equals("PROMPT")) {
			ClientCertDialog d = new ClientCertDialog();
			viewer.oneTimeKey = d.queryUser();
		}
		if (viewer.oneTimeKey != null && viewer.oneTimeKey.indexOf(",") > 0) {
			int idx = viewer.oneTimeKey.indexOf(",");

			String onetimekey = viewer.oneTimeKey.substring(0, idx);
			byte[] key = hex2bytes(onetimekey);
			String onetimecert = viewer.oneTimeKey.substring(idx+1);
			byte[] cert = hex2bytes(onetimecert);

			KeyFactory kf = KeyFactory.getInstance("RSA");
			PKCS8EncodedKeySpec keysp = new PKCS8EncodedKeySpec ( key );
			PrivateKey ff = kf.generatePrivate (keysp);
			if (debug_certs) {
				dbg("one time key " + ff);
			}

			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			Collection c = cf.generateCertificates(new ByteArrayInputStream(cert));
			Certificate[] certs = new Certificate[c.toArray().length];
			if (c.size() == 1) {
				Certificate tmpcert = cf.generateCertificate(new ByteArrayInputStream(cert));
				if (debug_certs) {
					dbg("one time cert" + tmpcert);
				}
				certs[0] = tmpcert;
			} else {
				certs = (Certificate[]) c.toArray();
			}

			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(null, null);
			ks.setKeyEntry("onetimekey", ff, "".toCharArray(), certs);
			String da = KeyManagerFactory.getDefaultAlgorithm();
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(da);
			kmf.init(ks, "".toCharArray());

			mykey = kmf.getKeyManagers();
		}

		/*
		 * build serverCert cert if supplied in applet parameter:
		 */
		if (viewer.serverCert != null) {
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			byte[] cert = hex2bytes(viewer.serverCert);
			Collection c = cf.generateCertificates(new ByteArrayInputStream(cert));
			trustsrvCerts = new Certificate[c.toArray().length];
			if (c.size() == 1) {
				Certificate tmpcert = cf.generateCertificate(new ByteArrayInputStream(cert));
				trustsrvCerts[0] = tmpcert;
			} else {
				trustsrvCerts = (Certificate[]) c.toArray();
			}
		}

		/* the trust loc certs context: */
		try {
			trustloc_ctx = SSLContext.getInstance("SSL");

			/*
			 * below is a failed attempt to get jvm's default
			 * trust manager using null (below) makes it so
			 * for HttpsURLConnection the server cannot be
			 * verified (no prompting.)
			 */
			if (false) {
				boolean didit = false;
				TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());  
				tmf.init((KeyStore) null);
				TrustManager [] tml = tmf.getTrustManagers();
				for (int i = 0; i < tml.length; i++) {
					TrustManager tm = tml[i];
					if (tm instanceof X509TrustManager) {
						TrustManager tm1[] = new TrustManager[1];
						tm1[0] = tm;
						trustloc_ctx.init(mykey, tm1, null);
						didit = true;
						break;
					}
				}
				if (!didit) {
					trustloc_ctx.init(mykey, null, null);
				}
			} else {
				/* we have to set trust manager to null */
				trustloc_ctx.init(mykey, null, null);
			}

		} catch (Exception e) {
			String msg = "SSL trustloc_ctx FAILED.";
			dbg(msg);
			throw new Exception(msg);
		}

		/* the trust all certs context: */
		try {
			trustall_ctx = SSLContext.getInstance("SSL");
			trustall_ctx.init(mykey, trustAllCerts, new
			    java.security.SecureRandom());

		} catch (Exception e) {
			String msg = "SSL trustall_ctx FAILED.";
			dbg(msg);
			throw new Exception(msg);
		}

		/* the trust url certs context: */
		try {
			trusturl_ctx = SSLContext.getInstance("SSL");
			trusturl_ctx.init(mykey, trustUrlCert, new
			    java.security.SecureRandom());

		} catch (Exception e) {
			String msg = "SSL trusturl_ctx FAILED.";
			dbg(msg);
			throw new Exception(msg);
		}

		/* the trust srv certs context: */
		try {
			trustsrv_ctx = SSLContext.getInstance("SSL");
			trustsrv_ctx.init(mykey, trustSrvCert, new
			    java.security.SecureRandom());

		} catch (Exception e) {
			String msg = "SSL trustsrv_ctx FAILED.";
			dbg(msg);
			throw new Exception(msg);
		}

		/* the trust the one cert from server context: */
		try {
			trustone_ctx = SSLContext.getInstance("SSL");
			trustone_ctx.init(mykey, trustOneCert, new
			    java.security.SecureRandom());

		} catch (Exception e) {
			String msg = "SSL trustone_ctx FAILED.";
			dbg(msg);
			throw new Exception(msg);
		}
	}

	/*
	 * we call this early on to 1) check for a proxy, 2) grab
	 * Browser/JVM accepted HTTPS cert.
	 */
	public void check_for_proxy_and_grab_vnc_server_cert() {
		
		trusturlCerts = null;
		proxy_in_use = false;

		if (viewer.ignoreProxy) {
			/* applet param says skip it. */
			/* the downside is we do not set trusturlCerts for comparison later... */
			/* nor do we autodetect x11vnc for GET=1. */
			return;
		}

		dbg("------------------------------------------------");
		dbg("Into check_for_proxy_and_grab_vnc_server_cert():");

		dbg("TRYING HTTPS:");
		String ustr = "https://" + host + ":";
		if (viewer.httpsPort != null) {
			ustr += viewer.httpsPort;
		} else {
			ustr += port;
		}
		ustr += viewer.urlPrefix + "/check.https.proxy.connection";
		dbg("ustr is: " + ustr);

		try {
			/* prepare for an HTTPS URL connection to host:port */
			URL url = new URL(ustr);
			HttpsURLConnection https = (HttpsURLConnection) url.openConnection();

			if (mykey != null) {
				/* with oneTimeKey (mykey) we can't use the default SSL context */
				if (trustsrvCerts != null) {
					dbg("passing trustsrv_ctx to HttpsURLConnection to provide client cert.");
					https.setSSLSocketFactory(trustsrv_ctx.getSocketFactory());	
				} else if (trustloc_ctx != null) {
					dbg("passing trustloc_ctx to HttpsURLConnection to provide client cert.");
					https.setSSLSocketFactory(trustloc_ctx.getSocketFactory());	
				}
			}

			https.setUseCaches(false);
			https.setRequestMethod("GET");
			https.setRequestProperty("Pragma", "No-Cache");
			https.setRequestProperty("Proxy-Connection", "Keep-Alive");
			https.setDoInput(true);

			dbg("trying https.connect()");
			https.connect();

			dbg("trying https.getServerCertificates()");
			trusturlCerts = https.getServerCertificates();

			if (trusturlCerts == null) {
				dbg("set trusturlCerts to null!");
			} else {
				dbg("set trusturlCerts to non-null");
			}

			if (https.usingProxy()) {
				proxy_in_use = true;
				dbg("An HTTPS proxy is in use. There may be connection problems.");
			}

			dbg("trying https.getContent()");
			Object output = https.getContent();
			dbg("trying https.disconnect()");
			https.disconnect();
			if (! viewer.GET) {
				String header = https.getHeaderField("VNC-Server");
				if (header != null && header.startsWith("x11vnc")) {
					dbg("detected x11vnc server (1), setting GET=1");
					viewer.GET = true;
				}
			}

		} catch(Exception e) {
			dbg("HttpsURLConnection: " + e.getMessage());
		}

		if (proxy_in_use) {
			dbg("exit check_for_proxy_and_grab_vnc_server_cert():");
			dbg("------------------------------------------------");
			return;
		} else if (trusturlCerts != null && !viewer.forceProxy) {
			/* Allow user to require HTTP check?  use forceProxy for now. */
			dbg("SKIPPING HTTP PROXY CHECK: got trusturlCerts, assuming proxy info is correct.");
			dbg("exit check_for_proxy_and_grab_vnc_server_cert():");
			dbg("------------------------------------------------");
			return;
		}

		/*
		 * XXX need to remember scenario where this extra check
		 * gives useful info.  User's Browser proxy settings?
		 */
		dbg("TRYING HTTP:");
		ustr = "http://" + host + ":" + port;
		ustr += viewer.urlPrefix + "/index.vnc";
		dbg("ustr is: " + ustr);

		try {
			/* prepare for an HTTP URL connection to the same host:port (but not httpsPort) */
			URL url = new URL(ustr);
			HttpURLConnection http = (HttpURLConnection)
			    url.openConnection();

			http.setUseCaches(false);
			http.setRequestMethod("GET");
			http.setRequestProperty("Pragma", "No-Cache");
			http.setRequestProperty("Proxy-Connection", "Keep-Alive");
			http.setDoInput(true);

			dbg("trying http.connect()");
			http.connect();

			if (http.usingProxy()) {
				proxy_in_use = true;
				dbg("An HTTP proxy is in use. There may be connection problems.");
			}
			dbg("trying http.getContent()");
			Object output = http.getContent();
			dbg("trying http.disconnect()");
			http.disconnect();
			if (! viewer.GET) {
				String header = http.getHeaderField("VNC-Server");
				if (header != null && header.startsWith("x11vnc")) {
					dbg("detected x11vnc server (2), setting GET=1");
					viewer.GET = true;
				}
			}
		} catch(Exception e) {
			dbg("HttpURLConnection:  " + e.getMessage());
		}
		dbg("exit check_for_proxy_and_grab_vnc_server_cert():");
		dbg("------------------------------------------------");
	}

	public Socket connectSock() throws IOException {
		/*
		 * first try a https connection to detect a proxy, and
		 * grab the VNC server cert at the same time:
		 */
		check_for_proxy_and_grab_vnc_server_cert();

		boolean srv_cert = false;
		
		if (trustsrvCerts != null) {
			/* applet parameter suppled serverCert */
			dbg("viewer.trustSrvCert-0 using trustsrv_ctx");
			factory = trustsrv_ctx.getSocketFactory();
			srv_cert = true;
		} else if (viewer.trustAllVncCerts) {
			/* trust all certs (no checking) */
			dbg("viewer.trustAllVncCerts-0 using trustall_ctx");
			factory = trustall_ctx.getSocketFactory();
		} else if (trusturlCerts != null) {
			/* trust certs the Browser/JVM accepted in check_for_proxy... */
			dbg("using trusturl_ctx");
			factory = trusturl_ctx.getSocketFactory();
		} else {
			/* trust the local defaults */
			dbg("using trustloc_ctx");
			factory = trustloc_ctx.getSocketFactory();
		}

		socket = null;

		try {
			if (proxy_in_use && viewer.forceProxy) {
				throw new Exception("forcing proxy (forceProxy)");
			} else if (viewer.CONNECT != null) {
				throw new Exception("forcing CONNECT");
			}

			int timeout = 6;
			if (timeout > 0) {
				socket = (SSLSocket) factory.createSocket();
				InetSocketAddress inetaddr = new InetSocketAddress(host, port);
				dbg("Using timeout of " + timeout + " secs to: " + host + ":" + port);
				socket.connect(inetaddr, timeout * 1000);
			} else {
				socket = (SSLSocket) factory.createSocket(host, port);
			}

		} catch (Exception esock) {
			dbg("socket error: " + esock.getMessage());
			if (proxy_in_use || viewer.CONNECT != null) {
				proxy_failure = true;
				if (proxy_in_use) {
					dbg("HTTPS proxy in use. Trying to go with it.");
				} else {
					dbg("viewer.CONNECT reverse proxy in use. Trying to go with it.");
				}
				try {
					socket = proxy_socket(factory);
				} catch (Exception e) {
					dbg("proxy_socket error: " + e.getMessage());
				}
			} else {
				/* n.b. socket is left in error state to cause ex. below. */
			}
		}

		try {
			socket.startHandshake();

			dbg("The Server Connection Verified OK on 1st try.");

			java.security.cert.Certificate[] currentTrustedCerts;
			BrowserCertsDialog bcd;

			SSLSession sess = socket.getSession();
			currentTrustedCerts = sess.getPeerCertificates();

			if (viewer.trustAllVncCerts) {
				dbg("viewer.trustAllVncCerts-1  keeping socket.");
			} else if (currentTrustedCerts == null || currentTrustedCerts.length < 1) {
				try {
					socket.close();
				} catch (Exception e) {
					dbg("socket is grumpy.");
				}
				socket = null;
				throw new SSLHandshakeException("no current certs");
			}

			String serv = "";
			try {
				CertInfo ci = new CertInfo(currentTrustedCerts[0]);
				serv = ci.get_certinfo("CN");
			} catch (Exception e) {
				;
			}

			if (viewer.trustAllVncCerts) {
				dbg("viewer.trustAllVncCerts-2  skipping browser certs dialog");
				user_wants_to_see_cert = false;
			} else if (viewer.serverCert != null && trustsrvCerts != null) {
				dbg("viewer.serverCert-1  skipping browser certs dialog");
				user_wants_to_see_cert = false;
			} else if (viewer.trustUrlVncCert) {
				dbg("viewer.trustUrlVncCert-1  skipping browser certs dialog");
				user_wants_to_see_cert = false;
			} else {
				/* have a dialog with the user: */
				bcd = new BrowserCertsDialog(serv, host + ":" + port);
				dbg("browser certs dialog begin.");
				bcd.queryUser();
				dbg("browser certs dialog finished.");

				if (bcd.showCertDialog) {
					String msg = "user wants to see cert";
					dbg(msg);
					user_wants_to_see_cert = true;
					if (cert_fail == null) {
						cert_fail = "user-view";
					}
					throw new SSLHandshakeException(msg);
				} else {
					user_wants_to_see_cert = false;
					dbg("browser certs dialog: user said yes, accept it");
				}
			}

		} catch (SSLHandshakeException eh)  {
			dbg("SSLHandshakeException: could not automatically verify Server.");
			dbg("msg: " + eh.getMessage());


			/* send a cleanup string just in case: */
			String getoutstr = "GET /index.vnc HTTP/1.0\r\nConnection: close\r\n\r\n";

			try {
				OutputStream os = socket.getOutputStream();
				os.write(getoutstr.getBytes());
				socket.close();
			} catch (Exception e) {
				dbg("socket is grumpy!");
			}

			/* reload */

			socket = null;

			String reason = null;

			if (srv_cert) {
				/* for serverCert usage we make this a fatal error. */
				throw new IOException("Fatal: VNC Server's Cert does not match Applet Parameter 'serverCert=...'");
				/* see below in TrustDialog were we describe this case to user anyway */
			}

			/*
			 * Reconnect, trusting any cert, so we can grab
			 * the cert to show it to the user in a dialog
			 * for him to manually accept.  This connection
			 * is not used for anything else.
			 */
			factory = trustall_ctx.getSocketFactory();
			if (proxy_failure) {
				socket = proxy_socket(factory);
			} else {
				socket = (SSLSocket) factory.createSocket(host, port);
			}

			if (debug_certs) {
				dbg("trusturlCerts: " + trusturlCerts);
				dbg("trustsrvCerts: " + trustsrvCerts);
			}
			if (trusturlCerts == null && cert_fail == null) {
				cert_fail = "missing-certs";
			}

			try {
				socket.startHandshake();

				dbg("The TrustAll Server Cert-grab Connection (trivially) Verified OK.");

				/* grab the cert: */
				try {
					SSLSession sess = socket.getSession();
					trustallCerts = sess.getPeerCertificates();
				} catch (Exception e) {
					throw new Exception("Could not get " + 
					    "Peer Certificate");	
				}
				if (debug_certs) {
					dbg("trustallCerts: " + trustallCerts);
				}

				if (viewer.trustAllVncCerts) {
					dbg("viewer.trustAllVncCerts-3.  skipping dialog, trusting everything.");
				} else if (! browser_cert_match()) {
					/*
					 * close socket now, we will reopen after
					 * dialog if user agrees to use the cert.
					 */
					try {
						OutputStream os = socket.getOutputStream();
						os.write(getoutstr.getBytes());
						socket.close();
					} catch (Exception e) {
						dbg("socket is grumpy!!");
					}
					socket = null;

					/* dialog with user to accept cert or not: */

					TrustDialog td= new TrustDialog(host, port,
					    trustallCerts);

					if (cert_fail == null) {
						;
					} else if (cert_fail.equals("user-view")) {
						reason = "Reason for this Dialog:\n\n"
						       + "        You Asked to View the Certificate.";
					} else if (cert_fail.equals("server-cert-mismatch")) {
						/* this is now fatal error, see above. */
						reason = "Reason for this Dialog:\n\n"
						       + "        The VNC Server's Certificate does not match the Certificate\n"
						       + "        specified in the supplied 'serverCert' Applet Parameter.";
					} else if (cert_fail.equals("cert-mismatch")) {
						reason = "Reason for this Dialog:\n\n"
						       + "        The VNC Server's Certificate does not match the Website's\n"
						       + "        HTTPS Certificate (that you previously accepted; either\n"
						       + "        manually or automatically via Certificate Authority.)";
					} else if (cert_fail.equals("missing-certs")) {
						reason = "Reason for this Dialog:\n\n"
						       + "        Not all Certificates could be obtained to check.";
					}

					if (! td.queryUser(reason)) {
						String msg = "User decided against it.";
						dbg(msg);
						throw new IOException(msg);
					}
				}

			} catch (Exception ehand2)  {
				dbg("** Could not TrustAll Verify Server!");

				throw new IOException(ehand2.getMessage());
			}

			/* reload again: */

			if (socket != null) {
				try {
					socket.close();
				} catch (Exception e) {
					dbg("socket is grumpy!!!");
				}
				socket = null;
			}

			/*
			 * Now connect a 3rd time, using the cert
			 * retrieved during connection 2 (sadly, that
			 * the user likely blindly agreed to...)
			 */

			factory = trustone_ctx.getSocketFactory();
			if (proxy_failure) {
				socket = proxy_socket(factory);
			} else {
				socket = (SSLSocket) factory.createSocket(host, port);
			}

			try {
				socket.startHandshake();
				dbg("TrustAll/TrustOne Server Connection Verified #3.");

			} catch (Exception ehand3)  {
				dbg("** Could not TrustAll/TrustOne Verify Server #3.");

				throw new IOException(ehand3.getMessage());
			}
		}

		/* we have socket (possibly null) at this point, so proceed: */

		/* handle x11vnc GET=1, if applicable: */
		if (socket != null && viewer.GET) {
			String str = "GET ";
			str += viewer.urlPrefix;
			str += "/request.https.vnc.connection";
			str += " HTTP/1.0\r\n";
			str += "Pragma: No-Cache\r\n";
			str += "\r\n";

			System.out.println("sending: " + str);
    			OutputStream os = socket.getOutputStream();
			String type = "os";

			if (type == "os") {
				os.write(str.getBytes());
				os.flush();
				System.out.println("used OutputStream");
			} else if (type == "bs") {
				BufferedOutputStream bs = new BufferedOutputStream(os);
				bs.write(str.getBytes());
				bs.flush();
				System.out.println("used BufferedOutputStream");
			} else if (type == "ds") {
				DataOutputStream ds = new DataOutputStream(os);
				ds.write(str.getBytes());
				ds.flush();
				System.out.println("used DataOutputStream");
			}
			if (false) {
				String rep = "";
				DataInputStream is = new DataInputStream(
				    new BufferedInputStream(socket.getInputStream(), 16384));
				while (true) {
					rep += readline(is);
					if (rep.indexOf("\r\n\r\n") >= 0) {
						break;
					}
				}
				System.out.println("rep: " + rep);
			}
		}

		dbg("SSL returning socket to caller.");
		dbg("");

		/* could be null, let caller handle that. */
		return (Socket) socket;
	}

	boolean browser_cert_match() {
		String msg = "Browser URL accept previously accepted cert";

		if (user_wants_to_see_cert) {
			return false;
		}

		if (viewer.serverCert != null || trustsrvCerts != null) {
			if (cert_fail == null) {
				cert_fail = "server-cert-mismatch";
			}
		}
		if (trustallCerts != null && trusturlCerts != null) {
		    if (trustallCerts.length == trusturlCerts.length) {
			boolean ok = true;
			/* check toath trustallCerts (socket) equals trusturlCerts (browser) */
			for (int i = 0; i < trusturlCerts.length; i++)  {
				if (! trustallCerts[i].equals(trusturlCerts[i])) {
					dbg("BCM: cert mismatch at i=" + i);
					dbg("BCM: cert mismatch  url" + trusturlCerts[i]);
					dbg("BCM: cert mismatch  all" + trustallCerts[i]);
					ok = false;
				}
			}
			if (ok) {
				System.out.println(msg);
				if (cert_fail == null) {
					cert_fail = "did-not-fail";
				}
				return true;
			} else {
				if (cert_fail == null) {
					cert_fail = "cert-mismatch";
				}
				return false;
			}
		    }
		}
		if (cert_fail == null) {
			cert_fail = "missing-certs";
		}
		return false;
	}

	private void dbg(String s) {
		if (debug) {
			System.out.println(s);
		}
	}

	private int gint(String s) {
		int n = -1;
		try {
			Integer I = new Integer(s);
			n = I.intValue();
		} catch (Exception ex) {
			return -1;
		}
		return n;
	}

	/* this will do the proxy CONNECT negotiation and hook us up.  */

	private void proxy_helper(String proxyHost, int proxyPort) {

		boolean proxy_auth = false;
		String proxy_auth_basic_realm = "";
		String hp = host + ":" + port;
		dbg("proxy_helper: " + proxyHost + ":" + proxyPort + " hp: " + hp);

		/* we loop here a few times trying for the password case */
		for (int k=0; k < 2; k++) {
			dbg("proxy_in_use psocket: " + k);

			if (proxySock != null) {
				try {
					proxySock.close();
				} catch (Exception e) {
					dbg("proxy socket is grumpy.");
				}
			}

			proxySock = psocket(proxyHost, proxyPort);
			if (proxySock == null) {
				dbg("1-a sadly, returning a null socket");
				return;
			}

			String req1 = "CONNECT " + hp + " HTTP/1.1\r\n"
			    + "Host: " + hp + "\r\n";

			dbg("requesting via proxy: " + req1);

			if (proxy_auth) {
				if (proxy_auth_string == null) {
					ProxyPasswdDialog pp = new ProxyPasswdDialog(proxyHost, proxyPort, proxy_auth_basic_realm);
					pp.queryUser();
					proxy_auth_string = pp.getAuth();
				}
				//dbg("auth1: " + proxy_auth_string);

				String auth2 = Base64Coder.encodeString(proxy_auth_string);
				//dbg("auth2: " + auth2);

				req1 += "Proxy-Authorization: Basic " + auth2 + "\r\n";
				//dbg("req1: " + req1);

				dbg("added Proxy-Authorization: Basic ... to request");
			}
			req1 += "\r\n";

			try {
				proxy_os.write(req1.getBytes());
				String reply = readline(proxy_is);

				dbg("proxy replied: " + reply.trim());

				if (reply.indexOf("HTTP/1.") == 0 && reply.indexOf(" 407 ") > 0) {
					proxy_auth = true;
					proxySock.close();
				} else if (reply.indexOf("HTTP/1.") < 0 && reply.indexOf(" 200") < 0) {
					proxySock.close();
					proxySock = psocket(proxyHost, proxyPort);
					if (proxySock == null) {
						dbg("2-a sadly, returning a null socket");
						return;
					}
				}
			} catch(Exception e) {
				dbg("some proxy socket problem: " + e.getMessage());
			}

			/* read the rest of the HTTP headers */
			while (true) {
				String line = readline(proxy_is);
				dbg("proxy line: " + line.trim());
				if (proxy_auth) {
					String uc = line.toLowerCase();
					if (uc.indexOf("proxy-authenticate:") == 0) {
						if (uc.indexOf(" basic ") >= 0) {
							int idx = uc.indexOf(" realm");
							if (idx >= 0) {
								proxy_auth_basic_realm = uc.substring(idx+1);
							}
						}
					}
				}
				if (line.equals("\r\n") || line.equals("\n")) {
					break;
				}
			}
			if (!proxy_auth || proxy_auth_basic_realm.equals("")) {
				/* we only try once for the non-password case: */
				break;
			}
		}
	}

	public SSLSocket proxy_socket(SSLSocketFactory factory) {
		Properties props = null;
		String proxyHost = null;
		int proxyPort = 0;
		String proxyHost_nossl = null;
		int proxyPort_nossl = 0;
		String str;

		/* see if we can guess the proxy info from Properties: */
		try {
			props = System.getProperties();
		} catch (Exception e) {
			/* sandboxed applet might not be able to read it. */
			dbg("props failed: " + e.getMessage());
		}
		if (viewer.proxyHost != null) {
			dbg("Using supplied proxy " + viewer.proxyHost + " " + viewer.proxyPort + " applet parameters.");
			proxyHost = viewer.proxyHost;
			if (viewer.proxyPort != null) {
				proxyPort = gint(viewer.proxyPort);
			} else {
				proxyPort = 8080;
			}
			
		} else if (props != null) {
			dbg("\n---------------\nAll props:");
			props.list(System.out);
			dbg("\n---------------\n\n");

			/* scrape throught properties looking for proxy info: */

			for (Enumeration e = props.propertyNames(); e.hasMoreElements(); ) {
				String s = (String) e.nextElement();
				String v = System.getProperty(s);
				String s2 = s.toLowerCase();
				String v2 = v.toLowerCase();

				if (s2.indexOf("proxy.https.host") >= 0) {
					proxyHost = v2;
					continue;
				}
				if (s2.indexOf("proxy.https.port") >= 0) {
					proxyPort = gint(v2);
					continue;
				}
				if (s2.indexOf("proxy.http.host") >= 0) {
					proxyHost_nossl = v2;
					continue;
				}
				if (s2.indexOf("proxy.http.port") >= 0) {
					proxyPort_nossl = gint(v2);
					continue;
				}
			}

			for (Enumeration e = props.propertyNames(); e.hasMoreElements(); ) {
				String s = (String) e.nextElement();
				String v = System.getProperty(s);
				String s2 = s.toLowerCase();
				String v2 = v.toLowerCase();

				if (proxyHost != null && proxyPort > 0) {
					break;
				}

				// look for something like: javaplugin.proxy.config.list = http=10.0.2.1:8082
				if (s2.indexOf("proxy") < 0 && v2.indexOf("proxy") < 0) {
					continue;
				}
				if (v2.indexOf("http") < 0) {
					continue;
				}

				String[] pieces = v.split("[,;]");
				for (int i = 0; i < pieces.length; i++) {
					String p = pieces[i];
					int j = p.indexOf("https");
					if (j < 0) {
						j = p.indexOf("http");
						if (j < 0) {
							continue;
						}
					}
					j = p.indexOf("=", j);
					if (j < 0) {
						continue;
					}
					p = p.substring(j+1);
					String [] hp = p.split(":");
					if (hp.length != 2) {
						continue;
					}
					if (hp[0].length() > 1 && hp[1].length() > 1) {

						proxyPort = gint(hp[1]);
						if (proxyPort < 0) {
							continue;
						}
						proxyHost = new String(hp[0]);
						break;
					}
				}
			}
		}
		if (proxyHost != null) {
			if (proxyHost_nossl != null && proxyPort_nossl > 0) {
				dbg("Using http proxy info instead of https.");
				proxyHost = proxyHost_nossl;
				proxyPort = proxyPort_nossl;
			}
		}

		if (proxy_in_use) {
			if (proxy_dialog_host != null && proxy_dialog_port > 0) {
				proxyHost = proxy_dialog_host;
				proxyPort = proxy_dialog_port;
			}
			if (proxyHost != null) {
				dbg("Lucky us! we figured out the Proxy parameters: " + proxyHost + " " + proxyPort);
			} else {
				/* ask user to help us: */
				ProxyDialog pd = new ProxyDialog(proxyHost, proxyPort);
				pd.queryUser();
				proxyHost = pd.getHost(); 
				proxyPort = pd.getPort();
				proxy_dialog_host = new String(proxyHost);
				proxy_dialog_port = proxyPort;
				dbg("User said host: " + pd.getHost() + " port: " + pd.getPort());
			}

			proxy_helper(proxyHost, proxyPort);
			if (proxySock == null) {
				return null;
			}
		} else if (viewer.CONNECT != null) {
			dbg("viewer.CONNECT psocket:");
			proxySock = psocket(host, port);
			if (proxySock == null) {
				dbg("1-b sadly, returning a null socket");
				return null;
			}
		}
		
		if (viewer.CONNECT != null) {
			String hp = viewer.CONNECT;
			String req2 = "CONNECT " + hp + " HTTP/1.1\r\n"
			    + "Host: " + hp + "\r\n\r\n";

			dbg("requesting2: " + req2);

			try {
				proxy_os.write(req2.getBytes());
				String reply = readline(proxy_is);

				dbg("proxy replied2: " + reply.trim());

				if (reply.indexOf("HTTP/1.") < 0 && reply.indexOf(" 200") < 0) {
					proxySock.close();
					proxySock = psocket(proxyHost, proxyPort);
					if (proxySock == null) {
						dbg("2-b sadly, returning a null socket");
						return null;
					}
				}
			} catch(Exception e) {
				dbg("proxy socket problem-2: " + e.getMessage());
			}

			while (true) {
				String line = readline(proxy_is);
				dbg("proxy line2: " + line.trim());
				if (line.equals("\r\n") || line.equals("\n")) {
					break;
				}
			}
		}

		Socket sslsock = null;
		try {
			sslsock = factory.createSocket(proxySock, host, port, true);
		} catch(Exception e) {
			dbg("sslsock prob: " + e.getMessage());
			dbg("3 sadly, returning a null socket");
		}

		return (SSLSocket) sslsock;
	}

	Socket psocket(String h, int p) {
		Socket psock = null;
		try {
			psock = new Socket(h, p);
			proxy_is = new DataInputStream(new BufferedInputStream(
			    psock.getInputStream(), 16384));
			proxy_os = psock.getOutputStream();
		} catch(Exception e) {
			dbg("psocket prob: " + e.getMessage());
			return null;
		}

		return psock;
	}

	String readline(DataInputStream i) {
		byte[] ba = new byte[1];
		String s = new String("");
		ba[0] = 0;
		try {
			while (ba[0] != 0xa) {
				ba[0] = (byte) i.readUnsignedByte();
				s += new String(ba);
			}
		} catch (Exception e) {
			;
		}
		return s;
	}
}

class TrustDialog implements ActionListener {
	String msg, host, text;
	int port;
	java.security.cert.Certificate[] trustallCerts = null;
	boolean viewing_cert = false;
	boolean trust_this_session = false;

	/*
	 * this is the gui to show the user the cert and info and ask
	 * them if they want to continue using this cert.
	 */

	Button ok, cancel, viewcert;
	TextArea textarea;
	Checkbox accept, deny;
	Dialog dialog;

	String s1 = "Accept this certificate temporarily for this session";
	String s2 = "Do not accept this certificate and do not connect to"
	    + " this VNC server";
	String ln = "\n---------------------------------------------------\n\n";
		
	TrustDialog (String h, int p, java.security.cert.Certificate[] s) {
		host = h;
		port = p;
		trustallCerts = s;

		msg = "VNC Server " + host + ":" + port + " Not Verified";
	}

	public boolean queryUser(String reason) {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame(msg);

		dialog = new Dialog(frame, true);

		String infostr = "";
		if (trustallCerts.length == 1) {
			CertInfo ci = new CertInfo(trustallCerts[0]);
			infostr = ci.get_certinfo("all");
		}
		if (reason != null) {
			reason += "\n\n";
		}

		text = "\n" 
+ "Unable to verify the identity of\n"
+ "\n"
+ "        " + host + ":" + port + "\n" 
+ "\n"
+ infostr
+ "\n"
+ "as a trusted VNC server.\n"
+ "\n"
+ reason
+ "In General not being able to verify the VNC Server and/or your seeing this Dialog\n"
+ "is due to one of the following:\n"
+ "\n"
+ " - Your requesting to View the Certificate before accepting.\n"
+ "\n"
+ " - The VNC server is using a Self-Signed Certificate or a Certificate\n"
+ "   Authority not recognized by your Web Browser or Java Plugin runtime.\n"
+ "\n"
+ " - The use of an Apache SSL portal scheme employing CONNECT proxying AND\n"
+ "   the Apache Web server has a certificate *different* from the VNC server's.\n"
+ "\n"
+ " - No previously accepted Certificate (via Web Broswer/Java Plugin) could be\n"
+ "   obtained by this applet to compare the VNC Server Certificate against.\n"
+ "\n"
+ " - The VNC Server's Certificate does not match the one specified in the\n"
+ "   supplied 'serverCert' Java Applet Parameter.\n"
+ "\n"
+ " - A Man-In-The-Middle attack impersonating as the VNC server that you wish\n"
+ "   to connect to.  (Wouldn't that be exciting!!)\n"
+ "\n"
+ "By safely copying the VNC server's Certificate (or using a common Certificate\n"
+ "Authority certificate) you can configure your Web Browser and Java Plugin to\n"
+ "automatically authenticate this VNC Server.\n"
+ "\n"
+ "If you do so, then you will only have to click \"Yes\" when this VNC Viewer\n"
+ "applet asks you whether to trust your Browser/Java Plugin's acceptance of the\n"
+ "certificate (except for the Apache portal case above where they don't match.)\n"
+ "\n"
+ "You can also set the applet parameter 'trustUrlVncCert=yes' to automatically\n"
+ "accept certificates already accepted/trusted by your Web Browser/Java Plugin,\n"
+ "and thereby see no dialog from this VNC Viewer applet.\n"
;

		/* the accept / do-not-accept radio buttons: */
		CheckboxGroup checkbox = new CheckboxGroup();
		accept = new Checkbox(s1, true, checkbox);
		deny   = new Checkbox(s2, false, checkbox);

		/* put the checkboxes in a panel: */
		Panel check = new Panel();
		check.setLayout(new GridLayout(2, 1));

		check.add(accept);
		check.add(deny);

		/* make the 3 buttons: */
		ok = new Button("OK");
		cancel = new Button("Cancel");
		viewcert = new Button("View Certificate");

		ok.addActionListener(this);
		cancel.addActionListener(this);
		viewcert.addActionListener(this);

		/* put the buttons in their own panel: */
		Panel buttonrow = new Panel();
		buttonrow.setLayout(new FlowLayout(FlowLayout.LEFT));
		buttonrow.add(viewcert);
		buttonrow.add(ok);
		buttonrow.add(cancel);

		/* label at the top: */
		Label label = new Label(msg, Label.CENTER);
		label.setFont(new Font("Helvetica", Font.BOLD, 16));

		/* textarea in the middle */
		textarea = new TextArea(text, 38, 64,
		    TextArea.SCROLLBARS_VERTICAL_ONLY);
		textarea.setEditable(false);

		/* put the two panels in their own panel at bottom: */
		Panel bot = new Panel();
		bot.setLayout(new GridLayout(2, 1));
		bot.add(check);
		bot.add(buttonrow);

		/* now arrange things inside the dialog: */
		dialog.setLayout(new BorderLayout());

		dialog.add("North", label);
		dialog.add("South", bot);
		dialog.add("Center", textarea);

		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til OK or Cancel pressed. */

		return trust_this_session;
	}

	public synchronized void actionPerformed(ActionEvent evt) {

		if (evt.getSource() == viewcert) {
			/* View Certificate button clicked */
			if (viewing_cert) {
				/* show the original info text: */
				textarea.setText(text);
				viewcert.setLabel("View Certificate");
				viewing_cert = false;
			} else {
				int i;
				/* show all (likely just one) certs: */
				textarea.setText("");
				for (i=0; i < trustallCerts.length; i++) {
					int j = i + 1;
					textarea.append("Certificate[" +
					    j + "]\n\n");
					textarea.append(
					    trustallCerts[i].toString());
					textarea.append(ln);
				}
				viewcert.setLabel("View Info");
				viewing_cert = true;

				textarea.setCaretPosition(0);
			}

		} else if (evt.getSource() == ok) {
			/* OK button clicked */
			if (accept.getState()) {
				trust_this_session = true;
			} else {
				trust_this_session = false;
			}
			//dialog.dispose();
			dialog.hide();

		} else if (evt.getSource() == cancel) {
			/* Cancel button clicked */
			trust_this_session = false;

			//dialog.dispose();
			dialog.hide();
		}
	}

	String get_certinfo() {
		String all = "";
		String fields[] = {"CN", "OU", "O", "L", "C"};
		int i;
		if (trustallCerts.length < 1) {
			all = "";
			return all;
		}
		String cert = trustallCerts[0].toString();

		/*
		 * For now we simply scrape the cert string, there must
		 * be an API for this... perhaps optionValue?
		 */

		for (i=0; i < fields.length; i++) {
			int f, t, t1, t2;
			String sub, mat = fields[i] + "=";
			
			f = cert.indexOf(mat, 0);
			if (f > 0) {
				t1 = cert.indexOf(", ", f);
				t2 = cert.indexOf("\n", f);
				if (t1 < 0 && t2 < 0) {
					continue;
				} else if (t1 < 0) {
					t = t2;
				} else if (t2 < 0) {
					t = t1;
				} else if (t1 < t2) {
					t = t1;
				} else {
					t = t2;
				}
				if (t > f) {
					sub = cert.substring(f, t);
					all = all + "        " + sub + "\n";
				}
			}
		}
		return all;
	}
}

class ProxyDialog implements ActionListener {
	String guessedHost = null;
	String guessedPort = null;
	/*
	 * this is the gui to show the user the cert and info and ask
	 * them if they want to continue using this cert.
	 */

	Button ok;
	Dialog dialog;
	TextField entry;
	String reply = "";

	ProxyDialog (String h, int p) {
		guessedHost = h;
		try {
			guessedPort = Integer.toString(p);
		} catch (Exception e) {
			guessedPort = "8080";
		}
	}

	public void queryUser() {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame("Need Proxy host:port");

		dialog = new Dialog(frame, true);


		Label label = new Label("Please Enter your https Proxy info as host:port", Label.CENTER);
		//label.setFont(new Font("Helvetica", Font.BOLD, 16));
		entry = new TextField(30);
		ok = new Button("OK");
		ok.addActionListener(this);

		String guess = "";
		if (guessedHost != null) {
			guess = guessedHost + ":" + guessedPort;
		}
		entry.setText(guess);

		dialog.setLayout(new BorderLayout());
		dialog.add("North", label);
		dialog.add("Center", entry);
		dialog.add("South", ok);
		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til OK or Cancel pressed. */
		return;
	}

	public String getHost() {
		int i = reply.indexOf(":");
		if (i < 0) {
			return "unknown";
		}
		String h = reply.substring(0, i);
		return h;
	}

	public int getPort() {
		int i = reply.indexOf(":");
		int p = 8080;
		if (i < 0) {
			return p;
		}
		i++;
		String ps = reply.substring(i);
		try {
			Integer I = new Integer(ps);
			p = I.intValue();
		} catch (Exception e) {
			;
		}
		return p;
	}

	public synchronized void actionPerformed(ActionEvent evt) {
		System.out.println(evt.getActionCommand());
		if (evt.getSource() == ok) {
			reply = entry.getText();
			//dialog.dispose();
			dialog.hide();
		}
	}
}

class ProxyPasswdDialog implements ActionListener {
	String guessedHost = null;
	String guessedPort = null;
	String guessedUser = null;
	String guessedPasswd = null;
	String realm = null;
	/*
	 * this is the gui to show the user the cert and info and ask
	 * them if they want to continue using this cert.
	 */

	Button ok;
	Dialog dialog;
	TextField entry1;
	TextField entry2;
	String reply1 = "";
	String reply2 = "";

	ProxyPasswdDialog (String h, int p, String realm) {
		guessedHost = h;
		try {
			guessedPort = Integer.toString(p);
		} catch (Exception e) {
			guessedPort = "8080";
		}
		this.realm = realm;
	}

	public void queryUser() {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame("Proxy Requires Username and Password");

		dialog = new Dialog(frame, true);

		//Label label = new Label("Please Enter your Web Proxy Username in the top Entry and Password in the bottom Entry", Label.CENTER);
		TextArea label = new TextArea("Please Enter your Web Proxy\nUsername in the Top Entry and\nPassword in the Bottom Entry,\nand then press OK.", 4, 20, TextArea.SCROLLBARS_NONE);
		entry1 = new TextField(30);
		entry2 = new TextField(30);
		entry2.setEchoChar('*');
		ok = new Button("OK");
		ok.addActionListener(this);

		dialog.setLayout(new BorderLayout());
		dialog.add("North", label);
		dialog.add("Center", entry1);
		dialog.add("South",  entry2);
		dialog.add("East", ok);
		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til OK or Cancel pressed. */
		return;
	}

	public String getAuth() {
		return reply1 + ":" + reply2;
	}

	public synchronized void actionPerformed(ActionEvent evt) {
		System.out.println(evt.getActionCommand());
		if (evt.getSource() == ok) {
			reply1 = entry1.getText();
			reply2 = entry2.getText();
			//dialog.dispose();
			dialog.hide();
		}
	}
}

class ClientCertDialog implements ActionListener {

	Button ok;
	Dialog dialog;
	TextField entry;
	String reply = "";

	ClientCertDialog() {
		;
	}

	public String queryUser() {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame("Enter SSL Client Cert+Key String");

		dialog = new Dialog(frame, true);


		Label label = new Label("Please Enter the SSL Client Cert+Key String 308204c0...,...522d2d0a", Label.CENTER);
		entry = new TextField(30);
		ok = new Button("OK");
		ok.addActionListener(this);

		dialog.setLayout(new BorderLayout());
		dialog.add("North", label);
		dialog.add("Center", entry);
		dialog.add("South", ok);
		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til OK or Cancel pressed. */
		return reply;
	}

	public synchronized void actionPerformed(ActionEvent evt) {
		System.out.println(evt.getActionCommand());
		if (evt.getSource() == ok) {
			reply = entry.getText();
			//dialog.dispose();
			dialog.hide();
		}
	}
}

class BrowserCertsDialog implements ActionListener {
	Button yes, no;
	Dialog dialog;
	String vncServer;
	String hostport;
	public boolean showCertDialog = true;

	BrowserCertsDialog(String serv, String hp) {
		vncServer = serv;
		hostport = hp;
	}

	public void queryUser() {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame("Use Browser/JVM Certs?");

		dialog = new Dialog(frame, true);

		String m = "";
m += "\n";
m += "This VNC Viewer applet does not have its own keystore to track\n";
m += "SSL certificates, and so cannot authenticate the certificate\n";
m += "of the VNC Server:\n";
m += "\n";
m += "        " + hostport + "\n\n        " + vncServer + "\n";
m += "\n";
m += "on its own.\n";
m += "\n";
m += "However, it has noticed that your Web Browser and/or Java VM Plugin\n";
m += "has previously accepted the same certificate.  You may have set\n";
m += "this up permanently or just for this session, or the server\n";
m += "certificate was signed by a CA cert that your Web Browser or\n";
m += "Java VM Plugin has.\n";
m += "\n";
m += "If the VNC Server connection times out while you are reading this\n";
m += "dialog, then restart the connection and try again.\n";
m += "\n";
m += "Should this VNC Viewer applet now connect to the above VNC server?\n";
m += "\n";

		TextArea textarea = new TextArea(m, 22, 64,
		    TextArea.SCROLLBARS_VERTICAL_ONLY);
		textarea.setEditable(false);
		yes = new Button("Yes");
		yes.addActionListener(this);
		no = new Button("No, Let Me See the Certificate.");
		no.addActionListener(this);

		dialog.setLayout(new BorderLayout());
		dialog.add("North", textarea);
		dialog.add("Center", yes);
		dialog.add("South", no);
		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til Yes or No pressed. */
		System.out.println("done show()");
		return;
	}

	public synchronized void actionPerformed(ActionEvent evt) {
		System.out.println(evt.getActionCommand());
		if (evt.getSource() == yes) {
			showCertDialog = false;
			//dialog.dispose();
			dialog.hide();
		} else if (evt.getSource() == no) {
			showCertDialog = true;
			//dialog.dispose();
			dialog.hide();
		}
		System.out.println("done actionPerformed()");
	}
}

class CertInfo {
	String fields[] = {"CN", "OU", "O", "L", "C"};
	java.security.cert.Certificate cert;
	String certString = "";

	CertInfo(java.security.cert.Certificate c) {
		cert = c;
		certString = cert.toString();
	}
	
	String get_certinfo(String which) {
		int i;
		String cs = new String(certString);
		String all = "";

		/*
		 * For now we simply scrape the cert string, there must
		 * be an API for this... perhaps optionValue?
		 */
		for (i=0; i < fields.length; i++) {
			int f, t, t1, t2;
			String sub, mat = fields[i] + "=";
			
			f = cs.indexOf(mat, 0);
			if (f > 0) {
				t1 = cs.indexOf(", ", f);
				t2 = cs.indexOf("\n", f);
				if (t1 < 0 && t2 < 0) {
					continue;
				} else if (t1 < 0) {
					t = t2;
				} else if (t2 < 0) {
					t = t1;
				} else if (t1 < t2) {
					t = t1;
				} else {
					t = t2;
				}
				if (t > f) {
					sub = cs.substring(f, t);
					all = all + "        " + sub + "\n";
					if (which.equals(fields[i])) {
						return sub;
					}
				}
			}
		}
		if (which.equals("all")) {
			return all;
		} else {
			return "";
		}
	}
}

class Base64Coder {

	// Mapping table from 6-bit nibbles to Base64 characters.
	private static char[]    map1 = new char[64];
	   static {
	      int i=0;
	      for (char c='A'; c<='Z'; c++) map1[i++] = c;
	      for (char c='a'; c<='z'; c++) map1[i++] = c;
	      for (char c='0'; c<='9'; c++) map1[i++] = c;
	      map1[i++] = '+'; map1[i++] = '/'; }

	// Mapping table from Base64 characters to 6-bit nibbles.
	private static byte[]    map2 = new byte[128];
	   static {
	      for (int i=0; i<map2.length; i++) map2[i] = -1;
	      for (int i=0; i<64; i++) map2[map1[i]] = (byte)i; }

	/**
	* Encodes a string into Base64 format.
	* No blanks or line breaks are inserted.
	* @param s  a String to be encoded.
	* @return   A String with the Base64 encoded data.
	*/
	public static String encodeString (String s) {
	   return new String(encode(s.getBytes())); }

	/**
	* Encodes a byte array into Base64 format.
	* No blanks or line breaks are inserted.
	* @param in  an array containing the data bytes to be encoded.
	* @return    A character array with the Base64 encoded data.
	*/
	public static char[] encode (byte[] in) {
	   return encode(in,in.length); }

	/**
	* Encodes a byte array into Base64 format.
	* No blanks or line breaks are inserted.
	* @param in   an array containing the data bytes to be encoded.
	* @param iLen number of bytes to process in <code>in</code>.
	* @return     A character array with the Base64 encoded data.
	*/
	public static char[] encode (byte[] in, int iLen) {
	   int oDataLen = (iLen*4+2)/3;       // output length without padding
	   int oLen = ((iLen+2)/3)*4;         // output length including padding
	   char[] out = new char[oLen];
	   int ip = 0;
	   int op = 0;
	   while (ip < iLen) {
	      int i0 = in[ip++] & 0xff;
	      int i1 = ip < iLen ? in[ip++] & 0xff : 0;
	      int i2 = ip < iLen ? in[ip++] & 0xff : 0;
	      int o0 = i0 >>> 2;
	      int o1 = ((i0 &   3) << 4) | (i1 >>> 4);
	      int o2 = ((i1 & 0xf) << 2) | (i2 >>> 6);
	      int o3 = i2 & 0x3F;
	      out[op++] = map1[o0];
	      out[op++] = map1[o1];
	      out[op] = op < oDataLen ? map1[o2] : '='; op++;
	      out[op] = op < oDataLen ? map1[o3] : '='; op++; }
	   return out; }

	/**
	* Decodes a string from Base64 format.
	* @param s  a Base64 String to be decoded.
	* @return   A String containing the decoded data.
	* @throws   IllegalArgumentException if the input is not valid Base64 encoded data.
	*/
	public static String decodeString (String s) {
	   return new String(decode(s)); }

	/**
	* Decodes a byte array from Base64 format.
	* @param s  a Base64 String to be decoded.
	* @return   An array containing the decoded data bytes.
	* @throws   IllegalArgumentException if the input is not valid Base64 encoded data.
	*/
	public static byte[] decode (String s) {
	   return decode(s.toCharArray()); }

	/**
	* Decodes a byte array from Base64 format.
	* No blanks or line breaks are allowed within the Base64 encoded data.
	* @param in  a character array containing the Base64 encoded data.
	* @return    An array containing the decoded data bytes.
	* @throws    IllegalArgumentException if the input is not valid Base64 encoded data.
	*/
	public static byte[] decode (char[] in) {
	   int iLen = in.length;
	   if (iLen%4 != 0) throw new IllegalArgumentException ("Length of Base64 encoded input string is not a multiple of 4.");
	   while (iLen > 0 && in[iLen-1] == '=') iLen--;
	   int oLen = (iLen*3) / 4;
	   byte[] out = new byte[oLen];
	   int ip = 0;
	   int op = 0;
	   while (ip < iLen) {
	      int i0 = in[ip++];
	      int i1 = in[ip++];
	      int i2 = ip < iLen ? in[ip++] : 'A';
	      int i3 = ip < iLen ? in[ip++] : 'A';
	      if (i0 > 127 || i1 > 127 || i2 > 127 || i3 > 127)
		 throw new IllegalArgumentException ("Illegal character in Base64 encoded data.");
	      int b0 = map2[i0];
	      int b1 = map2[i1];
	      int b2 = map2[i2];
	      int b3 = map2[i3];
	      if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0)
		 throw new IllegalArgumentException ("Illegal character in Base64 encoded data.");
	      int o0 = ( b0       <<2) | (b1>>>4);
	      int o1 = ((b1 & 0xf)<<4) | (b2>>>2);
	      int o2 = ((b2 &   3)<<6) |  b3;
	      out[op++] = (byte)o0;
	      if (op<oLen) out[op++] = (byte)o1;
	      if (op<oLen) out[op++] = (byte)o2; }
	   return out; }

	// Dummy constructor.
	private Base64Coder() {}

}
