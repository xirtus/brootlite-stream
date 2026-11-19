// Compact Pure-JS QR Code SVG Generator (Byte mode, ECC Level L, Versions 1..14)
// Zero external dependencies or network requests.

const GF_EXP = new Uint8Array(512);
const GF_LOG = new Uint8Array(256);

(function initGalois() {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x;
    GF_LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) {
    GF_EXP[i] = GF_EXP[i - 255];
  }
})();

function gfMul(a, b) {
  if (a === 0 || b === 0) return 0;
  return GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

function rsGeneratorPoly(degree) {
  let poly = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array(poly.length + 1).fill(0);
    const root = GF_EXP[i];
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= gfMul(poly[j], root);
    }
    poly = next;
  }
  return poly;
}

function rsEncode(data, ecLen) {
  const gen = rsGeneratorPoly(ecLen);
  const res = new Uint8Array(data.length + ecLen);
  res.set(data, 0);
  for (let i = 0; i < data.length; i++) {
    const coef = res[i];
    if (coef !== 0) {
      for (let j = 0; j < gen.length; j++) {
        res[i + j] ^= gfMul(gen[j], coef);
      }
    }
  }
  return res.slice(data.length);
}

// Version table for ECC Level L: [totalCodewords, ecPerBlock, blocksG1, dataG1, blocksG2, dataG2]
const VERSION_L = [
  null,
  [26, 7, 1, 19, 0, 0],
  [44, 10, 1, 34, 0, 0],
  [70, 15, 1, 55, 0, 0],
  [100, 20, 1, 80, 0, 0],
  [134, 26, 1, 108, 0, 0],
  [172, 18, 2, 68, 0, 0],
  [196, 20, 2, 78, 0, 0],
  [242, 24, 2, 97, 0, 0],
  [292, 30, 2, 116, 0, 0],
  [346, 18, 2, 68, 2, 69],
  [404, 20, 4, 81, 0, 0],
  [466, 24, 2, 92, 2, 93],
  [532, 26, 4, 107, 0, 0],
  [581, 30, 3, 115, 1, 116],
];

const ALIGNMENT_POS = [
  [],
  [],
  [6, 18],
  [6, 22],
  [6, 26],
  [6, 30],
  [6, 34],
  [6, 22, 38],
  [6, 24, 42],
  [6, 26, 46],
  [6, 28, 50],
  [6, 30, 54],
  [6, 32, 58],
  [6, 34, 62],
  [6, 26, 46, 66],
];

function encodeUtf8Bytes(str) {
  if (typeof TextEncoder !== "undefined") {
    return Array.from(new TextEncoder().encode(str));
  }
  const out = [];
  for (let i = 0; i < str.length; i++) {
    let c = str.charCodeAt(i);
    if (c < 128) out.push(c);
    else if (c < 2048) out.push((c >> 6) | 192, (c & 63) | 128);
    else out.push((c >> 12) | 224, ((c >> 6) & 63) | 128, (c & 63) | 128);
  }
  return out;
}

function chooseVersion(byteLen) {
  for (let v = 1; v < VERSION_L.length; v++) {
    const info = VERSION_L[v];
    const dataBytes = info[2] * info[3] + info[4] * info[5];
    const countBits = v < 10 ? 8 : 16;
    const neededBits = 4 + countBits + byteLen * 8;
    if (neededBits <= dataBytes * 8) return v;
  }
  return -1;
}

function buildCodewords(bytes, version) {
  const [, ecLen, b1, d1, b2, d2] = VERSION_L[version];
  const totalDataBytes = b1 * d1 + b2 * d2;
  const bits = [];
  const pushBits = (val, len) => {
    for (let i = len - 1; i >= 0; i--) {
      bits.push((val >> i) & 1);
    }
  };

  pushBits(0b0100, 4); // Byte mode
  pushBits(bytes.length, version < 10 ? 8 : 16);
  for (let i = 0; i < bytes.length; i++) {
    pushBits(bytes[i], 8);
  }
  const maxBits = totalDataBytes * 8;
  for (let i = 0; i < 4 && bits.length < maxBits; i++) bits.push(0);
  while (bits.length % 8 !== 0) bits.push(0);

  const data = [];
  for (let i = 0; i < bits.length; i += 8) {
    let byte = 0;
    for (let j = 0; j < 8; j++) byte = (byte << 1) | bits[i + j];
    data.push(byte);
  }
  let padToggle = true;
  while (data.length < totalDataBytes) {
    data.push(padToggle ? 0xec : 0x11);
    padToggle = !padToggle;
  }

  const dataBlocks = [];
  const ecBlocks = [];
  let offset = 0;
  for (let i = 0; i < b1; i++) {
    const blk = Uint8Array.from(data.slice(offset, offset + d1));
    offset += d1;
    dataBlocks.push(blk);
    ecBlocks.push(rsEncode(blk, ecLen));
  }
  for (let i = 0; i < b2; i++) {
    const blk = Uint8Array.from(data.slice(offset, offset + d2));
    offset += d2;
    dataBlocks.push(blk);
    ecBlocks.push(rsEncode(blk, ecLen));
  }

  const interleaved = [];
  const maxD = Math.max(d1, d2);
  for (let i = 0; i < maxD; i++) {
    for (let b = 0; b < dataBlocks.length; b++) {
      if (i < dataBlocks[b].length) interleaved.push(dataBlocks[b][i]);
    }
  }
  for (let i = 0; i < ecLen; i++) {
    for (let b = 0; b < ecBlocks.length; b++) {
      interleaved.push(ecBlocks[b][i]);
    }
  }
  return interleaved;
}

function makeEmptyMatrix(size) {
  const modules = Array.from({ length: size }, () => new Int8Array(size).fill(-1));
  const reserved = Array.from({ length: size }, () => new Uint8Array(size));
  return { size, modules, reserved };
}

function placeFinder(mat, r0, c0) {
  for (let r = -1; r <= 7; r++) {
    for (let c = -1; c <= 7; c++) {
      const rr = r0 + r;
      const cc = c0 + c;
      if (rr < 0 || rr >= mat.size || cc < 0 || cc >= mat.size) continue;
      const inBox = r >= 0 && r <= 6 && c >= 0 && c <= 6;
      const dark =
        inBox &&
        (r === 0 || r === 6 || c === 0 || c === 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4));
      mat.modules[rr][cc] = dark ? 1 : 0;
      mat.reserved[rr][cc] = 1;
    }
  }
}

function placeAlignment(mat, r0, c0) {
  for (let r = -2; r <= 2; r++) {
    for (let c = -2; c <= 2; c++) {
      const rr = r0 + r;
      const cc = c0 + c;
      if (mat.reserved[rr][cc]) return;
    }
  }
  for (let r = -2; r <= 2; r++) {
    for (let c = -2; c <= 2; c++) {
      const dark = Math.abs(r) === 2 || Math.abs(c) === 2 || (r === 0 && c === 0);
      mat.modules[r0 + r][c0 + c] = dark ? 1 : 0;
      mat.reserved[r0 + r][c0 + c] = 1;
    }
  }
}

function setupPatterns(mat, version) {
  const s = mat.size;
  placeFinder(mat, 0, 0);
  placeFinder(mat, 0, s - 7);
  placeFinder(mat, s - 7, 0);

  const align = ALIGNMENT_POS[version];
  for (let i = 0; i < align.length; i++) {
    for (let j = 0; j < align.length; j++) {
      placeAlignment(mat, align[i], align[j]);
    }
  }

  for (let i = 8; i < s - 8; i++) {
    if (!mat.reserved[6][i]) {
      mat.modules[6][i] = i % 2 === 0 ? 1 : 0;
      mat.reserved[6][i] = 1;
    }
    if (!mat.reserved[i][6]) {
      mat.modules[i][6] = i % 2 === 0 ? 1 : 0;
      mat.reserved[i][6] = 1;
    }
  }

  // Dark module
  mat.modules[s - 8][8] = 1;
  mat.reserved[s - 8][8] = 1;

  // Reserve format info cells
  for (let i = 0; i < 9; i++) {
    if (i !== 6) {
      mat.reserved[8][i] = 1;
      mat.reserved[i][8] = 1;
    }
  }
  for (let i = 0; i < 8; i++) {
    mat.reserved[8][s - 1 - i] = 1;
    mat.reserved[s - 1 - i][8] = 1;
  }

  // Reserve version info for version >= 7
  if (version >= 7) {
    for (let i = 0; i < 6; i++) {
      for (let j = 0; j < 3; j++) {
        mat.reserved[i][s - 11 + j] = 1;
        mat.reserved[s - 11 + j][i] = 1;
      }
    }
  }
}

function placeDataBits(mat, codewords) {
  const s = mat.size;
  let bitIdx = 0;
  const totalBits = codewords.length * 8;
  let upward = true;
  for (let col = s - 1; col > 0; col -= 2) {
    if (col === 6) col--;
    for (let i = 0; i < s; i++) {
      const r = upward ? s - 1 - i : i;
      for (let dc = 0; dc < 2; dc++) {
        const c = col - dc;
        if (mat.reserved[r][c]) continue;
        let bit = 0;
        if (bitIdx < totalBits) {
          const byte = codewords[bitIdx >> 3];
          bit = (byte >> (7 - (bitIdx & 7))) & 1;
          bitIdx++;
        }
        mat.modules[r][c] = bit;
      }
    }
    upward = !upward;
  }
}

function applyFormatAndMask(baseMat, version, maskId) {
  const s = baseMat.size;
  const grid = baseMat.modules.map((row) => Int8Array.from(row));

  const maskFn = (r, c) => {
    switch (maskId) {
      case 0: return (r + c) % 2 === 0;
      case 1: return r % 2 === 0;
      case 2: return c % 3 === 0;
      case 3: return (r + c) % 3 === 0;
      case 4: return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0;
      case 5: return ((r * c) % 2) + ((r * c) % 3) === 0;
      case 6: return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0;
      default: return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0;
    }
  };

  for (let r = 0; r < s; r++) {
    for (let c = 0; c < s; c++) {
      if (!baseMat.reserved[r][c] && maskFn(r, c)) {
        grid[r][c] ^= 1;
      }
    }
  }

  // Format bits for ECC L (01) + maskId (0..7)
  const formatData = (0b01 << 3) | maskId;
  let rem = formatData << 10;
  for (let i = 14; i >= 10; i--) {
    if ((rem >> i) & 1) rem ^= 0b10100110111 << (i - 10);
  }
  const formatBits = ((formatData << 10) | rem) ^ 0b101010000010010;

  for (let i = 0; i < 6; i++) {
    const bit = (formatBits >> i) & 1;
    grid[8][s - 1 - i] = bit;
    grid[i][8] = bit;
  }
  grid[8][s - 7] = (formatBits >> 6) & 1;
  grid[7][8] = (formatBits >> 6) & 1;
  grid[8][s - 8] = (formatBits >> 7) & 1;
  grid[8][8] = (formatBits >> 7) & 1;
  grid[8][7] = (formatBits >> 8) & 1;
  grid[s - 7][8] = (formatBits >> 8) & 1;
  for (let i = 9; i < 15; i++) {
    const bit = (formatBits >> i) & 1;
    grid[8][14 - i] = bit;
    grid[s - 15 + i][8] = bit;
  }

  if (version >= 7) {
    let vRem = version << 12;
    for (let i = 17; i >= 12; i--) {
      if ((vRem >> i) & 1) vRem ^= 0b1111100100101 << (i - 12);
    }
    const vBits = (version << 12) | vRem;
    for (let i = 0; i < 18; i++) {
      const bit = (vBits >> i) & 1;
      const r = Math.floor(i / 3);
      const c = s - 11 + (i % 3);
      grid[r][c] = bit;
      grid[c][r] = bit;
    }
  }

  return grid;
}

function penaltyScore(grid) {
  const s = grid.length;
  let score = 0;
  for (let r = 0; r < s; r++) {
    let run = 1;
    for (let c = 1; c < s; c++) {
      if (grid[r][c] === grid[r][c - 1]) {
        run++;
        if (run === 5) score += 3;
        else if (run > 5) score += 1;
      } else {
        run = 1;
      }
    }
  }
  for (let c = 0; c < s; c++) {
    let run = 1;
    for (let r = 1; r < s; r++) {
      if (grid[r][c] === grid[r - 1][c]) {
        run++;
        if (run === 5) score += 3;
        else if (run > 5) score += 1;
      } else {
        run = 1;
      }
    }
  }
  for (let r = 0; r < s - 1; r++) {
    for (let c = 0; c < s - 1; c++) {
      const v = grid[r][c];
      if (v === grid[r][c + 1] && v === grid[r + 1][c] && v === grid[r + 1][c + 1]) {
        score += 3;
      }
    }
  }
  return score;
}

export function generateQrSvg(text, sizePx = 172) {
  if (!text) return "";
  const bytes = encodeUtf8Bytes(String(text));
  const version = chooseVersion(bytes.length);
  if (version < 0) {
    return (
      '<svg width="' +
      sizePx +
      '" height="' +
      sizePx +
      '" viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">' +
      '<rect width="100" height="100" fill="#fff"/>' +
      '<text x="50" y="54" text-anchor="middle" font-size="11" fill="#090B10">QR Payload Too Long</text>' +
      "</svg>"
    );
  }
  const codewords = buildCodewords(bytes, version);
  const dim = version * 4 + 17;
  const mat = makeEmptyMatrix(dim);
  setupPatterns(mat, version);
  placeDataBits(mat, codewords);

  let bestGrid = null;
  let bestScore = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    const candidate = applyFormatAndMask(mat, version, mask);
    const sc = penaltyScore(candidate);
    if (sc < bestScore) {
      bestScore = sc;
      bestGrid = candidate;
    }
  }

  let paths = "";
  for (let r = 0; r < dim; r++) {
    for (let c = 0; c < dim; c++) {
      if (bestGrid[r][c] === 1) {
        paths += "M" + c + "," + r + "h1v1h-1z";
      }
    }
  }

  return (
    '<svg width="' +
    sizePx +
    '" height="' +
    sizePx +
    '" viewBox="-2 -2 ' +
    (dim + 4) +
    " " +
    (dim + 4) +
    '" xmlns="http://www.w3.org/2000/svg" shape-rendering="crispEdges">' +
    '<rect x="-2" y="-2" width="' +
    (dim + 4) +
    '" height="' +
    (dim + 4) +
    '" fill="#FFFFFF"/>' +
    '<path d="' +
    paths +
    '" fill="#090B10"/>' +
    "</svg>"
  );
}
