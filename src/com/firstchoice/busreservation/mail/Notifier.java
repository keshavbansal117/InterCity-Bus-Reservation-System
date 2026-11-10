package com.firstchoice.busreservation.mail;

import com.firstchoice.busreservation.db.Db;
import com.firstchoice.busreservation.util.Security;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends emails and keeps a record in the outbox table.
 * - With SMTP configured: emails go out in the background; links in sensitive mails (password reset) are not stored.
 * - Without SMTP ("log mode"): emails are printed to the server console and stored in the admin Outbox,
 *   so the whole system can be tried locally without an email account.
 */
public final class Notifier {
    private final Db db;
    private final SmtpMailer smtp;      // null = log mode
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "mailer"); t.setDaemon(true); return t; });

    public Notifier(Db db, SmtpMailer smtp) { this.db = db; this.smtp = smtp; }

    public boolean isLogMode() { return smtp == null; }

    public void send(String to, String subject, String body, boolean sensitive) {
        String id = Security.id();
        String stored = (smtp == null || !sensitive) ? body : "[hidden: contains a secure link]";
        if (stored.length() > 3900) stored = stored.substring(0, 3900);
        try {
            db.exec("INSERT INTO outbox(id,to_email,subject,body,mail_status,created_at) VALUES (?,?,?,?,?,?)",
                    id, to, subject, stored, smtp == null ? "LOGGED" : "QUEUED", LocalDateTime.now());
        } catch (RuntimeException e) { System.err.println("Could not record email: " + e.getMessage()); }

        if (smtp == null) {
            System.out.println("\n[email - log mode] To: " + to + "\nSubject: " + subject + "\n" + body + "\n");
            return;
        }
        pool.submit(() -> {
            String status = "SENT";
            try { smtp.send(to, subject, body); }
            catch (Exception e) { status = "FAILED"; System.err.println("Email to " + to + " failed: " + e.getMessage()); }
            try { db.exec("UPDATE outbox SET mail_status=? WHERE id=?", status, id); } catch (RuntimeException ignored) { }
        });
    }
}
