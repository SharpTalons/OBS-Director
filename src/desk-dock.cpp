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
#include <QFrame>
#include <QHBoxLayout>
#include <QPushButton>
#include <QScrollArea>
#include <QTime>

DeskDock::DeskDock(QWidget *parent) : QWidget(parent)
{
	auto *root = new QVBoxLayout(this);
	root->setContentsMargins(10, 10, 10, 10);
	root->setSpacing(8);

	presence = new QLabel(tr("No remote connected"), this);
	presence->setWordWrap(true);
	presence->setStyleSheet("font-weight: 600;");
	root->addWidget(presence);

	auto *scroll = new QScrollArea(this);
	scroll->setWidgetResizable(true);
	scroll->setFrameShape(QFrame::NoFrame);
	auto *inner = new QWidget(scroll);
	messages = new QVBoxLayout(inner);
	messages->setContentsMargins(0, 0, 0, 0);
	messages->setSpacing(6);
	empty = new QLabel(tr("Messages from the remote appear here."), inner);
	empty->setWordWrap(true);
	empty->setStyleSheet("color: palette(mid);");
	messages->addWidget(empty);
	messages->addStretch(1);
	scroll->setWidget(inner);
	root->addWidget(scroll, 1);

	feed = new QLabel(this);
	feed->setWordWrap(true);
	feed->setStyleSheet("color: palette(mid); font-size: 11px;");
	root->addWidget(feed);
}

void DeskDock::setPresence(const QString &text, bool listening)
{
	presence->setText((listening ? QStringLiteral("● ") : QString()) + text);
}

void DeskDock::addMessage(const QString &id, const QString &from, const QString &text)
{
	empty->hide();
	auto *box = new QFrame(this);
	box->setFrameShape(QFrame::StyledPanel);
	box->setStyleSheet("QFrame { border: 1px solid #d9a441; border-radius: 4px; }");
	auto *v = new QVBoxLayout(box);
	auto *head = new QLabel(QTime::currentTime().toString("HH:mm") + " · " + from, box);
	head->setStyleSheet("border: none; color: palette(mid); font-size: 11px;");
	auto *body = new QLabel(text, box);
	body->setWordWrap(true);
	body->setStyleSheet("border: none; font-size: 14px;");
	v->addWidget(head);
	v->addWidget(body);

	auto *row = new QHBoxLayout();
	const QStringList answers = {tr("Got it"), tr("On it"), tr("Fixed")};
	for (const QString &a : answers) {
		auto *b = new QPushButton(a, box);
		row->addWidget(b);
		connect(b, &QPushButton::clicked, this, [this, box, id, a]() {
			emit replied(id, a);
			box->setStyleSheet("QFrame { border: 1px solid palette(mid); border-radius: 4px; }");
			for (auto *btn : box->findChildren<QPushButton *>())
				btn->setEnabled(false);
		});
	}
	v->addLayout(row);
	messages->insertWidget(0, box);
	QApplication::beep();
}

void DeskDock::addFeed(const QString &text)
{
	feedLines.prepend(QTime::currentTime().toString("HH:mm") + " · " + text);
	while (feedLines.size() > 5)
		feedLines.removeLast();
	feed->setText(feedLines.join("\n"));
}
