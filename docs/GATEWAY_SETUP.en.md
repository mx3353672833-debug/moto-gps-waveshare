> **Language:** English · [中文](GATEWAY_SETUP.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Waveshare edition live navigation: route gateway configuration

This page is the companion step to the [DIY guide](WAVESHARE_DIY_GUIDE.en.md). If you only want the
App demo you can configure this later; searching for real destinations, candidate routes, off-route
rerouting and traffic conditions need the service on this page.

```text
iPhone → your own HTTPS gateway → AMap Web Service
              stores the key
```

## 1. Prepare the key and the service environment

In the [AMap Open Platform Web Service key guide](https://lbs.amap.com/api/webservice/guide/create-project/get-key),
create an application and a **Web Service** type key following the current console flow, and confirm
the POI search and driving route permissions / quota. This is not the iOS SDK key, nor the web page
JavaScript key.

You need a server that can run Node.js continuously and a domain of your own. The examples below are
written for Linux + Node.js 24+ + Caddy 2; a local Mac can test the backend first, but once the
computer sleeps, shuts down or the phone leaves the local network you cannot treat it as a
continuously available public service. GitHub Pages only hosts static content and cannot run this
Node gateway.

On the server, install the tools following the [official Node.js download](https://nodejs.org/en/download)
and the [official Caddy installation instructions](https://caddyserver.com/docs/install), and prepare
Git. The server should be able to reach the AMap API. `nav.example.com` is used below as a placeholder
domain and must be replaced with your own address.

## 2. Download the code and verify the backend

Run this in the current user's home directory on the server; the backend tests do not need LVGL
initialised:

```sh
git clone https://github.com/mx3353672833-debug/moto-gps-waveshare.git
cd moto-gps-waveshare
node --version
npm --prefix backend test
cp backend/.env.example backend/.env
chmod 600 backend/.env
```

Fill in `backend/.env` with an editor, and do not post the real key to Issues:

```dotenv
MOTO_PROVIDER=amap
AMAP_WEB_SERVICE_KEY=REPLACE_WITH_YOUR_WEB_SERVICE_KEY
PORT=8787
WEB_ORIGIN=https://nav.example.com
```

`WEB_ORIGIN` is the browser origin allowed when you use the web tool, not user authentication. Even
if you use only native iOS, you should know that the public gateway currently has no App login / token
authentication; rate limiting is no substitute for access control. A personal deployment should limit
the scope of use and can sit in a controlled network reachable by your own phone. Adding Basic Auth or
interactive login would require matching changes on the client, so you cannot simply add it and expect
the current App to log in automatically. The key always stays on the server side.

Start it:

```sh
node --env-file=backend/.env backend/src/server.js
```

You should see the service listening on `127.0.0.1:8787`. Keep the terminal running and check from
another terminal on the server:

```sh
curl --fail-with-body http://127.0.0.1:8787/healthz
```

`provider: amap` and `ready_for_live_navigation: true` mean the configuration is enabled; they do not
mean the actual key and the upstream network have been verified. A search and a route request are
still needed later.

## 3. Provide an HTTPS address to the phone

Point your domain's A/AAAA records at the server, allow the 80/443 this deployment needs in the
firewall / security group you use, and make sure the ports are not occupied by another service.
Expose HTTPS only for the reverse proxy, and keep Node listening on the local machine. Open the
Caddyfile the way your installation expects (on a Linux service it is usually
`/etc/caddy/Caddyfile`) and merge the following site block into it:

```caddyfile
nav.example.com {
    handle_path /moto-gps/api/* {
        reverse_proxy 127.0.0.1:8787
    }
}
```

`handle_path` strips the `/moto-gps/api` prefix so the backend receives the real routes such as
`/healthz` and `/v1/places`. Caddy obtains and maintains the HTTPS certificate when the domain, the
port and the storage conditions are met. Sources:
[path handling](https://caddyserver.com/docs/caddyfile/directives/handle_path),
[automatic HTTPS](https://caddyserver.com/docs/automatic-https).

With the Linux Caddy service, validate the configuration first, then load it:

```sh
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

Then open your own address in iPhone Safari:

```text
https://nav.example.com/moto-gps/api/healthz
```

The phone should also be able to reach it over the cellular network (if you use a controlled network,
connect to it first). You cannot use the server's `localhost` in place of the domain. For a 404, check
the path prefix first; for a 502, check whether Node is running; for a certificate error, check the
domain resolution and the TLS configuration first.

## 4. Keep the backend running

A foreground Node process stops when the session exits. After the desktop verification, Linux can
manage it with systemd. First use `pwd` to confirm the repository's absolute path, and
`command -v node` to confirm Node's absolute path. Create the service with
`sudoedit /etc/systemd/system/moto-gps.service`, replacing every `YOUR_*`:

```ini
[Unit]
Description=MOTO GPS route gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=YOUR_LINUX_USER
WorkingDirectory=/YOUR_ABSOLUTE_PATH/moto-gps-waveshare
EnvironmentFile=/YOUR_ABSOLUTE_PATH/moto-gps-waveshare/backend/.env
ExecStart=/YOUR_ABSOLUTE_NODE_PATH /YOUR_ABSOLUTE_PATH/moto-gps-waveshare/backend/src/server.js
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
```

Use an ordinary user that can read this code for `User`, and fill the Node path with the complete
result of `command -v node`. First press Ctrl+C in the terminal running the foreground Node to release
8787, then enable the service:

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now moto-gps
sudo systemctl status moto-gps
```

If it fails to start, look at the errors with `sudo journalctl -u moto-gps -n 50 --no-pager`. This
example applies to Linux with systemd; for the Mac and hosted platforms use their own process
management.

## 5. Verify a real request and configure the App

In the terminal, with the domain replaced, test a real search:

```sh
curl --fail-with-body --get 'https://nav.example.com/moto-gps/api/v1/places' \
  --data-urlencode 'keywords=济南西站'
```

It should return place results or a service error with a clear reason; a lack of permission, an
insufficient quota or a timeout must not be treated as fixed by using the fixture. `fixture` is only
for protocol tests; `disabled` explicitly refuses online navigation.

Open `platforms/ios/project.yml` and set:

```yaml
MOTOGPSGatewayBaseURL: https://nav.example.com/moto-gps/api/
```

Go back to [the DIY guide's iPhone installation steps](WAVESHARE_DIY_GUIDE.en.md#4-install-the-app-on-the-iphone),
regenerate the project and Run. Search for a nearby destination in the App, get at least one candidate
route, then start and end one navigation. Only that confirms that the phone's location, the public
gateway, the actual key, the route request and the App's address all work together.

Replacing the key later only requires updating the server-side environment and restarting the
service; changing the domain / path the App uses requires updating the project configuration and
reinstalling. For the API fields and the request constraints see the
[backend README](../backend/README.en.md); for day-to-day operation see the
[features manual](USER_MANUAL.en.md).
