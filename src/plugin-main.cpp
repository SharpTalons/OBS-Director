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

#include <QDialog>
#include <QFormLayout>
#include <QHBoxLayout>
#include <QLabel>
#include <QMainWindow>
#include <QPushButton>
#include <QVBoxLayout>

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE(PLUGIN_NAME, "en-US")

static DirectorServer *server = nullptr;
static DeskDock *dock = nullptr;

/* Tools > OBS Director: what the phone needs to pair, and a way to change the PIN. */
static void showConnectInfo(void *)
{
	if (!server)
		return;
	auto *main = static_cast<QMainWindow *>(obs_frontend_get_main_window());
	QDialog dlg(main);
	dlg.setWindowTitle(QStringLiteral("OBS Director"));
	auto *root = new QVBoxLayout(&dlg);

	auto *intro = new QLabel(
		QStringLiteral(
			"Open OBS Director on your phone. It finds this PC on the network; enter the PIN below."),
		&dlg);
	intro->setWordWrap(true);
	root->addWidget(intro);

	auto *form = new QFormLayout();
	auto *pin = new QLabel(server->pin(), &dlg);
	pin->setStyleSheet("font-size: 28px; font-weight: 600; letter-spacing: 6px;");
	pin->setTextInteractionFlags(Qt::TextSelectableByMouse);
	form->addRow(QStringLiteral("PIN"), pin);
	form->addRow(QStringLiteral("PC name"), new QLabel(server->pcName(), &dlg));
	auto *addrs = new QLabel(server->addresses().join(QStringLiteral("\n")), &dlg);
	addrs->setTextInteractionFlags(Qt::TextSelectableByMouse);
	form->addRow(QStringLiteral("Address"), addrs);
	form->addRow(QStringLiteral("Port"), new QLabel(QString::number(DirectorServer::kControlPort), &dlg));
	form->addRow(QStringLiteral("Plugin"), new QLabel(QString::fromUtf8(PLUGIN_VERSION), &dlg));
	root->addLayout(form);

	auto *note = new QLabel(
		QStringLiteral("Clips are written into the recording as chapter markers when OBS records in Hybrid MP4 "
			       "(Settings, Output, Recording Format)."),
		&dlg);
	note->setWordWrap(true);
	note->setStyleSheet("color: palette(mid);");
	root->addWidget(note);

	auto *buttons = new QHBoxLayout();
	auto *regen = new QPushButton(QStringLiteral("New PIN"), &dlg);
	auto *close = new QPushButton(QStringLiteral("Close"), &dlg);
	buttons->addWidget(regen);
	buttons->addStretch(1);
	buttons->addWidget(close);
	root->addLayout(buttons);

	QObject::connect(regen, &QPushButton::clicked, &dlg, [pin]() {
		server->newPin();
		pin->setText(server->pin());
	});
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
