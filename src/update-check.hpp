/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#pragma once

#include <QNetworkAccessManager>
#include <QObject>
#include <QString>
#include <functional>

/*
 * Tells the desk when a newer OBS Director is out. Shortly after OBS starts, and at most once a
 * day, it asks GitHub for the newest public release (nothing about this PC is sent). If it is
 * newer than this plugin, it offers Download (the Windows installer), Skip this version or Later.
 * It never interrupts a stream or a recording: then it leaves a note in the dock and asks once
 * both have stopped. Tools, OBS Director has "Check now" and a switch to turn the daily check off.
 */
class UpdateCheck : public QObject {
	Q_OBJECT

public:
	/* note: shows a line in the dock */
	explicit UpdateCheck(std::function<void(const QString &)> note, QObject *parent = nullptr);

	/* after OBS has loaded: check in a little while, if it's on and a day has passed */
	void checkSoon();
	/* Tools, Check now: check at once and say how it went */
	void checkNow();
	/* the stream or recording stopped: offer an update that was held back */
	void obsIdle();

	bool autoCheck() const { return enabled; }
	void setAutoCheck(bool on);
	/* "Up to date", "0.8.2 is available", "Couldn't check", ... */
	QString status() const { return statusText; }

	/* is version a newer than b ("0.8.2" > "0.8.1") */
	static bool newer(const QString &a, const QString &b);

signals:
	void statusChanged(const QString &status);

private:
	void fetch(bool manual);
	void offer(bool manual);
	void setStatus(const QString &s);
	void load();
	void save() const;

	QNetworkAccessManager net;
	std::function<void(const QString &)> note;
	bool enabled = true;
	qint64 lastCheck = 0;
	QString skipped;
	QString latest, page, installer, notes;
	bool held = false; /* an offer waiting for the stream and recording to stop */
	bool busy = false;
	QString statusText;
};
