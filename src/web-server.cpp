/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

/*
 * The browser version of OBS Director, served by the plugin on TCP 4470 (see docs/PROTOCOL.md).
 *
 *   GET /, /app.js, /app.css, /icon.svg   the page, from the plugin's own resources
 *   GET /ws (Upgrade: websocket)          the control channel: the same JSON messages as
 *                                         TCP 4466, one per text frame; the mix ("ODA1")
 *                                         and the live output ("ODV1") as binary frames
 *
 * A browser pairs like a phone: it says hello with its own random ID, and OBS asks once.
 * The page is only served under a name this PC answers to (its IP address, localhost or its
 * computer name), so a web page elsewhere can't reach it by pointing its own name at this PC.
 */

#include "director-server.hpp"

#include "udp-audio.hpp"
#include "video-preview.hpp"

#include <obs-module.h>
#include <plugin-support.h>

#include <QCryptographicHash>
#include <QFile>
#include <QHostAddress>
#include <QJsonDocument>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimer>

namespace {

constexpr int kMaxRequestBytes = 8 * 1024;
constexpr int kMaxHttpPending = 16;
constexpr qint64 kMaxFramePayload = 64 * 1024;
constexpr qint64 kAudioBacklog = 256 * 1024;  /* a browser this far behind skips audio */
constexpr qint64 kVideoBacklog = 1024 * 1024; /* and this far behind waits for a keyframe */

struct WebFile {
	const char *path;
	const char *resource;
	const char *type;
};

const WebFile kFiles[] = {
	{"/", ":/web/index.html", "text/html; charset=utf-8"},
	{"/index.html", ":/web/index.html", "text/html; charset=utf-8"},
	{"/app.js", ":/web/app.js", "text/javascript; charset=utf-8"},
	{"/app.css", ":/web/app.css", "text/css; charset=utf-8"},
	{"/icon.svg", ":/web/icon.svg", "image/svg+xml"},
	{"/tap.wav", ":/web/tap.wav", "audio/wav"},
};

QByteArray statusLine(int code)
{
	switch (code) {
	case 200:
		return "200 OK";
	case 400:
		return "400 Bad Request";
	case 403:
		return "403 Forbidden";
	case 404:
		return "404 Not Found";
	case 405:
		return "405 Method Not Allowed";
	case 421:
		return "421 Misdirected Request";
	default:
		return "500 Internal Server Error";
	}
}

void respond(QTcpSocket *sock, int code, const QByteArray &type, const QByteArray &body,
	     const QByteArray &extraHeaders = QByteArray())
{
	QByteArray out = "HTTP/1.1 " + statusLine(code) + "\r\n";
	out += "Content-Type: " + type + "\r\n";
	out += "Content-Length: " + QByteArray::number(body.size()) + "\r\n";
	out += "Cache-Control: no-cache\r\n";
	out += "X-Content-Type-Options: nosniff\r\n";
	out += "Referrer-Policy: no-referrer\r\n";
	out += "X-Frame-Options: DENY\r\n";
	out += extraHeaders;
	out += "Connection: close\r\n\r\n";
	out += body;
	sock->write(out);
	sock->disconnectFromHost(); /* after what's written has gone */
}

QByteArray hostName(const QByteArray &host)
{
	/* "192.168.1.24:4470" -> "192.168.1.24"; "[::1]:4470" -> "::1" */
	if (host.startsWith('['))
		return host.mid(1, host.indexOf(']') - 1);
	int colon = host.lastIndexOf(':');
	return colon >= 0 ? host.left(colon) : host;
}

} // namespace

/* ---------------- HTTP ---------------- */

QString DirectorServer::webAddress() const
{
	const QString a = advertisedAddress();
	return a.isEmpty() ? QString() : QStringLiteral("http://%1:%2").arg(a).arg(kWebPort);
}

bool DirectorServer::allowedHost(const QByteArray &host) const
{
	const QString name = QString::fromLatin1(hostName(host)).toLower();
	if (name.isEmpty())
		return false;
	QHostAddress ip;
	if (ip.setAddress(name))
		return true; /* an address typed in, or the one OBS shows */
	const QString pc = pcName().toLower();
	return name == QLatin1String("localhost") || name == pc || name == pc + QLatin1String(".local") ||
	       name == pc + QLatin1String(".lan") || name == pc + QLatin1String(".home");
}

void DirectorServer::onWebConnection()
{
	while (QTcpSocket *sock = webServer->nextPendingConnection()) {
		if (httpPending.size() >= kMaxHttpPending) {
			sock->abort();
			sock->deleteLater();
			continue;
		}
		httpPending.insert(sock, QByteArray());
		connect(sock, &QTcpSocket::readyRead, this, [this, sock]() { onHttpReadyRead(sock); });
		connect(sock, &QTcpSocket::disconnected, this, [this, sock]() {
			if (httpPending.remove(sock))
				sock->deleteLater();
		});
		/* a request has 10 s to arrive */
		QTimer::singleShot(10000, sock, [this, sock]() {
			if (httpPending.contains(sock))
				sock->abort();
		});
	}
}

void DirectorServer::onHttpReadyRead(QTcpSocket *sock)
{
	auto it = httpPending.find(sock);
	if (it == httpPending.end())
		return;
	it->append(sock->read(kMaxRequestBytes + 1 - it->size()));
	int end = it->indexOf("\r\n\r\n");
	if (end < 0 || end > kMaxRequestBytes) {
		if (it->size() > kMaxRequestBytes) {
			httpPending.erase(it);
			disconnect(sock, nullptr, this, nullptr);
			connect(sock, &QTcpSocket::disconnected, sock, &QObject::deleteLater);
			respond(sock, 400, "text/plain", "Request too large\n");
		}
		return;
	}
	const QByteArray head = it->left(end);
	const QByteArray rest = it->mid(end + 4);
	httpPending.erase(it);
	disconnect(sock, &QTcpSocket::readyRead, this, nullptr);
	disconnect(sock, &QTcpSocket::disconnected, this, nullptr);
	connect(sock, &QTcpSocket::disconnected, sock, &QObject::deleteLater);

	const QList<QByteArray> lines = head.split('\n');
	const QList<QByteArray> request = lines.value(0).trimmed().split(' ');
	if (request.size() != 3 || !request[2].startsWith("HTTP/1."))
		return respond(sock, 400, "text/plain", "Bad request\n");
	QHash<QByteArray, QByteArray> headers;
	for (int i = 1; i < lines.size(); i++) {
		const QByteArray l = lines[i].trimmed();
		int colon = l.indexOf(':');
		if (colon > 0)
			headers.insert(l.left(colon).trimmed().toLower(), l.mid(colon + 1).trimmed());
	}
	const QByteArray method = request[0];
	QByteArray path = request[1];
	if (int q = path.indexOf('?'); q >= 0)
		path = path.left(q);
	const QByteArray host = headers.value("host");

	if (!allowedHost(host))
		return respond(sock, 421, "text/plain",
			       "Open OBS Director by the address OBS shows under Tools, OBS Director.\n");
	if (method != "GET")
		return respond(sock, 405, "text/plain", "Only GET\n", "Allow: GET\r\n");

	if (path == "/ws") {
		/* Only this page may open the control channel: a page from anywhere else is refused. */
		const QByteArray origin = headers.value("origin");
		if (!origin.isEmpty() && origin != "http://" + host)
			return respond(sock, 403, "text/plain", "Not from this page\n");
		if (headers.value("upgrade").toLower() != "websocket" ||
		    !headers.value("connection").toLower().contains("upgrade") ||
		    headers.value("sec-websocket-version") != "13" || headers.value("sec-websocket-key").isEmpty())
			return respond(sock, 400, "text/plain", "WebSocket only\n");
		return upgradeToWebSocket(sock, headers.value("sec-websocket-key"), rest);
	}
	serveFile(sock, path, host);
}

void DirectorServer::serveFile(QTcpSocket *sock, const QByteArray &path, const QByteArray &host)
{
	for (const WebFile &f : kFiles) {
		if (path != f.path)
			continue;
		QFile file(QString::fromLatin1(f.resource));
		if (!file.open(QIODevice::ReadOnly))
			return respond(sock, 500, "text/plain", "Missing\n");
		/* Everything the page loads comes from here; it talks only to this PC. */
		const QByteArray csp =
			"Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; "
			"img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' ws://" +
			host + "; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n";
		QByteArray body = file.readAll();
		if (path == "/app.js")
			body.replace("@PLUGIN_VERSION@", QByteArray(PLUGIN_VERSION));
		return respond(sock, 200, f.type, body, csp);
	}
	respond(sock, 404, "text/plain", "Not found\n");
}

/* ---------------- WebSocket ---------------- */

void DirectorServer::upgradeToWebSocket(QTcpSocket *sock, const QByteArray &key, const QByteArray &rest)
{
	const QByteArray accept =
		QCryptographicHash::hash(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11", QCryptographicHash::Sha1)
			.toBase64();
	sock->write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
		    "Sec-WebSocket-Accept: " +
		    accept + "\r\n\r\n");
	disconnect(sock, &QTcpSocket::disconnected, sock, &QObject::deleteLater);
	acceptClient(sock, true);
	auto it = clients.find(sock);
	if (it == clients.end())
		return;
	if (!rest.isEmpty()) {
		it->wsBuf = rest;
		readWebSocket(sock);
	}
}

void DirectorServer::wsWrite(QTcpSocket *sock, quint8 opcode, const QByteArray &payload)
{
	if (!sock || sock->state() != QAbstractSocket::ConnectedState)
		return;
	QByteArray frame;
	frame.reserve(payload.size() + 10);
	frame.append(char(0x80 | opcode));
	const qint64 n = payload.size();
	if (n < 126) {
		frame.append(char(n));
	} else if (n <= 0xffff) {
		frame.append(char(126));
		frame.append(char((n >> 8) & 0xff));
		frame.append(char(n & 0xff));
	} else {
		frame.append(char(127));
		for (int i = 7; i >= 0; i--)
			frame.append(char((quint64(n) >> (8 * i)) & 0xff));
	}
	frame.append(payload);
	sock->write(frame);
}

void DirectorServer::readWebSocket(QTcpSocket *sock)
{
	auto it = clients.find(sock);
	if (it == clients.end())
		return;
	it->wsBuf.append(sock->readAll());
	for (;;) {
		it = clients.find(sock);
		if (it == clients.end())
			return;
		QByteArray &buf = it->wsBuf;
		if (buf.size() < 2)
			return;
		const quint8 b0 = quint8(buf[0]), b1 = quint8(buf[1]);
		const bool fin = b0 & 0x80;
		const quint8 op = b0 & 0x0f;
		qint64 len = b1 & 0x7f;
		int pos = 2;
		if (!(b1 & 0x80))
			return sock->abort(); /* a browser always masks what it sends */
		if (len == 126) {
			if (buf.size() < 4)
				return;
			len = (qint64(quint8(buf[2])) << 8) | quint8(buf[3]);
			pos = 4;
		} else if (len == 127) {
			return sock->abort(); /* nothing the page sends is that large */
		}
		if (len > kMaxFramePayload)
			return sock->abort();
		if (buf.size() < pos + 4 + len)
			return;
		const QByteArray mask = buf.mid(pos, 4);
		QByteArray payload = buf.mid(pos + 4, int(len));
		for (int i = 0; i < payload.size(); i++)
			payload[i] = char(payload[i] ^ mask[i % 4]);
		buf.remove(0, pos + 4 + int(len));

		if (!fin)
			return sock->abort(); /* the page never splits a message */
		if (op == 0x8) {
			wsWrite(sock, 0x8, payload.left(2));
			sock->disconnectFromHost();
			return;
		}
		if (op == 0x9) {
			wsWrite(sock, 0xA, payload);
			continue;
		}
		if (op == 0xA)
			continue;
		if (op != 0x1)
			return sock->abort();
		QJsonParseError err;
		QJsonDocument doc = QJsonDocument::fromJson(payload, &err);
		if (err.error != QJsonParseError::NoError || !doc.isObject())
			continue;
		handleMessage(*it, doc.object());
	}
}

/* ---------------- the mix for browsers ---------------- */

void DirectorServer::updateBrowserAudio()
{
	bool any = false;
	for (auto it = clients.constBegin(); it != clients.constEnd(); ++it)
		any |= it->authed && it->ws && it->wsAudio;
	audio->setTapEnabled(any);
}

void DirectorServer::sendAudioToBrowsers(const QByteArray &packet)
{
	for (auto it = clients.begin(); it != clients.end(); ++it) {
		if (!it->authed || !it->ws || !it->wsAudio)
			continue;
		/* a browser that has fallen behind skips audio rather than drifting further back */
		if (it->sock->bytesToWrite() > kAudioBacklog)
			continue;
		wsWrite(it->sock, 0x2, packet);
	}
}

/* Shared with sendVideoFrame in director-server.cpp: frames for browsers watching. */
void DirectorServer::sendVideoToBrowsers(const QByteArray &frame)
{
	const bool config = frame.size() > 8 && (frame.at(8) & 1);
	const bool key = frame.size() > 8 && (frame.at(8) & 2);
	for (auto it = clients.begin(); it != clients.end(); ++it) {
		if (!it->authed || !it->ws || !it->wsVideo)
			continue;
		if (!config && it->sock->bytesToWrite() > kVideoBacklog)
			it->wsVideoNeedKey = true; /* dropping a frame spoils the rest until a keyframe */
		if (it->wsVideoNeedKey && !config) {
			if (!key || it->sock->bytesToWrite() > kVideoBacklog)
				continue;
			it->wsVideoNeedKey = false;
		}
		wsWrite(it->sock, 0x2, frame);
	}
}
