package com.drskunk.sh3dikea.netdiag;

import javax.crypto.Cipher;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Network diagnostics for the IKEA Browser plugin. Runs a battery of DNS,
 * TCP, TLS and HTTP checks against the hosts the plugin talks to (plus a few
 * control hosts) from inside whatever JVM / sandbox it is launched in, and
 * prints a plain-text report suitable for pasting into a GitHub issue.
 *
 * Usable both as a Sweet Home 3D plugin (see NetDiagPlugin) and from the
 * command line: {@code java -jar IkeaNetDiag.sh3p [extra-host ...]}.
 */
public final class NetDiag {

    /** Hosts the IKEA Browser plugin actually uses. */
    static final String[] IKEA_HOSTS = {
            "sik.search.blue.cdtapps.com",
            "web-api.ikea.com",
            "www.ikea.com",
    };

    /** Unrelated hosts: if these fail too, the problem is general connectivity. */
    static final String[] CONTROL_HOSTS = {
            "www.google.com",
            "github.com",
            "www.cloudflare.com",
    };

    private static final String SEARCH_URL =
            "https://sik.search.blue.cdtapps.com/us/en/search-result-page"
                    + "?types=PRODUCT&q=billy&c=sr&v=20210322&size=1";
    private static final String EXISTS_URL =
            "https://web-api.ikea.com/us/en/rotera/data/exists/20522046/";
    private static final String MODEL_URL =
            "https://web-api.ikea.com/us/en/rotera/data/model/20522046/";
    private static final String CLIENT_ID = "4863e7d2-1428-4324-890b-ae5dede24fc6";
    private static final String USER_AGENT =
            "Sweet Home 3D IKEA Browser NetDiag ( https://github.com/drskunk/sh3d-ikea-models/ )";

    private static final int TIMEOUT_MS = 8000;

    private final Consumer<String> out;
    private final List<String> findings = new ArrayList<>();

    public NetDiag(Consumer<String> out) {
        this.out = out;
    }

    public static void main(String[] args) {
        NetDiag d = new NetDiag(System.out::println);
        List<String> extra = new ArrayList<>(Arrays.asList(args));
        d.runAll(extra);
    }

    // --- Driver ---------------------------------------------------------------

    public void runAll(List<String> extraHosts) {
        line("IKEA Browser network diagnostics");
        line("Started: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date()));
        section("Environment", this::environment);
        section("Proxy configuration", this::proxies);
        section("TLS / JSSE configuration", this::tlsConfig);
        section("Trust store", this::trustStore);
        section("Resolver configuration", this::resolverFiles);
        section("Network interfaces", this::interfaces);

        Set<String> hosts = new LinkedHashSet<>();
        Collections.addAll(hosts, IKEA_HOSTS);
        for (String h : extraHosts) {
            h = h.trim();
            if (!h.isEmpty()) hosts.add(h);
        }
        Collections.addAll(hosts, CONTROL_HOSTS);
        for (String h : hosts) {
            final String host = h;
            section("Host " + host, () -> hostChecks(host));
        }

        section("Plugin requests (same as IKEA Browser)", this::pluginRequests);

        line("");
        line("=== Summary ===");
        if (findings.isEmpty()) {
            line("No problems detected.");
        } else {
            for (String f : findings) line("* " + f);
        }
        line("Finished: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date()));
    }

    private void section(String title, Runnable body) {
        line("");
        line("=== " + title + " ===");
        try {
            body.run();
        } catch (Throwable t) {
            line("  !! section crashed: " + describe(t));
        }
    }

    private void line(String s) {
        out.accept(s);
    }

    private void kv(String k, Object v) {
        line("  " + k + ": " + v);
    }

    private void finding(String s) {
        findings.add(s);
        line("  >> " + s);
    }

    // --- Environment ----------------------------------------------------------

    private void environment() {
        for (String p : new String[] {"java.version", "java.vendor", "java.runtime.version",
                "java.vm.name", "java.home", "os.name", "os.version", "os.arch",
                "user.language", "user.country", "file.encoding",
                "java.net.preferIPv4Stack", "java.net.preferIPv6Addresses",
                "networkaddress.cache.ttl", "sun.net.inetaddr.ttl"}) {
            kv(p, System.getProperty(p));
        }
        kv("security networkaddress.cache.ttl", Security.getProperty("networkaddress.cache.ttl"));
        kv("FLATPAK_ID", System.getenv("FLATPAK_ID"));
        kv("SNAP_NAME", System.getenv("SNAP_NAME"));
        kv("APPIMAGE", System.getenv("APPIMAGE") != null ? "set" : null);
        File flatpakInfo = new File("/.flatpak-info");
        if (flatpakInfo.exists()) {
            line("  /.flatpak-info:");
            for (String l : readLines(flatpakInfo, 60)) line("    " + l);
        }
    }

    // --- Proxies --------------------------------------------------------------

    private void proxies() {
        for (String p : new String[] {"java.net.useSystemProxies", "http.proxyHost", "http.proxyPort",
                "https.proxyHost", "https.proxyPort", "socksProxyHost", "socksProxyPort",
                "http.nonProxyHosts"}) {
            kv(p, System.getProperty(p));
        }
        for (String e : new String[] {"http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY",
                "all_proxy", "ALL_PROXY", "no_proxy", "NO_PROXY"}) {
            String v = System.getenv(e);
            if (v != null) kv("env " + e, redactUserInfo(v));
        }
        ProxySelector sel = ProxySelector.getDefault();
        kv("ProxySelector", sel == null ? null : sel.getClass().getName());
        if (sel != null) {
            for (String h : IKEA_HOSTS) {
                try {
                    List<Proxy> ps = sel.select(new URI("https://" + h + "/"));
                    kv("select https://" + h, ps);
                    for (Proxy p : ps) {
                        if (p.type() != Proxy.Type.DIRECT) {
                            finding("Java routes https://" + h + " through proxy " + p);
                        }
                    }
                } catch (Exception e) {
                    kv("select https://" + h, describe(e));
                }
            }
        }
    }

    // --- TLS config -----------------------------------------------------------

    private void tlsConfig() {
        StringBuilder provs = new StringBuilder();
        for (Provider p : Security.getProviders()) {
            if (provs.length() > 0) provs.append(", ");
            provs.append(p.getName()).append(' ').append(p.getVersion());
        }
        kv("security providers", provs);
        for (String p : new String[] {"jdk.tls.disabledAlgorithms", "jdk.certpath.disabledAlgorithms",
                "jdk.tls.legacyAlgorithms", "crypto.policy", "keystore.type"}) {
            kv("security " + p, Security.getProperty(p));
        }
        for (String p : new String[] {"https.protocols", "jdk.tls.client.protocols",
                "jdk.tls.namedGroups", "jdk.tls.client.cipherSuites", "jsse.enableSNIExtension",
                "javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType",
                "com.sun.net.ssl.checkRevocation", "javax.net.debug"}) {
            kv(p, System.getProperty(p));
        }
        try {
            kv("max AES key length", Cipher.getMaxAllowedKeyLength("AES"));
            if (Cipher.getMaxAllowedKeyLength("AES") < 256) {
                finding("Limited crypto policy: AES-256 not allowed (old JRE without unlimited policy)");
            }
        } catch (Exception e) {
            kv("max AES key length", describe(e));
        }
        // JSSE only enables ECDHE suites if all of these work. A JRE missing
        // sunec.jar (Sweet Home 3D's bundled Linux runtime) fails them all;
        // an unsigned sunec.jar on Oracle's JRE fails only KeyAgreement.
        boolean ecOk = true;
        for (String[] alg : new String[][] {{"KeyAgreement", "ECDH"}, {"KeyPairGenerator", "EC"},
                {"KeyFactory", "EC"}, {"Signature", "SHA256withECDSA"}}) {
            try {
                if (alg[0].equals("KeyAgreement")) javax.crypto.KeyAgreement.getInstance(alg[1]);
                else if (alg[0].equals("KeyPairGenerator")) java.security.KeyPairGenerator.getInstance(alg[1]);
                else if (alg[0].equals("KeyFactory")) java.security.KeyFactory.getInstance(alg[1]);
                else java.security.Signature.getInstance(alg[1]);
                kv(alg[0] + " " + alg[1], "available");
            } catch (Exception e) {
                kv(alg[0] + " " + alg[1], "NOT available (" + e.getMessage() + ")");
                ecOk = false;
            }
        }
        if (!ecOk) {
            finding("This JRE has no usable elliptic-curve crypto (SunEC missing or refused): ECDHE "
                    + "cipher suites are disabled, so hosts that require them fail with handshake_failure");
        }
        for (String proto : new String[] {"TLSv1.2", "TLSv1.3"}) {
            try {
                SSLContext.getInstance(proto);
                kv("SSLContext " + proto, "available");
            } catch (Exception e) {
                kv("SSLContext " + proto, "NOT available (" + e.getClass().getSimpleName() + ")");
            }
        }
        try {
            SSLContext ctx = SSLContext.getDefault();
            SSLParameters def = ctx.getDefaultSSLParameters();
            SSLParameters sup = ctx.getSupportedSSLParameters();
            kv("default protocols", Arrays.toString(def.getProtocols()));
            kv("supported protocols", Arrays.toString(sup.getProtocols()));
            String[] suites = def.getCipherSuites();
            kv("enabled cipher suites", suites.length);
            for (String s : suites) line("    " + s);
            // The ones IKEA's search server (Google front end) accepts on TLS 1.2.
            List<String> wanted = Arrays.asList(
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                    "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384");
            boolean any = false;
            for (String w : wanted) any |= Arrays.asList(suites).contains(w);
            if (!any) {
                finding("None of the cipher suites sik.search.blue.cdtapps.com accepts are enabled in this JRE");
            }
        } catch (Exception e) {
            kv("SSLContext default", describe(e));
        }
    }

    private void trustStore() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            int count = 0;
            List<String> interesting = new ArrayList<>();
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (!(tm instanceof X509TrustManager)) continue;
                for (X509Certificate c : ((X509TrustManager) tm).getAcceptedIssuers()) {
                    count++;
                    String dn = c.getSubjectX500Principal().getName();
                    if (dn.matches("(?i).*(GTS Root|GlobalSign|ISRG|DigiCert Global Root G2|Amazon Root).*")) {
                        interesting.add(cn(dn) + " (until " + day(c.getNotAfter()) + ")");
                    }
                }
            }
            kv("trusted CA certificates", count);
            String home = System.getProperty("java.home");
            kv("cacerts", new File(home, "lib/security/cacerts").getAbsolutePath()
                    + (new File(home, "lib/security/cacerts").exists() ? "" : " (missing)"));
            line("  relevant roots:");
            for (String s : interesting) line("    " + s);
            boolean gts = false;
            for (String s : interesting) gts |= s.startsWith("GTS Root R1") || s.startsWith("GTS Root R4")
                    || s.startsWith("GlobalSign");
            if (!gts) {
                finding("Trust store lacks GTS Root R1/R4 and GlobalSign roots: Google-issued certs "
                        + "(sik.search.blue.cdtapps.com) cannot be validated");
            }
        } catch (Exception e) {
            kv("trust store", describe(e));
        }
    }

    // --- Resolver / interfaces -----------------------------------------------

    private void resolverFiles() {
        dumpFile("/etc/resolv.conf", null);
        dumpFile("/etc/nsswitch.conf", "^hosts:.*");
        dumpFile("/etc/hosts", "(?i).*(ikea|cdtapps|google|github|cloudflare).*");
        dumpFile("/etc/gai.conf", "^\\s*[^#\\s].*");
        dumpFile("/run/systemd/resolve/resolv.conf", null);
    }

    private void dumpFile(String path, String filter) {
        File f = new File(path);
        if (!f.exists()) {
            kv(path, "(not present)");
            return;
        }
        line("  " + path + (filter != null ? " (filtered)" : "") + ":");
        int shown = 0;
        for (String l : readLines(f, 200)) {
            if (filter != null && !l.matches(filter)) continue;
            if (filter == null && (l.trim().isEmpty() || l.trim().startsWith("#"))) continue;
            line("    " + l);
            shown++;
        }
        if (shown == 0) line("    (no matching lines)");
        if (path.equals("/etc/hosts")) {
            for (String l : readLines(f, 2000)) {
                if (l.matches("(?i)^\\s*[^#].*(ikea|cdtapps).*")) {
                    finding("/etc/hosts overrides an IKEA host: " + l.trim());
                }
            }
        }
    }

    private void interfaces() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                boolean v4 = false, v6 = false;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) v4 = true;
                    if (a instanceof Inet6Address && !a.isLinkLocalAddress()) v6 = true;
                }
                // Addresses are deliberately not printed (the report is meant to be posted publicly).
                line("  " + ni.getName() + " up=" + ni.isUp() + " loopback=" + ni.isLoopback()
                        + " virtual=" + ni.isVirtual() + " p2p=" + ni.isPointToPoint()
                        + " mtu=" + safeMtu(ni) + " ipv4=" + v4 + " ipv6=" + v6);
                String n = ni.getName().toLowerCase();
                if (ni.isUp() && (n.startsWith("tun") || n.startsWith("wg") || n.startsWith("ppp")
                        || n.startsWith("tailscale") || n.startsWith("proton") || n.startsWith("nordlynx")
                        || n.startsWith("mullvad"))) {
                    finding("VPN-like interface is up: " + ni.getName());
                }
            }
        } catch (Exception e) {
            kv("interfaces", describe(e));
        }
    }

    private static String safeMtu(NetworkInterface ni) {
        try {
            return String.valueOf(ni.getMTU());
        } catch (Exception e) {
            return "?";
        }
    }

    // --- Per-host checks ------------------------------------------------------

    private void hostChecks(String host) {
        boolean isIkea = Arrays.asList(IKEA_HOSTS).contains(host);

        // 1. System resolver (what HttpURLConnection uses).
        List<InetAddress> sys = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            sys.addAll(Arrays.asList(InetAddress.getAllByName(host)));
            kv("system DNS (" + ms(t0) + " ms)", ipList(sys));
        } catch (Exception e) {
            kv("system DNS (" + ms(t0) + " ms)", "FAILED " + describe(e));
            if (isIkea) finding("System DNS cannot resolve " + host + ": " + describe(e));
        }
        for (InetAddress a : sys) {
            String cls = classify(a);
            if (cls != null) {
                kv("  " + a.getHostAddress(), cls);
                if (isIkea) finding(host + " resolves to " + a.getHostAddress() + " (" + cls
                        + ") - looks like a DNS blocker / sinkhole");
            }
        }

        // 2. DNS over HTTPS, bypassing the local resolver, to compare answers.
        List<String> doh = new ArrayList<>();
        for (String[] r : new String[][] {
                {"dns.google", "https://dns.google/resolve?type=A&name="},
                {"cloudflare", "https://cloudflare-dns.com/dns-query?type=A&name="}}) {
            t0 = System.nanoTime();
            try {
                List<String> ips = dohLookup(r[1] + host);
                kv("DoH " + r[0] + " (" + ms(t0) + " ms)", ips);
                for (String ip : ips) if (!doh.contains(ip)) doh.add(ip);
            } catch (Exception e) {
                kv("DoH " + r[0] + " (" + ms(t0) + " ms)", "FAILED " + describe(e));
            }
        }
        if (!doh.isEmpty() && !sys.isEmpty()) {
            boolean overlap = false;
            for (InetAddress a : sys) overlap |= doh.contains(a.getHostAddress());
            boolean sysHasV4 = false;
            for (InetAddress a : sys) sysHasV4 |= a instanceof Inet4Address;
            if (!overlap && sysHasV4) {
                String msg = "System DNS answer for " + host + " differs from public DNS " + doh
                        + " (could be geo-DNS, or DNS rewriting by router/blocker)";
                if (isIkea) finding(msg); else kv("note", msg);
            }
        }

        // 3. TCP + TLS against each system-resolved address (max 4). Java 8's
        // HttpURLConnection only ever connects to the first one, so failures on
        // the others are informational.
        for (int i = 0; i < sys.size() && i < 4; i++) {
            probeAddress(host, sys.get(i), isIkea && i == 0, i == 0 ? "system, used by Java" : "system");
        }
        // 4. TLS against the public-DNS address if the system one is different.
        for (String ip : doh) {
            boolean seen = false;
            for (InetAddress a : sys) seen |= a.getHostAddress().equals(ip);
            if (seen) continue;
            try {
                probeAddress(host, InetAddress.getByAddress(host, InetAddress.getByName(ip).getAddress()),
                        false, "public-DNS");
            } catch (Exception e) {
                kv(ip, describe(e));
            }
            break;
        }

        // 5. Plain HTTP on port 80: transparent proxies / block pages show up here.
        httpProbe("http://" + host + "/", false, null, false);
    }

    /** {@code critical}: failures here become summary findings rather than notes. */
    private void probeAddress(String host, InetAddress addr, boolean critical, String via) {
        String tag = addr.getHostAddress() + " [" + via + "]";
        line("  -- " + tag);

        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, 443), TIMEOUT_MS);
            kv("TCP 443", "ok (" + ms(t0) + " ms)");
        } catch (Exception e) {
            kv("TCP 443", "FAILED after " + ms(t0) + " ms: " + describe(e));
            if (critical) finding("Cannot open TCP 443 to " + host + " at " + tag + ": " + describe(e));
            return;
        }

        String defaultResult = tls(host, addr, null, true, false, "TLS default");
        if (defaultResult != null && critical) {
            finding("TLS handshake with " + host + " at " + tag + " fails with JRE defaults: " + defaultResult);
        }
        tls(host, addr, new String[] {"TLSv1.2"}, true, false, "TLS 1.2 only");
        tls(host, addr, new String[] {"TLSv1.3"}, true, false, "TLS 1.3 only");
        tls(host, addr, null, false, false, "TLS no SNI");
        // Trust-all handshake only to see which certificate is actually presented
        // (a foreign issuer means something on the path intercepts TLS).
        tls(host, addr, null, true, true, "TLS trust-all (cert inspection)");
    }

    /** Returns null on success, else a short error description. */
    private String tls(String host, InetAddress addr, String[] protocols, boolean sni,
                       boolean trustAll, String label) {
        long t0 = System.nanoTime();
        SSLSocket ssl = null;
        try {
            SSLSocketFactory f;
            if (trustAll) {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] {new TrustAll()}, null);
                f = ctx.getSocketFactory();
            } else {
                f = SSLContext.getDefault().getSocketFactory();
            }
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(addr, 443), TIMEOUT_MS);
            raw.setSoTimeout(TIMEOUT_MS);
            ssl = (SSLSocket) f.createSocket(raw, sni ? host : addr.getHostAddress(), 443, true);
            SSLParameters p = ssl.getSSLParameters();
            if (sni) {
                List<SNIServerName> names = new ArrayList<>();
                names.add(new SNIHostName(host));
                p.setServerNames(names);
                if (!trustAll) p.setEndpointIdentificationAlgorithm("HTTPS");
            } else {
                p.setServerNames(Collections.<SNIServerName>emptyList());
            }
            if (protocols != null) {
                List<String> supported = Arrays.asList(ssl.getSupportedProtocols());
                for (String pr : protocols) {
                    if (!supported.contains(pr)) {
                        kv(label, "skipped (" + pr + " not supported by this JRE)");
                        return null;
                    }
                }
                p.setProtocols(protocols);
            }
            ssl.setSSLParameters(p);
            ssl.startHandshake();
            SSLSession s = ssl.getSession();
            kv(label, "ok " + s.getProtocol() + " " + s.getCipherSuite() + " (" + ms(t0) + " ms)");
            if (trustAll) {
                Certificate[] chain = s.getPeerCertificates();
                for (int i = 0; i < chain.length; i++) {
                    if (!(chain[i] instanceof X509Certificate)) continue;
                    X509Certificate c = (X509Certificate) chain[i];
                    line("      cert[" + i + "] subject=" + cn(c.getSubjectX500Principal().getName())
                            + " issuer=" + cn(c.getIssuerX500Principal().getName())
                            + " valid=" + day(c.getNotBefore()) + ".." + day(c.getNotAfter())
                            + " sig=" + c.getSigAlgName());
                }
                if (chain.length > 0 && chain[0] instanceof X509Certificate) {
                    X509Certificate leaf = (X509Certificate) chain[0];
                    String issuer = leaf.getIssuerX500Principal().getName();
                    if (!issuer.matches("(?i).*(Google Trust Services|DigiCert|GlobalSign|Let's Encrypt"
                            + "|Amazon|Sectigo|Entrust|GeoTrust|Thawte|Cloudflare|Microsoft).*")) {
                        finding(host + " presents a certificate issued by '" + cn(issuer)
                                + "' - TLS is probably intercepted (antivirus, proxy, firewall)");
                    }
                }
            }
            return null;
        } catch (Exception e) {
            String d = describe(e);
            kv(label, "FAILED after " + ms(t0) + " ms: " + d);
            return d;
        } finally {
            if (ssl != null) {
                try {
                    ssl.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // --- Real plugin requests -------------------------------------------------

    private void pluginRequests() {
        httpProbe(SEARCH_URL, false, null, true);
        httpProbe(SEARCH_URL, false, Proxy.NO_PROXY, true);
        httpProbe(EXISTS_URL, true, null, true);
        httpProbe(MODEL_URL, true, null, true);
    }

    private void httpProbe(String url, boolean clientId, Proxy proxy, boolean report) {
        long t0 = System.nanoTime();
        String label = "GET " + url + (proxy == Proxy.NO_PROXY ? " [NO_PROXY]" : "");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) (proxy != null ? new URL(url).openConnection(proxy)
                    : new URL(url).openConnection());
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            if (clientId) c.setRequestProperty("X-Client-Id", CLIENT_ID);
            int status = c.getResponseCode();
            line("  " + label);
            kv("  status", status + " " + c.getResponseMessage() + " (" + ms(t0) + " ms)");
            for (String h : new String[] {"Server", "Via", "Location", "Content-Type", "X-Cache",
                    "CF-RAY", "X-Squid-Error", "Proxy-Agent"}) {
                String v = c.getHeaderField(h);
                if (v != null) kv("  " + h, v);
            }
            if (c instanceof HttpsURLConnection) {
                kv("  cipher", ((HttpsURLConnection) c).getCipherSuite());
            }
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] body = in == null ? new byte[0] : readUpTo(in, 64 * 1024);
            String text = new String(body, StandardCharsets.UTF_8);
            kv("  body", body.length + " bytes: " + oneLine(text, 160));
            if (report && url.equals(SEARCH_URL) && status == 200 && !text.contains("searchResultPage")) {
                finding("Search returned 200 but not IKEA's JSON (proxy / block page?): " + oneLine(text, 120));
            }
            if (report && status >= 400) {
                finding(label + " -> HTTP " + status);
            }
        } catch (Exception e) {
            line("  " + label);
            kv("  FAILED after " + ms(t0) + " ms", describe(e));
            if (report) finding(label + " fails: " + describe(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // --- Helpers --------------------------------------------------------------

    private static List<String> dohLookup(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Accept", "application/dns-json");
        c.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            String json = new String(readUpTo(c.getInputStream(), 64 * 1024), StandardCharsets.UTF_8);
            List<String> ips = new ArrayList<>();
            // Only type-1 (A) answers; CNAME targets are skipped.
            Matcher m = Pattern.compile("\"type\"\\s*:\\s*1\\s*,[^}]*?\"data\"\\s*:\\s*\"([0-9.]+)\"").matcher(json);
            while (m.find()) ips.add(m.group(1));
            return ips;
        } finally {
            c.disconnect();
        }
    }

    /** Non-null if the address is one a DNS blocker would typically return. */
    private static String classify(InetAddress a) {
        if (a.isAnyLocalAddress()) return "unspecified address";
        if (a.isLoopbackAddress()) return "loopback";
        if (a.isLinkLocalAddress()) return "link-local";
        if (a.isSiteLocalAddress()) return "private network";
        byte[] b = a.getAddress();
        if (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64) return "carrier-grade NAT range";
        if (a instanceof Inet6Address && (b[0] & 0xfe) == 0xfc) return "unique-local IPv6";
        return null;
    }

    private static String ipList(List<InetAddress> as) {
        StringBuilder sb = new StringBuilder();
        for (InetAddress a : as) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(a.getHostAddress());
        }
        return sb.toString();
    }

    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = new LinkedHashSet<>();
        while (t != null && seen.add(t)) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(t.getClass().getName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
            t = t.getCause();
        }
        return sb.toString();
    }

    private static String cn(String dn) {
        Matcher m = Pattern.compile("CN=([^,]+)").matcher(dn);
        if (m.find()) return m.group(1);
        return dn;
    }

    private static String day(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd").format(d);
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String oneLine(String s, int max) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String redactUserInfo(String proxyUrl) {
        return proxyUrl.replaceAll("//[^/@]+@", "//***@");
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while (buf.size() < max && (n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    private static List<String> readLines(File f, int max) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f),
                StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null && lines.size() < max) lines.add(l);
        } catch (IOException e) {
            lines.add("(unreadable: " + describe(e) + ")");
        }
        return lines;
    }

    /** Accepts any certificate. Used only to inspect what the peer presents; no data is sent. */
    private static final class TrustAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
