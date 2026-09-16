# Glimpse 官网提问邮件服务

Node.js 20+，通过经过 TLS 验证和认证的 SMTP 发送邮件。收件人固定为
`malerxv@gmail.com`，访客不能修改收件人、发件人、标题或添加附件。
SMTP 凭据只通过服务器环境变量读取。

## 接口

`POST /api/contact`，必须带 `Origin: https://maler.top` 和
`Content-Type: application/json`。路径由反向代理原样转发给此服务。

```json
{
  "question": "想了解离线地图如何下载？",
  "email": "visitor@example.com",
  "name": "访客",
  "website": ""
}
```

- `question`：必填，去掉首尾空白后 2–4000 字符。
- `email`：可选，最长 254 字符的单个邮箱；只设置 `Reply-To`。邮箱由访客填写，未经验证。
- `name`：可选，最长 80 字符，不接受换行或控制字符。
- `website`：蜜罐字段，正常访客应留空；有内容时拒绝发送。
- `token`：可选字符串预留字段，最长 4096 字符，目前不用于授权或验证码。

请求体最多 16 KiB，不接受压缩体。每个来源地址每 10 分钟最多 3 次尝试，
全站每小时最多 30 次有效发送尝试，最多同时发送 2 封。进程重启会重置内存限额。
接口不是公开邮件转发器；同源检查不能阻止脚本伪造 Origin，限流用于降低重复和批量提交。

### 返回

```json
{"ok":true,"requestId":"随机提交编号","message":"问题已发送，谢谢你的反馈。"}
```

只有 SMTP 服务明确接受固定收件人后才返回 200；这表示邮件已交给发送服务，
不等于保证 Gmail 已放入收件箱。最终送达还受发送服务和收件服务影响。
发送异常或收件人被拒绝返回 503、`ok:false`、`code:delivery_unavailable`。
前端只在收到成功响应后清空表单；失败时保留内容，展示备用邮箱链接。

格式错误返回 400，跨来源或缺少 Origin 返回 403，体积超限返回 413，
非 JSON 返回 415，限流返回 429 和 `Retry-After` 秒数。`GET /healthz`
只表示此进程运行；启动前会验证 SMTP 连通与认证，健康接口不保证之后每次邮件均可送达。

## 安装与运行

在仓库根目录执行：

```sh
npm ci --prefix website/server
npm --prefix website/server test
```

以 `.env.example` 为字段参考，在服务器创建仅管理员可读的专用环境文件；
不要把真实凭据放入仓库、网页或日志。可复用现有已授权 SMTP 服务的凭据，
发件邮箱必须是该 SMTP 账号获准使用的地址。

| 配置 | 含义 |
| --- | --- |
| `CONTACT_PORT` | 默认 `8788`，服务固定只监听本机回环地址 |
| `CONTACT_ALLOWED_ORIGIN` | 默认 `https://maler.top`，精确匹配，不含路径或末尾斜杠 |
| `CONTACT_TRUST_PROXY` | 默认关闭；仅在本机代理覆盖 `X-Real-IP` 时设为 `true` |
| `SMTP_HOST`、`SMTP_PORT` | 邮件服务主机与端口 |
| `SMTP_SECURE` | `true` 使用直接 TLS，通常端口 465；`false` 强制 STARTTLS，通常端口 587 |
| `SMTP_USER`、`SMTP_PASS` | SMTP 登录凭据 |
| `SMTP_FROM_EMAIL` | 经过授权的发件邮箱；未填则使用 `SMTP_USER`，必须是有效邮箱 |

证书验证不能关闭。邮件只含纯文本，不会按表单内容读取文件、访问 URL 或增加附件。
服务日志只记录成功/失败、提交编号及邮件 Message-ID，不记录访客的正文、邮箱、地址或密码。

手动启动可以使用 Node.js 20.6+ 的环境文件选项：

```sh
node --env-file=/etc/glimpse-contact.env website/server/server.js
```

`glimpse-contact.service` 是以 `/opt/glimpse-contact` 为安装目录的 systemd 示例，
部署方需核对用户、目录和环境文件，安装依赖后再启用；源码仓库中的样例不会自动安装服务。
反向代理应将 `/api/contact` 原样转发到服务，覆盖 `X-Real-IP` 为实际客户端地址，
将请求体上限设为 16 KiB，并给 SMTP 响应预留至少 60 秒。
浏览器与 API 使用同一 HTTPS origin；无需设置跨域 CORS 放行。

前端若对接网站子目录，仍应请求站点根路径 `/api/contact`，避免混入导航网关的 API 路由。

## 验证与实际投递

自动测试使用内存邮件替身，不发送邮件，覆盖输入、同源、限流、固定收件人和失败返回。
部署后先检查 `/healthz`，再由负责人提交**一封**标题或正文明确标为测试的提问，
确认 HTTP 返回及服务日志的 Message-ID；不要为了轮询结果重复发信。
SMTP 接受后可由收件人在 Gmail 收件箱或垃圾邮件中确认最终到达。

SMTP 参数与 TLS 语义依据 [Nodemailer 官方文档](https://nodemailer.com/smtp)。
