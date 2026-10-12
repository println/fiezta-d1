import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

function bus() {
  const page = startPage();
  return { page, bus: page.bus };
}

test('a late subscriber receives the current state', () => {
  const { page, bus: fieztaPage } = bus();
  fieztaPage.emit('site:trackChanged', { id: 'a' }, { video: 1 });
  const received = [];
  fieztaPage.on('site:trackChanged', (detail, elements) => received.push([detail, elements]));
  assert.deepEqual(received, [[{ id: 'a' }, { video: 1 }]]);
  page.close();
});

test('an emit equal to the last one is dropped', () => {
  const { page, bus: fieztaPage } = bus();
  const received = [];
  fieztaPage.on('site:trackChanged', (detail) => received.push(detail.id));
  fieztaPage.emit('site:trackChanged', { id: 'a' });
  fieztaPage.emit('site:trackChanged', { id: 'a' });
  fieztaPage.emit('site:trackChanged', { id: 'b' });
  assert.deepEqual(received, ['a', 'b']);
  page.close();
});

test('a moment reaches only who was already listening', () => {
  const { page, bus: fieztaPage } = bus();
  fieztaPage.emit('site:userInteracted', { at: 1 }, {}, { moment: true });
  const received = [];
  fieztaPage.on('site:userInteracted', (detail) => received.push(detail.at));
  fieztaPage.emit('site:userInteracted', { at: 2 }, {}, { moment: true });
  fieztaPage.emit('site:userInteracted', { at: 2 }, {}, { moment: true });
  assert.deepEqual(received, [2, 2]);
  page.close();
});

test('cancelling stops delivery', () => {
  const { page, bus: fieztaPage } = bus();
  const received = [];
  const off = fieztaPage.on('site:pageChanged', (detail) => received.push(detail.kind));
  fieztaPage.emit('site:pageChanged', { kind: 'watch' });
  off();
  fieztaPage.emit('site:pageChanged', { kind: 'search' });
  assert.deepEqual(received, ['watch']);
  page.close();
});

test('a handler that throws does not stop the others', () => {
  const { page, bus: fieztaPage } = bus();
  const received = [];
  fieztaPage.on('site:pageChanged', () => { throw new Error('boom'); });
  fieztaPage.on('site:pageChanged', (detail) => received.push(detail.kind));
  fieztaPage.emit('site:pageChanged', { kind: 'watch' });
  assert.deepEqual(received, ['watch']);
  page.close();
});

test('an event without a plugin prefix is refused', () => {
  const { page, bus: fieztaPage } = bus();
  assert.throws(() => fieztaPage.emit('trackChanged', {}));
  page.close();
});

test('fiezta.reset cancels the subscriptions of the events script', () => {
  const { page, bus: fieztaPage } = bus();
  const received = [];
  page.fiezta.listen('site:pageChanged', (detail) => received.push(detail.kind));
  fieztaPage.emit('site:pageChanged', { kind: 'watch' });
  page.fiezta.reset();
  fieztaPage.emit('site:pageChanged', { kind: 'search' });
  assert.deepEqual(received, ['watch']);
  page.close();
});
