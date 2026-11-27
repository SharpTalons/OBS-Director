/*
OBS Director
Copyright (C) 2026 SharpTalons

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along
with this program. If not, see <https://www.gnu.org/licenses/>
*/

#include <obs-frontend-api.h>
#include <obs-module.h>
#include <plugin-support.h>

#include "desk-dock.hpp"
#include "director-server.hpp"
#include "video-preview.hpp"

#include <QDateTime>
#include <QDialog>
#include <QFormLayout>
#include <QHBoxLayout>
#include <QLabel>
#include <QListWidget>
#include <QMainWindow>
#include <QMessageBox>
#include <QPushButton>
#include <QVBoxLayout>

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE(PLUGIN_NAME, "en-US")

static DirectorServer *server = nullptr;
static DeskDock *dock = nullptr;

/* A string from data/locale, so the Tools window can be translated like the rest of OBS. */
static QString text(const char *key)
{
	return QString::fromUtf8(obs_module_text(key));
}

/* Tools > OBS Director: how to connect, this PC's address, and the phones allowed to connect. */
static void showConnectInfo(void *)
{
	if (!server)
		return;
	auto *main = static_cast<QMainWindow *>(obs_frontend_get_main_window());
	QDialog dlg(main);
	dlg.setWindowTitle(text("OBSDirector"));
	dlg.setMinimumWidth(420);
	auto *root = new QVBoxLayout(&dlg);

	auto *intro = new QLabel(text("OBSDirector.Tools.Intro"), &dlg);
	intro->setWordWrap(true);
	root->addWidget(intro);

	auto *form = new QFormLayout();
	form->addRow(text("OBSDirector.Tools.PcName"), new QLabel(server->pcName(), &dlg));
	auto *addr = new QLabel(&dlg);
	addr->setTextInteractionFlags(Qt::TextSelectableByMouse);
	auto showAddress = [addr]() {
		const QString a = server->advertisedAddress();
		addr->setText(a.isEmpty() ? text("OBSDirector.Tools.NoNetwork") : a);
	};
	showAddress();
	QObject::connect(server, &DirectorServer::addressChanged, &dlg, showAddress);
	form->addRow(text("OBSDirector.Tools.Address"), addr);
	auto *web = new QLabel(&dlg);
	web->setTextFormat(Qt::RichText);
	web->setOpenExternalLinks(true);
	web->setTextInteractionFlags(Qt::TextBrowserInteraction);
	auto showWeb = [web]() {
		const QString w = server->webAddress();
		web->setText(w.isEmpty() ? text("OBSDirector.Tools.NoNetwork")
					 : QStringLiteral("<a href=\"%1\">%1</a>").arg(w.toHtmlEscaped()));
	};
	showWeb();
	QObject::connect(server, &DirectorServer::addressChanged, &dlg, showWeb);
	form->addRow(text("OBSDirector.Tools.InBrowser"), web);
	form->addRow(text("OBSDirector.Tools.Plugin"), new QLabel(QString::fromUtf8(PLUGIN_VERSION), &dlg));
	root->addLayout(form);

	auto *heading = new QLabel(text("OBSDirector.Tools.Allowed"), &dlg);
	heading->setStyleSheet("font-weight: 600; margin-top: 8px;");
	root->addWidget(heading);
	auto *list = new QListWidget(&dlg);
	list->setMinimumHeight(110);
	root->addWidget(list);
	auto *empty = new QLabel(text("OBSDirector.Tools.NoneYet"), &dlg);
	empty->setWordWrap(true);
	root->addWidget(empty);

	auto *removeRow = new QHBoxLayout();
	auto *remove = new QPushButton(text("OBSDirector.Tools.Remove"), &dlg);
	auto *removeAll = new QPushButton(text("OBSDirector.Tools.RemoveAll"), &dlg);
	removeRow->addWidget(remove);
	removeRow->addWidget(removeAll);
	removeRow->addStretch(1);
	root->addLayout(removeRow);

	auto refresh = [list, empty, remove, removeAll]() {
		list->clear();
		for (const auto &t : server->trustedDevices()) {
			QString when = t.approvedAt ? QDateTime::fromMSecsSinceEpoch(t.approvedAt)
							      .toString(QStringLiteral("d MMM yyyy"))
						    : QString();
			auto *item = new QListWidgetItem(
				when.isEmpty() ? t.name
					       : t.name + QStringLiteral("  ·  ") +
							 text("OBSDirector.Tools.AllowedOn").arg(when),
				list);
			item->setData(Qt::UserRole, t.id);
			item->setData(Qt::AccessibleTextRole,
				      when.isEmpty() ? t.name
						     : t.name + QStringLiteral(", ") +
							       text("OBSDirector.Tools.AllowedOn").arg(when));
		}
		bool none = list->count() == 0;
		list->setVisible(!none);
		empty->setVisible(none);
		remove->setEnabled(false);
		removeAll->setEnabled(!none);
	};
	refresh();
	QObject::connect(server, &DirectorServer::devicesChanged, &dlg, refresh);
	QObject::connect(list, &QListWidget::itemSelectionChanged, &dlg,
			 [list, remove]() { remove->setEnabled(!list->selectedItems().isEmpty()); });
	QObject::connect(remove, &QPushButton::clicked, &dlg, [list]() {
		for (auto *item : list->selectedItems())
			server->forgetDevice(item->data(Qt::UserRole).toString());
	});
	QObject::connect(removeAll, &QPushButton::clicked, &dlg, [&dlg]() {
		if (QMessageBox::question(&dlg, text("OBSDirector"), text("OBSDirector.Tools.RemoveAllQuestion")) ==
		    QMessageBox::Yes)
			server->forgetAllDevices();
	});

	/* normal text colour: OBS's dark themes make palette(mid) too faint to read */
	auto *note = new QLabel(text("OBSDirector.Tools.Note"), &dlg);
	note->setWordWrap(true);
	root->addWidget(note);

	auto *buttons = new QHBoxLayout();
	auto *close = new QPushButton(text("OBSDirector.Tools.Close"), &dlg);
	buttons->addStretch(1);
	buttons->addWidget(close);
	root->addLayout(buttons);
	QObject::connect(close, &QPushButton::clicked, &dlg, &QDialog::accept);
	dlg.exec();
}

static void onFrontendEvent(enum obs_frontend_event event, void *)
{
	switch (event) {
	case OBS_FRONTEND_EVENT_FINISHED_LOADING: {
		auto *main = static_cast<QMainWindow *>(obs_frontend_get_main_window());
		dock = new DeskDock(main);
		if (!obs_frontend_add_dock_by_id("obs-director-desk", obs_module_text("OBSDirector.Dock"), dock))
			obs_log(LOG_WARNING, "could not add the desk dock");
		server = new DirectorServer(dock);
		obs_frontend_add_tools_menu_item(obs_module_text("OBSDirector.Tools"), showConnectInfo, nullptr);
		break;
	}
	case OBS_FRONTEND_EVENT_EXIT:
		delete server;
		server = nullptr;
		break;
	default:
		if (server)
			server->handleFrontendEvent(event);
		break;
	}
}

bool obs_module_load(void)
{
	VideoPreview::registerOutput();
	obs_frontend_add_event_callback(onFrontendEvent, nullptr);
	obs_log(LOG_INFO, "plugin loaded (version %s)", PLUGIN_VERSION);
	return true;
}

void obs_module_unload(void)
{
	obs_frontend_remove_event_callback(onFrontendEvent, nullptr);
	delete server;
	server = nullptr;
	obs_log(LOG_INFO, "plugin unloaded");
}
