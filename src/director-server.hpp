/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#pragma once

#include <obs-frontend-api.h>
#include <util/platform.h>

#include <QElapsedTimer>
#include <QHostAddress>
#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QPointer>
#include <QStringList>
#include <QTimer>

#include <memory>

class QTcpServer;
class QTcpSocket;
class QUdpSocket;
class DeskDock;
class QMessageBox;
class UdpAudio;
class VideoPreview;

/*
 * The plugin's side of OBS Director (see docs/PROTOCOL.md):
 *   - TCP 4466: newline-delimited JSON control channel; a phone is approved once in OBS
 *   - UDP 4467: discovery beacon, broadcast every 2 s, and answers to probes
 *   - UDP to the device: program mix as PCM (UdpAudio)
 *   - TCP 4468: H.264 live output frames, opened with a token from video.start
 *   - TCP 4470: the browser version: the page itself over HTTP, then a WebSocket on /ws that
 *     carries the same JSON messages, and the mix and live output as binary frames
 */
class DirectorServer : public QObject {
	Q_OBJECT

public:
	static constexpr quint16 kControlPort = 4466;
	static constexpr quint16 kBeaconPort = 4467;
	static constexpr quint16 kVideoPort = 4468;
	static constexpr quint16 kWebPort = 4470;
	static constexpr int kProtocol = 2; /* 2: approve-once pairing (deviceId), no PIN */

	explicit DirectorServer(DeskDock *dock, QObject *parent = nullptr);
	~DirectorServer() override;

	void handleFrontendEvent(enum obs_frontend_event event);

	/* a phone someone pressed Allow for; it connects without asking again */
	struct TrustedDevice {
		QString id;
		QString name;
		qint64 approvedAt = 0;
	};
	QList<TrustedDevice> trustedDevices() const { return trusted; }
	void forgetDevice(const QString &id);
	void forgetAllDevices();
	/* the one address to show and advertise: the one phones have reached this PC on,
	 * or before any phone has, the PC's main LAN address */
	QString advertisedAddress() const;
	QString primaryAddress() const;
	QString pcName() const;
	/* where to open the browser version, e.g. http://192.168.1.24:4470 */
	QString webAddress() const;

signals:
	void devicesChanged();
	void addressChanged();

private slots:
	void onNewConnection();
	void onBeaconReadyRead();
	void onVideoConnection();
	void onWebConnection();
	void sendStatus();
	void sendBeacon();

private:
	struct Client {
		QTcpSocket *sock = nullptr;
		bool authed = false;
		bool pending = false; /* waiting for Allow or Deny in OBS */
		QPointer<QMessageBox> prompt;
		QString device;
		QString deviceId;
		QString ip;
		quint16 audioPort = 0;
		bool listening = false;
		QString videoToken;
		QTcpSocket *videoSock = nullptr;
		qint64 connectedAt = 0;
		/* a browser: messages are WebSocket frames, and the mix and live output come over
		 * the same connection instead of UDP and TCP 4468 */
		bool ws = false;
		QByteArray wsBuf;
		bool wsAudio = false;
		bool wsVideo = false;
		bool wsVideoNeedKey = true;
	};

	void onReadyRead(QTcpSocket *sock);
	void onDisconnected(QTcpSocket *sock);
	void handleMessage(Client &c, const QJsonObject &msg);
	void handleCommand(Client &c, int id, const QString &cmd, const QJsonObject &args);
	void send(QTcpSocket *sock, const QJsonObject &msg);
	void broadcast(const QJsonObject &msg);
	void event(const QString &name, const QJsonObject &data = QJsonObject());
	void result(Client &c, int id, bool ok, const QJsonObject &data = QJsonObject(),
		    const QString &error = QString());
	QJsonObject buildStatus();
	void updatePresence();
	void updateVideo();
	void sendVideoFrame(const QByteArray &frame);
	void loadConfig();
	void saveConfig();

	/* the browser version (web-server.cpp) */
	void acceptClient(QTcpSocket *sock, bool ws);
	void onHttpReadyRead(QTcpSocket *sock);
	void serveFile(QTcpSocket *sock, const QByteArray &path, const QByteArray &host);
	void upgradeToWebSocket(QTcpSocket *sock, const QByteArray &key, const QByteArray &rest);
	void readWebSocket(QTcpSocket *sock);
	void wsWrite(QTcpSocket *sock, quint8 opcode, const QByteArray &payload);
	void sendAudioToBrowsers(const QByteArray &packet);
	void sendVideoToBrowsers(const QByteArray &frame);
	void updateBrowserAudio();
	bool allowedHost(const QByteArray &host) const;
	QTcpServer *webServer = nullptr;
	QHash<QTcpSocket *, QByteArray> httpPending;

	DeskDock *dock;
	QTcpServer *server;
	QTcpServer *videoServer;
	QUdpSocket *beacon;
	QTimer statusTimer;
	QTimer beaconTimer;
	QHash<QTcpSocket *, Client> clients;
	std::unique_ptr<UdpAudio> audio;
	std::unique_ptr<VideoPreview> preview;
	QList<TrustedDevice> trusted;
	QString pcId;         /* stable, so a phone can tell one PC heard on two adapters is one PC */
	QString phoneAddress; /* this PC's address on the network phones are on */
	void notePhoneSeen(const QHostAddress &phone, const QHostAddress &local = QHostAddress());

	/* after Deny (or no answer), an address waits before it may ask again */
	QHash<QString, qint64> lockouts;
	bool lockedOut(const QString &ip);
	void lockOut(const QString &ip, qint64 ms);
	int unpairedCount() const;
	int openPrompts() const;
	bool isTrusted(const QString &id) const;
	void askApproval(Client &c);
	void trust(const Client &c);
	void admit(Client &c);
	void refuse(Client &c, const char *code, const QString &message, qint64 lockMs);
	QByteArray beaconPayload() const;
	qint64 probeWindowStart = 0;
	int probeReplies = 0;

	/* who last asked to start or stop the recording, so the app can tell "it wasn't you" */
	QString pendingRecordBy;
	QString lastStopBy;

	qint64 streamStartMs = 0;
	uint64_t lastStreamBytes = 0;
	qint64 lastStreamBytesAt = 0;
	double streamKbps = 0;
	os_cpu_usage_info_t *cpu = nullptr;
	double cpuUsage = 0;
	QString lastFile;
	/* recordings OBS made while the plugin ran: file name -> full path. clips.write may only
	 * write next to one of these. */
	QHash<QString, QString> recordedFiles;
	void rememberRecording(const QString &path);
};
