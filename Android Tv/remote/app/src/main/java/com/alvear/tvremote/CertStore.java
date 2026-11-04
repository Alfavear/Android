package com.alvear.tvremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Certificado de cliente autofirmado. El TV lo recuerda tras el emparejamiento,
 * así que se genera una sola vez y se guarda en disco.
 */
final class CertStore {

    final PrivateKey key;
    final X509Certificate cert;

    private CertStore(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    static CertStore loadOrCreate(File dir) throws Exception {
        File kf = new File(dir, "client.key");
        File cf = new File(dir, "client.crt");
        CertificateFactory cfac = CertificateFactory.getInstance("X.509");
        if (kf.exists() && cf.exists()) {
            PrivateKey k = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(readAll(kf)));
            X509Certificate c = (X509Certificate) cfac.generateCertificate(new FileInputStream(cf));
            return new CertStore(k, c);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        byte[] der = buildSelfSigned(kp);
        X509Certificate c = (X509Certificate) cfac.generateCertificate(new ByteArrayInputStream(der));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(kf)) {
            o.write(kp.getPrivate().getEncoded());
        }
        try (FileOutputStream o = new FileOutputStream(cf)) {
            o.write(der);
        }
        return new CertStore(kp.getPrivate(), c);
    }

    static void wipe(File dir) {
        new File(dir, "client.key").delete();
        new File(dir, "client.crt").delete();
    }

    SSLContext sslContext() throws Exception {
        final PrivateKey k = key;
        final X509Certificate c = cert;
        KeyManager km = new X509KeyManager() {
            public String[] getClientAliases(String t, java.security.Principal[] i) {
                return new String[]{"c"};
            }

            public String chooseClientAlias(String[] t, java.security.Principal[] i, Socket s) {
                return "c";
            }

            public String[] getServerAliases(String t, java.security.Principal[] i) {
                return null;
            }

            public String chooseServerAlias(String t, java.security.Principal[] i, Socket s) {
                return null;
            }

            public X509Certificate[] getCertificateChain(String a) {
                return new X509Certificate[]{c};
            }

            public PrivateKey getPrivateKey(String a) {
                return k;
            }
        };
        // El TV usa un certificado autofirmado; la confianza se establece con el código de emparejamiento.
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String t) {
            }

            public void checkServerTrusted(X509Certificate[] chain, String t) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{km}, new TrustManager[]{tm}, null);
        return ctx;
    }

    // ---- DER mínimo para un certificado X.509 v3 autofirmado ----

    private static byte[] buildSelfSigned(KeyPair kp) throws Exception {
        byte[] sigAlg = der(0x30, new byte[]{0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B}, new byte[]{0x05, 0x00});
        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] = (byte) ((serial[0] & 0x7F) | 0x01);
        byte[] name = der(0x30, der(0x31, der(0x30,
                new byte[]{0x06, 0x03, 0x55, 0x04, 0x03},
                der(0x0C, "atvremote".getBytes("UTF-8")))));
        byte[] validity = der(0x30,
                der(0x17, "200101000000Z".getBytes("US-ASCII")),
                der(0x17, "491231235959Z".getBytes("US-ASCII")));
        byte[] tbs = der(0x30,
                new byte[]{(byte) 0xA0, 0x03, 0x02, 0x01, 0x02},
                der(0x02, serial),
                sigAlg,
                name,
                validity,
                name,
                kp.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return der(0x30, tbs, sigAlg, der(0x03, bits));
    }

    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (b.length < 128) {
            o.write(b.length);
        } else if (b.length < 256) {
            o.write(0x81);
            o.write(b.length);
        } else {
            o.write(0x82);
            o.write(b.length >> 8);
            o.write(b.length & 0xFF);
        }
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
            return o.toByteArray();
        }
    }
}
