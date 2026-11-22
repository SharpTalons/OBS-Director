/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#include "director-server.hpp"

#include "desk-dock.hpp"
#include "udp-audio.hpp"
#include "video-preview.hpp"

#include <obs-module.h>
#include <obs.h>
#include <plugin-support.h>
#include <util/bmem.h>
#include <util/platform.h>

#include <QDateTime>
#include <QFileInfo>
#include <QHostAddress>
#include <QHostInfo>
#include <QApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMainWindow>
#include <QMessageBox>
#include <QPushButton>
#include <QRegularExpression>
#include <QUuid>
#include <QNetworkDatagram>
#include <QNetworkInterface>
#include <QRandomGenerator>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUdpSocket>

namespace {

QString ipv4Of(const QHostAddress &addr)
{
	bool ok = false;
	quint32 v4 = addr.toIPv4Address(&ok);
	return ok ? QHostAddress(v4).toString() : addr.toString();
}

QString outputPath(obs_output_t *out)
{
	if (!out)
		return QString();
	obs_data_t *s = obs_output_get_settings(out);
	/* Only a file path: a custom FFmpeg "url" output can hold a stream key. */
	QString p = QString::fromUtf8(obs_data_get_string(s, "path"));
	obs_data_release(s);
	return p;
}

void putLen(QByteArray &b, quint32 v)
{
	for (int i = 0; i < 4; i++)
		b.append(char((v >> (8 * i)) & 0xff));
}

void put64(QByteArray &b, qint64 v)
{
	for (int i = 0; i < 8; i++)
		b.append(char((quint64(v) >> (8 * i)) & 0xff));
}

/* Compares without stopping at the first difference, so timing says nothing about a phone ID. */
bool sameSecret(const QString &a, const QString &b)
{
	const QByteArray x = a.toUtf8(), y = b.toUtf8();
	if (x.size() != y.size())
		return false;
	unsigned char diff = 0;
	for (int i = 0; i < x.size(); i++)
		diff |= static_cast<unsigned char>(x[i] ^ y[i]);
	return diff == 0;
}

constexpr int kMaxUnpaired = 8;
constexpr int kMaxPrompts = 3;              /* Allow/Deny prompts open at once */
constexpr int kMaxTrusted = 50;             /* approved phones remembered */
constexpr qint64 kApprovalWaitMs = 120000;  /* how long a phone waits for an answer */
constexpr qint64 kDeniedWaitMs = 60000;     /* after Deny, before that address may ask again */
constexpr qint64 kUnansweredWaitMs = 30000; /* after no answer */
constexpr int kMaxProbeRepliesPerSec = 20;

} // namespace

DirectorServer::DirectorServer(DeskDock *d, QObject *parent)
	: QObject(parent),
	  dock(d),
	  server(new QTcpServer(this)),
	  videoServer(new QTcpServer(this)),
	  beacon(new QUdpSocket(this)),
	  audio(std::make_unique<UdpAudio>())
{
	loadConfig();

	preview = std::make_unique<VideoPreview>([this](const uint8_t *data, size_t size, int64_t ptsUs, bool key,
							bool config) {
		/* Called on the encoder thread: frame it here, write it on the Qt thread. */
		QByteArray frame;
		frame.reserve(int(size) + 17);
		frame.append("ODV1", 4);
		putLen(frame, quint32(size));
		frame.append(char((config ? 1 : 0) | (key ? 2 : 0)));
		put64(frame, ptsUs);
		frame.append(reinterpret_cast<const char *>(data), int(size));
		QMetaObject::invokeMethod(this, [this, frame]() { sendVideoFrame(frame); }, Qt::QueuedConnection);
	});

	connect(server, &QTcpServer::newConnection, this, &DirectorServer::onNewConnection);
	if (!server->listen(QHostAddress::AnyIPv4, kControlPort))
		obs_log(LOG_ERROR, "could not listen on TCP %d: %s", kControlPort,
			server->errorString().toUtf8().constData());

	connect(videoServer, &QTcpServer::newConnection, this, &DirectorServer::onVideoConnection);
	if (!videoServer->listen(QHostAddress::AnyIPv4, kVideoPort))
		obs_log(LOG_WARNING, "could not listen on TCP %d for video", kVideoPort);

	cpu = os_cpu_usage_info_start();

	connect(&statusTimer, &QTimer::timeout, this, &DirectorServer::sendStatus);
	statusTimer.start(250);
	/* Phones can also ask "who's there?" on the beacon port, to name a PC typed in by
	 * address and to answer a Search at once. */
	if (beacon->bind(QHostAddress::AnyIPv4, kBeaconPort, QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint))
		connect(beacon, &QUdpSocket::readyRead, this, &DirectorServer::onBeaconReadyRead);
	else
		obs_log(LOG_WARNING, "could not listen on UDP %d for probes; the beacon still works", kBeaconPort);
	connect(&beaconTimer, &QTimer::timeout, this, &DirectorServer::sendBeacon);
	beaconTimer.start(2000);
	sendBeacon();

	if (obs_frontend_recording_active()) {
		audio->recordingStarted();
		audio->recordingPaused(obs_frontend_recording_paused());
	}
	if (obs_frontend_streaming_active())
		streamStartMs = QDateTime::currentMSecsSinceEpoch();

	updatePresence();
	connect(dock, &DeskDock::replied, this, [this](const QString &id, const QString &text) {
		QJsonObject data;
		data["id"] = id;
		data["text"] = text;
		event("desk.reply", data);
	});

	obs_log(LOG_INFO, "listening on TCP %d (control), %d (video); beacon on UDP %d", kControlPort, kVideoPort,
		kBeaconPort);
}

DirectorServer::~DirectorServer()
{
	statusTimer.stop();
	beaconTimer.stop();
	preview->stop();
	audio->clearDestinations();
	for (auto it = clients.begin(); it != clients.end(); ++it) {
		if (it->videoSock)
			it->videoSock->close();
		it->sock->close();
	}
	if (cpu)
		os_cpu_usage_info_destroy(cpu);
}

/* ---------------- config: approved phones ---------------- */

void DirectorServer::loadConfig()
{
	char *path = obs_module_config_path("config.json");
	obs_data_t *data = path ? obs_data_create_from_json_file_safe(path, "bak") : nullptr;
	bfree(path);
	if (data)
		pcId = QString::fromUtf8(obs_data_get_string(data, "pcId"));
	if (pcId.isEmpty()) {
		pcId = QUuid::createUuid().toString(QUuid::WithoutBraces);
		saveConfig();
	}
	if (!data)
		return;
	obs_data_array_t *arr = obs_data_get_array(data, "devices");
	for (size_t i = 0, n = arr ? obs_data_array_count(arr) : 0; i < n; i++) {
		obs_data_t *d = obs_data_array_item(arr, i);
		TrustedDevice t;
		t.id = QString::fromUtf8(obs_data_get_string(d, "id"));
		t.name = QString::fromUtf8(obs_data_get_string(d, "name"));
		t.approvedAt = obs_data_get_int(d, "approvedAt");
		if (!t.id.isEmpty())
			trusted << t;
		obs_data_release(d);
	}
	obs_data_array_release(arr);
	obs_data_release(data);
}

void DirectorServer::saveConfig()
{
	char *dir = obs_module_config_path("");
	if (dir) {
		os_mkdirs(dir);
		bfree(dir);
	}
	char *path = obs_module_config_path("config.json");
	if (!path)
		return;
	obs_data_t *data = obs_data_create();
	obs_data_set_string(data, "pcId", pcId.toUtf8().constData());
	obs_data_array_t *arr = obs_data_array_create();
	for (const TrustedDevice &t : trusted) {
		obs_data_t *d = obs_data_create();
		obs_data_set_string(d, "id", t.id.toUtf8().constData());
		obs_data_set_string(d, "name", t.name.toUtf8().constData());
		obs_data_set_int(d, "approvedAt", t.approvedAt);
		obs_data_array_push_back(arr, d);
		obs_data_release(d);
	}
	obs_data_set_array(data, "devices", arr);
	obs_data_array_release(arr);
	obs_data_save_json_safe(data, path, "tmp", "bak");
	obs_data_release(data);
	bfree(path);
}

void DirectorServer::forgetDevice(const QString &id)
{
	for (int i = trusted.size() - 1; i >= 0; i--)
		if (trusted[i].id == id)
			trusted.removeAt(i);
	saveConfig();
	/* a forgotten phone that's connected now is let go at once */
	for (auto it = clients.begin(); it != clients.end(); ++it)
		if (it->authed && it->deviceId == id)
			it->sock->disconnectFromHost();
	emit devicesChanged();
}

void DirectorServer::forgetAllDevices()
{
	trusted.clear();
	saveConfig();
	for (auto it = clients.begin(); it != clients.end(); ++it)
		if (it->authed)
			it->sock->disconnectFromHost();
	emit devicesChanged();
}

namespace {

bool isUsableLan(const QHostAddress &a)
{
	if (a.protocol() != QAbstractSocket::IPv4Protocol || a.isLoopback() || a.isNull())
		return false;
	/* 169.254.x.x is what Windows gives an adapter with no network: never useful */
	return !a.isInSubnet(QHostAddress(QStringLiteral("169.254.0.0")), 16);
}

bool isPrivate(const QHostAddress &a)
{
	return a.isInSubnet(QHostAddress(QStringLiteral("192.168.0.0")), 16) ||
	       a.isInSubnet(QHostAddress(QStringLiteral("10.0.0.0")), 8) ||
	       a.isInSubnet(QHostAddress(QStringLiteral("172.16.0.0")), 12);
}

/* Hyper-V, WSL, VPN and VM adapters: a phone on the Wi-Fi can't reach these. */
bool isVirtual(const QNetworkInterface &iface)
{
	if (iface.type() == QNetworkInterface::Virtual || iface.type() == QNetworkInterface::Loopback)
		return true;
	const QString n = (iface.humanReadableName() + QLatin1Char(' ') + iface.name()).toLower();
	static const char *const kVirtual[] = {"vethernet", "hyper-v",   "wsl",      "virtualbox", "vmware",
					       "vpn",       "tailscale", "zerotier", "hamachi",    "bluetooth",
					       "tap-",      "tun",       "docker"};
	for (const char *k : kVirtual)
		if (n.contains(QLatin1String(k)))
			return true;
	return false;
}

} // namespace

QString DirectorServer::primaryAddress() const
{
	/* The address Windows would send through to reach the network: connecting a UDP
	 * socket only picks a route, it sends nothing. */
	QUdpSocket probe;
	probe.connectToHost(QHostAddress(QStringLiteral("8.8.8.8")), 53);
	if (probe.waitForConnected(300) && isUsableLan(probe.localAddress()) && isPrivate(probe.localAddress()))
		return ipv4Of(probe.localAddress());

	/* No route out (a LAN without internet): the first real, private adapter address. */
	QString fallback;
	for (const QNetworkInterface &iface : QNetworkInterface::allInterfaces()) {
		if (!(iface.flags() & QNetworkInterface::IsUp) || !(iface.flags() & QNetworkInterface::IsRunning) ||
		    isVirtual(iface))
			continue;
		for (const QNetworkAddressEntry &e : iface.addressEntries()) {
			if (!isUsableLan(e.ip()))
				continue;
			if (isPrivate(e.ip()))
				return e.ip().toString();
			if (fallback.isEmpty())
				fallback = e.ip().toString();
		}
	}
	return fallback;
}

QString DirectorServer::advertisedAddress() const
{
	/* Keep showing the address phones reached us on while this PC still has it. */
	if (!phoneAddress.isEmpty())
		for (const QHostAddress &a : QNetworkInterface::allAddresses())
			if (ipv4Of(a) == phoneAddress)
				return phoneAddress;
	return primaryAddress();
}

void DirectorServer::notePhoneSeen(const QHostAddress &phone, const QHostAddress &local)
{
	/* Which of this PC's addresses is on the phone's network? */
	QString mine = local.isNull() ? QString() : ipv4Of(local);
	if (mine.isEmpty() || mine == QLatin1String("0.0.0.0")) {
		QHostAddress p(ipv4Of(phone));
		for (const QNetworkInterface &iface : QNetworkInterface::allInterfaces())
			for (const QNetworkAddressEntry &e : iface.addressEntries())
				if (e.ip().protocol() == QAbstractSocket::IPv4Protocol && e.prefixLength() > 0 &&
				    p.isInSubnet(e.ip(), e.prefixLength()))
					mine = e.ip().toString();
	}
	if (mine.isEmpty() || mine == phoneAddress)
		return;
	phoneAddress = mine;
	emit addressChanged();
}

QString DirectorServer::pcName() const
{
	QString n = QHostInfo::localHostName();
	return n.isEmpty() ? QStringLiteral("OBS PC") : n;
}

/* ---------------- pairing: approve once in OBS ---------------- */

bool DirectorServer::lockedOut(const QString &ip)
{
	auto it = lockouts.find(ip);
	return it != lockouts.end() && QDateTime::currentMSecsSinceEpoch() < it.value();
}

void DirectorServer::lockOut(const QString &ip, qint64 ms)
{
	qint64 now = QDateTime::currentMSecsSinceEpoch();
	lockouts[ip] = now + ms;
	if (lockouts.size() > 256) {
		/* forget addresses whose wait has passed, so the table can't grow without end */
		for (auto it = lockouts.begin(); it != lockouts.end();)
			it = (it.value() < now) ? lockouts.erase(it) : std::next(it);
	}
}

int DirectorServer::openPrompts() const
{
	int n = 0;
	for (auto it = clients.constBegin(); it != clients.constEnd(); ++it)
		if (it->pending)
			n++;
	return n;
}

bool DirectorServer::isTrusted(const QString &id) const
{
	bool found = false;
	for (const TrustedDevice &t : trusted)
		found |= sameSecret(t.id, id); /* no early exit: timing says nothing about the list */
	return found;
}

void DirectorServer::trust(const Client &c)
{
	if (isTrusted(c.deviceId))
		return;
	TrustedDevice t;
	t.id = c.deviceId;
	t.name = c.device;
	t.approvedAt = QDateTime::currentMSecsSinceEpoch();
	trusted << t;
	while (trusted.size() > kMaxTrusted)
		trusted.removeFirst();
	saveConfig();
	emit devicesChanged();
}

void DirectorServer::refuse(Client &c, const char *code, const QString &message, qint64 lockMs)
{
	QJsonObject e;
	e["type"] = "error";
	e["code"] = code;
	e["message"] = message;
	send(c.sock, e);
	if (lockMs > 0)
		lockOut(c.ip, lockMs);
	c.pending = false;
	if (c.prompt) {
		QMessageBox *box = c.prompt;
		c.prompt = nullptr;
		box->close();
	}
	c.sock->disconnectFromHost();
}

void DirectorServer::askApproval(Client &c)
{
	for (auto it = clients.constBegin(); it != clients.constEnd(); ++it)
		if (it->pending && (it->ip == c.ip || it->deviceId == c.deviceId))
			return refuse(c, "busy", tr("This phone is already waiting for someone to press Allow in OBS."),
				      0);
	if (openPrompts() >= kMaxPrompts)
		return refuse(c, "busy", tr("OBS is already asking about other phones. Try again in a moment."), 0);

	c.pending = true;
	QJsonObject p;
	p["type"] = "pending";
	p["message"] = tr("Waiting for someone to press Allow in OBS on %1.").arg(pcName());
	send(c.sock, p);

	auto *main = static_cast<QWidget *>(obs_frontend_get_main_window());
	auto *box = new QMessageBox(main);
	box->setWindowTitle(QStringLiteral("OBS Director"));
	box->setIcon(QMessageBox::Question);
	box->setTextFormat(Qt::PlainText); /* the name comes from the phone */
	box->setText(tr("Allow \"%1\" to connect?").arg(c.device));
	box->setInformativeText(
		tr("A phone at %1 wants to connect. It will be able to start and stop the recording, "
		   "listen to the mix and send messages to this dock. Once allowed, it connects by itself "
		   "next time; you can remove it under Tools, OBS Director.")
			.arg(c.ip));
	QPushButton *allow = box->addButton(tr("Allow"), QMessageBox::AcceptRole);
	QPushButton *deny = box->addButton(tr("Deny"), QMessageBox::RejectRole);
	box->setDefaultButton(deny);
	box->setEscapeButton(deny);
	box->setAttribute(Qt::WA_DeleteOnClose);
	box->setWindowModality(Qt::NonModal);
	c.prompt = box;

	QTcpSocket *sock = c.sock;
	connect(box, &QMessageBox::buttonClicked, this, [this, sock, allow](QAbstractButton *b) {
		auto it = clients.find(sock);
		if (it == clients.end() || !it->pending)
			return;
		it->prompt = nullptr;
		if (b == allow) {
			trust(*it);
			admit(*it);
		} else {
			dock->addFeed(tr("%1 was not allowed to connect").arg(it->device));
			refuse(*it, "denied",
			       tr("OBS on this PC said no. Ask the person at the PC to allow this phone."),
			       kDeniedWaitMs);
		}
	});
	QTimer::singleShot(kApprovalWaitMs, this, [this, sock]() {
		auto it = clients.find(sock);
		if (it != clients.end() && it->pending)
			refuse(*it, "approval_timeout",
			       tr("Nobody pressed Allow in OBS. Try again when someone is at the PC."),
			       kUnansweredWaitMs);
	});

	box->show();
	box->raise();
	box->activateWindow();
	QApplication::alert(main);
	dock->addFeed(tr("%1 asks to connect").arg(c.device));
	obs_log(LOG_INFO, "a phone at %s asks to connect", c.ip.toUtf8().constData());
}

void DirectorServer::admit(Client &c)
{
	c.pending = false;
	c.authed = true;
	lockouts.remove(c.ip);
	for (TrustedDevice &t : trusted)
		if (t.id == c.deviceId && t.name != c.device) {
			t.name = c.device; /* the phone was renamed */
			saveConfig();
			emit devicesChanged();
		}

	QJsonObject w;
	w["type"] = "welcome";
	w["proto"] = kProtocol;
	w["pc"] = pcName();
	w["obs"] = QString::fromUtf8(obs_get_version_string());
	w["plugin"] = QString::fromUtf8(PLUGIN_VERSION);
	w["audioRate"] = int(UdpAudio::kSampleRate);
	w["videoPort"] = kVideoPort;
	send(c.sock, w);
	send(c.sock, buildStatus());
	updatePresence();
	dock->addFeed(tr("%1 connected").arg(c.device));
}

int DirectorServer::unpairedCount() const
{
	int n = 0;
	for (auto it = clients.constBegin(); it != clients.constEnd(); ++it)
		if (!it->authed)
			n++;
	return n;
}

/* ---------------- connections ---------------- */

void DirectorServer::onNewConnection()
{
	while (QTcpSocket *sock = server->nextPendingConnection()) {
		/* too many half-open pairings at once: refuse, so nobody can tie up the server */
		if (unpairedCount() >= kMaxUnpaired) {
			sock->abort();
			sock->deleteLater();
			continue;
		}
		sock->setSocketOption(QAbstractSocket::LowDelayOption, 1);
		Client c;
		c.sock = sock;
		c.ip = ipv4Of(sock->peerAddress());
		notePhoneSeen(sock->peerAddress(), sock->localAddress());
		c.connectedAt = QDateTime::currentMSecsSinceEpoch();
		clients.insert(sock, c);
		connect(sock, &QTcpSocket::readyRead, this, [this, sock]() { onReadyRead(sock); });
		connect(sock, &QTcpSocket::disconnected, this, [this, sock]() { onDisconnected(sock); });
		/* unpaired sockets get 10 s to say hello (a phone waiting for Allow has its own timer) */
		QTimer::singleShot(10000, this, [this, sock]() {
			auto it = clients.find(sock);
			if (it != clients.end() && !it->authed && !it->pending)
				sock->disconnectFromHost();
		});
	}
}

void DirectorServer::onDisconnected(QTcpSocket *sock)
{
	auto it = clients.find(sock);
	if (it == clients.end())
		return;
	if (it->listening)
		audio->removeDestination(it->ip.toStdString(), it->audioPort);
	if (it->videoSock)
		it->videoSock->close();
	if (it->prompt) {
		/* the phone gave up waiting: take the question away (not counted as Deny) */
		it->pending = false;
		QMessageBox *box = it->prompt;
		it->prompt = nullptr;
		box->close();
	}
	QString device = it->device;
	bool authed = it->authed;
	clients.erase(it);
	sock->deleteLater();
	updateVideo();
	updatePresence();
	if (authed)
		dock->addFeed(tr("%1 disconnected").arg(device));
}

void DirectorServer::onReadyRead(QTcpSocket *sock)
{
	auto it = clients.find(sock);
	if (it == clients.end())
		return;
	while (sock->canReadLine()) {
		QByteArray line = sock->readLine(64 * 1024).trimmed();
		if (line.isEmpty())
			continue;
		QJsonParseError err;
		QJsonDocument doc = QJsonDocument::fromJson(line, &err);
		if (err.error != QJsonParseError::NoError || !doc.isObject())
			continue;
		handleMessage(*it, doc.object());
		it = clients.find(sock);
		if (it == clients.end())
			return;
	}
	if (sock->bytesAvailable() > 256 * 1024)
		sock->disconnectFromHost();
}

void DirectorServer::send(QTcpSocket *sock, const QJsonObject &msg)
{
	if (!sock || sock->state() != QAbstractSocket::ConnectedState)
		return;
	QByteArray out = QJsonDocument(msg).toJson(QJsonDocument::Compact);
	out.append('\n');
	sock->write(out);
}

void DirectorServer::broadcast(const QJsonObject &msg)
{
	for (auto it = clients.begin(); it != clients.end(); ++it)
		if (it->authed)
			send(it->sock, msg);
}

void DirectorServer::event(const QString &name, const QJsonObject &data)
{
	QJsonObject msg;
	msg["type"] = "event";
	msg["event"] = name;
	msg["data"] = data;
	broadcast(msg);
}

void DirectorServer::result(Client &c, int id, bool ok, const QJsonObject &data, const QString &error)
{
	QJsonObject msg;
	msg["type"] = "result";
	msg["id"] = id;
	msg["ok"] = ok;
	if (!data.isEmpty())
		msg["data"] = data;
	if (!error.isEmpty())
		msg["error"] = error;
	send(c.sock, msg);
}

void DirectorServer::handleMessage(Client &c, const QJsonObject &msg)
{
	const QString type = msg.value("type").toString();

	if (!c.authed) {
		if (type != "hello" || c.pending)
			return;
		if (lockedOut(c.ip))
			return refuse(c, "locked_out",
				      tr("OBS said no to this phone a moment ago. Wait a minute, then try again."), 0);
		c.device = msg.value("device").toString().trimmed().left(40);
		if (c.device.isEmpty())
			c.device = tr("A phone");
		c.deviceId = msg.value("deviceId").toString();
		static const QRegularExpression idPattern(QStringLiteral("^[A-Za-z0-9-]{16,64}$"));
		if (!idPattern.match(c.deviceId).hasMatch())
			return refuse(c, "update_app",
				      tr("Update the OBS Director app on this phone to connect to this PC."), 0);
		if (isTrusted(c.deviceId))
			admit(c);
		else
			askApproval(c);
		return;
	}

	if (type == "cmd")
		handleCommand(c, msg.value("id").toInt(), msg.value("cmd").toString(), msg.value("args").toObject());
	else if (type == "ping") {
		QJsonObject p;
		p["type"] = "pong";
		p["t"] = msg.value("t");
		send(c.sock, p);
	}
}

void DirectorServer::handleCommand(Client &c, int id, const QString &cmd, const QJsonObject &args)
{
	if (cmd == "record.start") {
		if (obs_frontend_recording_active())
			return result(c, id, true);
		pendingRecordBy = c.device;
		obs_frontend_recording_start();
		return result(c, id, true);
	}
	if (cmd == "record.stop") {
		if (!obs_frontend_recording_active())
			return result(c, id, true);
		lastStopBy = c.device;
		obs_frontend_recording_stop();
		return result(c, id, true);
	}
	if (cmd == "record.pause" || cmd == "record.resume") {
		if (!obs_frontend_recording_active())
			return result(c, id, false, {}, "not_recording");
		obs_frontend_recording_pause(cmd == "record.pause");
		return result(c, id, true);
	}
	if (cmd == "stream.stop") {
		if (obs_frontend_streaming_active())
			obs_frontend_streaming_stop();
		dock->addFeed(tr("%1 ended the stream").arg(c.device));
		return result(c, id, true);
	}
	if (cmd == "audio.start") {
		int port = args.value("port").toInt();
		if (port <= 0 || port > 65535)
			return result(c, id, false, {}, "bad_port");
		if (c.listening)
			audio->removeDestination(c.ip.toStdString(), c.audioPort);
		c.audioPort = quint16(port);
		c.listening = true;
		audio->addDestination(c.ip.toStdString(), c.audioPort);
		updatePresence();
		QJsonObject d;
		d["rate"] = int(UdpAudio::kSampleRate);
		d["channels"] = int(UdpAudio::kChannels);
		d["frames"] = int(UdpAudio::kFramesPerPacket);
		return result(c, id, true, d);
	}
	if (cmd == "audio.stop") {
		if (c.listening)
			audio->removeDestination(c.ip.toStdString(), c.audioPort);
		c.listening = false;
		updatePresence();
		return result(c, id, true);
	}
	if (cmd == "video.start") {
		c.videoToken = QString::number(QRandomGenerator::global()->generate64(), 16);
		QJsonObject d;
		d["token"] = c.videoToken;
		d["port"] = kVideoPort;
		d["width"] = 640;
		d["height"] = 360;
		return result(c, id, true, d);
	}
	if (cmd == "video.stop") {
		c.videoToken.clear();
		if (c.videoSock)
			c.videoSock->close();
		c.videoSock = nullptr;
		updateVideo();
		return result(c, id, true);
	}
	if (cmd == "chapter.add") {
		if (!obs_frontend_recording_active())
			return result(c, id, false, {}, "not_recording");
		QString name = args.value("name").toString().left(120);
		bool ok = obs_frontend_recording_add_chapter(name.isEmpty() ? nullptr : name.toUtf8().constData());
		return result(c, id, ok, {}, ok ? QString() : QStringLiteral("chapters_unsupported"));
	}
	if (cmd == "desk.send") {
		QString text = args.value("text").toString().left(200);
		QString mid = args.value("id").toString().left(64);
		if (text.isEmpty())
			return result(c, id, false, {}, "empty");
		dock->addMessage(mid, c.device, text);
		return result(c, id, true);
	}
	if (cmd == "desk.feed") {
		dock->addFeed(args.value("text").toString().left(120));
		return result(c, id, true);
	}
	result(c, id, false, {}, "unknown_command");
}

/* ---------------- video ---------------- */

void DirectorServer::onVideoConnection()
{
	while (QTcpSocket *vs = videoServer->nextPendingConnection()) {
		vs->setSocketOption(QAbstractSocket::LowDelayOption, 1);
		connect(vs, &QTcpSocket::readyRead, this, [this, vs]() {
			if (!vs->canReadLine())
				return;
			QString token = QString::fromUtf8(vs->readLine(128)).trimmed();
			for (auto it = clients.begin(); it != clients.end(); ++it) {
				if (it->authed && !it->videoToken.isEmpty() && it->videoToken == token) {
					if (it->videoSock && it->videoSock != vs)
						it->videoSock->close();
					it->videoSock = vs;
					vs->setProperty("bound", true);
					disconnect(vs, &QTcpSocket::readyRead, this, nullptr);
					updateVideo();
					return;
				}
			}
			vs->close();
		});
		connect(vs, &QTcpSocket::disconnected, this, [this, vs]() {
			for (auto it = clients.begin(); it != clients.end(); ++it)
				if (it->videoSock == vs)
					it->videoSock = nullptr;
			vs->deleteLater();
			updateVideo();
		});
		/* a video socket that hasn't presented a token within 5 s is closed */
		QTimer::singleShot(5000, vs, [vs]() {
			if (!vs->property("bound").toBool())
				vs->close();
		});
	}
}

void DirectorServer::updateVideo()
{
	bool want = false;
	for (auto it = clients.begin(); it != clients.end(); ++it)
		if (it->videoSock)
			want = true;
	if (want && !preview->active())
		preview->start();
	else if (!want && preview->active())
		preview->stop();
}

void DirectorServer::sendVideoFrame(const QByteArray &frame)
{
	for (auto it = clients.begin(); it != clients.end(); ++it) {
		QTcpSocket *vs = it->videoSock;
		if (!vs || vs->state() != QAbstractSocket::ConnectedState)
			continue;
		/* a slow phone drops frames instead of building up delay */
		if (vs->bytesToWrite() > 512 * 1024 && !(frame.size() > 8 && (frame.at(8) & 3)))
			continue;
		vs->write(frame);
	}
}

/* ---------------- status, beacon, events ---------------- */

QJsonObject DirectorServer::buildStatus()
{
	QJsonObject rec;
	bool recording = obs_frontend_recording_active();
	rec["active"] = recording;
	rec["paused"] = recording && obs_frontend_recording_paused();
	rec["fileTimeMs"] = double(audio->fileTimeUs() / 1000);
	obs_output_t *ro = obs_frontend_get_recording_output();
	QString path = outputPath(ro);
	if (!path.isEmpty())
		lastFile = path;
	/* file name only: the full path can contain the PC's account name */
	rec["file"] = QFileInfo(lastFile).fileName();
	rec["container"] = QFileInfo(lastFile).suffix().toLower();
	rec["bytes"] = ro ? double(obs_output_get_total_bytes(ro)) : 0.0;
	QString dir = QFileInfo(lastFile).absolutePath();
	rec["diskFreeMB"] = dir.isEmpty() ? -1.0
					  : double(os_get_free_disk_space(dir.toUtf8().constData()) / (1024 * 1024));
	if (ro)
		obs_output_release(ro);

	QJsonObject stream;
	bool live = obs_frontend_streaming_active();
	stream["active"] = live;
	qint64 now = QDateTime::currentMSecsSinceEpoch();
	stream["durationMs"] = live && streamStartMs ? double(now - streamStartMs) : 0.0;
	obs_output_t *so = obs_frontend_get_streaming_output();
	if (so) {
		uint64_t bytes = obs_output_get_total_bytes(so);
		if (lastStreamBytesAt && now > lastStreamBytesAt && bytes >= lastStreamBytes) {
			double kbps = double(bytes - lastStreamBytes) * 8.0 / double(now - lastStreamBytesAt);
			streamKbps = streamKbps * 0.7 + kbps * 0.3;
		}
		lastStreamBytes = bytes;
		lastStreamBytesAt = now;
		stream["kbps"] = live ? streamKbps : 0.0;
		stream["dropped"] = obs_output_get_frames_dropped(so);
		stream["total"] = obs_output_get_total_frames(so);
		stream["congestion"] = double(obs_output_get_congestion(so));
		obs_output_release(so);
	}

	QJsonObject pc;
	if (cpu)
		cpuUsage = cpuUsage * 0.8 + os_cpu_usage_info_query(cpu) * 0.2;
	pc["cpu"] = cpuUsage;
	pc["fps"] = obs_get_active_fps();
	pc["renderLag"] = double(obs_get_lagged_frames());
	pc["encodeLag"] = double(video_output_get_skipped_frames(obs_get_video()));

	QJsonObject st;
	st["type"] = "status";
	st["t"] = double(now);
	st["rec"] = rec;
	st["stream"] = stream;
	st["pc"] = pc;
	obs_source_t *scene = obs_frontend_get_current_scene();
	if (scene) {
		st["scene"] = QString::fromUtf8(obs_source_get_name(scene));
		obs_source_release(scene);
	}
	return st;
}

void DirectorServer::sendStatus()
{
	bool any = false;
	for (auto it = clients.begin(); it != clients.end(); ++it)
		if (it->authed)
			any = true;
	if (any)
		broadcast(buildStatus());
}

QByteArray DirectorServer::beaconPayload() const
{
	QJsonObject b;
	b["obsdirector"] = kProtocol;
	b["id"] = pcId;
	b["name"] = pcName();
	b["address"] = advertisedAddress();
	b["port"] = kControlPort;
	b["obs"] = QString::fromUtf8(obs_get_version_string());
	b["plugin"] = QString::fromUtf8(PLUGIN_VERSION);
	return QJsonDocument(b).toJson(QJsonDocument::Compact);
}

void DirectorServer::onBeaconReadyRead()
{
	while (beacon->hasPendingDatagrams()) {
		QNetworkDatagram d = beacon->receiveDatagram(512);
		/* only {"obsdirector":"probe"}; our own beacons (and other PCs') arrive here too */
		QJsonObject o = QJsonDocument::fromJson(d.data()).object();
		if (o.value("obsdirector").toString() != QLatin1String("probe"))
			continue;
		QHostAddress from(ipv4Of(d.senderAddress()));
		if (!isUsableLan(from) || !isPrivate(from))
			continue; /* answer the local network only */
		qint64 now = QDateTime::currentMSecsSinceEpoch();
		if (now - probeWindowStart > 1000) {
			probeWindowStart = now;
			probeReplies = 0;
		}
		if (++probeReplies > kMaxProbeRepliesPerSec)
			continue;
		notePhoneSeen(from);
		beacon->writeDatagram(beaconPayload(), from, quint16(d.senderPort()));
	}
}

void DirectorServer::sendBeacon()
{
	QByteArray payload = beaconPayload();

	bool sent = false;
	for (const QNetworkInterface &iface : QNetworkInterface::allInterfaces()) {
		if (!(iface.flags() & QNetworkInterface::IsUp) || (iface.flags() & QNetworkInterface::IsLoopBack))
			continue;
		for (const QNetworkAddressEntry &e : iface.addressEntries()) {
			if (e.ip().protocol() != QAbstractSocket::IPv4Protocol || e.broadcast().isNull())
				continue;
			beacon->writeDatagram(payload, e.broadcast(), kBeaconPort);
			sent = true;
		}
	}
	if (!sent)
		beacon->writeDatagram(payload, QHostAddress::Broadcast, kBeaconPort);
}

void DirectorServer::updatePresence()
{
	QStringList listening, connected;
	for (auto it = clients.begin(); it != clients.end(); ++it) {
		if (!it->authed)
			continue;
		(it->listening ? listening : connected) << it->device;
	}
	if (!listening.isEmpty())
		dock->setPresence(tr("%1 listening").arg(listening.join(", ")), true);
	else if (!connected.isEmpty())
		dock->setPresence(tr("%1 connected").arg(connected.join(", ")), false);
	else
		dock->setPresence(tr("No remote connected"), false);
}

void DirectorServer::handleFrontendEvent(enum obs_frontend_event e)
{
	QJsonObject d;
	switch (e) {
	case OBS_FRONTEND_EVENT_RECORDING_STARTED:
		audio->recordingStarted();
		d["by"] = pendingRecordBy;
		if (!pendingRecordBy.isEmpty())
			dock->addFeed(tr("%1 started the recording").arg(pendingRecordBy));
		pendingRecordBy.clear();
		event("record.started", d);
		break;
	case OBS_FRONTEND_EVENT_RECORDING_STOPPED: {
		audio->recordingStopped();
		char *last = obs_frontend_get_last_recording();
		if (last) {
			lastFile = QString::fromUtf8(last);
			bfree(last);
		}
		d["by"] = lastStopBy;
		d["file"] = QFileInfo(lastFile).fileName();
		if (!lastStopBy.isEmpty())
			dock->addFeed(tr("%1 stopped the recording").arg(lastStopBy));
		lastStopBy.clear();
		event("record.stopped", d);
		break;
	}
	case OBS_FRONTEND_EVENT_RECORDING_PAUSED:
		audio->recordingPaused(true);
		event("record.paused");
		break;
	case OBS_FRONTEND_EVENT_RECORDING_UNPAUSED:
		audio->recordingPaused(false);
		event("record.resumed");
		break;
	case OBS_FRONTEND_EVENT_STREAMING_STARTED:
		streamStartMs = QDateTime::currentMSecsSinceEpoch();
		event("stream.started");
		break;
	case OBS_FRONTEND_EVENT_STREAMING_STOPPED:
		streamStartMs = 0;
		event("stream.stopped");
		break;
	case OBS_FRONTEND_EVENT_SCENE_CHANGED: {
		obs_source_t *scene = obs_frontend_get_current_scene();
		if (scene) {
			d["scene"] = QString::fromUtf8(obs_source_get_name(scene));
			obs_source_release(scene);
		}
		event("scene.changed", d);
		break;
	}
	default:
		break;
	}
}
