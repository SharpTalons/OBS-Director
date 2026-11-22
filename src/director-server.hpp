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
#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QStringList>
#include <QTimer>

#include <memory>

class QTcpServer;
class QTcpSocket;
class QUdpSocket;
class DeskDock;
class UdpAudio;
class VideoPreview;

/*
 * The plugin's side of OBS Director (see docs/PROTOCOL.md):
 *   - TCP 4466: newline-delimited JSON control channel, PIN-paired
 *   - UDP 4467: discovery beacon, broadcast every 2 s
 *   - UDP to the device: program mix as PCM (UdpAudio)
 *   - TCP 4468: H.264 live output frames, opened with a token from video.start
 */
class DirectorServer : public QObject {
	Q_OBJECT

public:
	static constexpr quint16 kControlPort = 4466;
	static constexpr quint16 kBeaconPort = 4467;
	static constexpr quint16 kVideoPort = 4468;
	static constexpr int kProtocol = 1;

	explicit DirectorServer(DeskDock *dock, QObject *parent = nullptr);
	~DirectorServer() override;

	void handleFrontendEvent(enum obs_frontend_event event);

	QString pin() const { return currentPin; }
	void newPin();
	QStringList addresses() const;
	QString pcName() const;

private slots:
	void onNewConnection();
	void onVideoConnection();
	void sendStatus();
	void sendBeacon();

private:
	struct Client {
		QTcpSocket *sock = nullptr;
		bool authed = false;
		QString device;
		QString ip;
		quint16 audioPort = 0;
		bool listening = false;
		QString videoToken;
		QTcpSocket *videoSock = nullptr;
		qint64 connectedAt = 0;
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

	DeskDock *dock;
	QTcpServer *server;
	QTcpServer *videoServer;
	QUdpSocket *beacon;
	QTimer statusTimer;
	QTimer beaconTimer;
	QHash<QTcpSocket *, Client> clients;
	std::unique_ptr<UdpAudio> audio;
	std::unique_ptr<VideoPreview> preview;
	QString currentPin;
	int badPinCount = 0;

	/* wrong-PIN backoff per address, so the 4-digit PIN can't be guessed by brute force */
	struct Lockout {
		int failures = 0;
		qint64 until = 0;
	};
	QHash<QString, Lockout> lockouts;
	bool lockedOut(const QString &ip);
	void recordBadPin(const QString &ip);
	int unpairedCount() const;

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
};
