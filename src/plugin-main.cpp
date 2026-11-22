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

/* Tools > OBS Director: how to connect, this PC's address, and the phones allowed to connect. */
static void showConnectInfo(void *)
{
	if (!server)
		return;
	auto *main = static_cast<QMainWindow *>(obs_frontend_get_main_window());
	QDialog dlg(main);
	dlg.setWindowTitle(QStringLiteral("OBS Director"));
	dlg.setMinimumWidth(420);
	auto *root = new QVBoxLayout(&dlg);

	auto *intro =
		new QLabel(QStringLiteral("Open OBS Director on your phone and tap this PC in the list. The first "
					  "time a phone connects, OBS asks you to allow it."),
			   &dlg);
	intro->setWordWrap(true);
	root->addWidget(intro);

	auto *form = new QFormLayout();
	form->addRow(QStringLiteral("PC name"), new QLabel(server->pcName(), &dlg));
	auto *addr = new QLabel(&dlg);
	addr->setTextInteractionFlags(Qt::TextSelectableByMouse);
	auto showAddress = [addr]() {
		const QString a = server->advertisedAddress();
		addr->setText(a.isEmpty() ? QStringLiteral("Not connected to a network") : a);
	};
	showAddress();
	QObject::connect(server, &DirectorServer::addressChanged, &dlg, showAddress);
	form->addRow(QStringLiteral("Address"), addr);
	form->addRow(QStringLiteral("Plugin"), new QLabel(QString::fromUtf8(PLUGIN_VERSION), &dlg));
	root->addLayout(form);

	auto *heading = new QLabel(QStringLiteral("Phones allowed to connect"), &dlg);
	heading->setStyleSheet("font-weight: 600; margin-top: 8px;");
	root->addWidget(heading);
	auto *list = new QListWidget(&dlg);
	list->setMinimumHeight(110);
	root->addWidget(list);
	auto *empty = new QLabel(QStringLiteral("None yet. Phones appear here once you press Allow."), &dlg);
	empty->setStyleSheet("color: palette(mid);");
	root->addWidget(empty);

	auto *removeRow = new QHBoxLayout();
	auto *remove = new QPushButton(QStringLiteral("Remove"), &dlg);
	auto *removeAll = new QPushButton(QStringLiteral("Remove all"), &dlg);
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
				when.isEmpty() ? t.name : t.name + QStringLiteral("  ·  allowed ") + when, list);
			item->setData(Qt::UserRole, t.id);
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
		if (QMessageBox::question(
			    &dlg, QStringLiteral("OBS Director"),
			    QStringLiteral("Remove every phone? Each one will need to be allowed again.")) ==
		    QMessageBox::Yes)
			server->forgetAllDevices();
	});

	auto *note = new QLabel(
		QStringLiteral(
			"A removed phone is disconnected and has to be allowed again. Clips are written into the "
			"recording as chapter markers when OBS records in Hybrid MP4 (Settings, Output, Recording "
			"Format)."),
		&dlg);
	note->setWordWrap(true);
	note->setStyleSheet("color: palette(mid);");
	root->addWidget(note);

	auto *buttons = new QHBoxLayout();
	auto *close = new QPushButton(QStringLiteral("Close"), &dlg);
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
