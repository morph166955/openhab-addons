/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.androidtv.internal.protocol.googletv;

import static org.openhab.binding.androidtv.internal.AndroidTVBindingConstants.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.androidtv.internal.GoogleTVHandler;
import org.openhab.binding.androidtv.internal.utils.AndroidTVPKI;
import org.openhab.core.OpenHAB;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link GoogleTVConnectionManager} is responsible for handling connections via the googletv protocol
 *
 * Significant portions reused from Lutron binding with permission from Bob A.
 *
 * @author Ben Rosenblum - Initial contribution
 */
@NonNullByDefault
public class GoogleTVConnectionManager {
    private static final int DEFAULT_RECONNECT_SECONDS = 60;
    private static final int DEFAULT_HEARTBEAT_SECONDS = 5;
    private static final long KEEPALIVE_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_KEYSTORE_PASSWORD = "secret";
    private static final String DEFAULT_MODE = "NORMAL";
    private static final String PIN_MODE = "PIN";
    private static final int DEFAULT_PORT = 6466;

    private final Logger logger = LoggerFactory.getLogger(GoogleTVConnectionManager.class);

    private ScheduledExecutorService scheduler;

    private final GoogleTVHandler handler;
    private GoogleTVConfiguration config;

    private @NonNullByDefault({}) SSLSocketFactory sslSocketFactory;
    private @Nullable SSLSocket sslSocket;
    private @Nullable BufferedWriter writer;
    private @Nullable BufferedReader reader;

    private @NonNullByDefault({}) SSLServerSocketFactory sslServerSocketFactory;
    private @Nullable Socket shimServerSocket;
    private @Nullable BufferedWriter shimWriter;
    private @Nullable BufferedReader shimReader;

    private @Nullable GoogleTVConnectionManager connectionManager;
    private @Nullable GoogleTVConnectionManager childConnectionManager;
    private @NonNullByDefault({}) GoogleTVMessageParser messageParser;

    private final BlockingQueue<GoogleTVCommand> sendQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<GoogleTVCommand> shimQueue = new LinkedBlockingQueue<>();

    private @Nullable Future<?> asyncInitializeTask;
    private @Nullable Future<?> shimAsyncInitializeTask;

    private @Nullable Thread senderThread;
    private @Nullable Thread readerThread;
    private @Nullable Thread shimSenderThread;
    private @Nullable Thread shimReaderThread;

    private @Nullable ScheduledFuture<?> keepAliveJob;
    private @Nullable ScheduledFuture<?> keepAliveReconnectJob;
    private @Nullable ScheduledFuture<?> connectRetryJob;
    private final Object keepAliveReconnectLock = new Object();
    private int periodicUpdate;

    private StringBuffer sbReader = new StringBuffer();
    private StringBuffer sbShimReader = new StringBuffer();
    private String thisMsg = "";

    private X509Certificate @Nullable [] shimX509ClientChain;
    private Certificate @Nullable [] shimClientChain;
    private Certificate @Nullable [] shimServerChain;
    private Certificate @Nullable [] shimClientLocalChain;

    private boolean disposing = false;
    private boolean isLoggedIn = false;
    private String statusMessage = "";
    private String pinHash = "";
    private String shimPinHash = "";

    private boolean power = false;
    private String volCurr = "00";
    private String volMax = "ff";
    private boolean volMute = false;
    private String audioMode = "";
    private String hostName = "";
    private String currentApp = "";
    private String deviceId = "";
    private String arch = "";
    private String manufacturer = "";
    private String model = "";
    private String androidVersion = "";
    private String remoteServer = "";
    private String remoteServerVersion = "";

    private AndroidTVPKI androidtvPKI = new AndroidTVPKI();
    private byte[] encryptionKey;

    private Map<String, String> appNameDB = new HashMap<>();
    private Map<String, String> appURLDB = new HashMap<>();

    public GoogleTVConnectionManager(GoogleTVHandler handler, GoogleTVConfiguration config) {
        messageParser = new GoogleTVMessageParser(this);
        this.config = config;
        this.handler = handler;
        this.connectionManager = this;
        this.scheduler = handler.getScheduler();
        this.encryptionKey = androidtvPKI.generateEncryptionKey();
        initalize();
    }

    public GoogleTVConnectionManager(GoogleTVHandler handler, GoogleTVConfiguration config,
            GoogleTVConnectionManager connectionManager) {
        messageParser = new GoogleTVMessageParser(this);
        this.config = config;
        this.handler = handler;
        this.connectionManager = connectionManager;
        this.scheduler = handler.getScheduler();
        this.encryptionKey = androidtvPKI.generateEncryptionKey();
        initalize();
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
        handler.setThingProperty("Device Name", hostName);
    }

    public String getHostName() {
        return this.hostName;
    }

    public void setDeviceID(String deviceId) {
        this.deviceId = deviceId;
        handler.setThingProperty("Device ID", deviceId);
    }

    public String getDeviceID() {
        return this.deviceId;
    }

    public void setArch(String arch) {
        this.arch = arch;
        handler.setThingProperty("Architectures", arch);
    }

    public String getArch() {
        return this.arch;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
        handler.setThingProperty("Manufacturer", manufacturer);
    }

    public String getManufacturer() {
        return this.manufacturer;
    }

    public void setModel(String model) {
        this.model = model;
        handler.setThingProperty("Model", model);
    }

    public String getModel() {
        return this.model;
    }

    public void setAndroidVersion(String androidVersion) {
        this.androidVersion = androidVersion;
        handler.setThingProperty("Android Version", androidVersion);
    }

    public String getAndroidVersion() {
        return this.androidVersion;
    }

    public void setRemoteServer(String remoteServer) {
        this.remoteServer = remoteServer;
        handler.setThingProperty("Remote Server", remoteServer);
    }

    public String getRemoteServer() {
        return this.remoteServer;
    }

    public void setRemoteServerVersion(String remoteServerVersion) {
        this.remoteServerVersion = remoteServerVersion;
        handler.setThingProperty("Remote Server Version", remoteServerVersion);
    }

    public String getRemoteServerVersion() {
        return this.remoteServerVersion;
    }

    public void setPower(boolean power) {
        this.power = power;
        logger.trace("Setting power to {}", power);
        if (power) {
            handler.updateChannelState(CHANNEL_POWER, OnOffType.ON);
        } else {
            handler.updateChannelState(CHANNEL_POWER, OnOffType.OFF);
        }
    }

    public boolean getPower() {
        return this.power;
    }

    public void setVolCurr(String volCurr) {
        this.volCurr = volCurr;
        int max = Integer.parseInt(this.volMax, 16);
        int volume = ((Integer.parseInt(volCurr, 16) * 100) / max);
        handler.updateChannelState(CHANNEL_VOLUME, new PercentType(volume));
    }

    public String getVolCurr() {
        return this.volCurr;
    }

    public void setVolMax(String volMax) {
        this.volMax = volMax;
    }

    public String getVolMax() {
        return this.volMax;
    }

    public void setVolMute(String volMute) {
        if (volMute.equals("00")) {
            this.volMute = false;
            handler.updateChannelState(CHANNEL_MUTE, OnOffType.OFF);
        } else if (volMute.equals("01")) {
            this.volMute = true;
            handler.updateChannelState(CHANNEL_MUTE, OnOffType.ON);
        }
    }

    public boolean getVolMute() {
        return this.volMute;
    }

    public void setAudioMode(String audioMode) {
        this.audioMode = audioMode;
    }

    public String getAudioMode() {
        return this.audioMode;
    }

    public void setCurrentApp(String currentApp) {
        this.currentApp = currentApp;
        handler.updateChannelState(CHANNEL_APP, new StringType(currentApp));

        String appName = "";
        String appURL = "";

        try {
            appName = appNameDB.get(currentApp);
            appURL = appURLDB.get(currentApp);
        } catch (NullPointerException e) {
            logger.debug("Null Pointer Exception", e);
            logger.info("Unknown Android App: {}", currentApp);
        } finally {
            handler.updateChannelState(CHANNEL_APPNAME, new StringType(appName));
            handler.updateChannelState(CHANNEL_APPURL, new StringType(appURL));
        }
    }

    public String getStatusMessage() {
        return this.statusMessage;
    }

    private void setStatus(boolean isLoggedIn) {
        if (isLoggedIn) {
            setStatus(isLoggedIn, "ONLINE");
        } else {
            setStatus(isLoggedIn, "UNKNOWN");
        }
    }

    private void setStatus(boolean isLoggedIn, String statusMessage) {
        if ((this.isLoggedIn != isLoggedIn) || (!this.statusMessage.equals(statusMessage))) {
            this.isLoggedIn = isLoggedIn;
            this.statusMessage = statusMessage;
            handler.checkThingStatus();
        }
    }

    public String getCurrentApp() {
        return this.currentApp;
    }

    private void sendPeriodicUpdate() {
        // sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage("080b120308cd08"))); // Get Hostname
        // sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage("08f30712020805"))); // No Reply
        // sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage("08f10712020800"))); // Get App DB
        // sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage("08ec0712020806"))); // Get App
    }

    public void setLoggedIn(boolean isLoggedIn) {
        if (!this.isLoggedIn && isLoggedIn) {
            sendPeriodicUpdate();
        }

        if (this.isLoggedIn != isLoggedIn) {
            setStatus(isLoggedIn);
        }
    }

    public boolean getLoggedIn() {
        return this.isLoggedIn;
    }

    public void setKeys(String privKey, String cert) {
        try {
            androidtvPKI.setKeys(privKey, encryptionKey, cert);
            androidtvPKI.saveKeyStore(config.keystorePassword, encryptionKey);
        } catch (GeneralSecurityException e) {
            logger.debug("General security exception", e);
        } catch (IOException e) {
            logger.debug("IO Exception", e);
        } catch (Exception e) {
            logger.debug("General Exception", e);

        }
    }

    public void setAppDB(Map<String, String> appNameDB, Map<String, String> appURLDB) {
        this.appNameDB = appNameDB;
        this.appURLDB = appURLDB;
        logger.debug("App DB Populated");
        logger.trace("Handler appNameDB: {} appURLDB: {}", this.appNameDB.toString(), this.appURLDB.toString());
        handler.updateCDP(CHANNEL_APP, this.appNameDB);
    }

    private void setShimX509ClientChain(X509Certificate @Nullable [] shimX509ClientChain) {
        try {
            this.shimX509ClientChain = shimX509ClientChain;
            logger.trace("Setting shimX509ClientChain {}", this.config.port);
            if (shimX509ClientChain != null && logger.isTraceEnabled()) {
                for (int cert = 0; cert < shimX509ClientChain.length; cert++) {
                    logger.trace("Subject DN: {}", shimX509ClientChain[cert].getSubjectX500Principal());
                    logger.trace("Issuer DN: {}", shimX509ClientChain[cert].getIssuerX500Principal());
                    logger.trace("Serial number: {}", shimX509ClientChain[cert].getSerialNumber());
                    logger.trace("Cert: {}", GoogleTVRequest
                            .decodeMessage(GoogleTVUtils.byteArrayToString(shimX509ClientChain[cert].getEncoded())));
                }
            }
        } catch (CertificateEncodingException e) {
            logger.trace("setShimX509ClientChain CertificateEncodingException", e);
        }
    }

    private void startChildConnectionManager(int port, String mode) {
        GoogleTVConfiguration childConfig = new GoogleTVConfiguration();
        childConfig.ipAddress = config.ipAddress;
        childConfig.port = port;
        childConfig.reconnect = config.reconnect;
        childConfig.heartbeat = config.heartbeat;
        childConfig.keystoreFileName = config.keystoreFileName;
        childConfig.keystorePassword = config.keystorePassword;
        childConfig.delay = config.delay;
        childConfig.shim = config.shim;
        childConfig.mode = mode;
        logger.trace("startChildConnectionManager parent config: {} {} {}", config.port, config.mode, config.shim);
        logger.trace("startChildConnectionManager child config: {} {} {}", childConfig.port, childConfig.mode,
                childConfig.shim);
        childConnectionManager = new GoogleTVConnectionManager(this.handler, childConfig, this);
    }

    private void resetConnectionManager() {
        dispose();
        initalize();
    }

    private TrustManager[] defineNoOpTrustManager() {
        return new TrustManager[] { new X509TrustManager() {
            @Override
            public void checkClientTrusted(final X509Certificate @Nullable [] chain, final @Nullable String authType) {
                logger.debug("Assuming client certificate is valid");
                if (chain != null && logger.isTraceEnabled()) {
                    for (int cert = 0; cert < chain.length; cert++) {
                        logger.trace("Subject DN: {}", chain[cert].getSubjectX500Principal());
                        logger.trace("Issuer DN: {}", chain[cert].getIssuerX500Principal());
                        logger.trace("Serial number {}:", chain[cert].getSerialNumber());
                    }
                }
            }

            @Override
            public void checkServerTrusted(final X509Certificate @Nullable [] chain, final @Nullable String authType) {
                logger.debug("Assuming server certificate is valid");
                if (chain != null && logger.isTraceEnabled()) {
                    for (int cert = 0; cert < chain.length; cert++) {
                        logger.trace("Subject DN: {}", chain[cert].getSubjectX500Principal());
                        logger.trace("Issuer DN: {}", chain[cert].getIssuerX500Principal());
                        logger.trace("Serial number: {}", chain[cert].getSerialNumber());
                    }
                }
            }

            @Override
            public X509Certificate @Nullable [] getAcceptedIssuers() {
                if (shimX509ClientChain != null && logger.isTraceEnabled()) {
                    logger.debug("Returning shimX509ClientChain for getAcceptedIssuers");
                    for (int cert = 0; cert < shimX509ClientChain.length; cert++) {
                        logger.trace("Subject DN: {}", shimX509ClientChain[cert].getSubjectX500Principal());
                        logger.trace("Issuer DN: {}", shimX509ClientChain[cert].getIssuerX500Principal());
                        logger.trace("Serial number: {}", shimX509ClientChain[cert].getSerialNumber());
                    }

                    return shimX509ClientChain;
                } else {
                    logger.debug("Returning empty certificate for getAcceptedIssuers");
                    return new X509Certificate[0];
                }
            }
        } };
    }

    private void initalize() {
        SSLContext sslContext;

        String folderName = OpenHAB.getUserDataFolder() + "/androidtv";
        File folder = new File(folderName);

        if (!folder.exists()) {
            logger.debug("Creating directory {}", folderName);
            folder.mkdirs();
        }

        config.port = (config.port > 0) ? config.port : DEFAULT_PORT;
        config.reconnect = (config.reconnect > 0) ? config.reconnect : DEFAULT_RECONNECT_SECONDS;
        config.heartbeat = (config.heartbeat > 0) ? config.heartbeat : DEFAULT_HEARTBEAT_SECONDS;
        config.delay = (config.delay < 0) ? 0 : config.delay;
        config.shim = (config.shim) ? true : false;
        config.shimNewKeys = (config.shimNewKeys) ? true : false;
        config.mode = (!config.mode.equals("")) ? config.mode : DEFAULT_MODE;

        config.keystoreFileName = (!config.keystoreFileName.equals("")) ? config.keystoreFileName
                : folderName + "/googletv." + ((config.shim) ? "shim." : "") + handler.getThing().getUID().getId()
                        + ".keystore";
        config.keystorePassword = (!config.keystorePassword.equals("")) ? config.keystorePassword
                : DEFAULT_KEYSTORE_PASSWORD;

        androidtvPKI.setKeystoreFileName(config.keystoreFileName);
        androidtvPKI.setAlias("nvidia");

        try {
            File keystoreFile = new File(config.keystoreFileName);

            if (!keystoreFile.exists() || config.shimNewKeys) {
                androidtvPKI.generateNewKeyPair(encryptionKey);
                androidtvPKI.saveKeyStore(config.keystorePassword, this.encryptionKey);
            } else {
                androidtvPKI.loadFromKeyStore(config.keystorePassword, this.encryptionKey);
            }

            logger.trace("Initializing SSL Context");
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(androidtvPKI.getKeyStore(config.keystorePassword, this.encryptionKey),
                    config.keystorePassword.toCharArray());

            TrustManager[] trustManagers = defineNoOpTrustManager();

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), trustManagers, null);

            sslSocketFactory = sslContext.getSocketFactory();
            if (!config.shim) {
                asyncInitializeTask = scheduler.submit(this::connect);
            } else {
                shimAsyncInitializeTask = scheduler.submit(this::shimInitalize);
            }

        } catch (NoSuchAlgorithmException | IOException e) {
            setStatus(false, "Error initializing keystore");
            logger.debug("Error initializing keystore", e);
        } catch (UnrecoverableKeyException e) {
            setStatus(false, "Key unrecoverable with supplied password");
        } catch (GeneralSecurityException e) {
            logger.debug("General security exception", e);
        } catch (Exception e) {
            logger.debug("General exception", e);
        }
    }

    public synchronized void connect() {

        try {
            logger.debug("Opening GoogleTV SSL connection to {}:{}", config.ipAddress, config.port);
            SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(config.ipAddress, config.port);
            sslSocket.startHandshake();
            this.shimServerChain = ((SSLSocket) sslSocket).getSession().getPeerCertificates();
            writer = new BufferedWriter(
                    new OutputStreamWriter(sslSocket.getOutputStream(), StandardCharsets.ISO_8859_1));
            reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream(), StandardCharsets.ISO_8859_1));
            this.sslSocket = sslSocket;
        } catch (UnknownHostException e) {
            setStatus(false, "Unknown host");
            return;
        } catch (IllegalArgumentException e) {
            // port out of valid range
            setStatus(false, "Invalid port number");
            return;
        } catch (InterruptedIOException e) {
            logger.debug("Interrupted while establishing GoogleTV connection");
            Thread.currentThread().interrupt();
            return;
        } catch (IOException e) {
            if ((e.getMessage().contains("certificate_unknown")) && (!config.mode.equals(PIN_MODE)) && (!config.shim)) {
                setStatus(false, "PIN Process Incomplete");
                logger.debug("GoogleTV PIN Process Incomplete");
                reconnectTaskCancel(true);
                startChildConnectionManager(this.config.port + 1, PIN_MODE);
            } else if ((e.getMessage().contains("certificate_unknown")) && (config.shim)) {
                logger.debug("Shim cert_unknown I/O error while connecting: {}", e.getMessage());
                Socket shimServerSocket = this.shimServerSocket;
                if (shimServerSocket != null) {
                    try {
                        shimServerSocket.close();
                    } catch (IOException ex) {
                        logger.debug("Error closing GoogleTV SSL socket: {}", ex.getMessage());
                    }
                    this.shimServerSocket = null;
                }

            } else {
                setStatus(false, "Error opening GoogleTV SSL connection. Check log.");
                logger.info("Error opening GoogleTV SSL connection: {}", e.getMessage());
                disconnect(false);
                scheduleConnectRetry(config.reconnect); // Possibly a temporary problem. Try again later.
            }
            return;
        }

        setStatus(false, "Initializing");

        Thread readerThread = new Thread(this::readerThreadJob, "GoogleTV reader");
        readerThread.setDaemon(true);
        readerThread.start();
        this.readerThread = readerThread;

        Thread senderThread = new Thread(this::senderThreadJob, "GoogleTV sender");
        senderThread.setDaemon(true);
        senderThread.start();
        this.senderThread = senderThread;

        if ((!config.shim) && (config.mode.equals(DEFAULT_MODE))) {
            this.periodicUpdate = 20;
        } else if (config.mode.equals(PIN_MODE)) {
            // Send app name and device name
            sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage(GoogleTVRequest.loginRequest(1))));
            // Unknown but required
            sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage(GoogleTVRequest.loginRequest(2))));
            // Don't end pin request yet, let user send REQUEST via PINCODE channel
        }
    }

    public synchronized void shimInitalize() {

        AndroidTVPKI shimPKI = new AndroidTVPKI();
        byte[] shimEncryptionKey = shimPKI.generateEncryptionKey();
        SSLContext sslContext;

        try {
            shimPKI.generateNewKeyPair(shimEncryptionKey);
            // Move this to PKI. Shim requires a trusted cert chain in the keystore.
            KeyStore keystore = KeyStore.getInstance("JKS");
            FileInputStream keystoreInputStream = new FileInputStream(config.keystoreFileName);
            keystore.load(keystoreInputStream, config.keystorePassword.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keystore, config.keystorePassword.toCharArray());
            TrustManager[] trustManagers = defineNoOpTrustManager();

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), trustManagers, null);
            this.sslServerSocketFactory = sslContext.getServerSocketFactory();

            ServerSocketFactory factory = SSLServerSocketFactory.getDefault();

            logger.debug("Opening GoogleTV shim on port {}", config.port);
            SSLServerSocket sslServerSocket = (SSLServerSocket) this.sslServerSocketFactory
                    .createServerSocket(config.port);
            if (this.config.mode.equals(DEFAULT_MODE)) {
                sslServerSocket.setNeedClientAuth(true);
            } else {
                sslServerSocket.setWantClientAuth(true);
            }

            logger.trace("sslServerSocket Cipher {}", sslServerSocket.getEnabledCipherSuites());
            logger.trace("sslServerSocket Protocols {}", sslServerSocket.getEnabledProtocols());

            logger.trace("sslServerSocket Cipher {}", sslServerSocket.getSupportedCipherSuites());
            logger.trace("sslServerSocket Protocols {}", sslServerSocket.getSupportedProtocols());

            while (true) {
                logger.debug("Waiting for shim connection... {}", config.port);
                if (this.config.mode.equals(DEFAULT_MODE) && (childConnectionManager == null)) {
                    logger.debug("Starting childConnectionManager {}", config.port);
                    startChildConnectionManager(this.config.port + 1, PIN_MODE);
                }
                SSLSocket serverSocket = (SSLSocket) sslServerSocket.accept();
                logger.trace("shimInitalize accepted {}", config.port);
                try {
                    serverSocket.startHandshake();
                    logger.trace("shimInitalize startHandshake {}", config.port);
                    connect();
                    logger.trace("shimInitalize connected {}", config.port);

                    SSLSession session = serverSocket.getSession();
                    Certificate[] cchain2 = session.getPeerCertificates();
                    this.shimClientChain = cchain2;
                    Certificate[] cchain3 = session.getLocalCertificates();
                    this.shimClientLocalChain = cchain3;

                    X509Certificate[] shimX509ClientChain = new X509Certificate[cchain2.length];

                    if (cchain2 != null) {
                        for (int i = 0; i < cchain2.length; i++) {
                            logger.trace("Connection from: {}",
                                    ((X509Certificate) cchain2[i]).getSubjectX500Principal());
                            shimX509ClientChain[i] = ((X509Certificate) cchain2[i]);
                        }
                    }

                    if (this.config.mode.equals(PIN_MODE)) {
                        this.shimX509ClientChain = shimX509ClientChain;
                        connectionManager.setShimX509ClientChain(shimX509ClientChain);
                    }

                    if (cchain3 != null) {
                        for (int i = 0; i < cchain3.length; i++) {
                            logger.trace("Connection from: {}",
                                    ((X509Certificate) cchain3[i]).getSubjectX500Principal());
                        }
                    }

                    logger.trace("Peer host is {}", session.getPeerHost());
                    logger.trace("Cipher is {}", session.getCipherSuite());
                    logger.trace("Protocol is {}", session.getProtocol());
                    logger.trace("ID is {}", new BigInteger(session.getId()));
                    logger.trace("Session created in {}", session.getCreationTime());
                    logger.trace("Session accessed in {}", session.getLastAccessedTime());

                    shimWriter = new BufferedWriter(
                            new OutputStreamWriter(serverSocket.getOutputStream(), StandardCharsets.ISO_8859_1));
                    shimReader = new BufferedReader(
                            new InputStreamReader(serverSocket.getInputStream(), StandardCharsets.ISO_8859_1));
                    this.shimServerSocket = serverSocket;

                    Thread readerThread = new Thread(this::shimReaderThreadJob, "GoogleTV shim reader");
                    readerThread.setDaemon(true);
                    readerThread.start();
                    this.shimReaderThread = readerThread;

                    Thread senderThread = new Thread(this::shimSenderThreadJob, "GoogleTV shim sender");
                    senderThread.setDaemon(true);
                    senderThread.start();
                    this.shimSenderThread = senderThread;
                } catch (Exception e) {
                    logger.trace("Shim initalization exception {}", config.port);
                    logger.trace("Shim initalization exception", e);

                }
            }
        } catch (Exception e) {
            logger.trace("Shim initalization exception {}", config.port);
            logger.trace("Shim initalization exception", e);

            return;
        }
    }

    private void scheduleConnectRetry(long waitSeconds) {
        logger.debug("Scheduling GoogleTV connection retry in {} seconds", waitSeconds);
        connectRetryJob = scheduler.schedule(this::connect, waitSeconds, TimeUnit.SECONDS);
    }

    /**
     * Disconnect from bridge, cancel retry and keepalive jobs, stop reader and writer threads, and
     * clean up.
     *
     * @param interruptAll Set if reconnect task should be interrupted if running. Should be false when calling from
     *            connect or reconnect, and true when calling from dispose.
     */
    private synchronized void disconnect(boolean interruptAll) {
        logger.debug("Disconnecting GoogleTV");

        ScheduledFuture<?> connectRetryJob = this.connectRetryJob;
        if (connectRetryJob != null) {
            connectRetryJob.cancel(true);
        }
        ScheduledFuture<?> keepAliveJob = this.keepAliveJob;
        if (keepAliveJob != null) {
            keepAliveJob.cancel(true);
        }

        Thread senderThread = this.senderThread;
        if (senderThread != null && senderThread.isAlive()) {
            senderThread.interrupt();
        }

        Thread readerThread = this.readerThread;
        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
        }

        Thread shimSenderThread = this.shimSenderThread;
        if (shimSenderThread != null && shimSenderThread.isAlive()) {
            shimSenderThread.interrupt();
        }

        Thread shimReaderThread = this.shimReaderThread;
        if (shimReaderThread != null && shimReaderThread.isAlive()) {
            shimReaderThread.interrupt();
        }

        SSLSocket sslSocket = this.sslSocket;
        if (sslSocket != null) {
            try {
                sslSocket.close();
            } catch (IOException e) {
                logger.debug("Error closing GoogleTV SSL socket: {}", e.getMessage());
            }
            this.sslSocket = null;
        }
        BufferedReader reader = this.reader;
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                logger.debug("Error closing reader: {}", e.getMessage());
            }
        }
        BufferedWriter writer = this.writer;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                logger.debug("Error closing writer: {}", e.getMessage());
            }
        }

        Socket shimServerSocket = this.shimServerSocket;
        if (shimServerSocket != null) {
            try {
                shimServerSocket.close();
            } catch (IOException e) {
                logger.debug("Error closing GoogleTV SSL socket: {}", e.getMessage());
            }
            this.shimServerSocket = null;
        }
        BufferedReader shimReader = this.shimReader;
        if (shimReader != null) {
            try {
                shimReader.close();
            } catch (IOException e) {
                logger.debug("Error closing shimReader: {}", e.getMessage());
            }
        }
        BufferedWriter shimWriter = this.shimWriter;
        if (shimWriter != null) {
            try {
                shimWriter.close();
            } catch (IOException e) {
                logger.debug("Error closing shimWriter: {}", e.getMessage());
            }
        }
    }

    private synchronized void reconnect() {
        if (!this.disposing) {
            logger.debug("Attempting to reconnect to the GoogleTV");
            setStatus(false, "reconnecting");
            disconnect(false);
            connect();
        }
    }

    /**
     * Method executed by the message sender thread (senderThread)
     */
    private void senderThreadJob() {
        logger.debug("Command sender thread started");
        try {
            while (!Thread.currentThread().isInterrupted() && writer != null) {
                GoogleTVCommand command = sendQueue.take();

                try {
                    BufferedWriter writer = this.writer;
                    if (writer != null) {
                        logger.trace("Raw GoogleTV command decodes as: {}",
                                GoogleTVRequest.decodeMessage(command.toString()));
                        writer.write(command.toString());
                        writer.flush();
                    }
                } catch (InterruptedIOException e) {
                    logger.debug("Interrupted while sending to GoogleTV");
                    setStatus(false, "Interrupted");
                    break; // exit loop and terminate thread
                } catch (IOException e) {
                    logger.warn("Communication error, will try to reconnect GoogleTV. Error: {}", e.getMessage());
                    setStatus(false, "Communication error, will try to reconnect");
                    sendQueue.add(command); // Requeue command
                    reconnect();
                    break; // reconnect() will start a new thread; terminate this one
                }
                if (config.delay > 0) {
                    Thread.sleep(config.delay); // introduce delay to throttle send rate
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            logger.debug("Command sender thread exiting");
        }
    }

    private void shimSenderThreadJob() {
        logger.debug("Shim sender thread started");
        try {
            while (!Thread.currentThread().isInterrupted() && shimWriter != null) {
                GoogleTVCommand command = shimQueue.take();

                try {
                    BufferedWriter writer = this.shimWriter;
                    if (writer != null) {
                        logger.trace("Shim received from google: {}",
                                GoogleTVRequest.decodeMessage(command.toString()));
                        writer.write(command.toString());
                        writer.flush();
                    }
                } catch (InterruptedIOException e) {
                    logger.debug("Shim interrupted while sending.");
                    break; // exit loop and terminate thread
                } catch (IOException e) {
                    logger.warn("Shim communication error. Error: {}", e.getMessage());
                    break; // reconnect() will start a new thread; terminate this one
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            logger.debug("Command sender thread exiting");
        }
    }

    /**
     * Method executed by the message reader thread (readerThread)
     */
    private void readerThreadJob() {
        logger.debug("Message reader thread started {}", config.port);
        try {
            BufferedReader reader = this.reader;
            int length = 0;
            int current = 0;
            while (!Thread.interrupted() && reader != null) {
                thisMsg = GoogleTVRequest.fixMessage(Integer.toHexString(reader.read()));
                if (thisMsg.equals("ffffffff")) {
                    // Google has crashed the connection. Disconnect hard.
                    logger.trace("readerThreadJob received ffffffff.  Disconnecting hard.");
                    reconnect();
                    break;
                }
                if (length == 0) {
                    length = Integer.parseInt(thisMsg.toString(), 16);
                    logger.trace("readerThreadJob message length {}", length);
                    current = 0;
                    sbReader = new StringBuffer();
                    sbReader.append(thisMsg.toString());
                } else {
                    sbReader.append(thisMsg.toString());
                    current += 1;
                }

                if ((length > 0) && (current == length)) {
                    logger.trace("GoogleTV Message: {} {}", length, sbReader.toString());
                    messageParser.handleMessage(sbReader.toString());
                    if (config.shim) {
                        String thisCommand = interceptMessages(sbReader.toString());
                        shimQueue.add(new GoogleTVCommand(GoogleTVRequest.encodeMessage(thisCommand)));
                    }
                    length = 0;
                }
            }
        } catch (InterruptedIOException e) {
            logger.debug("Interrupted while reading");
            setStatus(false, "Interrupted");
        } catch (IOException e) {
            if ((e.getMessage().contains("certificate_unknown")) && (!config.mode.equals(PIN_MODE)) && (!config.shim)) {
                setStatus(false, "PIN Process Incomplete");
                logger.debug("GoogleTV PIN Process Incomplete");
                reconnectTaskCancel(true);
                startChildConnectionManager(this.config.port + 1, PIN_MODE);
            } else if ((e.getMessage().contains("certificate_unknown")) && (config.shim)) {
                logger.debug("Shim cert_unknown I/O error while reading from stream: {}", e.getMessage());
                Socket shimServerSocket = this.shimServerSocket;
                if (shimServerSocket != null) {
                    try {
                        shimServerSocket.close();
                    } catch (IOException ex) {
                        logger.debug("Error closing GoogleTV SSL socket: {}", ex.getMessage());
                    }
                    this.shimServerSocket = null;
                }

            } else {
                logger.debug("I/O error while reading from stream: {}", e.getMessage());
                setStatus(false, "I/O Error");
            }
        } catch (RuntimeException e) {
            logger.warn("Runtime exception in reader thread", e);
            setStatus(false, "Runtime exception");
        } finally {
            logger.debug("Message reader thread exiting port {}", config.port);
        }
    }

    private String interceptMessages(String message) {
        if (message.startsWith("080210c801c202", 2)) {
            // intercept PIN hash and replace with valid shim hash
            int length = this.pinHash.length() / 2;
            String len1 = GoogleTVRequest.fixMessage(Integer.toHexString(length + 2));
            String len2 = GoogleTVRequest.fixMessage(Integer.toHexString(length));
            String reply = "080210c801c202" + len1 + "0a" + len2 + this.pinHash;
            String replyLength = GoogleTVRequest.fixMessage(Integer.toHexString(reply.length() / 2));
            String finalReply = replyLength + reply;
            logger.trace("Message Intercepted: {}", message);
            logger.trace("Message chagnged to: {}", finalReply);
            return finalReply;
        } else if (message.startsWith("080210c801ca02", 2)) {
            // intercept PIN hash and replace with valid shim hash
            int length = this.shimPinHash.length() / 2;
            String len1 = GoogleTVRequest.fixMessage(Integer.toHexString(length + 2));
            String len2 = GoogleTVRequest.fixMessage(Integer.toHexString(length));
            String reply = "080210c801ca02" + len1 + "0a" + len2 + this.shimPinHash;
            String replyLength = GoogleTVRequest.fixMessage(Integer.toHexString(reply.length() / 2));
            String finalReply = replyLength + reply;
            logger.trace("Message Intercepted: {}", message);
            logger.trace("Message chagnged to: {}", finalReply);
            return finalReply;
        } else {
            // don't intercept message
            return message;
        }
    }

    private void shimReaderThreadJob() {
        logger.debug("Shim reader thread started {}", config.port);
        try {
            BufferedReader reader = this.shimReader;
            String thisShimMsg = "";
            int length = 0;
            int current = 0;
            while (!Thread.interrupted() && reader != null) {
                thisShimMsg = GoogleTVRequest.fixMessage(Integer.toHexString(reader.read()));
                if (thisShimMsg.equals("ffffffff")) {
                    // Google has crashed the connection. Disconnect hard.
                    disconnect(true);
                    break;
                }
                if (length == 0) {
                    length = Integer.parseInt(thisShimMsg.toString(), 16);
                    logger.trace("shimReaderThreadJob message length {}", length);
                    current = 0;
                    sbShimReader = new StringBuffer();
                    sbShimReader.append(thisShimMsg.toString());
                } else {
                    sbShimReader.append(thisShimMsg.toString());
                    current += 1;
                }
                if ((length > 0) && (current == length)) {
                    logger.trace("Shim GoogleTV Message: {} {}", length, sbShimReader.toString());
                    String thisCommand = interceptMessages(sbShimReader.toString());
                    sendQueue.add(new GoogleTVCommand(GoogleTVRequest.encodeMessage(thisCommand)));
                    length = 0;
                }
            }

        } catch (InterruptedIOException e) {
            logger.debug("Interrupted while reading");
            setStatus(false, "Interrupted");
        } catch (IOException e) {
            logger.debug("I/O error while reading from stream: {}", e.getMessage());
            setStatus(false, "I/O Error");
        } catch (RuntimeException e) {
            logger.warn("Runtime exception in reader thread", e);
            setStatus(false, "Runtime exception");
        } finally {
            logger.debug("Shim message reader thread exiting {}", config.port);
        }
    }

    public void sendKeepAlive(String request) {
        logger.trace("Sending GoogleTV keepalive query");
        String keepalive = GoogleTVRequest.encodeMessage(GoogleTVRequest.keepAlive(request));
        sendCommand(new GoogleTVCommand(keepalive));
        reconnectTaskSchedule();
        if (this.periodicUpdate <= 1) {
            sendPeriodicUpdate();
            this.periodicUpdate = 20;
        } else {
            periodicUpdate--;
        }
    }

    /**
     * Schedules the reconnect task keepAliveReconnectJob to execute in KEEPALIVE_TIMEOUT_SECONDS. This should
     * be
     * cancelled by calling reconnectTaskCancel() if a valid response is received from the bridge.
     */
    private void reconnectTaskSchedule() {
        synchronized (keepAliveReconnectLock) {
            logger.trace("Scheduling Reconnect Job for {}", KEEPALIVE_TIMEOUT_SECONDS);
            keepAliveReconnectJob = scheduler.schedule(this::keepAliveTimeoutExpired, KEEPALIVE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        }
    }

    /**
     * Cancels the reconnect task keepAliveReconnectJob.
     */
    private void reconnectTaskCancel(boolean interrupt) {
        synchronized (keepAliveReconnectLock) {
            ScheduledFuture<?> keepAliveReconnectJob = this.keepAliveReconnectJob;
            if (keepAliveReconnectJob != null) {
                logger.trace("Canceling GoogleTV scheduled reconnect job.");
                keepAliveReconnectJob.cancel(interrupt);
                this.keepAliveReconnectJob = null;
            }
        }
    }

    /**
     * Executed by keepAliveReconnectJob if it is not cancelled by the LEAP message parser calling
     * validMessageReceived() which in turn calls reconnectTaskCancel().
     */
    private void keepAliveTimeoutExpired() {
        logger.debug("GoogleTV keepalive response timeout expired. Initiating reconnect.");
        reconnect();
    }

    public void validMessageReceived() {
        reconnectTaskCancel(true); // Got a good message, so cancel reconnect task.
    }

    public void finishPinProcess() {
        if ((config.mode.equals(PIN_MODE)) && (!config.shim)) {
            disconnect(true);
            connectionManager.finishPinProcess();
        } else if ((config.mode.equals(DEFAULT_MODE)) && (!config.shim)) {
            childConnectionManager.dispose();
            reconnect();
        }
    }

    public void sendCommand(GoogleTVCommand command) {
        if ((!config.shim) && (!command.toString().equals(""))) {
            int length = command.toString().length();
            String hexLength = GoogleTVRequest.encodeMessage(GoogleTVRequest.fixMessage(Integer.toHexString(length)));
            String message = hexLength + command.toString();
            GoogleTVCommand lenCommand = new GoogleTVCommand(message);
            sendQueue.add(lenCommand);
        }
    }

    public void sendShim(GoogleTVCommand command) {
        if (!command.toString().equals("")) {
            shimQueue.add(command);
        }
    }

    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.trace("Command received: {}", channelUID.getId().toString());

        if (CHANNEL_KEYPRESS.equals(channelUID.getId())) {
            if (command instanceof StringType) {
                if (command.toString().length() == 5) {
                    // Account for KEY_(ASCII Character)
                    String keyPress = "aa01071a0512031a01"
                            + GoogleTVRequest.decodeMessage(new String("" + command.toString().charAt(4)));
                    sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage(keyPress)));
                    return;
                }

                String message = "";
                String suffix = "";
                String shortCommand = command.toString();
                if (command.toString().endsWith("_PRESS")) {
                    suffix = "1001";
                    shortCommand = "KEY_" + command.toString().split("_")[1];
                } else if (command.toString().endsWith("_RELEASE")) {
                    suffix = "1002";
                    shortCommand = "KEY_" + command.toString().split("_")[1];
                } else {
                    suffix = "1003";
                }

                switch (shortCommand) {
                    case "KEY_UP":
                        message = "52040813" + suffix;
                        break;
                    case "KEY_DOWN":
                        message = "52040814" + suffix;
                        break;
                    case "KEY_RIGHT":
                        message = "52040816" + suffix;
                        break;
                    case "KEY_LEFT":
                        message = "52040815" + suffix;
                        break;
                    case "KEY_ENTER":
                        message = "52040817" + suffix;
                        break;
                    case "KEY_HOME":
                        message = "52040803" + suffix;
                        break;
                    case "KEY_BACK":
                        message = "52040804" + suffix;
                        break;
                    case "KEY_MENU":
                        message = "52040852" + suffix;
                        break;
                    case "KEY_PLAYPAUSE":
                        message = "52040855" + suffix;
                        break;
                    case "KEY_STOP":
                        message = "52040856" + suffix;
                        break;
                    case "KEY_NEXT":
                        message = "52040857" + suffix;
                        break;
                    case "KEY_PREVIOUS":
                        message = "52040858" + suffix;
                        break;
                    case "KEY_REWIND":
                        message = "52040859" + suffix;
                        break;
                    case "KEY_FORWARD":
                        message = "5204085A" + suffix;
                        break;
                    case "KEY_POWER":
                        message = "5204081a" + suffix;
                        break;
                    case "KEY_VOLUP":
                        message = "52040818" + suffix;
                        break;
                    case "KEY_VOLDOWN":
                        message = "52040819" + suffix;
                        break;
                    case "KEY_MUTE":
                        message = "5204085b" + suffix;
                        break;
                    default:
                        logger.debug("Unknown Key {}", command.toString());
                        return;
                }
                sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage(message)));
            }
        } else if (CHANNEL_KEYCODE.equals(channelUID.getId())) {
            if (command instanceof StringType) {
                String shortCommand = command.toString().split("_")[0];
                int commandInt = Integer.parseInt(shortCommand, 10);
                String suffix = "";
                if (commandInt > 255) {
                    suffix = "02";
                    commandInt -= 256;
                } else if (commandInt > 127) {
                    suffix = "01";
                }

                String key = Integer.toHexString(commandInt) + suffix;

                if ((key.length() % 2) == 1) {
                    key = "0" + key;
                }

                key = "08" + key;

                if (command.toString().endsWith("_PRESS")) {
                    key = key + "1001";
                } else if (command.toString().endsWith("_RELEASE")) {
                    key = key + "1002";
                } else {
                    key = key + "1003";
                }

                String length = "0" + (key.length() / 2);
                String message = "52" + length + key;

                logger.trace("Sending KEYCODE {} as {}", key, message);
                sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage(message)));
            }

        } else if (CHANNEL_PINCODE.equals(channelUID.getId())) {
            if (command instanceof StringType) {
                try {
                    if (config.mode.equals(DEFAULT_MODE)) {
                        childConnectionManager.handleCommand(channelUID, command);
                    } else if ((config.mode.equals(PIN_MODE)) && (!config.shim)) {
                        if (!isLoggedIn) {
                            if (command.toString().equals("REQUEST")) {
                                sendCommand(new GoogleTVCommand(
                                        GoogleTVRequest.encodeMessage(GoogleTVRequest.pinRequest(command.toString()))));
                            } else {
                                this.pinHash = GoogleTVUtils.validatePIN(command.toString(), androidtvPKI.getCert(),
                                        shimServerChain[0]);
                                sendCommand(new GoogleTVCommand(
                                        GoogleTVRequest.encodeMessage(GoogleTVRequest.pinRequest(this.pinHash))));
                            }
                        }
                    } else if ((config.mode.equals(PIN_MODE)) && (config.shim)) {
                        this.pinHash = GoogleTVUtils.validatePIN(command.toString(), androidtvPKI.getCert(),
                                shimServerChain[0]);
                        this.shimPinHash = GoogleTVUtils.validatePIN(command.toString(), shimClientChain[0],
                                shimClientLocalChain[0]);
                    }
                } catch (CertificateException e) {
                    logger.trace("PIN CertificateException", e);
                }
            }
        } else if (CHANNEL_POWER.equals(channelUID.getId())) {
            if (command instanceof OnOffType) {
                if ((power && command.equals(OnOffType.OFF)) || (!power && command.equals(OnOffType.ON))) {
                    sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage("5204081a1003")));
                }
            }
        } else if (CHANNEL_MUTE.equals(channelUID.getId())) {
            if (command instanceof OnOffType) {
                if ((volMute && command.equals(OnOffType.OFF)) || (!volMute && command.equals(OnOffType.ON))) {
                    sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage("5204085b1003")));
                }
            }
        } else if (CHANNEL_RAW.equals(channelUID.getId())) {
            if (command instanceof StringType) {
                String message = GoogleTVRequest.encodeMessage(command.toString());
                logger.trace("Raw Message Decodes as: {}", GoogleTVRequest.decodeMessage(message));
                sendCommand(new GoogleTVCommand(message));
            }
        } else if (CHANNEL_RAWMSG.equals(channelUID.getId())) {
            if (command instanceof StringType) {
                messageParser.handleMessage(command.toString());
            }
        } else if (CHANNEL_APP.equals(channelUID.getId())) {
            if (command instanceof StringType) {
                String message = GoogleTVRequest.encodeMessage(GoogleTVRequest.startApp(command.toString()));
                sendCommand(new GoogleTVCommand(message));
            }
        } else if (CHANNEL_KEYBOARD.equals(channelUID.getId())) {
            if (command instanceof StringType) {
                String keyPress = "";
                for (int i = 0; i < command.toString().length(); i++) {
                    keyPress = "aa01071a0512031a01"
                            + GoogleTVRequest.decodeMessage(new String("" + command.toString().charAt(i)));
                    sendCommand(new GoogleTVCommand(GoogleTVRequest.encodeMessage(keyPress)));
                }
            }
        }
    }

    public void dispose() {
        this.disposing = true;

        Future<?> asyncInitializeTask = this.asyncInitializeTask;
        if (asyncInitializeTask != null && !asyncInitializeTask.isDone()) {
            asyncInitializeTask.cancel(true); // Interrupt async init task if it isn't done yet
        }
        Future<?> shimAsyncInitializeTask = this.shimAsyncInitializeTask;
        if (shimAsyncInitializeTask != null && !shimAsyncInitializeTask.isDone()) {
            shimAsyncInitializeTask.cancel(true); // Interrupt async init task if it isn't done yet
        }
        if (childConnectionManager != null) {
            childConnectionManager.dispose();
        }
        disconnect(true);
    }
}
