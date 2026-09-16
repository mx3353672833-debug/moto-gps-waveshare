import { createContactServer } from "./contact.js";
import { createMailer } from "./mail.js";

let mailer;
try {
  const port = Number(process.env.CONTACT_PORT || 8788);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("PORT_INVALID");
  mailer = createMailer(process.env);
  await mailer.verify();
  const server = createContactServer({
    mailer,
    allowedOrigin: process.env.CONTACT_ALLOWED_ORIGIN || "https://maler.top",
    trustProxy: process.env.CONTACT_TRUST_PROXY === "true",
    log: (event) => console.log(JSON.stringify(event)),
  });
  server.on("error", () => { console.error("CONTACT_SERVER_FAILED"); process.exitCode = 1; mailer.close(); });
  server.listen(port, "127.0.0.1", () => console.log("Glimpse contact service listening on loopback"));
  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.once(signal, () => server.close(() => { mailer.close(); process.exit(0); }));
  }
} catch {
  mailer?.close();
  console.error("CONTACT_START_FAILED: check service configuration and SMTP access");
  process.exitCode = 1;
}
