#!/usr/bin/env node
/**
 * Cron Daemon for OpenCode Debian proot.
 * Executes scheduled tasks from /root/.opencode/crontab.txt without root or systemd.
 */
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const PID_FILE = '/var/run/cron.pid';
const CRONTAB_FILE = '/root/.opencode/crontab.txt';
const LOG_FILE = '/root/.opencode/cron.log';

function writePid() {
  try {
    fs.mkdirSync(path.dirname(PID_FILE), { recursive: true });
    fs.writeFileSync(PID_FILE, String(process.pid));
  } catch (e) {
    console.error('Failed to write PID:', e);
  }
}

function cleanPid() {
  try {
    if (fs.existsSync(PID_FILE)) {
      const cur = fs.readFileSync(PID_FILE, 'utf-8').trim();
      if (cur === String(process.pid)) {
        fs.unlinkSync(PID_FILE);
      }
    }
  } catch (_) {}
}

function parseCronLine(line) {
  line = line.trim();
  if (!line || line.startsWith('#')) return null;
  const parts = line.split(/\s+/);
  if (parts.length < 6) return null;
  const [m, h, dom, mon, dow, ...cmdParts] = parts;
  return { m, h, dom, mon, dow, cmd: cmdParts.join(' ') };
}

function matchField(val, pat) {
  if (pat === '*') return true;
  for (const part of pat.split(',')) {
    if (part === '*') return true;
    if (part.includes('/')) {
      const [, step] = part.split('/');
      const s = parseInt(step, 10);
      if (s > 0 && val % s === 0) return true;
    } else if (part.includes('-')) {
      const [lo, hi] = part.split('-').map((x) => parseInt(x, 10));
      if (val >= lo && val <= hi) return true;
    } else if (/^\d+$/.test(part) && parseInt(part, 10) === val) {
      return true;
    }
  }
  return false;
}

function runJob(cmd) {
  const env = Object.assign({}, process.env, {
    HOME: '/root',
    PATH: '/root/.opencode/bin:/opt/node/bin:/usr/local/bin:/usr/bin:/bin:' + (process.env.PATH || ''),
  });
  const nowStr = new Date().toISOString().replace('T', ' ').slice(0, 19);
  try {
    fs.mkdirSync(path.dirname(LOG_FILE), { recursive: true });
    fs.appendFileSync(LOG_FILE, `[${nowStr}] CRON RUN: ${cmd}\n`);
    const logFd = fs.openSync(LOG_FILE, 'a');
    const child = spawn('/bin/bash', ['-c', cmd], {
      env,
      stdio: ['ignore', logFd, logFd],
      detached: true,
    });
    child.unref();
  } catch (err) {
    try {
      fs.appendFileSync(LOG_FILE, `[${nowStr}] [CRON ERROR] failed to spawn ${cmd}: ${err.message}\n`);
    } catch (_) {}
  }
}

function checkAndRun(now) {
  if (!fs.existsSync(CRONTAB_FILE)) return;
  try {
    const content = fs.readFileSync(CRONTAB_FILE, 'utf-8');
    const lines = content.split('\n');
    for (const line of lines) {
      const parsed = parseCronLine(line);
      if (!parsed) continue;
      const { m, h, dom, mon, dow, cmd } = parsed;
      if (
        matchField(now.getMinutes(), m) &&
        matchField(now.getHours(), h) &&
        matchField(now.getDate(), dom) &&
        matchField(now.getMonth() + 1, mon) &&
        matchField(now.getDay(), dow)
      ) {
        runJob(cmd);
      }
    }
  } catch (err) {
    console.error('Error checking crontab:', err);
  }
}

function main() {
  writePid();

  process.on('SIGINT', () => { cleanPid(); process.exit(0); });
  process.on('SIGTERM', () => { cleanPid(); process.exit(0); });
  process.on('exit', () => { cleanPid(); });

  let lastMinute = -1;

  setInterval(() => {
    const now = new Date();
    const curMinute = now.getMinutes();
    if (curMinute !== lastMinute) {
      lastMinute = curMinute;
      checkAndRun(now);
    }
  }, 10000);
}

main();
