/*
 * OBS Director in a browser. Served by the OBS plugin on TCP 4470; talks to it over a
 * WebSocket on /ws with the same messages the phone app uses (docs/PROTOCOL.md).
 * Everything this page keeps (settings, clips, the session audio for playing clips back)
 * stays in this browser.
 */
'use strict';

/* the plugin writes its own version here as it serves this file */
const VERSION = '@PLUGIN_VERSION@';

/* =============================================================== helpers */

const $ = (sel, root = document) => root.querySelector(sel);

/** h('div.class#id', {attrs}, ...children): a small element builder. */
function h(spec, attrs, ...kids) {
	const m = /^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i.exec(spec) || [];
	const el = document.createElement(m[1] || 'div');
	for (const part of (m[2] || '').match(/[.#][\w-]+/g) || []) {
		if (part[0] === '.') el.classList.add(part.slice(1));
		else el.id = part.slice(1);
	}
	if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) {
		kids.unshift(attrs);
		attrs = null;
	}
	for (const [k, v] of Object.entries(attrs || {})) {
		if (v === undefined || v === null || v === false) continue;
		if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
		else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
		else if (k === 'text') el.textContent = v;
		else if (k === 'html') el.innerHTML = v; /* only ever our own icon markup */
		else el.setAttribute(k, v === true ? '' : v);
	}
	for (const kid of kids.flat(Infinity)) {
		if (kid === null || kid === undefined || kid === false) continue;
		el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
	}
	return el;
}

const pad2 = (n) => String(n).padStart(2, '0');
/** 01:02:03 */
function tc(ms) {
	const s = Math.max(0, Math.floor(ms / 1000));
	return pad2(Math.floor(s / 3600)) + ':' + pad2(Math.floor(s / 60) % 60) + ':' + pad2(s % 60);
}
/** "45 s", "2 m 05 s" */
function len(ms) {
	const s = Math.round(ms / 1000);
	return s < 60 ? s + ' s' : Math.floor(s / 60) + ' m ' + pad2(s % 60) + ' s';
}
/** the clip button's length: 1:06, 12:30, 1:02:03 */
function clipLength(ms) {
	const s = Math.max(0, Math.floor(ms / 1000));
	const hh = Math.floor(s / 3600), mm = Math.floor(s / 60) % 60, ss = s % 60;
	return hh > 0 ? hh + ':' + pad2(mm) + ':' + pad2(ss) : mm + ':' + pad2(ss);
}
function durationText(ms) {
	const m = Math.round(ms / 60000);
	return m < 60 ? m + ' min' : Math.floor(m / 60) + ' h ' + pad2(m % 60) + ' min';
}
const newId = () => {
	const b = new Uint8Array(16);
	crypto.getRandomValues(b);
	return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('').replace(/^(.{8})(.{4})(.{4})(.{4})/, '$1-$2-$3-$4-');
};

function icon(name, size = 20) {
	const paths = {
		moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>',
		play: '<path d="M7 4l13 8-13 8z"/>',
		stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
		pause: '<path d="M8 5v14M16 5v14"/>',
		rec: '<circle cx="12" cy="12" r="6"/>',
		plus: '<path d="M12 5v14M5 12h14"/>',
		chev: '<path d="M9 6l6 6-6 6"/>',
		back: '<path d="M15 6l-6 6 6 6"/>',
		tune: '<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>',
		edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/>',
		down: '<path d="M12 4v11M7 10l5 5 5-5M5 20h14"/>',
		trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
		send: '<path d="M4 12l16-8-6 16-3-6z"/>',
		head: '<path d="M4 15v-3a8 8 0 0 1 16 0v3"/><rect x="3" y="14" width="5" height="7" rx="2"/><rect x="16" y="14" width="5" height="7" rx="2"/>',
		pc: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>',
		power: '<path d="M12 3v9M6.3 7.3a8 8 0 1 0 11.4 0"/>',
		export: '<path d="M12 15V3M7 8l5-5 5 5M5 14v6h14v-6"/>',
		check: '<path d="M5 12l5 5 9-10"/>',
		undo: '<path d="M9 14L4 9l5-5"/><path d="M4 9h10a6 6 0 0 1 0 12h-3"/>',
		close: '<path d="M6 6l12 12M18 6L6 18"/>',
		clock: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>',
		ticks: '<path d="M2 13l4 4 9-10M10 16l1 1 11-11"/>',
		stage: '<path d="M3 4h9v5a4.5 4.5 0 0 1-9 0z"/><path d="M5.5 7h.01M9.5 7h.01M6 10.3c1 .8 2 .8 3 0"/><path d="M12 10h9v5a4.5 4.5 0 0 1-9 0z"/><path d="M14.5 13h.01M18.5 13h.01M15 17.2c1-.8 2-.8 3 0"/>',
		alert: '<path d="M12 8v5M12 17h.01"/><circle cx="12" cy="12" r="9"/>',
		info: '<path d="M12 11v6M12 7h.01"/><circle cx="12" cy="12" r="9"/>',
		name: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M7 10h6M7 14h10"/>',
		rewind: '<path d="M11 19l-8-7 8-7zM21 19l-8-7 8-7z"/>',
		forward: '<path d="M13 19l8-7-8-7zM3 19l8-7-8-7z"/>',
		bookmark: '<path d="M6 3h12v18l-6-4-6 4z"/>',
		file: '<path d="M6 3h8l4 4v14H6z"/><path d="M9 14l2 2 4-4"/>',
		keyboard: '<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/>',
		mute: '<path d="M4 9h4l5-4v14l-5-4H4zM17 9l4 6M21 9l-4 6"/>',
		peak: '<path d="M3 17l5-6 4 4 5-8 4 5"/>',
		disk: '<ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/>',
		chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
		mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
		calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/>',
		timer: '<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2 2M9 2h6"/>',
		tap: '<path d="M9 11V5a2 2 0 0 1 4 0v6M13 10a2 2 0 0 1 4 0v4a6 6 0 0 1-6 6h-1a6 6 0 0 1-5-3l-1.5-3a1.6 1.6 0 0 1 2.8-1.6L9 15"/>',
		vibrate: '<rect x="8" y="4" width="8" height="16" rx="2"/><path d="M4 8v8M20 8v8"/>',
		more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
	};
	return h('span', { 'aria-hidden': 'true', style: { display: 'inline-flex' }, html: `<svg width="${size}" height="${size}" viewBox="0 0 24 24">${paths[name] || ''}</svg>` });
}

/** localStorage, quietly: a private window or blocked storage just means nothing is kept. */
const store = {
	get(key, fallback) {
		try {
			const v = localStorage.getItem('obsdirector.' + key);
			return v === null ? fallback : JSON.parse(v);
		} catch {
			return fallback;
		}
	},
	set(key, value) {
		try {
			localStorage.setItem('obsdirector.' + key, JSON.stringify(value));
		} catch { /* full or blocked */ }
	},
};

/* =============================================================== settings */

function defaultName() {
	const ua = navigator.userAgent;
	const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
	const os = /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'Mac' : /Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : 'this computer';
	return browser + ' on ' + os;
}

const settings = Object.assign({
	deviceName: defaultName(),
	autoListen: true,
	volume: 1,
	saveAudio: true,
	keepDays: 30,
	leadMinutes: 1,
	padSeconds: 2,
	chapters: true,
	pcClipLists: true,
	exportFormat: 'Resolve EDL',
	silenceSeconds: 10,
	alertClipping: true,
	alertRecStop: true,
	diskHours: 2,
	dropsPerMinute: 100,
	holdMs: 1000,
	touchSound: 50,
	haptics: 2,
	allowStreamStop: false,
	spaceForClip: true,
}, store.get('settings', {}));
function saveSettings() { store.set('settings', settings); }

let deviceId = store.get('deviceId', '');
if (!/^[A-Za-z0-9-]{16,64}$/.test(deviceId)) {
	deviceId = newId();
	store.set('deviceId', deviceId);
}

/* =============================================================== recordings and clips */

/** One recording as this browser knows it: {file, startedAt, durationMs, live, clips[], savedOnPc} */
let recordings = store.get('recordings', []);
function saveRecordings() {
	store.set('recordings', recordings);
	scheduleClipLists();
	render();
}
function ensureRecording(file, live) {
	let r = recordings.find((x) => x.file === file);
	if (!r) {
		r = { file, startedAt: Date.now(), durationMs: 0, live, clips: [], savedOnPc: null };
		recordings.unshift(r);
	}
	return r;
}
function recordingTitle(r) {
	const d = new Date(r.startedAt);
	const today = new Date();
	const time = pad2(d.getHours()) + ':' + pad2(d.getMinutes());
	const dayMs = 86400000;
	const days = Math.round((new Date(today.toDateString()) - new Date(d.toDateString())) / dayMs);
	if (days === 0) return 'Today, ' + time;
	if (days === 1) return 'Yesterday, ' + time;
	return d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'short' }) + ', ' + time;
}
const clipsText = (n) => (n === 0 ? 'No clips' : n === 1 ? '1 clip' : n + ' clips');

/* ---------- export: the same formats as the phone (core/Exporter.kt) ---------- */
const Exporter = {
	FPS: 30,
	formats: [
		{ id: 'Resolve EDL', badge: 'EDL', label: 'DaVinci Resolve', hint: 'Markers spanning each clip', ext: 'edl', tint: 'blue' },
		{ id: 'CSV', badge: 'CSV', label: 'Spreadsheet', hint: 'Start, end, length and note', ext: 'csv', tint: 'ok' },
		{ id: 'Text', badge: 'TXT', label: 'Plain list', hint: 'To paste into a message', ext: 'txt', tint: 'amber' },
	],
	tcf(ms) {
		const s = Math.floor(ms / 1000);
		const f = Math.floor(((ms % 1000) * this.FPS) / 1000);
		return pad2(Math.floor(s / 3600)) + ':' + pad2(Math.floor(s / 60) % 60) + ':' + pad2(s % 60) + ':' + pad2(f);
	},
	edl(r) {
		let out = 'TITLE: ' + r.file.replace(/\.[^.]*$/, '') + '\nFCM: NON-DROP FRAME\n';
		r.clips.forEach((c, i) => {
			const durFrames = Math.max(1, Math.floor(((c.endMs - c.startMs) * this.FPS + 500) / 1000));
			const a = this.tcf(c.startMs);
			const b = this.tcf(c.startMs + Math.floor((durFrames * 1000) / this.FPS));
			const name = (c.note || '').trim() ? c.note.replace(/\|/g, '/') : 'Clip ' + (i + 1);
			out += '\n' + String(i + 1).padStart(3, '0') + '  001      V     C        ' + a + ' ' + b + ' ' + a + ' ' + b + '  \n';
			out += ' |C:ResolveColorGreen |M:' + name + ' |D:' + durFrames + '\n';
		});
		return out;
	},
	csv(r) {
		let out = 'number,start,end,duration_s,tags,note\n';
		r.clips.forEach((c, i) => {
			out += (i + 1) + ',' + this.tcf(c.startMs) + ',' + this.tcf(c.endMs) + ',' + ((c.endMs - c.startMs) / 1000).toFixed(1) + ',"","' + (c.note || '').replace(/"/g, '""') + '"\n';
		});
		return out;
	},
	text(r) {
		return r.clips.map((c) => tc(c.startMs) + ' - ' + tc(c.endMs) + '  (' + len(c.endMs - c.startMs) + ')  ' + ((c.note || '').trim() || 'Clip')).join('\n');
	},
	export(r, id) { return id === 'Resolve EDL' ? this.edl(r) : id === 'CSV' ? this.csv(r) : this.text(r); },
	ext(id) { return (this.formats.find((f) => f.id === id) || this.formats[2]).ext; },
};

function download(name, blob) {
	const url = URL.createObjectURL(blob);
	const a = h('a', { href: url, download: name });
	document.body.append(a);
	a.click();
	a.remove();
	setTimeout(() => URL.revokeObjectURL(url), 10000);
}

/* =============================================================== connection */

const state = {
	link: 'connecting', // connecting, pending, connected, lost, refused
	pendingMessage: '',
	error: '',
	pc: '',
	plugin: '',
	obs: '',
	latencyMs: 0,
	status: null,
	statusAt: 0,
	clipStart: null,
	lastSaved: '',
	alerts: [],
	desk: [], // the desk chat, from OBS: {id, from, text, at, desk, mine, state}
	deskUnread: 0,
	/** the most urgent unread line: 0 messages only, 1 an alert, 2 a severe alert */
	deskAlert: 0,
	health: [], // dropped frames per minute, last 30
	listening: false,
	video: false,
	lostAt: 0,
};

let ws = null;
let nextCmd = 1;
const waiting = new Map();
let retry = 0;
let pingTimer = 0;

function connect() {
	state.link = state.link === 'connected' ? 'lost' : state.link === 'lost' ? 'lost' : 'connecting';
	try {
		ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
	} catch (e) {
		return scheduleReconnect();
	}
	ws.binaryType = 'arraybuffer';
	ws.onopen = () => {
		retry = 0;
		send({ type: 'hello', deviceId, device: settings.deviceName.slice(0, 40), proto: 2, client: 'web', page: VERSION });
	};
	ws.onmessage = (e) => {
		if (typeof e.data === 'string') {
			let msg;
			try { msg = JSON.parse(e.data); } catch { return; }
			onMessage(msg);
		} else {
			onBinary(new Uint8Array(e.data));
		}
	};
	ws.onclose = () => {
		for (const w of waiting.values()) w({ ok: false, error: 'not_connected' });
		waiting.clear();
		clearInterval(pingTimer);
		if (state.link === 'connected') {
			state.link = 'lost';
			state.lostAt = Date.now();
			chatNote({ kind: 'alert', severe: true, from: 'Connection to ' + (state.pc || 'the PC') + ' lost.', text: 'Listening paused. The recording carries on at the PC. Reconnecting…' });
			feedback.buzz([200, 100, 200]);
			endClipIfRunning(', ended when the connection dropped');
			audio.pause();
			video.reset();
		}
		if (state.link !== 'refused') scheduleReconnect();
		render();
	};
}

function scheduleReconnect() {
	retry++;
	setTimeout(connect, Math.min(10000, 500 * 2 ** Math.min(retry, 5)));
}

function send(obj) {
	if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

/** A command; resolves with the plugin's result. */
function command(cmd, args = {}) {
	return new Promise((resolve) => {
		if (!ws || ws.readyState !== WebSocket.OPEN || state.link !== 'connected') return resolve({ ok: false, error: 'not_connected' });
		const id = nextCmd++;
		waiting.set(id, resolve);
		send({ type: 'cmd', id, cmd, args });
		setTimeout(() => {
			if (waiting.delete(id)) resolve({ ok: false, error: 'timeout' });
		}, 5000);
	});
}
async function act(cmd, args) {
	const r = await command(cmd, args);
	if (!r.ok) toast("The PC couldn't do that (" + (r.error || 'error') + ').');
	return r;
}

function onMessage(m) {
	switch (m.type) {
	case 'pending':
		state.link = 'pending';
		state.pendingMessage = m.message || '';
		render();
		break;
	case 'error':
		state.link = 'refused';
		state.error = m.message || m.code;
		state.errorCode = m.code;
		render();
		/* Deny and no answer let the browser ask again after a short wait */
		if (m.code === 'denied' || m.code === 'approval_timeout' || m.code === 'locked_out' || m.code === 'busy')
			setTimeout(() => { if (state.link === 'refused') { state.link = 'connecting'; connect(); render(); } }, m.code === 'denied' ? 61000 : 31000);
		break;
	case 'welcome': {
		const wasLost = state.link === 'lost';
		state.link = 'connected';
		state.pc = m.pc || 'OBS PC';
		state.plugin = m.plugin || '';
		state.obs = m.obs || '';
		state.error = '';
		clearInterval(pingTimer);
		pingTimer = setInterval(() => send({ type: 'ping', t: performance.now() }), 2000);
		if (wasLost) chatNote({ kind: 'note', text: 'Reconnected after ' + len(Date.now() - state.lostAt) + '. Listening again.' });
		if (state.listening || (settings.autoListen && audio.unlocked)) audio.start();
		if (state.video) video.start();
		loadDesk();
		sentClipLists.clear();
		scheduleClipLists();
		if (view.name === 'connect') view.name = 'monitor';
		render();
		break;
	}
	case 'pong':
		if (typeof m.t === 'number') state.latencyMs = Math.round((performance.now() - m.t) / 2);
		break;
	case 'status':
		onStatus(m);
		break;
	case 'result': {
		const w = waiting.get(m.id);
		if (w) { waiting.delete(m.id); w(m); }
		break;
	}
	case 'event':
		onEvent(m.event, m.data || {});
		break;
	}
}

function onStatus(m) {
	const prev = state.status;
	const rec = m.rec || {}, stream = m.stream || {}, pc = m.pc || {};
	state.status = {
		recActive: !!rec.active, recPaused: !!rec.paused, fileTimeMs: rec.fileTimeMs || 0, file: rec.file || '',
		container: rec.container || '', recBytes: rec.bytes || 0, diskFreeMb: rec.diskFreeMB ?? -1,
		streamActive: !!stream.active, streamDurationMs: stream.durationMs || 0, streamKbps: stream.kbps || 0,
		dropped: stream.dropped || 0, totalFrames: stream.total || 0, congestion: stream.congestion || 0,
		cpu: pc.cpu || 0, fps: pc.fps || 0, renderLag: pc.renderLag || 0, encodeLag: pc.encodeLag || 0,
		scene: m.scene || '',
	};
	state.statusAt = performance.now();
	const s = state.status;
	if (s.recActive && s.file) {
		const r = ensureRecording(s.file, true);
		r.live = true;
		r.durationMs = s.fileTimeMs;
	}
	if (prev && prev.recActive && !s.recActive) {
		endClipIfRunning(', ended when the recording stopped');
		const r = recordings.find((x) => x.file === prev.file);
		if (r) { r.live = false; r.durationMs = prev.fileTimeMs; saveRecordings(); }
	}
	audio.statusChanged(s);
	dropHistory.push([Date.now(), s.dropped]);
	while (dropHistory.length && dropHistory[0][0] < Date.now() - 70000) dropHistory.shift();
}

/** Where the recording is now, between status messages. */
function nowFileTimeMs() {
	const s = state.status;
	if (!s || !s.recActive) return s ? s.fileTimeMs : 0;
	return s.fileTimeMs + (s.recPaused ? 0 : performance.now() - state.statusAt);
}

function onEvent(name, d) {
	if (name === 'record.stopped') {
		if (settings.alertRecStop && d.by !== settings.deviceName && !stopRequestedHere)
			raise({ id: 'recstop', severe: true, title: 'Recording stopped on the OBS PC.', body: d.by ? d.by + ' stopped it.' : 'Someone stopped it in OBS.', offerStart: true });
		stopRequestedHere = false;
	} else if (name === 'record.started') {
		clearAlert('recstop');
	} else if (name === 'desk.message') {
		const m = chatMessage(d);
		const known = state.desk.find((x) => x.id === m.id);
		if (known) { known.at = m.at; if (known.state !== 'seen') known.state = m.state; }
		else {
			state.desk.push(m);
			if (state.desk.length > 500) state.desk.splice(0, state.desk.length - 500);
			if (!m.mine && view.name !== 'desk') { state.deskUnread++; toast(m.from + ': ' + m.text.slice(0, 80)); }
		}
		render();
	} else if (name === 'desk.seen') {
		const ids = new Set(d.ids || []);
		for (const m of state.desk) if (m.mine && ids.has(m.id)) m.state = 'seen';
		render();
	}
}
let stopRequestedHere = false;

/* =============================================================== saved session audio (IndexedDB) */

const idb = {
	db: null,
	open() {
		if (this.db || !window.indexedDB) return Promise.resolve(this.db);
		return new Promise((resolve) => {
			let req;
			try { req = indexedDB.open('obsdirector', 1); } catch { return resolve(null); }
			req.onupgradeneeded = () => {
				const s = req.result.createObjectStore('audio', { keyPath: 'id' });
				s.createIndex('file', 'file');
			};
			req.onsuccess = () => { this.db = req.result; resolve(this.db); };
			req.onerror = () => resolve(null);
		});
	},
	async put(seg) {
		const db = await this.open();
		if (!db) return;
		await new Promise((res) => {
			const tx = db.transaction('audio', 'readwrite');
			tx.objectStore('audio').put(seg);
			tx.oncomplete = tx.onerror = tx.onabort = () => res();
		});
	},
	async forFile(file) {
		const db = await this.open();
		if (!db) return [];
		return new Promise((res) => {
			const req = db.transaction('audio').objectStore('audio').index('file').getAll(file);
			req.onsuccess = () => res(req.result.sort((a, b) => a.startMs - b.startMs));
			req.onerror = () => res([]);
		});
	},
	/** Deletes the saved audio of one recording, or of all of them. */
	async remove(file) {
		const db = await this.open();
		if (!db) return;
		const tx = db.transaction('audio', 'readwrite');
		tx.objectStore('audio').openCursor().onsuccess = (e) => {
			const c = e.target.result;
			if (!c) return;
			if (file === undefined || c.value.file === file) c.delete();
			c.continue();
		};
	},
	async prune(days) {
		const db = await this.open();
		if (!db || !days) return;
		const cutoff = Date.now() - days * 86400000;
		const tx = db.transaction('audio', 'readwrite');
		tx.objectStore('audio').openCursor().onsuccess = (e) => {
			const c = e.target.result;
			if (!c) return;
			if (c.value.at < cutoff) c.delete();
			c.continue();
		};
	},
};

/* =============================================================== listening */

const audio = {
	ctx: null,
	gain: null,
	shaper: null,
	recDest: null,
	unlocked: false,
	nextTime: 0,
	batchL: [],
	batchR: [],
	batchFrames: 0,
	batchFileUs: 0,
	timeline: [], // [ctxTime, fileTimeUs] of scheduled buffers, to time the saved audio
	lastSeq: -1,
	lastPacketAt: 0,
	dropouts: [],
	peak: [-Infinity, -Infinity],
	clippedAt: -Infinity,
	recorder: null,
	segment: null,

	ensure() {
		if (this.ctx) return this.ctx;
		const C = window.AudioContext || window.webkitAudioContext;
		if (!C) return null;
		this.ctx = new C({ latencyHint: 'interactive' });
		this.gain = this.ctx.createGain();
		this.shaper = this.ctx.createWaveShaper();
		this.gain.connect(this.shaper);
		this.shaper.connect(this.ctx.destination);
		try { this.recDest = this.ctx.createMediaStreamDestination(); } catch { this.recDest = null; }
		this.setVolume(settings.volume);
		return this.ctx;
	},
	/** Called from a click: browsers only let a page make sound after one. */
	unlock() {
		if (!this.ensure()) return;
		this.ctx.resume();
		this.unlocked = true;
	},
	setVolume(v) {
		settings.volume = Math.max(0, Math.min(3, v));
		if (!this.gain) return;
		this.gain.gain.value = settings.volume;
		/* above 100% a soft limiter keeps peaks from crackling, as on the phone */
		if (settings.volume > 1) {
			const n = 1024, curve = new Float32Array(n);
			for (let i = 0; i < n; i++) {
				const x = (i / (n - 1)) * 2 - 1, a = Math.abs(x);
				curve[i] = a <= 0.7 ? x : Math.sign(x) * (0.7 + 0.3 * Math.tanh((a - 0.7) / 0.3));
			}
			this.shaper.curve = curve;
		} else {
			this.shaper.curve = null;
		}
	},
	async start() {
		this.unlock();
		if (!this.ctx) return toast("This browser can't play audio.");
		state.listening = true;
		render();
		const r = await command('audio.start', { ws: true });
		if (!r.ok && state.link === 'connected') {
			state.listening = false;
			toast("The PC couldn't start the mix (" + (r.error || 'error') + ').');
		}
		this.nextTime = 0;
		this.lastSeq = -1;
		render();
	},
	async stop() {
		state.listening = false;
		this.endSegment();
		render();
		await command('audio.stop');
	},
	/** The connection dropped: listening resumes on reconnect. */
	pause() {
		this.endSegment();
		this.nextTime = 0;
	},
	receiving() { return performance.now() - this.lastPacketAt < 1000; },
	delayMs() { return this.ctx && this.nextTime ? Math.max(0, Math.round((this.nextTime - this.ctx.currentTime) * 1000)) : 0; },
	dropoutsLastMinute() {
		const cutoff = performance.now() - 60000;
		while (this.dropouts.length && this.dropouts[0] < cutoff) this.dropouts.shift();
		return this.dropouts.length;
	},

	onPacket(u8) {
		if (!state.listening || !this.ctx || u8.length < 24) return;
		const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
		const seq = dv.getUint32(4, true);
		const fileUs = dv.getUint32(8, true) + dv.getUint32(12, true) * 4294967296;
		const frames = dv.getUint16(20, true);
		const ch = u8[22];
		if (ch !== 2 || u8.length < 24 + frames * 4) return;
		if (this.lastSeq >= 0 && seq !== (this.lastSeq + 1) >>> 0) this.dropouts.push(performance.now());
		this.lastSeq = seq;
		this.lastPacketAt = performance.now();
		if (!this.batchFrames) this.batchFileUs = fileUs;
		let pl = 0, pr = 0;
		for (let i = 0; i < frames; i++) {
			const l = dv.getInt16(24 + i * 4, true) / 32768, r = dv.getInt16(26 + i * 4, true) / 32768;
			this.batchL.push(l);
			this.batchR.push(r);
			const al = Math.abs(l), ar = Math.abs(r);
			if (al > pl) pl = al;
			if (ar > pr) pr = ar;
		}
		this.batchFrames += frames;
		meters.push(pl, pr);
		if (pl >= 0.999 || pr >= 0.999) this.clippedAt = performance.now();
		if (this.batchFrames >= 960) this.flush();
	},
	flush() {
		const ctx = this.ctx, n = this.batchFrames;
		const buf = ctx.createBuffer(2, n, 48000);
		buf.copyToChannel(Float32Array.from(this.batchL), 0);
		buf.copyToChannel(Float32Array.from(this.batchR), 1);
		const fileUs = this.batchFileUs;
		this.batchL = []; this.batchR = []; this.batchFrames = 0;
		const now = ctx.currentTime;
		/* about 120 ms of buffer: enough for Wi-Fi to stutter without clicks */
		if (this.nextTime < now + 0.02) this.nextTime = now + 0.12;
		if (this.nextTime > now + 0.5) return; /* too far behind: skip ahead rather than drift */
		const src = ctx.createBufferSource();
		src.buffer = buf;
		src.connect(this.gain);
		if (this.recDest) src.connect(this.recDest);
		src.start(this.nextTime);
		this.timeline.push([this.nextTime, fileUs]);
		if (this.timeline.length > 200) this.timeline.splice(0, 100);
		this.nextTime += n / 48000;
		this.maybeSegment();
	},
	/** The recording's file time of what's coming out of the speakers now. */
	fileMsNow() {
		const now = this.ctx.currentTime;
		let best = null;
		for (const e of this.timeline) if (e[0] <= now) best = e;
		return best ? best[1] / 1000 + (now - best[0]) * 1000 : null;
	},

	/* ----- saving the session audio, in 30 s pieces, so clips can be played back ----- */
	statusChanged(s) {
		if (!s.recActive || s.recPaused) this.endSegment();
	},
	maybeSegment() {
		const s = state.status;
		const want = settings.saveAudio && state.listening && s && s.recActive && !s.recPaused && this.recDest && window.MediaRecorder;
		if (!want) return this.endSegment();
		if (this.segment && performance.now() - this.segment.started > 30000) this.endSegment();
		if (this.segment) return;
		const fileMs = this.fileMsNow();
		if (fileMs === null) return;
		const mime = ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4'].find((t) => MediaRecorder.isTypeSupported(t)) || '';
		let rec;
		try { rec = new MediaRecorder(this.recDest.stream, mime ? { mimeType: mime, audioBitsPerSecond: 96000 } : undefined); } catch { return; }
		const seg = { id: newId(), file: s.file, startMs: Math.round(fileMs), ctxStart: this.ctx.currentTime, started: performance.now(), mime: rec.mimeType, chunks: [] };
		rec.ondataavailable = (e) => { if (e.data && e.data.size) seg.chunks.push(e.data); };
		rec.onstop = () => {
			const blob = new Blob(seg.chunks, { type: seg.mime || 'audio/webm' });
			if (blob.size) idb.put({ id: seg.id, file: seg.file, startMs: seg.startMs, durMs: seg.durMs, mime: blob.type, blob, at: Date.now() });
		};
		rec.start();
		seg.rec = rec;
		this.segment = seg;
	},
	endSegment() {
		const seg = this.segment;
		if (!seg) return;
		this.segment = null;
		seg.durMs = Math.round((this.ctx.currentTime - seg.ctxStart) * 1000);
		try { seg.rec.stop(); } catch { /* already stopped */ }
	},
};

/** Decoded saved audio for [fromMs, toMs] of a recording: {sampleRate, pieces:[{atMs, buffer, offsetS, durS}]} */
async function savedAudio(file, fromMs, toMs) {
	const ctx = audio.ensure();
	if (!ctx) return null;
	const segs = (await idb.forFile(file)).filter((s) => s.startMs < toMs && s.startMs + (s.durMs || 30000) > fromMs);
	const pieces = [];
	for (const s of segs) {
		try {
			const buffer = await ctx.decodeAudioData(await s.blob.arrayBuffer());
			const a = Math.max(fromMs, s.startMs), b = Math.min(toMs, s.startMs + buffer.duration * 1000);
			if (b > a) pieces.push({ atMs: a, buffer, offsetS: (a - s.startMs) / 1000, durS: (b - a) / 1000 });
		} catch { /* a piece that can't be decoded is skipped */ }
	}
	return pieces.length ? { sampleRate: pieces[0].buffer.sampleRate, pieces } : null;
}

const player = {
	key: null,
	sources: [],
	startedAt: 0,
	fromMs: 0,
	toMs: 0,
	async play(key, file, fromMs, toMs) {
		this.stop();
		audio.unlock();
		const got = await savedAudio(file, fromMs, toMs);
		if (!got) return toast('No audio saved in this browser for that part. Listen while recording to keep it.');
		const ctx = audio.ctx, t0 = ctx.currentTime + 0.05;
		for (const p of got.pieces) {
			const src = ctx.createBufferSource();
			src.buffer = p.buffer;
			src.connect(audio.gain);
			src.start(t0 + (p.atMs - fromMs) / 1000, p.offsetS, p.durS);
			this.sources.push(src);
		}
		this.key = key;
		this.startedAt = t0;
		this.fromMs = fromMs;
		this.toMs = toMs;
		const end = t0 + (toMs - fromMs) / 1000;
		this.timer = setInterval(() => { if (!audio.ctx || audio.ctx.currentTime >= end) this.stop(); render(); }, 250);
		render();
	},
	posMs() { return this.key && audio.ctx ? this.fromMs + Math.max(0, audio.ctx.currentTime - this.startedAt) * 1000 : 0; },
	stop() {
		for (const s of this.sources) { try { s.stop(); } catch { /* done */ } }
		this.sources = [];
		clearInterval(this.timer);
		if (this.key) { this.key = null; render(); }
	},
};

async function downloadClipAudio(r, c) {
	const got = await savedAudio(r.file, c.startMs, c.endMs);
	if (!got) return toast('No audio saved in this browser for this clip.');
	const rate = got.sampleRate, n = Math.ceil(((c.endMs - c.startMs) / 1000) * rate);
	const L = new Float32Array(n), R = new Float32Array(n);
	for (const p of got.pieces) {
		const at = Math.round(((p.atMs - c.startMs) / 1000) * rate), off = Math.round(p.offsetS * rate), cnt = Math.round(p.durS * rate);
		const l = p.buffer.getChannelData(0), rr = p.buffer.numberOfChannels > 1 ? p.buffer.getChannelData(1) : l;
		for (let i = 0; i < cnt && at + i < n; i++) { L[at + i] = l[off + i] || 0; R[at + i] = rr[off + i] || 0; }
	}
	const data = new DataView(new ArrayBuffer(44 + n * 4));
	const str = (o, s) => { for (let i = 0; i < s.length; i++) data.setUint8(o + i, s.charCodeAt(i)); };
	str(0, 'RIFF'); data.setUint32(4, 36 + n * 4, true); str(8, 'WAVE'); str(12, 'fmt ');
	data.setUint32(16, 16, true); data.setUint16(20, 1, true); data.setUint16(22, 2, true);
	data.setUint32(24, rate, true); data.setUint32(28, rate * 4, true); data.setUint16(32, 4, true); data.setUint16(34, 16, true);
	str(36, 'data'); data.setUint32(40, n * 4, true);
	for (let i = 0; i < n; i++) {
		data.setInt16(44 + i * 4, Math.max(-1, Math.min(1, L[i])) * 32767, true);
		data.setInt16(46 + i * 4, Math.max(-1, Math.min(1, R[i])) * 32767, true);
	}
	download(r.file.replace(/\.[^.]*$/, '') + ' clip ' + (r.clips.indexOf(c) + 1) + '.wav', new Blob([data], { type: 'audio/wav' }));
}

/** A colour token's value, read once: the tokens don't change while the page runs. */
const tokenCache = new Map();
function tokenColour(name) {
	if (!tokenCache.has(name)) tokenCache.set(name, getComputedStyle(document.documentElement).getPropertyValue(name).trim());
	return tokenCache.get(name);
}

/* ---------- meters, drawn the way OBS draws them ---------- */
const meters = {
	db: [-60, -60],
	hold: [-60, -60],
	holdAt: [0, 0],
	pending: [0, 0],
	last: performance.now(),
	push(l, r) {
		this.pending[0] = Math.max(this.pending[0], l);
		this.pending[1] = Math.max(this.pending[1], r);
	},
	/** called each frame: OBS falls at 23.53 dB a second and holds the peak for 20 s */
	step() {
		const now = performance.now(), dt = (now - this.last) / 1000;
		this.last = now;
		const live = state.listening && audio.receiving();
		for (let i = 0; i < 2; i++) {
			const p = this.pending[i];
			const pdb = live && p > 0 ? Math.max(-60, 20 * Math.log10(p)) : -60;
			this.pending[i] = 0;
			this.db[i] = Math.max(pdb, this.db[i] - 23.53 * dt);
			if (this.db[i] >= this.hold[i] || now - this.holdAt[i] > 20000) { this.hold[i] = this.db[i]; this.holdAt[i] = now; }
		}
	},
	draw(canvas, dim) {
		const dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, hgt = canvas.clientHeight;
		if (!w) return;
		/* nothing moved since this canvas was last drawn: leave it (an idle meter costs nothing) */
		const sig = this.db.map((d) => d.toFixed(1)).join() + '|' + this.hold.map((d) => d.toFixed(1)).join() + '|' + w + 'x' + hgt;
		if (canvas.drawn === sig) return;
		canvas.drawn = sig;
		if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(hgt * dpr); }
		const g = canvas.getContext('2d');
		g.setTransform(dpr, 0, 0, dpr, 0, 0);
		g.clearRect(0, 0, w, hgt);
		const bar = (hgt - 4) / 2, x = (db) => ((db + 60) / 60) * w;
		const col = (n) => tokenColour(n);
		const zones = [[-60, -20, '--meter-green'], [-20, -9, '--meter-yellow'], [-9, 0, '--meter-red']];
		for (let i = 0; i < 2; i++) {
			const y = i * (bar + 4);
			for (const [a, b, c] of zones) {
				g.globalAlpha = dim ? 0.5 : 1;
				g.fillStyle = col(c + '-off');
				g.fillRect(x(a), y, x(b) - x(a), bar);
				const lit = Math.min(b, this.db[i]);
				if (lit > a) { g.fillStyle = col(c); g.fillRect(x(a), y, x(lit) - x(a), bar); }
			}
			if (this.hold[i] > -59) {
				const hx = x(this.hold[i]);
				g.fillStyle = col(this.hold[i] > -9 ? '--meter-red' : this.hold[i] > -20 ? '--meter-yellow' : '--meter-green');
				g.fillRect(Math.min(w - 2, hx), y, 2, bar);
			}
		}
		g.globalAlpha = 1;
	},
	label() {
		if (!state.listening) return audio.unlocked ? 'Not listening' : 'Not listening · browsers play sound only after a click, so switch Listen on';
		if (!audio.receiving()) return 'No sound arriving';
		return 'Peak ' + Math.round(Math.max(this.db[0], this.db[1])) + ' dB';
	},
};

/* =============================================================== live picture */

/*
 * The plugin sends H.264 (Annex B) frames. Browsers play H.264 through Media Source Extensions
 * in fragmented MP4, which also works on a plain http:// page, so each frame is repackaged here:
 * one init segment from the SPS and PPS, then one small fragment per frame.
 */
const mp4 = {
	box(type, ...parts) {
		const size = 8 + parts.reduce((n, p) => n + p.length, 0);
		const out = new Uint8Array(size);
		const dv = new DataView(out.buffer);
		dv.setUint32(0, size);
		for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
		let o = 8;
		for (const p of parts) { out.set(p, o); o += p.length; }
		return out;
	},
	full(type, version, flags, ...parts) {
		return this.box(type, new Uint8Array([version, (flags >> 16) & 255, (flags >> 8) & 255, flags & 255]), ...parts);
	},
	u32(...v) { const b = new Uint8Array(v.length * 4), d = new DataView(b.buffer); v.forEach((x, i) => d.setUint32(i * 4, x)); return b; },
	u16(...v) { const b = new Uint8Array(v.length * 2), d = new DataView(b.buffer); v.forEach((x, i) => d.setUint16(i * 2, x)); return b; },
	u8(...v) { return new Uint8Array(v); },
	matrix() { return this.u32(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000); },
	init(sps, pps, w, hgt) {
		const avcC = this.box('avcC', this.u8(1, sps[1], sps[2], sps[3], 0xff, 0xe1), this.u16(sps.length), sps, this.u8(1), this.u16(pps.length), pps);
		const avc1 = this.box('avc1', new Uint8Array(6), this.u16(1), new Uint8Array(16), this.u16(w, hgt), this.u32(0x480000, 0x480000, 0), this.u16(1),
			new Uint8Array(32), this.u16(0x18, 0xffff), avcC);
		const stbl = this.box('stbl', this.full('stsd', 0, 0, this.u32(1), avc1), this.full('stts', 0, 0, this.u32(0)), this.full('stsc', 0, 0, this.u32(0)),
			this.full('stsz', 0, 0, this.u32(0, 0)), this.full('stco', 0, 0, this.u32(0)));
		const minf = this.box('minf', this.full('vmhd', 0, 1, new Uint8Array(8)), this.box('dinf', this.full('dref', 0, 0, this.u32(1), this.full('url ', 0, 1))), stbl);
		const mdia = this.box('mdia', this.full('mdhd', 0, 0, this.u32(0, 0, 90000, 0), this.u16(0x55c4, 0)),
			this.full('hdlr', 0, 0, this.u32(0), new Uint8Array([118, 105, 100, 101]), new Uint8Array(12), new Uint8Array([86, 105, 100, 101, 111, 0])), minf);
		const trak = this.box('trak', this.full('tkhd', 0, 3, this.u32(0, 0, 1, 0, 0, 0, 0), this.u16(0, 0, 0, 0), this.matrix(), this.u32(w << 16, hgt << 16)), mdia);
		const mvex = this.box('mvex', this.full('trex', 0, 0, this.u32(1, 1, 0, 0, 0)));
		const moov = this.box('moov', this.full('mvhd', 0, 0, this.u32(0, 0, 1000, 0, 0x10000), this.u16(0x100, 0), new Uint8Array(8), this.matrix(), new Uint8Array(24), this.u32(2)), trak, mvex);
		const ftyp = this.box('ftyp', new Uint8Array([105, 115, 111, 109]), this.u32(0x200), new Uint8Array([105, 115, 111, 109, 105, 115, 111, 50, 97, 118, 99, 49, 109, 112, 52, 49]));
		const out = new Uint8Array(ftyp.length + moov.length);
		out.set(ftyp); out.set(moov, ftyp.length);
		return out;
	},
	fragment(seq, decodeTime, duration, key, sample) {
		const flags = key ? 0x02000000 : 0x01010000;
		const trunLen = 12 + 4 + 4 + 12; /* header, count, offset, one sample */
		const moofLen = 8 + 16 + 8 + 16 + 20 + trunLen;
		const traf = this.box('traf', this.full('tfhd', 0, 0x020000, this.u32(1)),
			this.full('tfdt', 1, 0, this.u32(Math.floor(decodeTime / 4294967296), decodeTime >>> 0)),
			this.full('trun', 0, 0x701, this.u32(1, moofLen + 8, duration, sample.length, flags)));
		const moof = this.box('moof', this.full('mfhd', 0, 0, this.u32(seq)), traf);
		const mdat = this.box('mdat', sample);
		const out = new Uint8Array(moof.length + mdat.length);
		out.set(moof); out.set(mdat, moof.length);
		return out;
	},
};

/** Split Annex B into NAL units. */
function nals(buf) {
	const out = [];
	let i = 0, start = -1;
	while (i + 3 <= buf.length) {
		const sc3 = buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1;
		const sc4 = i + 4 <= buf.length && buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 0 && buf[i + 3] === 1;
		if (sc3 || sc4) {
			if (start >= 0) out.push(buf.subarray(start, i));
			i += sc4 ? 4 : 3;
			start = i;
		} else i++;
	}
	if (start >= 0 && start < buf.length) out.push(buf.subarray(start));
	return out;
}

const video = {
	el: null,
	ms: null,
	sb: null,
	queue: [],
	sps: null,
	pps: null,
	inited: false,
	seq: 1,
	firstPts: null,
	lastPts: 0,
	supported: !!(window.MediaSource || window.ManagedMediaSource),
	async start() {
		if (!this.supported) return toast("This browser can't show the live picture.");
		state.video = true;
		render();
		const r = await command('video.start', { ws: true });
		if (!r.ok && state.link === 'connected') { state.video = false; toast("The PC couldn't start the live picture."); render(); }
	},
	async stop() {
		state.video = false;
		this.reset();
		render();
		await command('video.stop');
	},
	reset() {
		this.inited = false;
		this.queue = [];
		this.firstPts = null;
		this.sb = null;
		if (this.ms && this.el) { try { URL.revokeObjectURL(this.el.src); } catch { /* fine */ } }
		this.ms = null;
	},
	attach(el) {
		if (this.el === el) return;
		this.el = el;
		this.reset();
	},
	setup(sps, pps) {
		const MS = window.MediaSource || window.ManagedMediaSource;
		const codec = 'avc1.' + [sps[1], sps[2], sps[3]].map((x) => x.toString(16).padStart(2, '0')).join('');
		const mime = 'video/mp4; codecs="' + codec + '"';
		if (!MS.isTypeSupported(mime) || !this.el) return false;
		this.ms = new MS();
		this.el.disableRemotePlayback = true;
		this.el.src = URL.createObjectURL(this.ms);
		this.ms.addEventListener('sourceopen', () => {
			try {
				this.sb = this.ms.addSourceBuffer(mime);
				this.sb.mode = 'segments';
				this.sb.addEventListener('updateend', () => this.pump());
				this.queue.unshift(mp4.init(sps, pps, 640, 360));
				this.pump();
			} catch { this.reset(); }
		}, { once: true });
		return true;
	},
	pump() {
		const sb = this.sb;
		if (!sb || sb.updating || !this.queue.length) return;
		try {
			/* keep it live: trim what's long past, and jump to the newest frame if behind */
			const b = sb.buffered;
			if (b.length && this.el) {
				const end = b.end(b.length - 1);
				if (end - this.el.currentTime > 0.6) this.el.currentTime = end - 0.1;
				if (this.el.currentTime - b.start(0) > 20) { sb.remove(b.start(0), this.el.currentTime - 5); return; }
			}
			sb.appendBuffer(this.queue.shift());
			if (this.el && this.el.paused) this.el.play().catch(() => {});
		} catch { this.reset(); }
	},
	onFrame(u8) {
		if (!state.video || u8.length < 17) return;
		const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
		const size = dv.getUint32(4, true);
		const flags = u8[8];
		const pts = dv.getUint32(9, true) + dv.getInt32(13, true) * 4294967296;
		const data = u8.subarray(17, 17 + size);
		const units = nals(data);
		if (flags & 1) {
			for (const n of units) {
				if ((n[0] & 31) === 7) this.sps = n.slice();
				if ((n[0] & 31) === 8) this.pps = n.slice();
			}
			return;
		}
		if (!this.inited) {
			if (!(flags & 2) || !this.sps || !this.pps || !this.el) return;
			if (!this.setup(this.sps, this.pps)) return;
			this.inited = true;
		}
		const keep = units.filter((n) => { const t = n[0] & 31; return t !== 7 && t !== 8 && t !== 9; });
		const total = keep.reduce((s, n) => s + 4 + n.length, 0);
		const sample = new Uint8Array(total);
		let o = 0;
		for (const n of keep) { new DataView(sample.buffer).setUint32(o, n.length); sample.set(n, o + 4); o += 4 + n.length; }
		if (this.firstPts === null) this.firstPts = pts;
		const t = Math.round(((pts - this.firstPts) * 90000) / 1e6);
		const dur = Math.max(1, Math.round(((pts - this.lastPts) * 90000) / 1e6)) || 3750;
		this.lastPts = pts;
		this.queue.push(mp4.fragment(this.seq++, Math.max(0, t), dur > 90000 ? 3750 : dur, !!(flags & 2), sample));
		if (this.queue.length > 60) this.queue.splice(1, this.queue.length - 30);
		this.pump();
	},
};

function onBinary(u8) {
	if (u8.length < 4) return;
	const magic = String.fromCharCode(u8[0], u8[1], u8[2], u8[3]);
	if (magic === 'ODA1') audio.onPacket(u8);
	else if (magic === 'ODV1') video.onFrame(u8);
}

/* =============================================================== clips */

function startClip() {
	const s = state.status;
	if (!s || !s.recActive || state.clipStart !== null || state.link !== 'connected') return;
	state.clipStart = Math.max(0, nowFileTimeMs() - settings.leadMinutes * 60000);
	state.lastSaved = '';
	if (settings.chapters) {
		const r = recordings.find((x) => x.file === s.file);
		command('chapter.add', { name: 'Clip ' + ((r ? r.clips.length : 0) + 1) });
	}
	render();
}
function endClip(why = '', auto = false) {
	const start = state.clipStart;
	const s = state.status;
	if (start === null || !s || !s.file) return;
	const end = Math.max(start + 1000, nowFileTimeMs() + (auto ? 0 : settings.padSeconds * 1000));
	state.clipStart = null;
	const r = ensureRecording(s.file, s.recActive);
	const c = { id: newId(), startMs: Math.round(start), endMs: Math.round(end), note: '', createdAt: Date.now() };
	r.clips.push(c);
	r.clips.sort((a, b) => a.startMs - b.startMs);
	state.lastSaved = 'Clip ' + (r.clips.indexOf(c) + 1) + ' saved, ' + tc(start) + ' → ' + tc(end) + ' (' + len(end - start) + ')' + why;
	state.noteFor = { file: r.file, id: c.id };
	saveRecordings();
}
function endClipIfRunning(why) { if (state.clipStart !== null) endClip(why, true); }
function toggleClip() { if (state.clipStart === null) startClip(); else endClip(); }
function cancelClip() { state.clipStart = null; state.lastSaved = 'Clip cancelled. Nothing saved.'; render(); }

/* the clip lists, saved next to the recording on the PC as .edl and .csv */
const sentClipLists = new Map();
let clipListTimer = 0;
function scheduleClipLists() {
	clearTimeout(clipListTimer);
	clipListTimer = setTimeout(writeClipLists, 1500);
}
async function writeClipLists() {
	if (!settings.pcClipLists || state.link !== 'connected') return;
	for (const r of recordings) {
		if (!r.clips.length) continue;
		const sig = JSON.stringify(r.clips.map((c) => [c.startMs, c.endMs, c.note]));
		if (sentClipLists.get(r.file) === sig) continue;
		sentClipLists.set(r.file, sig);
		const res = await command('clips.write', { file: r.file, edl: Exporter.edl(r), csv: Exporter.csv(r) });
		if (res.ok) {
			if (r.savedOnPc !== state.pc) { r.savedOnPc = state.pc; store.set('recordings', recordings); render(); }
		} else if (res.error === 'not_connected' || res.error === 'timeout') {
			sentClipLists.delete(r.file);
		}
	}
}

/* =============================================================== alerts */

const dropHistory = [];
let silentSince = 0, lastClipAlertAt = 0, minuteTicks = 0, minuteStartDropped = -1;

/** An alert: a notification where the browser allows one, a buzz, and a card in the Desk chat. */
function raise(a) {
	const had = state.alerts.find((x) => x.id === a.id);
	state.alerts = state.alerts.filter((x) => x.id !== a.id).concat([a]);
	if (had) return;
	chatNote({ kind: 'alert', from: a.title, text: a.body, severe: !!a.severe, deskText: a.desk || null, offerStart: !!a.offerStart });
	feedback.buzz(a.severe ? [300, 120, 300, 120, 300] : [200, 100, 200]);
	notify(a.title, a.body);
	render();
}

/** A line in this browser's chat that isn't a message: an alert card or a connection note. */
function chatNote(n) {
	state.desk.push(Object.assign({ id: 'local-' + newId(), at: Date.now(), mine: false, desk: false, state: 'sent' }, n));
	/* a connection note shows in the chat but doesn't count as unread; an alert colours the count */
	if (view.name !== 'desk' && n.kind !== 'note') {
		state.deskUnread++;
		if (n.kind === 'alert') state.deskAlert = Math.max(state.deskAlert, n.severe ? 2 : 1);
	}
}

/** A system notification, when the browser allows it (it does on the OBS PC itself, at localhost). */
function notify(title, body) {
	try {
		if (!('Notification' in window) || !window.isSecureContext) return toast(title + ' ' + body);
		if (Notification.permission === 'granted') new Notification(title, { body, icon: 'icon.svg', tag: title });
		else { toast(title + ' ' + body); if (Notification.permission === 'default') Notification.requestPermission(); }
	} catch { toast(title + ' ' + body); }
}
function clearAlert(id) {
	if (!state.alerts.some((x) => x.id === id)) return;
	state.alerts = state.alerts.filter((x) => x.id !== id);
	render();
}
function diskHoursLeft(s) {
	const bps = s.fileTimeMs > 10000 ? (s.recBytes * 1000) / s.fileTimeMs : 0;
	if (bps <= 0 || s.diskFreeMb < 0) return null;
	return (s.diskFreeMb * 1048576) / bps / 3600;
}
setInterval(() => {
	const s = state.status;
	if (state.link !== 'connected' || !s) return;
	if (s.streamActive) {
		if (minuteStartDropped < 0) minuteStartDropped = s.dropped;
		if (++minuteTicks >= 60) {
			state.health = state.health.concat([Math.max(0, s.dropped - minuteStartDropped)]).slice(-30);
			minuteTicks = 0;
			minuteStartDropped = s.dropped;
		}
	}
	const now = Date.now();
	const silent = audio.receiving() && meters.db[0] < -55 && meters.db[1] < -55;
	if (settings.silenceSeconds > 0 && s.recActive && silent) {
		if (!silentSince) silentSince = now;
		if (now - silentSince >= settings.silenceSeconds * 1000)
			raise({ id: 'silence', title: 'Mix silent for ' + settings.silenceSeconds + ' s.', body: 'A mic may be muted, or the mix fader down.', desk: 'Mic is muted' });
	} else {
		if (silentSince && state.alerts.some((x) => x.id === 'silence')) { clearAlert('silence'); toast('Sound is back in the mix'); }
		silentSince = 0;
	}
	if (settings.alertClipping && performance.now() - audio.clippedAt < 1500 && now - lastClipAlertAt > 60000) {
		lastClipAlertAt = now;
		raise({ id: 'clip', title: 'Mix clipping.', body: 'Peaks are hitting 0 dB, so the recording will distort.', desk: "Check mic levels, it's clipping" });
	}
	const hours = s.recActive ? diskHoursLeft(s) : null;
	if (hours !== null && hours < settings.diskHours)
		raise({ id: 'disk', severe: true, title: 'Disk nearly full.', body: 'About ' + hours.toFixed(1) + ' h left on the OBS PC.', desk: 'Disk is nearly full' });
	if (s.streamActive && dropHistory.length > 2) {
		const ago = dropHistory.find((e) => e[0] >= now - 60000) || dropHistory[0];
		const dropped = s.dropped - ago[1];
		if (dropped > settings.dropsPerMinute)
			raise({ id: 'drops', title: 'Stream dropping frames.', body: dropped + " dropped in the last minute. Usually the venue's upload, not OBS.", desk: 'Stream is dropping frames' });
		else if (dropped === 0) clearAlert('drops');
	}
}, 1000);

/* =============================================================== touch feedback */

/*
 * The same tap as the phone app (tap.wav: one tap cut from the supplied recording, 60 ms),
 * at the Touch sound volume, and a short vibration on phones whose browser can vibrate.
 */
const feedback = {
	buffer: null,
	loading: false,
	async load() {
		if (this.buffer || this.loading || !audio.ensure()) return;
		this.loading = true;
		try { this.buffer = await audio.ctx.decodeAudioData(await (await fetch('tap.wav')).arrayBuffer()); } catch { /* no tap sound */ }
		this.loading = false;
	},
	tap() {
		const vol = settings.touchSound || 0;
		if (vol > 0) {
			this.load();
			if (this.buffer && audio.ctx) {
				const src = audio.ctx.createBufferSource(), g = audio.ctx.createGain();
				g.gain.value = (vol / 100) * 0.6; /* same ceiling as the app: under the mix, not over it */
				src.buffer = this.buffer; src.connect(g); g.connect(audio.ctx.destination); src.start();
			}
		}
		if (settings.haptics > 0) this.buzz([[8, 14, 22][settings.haptics - 1]]);
	},
	buzz(pattern) {
		if (!settings.haptics || !navigator.vibrate) return;
		const scale = [0.5, 1, 1.4][settings.haptics - 1];
		try { navigator.vibrate(pattern.map((v, i) => (i % 2 === 0 ? Math.max(5, Math.round(v * scale)) : v))); } catch { /* not allowed */ }
	},
};
document.addEventListener('click', (e) => {
	if (e.target.closest && e.target.closest('button, [role=switch], [role=tab], a, select')) feedback.tap();
}, { capture: true });

/* =============================================================== desk */

function chatMessage(d) {
	return { id: String(d.id), from: d.from || '', text: String(d.text || ''), at: Number(d.at) || Date.now(), desk: !!d.desk, mine: !!d.mine, state: d.seen ? 'seen' : 'sent' };
}

/** After connecting: the chat so far, from OBS, plus anything of ours that hadn't gone. */
async function loadDesk() {
	const r = await command('desk.history');
	if (!r.ok || !r.data) return;
	const msgs = (r.data.messages || []).map(chatMessage);
	const ids = new Set(msgs.map((m) => m.id));
	const pending = state.desk.filter((m) => m.mine && (m.state === 'sending' || m.state === 'failed') && !ids.has(m.id));
	/* this browser's own alert cards and notes stay, in time order */
	const local = state.desk.filter((m) => m.kind === 'alert' || m.kind === 'note');
	state.desk = msgs.concat(pending, local).sort((a, b) => a.at - b.at);
	pending.forEach(postDesk);
	render();
}

function sendDesk(text) {
	const t = text.trim().slice(0, 1000);
	if (!t) return;
	const m = { id: newId(), from: settings.deviceName, text: t, at: Date.now(), desk: false, mine: true, state: 'sending' };
	state.desk.push(m);
	postDesk(m);
	render();
}

async function postDesk(m) {
	m.state = 'sending';
	const r = await command('desk.send', { id: m.id, text: m.text });
	if (r.ok) { if (m.state !== 'seen') m.state = 'sent'; if (r.data && r.data.at) m.at = r.data.at; }
	else m.state = 'failed';
	render();
}

let toastTimer = 0;
function toast(text) {
	const el = $('#toast');
	if (!el) return;
	el.textContent = text;
	el.hidden = false;
	clearTimeout(toastTimer);
	toastTimer = setTimeout(() => { el.hidden = true; }, 3500);
}

/* =============================================================== views */

const view = { name: 'monitor', recording: null, open: null, mode: 'actions', streamTab: 'Stream', exporting: false, deskOpen: false };
let live = []; // [element, () => text] updated in place, so typing is never interrupted
let meterCanvases = [];
let signature = '';

const bind = (el, fn) => { live.push([el, fn]); el.textContent = fn(); return el; };
const tint = (name) => ({ bg: `var(--${name}-tint)`, ink: `var(--${name}-ink)` });

function structureKey() {
	const s = state.status || {};
	return [state.link, view.name, view.recording, view.open, view.mode, view.streamTab, view.exporting, JSON.stringify(view.sheet), view.sheetMode, !!undoing, s.recActive, s.recPaused, s.streamActive,
		state.clipStart !== null, state.listening, state.video, state.desk.length, state.desk.map((m) => m.state).join(''), state.deskUnread, state.deskAlert, state.alerts.map((a) => a.id).join(), !!state.lastSaved, settings.allowStreamStop,
		player.key, recordings.length].join('|');
}

/** Rebuild the screen. Whatever is being typed (text, cursor, focus) carries over. */
function render() {
	const a = document.activeElement;
	const keep = a && /^(INPUT|TEXTAREA)$/.test(a.tagName) && a.type !== 'range' && $('#view').contains(a) && a.getAttribute('aria-label')
		? { label: a.getAttribute('aria-label'), value: a.value, start: a.selectionStart, end: a.selectionEnd } : null;
	/* keep where each scrolling area was (the chat stays at the newest message if it was there) */
	const mainEl = $('.main'), chatEl = $('#chat-list');
	const mainTop = mainEl ? mainEl.scrollTop : 0;
	const chatAtEnd = !chatEl || chatEl.scrollHeight - chatEl.scrollTop - chatEl.clientHeight < 60;
	const chatTop = chatEl ? chatEl.scrollTop : 0;
	const sameView = render.lastView === view.name + ':' + view.recording;
	render.lastView = view.name + ':' + view.recording;
	signature = structureKey();
	live = [];
	meterCanvases = [];
	renderHead();
	renderBanners();
	const root = $('#view');
	root.replaceChildren();
	document.body.classList.toggle('staged', view.name === 'stage' && state.link === 'connected');
	if (state.link !== 'connected' && state.link !== 'lost') root.append(connectView());
	else if (view.name === 'stage') root.append(stageView());
	else root.append(appView());
	if (keep) {
		const el = Array.from(root.querySelectorAll('input, textarea')).find((x) => x.getAttribute('aria-label') === keep.label);
		if (el) {
			el.value = keep.value;
			el.focus();
			try { el.setSelectionRange(keep.start, keep.end); } catch { /* not a text field */ }
			el.dispatchEvent(new Event('input'));
		}
	}
	const newMain = $('.main'), newChat = $('#chat-list');
	if (newMain && sameView) newMain.scrollTop = mainTop;
	if (newChat) newChat.scrollTop = chatAtEnd || !sameView ? newChat.scrollHeight : chatTop;
	startFrames();
	tick();
}

function renderHead() {
	const dot = $('#link-dot'), line = $('#link-line');
	const colour = { connected: 'var(--ok)', pending: 'var(--amber-ink)', lost: 'var(--amber-ink)', connecting: 'var(--muted)', refused: 'var(--rec)' }[state.link];
	dot.style.background = colour;
	line.textContent = {
		connected: 'Connected to ' + state.pc + ' · ' + location.hostname,
		pending: (state.pc || location.hostname) + ' · waiting for Allow',
		lost: (state.pc || 'OBS PC') + ' · reconnecting',
		connecting: 'Connecting to ' + location.hostname + '…',
		refused: 'Not connected',
	}[state.link];
	$('#browser-name').textContent = 'This browser: ' + settings.deviceName;
	const stageBtn = $('#stage-open');
	stageBtn.hidden = state.link !== 'connected' || view.name === 'stage';
	stageBtn.onclick = () => { view.name = 'stage'; render(); };
}

/**
 * The banners above every screen. They sit in a polite live region, so a banner that's already
 * showing is kept as it is: only a new one is announced, once.
 */
const bannerNodes = new Map();
/* Alerts don't sit on top of the screens: they notify, and show up in the Desk chat. */
function renderBanners() {
	$('#banners').replaceChildren();
}

/* ---------- waiting for Allow ---------- */
function connectView() {
	const host = location.host;
	const step = (n, colour, ...text) => h('li', h('span.step-n', { style: { background: colour.bg, color: colour.ink } }, n), h('span', ...text));
	let headline, sub, steps;
	if (state.link === 'pending') {
		headline = 'Waiting for Allow in OBS';
		sub = state.pendingMessage || 'OBS is asking whether this browser may connect.';
		steps = [
			step(icon('check', 16), tint('ok'), 'You opened ', h('b', 'http://' + host), ', the address in OBS under ', h('b', 'Tools, OBS Director'), '.'),
			step('2', tint('amber'), 'At the PC, press ', h('b', 'Allow'), ' on ', h('i', 'Allow "' + settings.deviceName + '" to connect?'), " Check the taskbar if you don't see it."),
			step('3', { bg: 'var(--control)', ink: 'var(--muted)' }, 'This page opens Monitor by itself. Next time this browser connects straight away.'),
		];
	} else if (state.link === 'refused') {
		headline = 'Not connected';
		sub = state.error || 'OBS refused the connection.';
		steps = [step(icon('alert', 16), tint('rec'), state.errorCode === 'denied' ? 'This page asks again in a minute, or reload it after someone at the PC is ready to press Allow.' : 'This page tries again by itself in a moment.')];
	} else {
		headline = 'Connecting to OBS';
		sub = 'Looking for the OBS Director plugin at ' + host + '.';
		steps = [step(icon('info', 16), tint('blue'), 'OBS needs to be open on that PC with the OBS Director plugin. If this takes long, check the address under Tools, OBS Director in OBS.')];
	}
	return h('main.center', h('section.connect', { 'aria-labelledby': 'connect-title' },
		h('div.row', h('span.tint-tile', { style: { width: '48px', height: '48px', borderRadius: '16px', background: 'var(--amber-tint)', color: 'var(--amber-ink)' } }, icon('pc', 26)),
			h('div.grow', h('h1.title#connect-title', headline), h('div.sub', sub))),
		h('ol.steps', steps),
		h('div.foot-note', 'Works in Chrome, Edge and Firefox on any computer on the same network, including the OBS PC itself. Nothing to install. Everything the phone app does works here too.'),
		h('label.row', { style: { gap: '10px' } }, h('span.small', 'Name in OBS'),
			h('input', { type: 'text', value: settings.deviceName, maxlength: 40, 'aria-label': 'Name in OBS', style: { maxWidth: '280px' },
				onchange: (e) => { settings.deviceName = e.target.value.trim() || defaultName(); saveSettings(); } }))));
}

/* ---------- the app: navigation and pages ---------- */
function appView() {
	const nav = [['monitor', 'Monitor', 'teal'], ['stream', 'Stream and PC', 'rec'], ['recordings', 'Recordings', 'amber'], ['desk', 'Desk', 'ok'], ['settings', 'Settings', 'purple']];
	const current = view.name === 'recording' ? 'recordings' : view.name;
	const page = { monitor: monitorPage, stream: streamPage, recordings: recordingsPage, recording: recordingsPage, settings: settingsPage }[view.name] || monitorPage;
	if (view.name === 'desk') { state.deskUnread = 0; state.deskAlert = 0; }
	return h('div.app',
		h('nav.nav', { 'aria-label': 'Main' },
			nav.map(([id, label, sw]) => h('button', { type: 'button', 'aria-current': current === id ? 'page' : 'false', onclick: () => { view.name = id; view.open = null; render(); } },
				h('span.swatch', { style: { background: `var(--${sw}-ink)` } }), label,
				id === 'recordings' && recordings.some((r) => r.live) ? h('span.badge', h('span.dot', { style: { background: 'var(--rec)' } }), h('span.sr', ', recording now')) : null,
				id === 'desk' && state.deskUnread ? h('span.count' + (state.deskAlert === 2 ? '.severe' : state.deskAlert === 1 ? '.alert' : ''),
					{ 'aria-label': state.deskUnread + ' unread' + (state.deskAlert === 2 ? ', including a severe alert' : state.deskAlert === 1 ? ', including an alert' : '') },
					state.deskUnread > 99 ? '99+' : state.deskUnread) : null)),
			h('div.nav-foot', 'Plugin ' + (state.plugin || '–') + ' · page ' + VERSION)),
		view.name === 'desk' ? h('main.main.chat-main', deskPage()) : h('main.main', h('div.page', page())));
}

function recordingSection() {
	const s = state.status || {};
	const running = state.clipStart !== null;
	const r = recordings.find((x) => x.file === s.file);
	const pill = !s.recActive ? h('span.pill.off', 'NOT RECORDING') : s.recPaused ? h('span.pill.paused', h('span.dot'), 'PAUSED') : h('span.pill.rec', h('span.dot'), 'REC');
	const clipBtn = running
		? h('button.clip.running', { type: 'button', onclick: toggleClip },
			h('span.clip-left', h('span.big', 'End clip'), h('span.hint', 'clip length')),
			bind(h('span.len'), () => clipLength(nowFileTimeMs() - state.clipStart)))
		: h('button.clip', { type: 'button', onclick: toggleClip, disabled: !s.recActive },
			h('span.big', 'Start clip'),
			h('span.hint', s.recActive ? 'starts ' + settings.leadMinutes + ' min before your click' + (settings.spaceForClip ? ' · or press Space' : '') : 'Start recording to clip'));
	if (running) live.push([clipBtn, null, () => clipBtn.setAttribute('aria-label', 'End clip, ' + clipLength(nowFileTimeMs() - state.clipStart) + ' long')]);
	return h('section.section', { 'aria-label': 'Recording' },
		h('div.row', pill, h('span.small', s.file || 'No recording yet')),
		bind(h('div.timecode' + (s.recActive ? '' : '.idle')), () => tc(nowFileTimeMs())),
		clipBtn,
		statusLine(r, running),
		h('div.row.controls',
			s.recActive
				? [h('button.btn.outline', { type: 'button', onclick: () => act(s.recPaused ? 'record.resume' : 'record.pause') }, icon(s.recPaused ? 'play' : 'pause', 18), s.recPaused ? 'Resume' : 'Pause'),
					holdButton('Hold to stop', settings.holdMs, () => { stopRequestedHere = true; act('record.stop'); })]
				: h('button.btn.primary', { type: 'button', onclick: () => act('record.start') }, icon('rec', 18), 'Start recording')));
}

/**
 * One line under the Clip button, always the same height: what just happened and the one thing
 * to do about it (cancel, add a note, see all). Nothing appears or disappears around it, so the
 * buttons below never move.
 */
function statusLine(r, running) {
	const seeAll = () => { view.name = 'recording'; view.recording = r.file; render(); };
	const link = (label, onclick) => h('button.line-action', { type: 'button', onclick }, label);
	if (running)
		return h('div.status-line', h('span.grow', bind(h('span'), () => 'Clip running from ' + tc(state.clipStart))), link('Cancel', cancelClip));
	if (state.noteFor) {
		const { file, id } = state.noteFor;
		const rec = recordings.find((x) => x.file === file), c = rec && rec.clips.find((x) => x.id === id);
		if (c) {
			const input = h('input', { type: 'text', maxlength: 140, placeholder: 'Note for this clip (optional)', 'aria-label': 'Note for this clip' });
			const save = () => { c.note = input.value.trim(); state.noteFor = null; state.lastSaved = state.lastSaved + (c.note ? ' · ' + c.note : ''); saveRecordings(); render(); };
			input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); if (e.key === 'Escape') { state.noteFor = null; render(); } });
			return h('div.status-line', h('div.grow', input), link('Save', save), link('Skip', () => { state.noteFor = null; render(); }));
		}
		state.noteFor = null;
	}
	const text = state.lastSaved || (r && r.clips.length ? clipsText(r.clips.length) + ' in this recording' : 'No clips yet');
	return h('div.status-line', h('span.grow', { role: 'status' }, text), r && r.clips.length ? link('See all', seeAll) : null);
}

/** A button that only acts when held, so a stray click can't end the take. */
function holdButton(text, ms, done) {
	const fill = h('span.fill');
	const btn = h('button.btn.danger.hold', { type: 'button' }, fill, h('span', text));
	let timer = 0, started = 0, raf = 0;
	const reset = () => { clearTimeout(timer); cancelAnimationFrame(raf); fill.style.transform = 'scaleX(0)'; };
	const go = (e) => {
		if (e.type === 'keydown' && e.key !== 'Enter' && e.key !== ' ') return;
		if (e.repeat) return;
		e.preventDefault();
		if (e.pointerId !== undefined) { try { btn.setPointerCapture(e.pointerId); } catch { /* fine */ } }
		started = performance.now();
		const step = () => { fill.style.transform = 'scaleX(' + Math.min(1, (performance.now() - started) / ms) + ')'; raf = requestAnimationFrame(step); };
		step();
		timer = setTimeout(() => { reset(); done(); }, ms);
	};
	btn.addEventListener('pointerdown', go);
	btn.addEventListener('keydown', go);
	for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture', 'keyup', 'blur']) btn.addEventListener(ev, reset);
	btn.addEventListener('contextmenu', (e) => e.preventDefault()); /* a long press is the point, not a menu */
	btn.setAttribute('aria-description', 'Press and hold for ' + (ms / 1000) + ' seconds');
	return btn;
}

function meterBlock(dim) {
	const canvas = h('canvas', { role: 'img', 'aria-label': 'Mix level' });
	meterCanvases.push([canvas, dim]);
	live.push([canvas, null, () => canvas.setAttribute('aria-label', 'Mix level, ' + meters.label())]);
	return h('div.meter', canvas, !dim && h('div.scale', ['-60', '-40', '-20', '-9', '0 dB'].map((t) => h('span', t))));
}

function listenSection() {
	const sw = h('button.switch', { type: 'button', role: 'switch', 'aria-checked': String(state.listening), 'aria-label': 'Listen in this browser',
		onclick: () => (state.listening ? audio.stop() : audio.start()) });
	const vol = h('input', { type: 'range', min: 0, max: 300, step: 5, value: Math.round(settings.volume * 100), 'aria-label': 'Volume' });
	if (settings.volume > 1) vol.classList.add('boost');
	const volLabel = h('span.small', Math.round(settings.volume * 100) + '%');
	vol.addEventListener('input', () => {
		audio.setVolume(vol.value / 100);
		volLabel.textContent = vol.value + '%';
		vol.classList.toggle('boost', vol.value > 100);
		boostHint.textContent = vol.value > 100 ? boostText : baseText;
		boostHint.style.color = vol.value > 100 ? 'var(--amber-ink)' : 'var(--muted)';
	});
	vol.addEventListener('change', saveSettings);
	/* always there, so dragging past 100% changes the words, not the layout */
	const boostText = 'Above 100% is louder in this browser only. A soft limiter stops crackles.', baseText = 'Up to 300% for quiet rooms.';
	const boostHint = h('div.small', { style: { color: settings.volume > 1 ? 'var(--amber-ink)' : 'var(--muted)' } }, settings.volume > 1 ? boostText : baseText);
	return h('section.section', { 'aria-label': 'Listening' },
		h('div.row', h('span.tint-tile', { style: { background: 'var(--teal-tint)', color: 'var(--teal-ink)' } }, icon('head', 18)),
			h('div.grow', h('h2.card-title', 'Listen in this browser'),
				bind(h('div.small'), () => "This computer's sound output · about " + (audio.delayMs() + state.latencyMs || 120) + ' ms behind')),
			sw),
		bind(h('div.small'), () => meters.label()),
		meterBlock(false),
		h('div.row', h('span.small', 'Volume'), h('div.grow', vol), volLabel),
		boostHint,
		bind(h('div.small'), () => networkLine()));
}

function networkLine() {
	const drops = audio.dropoutsLastMinute();
	const lat = state.latencyMs;
	if (!state.listening) return 'Network ' + (lat < 150 ? 'good' : 'slow') + ' · ' + lat + ' ms to the PC';
	if (drops === 0 && lat < 150) return 'Network good · no dropouts';
	if (drops <= 3) return 'Network weak · ' + drops + ' dropout' + (drops === 1 ? '' : 's') + ' in the last minute';
	return 'Network poor · ' + drops + ' dropouts in the last minute. Move closer to the router, or use a cable.';
}

function chipsRow() {
	const S = () => state.status || {};
	const chip = (t, textFn, dot) => h('button.chip', { type: 'button', style: { background: t.bg, color: t.ink }, onclick: () => { view.name = 'stream'; render(); } },
		dot && h('span.dot', { style: { background: dot } }), bind(h('span'), textFn));
	return h('div.chips',
		S().streamActive ? chip(tint('rec'), () => 'Stream live ' + tc(S().streamDurationMs) + ' ›', 'var(--rec)')
			: chip({ bg: 'var(--control)', ink: 'var(--text-2)' }, () => 'Stream off ›'),
		chip(S().dropped ? tint('amber') : tint('ok'), () => (S().dropped || 0) + ' dropped'),
		chip(tint('purple'), () => { const hh = diskHoursLeft(S()); return 'Disk ' + (hh === null ? '–' : (hh >= 10 ? Math.round(hh) : hh.toFixed(1)) + ' h left'); }));
}

function pictureSection() {
	const sw = h('button.switch', { type: 'button', role: 'switch', 'aria-checked': String(state.video), 'aria-label': 'Show the live picture',
		onclick: () => (state.video ? video.stop() : video.start()) });
	let box;
	if (state.video) {
		const el = h('video', { muted: true, playsinline: true, autoplay: true, 'aria-label': 'Live output from OBS' });
		el.muted = true;
		video.attach(el);
		box = h('div.video', el);
	} else {
		video.attach(null);
		box = h('div.video', video.supported ? 'Off. About 540 MB an hour while on.' : "This browser can't show the live picture.");
	}
	return h('section.section', { 'aria-label': 'Live picture' },
		h('div.row', h('div.grow', h('h2.card-title', 'Live picture'), h('div.small', '640×360, to check framing. It never delays the audio.')), sw),
		box);
}

/* ---------- the desk chat, laid out like a messaging app ---------- */
const nameInks = ['blue', 'amber', 'ok', 'rec', 'purple', 'teal', 'orange'];
function nameInk(name) {
	let x = 0;
	for (const ch of name) x = (x * 31 + ch.charCodeAt(0)) >>> 0;
	return `var(--${nameInks[x % nameInks.length]}-ink)`;
}
function chatDay(at) {
	const d = new Date(at), t = new Date();
	const days = Math.round((new Date(t.toDateString()) - new Date(d.toDateString())) / 86400000);
	return days === 0 ? 'Today' : days === 1 ? 'Yesterday' : d.toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
}

function deskPage() {
	const connected = state.link === 'connected';
	const list = h('div.chat-list#chat-list', { role: 'log', 'aria-label': 'Desk chat' });
	let lastDay = '', lastFrom = null;
	if (!state.desk.length) list.append(h('div.chat-empty', 'No messages yet. Write to the volunteers at the OBS desk; everyone connected sees it.'));
	for (const m of state.desk) {
		const day = chatDay(m.at);
		if (day !== lastDay) { list.append(h('div.chat-day', h('span', day))); lastDay = day; lastFrom = null; }
		const hhmm = pad2(new Date(m.at).getHours()) + ':' + pad2(new Date(m.at).getMinutes());
		if (m.kind === 'note') { list.append(h('div.chat-day.chat-note', h('span', hhmm + '  ' + m.text))); lastFrom = null; continue; }
		if (m.kind === 'alert') {
			list.append(h('div.chat-alert' + (m.severe ? '.severe' : ''), { role: 'note' },
				h('div.row', icon('alert', 18), h('b.grow', m.from), h('span.small', hhmm)),
				h('div', m.text),
				(m.offerStart || m.deskText) && h('div.row',
					m.offerStart && h('button.btn.primary.small-btn', { type: 'button', onclick: () => act('record.start') }, icon('rec', 16), 'Start recording'),
					m.deskText && h('button.btn.outline.small-btn', { type: 'button', onclick: () => sendDesk(m.deskText) }, icon('send', 16), 'Tell the desk'))));
			lastFrom = null;
			continue;
		}
		const showName = !m.mine && lastFrom !== (m.desk ? '\u0000desk' : m.from);
		lastFrom = m.mine ? null : (m.desk ? '\u0000desk' : m.from);
		const time = pad2(new Date(m.at).getHours()) + ':' + pad2(new Date(m.at).getMinutes());
		const tick = m.mine ? ({ sending: ['clock', 'Sending'], failed: ['alert', 'Not sent, click to try again'], seen: ['ticks', 'Seen at the desk'] }[m.state] || ['ticks', 'Sent']) : null;
		const bubble = h('div.bubble' + (m.mine ? '.out' : '.in') + (m.state === 'failed' ? '.failed' : ''),
			{ onclick: m.state === 'failed' ? () => postDesk(m) : null, title: m.state === 'failed' ? 'Not sent: click to try again' : null },
			showName && h('div.who', { style: { color: m.desk ? 'var(--ok-ink)' : nameInk(m.from) } }, m.from || 'Someone'),
			h('div.text', m.text),
			h('div.meta', m.state === 'failed' && h('span.fail', 'Not sent · click to try again'), h('span', time),
				tick && h('span.tick' + (m.state === 'seen' ? '.seen' : ''), { 'aria-label': tick[1], title: tick[1] }, icon(tick[0], 15))));
		list.append(h('div.chat-row' + (m.mine ? '.mine' : '') + (showName ? '.first' : ''), bubble));
	}
	const box = h('textarea', { rows: 1, maxlength: 1000, placeholder: connected ? 'Message  (Ctrl+Enter to send)' : 'Not connected', 'aria-label': 'Message', disabled: !connected });
	const sendBtn = h('button.send', { type: 'button', 'aria-label': 'Send', disabled: true }, icon('send', 20));
	const grow = () => { box.style.height = 'auto'; box.style.height = Math.min(box.scrollHeight, 140) + 'px'; sendBtn.disabled = !connected || !box.value.trim(); };
	const go = () => { const t = box.value; if (!t.trim() || !connected) return; box.value = ''; grow(); sendDesk(t); };
	box.addEventListener('input', grow);
	/* Enter is a new line; Ctrl+Enter (or Cmd+Enter on a Mac) sends */
	box.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); go(); } });
	sendBtn.addEventListener('click', go);
	setTimeout(grow, 0);
	return [h('div.chat-head', h('h1.title', 'Desk'), h('div.sub', connected ? 'Everyone connected to OBS sees these' : 'Connect to OBS to chat')),
		list, h('div.composer', box, sendBtn)];
}

function monitorPage() {
	return [h('h1.sr', 'Monitor'),
		h('div.cols', h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px' } }, recordingSection()),
			h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px' } }, listenSection(), chipsRow())),
		pictureSection()];
}

/* ---------- stream and PC ---------- */
function streamPage() {
	const tabs = ['Stream', 'Computer', 'Network'];
	const stat = (label, fn, t) => h('div.stat', { style: { background: t.bg, color: t.ink } }, h('div.label', label), bind(h('div.value'), fn));
	const S = () => state.status || {};
	let body;
	if (view.streamTab === 'Stream') {
		const chart = h('canvas.chart', { role: 'img', 'aria-label': 'Dropped frames per minute' });
		requestAnimationFrame(() => drawHealth(chart));
		body = [h('div.stat-grid',
			stat('Live for', () => (S().streamActive ? tc(S().streamDurationMs) : 'Off air'), tint('rec')),
			stat('Bitrate', () => (S().streamActive ? Math.round(S().streamKbps).toLocaleString() + ' kb/s' : '–'), tint('blue')),
			stat('Dropped frames', () => S().dropped + ' · ' + (S().totalFrames ? ((S().dropped * 100) / S().totalFrames).toFixed(1) : '0.0') + '%', S().dropped ? tint('amber') : tint('ok')),
			stat('Congestion', () => (S().congestion < 0.1 ? 'Low' : S().congestion < 0.5 ? 'Some' : 'High'), tint('amber'))),
		h('section.section', h('h2.card-title', 'Dropped frames, last 30 minutes'),
			h('div.small', 'One bar a minute. Amber means it crossed your alert level (' + settings.dropsPerMinute + ' a minute).'), chart),
		settings.allowStreamStop && S().streamActive ? holdButton('Hold to end the stream', 2000, () => act('stream.stop'))
			: h('div.small', 'Ending the stream from this browser is off. Turn it on in Settings, Controls.')];
	} else if (view.streamTab === 'Computer') {
		body = [h('div.stat-grid',
			stat('Processor', () => Math.round(S().cpu) + '%', tint('blue')),
			stat('Frame rate', () => (S().fps || 0).toFixed(2), tint('ok')),
			stat('Render lag', () => S().renderLag + ' frames', S().renderLag ? tint('amber') : tint('ok')),
			stat('Encoder lag', () => S().encodeLag + ' frames', S().encodeLag ? tint('amber') : tint('ok')),
			stat('Disk left for recording', () => { const hh = diskHoursLeft(S()); return hh === null ? '–' : hh.toFixed(1) + ' h'; }, tint('purple')),
			stat('Scene', () => S().scene || '–', tint('teal'))),
		h('div.small', "Render and encoder lag count frames OBS couldn't keep up with. Anything above 0 that keeps rising means the PC is struggling.")];
	} else {
		const match = state.plugin && state.plugin === VERSION;
		body = [h('div.stat-grid',
			stat('This browser', () => networkLine().split(' · ')[0].replace('Network ', '').replace(/^./, (c) => c.toUpperCase()), tint('ok')),
			stat('Delay to the PC', () => state.latencyMs + ' ms', tint('blue')),
			stat('Audio dropouts', () => audio.dropoutsLastMinute() + ' a minute', tint('ok')),
			stat('PC address', () => location.hostname, tint('purple')),
			stat('Plugin and page', () => (state.plugin || '–') + (match ? ' · match' : state.plugin ? ' · page ' + VERSION : ''), match ? tint('ok') : tint('amber'))),
		h('div.small', "This is the link between this computer and the OBS PC, not the stream's internet connection.")];
	}
	return [h('h1.title', 'Stream and PC'),
		h('div.tabs', { role: 'tablist' }, tabs.map((t) => h('button', { type: 'button', role: 'tab', 'aria-selected': String(view.streamTab === t), onclick: () => { view.streamTab = t; render(); } }, t))),
		body];
}

function drawHealth(canvas) {
	const dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, hgt = canvas.clientHeight;
	if (!w) return;
	canvas.width = w * dpr; canvas.height = hgt * dpr;
	const g = canvas.getContext('2d');
	g.scale(dpr, dpr);
	const bars = state.health.slice(-30), n = 30, gap = 3, bw = (w - gap * (n - 1)) / n;
	const top = Math.max(1, settings.dropsPerMinute, ...bars);
	bars.forEach((d, i) => {
		const x = (n - bars.length + i) * (bw + gap), bh = Math.max(4, (hgt * d) / top);
		g.fillStyle = tokenColour(d >= settings.dropsPerMinute ? '--amber-ink' : d > 0 ? '--ok-ink' : '--chart-empty');
		g.fillRect(x, hgt - bh, bw, bh);
	});
	canvas.setAttribute('aria-label', bars.length ? 'Dropped frames per minute, most recent ' + bars[bars.length - 1] + ', highest ' + Math.max(...bars) : 'No stream data yet');
}

/* ---------- recordings ----------
 * Clips first, on one page. Each recording is a card with its clips inside; click a heading to
 * open or fold it. Every clip has its own Play button, and a small player shows at the bottom while
 * one plays. Clicking a clip opens one sheet with Play, Trim, Note, Download and Delete; a
 * recording's ⋯ opens Export and Delete. Recordings without clips stay folded at the end. */
const clipKey = (r, c) => r.file + ':' + c.id;
const clipName = (c) => c.note || 'Clip at ' + tc(c.startMs);
const clipMeta = (c) => clipLength(c.endMs - c.startMs) + ' long · at ' + tc(c.startMs);
const recMeta = (r) => (r.live ? 'Recording now' : durationText(r.durationMs)) + ' · ' + clipsText(r.clips.length);

/* Undo after a delete: one at a time. A deleted recording's saved audio goes only once Undo has gone. */
let undoing = null;
let undoTimer = 0;
function offerUndo(text, undo, commit) {
	if (undoing && undoing.commit) undoing.commit();
	clearTimeout(undoTimer);
	undoing = { text, undo, commit };
	undoTimer = setTimeout(() => { if (undoing && undoing.commit) undoing.commit(); undoing = null; render(); }, 6000);
}

function deleteRecording(r) {
	if (player.key && player.key.startsWith(r.file + ':')) player.stop();
	const at = recordings.indexOf(r);
	recordings = recordings.filter((x) => x !== r);
	saveRecordings();
	offerUndo('Recording deleted', () => { recordings.splice(Math.min(at, recordings.length), 0, r); saveRecordings(); }, () => idb.remove(r.file));
}

function recordingsPage() {
	const order = (a, b) => (b.live - a.live) || (b.startedAt - a.startedAt);
	const withClips = recordings.filter((r) => r.live || r.clips.length).sort(order);
	const empty = recordings.filter((r) => !r.live && !r.clips.length).sort(order);
	const total = recordings.reduce((n, r) => n + r.clips.length, 0);
	/* the recording OBS is making and the two newest others start open */
	if (!view.openRecs) view.openRecs = new Set(withClips.filter((r) => r.live).map((r) => r.file).concat(withClips.filter((r) => !r.live).slice(0, 2).map((r) => r.file)));
	if (view.name === 'recording' && view.recording) {
		view.openRecs.add(view.recording);
		const target = view.recording;
		view.name = 'recordings';
		setTimeout(() => { const el = document.getElementById('rec-' + cssId(target)); if (el) el.scrollIntoView({ block: 'start', behavior: 'smooth' }); }, 0);
	}
	const playingKey = player.key && player.key.includes(':') ? player.key : null;

	const card = (r) => {
		const open = view.openRecs.has(r.file);
		const head = h('div.rec-head',
			h('button.rec-toggle', { type: 'button', 'aria-expanded': String(open), onclick: () => { open ? view.openRecs.delete(r.file) : view.openRecs.add(r.file); render(); } },
				r.live ? h('span.rec-pill', h('span.dot', { style: { background: 'var(--rec)' } }), 'REC') : null,
				h('span.grow', h('h2.rec-title', recordingTitle(r)), h('span.small', recMeta(r))),
				h('span.chev' + (open ? '.up' : '.down'), icon('chev'))),
			h('button.round.plain', { type: 'button', 'aria-label': 'More for ' + recordingTitle(r), onclick: () => { view.sheet = { rec: r.file }; render(); } }, icon('more', 22)));
		const body = !open ? null : h('div.rec-body',
			!r.clips.length ? h('div.small', { style: { padding: '14px 16px 16px' } }, 'No clips yet. Click Start clip on Monitor when something good happens.') : null,
			r.clips.map((c) => {
				const key = clipKey(r, c), playing = playingKey === key;
				return h('div.clip-row' + (playing ? '.playing' : ''),
					h('button.clip-play', { type: 'button', 'aria-label': (playing ? 'Stop ' : 'Play ') + clipName(c), onclick: () => (playing ? player.stop() : player.play(key, r.file, c.startMs, c.endMs)) }, icon(playing ? 'pause' : 'play', 22)),
					h('button.clip-open', { type: 'button', onclick: () => { view.sheet = { clip: key }; view.sheetMode = 'clip'; render(); } },
						h('span.clip-note' + (c.note ? '' : '.empty'), c.note || 'Add a note'), h('span.small', clipMeta(c))),
					h('button.round.plain', { type: 'button', 'aria-label': 'More for ' + clipName(c), onclick: () => { view.sheet = { clip: key }; view.sheetMode = 'clip'; render(); } }, icon('more', 22)));
			}),
			settings.pcClipLists && r.savedOnPc ? h('div.rec-pc', icon('pc', 16), 'The clip list is saved on ' + r.savedOnPc + ' too, next to the recording.') : null);
		return h('section.rec-card' + (r.live ? '.live' : ''), { id: 'rec-' + cssId(r.file) }, head, body);
	};

	const out = [h('div', h('h1.title', 'Recordings'),
		h('div.sub', recordings.length ? clipsText(total) + ' in ' + withClips.length + (withClips.length === 1 ? ' recording' : ' recordings') + ' · kept in this browser' : 'Kept in this browser')),
	!recordings.length && h('section.section', h('div.small', 'No recordings yet. Clips you mark while OBS records show up here, under their recording.')),
	withClips.map(card),
	empty.length ? h('button.fold', { type: 'button', 'aria-expanded': String(!!view.showEmpty), onclick: () => { view.showEmpty = !view.showEmpty; render(); } },
		h('span.grow', empty.length === 1 ? '1 recording without clips' : empty.length + ' recordings without clips'), h('span.chev' + (view.showEmpty ? '.up' : '.down'), icon('chev'))) : null,
	empty.length && view.showEmpty ? h('div.list', empty.map((r) => h('div.list-row',
		h('span.grow', h('div', recordingTitle(r)), h('div.small', durationText(r.durationMs))),
		h('button.round.plain', { type: 'button', 'aria-label': 'Delete ' + recordingTitle(r), style: { color: 'var(--rec-ink)' }, onclick: () => deleteRecording(r) }, icon('trash'))))) : null,
	recordings.length ? h('button.btn.danger', { type: 'button', onclick: () => {
		if (!confirm('Delete all recordings and clips in this browser? Their notes and saved audio go too. The recordings on the PC are not touched. A recording OBS is making now keeps going, without its clips.')) return;
		player.stop();
		const live = recordings.filter((r) => r.live);
		for (const r of recordings) if (!r.live) idb.remove(r.file);
		recordings = live.map((r) => Object.assign(r, { clips: [] }));
		saveRecordings();
		toast('Deleted');
	} }, icon('trash', 18), 'Delete all recordings and clips') : null];

	/* Undo, then the small player, floating at the bottom so nothing in the list moves */
	const dock = h('div.rec-dock',
		undoing ? h('div.undo-snack', { role: 'status' }, h('span.grow', undoing.text),
			h('button', { type: 'button', onclick: () => { const u = undoing; clearTimeout(undoTimer); undoing = null; u.undo(); render(); } }, 'Undo')) : null,
		playingKey ? miniPlayer(playingKey) : null);
	if (dock.childNodes.length) out.push(dock);
	if (view.sheet) out.push(recordingSheet());
	return out;
}

const cssId = (s) => s.replace(/[^\w-]/g, '_');

/** The player at the bottom while a clip plays: which clip, how far, and Stop. */
function miniPlayer(key) {
	const r = recordings.find((x) => key.startsWith(x.file + ':'));
	const c = r && r.clips.find((x) => clipKey(r, x) === key);
	const fill = h('div.mini-fill');
	const span = () => Math.max(1, player.toMs - player.fromMs);
	const done = () => Math.min(span(), Math.max(0, player.posMs() - player.fromMs));
	const time = bind(h('span.small'), () => len(done()) + ' of ' + len(span()) + (r ? ' · ' + recordingTitle(r) : ''));
	live.push([fill, null, () => { fill.style.width = (done() / span() * 100).toFixed(1) + '%'; }]);
	fill.style.width = (done() / span() * 100).toFixed(1) + '%';
	return h('div.mini-player',
		h('div.mini-row', h('span.grow', h('div.mini-title', c ? clipName(c) : 'Playing'), time),
			h('button.mini-stop', { type: 'button', 'aria-label': 'Stop', onclick: () => player.stop() }, icon('stop', 18))),
		h('div.mini-track', fill));
}

/** One sheet for a clip (Play, Trim, Note, Download, Delete) or a recording (Export, Delete). */
function recordingSheet() {
	const dlg = h('dialog.sheet', { 'aria-labelledby': 'sheet-title' });
	const close = () => { if (view.sheetMode === 'trim' && player.key === 'trim') player.stop(); view.sheet = null; view.sheetMode = 'clip'; view.trim = null; dlg.close(); render(); };
	let title = '', sub = '', body = [];
	if (view.sheet.clip) {
		const key = view.sheet.clip;
		const r = recordings.find((x) => key.startsWith(x.file + ':'));
		const c = r && r.clips.find((x) => clipKey(r, x) === key);
		if (!c) { view.sheet = null; return null; }
		const mode = view.sheetMode || 'clip';
		title = mode === 'trim' ? 'Trim clip' : mode === 'note' ? 'Note' : clipName(c);
		sub = clipMeta(c) + ' · ' + recordingTitle(r);
		const tile = (label, ic, t, onclick) => h('button.sheet-tile', { type: 'button', style: { background: `var(--${t}-tint)`, color: `var(--${t}-ink)` }, onclick }, icon(ic, 22), label);
		if (mode === 'trim') {
			if (!view.trim) view.trim = { start: c.startMs, end: c.endMs };
			const t = view.trim;
			const from = Math.max(0, Math.min(t.start, c.startMs) - 30000), to = Math.max(t.end, c.endMs) + 30000, sp = to - from;
			const row = (label, which) => h('div.trim-row',
				h('span.grow', h('span.small', label), h('div.trim-time', tc(t[which]))),
				h('button', { type: 'button', 'aria-label': label + ': 5 seconds earlier', onclick: () => { t[which] = which === 'start' ? Math.max(0, t.start - 5000) : Math.max(t.start + 1000, t.end - 5000); render(); } }, '−5 s'),
				h('button', { type: 'button', 'aria-label': label + ': 5 seconds later', onclick: () => { t[which] = which === 'start' ? Math.min(t.end - 1000, t.start + 5000) : t.end + 5000; render(); } }, '+5 s'));
			const trimPlaying = player.key === 'trim';
			body = [
				h('div.trim-bar', { role: 'img', 'aria-label': 'Kept: ' + tc(t.start) + ' to ' + tc(t.end) },
					h('div.trim-kept', { style: { left: ((t.start - from) / sp * 100).toFixed(1) + '%', width: ((t.end - t.start) / sp * 100).toFixed(1) + '%' } })),
				row('Starts at', 'start'), row('Ends at', 'end'),
				h('button.btn.outline', { type: 'button', onclick: () => (trimPlaying ? player.stop() : player.play('trim', r.file, t.start, t.end)) }, icon(trimPlaying ? 'stop' : 'play', 18), trimPlaying ? 'Stop' : 'Play ' + clipLength(t.end - t.start)),
				h('div.row',
					h('button.btn.outline.grow', { type: 'button', onclick: () => { if (trimPlaying) player.stop(); view.trim = null; view.sheetMode = 'clip'; render(); } }, 'Cancel'),
					h('button.btn.primary.grow', { type: 'button', onclick: () => { player.stop(); c.startMs = t.start; c.endMs = t.end; r.clips.sort((a, b) => a.startMs - b.startMs); view.trim = null; view.sheetMode = 'clip'; saveRecordings(); toast('Clip trimmed to ' + clipLength(t.end - t.start)); render(); } }, 'Save'))];
		} else if (mode === 'note') {
			const input = h('input', { type: 'text', value: c.note || '', maxlength: 140, 'aria-label': 'Note for this clip', placeholder: 'What happens in this clip?' });
			const save = () => { const v = input.value.trim(); c.note = v; view.sheetMode = 'clip'; saveRecordings(); toast(v ? 'Note saved' : 'Note removed'); render(); };
			input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); });
			setTimeout(() => input.focus(), 0);
			body = [input, h('div.row', h('button.btn.outline.grow', { type: 'button', onclick: () => { view.sheetMode = 'clip'; render(); } }, 'Cancel'), h('button.btn.primary.grow', { type: 'button', onclick: save }, 'Save'))];
		} else {
			body = [
				h('button.btn.primary.big', { type: 'button', onclick: () => { player.play(key, r.file, c.startMs, c.endMs); close(); } }, icon('play', 18), player.key === key ? 'Playing…' : 'Play clip'),
				h('div.sheet-tiles',
					tile('Trim', 'tune', 'teal', () => { view.sheetMode = 'trim'; view.trim = null; render(); }),
					tile('Note', 'edit', 'amber', () => { view.sheetMode = 'note'; render(); }),
					tile('Download', 'down', 'purple', () => downloadClipAudio(r, c))),
				h('button.btn.danger', { type: 'button', onclick: () => {
					if (player.key === key) player.stop();
					r.clips = r.clips.filter((x) => x.id !== c.id);
					saveRecordings();
					offerUndo('Clip deleted', () => { r.clips.push(c); r.clips.sort((a, b) => a.startMs - b.startMs); saveRecordings(); });
					close();
				} }, icon('trash', 18), 'Delete clip')];
		}
	} else {
		const r = recordings.find((x) => x.file === view.sheet.rec);
		if (!r) { view.sheet = null; return null; }
		title = recordingTitle(r);
		sub = recMeta(r) + ' · ' + r.file;
		body = [
			r.clips.length ? h('div.small', 'Export the clip list for your editor. Click a format to download it, or Copy to paste it.') : null,
			r.clips.length ? h('div.export-list', Exporter.formats.map((f) => h('div.export-row',
				h('button.export-main', { type: 'button', onclick: () => {
					settings.exportFormat = f.id; saveSettings();
					download(r.file.replace(/\.[^.]*$/, '') + '.' + Exporter.ext(f.id), new Blob([Exporter.export(r, f.id)], { type: 'text/plain' }));
					close();
				} },
					h('span.tint-tile', { style: { width: '36px', height: '36px', background: `var(--${f.tint}-tint)`, color: `var(--${f.tint}-ink)`, fontSize: '12px', fontWeight: 700 } }, f.badge),
					h('span.grow', h('div', f.label), h('div.small', f.hint))),
				h('button.copy', { type: 'button', 'aria-label': 'Copy the ' + f.label + ' list', onclick: async () => {
					try { await navigator.clipboard.writeText(Exporter.export(r, f.id)); toast('Copied'); } catch { toast("This browser didn't allow copying."); }
				} }, 'Copy')))) : null,
			r.live ? null : h('button.btn.danger', { type: 'button', onclick: () => { close(); deleteRecording(r); render(); } }, icon('trash', 18), 'Delete this recording'),
			r.live ? null : h('div.small', { style: { textAlign: 'center' } }, 'Only from this browser. The recording on the PC isn’t touched.')];
	}
	dlg.append(h('div.sheet-body',
		h('div', h('h2.card-title#sheet-title', title), h('div.small', sub)),
		body));
	dlg.addEventListener('cancel', (e) => { e.preventDefault(); close(); });
	dlg.addEventListener('click', (e) => { if (e.target === dlg) close(); });
	setTimeout(() => { if (dlg.isConnected && !dlg.open) dlg.showModal(); }, 0);
	return dlg;
}

/* ---------- settings ---------- */
function settingsPage() {
	const groups = [
		['Connection', 'ok', [
			['info', 'pc', 'OBS PC', () => state.pc + ' · ' + location.host],
			['text', 'name', 'This browser in OBS', 'deviceName'],
		]],
		['Listening', 'teal', [
			['switch', 'head', 'Start listening when connected', 'autoListen'],
		]],
		['Clips', 'rec', [
			['choice', 'rewind', 'Start before your click', 'leadMinutes', [[1, '1 min'], [2, '2 min'], [3, '3 min'], [5, '5 min']]],
			['choice', 'forward', 'Keep going after End', 'padSeconds', [[0, '0 s'], [2, '2 s'], [5, '5 s']]],
			['switch', 'bookmark', 'Chapter marker in the recording', 'chapters'],
			['switch', 'file', 'Save the clip list on the PC', 'pcClipLists'],
			['choice', 'export', 'Export as', 'exportFormat', Exporter.formats.map((f) => [f.id, f.label])],
			['switch', 'keyboard', 'Space starts and ends a clip', 'spaceForClip'],
		]],
		['Alerts', 'orange', [
			['choice', 'mute', 'Mix goes silent for', 'silenceSeconds', [[0, 'Off'], [10, '10 s'], [20, '20 s'], [30, '30 s']]],
			['switch', 'peak', 'Mix clips (peaks at 0 dB)', 'alertClipping'],
			['switch', 'stop', 'Someone else stops the recording', 'alertRecStop'],
			['choice', 'disk', 'Disk space below', 'diskHours', [[1, '1 h'], [2, '2 h'], [4, '4 h']]],
			['choice', 'chart', 'Dropped frames over', 'dropsPerMinute', [[50, '50 a minute'], [100, '100 a minute'], [500, '500 a minute']]],
		]],
		['Audio in this browser', 'purple', [
			['switch', 'mic', 'Save the session audio, to play clips back', 'saveAudio'],
			['choice', 'calendar', 'Keep it for', 'keepDays', [[7, '7 days'], [30, '30 days'], [0, 'Forever']]],
		]],
		['Controls', 'amber', [
			['choice', 'timer', 'Hold to stop recording', 'holdMs', [[500, '0.5 s'], [1000, '1 s'], [2000, '2 s']]],
			['switch', 'power', 'Allow ending the stream', 'allowStreamStop'],
			['choice', 'tap', 'Touch sound', 'touchSound', [[0, 'Off'], [25, '25%'], [50, '50%'], [75, '75%'], [100, '100%']]],
			['choice', 'vibrate', 'Vibration (phones only)', 'haptics', [[0, 'Off'], [1, 'Light'], [2, 'Medium'], [3, 'Strong']]],
		]],
	];
	const item = ([kind, ic, label, key, options], t, first) => {
		const tile = h('span.tint-tile', { style: { background: `var(--${t}-tint)`, color: `var(--${t}-ink)` } }, icon(ic, 18));
		const base = { style: first ? { borderTop: 0 } : null };
		if (kind === 'switch') {
			const on = !!settings[key];
			return h('button.setting', Object.assign({ type: 'button', role: 'switch', 'aria-checked': String(on), onclick: () => { settings[key] = !on; saveSettings(); render(); } }, base),
				tile, h('span.grow', label), h('span.switch', { 'aria-checked': String(on), 'aria-hidden': 'true' }));
		}
		if (kind === 'choice') {
			const sel = h('select.choice', { 'aria-label': label, onchange: (e) => { const o = options[e.target.selectedIndex]; settings[key] = o[0]; saveSettings(); if (key === 'touchSound' || key === 'haptics') feedback.tap(); if (key === 'haptics') feedback.buzz([40, 80, 40]); render(); } },
				options.map(([v, l]) => h('option', { selected: settings[key] === v }, l)));
			return h('label.setting', base, tile, h('span.grow', label), sel);
		}
		if (kind === 'text') {
			const input = h('input', { type: 'text', value: settings[key], maxlength: 40, 'aria-label': label,
				onchange: (e) => { settings[key] = e.target.value.trim() || defaultName(); saveSettings(); toast('OBS shows the new name from the next connection.'); render(); } });
			return h('label.setting.with-field', base, tile, h('span.grow', label), input);
		}
		return h('div.setting', base, tile, h('span.grow', label), bind(h('span.value', { style: { color: `var(--${t}-ink)` } }), key));
	};
	return [h('div', h('h1.title', 'Settings'), h('div.sub', 'Saved in this browser as you change them')),
		groups.map(([title, t, rows]) => [h('div.group-label', { style: { color: `var(--${t}-ink)` } }, title), h('div.list', rows.map((r, i) => item(r, t, i === 0)))]),
		h('div.group-label', { style: { color: 'var(--muted)' } }, 'About'),
		h('section.section', h('div', { style: { fontWeight: 500 } }, 'OBS Director page ' + VERSION + (state.plugin ? ' · plugin ' + state.plugin : '')),
			h('div.small', 'This page comes from the plugin, so it updates when you update the plugin on the PC. Settings, clips and saved audio stay in this browser; it talks only to the OBS PC.'),
			h('div.row', h('button.btn.outline', { type: 'button', onclick: () => {
				if (confirm('Forget this browser? OBS will ask to allow it again, and the clips and saved audio in this browser are removed.')) {
					try { Object.keys(localStorage).filter((k) => k.startsWith('obsdirector.')).forEach((k) => localStorage.removeItem(k)); } catch { /* fine */ }
					try { indexedDB.deleteDatabase('obsdirector'); } catch { /* fine */ }
					location.reload();
				}
			} }, 'Forget this browser'), h('button.btn.outline', { type: 'button', onclick: () => location.reload() }, 'Replay the connect step')))];
}

/* ---------- stage mode ---------- */
function stageView() {
	const s = state.status || {};
	const running = state.clipStart !== null;
	const r = recordings.find((x) => x.file === s.file);
	const btn = h('button.big-clip' + (running ? '.running' : ''), { type: 'button', onclick: toggleClip, disabled: !s.recActive && !running },
		running ? bind(h('span.b'), () => clipLength(nowFileTimeMs() - state.clipStart)) : h('span.b', 'Clip'),
		h('span.s', running ? 'End clip' : s.recActive ? 'starts ' + settings.leadMinutes + ' min before your click' : 'Start recording to clip'));
	if (running) live.push([btn, null, () => btn.setAttribute('aria-label', 'End clip, ' + clipLength(nowFileTimeMs() - state.clipStart) + ' long')]);
	return h('main.stage', h('h1.sr', 'Stage mode'),
		h('div.top', h('span.dot', { style: { background: s.recActive ? 'var(--stage-rec)' : 'var(--stage-muted)' } }), (s.recActive ? (s.recPaused ? 'PAUSED' : 'REC') : 'NOT RECORDING') + ' · ' + state.pc),
		bind(h('div.time'), () => tc(nowFileTimeMs())),
		meterBlock(true),
		h('div.hint', (r ? clipsText(r.clips.length) : 'No clips') + ' so far'),
		btn,
		h('div.hint', 'Space starts and ends a clip. Esc leaves stage mode.'),
		h('button.exit', { type: 'button', onclick: () => { view.name = 'monitor'; render(); } }, 'Exit stage mode'));
}

/* =============================================================== running */

function tick() {
	if (structureKey() !== signature) return render();
	for (const [el, fn, side] of live) {
		if (fn) { const t = typeof fn === 'function' ? fn() : String(fn); if (el.textContent !== t) el.textContent = t; }
		if (side) side();
	}
}
setInterval(tick, 250);

/* The meters' frame loop runs only while a meter is on screen; render() restarts it. */
let framing = false;
function frame() {
	meters.step();
	for (const [c, dim] of meterCanvases) meters.draw(c, dim);
	framing = meterCanvases.length > 0;
	if (framing) requestAnimationFrame(frame);
}
function startFrames() {
	if (!framing && meterCanvases.length) { framing = true; requestAnimationFrame(frame); }
}

document.addEventListener('keydown', (e) => {
	const t = e.target;
	if (t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) || t.isContentEditable)) return;
	if (e.key === ' ' && settings.spaceForClip && state.link === 'connected' && !e.repeat && (!t || t.tagName !== 'BUTTON' || view.name === 'stage')) {
		e.preventDefault();
		toggleClip();
	} else if (e.key === 'Escape' && view.name === 'stage') {
		view.name = 'monitor';
		render();
	}
});
/* Browsers let a page play sound only after a click: the first one unlocks listening. It starts
 * after that click has done its own job, so redrawing the screen can't swallow the click. */
for (const ev of ['click', 'keydown']) {
	document.addEventListener(ev, () => {
		if (audio.unlocked) return;
		audio.unlock();
		setTimeout(() => { if (settings.autoListen && state.link === 'connected' && !state.listening) audio.start(); }, 0);
	}, { capture: true });
}
window.addEventListener('beforeunload', () => { audio.endSegment(); });

idb.prune(settings.keepDays);
render();
connect();
