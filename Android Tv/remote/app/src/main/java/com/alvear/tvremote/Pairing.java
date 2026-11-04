package com.alvear.tvremote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.SSLSocket;

/** Emparejamiento con el TV (puerto 6467): start() hace que el TV muestre el código, finish() lo valida. */
final class Pairing implements Closeable {

    private static final int PORT = 6467;
    private static final int VERSION = 2;
    private static final int STATUS_OK = 200;

    private final CertStore store;
    private SSLSocket sock;
    private InputStream in;
    private OutputStream out;
    private X509Certificate serverCert;

    Pairing(CertStore store) {
        this.store = store;
    }

    void start(String host) throws Exception {
        sock = (SSLSocket) store.sslContext().getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress(host, PORT), 5000);
        sock.setSoTimeout(15000);
        sock.startHandshake();
        serverCert = (X509Certificate) sock.getSession().getPeerCertificates()[0];
        in = sock.getInputStream();
        out = sock.getOutputStream();

        send(10, Proto.cat(Proto.str(1, "androidtvremote2"), Proto.str(2, "Control TV")));
        expect(11);

        byte[] encoding = Proto.cat(Proto.num(1, 3), Proto.num(2, 6));
        send(20, Proto.cat(Proto.bytes(1, encoding), Proto.num(3, 1)));
        expect(20);

        send(30, Proto.cat(Proto.bytes(1, encoding), Proto.num(2, 1)));
        expect(31);
    }

    void finish(String code) throws Exception {
        code = code.trim();
        if (!code.matches("[0-9A-Fa-f]{6}")) throw new IOException("El código son 6 caracteres (0-9, A-F)");
        RSAPublicKey c = (RSAPublicKey) store.cert.getPublicKey();
        RSAPublicKey s = (RSAPublicKey) serverCert.getPublicKey();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(unsigned(c.getModulus()));
        md.update(unsigned(c.getPublicExponent()));
        md.update(unsigned(s.getModulus()));
        md.update(unsigned(s.getPublicExponent()));
        md.update(hex(code.substring(2)));
        send(40, Proto.bytes(1, md.digest()));
        expect(41);
    }

    private void send(int field, byte[] payload) throws IOException {
        Proto.writeDelimited(out, Proto.cat(Proto.num(1, VERSION), Proto.num(2, STATUS_OK), Proto.bytes(field, payload)));
    }

    private void expect(int field) throws IOException {
        List<Proto.Field> f = Proto.parse(Proto.readDelimited(in));
        Proto.Field status = Proto.get(f, 2);
        if (status != null && status.val != STATUS_OK) {
            throw new IOException("El TV rechazó el emparejamiento (código " + status.val + ")");
        }
        if (Proto.get(f, field) == null) throw new IOException("Respuesta inesperada del TV");
    }

    private static byte[] unsigned(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    private static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return r;
    }

    @Override
    public void close() {
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
    }
}
