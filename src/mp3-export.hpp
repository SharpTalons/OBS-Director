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

#pragma once

#include <QObject>
#include <QString>

#include <atomic>
#include <functional>
#include <string>
#include <thread>

/**
 * Turns an OBS recording's sound into an MP3, with the FFmpeg that OBS itself ships (which has
 * the LAME MP3 encoder). The first audio track is decoded, made stereo and encoded at 192 kb/s.
 *
 * [convertToMp3] does the work and is plain C++, so it can be tested on its own. [Mp3Export]
 * runs one conversion at a time on its own thread and reports back on the main thread.
 */

/** Converts [in] to an MP3 at [out]. [progress] gets 0..1 and returns false to stop. */
bool convertToMp3(const std::string &in, const std::string &out, const std::function<bool(double)> &progress,
		  std::string &error);

class Mp3Export : public QObject {
	Q_OBJECT

public:
	explicit Mp3Export(QObject *parent = nullptr);
	~Mp3Export() override;

	/** Whether OBS's FFmpeg is there to use (on Windows, the exact DLLs this was built for). */
	static bool available();

	bool busy() const { return running; }
	/** The recording being converted (its file name). */
	QString file() const { return name; }
	/** Starts converting [inPath] to [outPath]; [fileName] names it in progress reports. */
	bool start(const QString &fileName, const QString &inPath, const QString &outPath);
	void cancel() { stop = true; }

signals:
	void progress(const QString &fileName, int percent);
	void finished(const QString &fileName, bool ok, const QString &error);

private:
	std::thread worker;
	std::atomic<bool> running{false};
	std::atomic<bool> stop{false};
	QString name;
};
