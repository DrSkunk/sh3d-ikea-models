package com.drskunk.sh3dikea;

import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.NameType;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.ServerName;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsNoCloseNotifyException;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import javax.crypto.KeyAgreement;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal HTTPS GET client built on Bouncy Castle's pure-Java TLS stack.
 *
 * The Java 8 runtime bundled with the official Sweet Home 3D Linux build
 * (and the Flatpak, which repackages it) ships without {@code sunec.jar},
 * so the JRE has no elliptic-curve crypto and can only offer RSA key
 * exchange. IKEA's search host only accepts ECDHE and rejects that
 * handshake. This client is used as a fallback for such hosts.
 *
 * Bouncy Castle's lightweight crypto ({@link BcTlsCrypto}) is used instead
 * of its JCE provider, so Oracle's JCE provider-signing check never applies
 * and the jars can be merged into the plugin. Only RSA signature algorithms
 * are advertised so the server presents an RSA certificate chain, which
 * the JRE's own trust manager can still validate without EC support.
 */
final class BcHttpsClient {

    static final class Response {
        final int status;
        final String message;
        final byte[] body;

        Response(int status, String message, byte[] body) {
            this.status = status;
            this.message = message;
            this.body = body;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static Boolean jreHasEcdh;

    private BcHttpsClient() {
    }

    /** True if the running JRE can do ECDH (i.e. ECDHE cipher suites are usable). */
    static synchronized boolean jreHasEcdh() {
        if (jreHasEcdh == null) {
            boolean ok;
            try {
                KeyAgreement.getInstance("ECDH");
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            jreHasEcdh = ok;
        }
        return jreHasEcdh;
    }

    static Response get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol())) {
            throw new IOException("BcHttpsClient only handles https: " + url);
        }
        final String host = u.getHost();
        int port = u.getPort() == -1 ? 443 : u.getPort();
        String path = u.getFile().isEmpty() ? "/" : u.getFile();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            TlsClientProtocol tls = new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
            try {
                tls.connect(new Client(host));
            } catch (IOException e) {
                SSLHandshakeException she = new SSLHandshakeException(
                        "Bouncy Castle TLS handshake with " + host + " failed: " + e);
                she.initCause(e);
                throw she;
            }

            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(port == 443 ? host : host + ":" + port).append("\r\n");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
            req.append("Accept-Encoding: identity\r\n");
            req.append("Connection: close\r\n\r\n");
            OutputStream out = tls.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            try {
                return readResponse(tls.getInputStream());
            } finally {
                try {
                    tls.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // --- HTTP/1.1 response parsing ------------------------------------------

    private static Response readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) throw new IOException("Empty HTTP response");
        Matcher m = Pattern.compile("HTTP/\\d\\.\\d (\\d{3})\\s*(.*)").matcher(statusLine);
        if (!m.matches()) throw new IOException("Malformed HTTP status line: " + statusLine);
        int status = Integer.parseInt(m.group(1));
        String message = m.group(2);

        long contentLength = -1;
        boolean chunked = false;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                contentLength = Long.parseLong(value);
            } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (chunked) {
            while (true) {
                String size = readLine(in);
                if (size == null) throw new IOException("Truncated chunked response");
                int semi = size.indexOf(';');
                long n = Long.parseLong((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
                if (n == 0) break;
                copy(in, body, n);
                readLine(in); // CRLF after chunk
            }
        } else if (contentLength >= 0) {
            copy(in, body, contentLength);
        } else {
            copy(in, body, -1);
        }
        return new Response(status, message, body.toByteArray());
    }

    /** Copies n bytes (or until EOF when n < 0). */
    private static void copy(InputStream in, ByteArrayOutputStream out, long n) throws IOException {
        byte[] buf = new byte[16 * 1024];
        long remaining = n;
        while (n < 0 || remaining > 0) {
            int want = n < 0 ? buf.length : (int) Math.min(buf.length, remaining);
            int r;
            try {
                r = in.read(buf, 0, want);
            } catch (TlsNoCloseNotifyException e) {
                r = -1; // peer closed without close_notify; fine for Connection: close
            }
            if (r < 0) {
                if (n < 0) return;
                throw new IOException("Connection closed with " + remaining + " bytes missing");
            }
            out.write(buf, 0, r);
            remaining -= r;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while (true) {
            try {
                b = in.read();
            } catch (TlsNoCloseNotifyException e) {
                b = -1;
            }
            if (b < 0) return line.size() == 0 ? null : line.toString("ISO-8859-1");
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        return line.toString("ISO-8859-1");
    }

    // --- TLS client -----------------------------------------------------------

    private static final class Client extends DefaultTlsClient {
        private final String host;

        Client(String host) {
            super(new BcTlsCrypto(RANDOM));
            this.host = host;
        }

        @Override
        protected ProtocolVersion[] getSupportedVersions() {
            return ProtocolVersion.TLSv13.downTo(ProtocolVersion.TLSv12);
        }

        @Override
        protected Vector getSNIServerNames() {
            Vector<ServerName> names = new Vector<>();
            names.add(new ServerName(NameType.host_name, host.getBytes(StandardCharsets.US_ASCII)));
            return names;
        }

        /** RSA only, so the JRE (without EC) can verify the presented chain. */
        @Override
        protected Vector getSupportedSignatureAlgorithms() {
            Vector<SignatureAndHashAlgorithm> algs = new Vector<>();
            algs.add(SignatureAndHashAlgorithm.rsa_pss_rsae_sha256);
            algs.add(SignatureAndHashAlgorithm.rsa_pss_rsae_sha384);
            algs.add(SignatureAndHashAlgorithm.rsa_pss_rsae_sha512);
            algs.add(SignatureAndHashAlgorithm.getInstance(
                    org.bouncycastle.tls.HashAlgorithm.sha256, org.bouncycastle.tls.SignatureAlgorithm.rsa));
            algs.add(SignatureAndHashAlgorithm.getInstance(
                    org.bouncycastle.tls.HashAlgorithm.sha384, org.bouncycastle.tls.SignatureAlgorithm.rsa));
            algs.add(SignatureAndHashAlgorithm.getInstance(
                    org.bouncycastle.tls.HashAlgorithm.sha512, org.bouncycastle.tls.SignatureAlgorithm.rsa));
            return algs;
        }

        @Override
        public TlsAuthentication getAuthentication() {
            return new TlsAuthentication() {
                @Override
                public void notifyServerCertificate(TlsServerCertificate serverCertificate) throws IOException {
                    verifyChain(host, serverCertificate.getCertificate());
                }

                @Override
                public TlsCredentials getClientCredentials(CertificateRequest certificateRequest) {
                    return null;
                }
            };
        }
    }

    // --- Certificate validation ----------------------------------------------

    private static void verifyChain(String host, Certificate chain) throws IOException {
        if (chain == null || chain.isEmpty()) throw new IOException("Server sent no certificate");
        X509Certificate[] certs = new X509Certificate[chain.getLength()];
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (int i = 0; i < certs.length; i++) {
                certs[i] = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(chain.getCertificateAt(i).getEncoded()));
            }
            defaultTrustManager().checkServerTrusted(certs, "ECDHE_RSA");
        } catch (CertificateException e) {
            throw new IOException("Untrusted certificate for " + host + ": " + e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot validate certificate for " + host + ": " + e, e);
        }
        if (!hostnameMatches(host, certs[0])) {
            throw new IOException("Certificate for " + host + " does not match: "
                    + certs[0].getSubjectX500Principal().getName());
        }
    }

    private static X509TrustManager defaultTrustManager() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new GeneralSecurityException("No X509TrustManager available");
    }

    static boolean hostnameMatches(String host, X509Certificate cert) {
        host = host.toLowerCase(Locale.ROOT);
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))
                            && dnsNameMatches(host, String.valueOf(san.get(1)))) {
                        return true;
                    }
                }
                return false;
            }
        } catch (CertificateException e) {
            return false;
        }
        Matcher cn = Pattern.compile("(?:^|,)CN=([^,]+)").matcher(cert.getSubjectX500Principal().getName());
        return cn.find() && dnsNameMatches(host, cn.group(1));
    }

    /** RFC 6125 matching: exact, or a single leftmost wildcard label. */
    static boolean dnsNameMatches(String host, String pattern) {
        pattern = pattern.toLowerCase(Locale.ROOT);
        if (pattern.endsWith(".")) pattern = pattern.substring(0, pattern.length() - 1);
        if (!pattern.startsWith("*.")) return host.equals(pattern);
        String suffix = pattern.substring(1); // ".example.com"
        if (!host.endsWith(suffix)) return false;
        String label = host.substring(0, host.length() - suffix.length());
        return !label.isEmpty() && label.indexOf('.') < 0;
    }
}
