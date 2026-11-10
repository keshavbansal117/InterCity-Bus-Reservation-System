package com.firstchoice.busreservation.mail;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/** A small SMTP client (works with Gmail, Brevo, SendGrid, Zoho, any SMTP server). Security: SSL, STARTTLS or NONE. */
public final class SmtpMailer {
    private final String host, user, password, from, security;
    private final int port;

    public SmtpMailer(String host, int port, String user, String password, String from, String security) {
        this.host = host; this.port = port; this.user = user; this.password = password; this.from = from; this.security = security;
    }

    private static final class Conn {
        final Socket socket; final BufferedReader in; final BufferedWriter out;
        Conn(Socket s) throws IOException {
            socket = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }
        int reply() throws IOException {
            String line;
            while (true) {
                line = in.readLine();
                if (line == null) throw new IOException("SMTP server closed the connection");
                if (line.length() < 4 || line.charAt(3) != '-') break;      // "250-..." continues, "250 ..." ends
            }
            return Integer.parseInt(line.substring(0, 3));
        }
        void expect(int code) throws IOException {
            int got = reply();
            if (got != code) throw new IOException("SMTP error: expected " + code + " but got " + got);
        }
        void cmd(String c, int code) throws IOException { out.write(c + "\r\n"); out.flush(); expect(code); }
    }

    public void send(String to, String subject, String body) throws IOException {
        String cleanTo = clean(to), cleanFrom = clean(from);
        Socket raw = "SSL".equals(security) ? SSLSocketFactory.getDefault().createSocket(host, port) : new Socket(host, port);
        raw.setSoTimeout(20_000);
        try {
            Conn c = new Conn(raw);
            c.expect(220);
            c.cmd("EHLO firstchoice", 250);
            if ("STARTTLS".equals(security)) {
                c.cmd("STARTTLS", 220);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
                tls.startHandshake();
                c = new Conn(tls);
                c.cmd("EHLO firstchoice", 250);
            }
            if (!user.isEmpty()) {
                c.cmd("AUTH LOGIN", 334);
                c.cmd(b64(user), 334);
                c.cmd(b64(password), 235);
            }
            c.cmd("MAIL FROM:<" + cleanFrom + ">", 250);
            c.cmd("RCPT TO:<" + cleanTo + ">", 250);
            c.cmd("DATA", 354);
            StringBuilder m = new StringBuilder();
            m.append("From: First Choice <").append(cleanFrom).append(">\r\n");
            m.append("To: <").append(cleanTo).append(">\r\n");
            m.append("Subject: =?UTF-8?B?").append(b64(clean(subject))).append("?=\r\n");
            m.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
            m.append("Message-ID: <").append(UUID.randomUUID()).append("@firstchoice>\r\n");
            m.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
            String enc = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < enc.length(); i += 76) m.append(enc, i, Math.min(enc.length(), i + 76)).append("\r\n");
            m.append(".\r\n");
            c.out.write(m.toString());
            c.out.flush();
            c.expect(250);
            try { c.cmd("QUIT", 221); } catch (IOException ignored) { }
        } finally {
            try { raw.close(); } catch (IOException ignored) { }
        }
    }

    private static String clean(String s) { return s.replaceAll("[\\r\\n<>]", "").trim(); }
    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
}
