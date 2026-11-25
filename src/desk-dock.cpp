/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#include "desk-dock.hpp"

#include <QApplication>
#include <QDateTime>
#include <QFrame>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QPushButton>
#include <QScrollBar>
#include <QTimer>

namespace {

/* The chat keeps its own colours, the same in every OBS theme, like a messaging app. */
const char *kChatStyle = R"(
QWidget#chat { background: #0b141a; }
QLabel#empty { color: #8696a0; font-size: 12px; }
QFrame#in { background: #202c33; border-radius: 10px; }
QFrame#out { background: #005c4b; border-radius: 10px; }
QLabel#body { color: #e9edef; font-size: 13px; }
QLabel#time { color: #8696a0; font-size: 10px; }
QLabel#outtime { color: #a5d6c9; font-size: 10px; }
QLabel#note { background: #182229; color: #8696a0; font-size: 11px; border-radius: 8px; padding: 3px 10px; }
QLabel#day { background: #182229; color: #8696a0; font-size: 11px; border-radius: 8px; padding: 3px 10px; }
QWidget#bar { background: #202c33; }
QPlainTextEdit#input { background: #2a3942; color: #e9edef; border: none; border-radius: 18px; padding: 6px 10px; font-size: 13px; }
QPushButton#send { background: #00a884; color: #0b141a; border: none; border-radius: 18px; font-weight: 600; min-width: 36px; min-height: 36px; }
QPushButton#send:disabled { background: #2a3942; color: #8696a0; }
QPushButton#quick { background: #2a3942; color: #e9edef; border: none; border-radius: 12px; padding: 4px 10px; font-size: 12px; }
QPushButton#quick:hover { background: #374248; }
)";

QString dayLabel(const QDate &d)
{
	const QDate today = QDate::currentDate();
	if (d == today)
		return QObject::tr("Today");
	if (d == today.addDays(-1))
		return QObject::tr("Yesterday");
	return QLocale().toString(d, QStringLiteral("d MMMM yyyy"));
}

} // namespace

DeskDock::DeskDock(QWidget *parent) : QWidget(parent)
{
	setStyleSheet(QString::fromLatin1(kChatStyle));
	auto *root = new QVBoxLayout(this);
	root->setContentsMargins(0, 0, 0, 0);
	root->setSpacing(0);

	presence = new QLabel(tr("No remote connected"), this);
	presence->setWordWrap(true);
	presence->setTextFormat(Qt::PlainText);
	presence->setContentsMargins(10, 6, 10, 6);
	presence->setStyleSheet("font-weight: 600;");
	root->addWidget(presence);

	scroll = new QScrollArea(this);
	scroll->setWidgetResizable(true);
	scroll->setFrameShape(QFrame::NoFrame);
	scroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
	inner = new QWidget(scroll);
	inner->setObjectName("chat");
	list = new QVBoxLayout(inner);
	list->setContentsMargins(10, 10, 10, 10);
	list->setSpacing(4);
	list->addStretch(1); /* messages sit at the bottom, newest last, like a chat */
	empty = new QLabel(tr("Messages with the phones and browsers appear here."), inner);
	empty->setObjectName("empty");
	empty->setAlignment(Qt::AlignCenter);
	empty->setWordWrap(true);
	list->addWidget(empty);
	scroll->setWidget(inner);
	root->addWidget(scroll, 1);

	/* the bar at the bottom: quick replies, then the text box and send */
	auto *bar = new QWidget(this);
	bar->setObjectName("bar");
	auto *barLayout = new QVBoxLayout(bar);
	barLayout->setContentsMargins(8, 6, 8, 8);
	barLayout->setSpacing(6);
	auto *quick = new QHBoxLayout();
	quick->setSpacing(6);
	for (const QString &q : {tr("Got it"), tr("On it"), tr("Fixed")}) {
		auto *b = new QPushButton(q, bar);
		b->setObjectName("quick");
		b->setCursor(Qt::PointingHandCursor);
		connect(b, &QPushButton::clicked, this, [this, q]() { emit sent(q); });
		quick->addWidget(b);
	}
	quick->addStretch(1);
	barLayout->addLayout(quick);

	auto *row = new QHBoxLayout();
	row->setSpacing(6);
	input = new QPlainTextEdit(bar);
	input->setObjectName("input");
	input->setPlaceholderText(tr("Message  (Ctrl+Enter to send)"));
	input->setTabChangesFocus(true);
	input->setFixedHeight(38);
	input->installEventFilter(this);
	auto *sendBtn = new QPushButton(QStringLiteral("➤"), bar);
	sendBtn->setObjectName("send");
	sendBtn->setToolTip(tr("Send (Ctrl+Enter)"));
	sendBtn->setAccessibleName(tr("Send"));
	sendBtn->setCursor(Qt::PointingHandCursor);
	sendBtn->setEnabled(false);
	connect(sendBtn, &QPushButton::clicked, this, &DeskDock::send);
	/* the box grows with the text, up to five lines, then scrolls */
	connect(input, &QPlainTextEdit::textChanged, this, [this, sendBtn]() {
		sendBtn->setEnabled(!input->toPlainText().trimmed().isEmpty());
		const int lines = qBound(1, int(input->document()->size().height()), 5);
		input->setFixedHeight(qMax(38, lines * input->fontMetrics().lineSpacing() + 18));
	});
	row->addWidget(input, 1);
	row->addWidget(sendBtn, 0, Qt::AlignBottom);
	barLayout->addLayout(row);
	root->addWidget(bar);

	/* seen when OBS comes to the front with the dock showing */
	connect(qApp, &QApplication::applicationStateChanged, this, [this](Qt::ApplicationState s) {
		if (s == Qt::ApplicationActive)
			markSeen();
	});
}

bool DeskDock::eventFilter(QObject *obj, QEvent *event)
{
	if (obj == input && event->type() == QEvent::KeyPress) {
		auto *k = static_cast<QKeyEvent *>(event);
		if ((k->key() == Qt::Key_Return || k->key() == Qt::Key_Enter) &&
		    (k->modifiers() & Qt::ControlModifier)) {
			send();
			return true;
		}
	}
	return QWidget::eventFilter(obj, event);
}

void DeskDock::showEvent(QShowEvent *event)
{
	QWidget::showEvent(event);
	markSeen();
}

void DeskDock::send()
{
	const QString text = input->toPlainText().trimmed();
	if (text.isEmpty())
		return;
	input->clear();
	emit sent(text.left(1000));
}

void DeskDock::setPresence(const QString &text, bool listening)
{
	presence->setText((listening ? QStringLiteral("● ") : QString()) + text);
}

QColor DeskDock::nameColour(const QString &name) const
{
	/* each sender keeps one colour, as in a group chat */
	static const QColor palette[] = {QColor("#53bdeb"), QColor("#ffd279"), QColor("#a5b337"), QColor("#ff8a9c"),
					 QColor("#b9a2ff"), QColor("#5fd4e8"), QColor("#f7a35c"), QColor("#6edda8")};
	if (!nameOrder.contains(name))
		nameOrder.insert(name, nameOrder.size());
	return palette[nameOrder.value(name) % int(sizeof(palette) / sizeof(palette[0]))];
}

void DeskDock::addDateIfNew(const QDate &day)
{
	if (day == lastDay)
		return;
	lastDay = day;
	auto *label = new QLabel(dayLabel(day), inner);
	label->setObjectName("day");
	auto *row = new QHBoxLayout();
	row->addStretch(1);
	row->addWidget(label);
	row->addStretch(1);
	list->addLayout(row);
}

void DeskDock::addMessage(const QString &id, const QString &from, const QString &text, qint64 at, bool mine)
{
	empty->hide();
	const QDateTime when = QDateTime::fromMSecsSinceEpoch(at);
	addDateIfNew(when.date());

	auto *bubble = new QFrame(inner);
	bubble->setObjectName(mine ? "out" : "in");
	bubble->setMaximumWidth(qMax(220, scroll->viewport()->width() * 4 / 5));
	auto *v = new QVBoxLayout(bubble);
	v->setContentsMargins(9, 6, 9, 5);
	v->setSpacing(2);
	if (!mine) {
		auto *name = new QLabel(from, bubble);
		name->setTextFormat(Qt::PlainText); /* names come from the phones: never HTML */
		name->setStyleSheet(
			QStringLiteral("color: %1; font-weight: 600; font-size: 12px;").arg(nameColour(from).name()));
		v->addWidget(name);
	}
	auto *body = new QLabel(text, bubble);
	body->setObjectName("body");
	body->setTextFormat(Qt::PlainText); /* text from the phone: never rendered as HTML */
	body->setWordWrap(true);
	/* as wide as the text needs, up to most of the dock, rather than Qt's narrow wrap */
	int widest = 0;
	for (const QString &line : text.split(QLatin1Char('\n')))
		widest = qMax(widest, body->fontMetrics().horizontalAdvance(line));
	const int room = qMax(240, qMax(width(), scroll->viewport()->width()) * 4 / 5 - 24);
	body->setMinimumWidth(qMin(widest + 4, room));
	body->setTextInteractionFlags(Qt::TextSelectableByMouse);
	v->addWidget(body);
	auto *time = new QLabel(when.toString(QStringLiteral("HH:mm")), bubble);
	time->setObjectName(mine ? "outtime" : "time");
	time->setAlignment(Qt::AlignRight);
	v->addWidget(time);

	auto *row = new QHBoxLayout();
	if (mine)
		row->addStretch(1);
	row->addWidget(bubble);
	if (!mine)
		row->addStretch(1);
	list->addLayout(row);

	if (!mine) {
		unseen << id;
		if (!isVisible() || QApplication::applicationState() != Qt::ApplicationActive)
			QApplication::beep();
	}
	scrollToEnd();
	markSeen();
}

void DeskDock::addFeed(const QString &text)
{
	empty->hide();
	addDateIfNew(QDate::currentDate());
	auto *label =
		new QLabel(QTime::currentTime().toString(QStringLiteral("HH:mm")) + QStringLiteral("  ") + text, inner);
	label->setObjectName("note");
	label->setTextFormat(Qt::PlainText);
	label->setWordWrap(text.size() > 48); /* short notes stay on one line */
	label->setAlignment(Qt::AlignCenter);
	auto *row = new QHBoxLayout();
	row->addStretch(1);
	row->addWidget(label);
	row->addStretch(1);
	list->addLayout(row);
	scrollToEnd();
}

void DeskDock::scrollToEnd()
{
	/* after the new row has been laid out */
	QTimer::singleShot(0, this,
			   [this]() { scroll->verticalScrollBar()->setValue(scroll->verticalScrollBar()->maximum()); });
}

void DeskDock::markSeen()
{
	if (unseen.isEmpty() || !isVisible() || QApplication::applicationState() != Qt::ApplicationActive)
		return;
	emit seen(unseen);
	unseen.clear();
}
