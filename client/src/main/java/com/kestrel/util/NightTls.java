package com.kestrel.util;

import com.kestrel.KestrelMod;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * HTTPS for Night's own requests. Minecraft 1.8.9 runs on Java 8u51, whose
 * trust store predates the roots most hosts use today (Let's Encrypt, Google
 * Trust Services, SSL.com), so connections to, say, Cloudflare fail with a
 * certificate error. Night's connections trust the JVM's roots plus a bundled
 * set of current public roots ({@code assets/kestrel/tls/roots.pem}).
 * Certificates are still fully validated (chain, dates, host name); nothing
 * else in the game is affected.
 */
public final class NightTls {
    private static final String ROOTS = "/assets/kestrel/tls/roots.pem";
    private static SSLSocketFactory factory;

    private NightTls() {
    }

    /** Open an HTTPS connection that trusts the JVM roots plus Night's bundled roots. */
    public static HttpURLConnection open(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("HTTPS only: " + url);
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        SSLSocketFactory f = factory();
        if (f != null) c.setSSLSocketFactory(f);
        return c;
    }

    private static synchronized SSLSocketFactory factory() {
        if (factory != null) return factory;
        try {
            X509TrustManager jvm = trustManager(null);
            X509TrustManager bundled = trustManager(bundledStore());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new Either(jvm, bundled)}, null);
            factory = ctx.getSocketFactory();
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            KestrelMod.LOG.warn("[Kestrel] bundled TLS roots unavailable, using Java's own: {}", e.toString());
        }
        return factory;
    }

    private static KeyStore bundledStore() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        InputStream in = NightTls.class.getResourceAsStream(ROOTS);
        if (in == null) throw new IOException("missing " + ROOTS);
        try {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            int i = 0;
            for (Certificate c : certs) ks.setCertificateEntry("night-root-" + i++, c);
            if (i == 0) throw new IOException("no certificates in " + ROOTS);
        } finally {
            in.close();
        }
        return ks;
    }

    private static X509TrustManager trustManager(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks); // null: the JVM's default roots
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new GeneralSecurityException("no X509TrustManager");
    }

    /** Trusts a server chain that either trust manager accepts. */
    private static final class Either implements X509TrustManager {
        private final X509TrustManager a;
        private final X509TrustManager b;

        Either(X509TrustManager a, X509TrustManager b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                a.checkServerTrusted(chain, authType);
            } catch (CertificateException first) {
                b.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<X509Certificate>();
            for (X509Certificate c : a.getAcceptedIssuers()) all.add(c);
            for (X509Certificate c : b.getAcceptedIssuers()) all.add(c);
            return all.toArray(new X509Certificate[0]);
        }
    }
}
