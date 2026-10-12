import { inflateSync } from 'node:zlib';

const SIGNATURE_BYTES = 8;
const CHANNELS_BY_COLOR_TYPE = { 2: 3, 6: 4 };
const VISIBLE_DIFFERENCE = 48;

export function decodePng(buffer) {
  let offset = SIGNATURE_BYTES;
  let header = null;
  const data = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('latin1', offset + 4, offset + 8);
    const body = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') header = { width: body.readUInt32BE(0), height: body.readUInt32BE(4), depth: body[8], colorType: body[9], interlace: body[12] };
    if (type === 'IDAT') data.push(body);
    if (type === 'IEND') return unfilter(header, inflateSync(Buffer.concat(data)));
    offset += length + 12;
  }
  throw new Error('truncated png');
}

function unfilter(header, raw) {
  const channels = CHANNELS_BY_COLOR_TYPE[header.colorType];
  if (!channels || header.depth !== 8 || header.interlace !== 0) throw new Error(`unsupported png: ${JSON.stringify(header)}`);
  const stride = header.width * channels;
  const pixels = Buffer.alloc(stride * header.height);
  for (let row = 0; row < header.height; row++) {
    const filter = raw[row * (stride + 1)];
    const source = row * (stride + 1) + 1;
    const target = row * stride;
    for (let i = 0; i < stride; i++) {
      const left = i >= channels ? pixels[target + i - channels] : 0;
      const up = row > 0 ? pixels[target + i - stride] : 0;
      const upLeft = row > 0 && i >= channels ? pixels[target + i - stride - channels] : 0;
      pixels[target + i] = (raw[source + i] + predicted(filter, left, up, upLeft)) & 0xff;
    }
  }
  return { width: header.width, height: header.height, channels, pixels };
}

function predicted(filter, left, up, upLeft) {
  if (filter === 1) return left;
  if (filter === 2) return up;
  if (filter === 3) return (left + up) >> 1;
  if (filter === 4) return paeth(left, up, upLeft);
  return 0;
}

function paeth(left, up, upLeft) {
  const estimate = left + up - upLeft;
  const toLeft = Math.abs(estimate - left);
  const toUp = Math.abs(estimate - up);
  const toUpLeft = Math.abs(estimate - upLeft);
  if (toLeft <= toUp && toLeft <= toUpLeft) return left;
  return toUp <= toUpLeft ? up : upLeft;
}

export function squareAround(image, centre, halfSide) {
  const side = halfSide * 2;
  const pixels = Buffer.alloc(side * side * 3);
  for (let y = 0; y < side; y++) {
    for (let x = 0; x < side; x++) {
      const from = ((centre.y - halfSide + y) * image.width + centre.x - halfSide + x) * image.channels;
      image.pixels.copy(pixels, (y * side + x) * 3, from, from + 3);
    }
  }
  return pixels;
}

export function colourfulFraction(rgb) {
  let colourful = 0;
  for (let i = 0; i < rgb.length; i += 3) {
    if (Math.max(rgb[i], rgb[i + 1], rgb[i + 2]) - Math.min(rgb[i], rgb[i + 1], rgb[i + 2]) > VISIBLE_DIFFERENCE) colourful++;
  }
  return colourful / (rgb.length / 3);
}

export function changedFraction(first, second) {
  let changed = 0;
  for (let i = 0; i < first.length; i += 3) {
    const difference = Math.abs(first[i] - second[i]) + Math.abs(first[i + 1] - second[i + 1]) + Math.abs(first[i + 2] - second[i + 2]);
    if (difference > VISIBLE_DIFFERENCE) changed++;
  }
  return changed / (first.length / 3);
}
