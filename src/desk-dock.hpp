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
#include <QList>
#include <QPointer>
#include <QPushButton>
#include <QTimer>
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
 * Enter starts a new line; Ctrl+Enter or the send button sends. A quick reply waits three
 * seconds before it goes, so a slip of the mouse can be taken back.
 *
 * The dock never plays a sound: OBS may be capturing the PC's audio. A new message while OBS
 * is in the background flashes OBS in the taskbar instead.
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
	void resizeEvent(QResizeEvent *event) override;
	void changeEvent(QEvent *event) override;

private:
	void send();
	void addDateIfNew(const QDate &day);
	void scrollToEnd();
	void markSeen();
	QColor nameColour(const QString &name) const;
	void applyStyle();
	void addRow(QHBoxLayout *row);
	void pruneRows();
	void fitBubbles();
	int bubbleRoom() const;
	void quickReply(QPushButton *button, const QString &text);
	void announce(const QString &text);

	QLabel *presence = nullptr;
	QScrollArea *scroll = nullptr;
	QWidget *inner = nullptr;
	QVBoxLayout *list = nullptr;
	QLabel *empty = nullptr;
	QPlainTextEdit *input = nullptr;
	QDate lastDay;
	QStringList unseen;
	mutable QHash<QString, int> nameOrder; /* senders get colours in the order they first write */
	QList<QHBoxLayout *> rows;             /* every line in the chat, oldest first */
	QList<QPointer<QLabel>> bodies;        /* each message's text, to rewrap when the dock is resized */
	bool followEnd = true;                 /* the chat is scrolled to the newest message */
	QList<QPushButton *> quickButtons;
	QPointer<QPushButton> pendingButton; /* a quick reply waiting to go */
	QString pendingText;
	QTimer pendingTimer;
	bool styling = false;
};
