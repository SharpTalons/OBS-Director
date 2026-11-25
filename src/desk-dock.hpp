/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#pragma once

#include <QDate>
#include <QHash>
#include <QLabel>
#include <QPlainTextEdit>
#include <QScrollArea>
#include <QSet>
#include <QVBoxLayout>
#include <QWidget>

/*
 * The dock the volunteers see in OBS: a group chat with everyone connected, laid out like a
 * messaging app. Messages from phones and browsers sit on the left with the sender's name;
 * the desk's own on the right. Connections and recording events show as small centred notes.
 * Enter starts a new line; Ctrl+Enter or the send button sends.
 */
class DeskDock : public QWidget {
	Q_OBJECT

public:
	explicit DeskDock(QWidget *parent = nullptr);

	void setPresence(const QString &text, bool listening);
	/* a chat message; mine = sent from this dock */
	void addMessage(const QString &id, const QString &from, const QString &text, qint64 at, bool mine);
	/* a small centred note in the chat (connected, recording started, ...) */
	void addFeed(const QString &text);

signals:
	/* the desk wrote a message */
	void sent(const QString &text);
	/* the desk has seen these messages (the dock is showing and OBS is in front) */
	void seen(const QStringList &ids);

protected:
	bool eventFilter(QObject *obj, QEvent *event) override;
	void showEvent(QShowEvent *event) override;

private:
	void send();
	void addDateIfNew(const QDate &day);
	void scrollToEnd();
	void markSeen();
	QColor nameColour(const QString &name) const;

	QLabel *presence;
	QScrollArea *scroll;
	QWidget *inner;
	QVBoxLayout *list;
	QLabel *empty;
	QPlainTextEdit *input;
	QDate lastDay;
	QStringList unseen;
	mutable QHash<QString, int> nameOrder; /* senders get colours in the order they first write */
};
