import { spawn } from 'node:child_process';
import { readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { adb } from './adb.mjs';
import { config } from './config.mjs';
import { decodePng } from './png.mjs';

export class HeadUnit {
  static async start(attempts = 4) {
    await adb('forward', `tcp:${config.headUnitPort}`, `tcp:${config.headUnitPort}`);
    const unit = new HeadUnit(spawn(config.dhu, ['-c', config.dhuConfig], { stdio: ['pipe', 'pipe', 'pipe'] }));
    try {
      await unit.waitForOutput(/SSL negotiation finished successfully/, 30_000);
      return unit;
    } catch (error) {
      unit.kill();
      await unit.exited;
      if (attempts <= 1) throw error;
      return HeadUnit.start(attempts - 1);
    }
  }

  constructor(process) {
    this.process = process;
    this.output = '';
    this.exited = new Promise((resolve) => process.on('exit', resolve));
    const collect = (chunk) => { this.output += chunk; };
    process.stdout.on('data', collect);
    process.stderr.on('data', collect);
  }

  async waitForOutput(pattern, timeout) {
    const deadline = Date.now() + timeout;
    while (!pattern.test(this.output)) {
      if (Date.now() > deadline) throw new Error(`head unit never printed ${pattern}:\n${this.output}`);
      await sleep(200);
    }
  }

  send(command) {
    this.process.stdin.write(`${command}\n`);
  }

  tap(x, y) {
    this.send(`tap ${x} ${y}`);
  }

  key(name) {
    this.send(`keycode ${name}`);
  }

  focus(kind) {
    this.send(`focus ${kind}`);
  }

  speak(wavPath) {
    this.send(`mic play ${wavPath}`);
  }

  async screen(timeout = 5_000) {
    const path = join(tmpdir(), `fiezta-head-unit-${this.process.pid}.png`);
    await rm(path, { force: true });
    this.send(`screenshot ${path}`);
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const image = await readFile(path).then(decodePng).catch(() => null);
      if (image) return image;
      await sleep(100);
    }
    throw new Error('the head unit never wrote the screenshot');
  }

  async quit() {
    this.send('quit');
    const exited = await Promise.race([this.exited.then(() => true), sleep(5_000).then(() => false)]);
    if (!exited) this.kill();
  }

  kill() {
    this.process.kill('SIGKILL');
  }
}
