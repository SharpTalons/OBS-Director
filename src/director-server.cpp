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
#include <QJsonArray>
#include <QJsonDocument>
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
	QString p = QString::fromUtf8(obs_data_get_string(s, "path"));
	if (p.isEmpty())
		p = QString::fromUtf8(obs_data_get_string(s, "url"));
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

/* ---------------- config: the pairing PIN ---------------- */

void DirectorServer::loadConfig()
{
	char *path = obs_module_config_path("config.json");
	obs_data_t *data = path ? obs_data_create_from_json_file_safe(path, "bak") : nullptr;
	if (data) {
		currentPin = QString::fromUtf8(obs_data_get_string(data, "pin"));
		obs_data_release(data);
	}
	bfree(path);
	if (currentPin.size() != 4)
		newPin();
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
	obs_data_set_string(data, "pin", currentPin.toUtf8().constData());
	obs_data_save_json_safe(data, path, "tmp", "bak");
	obs_data_release(data);
	bfree(path);
}

void DirectorServer::newPin()
{
	currentPin = QString("%1").arg(QRandomGenerator::global()->bounded(10000), 4, 10, QChar('0'));
	saveConfig();
}

QStringList DirectorServer::addresses() const
{
	QStringList out;
	for (const QHostAddress &a : QNetworkInterface::allAddresses())
		if (a.protocol() == QAbstractSocket::IPv4Protocol && !a.isLoopback())
			out << a.toString();
	return out;
}

QString DirectorServer::pcName() const
{
	QString n = QHostInfo::localHostName();
	return n.isEmpty() ? QStringLiteral("OBS PC") : n;
}

/* ---------------- connections ---------------- */

void DirectorServer::onNewConnection()
{
	while (QTcpSocket *sock = server->nextPendingConnection()) {
		sock->setSocketOption(QAbstractSocket::LowDelayOption, 1);
		Client c;
		c.sock = sock;
		c.ip = ipv4Of(sock->peerAddress());
		c.connectedAt = QDateTime::currentMSecsSinceEpoch();
		clients.insert(sock, c);
		connect(sock, &QTcpSocket::readyRead, this, [this, sock]() { onReadyRead(sock); });
		connect(sock, &QTcpSocket::disconnected, this, [this, sock]() { onDisconnected(sock); });
		/* unpaired sockets get 10 s to say hello */
		QTimer::singleShot(10000, this, [this, sock]() {
			auto it = clients.find(sock);
			if (it != clients.end() && !it->authed)
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
		if (type != "hello")
			return;
		if (msg.value("pin").toString() != currentPin) {
			badPinCount++;
			QJsonObject e;
			e["type"] = "error";
			e["code"] = "bad_pin";
			e["message"] = "That PIN doesn't match. Check Tools, OBS Director in OBS.";
			send(c.sock, e);
			if (badPinCount > 5) {
				newPin(); /* too many guesses: rotate the PIN */
				badPinCount = 0;
				obs_log(LOG_WARNING, "too many wrong PINs; a new PIN was generated");
			}
			c.sock->disconnectFromHost();
			return;
		}
		badPinCount = 0;
		c.authed = true;
		c.device = msg.value("device").toString().left(40);
		if (c.device.isEmpty())
			c.device = tr("A remote");

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
		QString mid = args.value("id").toString();
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
	rec["path"] = lastFile;
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

void DirectorServer::sendBeacon()
{
	QJsonObject b;
	b["obsdirector"] = kProtocol;
	b["name"] = pcName();
	b["port"] = kControlPort;
	b["obs"] = QString::fromUtf8(obs_get_version_string());
	b["plugin"] = QString::fromUtf8(PLUGIN_VERSION);
	QByteArray payload = QJsonDocument(b).toJson(QJsonDocument::Compact);

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
