/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.
*/

#include "update-check.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>

#include <QDateTime>
#include <QDesktopServices>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMessageBox>
#include <QNetworkReply>
#include <QPushButton>
#include <QTimer>
#include <QUrl>

namespace {

const char *kRepo = "SharpTalons/OBS-Director";
constexpr qint64 kDayMs = 24LL * 60 * 60 * 1000;

QString text(const char *key)
{
	return QString::fromUtf8(obs_module_text(key));
}

QString configFile()
{
	char *p = obs_module_config_path("update.json");
	QString path = p ? QString::fromUtf8(p) : QString();
	bfree(p);
	return path;
}

} // namespace

UpdateCheck::UpdateCheck(std::function<void(const QString &)> n, QObject *parent) : QObject(parent), note(std::move(n))
{
	load();
	setStatus(text("OBSDirector.Update.NotChecked"));
}

bool UpdateCheck::newer(const QString &a, const QString &b)
{
	const QStringList x = a.split(QLatin1Char('.')), y = b.split(QLatin1Char('.'));
	for (int i = 0; i < qMax(x.size(), y.size()); ++i) {
		/* "1-beta" counts as 1 */
		const int p = i < x.size() ? x[i].section(QLatin1Char('-'), 0, 0).toInt() : 0;
		const int q = i < y.size() ? y[i].section(QLatin1Char('-'), 0, 0).toInt() : 0;
		if (p != q)
			return p > q;
	}
	return false;
}

void UpdateCheck::load()
{
	QFile f(configFile());
	if (!f.open(QIODevice::ReadOnly))
		return;
	const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
	enabled = o.value("auto").toBool(true);
	lastCheck = qint64(o.value("lastCheck").toDouble());
	skipped = o.value("skip").toString();
}

void UpdateCheck::save() const
{
	QFile f(configFile());
	if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate))
		return;
	QJsonObject o;
	o["auto"] = enabled;
	o["lastCheck"] = double(lastCheck);
	o["skip"] = skipped;
	f.write(QJsonDocument(o).toJson());
}

void UpdateCheck::setAutoCheck(bool on)
{
	enabled = on;
	save();
}

void UpdateCheck::setStatus(const QString &s)
{
	statusText = s;
	emit statusChanged(s);
}

void UpdateCheck::checkSoon()
{
	if (!enabled || QDateTime::currentMSecsSinceEpoch() - lastCheck < kDayMs)
		return;
	/* give OBS a moment to finish starting first */
	QTimer::singleShot(15000, this, [this]() { fetch(false); });
}

void UpdateCheck::checkNow()
{
	fetch(true);
}

void UpdateCheck::fetch(bool manual)
{
	if (busy)
		return;
	busy = true;
	setStatus(text("OBSDirector.Update.Checking"));
	QNetworkRequest req(
		QUrl(QStringLiteral("https://api.github.com/repos/%1/releases/latest").arg(QLatin1String(kRepo))));
	req.setRawHeader("Accept", "application/vnd.github+json");
	req.setRawHeader("User-Agent", "OBS-Director-plugin");
	req.setTransferTimeout(10000);
	QNetworkReply *reply = net.get(req);
	connect(reply, &QNetworkReply::finished, this, [this, reply, manual]() {
		reply->deleteLater();
		busy = false;
		const int code = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
		if (reply->error() != QNetworkReply::NoError || code != 200) {
			obs_log(LOG_INFO, "update check failed: %s", reply->errorString().toUtf8().constData());
			setStatus(text("OBSDirector.Update.Failed"));
			return;
		}
		const QJsonObject o = QJsonDocument::fromJson(reply->readAll()).object();
		const QString tag = o.value("tag_name").toString();
		if (tag.isEmpty()) {
			setStatus(text("OBSDirector.Update.Failed"));
			return;
		}
		lastCheck = QDateTime::currentMSecsSinceEpoch();
		save();
		latest = tag.startsWith(QLatin1Char('v')) ? tag.mid(1) : tag;
		page = o.value("html_url")
			       .toString(QStringLiteral("https://github.com/%1/releases/latest")
						 .arg(QLatin1String(kRepo)));
		notes = o.value("body").toString().trimmed();
		installer = page;
		/* the Windows installer on the release, if it is there */
		for (const auto &a : o.value("assets").toArray()) {
			const QJsonObject asset = a.toObject();
			if (asset.value("name").toString().endsWith(QStringLiteral("-Windows-Setup.exe"))) {
				installer = asset.value("browser_download_url").toString(page);
				break;
			}
		}
		if (!newer(latest, QString::fromUtf8(PLUGIN_VERSION))) {
			setStatus(text("OBSDirector.Update.UpToDate").arg(QString::fromUtf8(PLUGIN_VERSION)));
			return;
		}
		setStatus(text("OBSDirector.Update.Available").arg(latest));
		/* a version the desk chose to skip only comes up again when someone checks by hand */
		if (manual || latest != skipped)
			offer(manual);
	});
}

void UpdateCheck::obsIdle()
{
	if (held && !obs_frontend_streaming_active() && !obs_frontend_recording_active()) {
		held = false;
		offer(false);
	}
}

void UpdateCheck::offer(bool manual)
{
	/* never in the middle of a stream or recording: a note now, the question afterwards */
	if (!manual && (obs_frontend_streaming_active() || obs_frontend_recording_active())) {
		if (!held && note)
			note(text("OBSDirector.Update.Note").arg(latest));
		held = true;
		return;
	}
	auto *main = static_cast<QWidget *>(obs_frontend_get_main_window());
	auto *box = new QMessageBox(main);
	box->setWindowTitle(text("OBSDirector"));
	box->setIcon(QMessageBox::Information);
	box->setTextFormat(Qt::PlainText);
	box->setText(text("OBSDirector.Update.Title").arg(latest, QString::fromUtf8(PLUGIN_VERSION)));
	box->setInformativeText(text("OBSDirector.Update.How"));
	if (!notes.isEmpty())
		box->setDetailedText(notes.left(4000));
	QPushButton *download = box->addButton(text("OBSDirector.Update.Download"), QMessageBox::AcceptRole);
	QPushButton *skip = box->addButton(text("OBSDirector.Update.Skip"), QMessageBox::DestructiveRole);
	box->addButton(text("OBSDirector.Update.Later"), QMessageBox::RejectRole);
	box->setDefaultButton(download);
	box->setAttribute(Qt::WA_DeleteOnClose);
	box->setWindowModality(Qt::NonModal);
	const QString version = latest, url = installer;
	connect(box, &QMessageBox::buttonClicked, this, [this, download, skip, version, url](QAbstractButton *b) {
		if (b == download) {
			QDesktopServices::openUrl(QUrl(url));
		} else if (b == skip) {
			skipped = version;
			save();
		}
	});
	box->show();
}
