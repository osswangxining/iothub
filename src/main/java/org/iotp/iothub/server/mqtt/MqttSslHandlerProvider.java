package org.iotp.iothub.server.mqtt;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.iotp.infomgt.dao.EncryptionUtil;
import org.iotp.infomgt.dao.device.DeviceCredentialsService;
import org.iotp.infomgt.data.security.DeviceCredentials;
import org.iotp.iothub.server.security.SslUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.google.common.io.Resources;

import io.netty.handler.ssl.SslHandler;
import lombok.extern.slf4j.Slf4j;

/**
 */
@Slf4j
@Component("MqttSslHandlerProvider")
@ConditionalOnProperty(prefix = "mqtt.ssl", value = "enabled", havingValue = "true", matchIfMissing = false)
public class MqttSslHandlerProvider {

    public static final String TLS = "TLS";
    @Value("${mqtt.ssl.key_store}")
    private String keyStoreFile;
    @Value("${mqtt.ssl.key_store_password}")
    private String keyStorePassword;
    @Value("${mqtt.ssl.key_password}")
    private String keyPassword;
    @Value("${mqtt.ssl.key_store_type}")
    private String keyStoreType;
    
    @Autowired
    private DeviceCredentialsService deviceCredentialsService;


    public SslHandler getSslHandler() {
        try {
            URL ksUrl = Resources.getResource(keyStoreFile);
            File ksFile = new File(ksUrl.toURI());
            URL tsUrl = Resources.getResource(keyStoreFile);
            File tsFile = new File(tsUrl.toURI());

            TrustManagerFactory tmFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore trustStore = KeyStore.getInstance(keyStoreType);
            trustStore.load(new FileInputStream(tsFile), keyStorePassword.toCharArray());
            tmFactory.init(trustStore);

            KeyStore ks = KeyStore.getInstance(keyStoreType);

            ks.load(new FileInputStream(ksFile), keyStorePassword.toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keyPassword.toCharArray());

            KeyManager[] km = kmf.getKeyManagers();
            TrustManager x509wrapped = getX509TrustManager(tmFactory);
            TrustManager[] tm = {x509wrapped};
            SSLContext sslContext = SSLContext.getInstance(TLS);
            sslContext.init(km, tm, null);
            SSLEngine sslEngine = sslContext.createSSLEngine();
            sslEngine.setUseClientMode(false);
            sslEngine.setNeedClientAuth(false);
            sslEngine.setWantClientAuth(true);
            sslEngine.setEnabledProtocols(sslEngine.getSupportedProtocols());
            sslEngine.setEnabledCipherSuites(sslEngine.getSupportedCipherSuites());
            sslEngine.setEnableSessionCreation(true);
            return new SslHandler(sslEngine);
        } catch (Exception e) {
            log.error("Unable to set up SSL context. Reason: " + e.getMessage(), e);
            throw new RuntimeException("Failed to get SSL handler", e);
        }
    }

    private TrustManager getX509TrustManager(TrustManagerFactory tmf) throws Exception {
        X509TrustManager x509Tm = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                x509Tm = (X509TrustManager) tm;
                break;
            }
        }
        return new ThingsboardMqttX509TrustManager(x509Tm, deviceCredentialsService);
    }

    static class ThingsboardMqttX509TrustManager implements X509TrustManager {

        private final X509TrustManager trustManager;
        private DeviceCredentialsService deviceCredentialsService;

        ThingsboardMqttX509TrustManager(X509TrustManager trustManager, DeviceCredentialsService deviceCredentialsService) {
            this.trustManager = trustManager;
            this.deviceCredentialsService = deviceCredentialsService;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return trustManager.getAcceptedIssuers();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain,
                                       String authType) throws CertificateException {
            trustManager.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain,
                                       String authType) throws CertificateException {
            DeviceCredentials deviceCredentials = null;
            for (X509Certificate cert : chain) {
                try {
                    String strCert = SslUtil.getX509CertificateString(cert);
                    String sha3Hash = EncryptionUtil.getSha3Hash(strCert);
                    deviceCredentials = deviceCredentialsService.findDeviceCredentialsByCredentialsId(sha3Hash);
                    if (deviceCredentials != null && strCert.equals(deviceCredentials.getCredentialsValue())) {
                        break;
                    }
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }
            if (deviceCredentials == null) {
                throw new CertificateException("Invalid Device Certificate");
            }
        }
    }
}
