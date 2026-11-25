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
	desk: store.get('desk', []),
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
		if (wasLost) toast('Reconnected after ' + len(Date.now() - state.lostAt) + '.');
		if (state.listening || (settings.autoListen && audio.unlocked)) audio.start();
		if (state.video) video.start();
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
	} else if (name === 'desk.reply') {
		const msg = state.desk.find((x) => x.id === d.id);
		if (msg) { msg.reply = d.text; store.set('desk', state.desk.slice(-50)); }
		toast('Desk: ' + d.text);
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
	clippedAt: 0,
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
		if (!state.listening) return 'Not listening';
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

function raise(a) {
	const had = state.alerts.find((x) => x.id === a.id);
	state.alerts = state.alerts.filter((x) => x.id !== a.id).concat([a]);
	if (!had) render();
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

/* =============================================================== desk */

function sendDesk(text) {
	const t = text.trim().slice(0, 200);
	if (!t) return;
	const id = newId().slice(0, 8);
	state.desk.push({ id, text: t, at: Date.now() });
	store.set('desk', state.desk.slice(-50));
	act('desk.send', { id, text: t });
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
	return [state.link, view.name, view.recording, view.open, view.mode, view.streamTab, view.exporting, s.recActive, s.recPaused, s.streamActive,
		state.clipStart !== null, state.listening, state.video, state.alerts.map((a) => a.id).join(), !!state.lastSaved, settings.allowStreamStop,
		player.key, recordings.length].join('|');
}

/** Rebuild the screen. Whatever is being typed (text, cursor, focus) carries over. */
function render() {
	const a = document.activeElement;
	const keep = a && /^(INPUT|TEXTAREA)$/.test(a.tagName) && a.type !== 'range' && $('#view').contains(a) && a.getAttribute('aria-label')
		? { label: a.getAttribute('aria-label'), value: a.value, start: a.selectionStart, end: a.selectionEnd } : null;
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
		}
	}
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
function renderBanners() {
	const box = $('#banners');
	const wanted = [];
	if (state.link === 'lost') wanted.push(['lost:' + state.lostAt, () => {
		/* the seconds tick visibly only, so a screen reader hears the banner once */
		const ago = h('span', { 'aria-hidden': 'true' });
		const node = h('div.banner', h('div.body', h('b', 'Connection to ' + (state.pc || 'the PC') + ' lost. '), ago,
			' Listening paused. The recording carries on at the PC. Reconnecting…'));
		node.tickers = [[ago, () => len(Date.now() - state.lostAt) + ' ago.']];
		return node;
	}]);
	for (const a of state.alerts) wanted.push(['alert:' + a.id, () => {
		const body = h('span');
		const node = h('div.banner' + (a.severe ? '.severe' : ''),
		h('div.body', h('b', a.title + ' '), body,
			h('div.actions',
				a.offerStart && h('button.btn.primary.small-btn', { type: 'button', onclick: () => { act('record.start'); clearAlert(a.id); } }, icon('rec', 16), 'Start recording'),
				a.desk && h('button.btn.outline.small-btn', { type: 'button', onclick: () => { sendDesk(a.desk); toast('Sent to the desk'); } }, icon('send', 16), 'Tell the desk'))),
		h('button.round.plain', { type: 'button', 'aria-label': 'Dismiss: ' + a.title, onclick: () => clearAlert(a.id) }, icon('close', 18)));
		/* the latest wording (the disk alert's hours, say) without rebuilding the banner */
		node.tickers = [[body, () => (state.alerts.find((x) => x.id === a.id) || a).body]];
		return node;
	}]);
	const keys = new Set(wanted.map((w) => w[0]));
	for (const [key, node] of bannerNodes) if (!keys.has(key)) { node.remove(); bannerNodes.delete(key); }
	for (const [key, build] of wanted) {
		let node = bannerNodes.get(key);
		if (!node) { node = build(); bannerNodes.set(key, node); box.append(node); }
		for (const [el, fn] of node.tickers || []) bind(el, fn);
	}
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
	const nav = [['monitor', 'Monitor', 'teal'], ['stream', 'Stream and PC', 'rec'], ['recordings', 'Recordings', 'amber'], ['settings', 'Settings', 'purple']];
	const current = view.name === 'recording' ? 'recordings' : view.name;
	const page = { monitor: monitorPage, stream: streamPage, recordings: recordingsPage, recording: recordingPage, settings: settingsPage }[view.name] || monitorPage;
	return h('div.app',
		h('nav.nav', { 'aria-label': 'Main' },
			nav.map(([id, label, sw]) => h('button', { type: 'button', 'aria-current': current === id ? 'page' : 'false', onclick: () => { view.name = id; view.open = null; render(); } },
				h('span.swatch', { style: { background: `var(--${sw}-ink)` } }), label,
				id === 'recordings' && recordings.some((r) => r.live) ? h('span.badge', h('span.dot', { style: { background: 'var(--rec)' } }), h('span.sr', ', recording now')) : null)),
			h('div.nav-foot', 'Plugin ' + (state.plugin || '–') + ' · page ' + VERSION)),
		h('main.main', h('div.page', page())));
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
		running && h('button.btn.outline', { type: 'button', onclick: cancelClip }, 'Cancel this clip'),
		state.lastSaved && h('div.small', { role: 'status' }, state.lastSaved),
		state.noteFor && noteForm(),
		h('div.row',
			s.recActive
				? [h('button.btn.outline', { type: 'button', onclick: () => act(s.recPaused ? 'record.resume' : 'record.pause') }, icon(s.recPaused ? 'play' : 'pause', 18), s.recPaused ? 'Resume' : 'Pause'),
					holdButton('Hold to stop', settings.holdMs, () => { stopRequestedHere = true; act('record.stop'); })]
				: h('button.btn.primary', { type: 'button', onclick: () => act('record.start') }, icon('rec', 18), 'Start recording')),
		r && r.clips.length ? h('button.btn.outline', { type: 'button', onclick: () => { view.name = 'recording'; view.recording = r.file; render(); } },
			clipsText(r.clips.length) + ' in this recording · see all') : null);
}

function noteForm() {
	const { file, id } = state.noteFor;
	const r = recordings.find((x) => x.file === file), c = r && r.clips.find((x) => x.id === id);
	if (!c) { state.noteFor = null; return null; }
	const input = h('input', { type: 'text', maxlength: 140, placeholder: 'What was it? (optional)', 'aria-label': 'Note for this clip' });
	const save = () => { c.note = input.value.trim(); state.noteFor = null; saveRecordings(); render(); };
	input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); if (e.key === 'Escape') { state.noteFor = null; render(); } });
	return h('div.row', input, h('button.btn.primary', { type: 'button', onclick: save }, 'Save note'), h('button.btn.outline', { type: 'button', onclick: () => { state.noteFor = null; render(); } }, 'Skip'));
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
		boostHint.hidden = vol.value <= 100;
	});
	vol.addEventListener('change', saveSettings);
	const boostHint = h('div.small', { style: { color: 'var(--amber-ink)' }, hidden: settings.volume <= 1 }, 'Above 100% is louder in this browser only. A soft limiter stops crackles.');
	return h('section.section', { 'aria-label': 'Listening' },
		h('div.row', h('span.tint-tile', { style: { background: 'var(--teal-tint)', color: 'var(--teal-ink)' } }, icon('head', 18)),
			h('div.grow', h('h2.card-title', 'Listen in this browser'),
				bind(h('div.small'), () => "This computer's sound output · about " + (audio.delayMs() + state.latencyMs || 120) + ' ms behind')),
			sw),
		!audio.unlocked && settings.autoListen && !state.listening && h('div.small', 'Browsers only play sound after a click: switch Listen on to start.'),
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

function deskSection() {
	const input = h('input', { type: 'text', maxlength: 200, placeholder: 'A message for the desk', 'aria-label': 'Message the desk' });
	const go = () => { const text = input.value; input.value = ''; sendDesk(text); };
	input.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
	return h('section.section', { 'aria-label': 'Message the desk' },
		h('h2.card-title', 'Message the desk'),
		h('div.row', h('div.grow', input), h('button.btn.primary', { type: 'button', onclick: go }, icon('send', 18), 'Send')),
		state.desk.length ? h('div.desk-list', state.desk.slice(-3).reverse().map((m) => {
			const d = new Date(m.at);
			return h('div.desk-msg', h('span.when', pad2(d.getHours()) + ':' + pad2(d.getMinutes())), h('span.grow', m.text), m.reply && h('span.reply', m.reply));
		})) : null);
}

function monitorPage() {
	return [h('h1.sr', 'Monitor'),
		h('div.cols', h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px' } }, recordingSection()),
			h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px' } }, listenSection(), chipsRow())),
		h('div.cols', pictureSection(), deskSection())];
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

/* ---------- recordings ---------- */
function recordingsPage() {
	const live_ = recordings.filter((r) => r.live), past = recordings.filter((r) => !r.live);
	const tiles = ['teal', 'purple', 'amber', 'ok'];
	const row = (r, t) => {
		const d = new Date(r.startedAt);
		return h('button.list-row', { type: 'button', onclick: () => { view.name = 'recording'; view.recording = r.file; view.open = null; render(); } },
			t ? h('span.date-tile', { style: { background: `var(--${t}-tint)`, color: `var(--${t}-ink)` } }, h('b', d.getDate()), h('small', d.toLocaleDateString(undefined, { month: 'short' }).toUpperCase()))
				: h('span.date-tile', { style: { background: 'var(--rec-tint)' } }, h('span.dot', { style: { width: '14px', height: '14px', background: 'var(--rec)' } })),
			h('span.grow', h('div', { style: { fontWeight: 500, fontSize: '16px' } }, recordingTitle(r)),
				h('div.small', (r.live ? 'Recording' : durationText(r.durationMs)) + ' · ' + clipsText(r.clips.length))),
			h('span.chev', icon('chev')));
	};
	return [h('div', h('h1.title', 'Recordings'), h('div.sub', 'Click one to see its clips and export them. Kept in this browser.')),
		!recordings.length && h('section.section', h('div.small', 'No recordings yet. Clips you mark while OBS records show up here, one row per recording.')),
		live_.length ? [h('div.group-label', { style: { color: 'var(--rec-ink)' } }, 'Now'), h('div.list', live_.map((r) => row(r, null)))] : null,
		past.length ? [h('div.group-label', { style: { color: 'var(--muted)' } }, 'Earlier'), h('div.list', past.map((r, i) => row(r, tiles[i % 4])))] : null];
}

let removed = null;
function recordingPage() {
	const r = recordings.find((x) => x.file === view.recording);
	const back = h('button.round.plain', { type: 'button', 'aria-label': 'Back to Recordings', onclick: () => { view.name = 'recordings'; render(); } }, icon('back'));
	if (!r) return [h('div.row', back, h('h1.title', 'Recording')), h('div.small', "This recording isn't in this browser any more.")];
	const out = [h('div.row', back,
		h('div.grow', h('h1.title', { style: { fontSize: '22px' } }, recordingTitle(r)),
			h('div.small', (r.live ? 'Recording' : durationText(r.durationMs)) + ' · ' + clipsText(r.clips.length) + ' · ' + r.file)),
		h('button.btn.primary', { type: 'button', disabled: !r.clips.length, onclick: () => { view.exporting = true; render(); } }, icon('export', 18), 'Export'))];
	if (settings.pcClipLists && r.savedOnPc)
		out.push(h('div.note-ok', icon('pc', 20), 'Saved on ' + r.savedOnPc + ' too, next to the recording as ' + r.file.replace(/\.[^.]*$/, '') + '.edl and .csv.'));
	if (removed && removed.file === r.file)
		out.push(h('div.row.section', { style: { padding: '8px 8px 8px 16px' } }, h('span.grow', 'Clip at ' + tc(removed.clip.startMs) + ' deleted.'),
			h('button.btn.outline', { type: 'button', onclick: () => { r.clips.push(removed.clip); r.clips.sort((a, b) => a.startMs - b.startMs); removed = null; saveRecordings(); } }, icon('undo', 18), 'Undo')));
	if (!r.clips.length) out.push(h('section.section', h('div.small', 'No clips in this recording.')));
	const tints = ['rec', 'blue', 'amber', 'teal'];
	r.clips.forEach((c, i) => {
		const key = r.file + ':' + c.id, isOpen = view.open === c.id, playing = player.key === key, t = tints[i % 4];
		const sub = playing ? bind(h('div.small', { style: { color: 'var(--ok-ink)' } }), () => 'Playing ' + len(player.posMs() - c.startMs) + ' of ' + len(c.endMs - c.startMs))
			: h('div.small', { style: { color: c.note ? 'var(--text-2)' : 'var(--muted)' } }, c.note || 'No note');
		const card = h('div.clip-card' + (isOpen ? '.open' : ''),
			h('div.clip-head', h('span.num', { style: { background: `var(--${t}-tint)`, color: `var(--${t}-ink)` } }, i + 1),
				h('div.grow', h('div', h('b', { style: { fontWeight: 500 } }, clipLength(c.endMs - c.startMs)), h('span.small', ' · at ' + tc(c.startMs))), sub),
				h('button.plus', { type: 'button', 'aria-expanded': String(isOpen), 'aria-label': (isOpen ? 'Close actions for clip ' : 'Actions for clip ') + (i + 1),
					onclick: () => { view.open = isOpen ? null : c.id; view.mode = 'actions'; render(); } }, icon('plus'))));
		if (isOpen) card.append(h('div.clip-body', clipActions(r, c, key, playing)));
		out.push(card);
	});
	if (view.exporting) out.push(exportDialog(r));
	return out;
}

function clipActions(r, c, key, playing) {
	const action = (label, ic, t, onclick) => h('button.action', { type: 'button', style: { background: `var(--${t}-tint)`, color: `var(--${t}-ink)` }, onclick }, icon(ic, 22), label);
	if (view.mode === 'adjust') return adjustPanel(r, c);
	if (view.mode === 'note') {
		const input = h('input', { type: 'text', value: c.note || '', maxlength: 140, 'aria-label': 'Note' });
		const save = () => { c.note = input.value.trim(); view.mode = 'actions'; saveRecordings(); render(); };
		input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); });
		setTimeout(() => input.focus(), 0);
		return [input, h('div.row', h('button.btn.outline', { type: 'button', onclick: () => { view.mode = 'actions'; render(); } }, 'Cancel'), h('button.btn.primary', { type: 'button', onclick: save }, 'Save note'))];
	}
	return [h('div.actions',
		action(playing ? 'Stop' : 'Play', playing ? 'stop' : 'play', 'blue', () => (playing ? player.stop() : player.play(key, r.file, c.startMs, c.endMs))),
		action('Adjust start and end', 'tune', 'teal', () => { view.mode = 'adjust'; render(); }),
		action('Edit note', 'edit', 'amber', () => { view.mode = 'note'; render(); }),
		action('Download audio', 'down', 'purple', () => downloadClipAudio(r, c))),
	h('button.btn.danger', { type: 'button', onclick: () => { player.stop(); r.clips = r.clips.filter((x) => x.id !== c.id); removed = { file: r.file, clip: c }; view.open = null; saveRecordings(); } }, icon('trash', 18), 'Delete clip')];
}

function adjustPanel(r, c) {
	const edit = { start: c.startMs, end: c.endMs };
	const startT = h('div', { style: { fontSize: '20px' } }), endT = h('div', { style: { fontSize: '20px' } }), lenT = h('div', { style: { fontSize: '20px' } });
	const show = () => {
		startT.textContent = tc(edit.start) + '.' + Math.floor((edit.start % 1000) / 100);
		endT.textContent = tc(edit.end) + '.' + Math.floor((edit.end % 1000) / 100);
		lenT.textContent = len(edit.end - edit.start);
	};
	const nudges = (which) => h('div.nudges', [-10000, -1000, -100, 100, 1000, 10000].map((d) => h('button', { type: 'button',
		'aria-label': 'Move ' + which + ' ' + (d > 0 ? 'later' : 'earlier') + ' by ' + Math.abs(d) / 1000 + ' seconds',
		onclick: () => {
			if (which === 'start') edit.start = Math.max(0, Math.min(edit.end - 1000, edit.start + d));
			else edit.end = Math.max(edit.start + 1000, edit.end + d);
			show();
		} }, (d > 0 ? '+' : '−') + (Math.abs(d) >= 1000 ? Math.abs(d) / 1000 : '0.' + Math.abs(d) / 100))));
	show();
	return [h('div.label', 'Start'), startT, nudges('start'), h('div.label', 'End'), endT, nudges('end'), h('div.label', 'Length'), lenT,
		h('div.row',
			h('button.btn.primary', { type: 'button', onclick: () => player.play('adj', r.file, edit.start, edit.end) }, icon('play', 18), 'Play clip'),
			h('button.btn.outline', { type: 'button', onclick: () => player.play('adj', r.file, Math.max(0, edit.start - 1500), edit.start + 1500) }, 'Hear start'),
			h('button.btn.outline', { type: 'button', onclick: () => player.play('adj', r.file, Math.max(0, edit.end - 1500), edit.end + 1500) }, 'Hear end')),
		h('div.row',
			h('button.btn.outline', { type: 'button', onclick: () => { player.stop(); view.mode = 'actions'; render(); } }, 'Cancel'),
			h('button.btn.primary', { type: 'button', onclick: () => { player.stop(); c.startMs = edit.start; c.endMs = edit.end; r.clips.sort((a, b) => a.startMs - b.startMs); view.mode = 'actions'; saveRecordings(); toast('Clip saved. The export uses the new times.'); } }, icon('check', 18), 'Save')),
		h('div.small', 'The chapter in the recording file marks where you clicked Start clip. Changed times go in the export.')];
}

function exportDialog(r) {
	let format = settings.exportFormat;
	const dlg = h('dialog', { 'aria-labelledby': 'export-title' });
	const list = h('div', { role: 'radiogroup', 'aria-label': 'Format', style: { display: 'flex', flexDirection: 'column', gap: '10px' } });
	const draw = () => {
		list.replaceChildren(...Exporter.formats.map((f) => h('button.format', { type: 'button', role: 'radio', 'aria-checked': String(f.id === format), onclick: () => { format = f.id; draw(); } },
			h('span.tint-tile', { style: { width: '36px', height: '36px', background: `var(--${f.tint}-tint)`, color: `var(--${f.tint}-ink)`, fontSize: '12px', fontWeight: 700 } }, f.badge),
			h('span.grow', h('div', { style: { fontWeight: 500 } }, f.label), h('div.small', f.hint)))));
	};
	draw();
	const close = () => { view.exporting = false; dlg.close(); render(); };
	dlg.append(h('div', { style: { display: 'flex', flexDirection: 'column', gap: '12px' } },
		h('h2.card-title#export-title', { style: { fontSize: '19px', fontWeight: 700 } }, 'Export clips'),
		h('div.small', clipsText(r.clips.length) + ' from ' + recordingTitle(r)),
		list,
		h('div.row', { style: { marginTop: '6px' } },
			h('button.btn.outline.grow', { type: 'button', onclick: async () => {
				try { await navigator.clipboard.writeText(Exporter.export(r, format)); toast('Copied'); } catch { toast("This browser didn't allow copying."); }
			} }, 'Copy'),
			h('button.btn.primary.grow', { type: 'button', onclick: () => {
				settings.exportFormat = format; saveSettings();
				download(r.file.replace(/\.[^.]*$/, '') + '.' + Exporter.ext(format), new Blob([Exporter.export(r, format)], { type: 'text/plain' }));
				close();
			} }, icon('down', 18), 'Download')),
		h('button.btn.outline', { type: 'button', onclick: close }, 'Close')));
	dlg.addEventListener('cancel', (e) => { e.preventDefault(); close(); });
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
			const sel = h('select.choice', { 'aria-label': label, onchange: (e) => { const o = options[e.target.selectedIndex]; settings[key] = o[0]; saveSettings(); render(); } },
				options.map(([v, l]) => h('option', { selected: settings[key] === v }, l)));
			return h('label.setting', base, tile, h('span.grow', label), sel);
		}
		if (kind === 'text') {
			const input = h('input', { type: 'text', value: settings[key], maxlength: 40, 'aria-label': label, style: { maxWidth: '240px' },
				onchange: (e) => { settings[key] = e.target.value.trim() || defaultName(); saveSettings(); toast('OBS shows the new name from the next connection.'); render(); } });
			return h('label.setting', base, tile, h('span.grow', label), input);
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

function frame() {
	meters.step();
	for (const [c, dim] of meterCanvases) meters.draw(c, dim);
	requestAnimationFrame(frame);
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
requestAnimationFrame(frame);
connect();
