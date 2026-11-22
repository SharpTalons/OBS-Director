/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#pragma once

#include <QLabel>
#include <QVBoxLayout>
#include <QWidget>

/*
 * The dock the volunteers see in OBS: who is connected and listening, messages from the
 * remote with one-tap answers, and a short feed (recording started from the phone, clips).
 */
class DeskDock : public QWidget {
	Q_OBJECT

public:
	explicit DeskDock(QWidget *parent = nullptr);

	void setPresence(const QString &text, bool listening);
	void addMessage(const QString &id, const QString &from, const QString &text);
	void addFeed(const QString &text);

signals:
	void replied(const QString &id, const QString &text);

private:
	QLabel *presence;
	QVBoxLayout *messages;
	QLabel *empty;
	QLabel *feed;
	QStringList feedLines;
};
