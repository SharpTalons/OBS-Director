/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#include "desk-dock.hpp"

#include <QAccessible>
#include <QApplication>
#include <QDateTime>
#include <QFrame>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QScrollBar>
#include <QStyle>

namespace {

/* As many lines as the PC side keeps (500 messages), plus room for day labels and notes. */
constexpr int kMaxRows = 600;

/*
 * The chat keeps its own colours, the same in every OBS theme, like a messaging app, picked to
 * read well on a dark screen: white message text (at least 6.5:1 on its bubble), light grey times
 * and notes (at least 5.5:1), and bubbles edged so they stand out from the background. Text
 * sizes follow the PC's own text size (Windows, Accessibility, Text size), so they're worked
 * out from OBS's font rather than fixed in pixels.
 */
QString chatStyle(double pt)
{
	auto size = [pt](double scale) {
		return QString::number(qMax(7.0, pt * scale), 'f', 1) + QStringLiteral("pt");
	};
	return QStringLiteral(R"(
QWidget#chat { background: #0b141a; }
QLabel#presence { background: #1c2a31; color: #e3e9ec; font-weight: 600; font-size: %2; }
QLabel#empty { color: #c9d3d8; font-size: %2; }
QFrame#in { background: #2b3b45; border: 1px solid #3d4e59; border-radius: 10px; }
QFrame#out { background: #005c4b; border: 1px solid #0a7a63; border-radius: 10px; }
QLabel#body { color: #ffffff; font-size: %1; background: transparent; border: none; }
QLabel#time { color: #c9d3d8; font-size: %3; background: transparent; border: none; }
QLabel#outtime { color: #d9f2ea; font-size: %3; background: transparent; border: none; }
QLabel#note { background: #223038; color: #c9d3d8; font-size: %4; border-radius: 8px; padding: 3px 10px; }
QLabel#day { background: #223038; color: #c9d3d8; font-size: %4; border-radius: 8px; padding: 3px 10px; }
QWidget#bar { background: #1c2a31; }
QPlainTextEdit#input { background: #34444f; color: #ffffff; border: none; border-radius: 18px; padding: 6px 10px; font-size: %1; }
QPushButton#send { background: #00a884; color: #0b141a; border: none; border-radius: 18px; font-weight: 600; min-width: 36px; min-height: 36px; }
QPushButton#send:disabled { background: #34444f; color: #b7c2c8; }
QPushButton:focus { outline: none; border: 2px solid #7fd0f5; }
)")
		.arg(size(1.0), size(0.92), size(0.78), size(0.85));
}

QString dayLabel(const QDate &d)
{
	const QDate today = QDate::currentDate();
	if (d == today)
		return QObject::tr("Today");
	if (d == today.addDays(-1))
		return QObject::tr("Yesterday");
	return QLocale().toString(d, QStringLiteral("d MMMM yyyy"));
}

/* Remove a row from the chat with everything in it. */
void dropRow(QLayout *row)
{
	while (QLayoutItem *item = row->takeAt(0)) {
		if (QWidget *w = item->widget())
			w->deleteLater();
		delete item;
	}
	delete row;
}

} // namespace

DeskDock::DeskDock(QWidget *parent) : QWidget(parent)
{
	setAccessibleName(tr("OBS Director desk chat"));
	auto *root = new QVBoxLayout(this);
	root->setContentsMargins(0, 0, 0, 0);
	root->setSpacing(0);

	presence = new QLabel(tr("No remote connected"), this);
	presence->setWordWrap(true);
	presence->setTextFormat(Qt::PlainText);
	presence->setContentsMargins(10, 6, 10, 6);
	/* in the chat's own colours, so it doesn't stand out as a light strip in light OBS themes */
	presence->setObjectName("presence");
	root->addWidget(presence);

	scroll = new QScrollArea(this);
	scroll->setWidgetResizable(true);
	scroll->setFrameShape(QFrame::NoFrame);
	scroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
	scroll->setAccessibleName(tr("Messages"));
	inner = new QWidget(scroll);
	inner->setObjectName("chat");
	list = new QVBoxLayout(inner);
	list->setContentsMargins(10, 10, 10, 10);
	list->setSpacing(4);
	list->addStretch(1); /* messages sit at the bottom, newest last, like a chat */
	empty = new QLabel(tr("Messages with the phones and browsers appear here.\n"
			      "Write below: Enter starts a new line, Ctrl+Enter sends."),
			   inner);
	empty->setObjectName("empty");
	empty->setAlignment(Qt::AlignCenter);
	empty->setWordWrap(true);
	list->addWidget(empty);
	scroll->setWidget(inner);
	root->addWidget(scroll, 1);
	/* stay at the newest message while new lines are laid out, unless the desk scrolled up to read */
	auto *bar0 = scroll->verticalScrollBar();
	connect(bar0, &QScrollBar::rangeChanged, this, [this, bar0](int, int max) {
		if (followEnd)
			bar0->setValue(max);
	});
	connect(bar0, &QScrollBar::valueChanged, this, [this, bar0](int v) { followEnd = v >= bar0->maximum() - 4; });

	/* the bar at the bottom: the text box and send */
	auto *bar = new QWidget(this);
	bar->setObjectName("bar");
	auto *barLayout = new QVBoxLayout(bar);
	barLayout->setContentsMargins(8, 8, 8, 8);

	auto *row = new QHBoxLayout();
	row->setSpacing(6);
	input = new QPlainTextEdit(bar);
	input->setObjectName("input");
	input->setPlaceholderText(tr("Message  (Ctrl+Enter to send)"));
	input->setAccessibleName(tr("Message to everyone connected"));
	input->setAccessibleDescription(tr("Enter starts a new line. Ctrl+Enter sends."));
	{
		/* the hint in the empty box, light enough to read on the dark bar */
		QPalette pal = input->palette();
		pal.setColor(QPalette::PlaceholderText, QColor("#b7c2c8"));
		input->setPalette(pal);
	}
	input->setTabChangesFocus(true);
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
		input->setFixedHeight(lines * input->fontMetrics().lineSpacing() + 22);
	});
	row->addWidget(input, 1);
	row->addWidget(sendBtn, 0, Qt::AlignBottom);
	barLayout->addLayout(row);
	root->addWidget(bar);

	applyStyle();

	/* seen when OBS comes to the front with the dock showing */
	connect(qApp, &QApplication::applicationStateChanged, this, [this](Qt::ApplicationState s) {
		if (s == Qt::ApplicationActive)
			markSeen();
	});
}

void DeskDock::applyStyle()
{
	if (!input)
		return;
	/* setStyleSheet sends a FontChange of its own; don't answer it */
	styling = true;
	setStyleSheet(chatStyle(QApplication::font().pointSizeF() > 0 ? QApplication::font().pointSizeF() : 9.0));
	styling = false;
	input->setFixedHeight(input->fontMetrics().lineSpacing() + 22);
}

void DeskDock::changeEvent(QEvent *event)
{
	QWidget::changeEvent(event);
	/* the PC's text size or OBS's theme changed */
	if (!styling && input &&
	    (event->type() == QEvent::FontChange || event->type() == QEvent::ApplicationFontChange)) {
		applyStyle();
		fitBubbles();
	}
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

void DeskDock::resizeEvent(QResizeEvent *event)
{
	QWidget::resizeEvent(event);
	fitBubbles();
	/* the full hint only where it fits on one line */
	const QString full = tr("Message  (Ctrl+Enter to send)");
	input->setPlaceholderText(input->fontMetrics().horizontalAdvance(full) + 100 < width() ? full : tr("Message"));
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
	/* light enough to read on an incoming bubble: at least 6:1 */
	static const QColor palette[] = {QColor("#7fd0f5"), QColor("#ffe08a"), QColor("#c6d35a"), QColor("#ffa3b1"),
					 QColor("#cbb8ff"), QColor("#7fe0f0"), QColor("#ffbb80"), QColor("#8ee8bb")};
	if (!nameOrder.contains(name))
		nameOrder.insert(name, nameOrder.size());
	return palette[nameOrder.value(name) % int(sizeof(palette) / sizeof(palette[0]))];
}

void DeskDock::addRow(QHBoxLayout *row)
{
	list->addLayout(row);
	rows << row;
	pruneRows();
}

void DeskDock::pruneRows()
{
	/* the oldest lines go once the chat is long, so a week-long OBS session stays light */
	while (rows.size() > kMaxRows) {
		QHBoxLayout *old = rows.takeFirst();
		list->removeItem(old);
		dropRow(old);
	}
	bodies.removeAll(QPointer<QLabel>());
}

int DeskDock::bubbleRoom() const
{
	/* most of the dock's width, less the bubble's padding and the chat's margins */
	return scroll ? qMax(80, scroll->viewport()->width() * 4 / 5 - 24) : 240;
}

void DeskDock::fitBubbles()
{
	/* as wide as the text needs, up to most of the dock: narrowing the dock rewraps the text
	 * rather than cutting it off */
	const int room = bubbleRoom();
	for (QLabel *label : bodies) {
		if (!label)
			continue;
		/* measured each time, so a bigger text size widens the bubbles too */
		int widest = 0;
		for (const QString &line : label->text().split(QLatin1Char('\n')))
			widest = qMax(widest, label->fontMetrics().horizontalAdvance(line));
		label->setMinimumWidth(qMin(widest + 4, room));
		if (auto *bubble = label->parentWidget())
			bubble->setMaximumWidth(room + 24);
	}
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
	addRow(row);
}

void DeskDock::addMessage(const QString &id, const QString &from, const QString &text, qint64 at, bool mine)
{
	empty->hide();
	const QDateTime when = QDateTime::fromMSecsSinceEpoch(at);
	addDateIfNew(when.date());
	const QString time = when.toString(QStringLiteral("HH:mm"));

	auto *bubble = new QFrame(inner);
	bubble->setObjectName(mine ? "out" : "in");
	/* a screen reader reads the bubble as one line: who, when and what */
	bubble->setAccessibleName(mine ? tr("You, %1: %2").arg(time, text) : tr("%1, %2: %3").arg(from, time, text));
	auto *v = new QVBoxLayout(bubble);
	v->setContentsMargins(9, 6, 9, 5);
	v->setSpacing(2);
	if (!mine) {
		auto *name = new QLabel(from, bubble);
		name->setTextFormat(Qt::PlainText); /* names come from the phones: never HTML */
		name->setStyleSheet(QStringLiteral("color: %1; font-weight: 600;").arg(nameColour(from).name()));
		v->addWidget(name);
	}
	auto *body = new QLabel(text, bubble);
	body->setObjectName("body");
	body->setTextFormat(Qt::PlainText); /* text from the phone: never rendered as HTML */
	body->setWordWrap(true);
	body->setTextInteractionFlags(Qt::TextSelectableByMouse);
	v->addWidget(body);
	auto *stamp = new QLabel(time, bubble);
	stamp->setObjectName(mine ? "outtime" : "time");
	stamp->setAlignment(Qt::AlignRight);
	v->addWidget(stamp);

	bodies << body;

	auto *row = new QHBoxLayout();
	if (mine)
		row->addStretch(1);
	row->addWidget(bubble);
	if (!mine)
		row->addStretch(1);
	addRow(row);
	fitBubbles();

	if (!mine) {
		unseen << id;
		announce(tr("%1 says: %2").arg(from, text));
		/* no sound: OBS may be capturing the PC's audio. Flash OBS in the taskbar instead. */
		if (!isVisible() || QApplication::applicationState() != Qt::ApplicationActive)
			QApplication::alert(window());
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
	addRow(row);
	scrollToEnd();
}

void DeskDock::announce(const QString &text)
{
	/* screen readers read new messages out, as the phone app and browser version do */
#if QT_VERSION >= QT_VERSION_CHECK(6, 8, 0)
	if (!QAccessible::isActive())
		return;
	QAccessibleAnnouncementEvent event(scroll, text);
	event.setPoliteness(QAccessible::AnnouncementPoliteness::Polite);
	QAccessible::updateAccessibility(&event);
#else
	Q_UNUSED(text);
#endif
}

void DeskDock::scrollToEnd()
{
	/* new lines bring the chat to the bottom; rangeChanged keeps it there once they're laid out */
	followEnd = true;
	scroll->verticalScrollBar()->setValue(scroll->verticalScrollBar()->maximum());
}

void DeskDock::markSeen()
{
	if (unseen.isEmpty() || !isVisible() || QApplication::applicationState() != Qt::ApplicationActive)
		return;
	emit seen(unseen);
	unseen.clear();
}
