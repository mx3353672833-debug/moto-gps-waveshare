import nodemailer from "nodemailer";

export const RECIPIENT = "malerxv@gmail.com";
export const SUBJECT = "[Glimpse] 官网提问";

export function isMailbox(value) {
  return typeof value === "string" && value.length <= 254 &&
    /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}$/.test(value) &&
    !value.includes("..") && value.indexOf("@") <= 64;
}

export function smtpOptions(env) {
  const host = env.SMTP_HOST?.trim();
  const user = env.SMTP_USER;
  const pass = env.SMTP_PASS;
  const from = (env.SMTP_FROM_EMAIL || user || "").trim();
  const secure = env.SMTP_SECURE === undefined ? true : ["true", "1"].includes(env.SMTP_SECURE.toLowerCase());
  const port = Number(env.SMTP_PORT || (secure ? 465 : 587));
  if (!host || /[\s/\r\n]/.test(host) || !user || !pass || !isMailbox(from) ||
      !Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("SMTP_CONFIGURATION_INVALID");
  }
  return {
    from,
    transport: {
      host, port, secure,
      requireTLS: !secure,
      auth: { user, pass },
      tls: { minVersion: "TLSv1.2", rejectUnauthorized: true },
      connectionTimeout: 10_000,
      greetingTimeout: 10_000,
      socketTimeout: 20_000,
      dnsTimeout: 10_000,
      disableFileAccess: true,
      disableUrlAccess: true,
      maxRecipients: 1,
      logger: false,
      debug: false,
    },
  };
}

export function buildMessage({ question, email, name }, from, requestId) {
  return {
    from: { name: "Glimpse 官网", address: from },
    to: RECIPIENT,
    envelope: { from, to: [RECIPIENT] },
    ...(email ? { replyTo: { name: name || "网站访客", address: email } } : {}),
    subject: SUBJECT,
    text: [
      "Glimpse 官网收到一条提问。",
      "",
      `称呼：${name || "未填写"}`,
      `回复邮箱：${email || "未填写"}`,
      "",
      "问题：",
      question,
      "",
      `提交编号：${requestId}`,
      "由 Glimpse 官网提问表单发送。回复邮箱为访客自行填写，未经身份验证。",
    ].join("\n"),
    disableFileAccess: true,
    disableUrlAccess: true,
  };
}

export function createMailer(env, createTransport = nodemailer.createTransport) {
  const options = smtpOptions(env);
  const transport = createTransport(options.transport);
  return {
    verify: () => transport.verify(),
    close: () => transport.close(),
    async send(payload, requestId) {
      const info = await transport.sendMail(buildMessage(payload, options.from, requestId));
      const accepted = info.accepted?.some((address) => String(address).toLowerCase() === RECIPIENT);
      if (!accepted || info.rejected?.length) throw new Error("SMTP_RECIPIENT_NOT_ACCEPTED");
      return { messageId: info.messageId };
    },
  };
}
