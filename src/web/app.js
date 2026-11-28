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

/**
 * The volume slider's scale, the same as the app's (audio/Loudness.kt). 1-100%, an even step in
 * loudness each: 1% is -40 dB, 25% -20 dB, 50% the mix as OBS sends it, 75% +12 dB, 100% +24 dB.
 */
const Loudness = {
	AS_SENT: 50, MIN_DB: -40, MAX_DB: 24,
	marks: ['−40 dB', '−20', 'As sent', '+12', '+24 dB'],
	db(p) { p = Math.max(1, Math.min(100, p)); return p <= 50 ? this.MIN_DB - this.MIN_DB * (p - 1) / 49 : this.MAX_DB * (p - 50) / 50; },
	gain(p) { return Math.pow(10, this.db(p) / 20); },
	/** for volumes saved before this scale (a gain of 0-3) */
	fromGain(g) {
		if (!(g > 0)) return 1;
		const d = 20 * Math.log10(g);
		return Math.max(1, Math.min(100, Math.round(d <= 0 ? 1 + (d - this.MIN_DB) / -this.MIN_DB * 49 : 50 + d / this.MAX_DB * 50)));
	},
	describe(p) {
		const d = Math.round(this.db(p)), t = Math.pow(2, d / 10);
		if (d === 0) return 'As OBS sends it';
		const words = t >= 1.8 ? 'About ' + (t < 2.5 ? 'twice' : t < 3.5 ? '3 times' : t < 4.5 ? '4 times' : '5 times') + ' as loud' : t > 1.05 ? 'A little louder'
			: t <= 0.12 ? 'Very quiet' : t <= 0.3 ? 'About a quarter as loud' : t <= 0.6 ? 'About half as loud' : 'A little quieter';
		return words + ' (' + (d > 0 ? '+' : '−') + Math.abs(d) + ' dB)' + (d > 0 ? '. A limiter keeps loud parts clean.' : '');
	},
};

const settings = Object.assign({
	deviceName: defaultName(),
	autoListen: true,
	/** the volume slider, 1-100% on the loudness scale; 50 = as OBS sends it */
	volumePct: null,
	saveAudio: true,
	keepDays: 30,
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
	/** how much the live mix is lowered while a saved clip plays, 0-100% */
	duckPct: 80,
}, store.get('settings', {}));
/* before 0.8.1 the volume was a gain of 0-3; carry it over onto the loudness scale */
if (settings.volumePct == null) settings.volumePct = 'volume' in settings ? Loudness.fromGain(settings.volume) : 50;
delete settings.volume;
function saveSettings() { store.set('settings', settings); }

let deviceId = store.get('deviceId', '');
if (!/^[A-Za-z0-9-]{16,64}$/.test(deviceId)) {
	deviceId = newId();
	store.set('deviceId', deviceId);
}

/* =============================================================== recordings and clips */

/**
 * One session as this browser knows it: the time it was connected, with its own copy of the mix,
 * and the clips marked in it: {file: the session's id, session: true, startedAt, durationMs, live,
 * clips[], obs[], savedOnPc}. Clip times are ms from the start of the session, so clips don't
 * depend on OBS recording; obs[] says which stretches OBS was recording ({file, fromMs, toMs,
 * fileStartMs}), for chapters and the editor's clip lists. Entries from before 0.9.0 (no
 * session flag) are one OBS recording each, in that file's times.
 */
let recordings = store.get('recordings', []);
/* a session left open by a closed page has ended */
for (const r of recordings) r.live = false;
function saveRecordings() {
	store.set('recordings', recordings);
	scheduleClipLists();
	render();
}
/** The OBS recordings an entry covers, in its own timeline. */
const obsSpans = (r) => (r.session ? r.obs || [] : [{ file: r.file, fromMs: 0, toMs: Infinity, fileStartMs: 0 }]);
const obsFiles = (r) => [...new Set(obsSpans(r).map((x) => x.file))];
/** An entry's clips as one OBS recording sees them: each clip's part inside it, in that file's times. */
function forObsFile(r, file) {
	const spans = obsSpans(r).filter((x) => x.file === file);
	const clips = [];
	for (const c of r.clips) {
		const parts = [];
		for (const x of spans) {
			const a = Math.max(c.startMs, x.fromMs), b = Math.min(c.endMs, x.toMs);
			if (b > a) parts.push([x.fileStartMs + a - x.fromMs, x.fileStartMs + b - x.fromMs]);
		}
		if (parts.length) clips.push(Object.assign({}, c, { startMs: Math.min(...parts.map((p) => p[0])), endMs: Math.max(...parts.map((p) => p[1])) }));
	}
	return { file, startedAt: r.startedAt, durationMs: r.durationMs, clips };
}
/** An entry with the OBS stretch still being recorded, for the session now. */
const withOpen = (r) => (session && session.open && r.file === session.id ? Object.assign({}, r, { obs: r.obs.concat([session.open]) }) : r);
/** A time in an entry: the clock time for a session, the file time for an old recording. */
function clockAt(r, ms) {
	if (!r || !r.session) return tc(ms);
	const d = new Date(r.startedAt + ms);
	return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
}
const fileBase = (f) => f.split(/[\\/]/).pop().replace(/\.[^.]*$/, '');
function entryName(r) {
	if (!r.session) return fileBase(r.file);
	const d = new Date(r.startedAt);
	return 'Session ' + d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + '.' + pad2(d.getMinutes());
}

/* ---------- the session: this browser's own clock and copy of the mix, whether or not OBS records ---------- */
const SESSION_BREAK_MS = 10 * 60000;
let session = null; // {id, t0, lastSeen, open}
function sessionEntry() { return session ? recordings.find((x) => x.file === session.id) || null : null; }
/** On connecting: carries on the session after a short break, or starts a new one. */
function beginSession() {
	if (session && Date.now() - session.lastSeen < SESSION_BREAK_MS && sessionEntry()) { session.lastSeen = Date.now(); return; }
	if (session) endSession();
	const d = new Date();
	const id = 'session-' + d.getFullYear() + pad2(d.getMonth() + 1) + pad2(d.getDate()) + '-' + pad2(d.getHours()) + pad2(d.getMinutes()) + pad2(d.getSeconds());
	session = { id, t0: performance.now(), lastSeen: Date.now(), open: null };
	for (const r of recordings) r.live = false;
	recordings.unshift({ file: id, session: true, startedAt: Date.now(), durationMs: 0, live: true, clips: [], obs: [], savedOnPc: null });
	saveRecordings();
}
function endSession() {
	const r = sessionEntry();
	if (session && session.open && r) r.obs.push(session.open);
	if (r) { r.live = false; r.durationMs = Math.round(sessionNowMs()); }
	audio.endSegment();
	session = null;
	saveRecordings();
}
function sessionNowMs() { return session ? performance.now() - session.t0 : 0; }
/** Session time of what's being heard now: behind the clock by the listening buffer. */
function heardMs() {
	const t = state.listening && audio.ctx ? audio.sessionMsNow() : null;
	return t === null ? sessionNowMs() : t;
}
/** Notes which stretches OBS is recording, from each status. */
function trackObs(s) {
	const r = sessionEntry();
	if (!r) return;
	session.lastSeen = Date.now();
	r.durationMs = Math.round(sessionNowMs());
	const t = heardMs();
	const heardFile = state.listening && audio.ctx ? audio.fileMsNow() : null;
	const fileMs = heardFile === null ? nowFileTimeMs() : heardFile;
	const o = session.open;
	if (s.recActive && !s.recPaused && s.file) {
		const expected = o ? o.fileStartMs + (t - o.fromMs) : fileMs;
		if (!o || o.file !== s.file || Math.abs(fileMs - expected) > 1000) {
			if (o) r.obs.push(o);
			session.open = { file: s.file, fromMs: Math.round(t), toMs: Math.round(t), fileStartMs: Math.round(fileMs) };
		} else o.toMs = Math.round(t);
	} else if (o) {
		r.obs.push(o);
		session.open = null;
		saveRecordings();
	}
}
/* the open stretch is kept with the entry every few seconds, so a closed page loses at most that */
setInterval(() => {
	const r = sessionEntry();
	if (!r) return;
	const saved = r.obs;
	r.obs = withOpen(r).obs;
	store.set('recordings', recordings);
	r.obs = saved;
}, 5000);
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
	clipName: null, // {name, number} of the running clip
	aacJobs: {}, // OBS recordings being saved as AAC: {file: {stage, percent}}
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
			feedback.buzz([300, 120, 300]);
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
		beginSession();
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
		recActive: !!rec.active, recPaused: !!rec.paused, fileTimeMs: rec.fileTimeMs || 0, file: rec.file || '', nextName: rec.nextName || '',
		container: rec.container || '', recBytes: rec.bytes || 0, diskFreeMb: rec.diskFreeMB ?? -1,
		streamActive: !!stream.active, streamDurationMs: stream.durationMs || 0, streamKbps: stream.kbps || 0,
		dropped: stream.dropped || 0, totalFrames: stream.total || 0, congestion: stream.congestion || 0,
		cpu: pc.cpu || 0, fps: pc.fps || 0, renderLag: pc.renderLag || 0, encodeLag: pc.encodeLag || 0,
		scene: m.scene || '',
	};
	state.statusAt = performance.now();
	const s = state.status;
	if (!session) beginSession();
	trackObs(s);
	if (prev && prev.recActive !== s.recActive) { scheduleClipLists(); idleName = null; }
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
	} else if (name === 'aac.progress') {
		const j = state.aacJobs[d.file];
		if (j && j.stage === 'converting') { j.percent = d.percent || 0; render(); }
	} else if (name === 'aac.ready') {
		const j = state.aacJobs[d.file];
		if (j && j.stage === 'converting') downloadAac(d.file, d);
	} else if (name === 'aac.failed') {
		const j = state.aacJobs[d.file];
		if (j && j.stage === 'converting') failAac(d.file, d.error || '');
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
			try { req = indexedDB.open('obsdirector', 2); } catch { return resolve(null); }
			req.onupgradeneeded = () => {
				const db = req.result;
				if (!db.objectStoreNames.contains('audio')) db.createObjectStore('audio', { keyPath: 'id' }).createIndex('file', 'file');
				/* an OBS recording's sound, saved as AAC: {file, blob, at} */
				if (!db.objectStoreNames.contains('aac')) db.createObjectStore('aac', { keyPath: 'file' });
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
	async aacGet(file) {
		const db = await this.open();
		if (!db) return null;
		return new Promise((res) => {
			const req = db.transaction('aac').objectStore('aac').get(file);
			req.onsuccess = () => res(req.result || null);
			req.onerror = () => res(null);
		});
	},
	async aacPut(rec) {
		const db = await this.open();
		if (!db) return false;
		return new Promise((res) => {
			const tx = db.transaction('aac', 'readwrite');
			tx.objectStore('aac').put(rec);
			tx.oncomplete = () => res(true);
			tx.onerror = tx.onabort = () => res(false);
		});
	},
	async aacDelete(file) {
		const db = await this.open();
		if (!db) return;
		db.transaction('aac', 'readwrite').objectStore('aac').delete(file);
	},
	/** {file: bytes} of every AAC file kept here */
	async aacSizes() {
		const db = await this.open();
		if (!db) return {};
		return new Promise((res) => {
			const out = {};
			const req = db.transaction('aac').objectStore('aac').openCursor();
			req.onsuccess = (e) => { const c = e.target.result; if (!c) return res(out); out[c.value.file] = c.value.blob.size; c.continue(); };
			req.onerror = () => res(out);
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
	clippedAt: -Infinity,
	recorder: null,
	segment: null,

	ensure() {
		if (this.ctx) return this.ctx;
		const C = window.AudioContext || window.webkitAudioContext;
		if (!C) return null;
		this.ctx = new C({ latencyHint: 'interactive' });
		this.gain = this.ctx.createGain();
		/* the live mix goes through its own gain, lowered while a saved clip plays */
		this.liveGain = this.ctx.createGain();
		this.liveGain.connect(this.gain);
		/* a limiter after the gain, as in the app: boosted peaks are held just under full scale */
		this.limiter = this.ctx.createDynamicsCompressor();
		this.limiter.threshold.value = -0.5;
		this.limiter.knee.value = 0;
		this.limiter.ratio.value = 20;
		this.limiter.attack.value = 0.001;
		this.limiter.release.value = 0.1;
		this.gain.connect(this.limiter);
		this.limiter.connect(this.ctx.destination);
		try { this.recDest = this.ctx.createMediaStreamDestination(); } catch { this.recDest = null; }
		this.setVolume(settings.volumePct);
		return this.ctx;
	},
	/** Called from a click: browsers only let a page make sound after one. */
	unlock() {
		if (!this.ensure()) return;
		this.ctx.resume();
		this.unlocked = true;
	},
	/** 1-100% on the loudness scale (see Loudness). */
	setVolume(percent) {
		settings.volumePct = Math.max(1, Math.min(100, Math.round(percent)));
		if (this.gain) this.gain.gain.value = Loudness.gain(settings.volumePct);
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
	/** Lowers the live mix while a clip plays (Settings, Lower the mix while a clip plays). */
	duck(on) {
		if (!this.liveGain) return;
		const g = this.liveGain.gain, now = this.ctx.currentTime;
		g.cancelScheduledValues(now);
		g.setValueAtTime(g.value, now);
		g.setTargetAtTime(on ? 1 - settings.duckPct / 100 : 1, now, 0.06);
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
		if (!this.batchFrames) { this.batchFileUs = fileUs; this.batchSessMs = sessionNowMs(); }
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
		const fileUs = this.batchFileUs, sessMs = this.batchSessMs;
		this.batchL = []; this.batchR = []; this.batchFrames = 0;
		const now = ctx.currentTime;
		/* about 120 ms of buffer: enough for Wi-Fi to stutter without clicks */
		if (this.nextTime < now + 0.02) this.nextTime = now + 0.12;
		if (this.nextTime > now + 0.5) return; /* too far behind: skip ahead rather than drift */
		const src = ctx.createBufferSource();
		src.buffer = buf;
		src.connect(this.liveGain);
		if (this.recDest) src.connect(this.recDest);
		src.start(this.nextTime);
		this.timeline.push([this.nextTime, fileUs, sessMs]);
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

	/** The session time of what's coming out of the speakers now. */
	sessionMsNow() {
		const now = this.ctx.currentTime;
		let best = null;
		for (const e of this.timeline) if (e[0] <= now) best = e;
		return best ? best[2] + (now - best[0]) * 1000 : null;
	},

	/* ----- saving the session audio in 30 s pieces, so clips can be played back, whether or not OBS records ----- */
	maybeSegment() {
		const want = settings.saveAudio && state.listening && session && this.recDest && window.MediaRecorder;
		if (!want || (this.segment && this.segment.file !== session.id)) this.endSegment();
		if (!want) return;
		if (this.segment && performance.now() - this.segment.started > 30000) this.endSegment();
		if (this.segment) return;
		const sessMs = this.sessionMsNow();
		if (sessMs === null) return;
		const mime = ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4'].find((t) => MediaRecorder.isTypeSupported(t)) || '';
		let rec;
		try { rec = new MediaRecorder(this.recDest.stream, mime ? { mimeType: mime, audioBitsPerSecond: 96000 } : undefined); } catch { return; }
		const seg = { id: newId(), file: session.id, startMs: Math.round(sessMs), ctxStart: this.ctx.currentTime, started: performance.now(), mime: rec.mimeType, chunks: [] };
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
	/* the piece being written holds part of it: finish it now (the next starts with the next sound) */
	const seg = audio.segment;
	if (seg && seg.file === file && toMs > seg.startMs) {
		const stopped = new Promise((res) => seg.rec.addEventListener('stop', () => setTimeout(res, 50), { once: true }));
		audio.endSegment();
		await Promise.race([stopped, new Promise((res) => setTimeout(res, 1500))]);
	}
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
		if (!got) return toast('No audio saved in this browser for that part. Listen in this browser to keep it.');
		const ctx = audio.ctx, t0 = ctx.currentTime + 0.05;
		for (const p of got.pieces) {
			const src = ctx.createBufferSource();
			src.buffer = p.buffer;
			src.connect(audio.gain);
			src.start(t0 + (p.atMs - fromMs) / 1000, p.offsetS, p.durS);
			this.sources.push(src);
		}
		this.key = key;
		audio.duck(true);
		this.startedAt = t0;
		this.fromMs = fromMs;
		this.toMs = toMs;
		const end = t0 + (toMs - fromMs) / 1000;
		this.timer = setInterval(() => { if (!audio.ctx || audio.ctx.currentTime >= end) this.stop(); render(); }, 250);
		render();
	},
	/** An OBS recording's AAC file saved in this browser. Too long to decode at once, so it streams from an <audio>. */
	async playAac(file) {
		this.stop();
		audio.unlock();
		const rec = await idb.aacGet(file);
		if (!rec) return toast('That AAC file isn’t saved in this browser.');
		const url = URL.createObjectURL(rec.blob);
		const el = new Audio(url);
		this.el = el; this.url = url;
		this.key = 'aac:' + file;
		this.fromMs = 0; this.toMs = 1;
		el.addEventListener('loadedmetadata', () => { this.toMs = el.duration * 1000 || 1; render(); });
		el.addEventListener('ended', () => this.stop());
		try { await el.play(); } catch { this.stop(); return toast('This browser couldn’t play the AAC file.'); }
		audio.duck(true);
		this.timer = setInterval(render, 500);
		render();
	},
	posMs() {
		if (this.el) return this.el.currentTime * 1000;
		return this.key && audio.ctx ? this.fromMs + Math.max(0, audio.ctx.currentTime - this.startedAt) * 1000 : 0;
	},
	stop() {
		if (this.el) { try { this.el.pause(); } catch { /* fine */ } URL.revokeObjectURL(this.url); this.el = null; this.url = null; }
		for (const s of this.sources) { try { s.stop(); } catch { /* done */ } }
		this.sources = [];
		clearInterval(this.timer);
		if (this.key) { this.key = null; audio.duck(false); render(); }
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
	download(entryName(r) + ' clip ' + (r.clips.indexOf(c) + 1) + '.wav', new Blob([data], { type: 'audio/wav' }));
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

/* A clip is a recording of the mix in this browser, timed on the session: it starts at your click
 * and ends at your next one, whether or not OBS records, and starting or stopping the recording
 * never ends it. Trim it afterwards to take in more: the sound around it is kept. Each clip is
 * named after the OBS recording ("2026-11-28 19-02-11 clip 2"), or the name OBS would give one. */
const canClip = () => state.link === 'connected' && !!session;
let idleName = null; // {name, session}: the name OBS would have given, kept for this session's clips while OBS isn't recording
function localName() {
	const d = new Date();
	return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + '-' + pad2(d.getMinutes()) + '-' + pad2(d.getSeconds());
}
function nextClip() {
	const s = state.status || {};
	let name;
	if (s.recActive && s.file) name = s.file.replace(/\.[^.]*$/, '');
	else {
		if (!idleName || idleName.session !== (session && session.id)) idleName = { name: s.nextName || localName(), session: session && session.id };
		name = idleName.name;
	}
	let max = 0;
	for (const r of recordings) for (const c of r.clips) if (c.name === name) max = Math.max(max, c.number || 0);
	return { name, number: max + 1 };
}
const nextClipName = () => { const n = state.clipName || nextClip(); return n.name + ' clip ' + n.number; };
/** "2026-11-28 19-02-11 clip 2 – Choir intro"; null for a clip from before names */
const fullName = (c) => (c.name && c.number ? c.name + ' clip ' + c.number + (c.note ? ' – ' + c.note : '') : null);

function startClip() {
	const s = state.status;
	if (!canClip() || state.clipStart !== null) return;
	if (state.noteFor) skipNote(); /* the clip before still waiting for its note: its end marker goes in now */
	state.clipStart = Math.max(0, heardMs());
	state.clipName = nextClip();
	state.lastSaved = '';
	/* a chapter only goes in while OBS records */
	if (settings.chapters && s && s.recActive) command('chapter.add', { name: 'Clip ' + state.clipName.number });
	render();
}
function runningClipMs() { return state.clipStart === null ? 0 : heardMs() - state.clipStart; }
let endMarker = null, endMarkerTimer = 0; // a clip's end marker waits for its note (a minute at most)
function endClip(why = '', auto = false) {
	const start = state.clipStart;
	const r = sessionEntry();
	if (start === null || !r) return;
	const end = Math.max(start + 1000, heardMs());
	const n = state.clipName || nextClip();
	state.clipStart = null;
	state.clipName = null;
	const s = state.status || {};
	const c = { id: newId(), startMs: Math.round(start), endMs: Math.round(end), note: '', createdAt: Date.now(), name: n.name, number: n.number };
	r.clips.push(c);
	r.clips.sort((a, b) => a.startMs - b.startMs);
	state.lastSaved = fullName(c) + ' saved, ' + len(end - start) + why;
	endMarker = settings.chapters && s.recActive ? n.number : null;
	clearTimeout(endMarkerTimer);
	if (!auto) {
		state.noteFor = { file: r.file, id: c.id };
		endMarkerTimer = setTimeout(() => { if (state.noteFor && state.noteFor.id === c.id) { skipNote(); render(); } }, 60000);
	} else writeEndMarker('');
	saveRecordings();
}
/** The clip's end marker in the OBS recording ("Clip 2 – Choir intro"), if OBS is still recording. */
function writeEndMarker(note) {
	const n = endMarker;
	endMarker = null;
	clearTimeout(endMarkerTimer);
	if (n === null || !(state.status && state.status.recActive)) return;
	command('chapter.add', { name: (note ? 'Clip ' + n + ' – ' + note : 'Clip ' + n + ' end').slice(0, 120) });
}
function saveNote(c, text) {
	c.note = text.trim();
	state.noteFor = null;
	writeEndMarker(c.note);
	state.lastSaved = fullName(c) + ' saved';
	saveRecordings();
}
function skipNote() { state.noteFor = null; writeEndMarker(''); }
function endClipIfRunning(why) { if (state.clipStart !== null) endClip(why, true); }
function toggleClip() { if (state.clipStart === null) startClip(); else endClip(); }
function cancelClip() { state.clipStart = null; state.clipName = null; state.lastSaved = 'Clip cancelled. Nothing saved.'; render(); }

/* =============================================================== OBS recordings, and their sound as AAC */

/** The OBS recordings this browser was connected for, newest first: [{file, startedAt, durationMs, live}] */
function obsRecordings() {
	const found = new Map();
	const add = (o) => {
		const had = found.get(o.file);
		found.set(o.file, had ? Object.assign(had, { startedAt: Math.min(had.startedAt, o.startedAt), durationMs: Math.max(had.durationMs, o.durationMs) }) : o);
	};
	for (const r of recordings.map(withOpen)) {
		if (!r.session) { add({ file: r.file, startedAt: r.startedAt, durationMs: r.durationMs || 0 }); continue; }
		for (const x of r.obs) add({ file: x.file, startedAt: r.startedAt + x.fromMs - x.fileStartMs, durationMs: x.fileStartMs + x.toMs - x.fromMs });
	}
	const s = state.status || {};
	const connected = state.link === 'connected';
	if (connected && s.recActive && s.file) add({ file: s.file, startedAt: Date.now() - s.fileTimeMs, durationMs: s.fileTimeMs });
	return [...found.values()].map((o) => Object.assign(o, { live: connected && !!s.recActive && o.file === s.file })).sort((a, b) => b.startedAt - a.startedAt);
}
/** The clips (or their parts) inside one OBS recording, from every session that overlaps it, in its times. */
function obsClips(file) {
	return { file, clips: recordings.map(withOpen).flatMap((r) => forObsFile(r, file).clips).sort((a, b) => a.startMs - b.startMs) };
}

let aacSizes = {};
idb.aacSizes().then((x) => { aacSizes = x; render(); });
const aacCopies = {};
/** Asks the PC to take a finished recording's sound out as AAC (.m4a), then copies it into this browser. */
async function saveAac(file) {
	if (state.aacJobs[file]) return;
	state.aacJobs[file] = { stage: 'converting', percent: 0 };
	render();
	const res = await command('recording.aac', { file });
	if (!res.ok) return failAac(file, res.error || 'error');
	if (res.data && res.data.state === 'ready') downloadAac(file, res.data);
}
function cancelAac(file) {
	const j = state.aacJobs[file];
	if (!j) return;
	if (aacCopies[file]) aacCopies[file].abort();
	if (j.stage === 'converting') command('recording.aac.cancel', { file });
	delete state.aacJobs[file];
	render();
}
function failAac(file, error) {
	delete state.aacJobs[file];
	render();
	if (error === 'cancelled') return;
	toast({
		still_recording: 'OBS is still making that recording. Save it once it stops.',
		aac_unavailable: 'The PC can’t make AAC files: OBS’s FFmpeg isn’t the version the plugin expects. Update OBS and the plugin.',
		unknown_recording: 'The PC no longer knows that recording (OBS was restarted since).',
		recording_missing: 'That recording isn’t on the PC any more.',
		no_audio: 'That recording has no sound to save.',
		busy: 'The PC is making another AAC file. Try again once it’s done.',
		unknown_command: 'Update the OBS Director plugin on the PC to save AAC files.',
		not_connected: 'Not connected to the PC.',
		timeout: 'Not connected to the PC.',
	}[error] || 'The PC couldn’t make the AAC file (' + error + ').');
}
async function downloadAac(file, d) {
	state.aacJobs[file] = { stage: 'copying', percent: 0 };
	render();
	const ctl = new AbortController();
	aacCopies[file] = ctl;
	try {
		const r = await fetch('/aac/' + encodeURIComponent(d.token), { signal: ctl.signal, cache: 'no-store' });
		if (!r.ok || !r.body) throw new Error('status ' + r.status);
		const total = Number(r.headers.get('content-length')) || d.bytes || 0;
		const reader = r.body.getReader();
		const parts = [];
		let got = 0, shown = -1;
		for (;;) {
			const { done, value } = await reader.read();
			if (done) break;
			parts.push(value);
			got += value.length;
			const pct = total ? Math.floor(got * 100 / total) : 0;
			if (pct !== shown) { shown = pct; if (state.aacJobs[file]) state.aacJobs[file].percent = pct; render(); }
		}
		if (total && got !== total) throw new Error('incomplete');
		const blob = new Blob(parts, { type: 'audio/mp4' });
		if (!(await idb.aacPut({ file, blob, at: Date.now() }))) throw new Error('storage');
		aacSizes[file] = blob.size;
		delete state.aacJobs[file];
		toast(fileBase(file) + '.m4a saved in this browser');
	} catch (e) {
		const cancelled = !state.aacJobs[file];
		delete state.aacJobs[file];
		if (!cancelled) toast(String(e && e.message) === 'storage' ? 'This browser has no room for the AAC file.' : 'Copying the AAC file from the PC stopped. Try again.');
	}
	delete aacCopies[file];
	render();
}

/* the clip lists, saved next to the recording on the PC as .edl and .csv */
const sentClipLists = new Map();
let clipListTimer = 0;
function scheduleClipLists() {
	clearTimeout(clipListTimer);
	clipListTimer = setTimeout(writeClipLists, 1500);
}
async function writeClipLists() {
	if (!settings.pcClipLists || state.link !== 'connected') return;
	/* one list per OBS recording, with the clips of every entry that overlaps it, in its times */
	const all = recordings.map(withOpen);
	const files = [...new Set(all.filter((r) => r.clips.length).flatMap(obsFiles))];
	for (const file of files) {
		const parts = all.map((r) => [r, forObsFile(r, file)]).filter(([, m]) => m.clips.length);
		if (!parts.length) continue;
		const merged = { file, clips: parts.flatMap(([, m]) => m.clips).sort((a, b) => a.startMs - b.startMs) };
		const sig = JSON.stringify(merged.clips.map((c) => [c.startMs, c.endMs, c.note]));
		if (sentClipLists.get(file) === sig) continue;
		sentClipLists.set(file, sig);
		const res = await command('clips.write', { file, edl: Exporter.edl(merged), csv: Exporter.csv(merged) });
		if (res.ok) {
			let changed = false;
			for (const [e] of parts) { const r = recordings.find((x) => x.file === e.file); if (r && r.savedOnPc !== state.pc) { r.savedOnPc = state.pc; changed = true; } }
			if (changed) { store.set('recordings', recordings); render(); }
		} else if (res.error === 'not_connected' || res.error === 'timeout') {
			sentClipLists.delete(file);
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
	feedback.buzz(a.severe ? [400, 150, 400, 150, 400] : [300, 120, 300]);
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
				/* the same loudness scale as the app: 25, 50, 75 and 100% play at −12, −6, −3 and 0 dB */
				g.gain.value = Math.pow(10, [-12, -6, -3, 0][Math.max(0, Math.min(3, Math.round(vol / 25) - 1))] / 20);
				src.buffer = this.buffer; src.connect(g); g.connect(audio.ctx.destination); src.start();
			}
		}
		if (settings.haptics > 0) this.buzz([[18, 28, 40][settings.haptics - 1]], true);
	},
	/* Browsers can't set vibration strength, only length, so stronger settings buzz longer. A tap
	 * is already sized for its level; patterns are scaled. */
	buzz(pattern, sized) {
		if (!settings.haptics || !navigator.vibrate) return;
		const scale = sized ? 1 : [0.8, 1.2, 1.6][settings.haptics - 1];
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
	return [state.link, view.name, view.recording, view.open, view.mode, view.streamTab, view.exporting, !!view.help, JSON.stringify(view.sheet), view.sheetMode, !!undoing, s.recActive, s.recPaused, s.streamActive,
		state.clipStart !== null, state.listening, state.video, state.desk.length, state.desk.map((m) => m.state).join(''), state.deskUnread, state.deskAlert, state.alerts.map((a) => a.id).join(), !!state.lastSaved, settings.allowStreamStop,
		player.key, recordings.length].join('|');
}

/** Rebuild the screen. Whatever is being typed (text, cursor, focus) carries over. */
function render() {
	/* lets the stylesheet hide the header on a phone off Monitor while all is well */
	document.body.dataset.view = view.name;
	document.body.dataset.link = state.link;
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
				h('span.swatch', { style: { background: `var(--${sw}-ink)` } }), id === 'stream' ? [h('span.long', label), h('span.short', 'Stream')] : label,
				id === 'recordings' && state.link === 'connected' && state.status && state.status.recActive ? h('span.badge', h('span.dot', { style: { background: 'var(--rec)' } }), h('span.sr', ', recording now')) : null,
				id === 'desk' && state.deskUnread ? h('span.count' + (state.deskAlert === 2 ? '.severe' : state.deskAlert === 1 ? '.alert' : ''),
					{ 'aria-label': state.deskUnread + ' unread' + (state.deskAlert === 2 ? ', including a severe alert' : state.deskAlert === 1 ? ', including an alert' : '') },
					state.deskUnread > 99 ? '99+' : state.deskUnread) : null)),
			h('div.nav-foot', 'Plugin ' + (state.plugin || '–') + ' · page ' + VERSION)),
		view.name === 'desk' ? h('main.main.chat-main', deskPage()) : h('main.main', h('div.page', page())));
}

/**
 * The OBS recording, small: its state, time and name (or the name OBS will give the next one),
 * and round buttons. Pause and Stop act only when held, so a stray click can't touch the take.
 */
function obsRow() {
	const s = state.status || {};
	const pill = !s.recActive ? h('span.pill.off', 'STOPPED') : s.recPaused ? h('span.pill.paused', h('span.dot'), 'PAUSED') : h('span.pill.rec', h('span.dot'), 'REC');
	const off = state.link !== 'connected';
	return h('section.section.obs-row', { 'aria-label': 'OBS recording' },
		h('div.grow',
			h('div.obs-top', pill, bind(h('span.obs-time' + (s.recActive ? '' : '.idle')), () => tc(nowFileTimeMs()))),
			h('div.small.obs-file', s.recActive ? s.file + ' · hold ' + (s.recPaused ? '■ to stop' : 'Ⅱ or ■') : 'OBS recording · next: ' + (s.nextName || '–'))),
		s.recActive
			? [s.recPaused
				? h('button.round.obs-btn', { type: 'button', 'aria-label': 'Resume recording', disabled: off, onclick: () => act('record.resume') }, icon('play', 20))
				: holdIconButton('pause', 'Hold to pause recording', settings.holdMs, () => act('record.pause'), 'var(--amber-ink)', off),
				holdIconButton('stop', 'Hold to stop recording', settings.holdMs, () => { stopRequestedHere = true; act('record.stop'); }, 'var(--rec)', off)]
			: h('button.round.obs-btn.start', { type: 'button', 'aria-label': 'Start recording', disabled: off, onclick: () => act('record.start') }, icon('rec', 20)));
}

/** A round button that acts only when held for [ms]: it fills with [fill] as you hold. */
function holdIconButton(ic, label, ms, done, fill, disabled) {
	const btn = h('button.round.obs-btn.hold', { type: 'button', 'aria-label': label, disabled: !!disabled }, icon(ic, 20));
	let timer = 0, started = 0, raf = 0;
	const reset = () => { clearTimeout(timer); cancelAnimationFrame(raf); btn.style.background = ''; };
	const go = (e) => {
		if (e.type === 'keydown' && e.key !== 'Enter' && e.key !== ' ') return;
		if (e.repeat || btn.disabled) return;
		e.preventDefault();
		if (e.pointerId !== undefined) { try { btn.setPointerCapture(e.pointerId); } catch { /* fine */ } }
		started = performance.now();
		const step = () => { const p = Math.min(1, (performance.now() - started) / ms); btn.style.background = `conic-gradient(${fill} ${p * 360}deg, var(--surface-2) 0)`; raf = requestAnimationFrame(step); };
		step();
		timer = setTimeout(() => { reset(); feedback.tap(); done(); }, ms);
	};
	btn.addEventListener('pointerdown', go);
	btn.addEventListener('keydown', go);
	for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture', 'keyup', 'blur']) btn.addEventListener(ev, reset);
	btn.addEventListener('contextmenu', (e) => e.preventDefault());
	btn.setAttribute('aria-description', 'Press and hold for ' + (ms / 1000) + ' seconds');
	return btn;
}

/** Clips: the Clip button, one line under it, and the latest clips with Play. */
function clippingBlock() {
	const running = state.clipStart !== null;
	const r = sessionEntry();
	const clipBtn = running
		? h('button.clip.running', { type: 'button', onclick: toggleClip },
			h('span.clip-left', h('span.big', 'End clip'), h('span.hint', 'clip length')),
			bind(h('span.len'), () => clipLength(runningClipMs())))
		: h('button.clip', { type: 'button', onclick: toggleClip, disabled: !canClip() },
			h('span.big', 'Start clip'),
			h('span.hint', canClip() ? nextClipName() + (settings.spaceForClip ? ' · or press Space' : '') : 'Waiting for OBS'));
	if (running) live.push([clipBtn, null, () => clipBtn.setAttribute('aria-label', 'End clip, ' + clipLength(runningClipMs()) + ' long')]);
	const all = recordings.flatMap((x) => x.clips.map((c) => [x, c])).sort((a, b) => (b[1].createdAt || b[0].startedAt) - (a[1].createdAt || a[0].startedAt)).slice(0, 2);
	const playingKey = player.key;
	return [clipBtn, statusLine(r, running),
		all.length ? h('div.latest',
			h('div.row', h('h3.grow.latest-title', 'Latest clips'), h('button.line-action', { type: 'button', onclick: () => { view.name = 'recordings'; view.recTab = 'clips'; render(); } }, 'See all')),
			all.map(([x, c]) => {
				const key = clipKey(x, c), playing = playingKey === key;
				return h('div.latest-row' + (playing ? '.playing' : ''),
					h('button.clip-play', { type: 'button', 'aria-label': (playing ? 'Stop ' : 'Play ') + clipName(c, x), onclick: () => (playing ? player.stop() : player.play(key, x.file, c.startMs, c.endMs)) }, icon(playing ? 'stop' : 'play', 20)),
					h('div.grow', h('div.latest-name', clipName(c, x)),
						h('div.small', playing && settings.duckPct ? 'Playing · the live mix is lowered ' + settings.duckPct + '%' : clipLength(c.endMs - c.startMs) + ' · ' + clockAt(x, c.startMs))));
			})) : null];
}

/**
 * One line under the Clip button/**
 * One line under the Clip button, always the same height: what just happened and the one thing
 * to do about it (cancel, add a note, see all). Nothing appears or disappears around it, so the
 * buttons below never move.
 */
function statusLine(r, running) {
	const seeAll = () => { view.name = 'recordings'; view.recTab = 'clips'; render(); };
	const link = (label, onclick) => h('button.line-action', { type: 'button', onclick }, label);
	if (running)
		return h('div.status-line', h('span.grow', bind(h('span'), () => 'Recording ' + nextClipName() + ' from ' + clockAt(r, state.clipStart))), link('Cancel', cancelClip));
	if (state.noteFor) {
		const { file, id } = state.noteFor;
		const rec = recordings.find((x) => x.file === file), c = rec && rec.clips.find((x) => x.id === id);
		if (c) {
			const input = h('input', { type: 'text', maxlength: 140, placeholder: 'Note for this clip (optional)', 'aria-label': 'Note for this clip' });
			const save = () => { saveNote(c, input.value); render(); };
			input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); if (e.key === 'Escape') { skipNote(); render(); } });
			return h('div.status-line', h('div.grow', input), link('Save', save), link('Skip', () => { skipNote(); render(); }));
		}
		state.noteFor = null;
	}
	const text = state.lastSaved || 'Starts at your click. Trim it later to take in more.';
	return h('div.status-line', h('span.grow', { role: 'status' }, text), state.lastSaved && r && r.clips.length ? link('See all', seeAll) : null);
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
	const pct = settings.volumePct;
	const vol = h('input', { type: 'range', min: 1, max: 100, step: 1, value: pct, 'aria-label': 'Volume', 'aria-valuetext': pct + ' percent. ' + Loudness.describe(pct) });
	if (pct > Loudness.AS_SENT) vol.classList.add('boost');
	const volLabel = h('span.small.vol-value' + (pct > Loudness.AS_SENT ? '.boost' : ''), pct + '%');
	/* always there, so dragging changes the words, not the layout */
	const hint = h('div.small.vol-hint' + (pct > Loudness.AS_SENT ? '.boost' : ''), Loudness.describe(pct));
	vol.addEventListener('input', () => {
		const p = Number(vol.value);
		audio.setVolume(p);
		volLabel.textContent = p + '%';
		vol.setAttribute('aria-valuetext', p + ' percent. ' + Loudness.describe(p));
		vol.classList.toggle('boost', p > Loudness.AS_SENT);
		hint.classList.toggle('boost', p > Loudness.AS_SENT);
		volLabel.classList.toggle('boost', p > Loudness.AS_SENT);
		hint.textContent = Loudness.describe(p);
	});
	vol.addEventListener('change', saveSettings);
	/* a double click puts it back to the mix as OBS sends it */
	vol.addEventListener('dblclick', () => { audio.setVolume(Loudness.AS_SENT); saveSettings(); feedback.tap(); render(); });
	vol.setAttribute('aria-description', 'Double-click for as set in OBS');
	const scale = h('div.vol-scale', { 'aria-hidden': 'true' }, Loudness.marks.map((m, k) => h('span' + (k === 2 ? '.mid' : ''), m)));
	return h('section.section', { 'aria-label': 'Monitoring' },
		h('div.row', h('span.tint-tile', { style: { background: 'var(--teal-tint)', color: 'var(--teal-ink)' } }, icon('head', 18)),
			h('div.grow', h('h2.card-title', 'Listen in this browser'),
				bind(h('div.small'), () => "This computer's sound output · about " + (audio.delayMs() + state.latencyMs || 120) + ' ms behind')),
			sw),
		bind(h('div.small'), () => meters.label()),
		meterBlock(false),
		h('div.vol-head', h('span.small', 'Volume · double-click for as set in OBS'), volLabel),
		vol, scale,
		hint,
		bind(h('div.small'), () => networkLine()),
		h('hr.rule'),
		clippingBlock());
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
		obsRow(),
		h('div.cols', h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px' } }, listenSection()),
			h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px' } }, chipsRow(), pictureSection()))];
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
const clipName = (c, r) => fullName(c) || c.note || 'Clip at ' + clockAt(r, c.startMs);
const clipMeta = (c, r) => clipLength(c.endMs - c.startMs) + ' long · at ' + clockAt(r, c.startMs);
function recMeta(r) {
	if (!r.session) return (r.live ? 'Recording now' : durationText(r.durationMs)) + ' · ' + clipsText(r.clips.length);
	const n = obsFiles(withOpen(r)).length;
	return (r.live ? 'Now' : durationText(r.durationMs)) + ' · ' + clipsText(r.clips.length) + ' · ' + (n === 0 ? 'Not recorded in OBS' : n === 1 ? 'In an OBS recording' : 'In ' + n + ' OBS recordings');
}

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
	offerUndo(r.session ? 'Session deleted' : 'Recording deleted', () => { recordings.splice(Math.min(at, recordings.length), 0, r); saveRecordings(); }, () => idb.remove(r.file));
}

function dayLabel(at) {
	const d = new Date(at), t = new Date();
	const days = Math.round((new Date(t.toDateString()) - new Date(d.toDateString())) / 86400000);
	return days === 0 ? 'Today' : days === 1 ? 'Yesterday' : d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'short' });
}
const mb = (n) => (n >= 10 * 1048576 ? Math.round(n / 1048576) + ' MB' : (n / 1048576).toFixed(1) + ' MB');

/**
 * Recordings, in two tabs, kept apart because they are:
 * - Clips: recordings of the mix made in this browser, whatever OBS was doing, newest first, by day.
 * - OBS recordings: what OBS recorded on the PC while this browser was connected. They stay on the
 *   PC; Save as AAC has the PC take one's sound out and copies it here to play.
 */
function recordingsPage() {
	if (view.name === 'recording') view.name = 'recordings';
	const tab = view.recTab || 'clips';
	const playingKey = player.key && player.key.includes(':') ? player.key : null;
	const s = state.status || {};
	const clips = recordings.flatMap((r) => {
		const full = withOpen(r);
		const inObs = new Map();
		for (const f of obsFiles(full)) for (const c of forObsFile(full, f).clips) inObs.set(c.id, f);
		return r.clips.map((c) => [r, c, inObs.get(c.id)]);
	}).sort((a, b) => (b[1].createdAt || b[0].startedAt) - (a[1].createdAt || a[0].startedAt));
	const obs = obsRecordings();

	const tabs = h('div.rec-tabs', { role: 'tablist', 'aria-label': 'Show' },
		[['clips', 'Clips · ' + clips.length], ['obs', 'OBS recordings · ' + obs.length]].map(([id, label]) =>
			h('button' + (tab === id ? '.on' : ''), { type: 'button', role: 'tab', 'aria-selected': String(tab === id), onclick: () => { view.recTab = id; render(); } }, label)));

	const out = [h('div', h('h1.title', 'Recordings'), h('div.sub', 'Clips are kept in this browser; OBS recordings on the PC')), tabs];
	if (tab === 'clips') {
		if (!clips.length) out.push(h('section.section', h('div.small', 'No clips yet. Click Start clip on Monitor: it records the mix in this browser, whether or not OBS is recording.')));
		const days = new Map();
		for (const x of clips) { const d = dayLabel(x[1].createdAt || x[0].startedAt); if (!days.has(d)) days.set(d, []); days.get(d).push(x); }
		for (const [day, list] of days) {
			out.push(h('div.group-label', { style: { color: 'var(--muted)' } }, day));
			out.push(h('section.rec-card', list.map(([r, c, file]) => {
				const key = clipKey(r, c), playing = playingKey === key;
				const tag = file && s.recActive && file === s.file ? ['OBS recording now', 'rec'] : file ? ['In the OBS recording', 'ok'] : ['Not recorded in OBS', 'neutral'];
				const open = () => { view.sheet = { clip: key }; view.sheetMode = 'clip'; render(); };
				return h('div.clip-row' + (playing ? '.playing' : ''),
					h('button.clip-play', { type: 'button', 'aria-label': (playing ? 'Stop ' : 'Play ') + clipName(c, r), onclick: () => (playing ? player.stop() : player.play(key, r.file, c.startMs, c.endMs)) }, icon(playing ? 'stop' : 'play', 22)),
					h('button.clip-open', { type: 'button', onclick: open },
						h('span.clip-note', clipName(c, r)), h('span.small', clipLength(c.endMs - c.startMs) + ' · ' + clockAt(r, c.startMs)),
						h('span.tag', { style: { background: `var(--${tag[1]}-tint)`, color: `var(--${tag[1]}-ink)` } }, tag[0])),
					h('button.round.plain', { type: 'button', 'aria-label': 'More for ' + clipName(c, r), onclick: open }, icon('more', 22)));
			})));
		}
		if (clips.length) out.push(h('button.btn.danger', { type: 'button', onclick: () => {
			if (!confirm('Delete all clips in this browser? Their notes and saved mix audio go too. The OBS recordings on the PC, and AAC files saved here, aren’t touched. Clipping carries on.')) return;
			player.stop();
			const live = recordings.filter((r) => r.live);
			for (const r of recordings) if (!r.live) idb.remove(r.file);
			recordings = live.map((r) => Object.assign(r, { clips: [] }));
			saveRecordings();
			toast('All clips deleted');
		} }, icon('trash', 18), 'Delete all clips'));
	} else {
		out.push(h('div.small', 'The recordings OBS made while this browser was connected. They stay on the PC; save one’s sound as AAC to play it here.'));
		if (!obs.length) out.push(h('section.section', h('div.small', 'None yet. Recordings OBS makes while this browser is connected show up here.')));
		const connected = state.link === 'connected';
		for (const o of obs) {
			const job = state.aacJobs[o.file], has = aacSizes[o.file] !== undefined, key = 'aac:' + o.file, playing = player.key === key;
			const n = obsClips(o.file).clips.length;
			let aac;
			if (job) {
				const total = job.stage === 'converting' ? job.percent * 0.2 : 20 + job.percent * 0.8;
				aac = h('div.aac-progress',
					h('div.row', h('span.grow', job.stage === 'converting' ? 'Getting it ready on the PC…' : 'Copying to this browser…'), h('span', job.percent + '%')),
					h('div.aac-track', { role: 'progressbar', 'aria-label': 'Saving as AAC', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(Math.round(total)) },
						h('div.aac-fill', { style: { width: total + '%' } })),
					h('div.row', h('span.small.grow', 'Keeps going while this page is open'), h('button.line-action', { type: 'button', onclick: () => cancelAac(o.file) }, 'Cancel')));
			} else if (has) {
				aac = h('div.aac-saved' + (playing ? '.playing' : ''),
					h('button.clip-play', { type: 'button', 'aria-label': (playing ? 'Stop ' : 'Play ') + fileBase(o.file), onclick: () => (playing ? player.stop() : player.playAac(o.file)) }, icon(playing ? 'stop' : 'play', 22)),
					h('div.grow', h('div', 'AAC in this browser'), h('div.small', mb(aacSizes[o.file]) + ' · download or delete from ⋯')));
			} else if (o.live) {
				aac = h('div.small', 'Save it as AAC once OBS stops recording.');
			} else {
				aac = [h('button.btn.primary', { type: 'button', disabled: !connected, onclick: () => saveAac(o.file) }, icon('down', 18), 'Save as AAC in this browser'),
					h('div.small', connected ? 'The PC takes its sound out as AAC (.m4a) and sends it over.' : 'Connect to the PC to save it.')];
			}
			out.push(h('section.rec-card.obs-card' + (o.live ? '.live' : ''),
				h('div.row',
					h('div.grow',
						h('div.small', o.live ? h('span.rec-pill', h('span.dot', { style: { background: 'var(--rec)' } }), 'REC') : null, ' ' + recordingTitle(o) + ' · ' + durationText(o.durationMs) + (o.live ? ' so far' : '')),
						h('h2.rec-title', o.file), h('div.small', clipsText(n))),
					h('button.round.plain', { type: 'button', 'aria-label': 'More for ' + o.file, onclick: () => { view.sheet = { obs: o.file }; render(); } }, icon('more', 22))),
				aac));
		}
	}

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
	const isAac = key.startsWith('aac:');
	const r = isAac ? null : recordings.find((x) => key.startsWith(x.file + ':'));
	const c = r && r.clips.find((x) => clipKey(r, x) === key);
	const fill = h('div.mini-fill');
	const span = () => Math.max(1, player.toMs - player.fromMs);
	const done = () => Math.min(span(), Math.max(0, player.posMs() - player.fromMs));
	const time = bind(h('span.small'), () => len(done()) + ' of ' + len(span()) + (settings.duckPct ? ' · live mix lowered ' + settings.duckPct + '%' : ''));
	live.push([fill, null, () => { fill.style.width = (done() / span() * 100).toFixed(1) + '%'; }]);
	fill.style.width = (done() / span() * 100).toFixed(1) + '%';
	return h('div.mini-player',
		h('div.mini-row', h('span.grow', h('div.mini-title', isAac ? fileBase(key.slice(4)) + '.m4a' : c ? clipName(c, r) : 'Playing'), time),
			h('button.mini-stop', { type: 'button', 'aria-label': 'Stop', onclick: () => player.stop() }, icon('stop', 18))),
		h('div.mini-track', fill));
}

/* ---------- trim ----------
 * The way phones trim video: the sound of the clip with 15 s either side, the kept part lit, and a
 * handle at each end. Drag a handle to move that end, or the lit part to move the whole clip;
 * click elsewhere to bring the nearer end there. The handles are sliders too: arrow keys move
 * them 1 s, Page Up and Down 5 s. Letting go settles an end in the nearest pause and plays a
 * moment at that edge, so you hear the cut. Nothing changes until Save. The same rules as the
 * app (audio/Trim.kt). */
const Trim = {
	MIN: 1000, PAD: 15000, SNAP: 500, STEP: 100,
	window(s, e, dur) { const from = Math.max(0, s - this.PAD); let to = e + this.PAD; if (dur > 0) to = Math.min(to, dur); return [from, Math.max(to, from + this.MIN)]; },
	moveStart(s, e, to) { return Math.max(0, Math.min(e - this.MIN, to)); },
	moveEnd(s, e, to, dur) { let v = Math.max(s + this.MIN, to); return dur > 0 ? Math.min(v, dur) : v; },
	moveBoth(s, e, d, dur) { const len = e - s, max = dur > 0 ? dur - len : Infinity; return Math.max(0, Math.min(max, s + d)); },
	/** env: loudness per 100 ms from envFrom; the quietest point within half a second, if clearly quieter */
	snap(ms, env, envFrom) {
		if (!env || !env.length) return ms;
		const at = Math.floor((ms - envFrom) / this.STEP);
		if (at < 0 || at >= env.length) return ms;
		const reach = this.SNAP / this.STEP;
		let best = at;
		for (let i = Math.max(0, at - reach); i <= Math.min(env.length - 1, at + reach); i++)
			if (env[i] < env[best] || (env[i] === env[best] && Math.abs(i - at) < Math.abs(best - at))) best = i;
		return best !== at && env[best] < env[at] * 0.6 ? Math.round(envFrom + best * this.STEP + this.STEP / 2) : ms;
	},
};

/** The loudness of the saved audio around a clip, per 100 ms, for the trimmer's picture and pauses. */
const trimWave = { key: '', env: null, from: 0, loading: false };
async function loadTrimWave(r, from, to) {
	const key = r.file + ':' + from + ':' + to;
	if (trimWave.key === key || trimWave.loading) return;
	trimWave.loading = true;
	const got = await savedAudio(r.file, from, to);
	const n = Math.ceil((to - from) / Trim.STEP), env = new Float32Array(n);
	if (got) for (const p of got.pieces) {
		const ch = p.buffer.getChannelData(0), rate = p.buffer.sampleRate;
		const first = Math.floor(p.offsetS * rate), count = Math.floor(p.durS * rate), per = rate * Trim.STEP / 1000;
		for (let k = 0; k < count; k++) {
			const bin = Math.floor((p.atMs - from) / Trim.STEP + k / per);
			const v = Math.abs(ch[first + k] || 0);
			if (bin >= 0 && bin < n && v > env[bin]) env[bin] = v;
		}
	}
	Object.assign(trimWave, { key, env: got ? env : null, from, loading: false });
	if (view.sheetMode === 'trim') render();
}

function trimmer(r, c) {
	const dur = r.live ? (r.session ? heardMs() : nowFileTimeMs()) : r.durationMs || 0;
	if (!view.trim) {
		const [from, to] = Trim.window(c.startMs, c.endMs, dur);
		view.trim = { start: c.startMs, end: c.endMs, active: 'start', from, to };
	}
	const t = view.trim;
	loadTrimWave(r, t.from, t.to);
	const env = trimWave.key === r.file + ':' + t.from + ':' + t.to ? trimWave.env : null;
	const trimPlaying = player.key === 'trim';
	const hear = (edge) => (edge === 'start' ? player.play('trim', r.file, t.start, Math.min(t.start + 2000, t.end)) : player.play('trim', r.file, Math.max(t.start, t.end - 2000), t.end));
	const settle = (edge) => {
		if (edge === 'start') t.start = Trim.moveStart(t.start, t.end, Trim.snap(t.start, env, t.from));
		if (edge === 'end') t.end = Trim.moveEnd(t.start, t.end, Trim.snap(t.end, env, t.from), dur);
		/* an end pushed to the side of the view: show more */
		if (t.start - t.from < 1500 || t.to - t.end < 1500) [t.from, t.to] = Trim.window(t.start, t.end, dur);
	};

	const canvas = h('canvas.trim-wave', { 'aria-hidden': 'true' });
	const kept = h('div.trim-kept');
	const handle = (which) => h('div.trim-handle.' + which + (t.active === which ? '.active' : ''), {
		role: 'slider', tabindex: '0', 'aria-label': which === 'start' ? 'Start of the clip' : 'End of the clip',
		'aria-valuemin': String(t.from), 'aria-valuemax': String(t.to), 'aria-valuenow': String(t[which]), 'aria-valuetext': clockAt(r, t[which]),
		onkeydown: (e) => {
			const d = { ArrowLeft: -1000, ArrowDown: -1000, ArrowRight: 1000, ArrowUp: 1000, PageDown: -5000, PageUp: 5000 }[e.key];
			if (!d) return;
			e.preventDefault();
			/* moved in place, without rebuilding the sheet, so focus and quick repeats are kept */
			t.active = which;
			if (which === 'start') t.start = Trim.moveStart(t.start, t.end, t.start + d); else t.end = Trim.moveEnd(t.start, t.end, t.end + d, dur);
			place();
		},
	}, h('span.grip'));
	const hs = handle('start'), he = handle('end');
	const box = h('div.trimmer', kept, canvas, hs, he);
	const place = () => {
		const sp = t.to - t.from;
		const sx = (t.start - t.from) / sp * 100, ex = (t.end - t.from) / sp * 100;
		kept.style.left = sx + '%'; kept.style.width = (ex - sx) + '%';
		hs.style.left = sx + '%'; he.style.left = ex + '%';
		startT.textContent = clockAt(r, t.start); endT.textContent = clockAt(r, t.end); lenT.textContent = clipLength(t.end - t.start);
		for (const [el, v] of [[hs, t.start], [he, t.end]]) { el.setAttribute('aria-valuenow', String(Math.round(v))); el.setAttribute('aria-valuetext', clockAt(r, v)); }
		/* which end ±1 s moves, and the length on Play */
		hs.classList.toggle('active', t.active === 'start'); he.classList.toggle('active', t.active === 'end');
		for (const [b, w] of [[edgeS, 'start'], [edgeE, 'end']]) { b.classList.toggle('active', t.active === w); b.setAttribute('aria-pressed', String(t.active === w)); }
		nudgeLabel.textContent = 'Move the ' + t.active;
		if (player.key !== 'trim') playLabel.textContent = 'Play ' + clipLength(t.end - t.start);
		draw();
	};
	const draw = () => {
		const w = canvas.clientWidth, hgt = canvas.clientHeight, dpr = window.devicePixelRatio || 1;
		if (!w) return;
		canvas.width = Math.round(w * dpr); canvas.height = Math.round(hgt * dpr);
		const g = canvas.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0);
		const bars = Math.max(20, Math.floor(w / 6)), bw = w / bars, sp = t.to - t.from;
		for (let k = 0; k < bars; k++) {
			const at = t.from + sp * k / bars;
			const a = Math.max(0.06, Math.min(1, env ? env[Math.floor((at - t.from) / Trim.STEP)] * 1.6 || 0.06 : 0.12));
			const bh = a * hgt * 0.7;
			g.fillStyle = tokenColour(at >= t.start && at <= t.end ? '--teal-ink' : '--line-strong');
			g.fillRect(k * bw + bw * 0.25, (hgt - bh) / 2, bw * 0.5, bh);
		}
		if (trimPlaying) { g.fillStyle = '#fff'; g.fillRect((player.posMs() - t.from) / sp * w - 1, 0, 2, hgt); }
	};
	/* drag: an end, the kept part (both), or elsewhere (the nearer end jumps there) */
	box.addEventListener('pointerdown', (e) => {
		const rect = box.getBoundingClientRect(), sp = t.to - t.from;
		const msAt = (x) => Math.round(t.from + (Math.max(0, Math.min(rect.width, x - rect.left)) / rect.width) * sp);
		const sx = rect.left + (t.start - t.from) / sp * rect.width, ex = rect.left + (t.end - t.from) / sp * rect.width;
		const x0 = e.clientX, grab = 22;
		let target;
		if (Math.abs(x0 - sx) <= grab && Math.abs(x0 - sx) <= Math.abs(x0 - ex)) target = 'start';
		else if (Math.abs(x0 - ex) <= grab) target = 'end';
		else if (x0 > sx && x0 < ex) target = 'both';
		else if (Math.abs(x0 - sx) < Math.abs(x0 - ex)) { target = 'start'; t.start = Trim.moveStart(t.start, t.end, msAt(x0)); }
		else { target = 'end'; t.end = Trim.moveEnd(t.start, t.end, msAt(x0), dur); }
		if (target !== 'both') t.active = target;
		if (player.key === 'trim') player.stop();
		const s0 = t.start, len = t.end - t.start;
		box.setPointerCapture(e.pointerId);
		const moveAt = (ev) => {
			if (target === 'start') t.start = Trim.moveStart(t.start, t.end, msAt(ev.clientX));
			else if (target === 'end') t.end = Trim.moveEnd(t.start, t.end, msAt(ev.clientX), dur);
			else { t.start = Trim.moveBoth(s0, s0 + len, Math.round((ev.clientX - x0) / rect.width * sp), dur); t.end = t.start + len; }
			place();
		};
		const up = () => {
			box.removeEventListener('pointermove', moveAt); box.removeEventListener('pointerup', up); box.removeEventListener('pointercancel', up);
			if (target !== 'both') { settle(target); hear(target); }
			render();
		};
		box.addEventListener('pointermove', moveAt); box.addEventListener('pointerup', up); box.addEventListener('pointercancel', up);
		place();
		e.preventDefault();
	});
	const edgeTime = (which, label) => h('button.trim-edge' + (t.active === which ? '.active' : ''), { type: 'button', 'aria-pressed': String(t.active === which), onclick: () => { t.active = which; render(); } },
		h('span.small', label), which === 'start' ? startT : endT);
	const startT = h('span.trim-time'), endT = h('span.trim-time'), lenT = h('span.trim-len');
	const nudge = (d) => { if (t.active === 'start') t.start = Trim.moveStart(t.start, t.end, t.start + d); else t.end = Trim.moveEnd(t.start, t.end, t.end + d, dur); hear(t.active); render(); };
	const edgeS = edgeTime('start', 'Start'), edgeE = edgeTime('end', 'End');
	const nudgeLabel = h('span.small.grow');
	const playLabel = h('span', trimPlaying ? 'Stop' : '');
	setTimeout(place, 0);
	return [
		box,
		h('div.trim-times', edgeS, lenT, edgeE),
		h('div.trim-nudge', nudgeLabel,
			h('button', { type: 'button', 'aria-label': '1 second earlier', onclick: () => nudge(-1000) }, '−1 s'),
			h('button', { type: 'button', 'aria-label': '1 second later', onclick: () => nudge(1000) }, '+1 s')),
		h('div.small', 'Drag the ends, or the lit part to move the whole clip' + (env ? '. Each end settles in the nearest pause.' : '.')),
		h('button.btn.outline', { type: 'button', onclick: () => (trimPlaying ? player.stop() : player.play('trim', r.file, t.start, t.end)) }, icon(trimPlaying ? 'stop' : 'play', 18), playLabel),
		h('div.row',
			h('button.btn.outline.grow', { type: 'button', disabled: t.start === c.startMs && t.end === c.endMs, onclick: () => { view.trim = null; render(); } }, 'Reset'),
			h('button.btn.outline.grow', { type: 'button', onclick: () => { if (trimPlaying) player.stop(); view.trim = null; view.sheetMode = 'clip'; render(); } }, 'Cancel'),
			h('button.btn.primary.grow', { type: 'button', onclick: () => { player.stop(); c.startMs = t.start; c.endMs = t.end; r.clips.sort((a, b) => a.startMs - b.startMs); view.trim = null; view.sheetMode = 'clip'; saveRecordings(); toast('Clip trimmed to ' + clipLength(t.end - t.start)); render(); } }, 'Save'))];
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
		title = mode === 'trim' ? 'Trim clip' : mode === 'note' ? 'Note' : clipName(c, r);
		sub = clipMeta(c, r) + ' · ' + recordingTitle(r);
		const tile = (label, ic, t, onclick) => h('button.sheet-tile', { type: 'button', style: { background: `var(--${t}-tint)`, color: `var(--${t}-ink)` }, onclick }, icon(ic, 22), label);
		if (mode === 'trim') {
			body = trimmer(r, c);
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
		const o = obsRecordings().find((x) => x.file === view.sheet.obs);
		if (!o) { view.sheet = null; return null; }
		const v = obsClips(o.file);
		title = o.file;
		sub = recordingTitle(o) + ' · ' + durationText(o.durationMs) + ' · ' + clipsText(v.clips.length);
		const exportRow = (f) => h('div.export-row',
			h('button.export-main', { type: 'button', onclick: () => {
				settings.exportFormat = f.id; saveSettings();
				download(fileBase(o.file) + '.' + Exporter.ext(f.id), new Blob([Exporter.export(v, f.id)], { type: 'text/plain' }));
				close();
			} },
				h('span.tint-tile', { style: { width: '36px', height: '36px', background: `var(--${f.tint}-tint)`, color: `var(--${f.tint}-ink)`, fontSize: '12px', fontWeight: 700 } }, f.badge),
				h('span.grow', h('div', f.label), h('div.small', f.hint))),
			h('button.copy', { type: 'button', 'aria-label': 'Copy the ' + f.label + ' list', onclick: async () => {
				try { await navigator.clipboard.writeText(Exporter.export(v, f.id)); toast('Copied'); } catch { toast("This browser didn't allow copying."); }
			} }, 'Copy'));
		const has = aacSizes[o.file] !== undefined;
		body = [
			v.clips.length ? h('div.small', 'Export its clip list, in the recording’s own times. Click a format to download it, or Copy to paste it.') : h('div.small', 'No clips in this recording.'),
			v.clips.length ? h('div.export-list', Exporter.formats.map(exportRow)) : null,
			has ? h('div.row',
				h('button.btn.outline.grow', { type: 'button', onclick: async () => { const rec = await idb.aacGet(o.file); if (rec) download(fileBase(o.file) + '.m4a', rec.blob); close(); } }, icon('down', 18), 'Download AAC'),
				h('button.btn.danger.grow', { type: 'button', onclick: () => {
					if (player.key === 'aac:' + o.file) player.stop();
					idb.aacDelete(o.file); delete aacSizes[o.file]; toast('AAC file deleted from this browser'); close();
				} }, icon('trash', 18), 'Delete AAC')) : null,
			h('div.small', { style: { textAlign: 'center' } }, 'The recording itself stays on the PC.')];
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
			['range', 'mute', 'Lower the mix while playing', 'duckPct', 'A clip or an OBS recording’s AAC plays over the live mix lowered this much. 100% silences it. Clipping and OBS carry on either way.'],
		]],
		['Clips', 'rec', [
			['switch', 'bookmark', 'Chapter markers in the OBS recording', 'chapters'],
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
			const sel = h('select.choice', { 'aria-label': label, onchange: (e) => { const o = options[e.target.selectedIndex]; settings[key] = o[0]; saveSettings(); if (key === 'touchSound' || key === 'haptics') feedback.tap(); if (key === 'haptics') feedback.buzz([60, 90, 60]); render(); } },
				options.map(([v, l]) => h('option', { selected: settings[key] === v }, l)));
			return h('label.setting', base, tile, h('span.grow', label), sel);
		}
		if (kind === 'range') {
			const val = h('span.value', { style: { color: `var(--${t}-ink)` } }, settings[key] ? settings[key] + '%' : 'Off');
			const r = h('input', { type: 'range', min: 0, max: 100, step: 5, value: settings[key], 'aria-label': label, 'aria-valuetext': settings[key] + ' percent' });
			r.addEventListener('input', () => { settings[key] = Number(r.value); val.textContent = settings[key] ? settings[key] + '%' : 'Off'; r.setAttribute('aria-valuetext', settings[key] + ' percent'); });
			r.addEventListener('change', saveSettings);
			return h('div.setting.with-range', base, h('div.row', tile, h('span.grow', label), val), r, h('div.small', options));
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
			} }, 'Forget this browser'), h('button.btn.outline', { type: 'button', onclick: () => location.reload() }, 'Replay the connect step'),
				h('button.btn.outline', { type: 'button', onclick: () => { view.help = true; render(); } }, 'How OBS Director works'))),
		view.help ? helpDialog() : null];
}

/** A short guide to the whole page, from Settings, About. */
function helpDialog() {
	const dlg = h('dialog', { 'aria-labelledby': 'help-title' });
	const close = () => { view.help = false; dlg.close(); render(); };
	const step = (n, title, body) => h('div.how-step', h('span.how-n', n), h('div', h('div', { style: { fontWeight: 500 } }, title), h('div.small', body)));
	dlg.append(h('div', { style: { display: 'flex', flexDirection: 'column', gap: '14px' } },
		h('h2.card-title#help-title', { style: { fontSize: '19px', fontWeight: 700 } }, 'How OBS Director works'),
		step('1', 'Connect', 'This page comes from the OBS Director plugin on the PC. The first time, someone at the PC clicks Allow in OBS.'),
		step('2', 'Listen', 'Switch on Listen in this browser on Monitor to hear the OBS mix. Browsers only play sound after a click.'),
		step('3', 'Mark clips', 'Click Start clip (or press Space) when something good happens and End when it\u2019s over, whether or not OBS is recording. It records the mix in this browser and is named after the OBS recording; trim it later to take in more.'),
		step('4', 'Use the clips', 'Recordings has two tabs: Clips, made in this browser, and OBS recordings, kept on the PC, whose sound you can save here as AAC. Play, trim, add a note, download the audio, or export the list for your editor.'),
		step('5', 'Talk to the desk', 'Desk is a group chat with the OBS dock and the phones. Alerts, like a full disk, show up there too.'),
		step('6', 'Stage mode', 'The masks button at the top shows a big, dim timer and Clip button for use beside a stage.'),
		h('button.btn.primary', { type: 'button', onclick: close }, 'Done')));
	dlg.addEventListener('cancel', (e) => { e.preventDefault(); close(); });
	setTimeout(() => { if (dlg.isConnected && !dlg.open) dlg.showModal(); }, 0);
	return dlg;
}

/* ---------- stage mode ---------- */
function stageView() {
	const s = state.status || {};
	const running = state.clipStart !== null;
	const r = sessionEntry();
	const btn = h('button.big-clip' + (running ? '.running' : ''), { type: 'button', onclick: toggleClip, disabled: !canClip() && !running },
		running ? bind(h('span.b'), () => clipLength(runningClipMs())) : h('span.b', 'Clip'),
		h('span.s', running ? 'End clip' : canClip() ? nextClipName() : 'Waiting for OBS'));
	if (running) live.push([btn, null, () => btn.setAttribute('aria-label', 'End clip, ' + clipLength(runningClipMs()) + ' long')]);
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
