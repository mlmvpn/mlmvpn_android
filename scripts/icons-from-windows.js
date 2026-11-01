#!/usr/bin/env node
/*
 * Regenerates the home-screen artwork from the Windows app's icon sprite.
 *
 *     node scripts/icons-from-windows.js [--check]
 *
 * The Windows desktop's icons (public/shell/icons.js) are the approved set, so any feature both
 * apps have must look the same on both. Rather than redrawing them here by hand, this reads the
 * sprite exactly as the desktop does -- the file is evaluated, not parsed with regexes -- and writes
 * Android VectorDrawables:
 *
 *   ic_app_<name>.xml   finished artwork seated on the shared 1024 squircle (inset to 90%, the way
 *                       AppIcon.kt expects -- it scales artwork by 100/90), in one of the desktop's
 *                       tile styles:
 *                         cover  the art fills the tile on its own ground colour (`artBg`), scaled
 *                                by `artScale` when the desktop scales it
 *                         white  the white tile (#FFFFFF -> #ECECF0) with the art at 52% or, `big`,
 *                                82% -- `.mv-ic.is-art` / `.is-big` in public/ui/components.css
 *                         image  artwork that carries its own ground (the desktop's `image: true`,
 *                                `.mv-ic.is-image`): drawn as is, its viewBox mapped onto the 1024 box
 *   ic_glyph_<name>.xml a white 24dp glyph, drawn by AppIcon on the tinted gradient tile -- the same
 *                       recipe the desktop's glyph tiles use.
 *
 * Re-run after any icon changes on Windows (the same arrangement as sync-gt-assets.js). `--check`
 * exits non-zero when a generated file is out of date, so a build step can guard it.
 *
 * Only the SVG the sprite actually uses is supported: path, rect, circle and g, fill / stroke /
 * fill-rule / opacity / stroke-width / linecap / linejoin, and translate / scale / rotate
 * transforms. Anything else (a <text>, a matrix, a gradient reference) stops the script rather than
 * producing an icon that silently differs from the desktop's.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const WINDOWS_ICONS = path.resolve(ROOT, '..', 'public', 'shell', 'icons.js');
const OUT = path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable');

/** Desktop app id -> Android drawable, with the tile style the desktop gives it (apps.js). */
const ART = {
  ic_app_masque:     { id: 'g-masque-art',     kind: 'cover', bg: '#FFFFFF', app: 'masque' },
  ic_app_wireguard:  { id: 'g-wireguard-art',  kind: 'cover', bg: '#88171a', scale: 0.84, app: 'wireguard' },
  ic_app_warp:       { id: 'g-warp-art',       kind: 'cover', bg: '#f06d6c', app: 'warp' },
  ic_app_geph:       { id: 'g-geph-art',       kind: 'cover', bg: '#FFFFFF', app: 'geph' },
  ic_app_gateway:    { id: 'g-gateway-art',    kind: 'cover', bg: '#FFFFFF', app: 'gateway' },
  ic_app_cloudflare: { id: 'g-cloudflare-art', kind: 'cover', bg: '#FFFFFF', app: 'cloud' },
  ic_app_tor:        { id: 'g-tor-art',        kind: 'white', scale: 0.82, app: 'tor' },
  ic_app_openvpn:    { id: 'g-openvpn-art',    kind: 'white', scale: 0.52, app: 'openvpn' },
  ic_app_lantern:    { id: 'g-lantern-art',    kind: 'image', app: 'lantern' },
};

/** Desktop glyph -> Android drawable. Tints stay in HomeDestinations (the desktop's tint tokens). */
const GLYPHS = {
  ic_glyph_radar:        'g-radar',
  ic_glyph_layers:       'g-layers',
  ic_glyph_pad:          'g-pad',
  ic_glyph_pin:          'g-pin',
  ic_glyph_shield_check: 'g-shield-check',
  ic_glyph_swap:         'g-swap',
  ic_glyph_bars:         'g-bars',
  ic_glyph_book:         'g-book',
};

/** The squircle every ic_app_* tile shares (a 90% inset in a 1024 box). */
const SQUIRCLE = 'M651.264 51.2c92.16 0 138.24 0 188.416 15.36 54.272 19.456 97.28 62.464 116.736 116.736C972.8 233.472 972.8 280.576 972.8 372.736v278.528c0 92.16 0 138.24-15.36 188.416-19.456 54.272-62.464 97.28-116.736 116.736-51.2 16.384-97.28 16.384-189.44 16.384H372.736c-92.16 0-138.24 0-188.416-15.36-55.296-20.48-97.28-62.464-117.76-117.76C51.2 790.528 51.2 744.448 51.2 651.264V372.736c0-92.16 0-138.24 15.36-188.416 20.48-54.272 62.464-97.28 117.76-116.736C233.472 51.2 279.552 51.2 372.736 51.2h278.528z';
const TILE_MIN = 51.2;
const TILE_SIZE = 921.6;

// ── the sprite, evaluated the way the desktop evaluates it ────────────────────────────────────

function loadSprite() {
  const src = fs.readFileSync(WINDOWS_ICONS, 'utf8');
  const exposed = src.replace(
    'window.MV.icons = { inject: inject, svg: svg };',
    'window.MV.icons = { inject: inject, svg: svg, S: S, ART: ART, ART_DEFS: ART_DEFS, VIEWBOX: VIEWBOX };');
  if (exposed === src) throw new Error('icons.js no longer ends with the expected export line');
  const ctx = { window: {}, document: { getElementById: () => null } };
  vm.runInNewContext(exposed, ctx, { filename: 'icons.js' });
  return ctx.window.MV.icons;
}

// ── a small XML reader: enough for the sprite's SVG fragments ─────────────────────────────────

function parseXml(fragment) {
  const root = { tag: '#root', attrs: {}, children: [] };
  const stack = [root];
  const re = /<\s*(\/)?\s*([a-zA-Z][\w:-]*)((?:\s+[\w:-]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(\/)?\s*>|([^<]+)/g;
  let m;
  while ((m = re.exec(fragment))) {
    if (m[5] !== undefined) {
      if (m[5].trim()) stack[stack.length - 1].children.push({ tag: '#text', text: m[5] });
      continue;
    }
    const [, closing, tag, attrText, selfClose] = m;
    if (closing) {
      const top = stack.pop();
      if (!top || top.tag !== tag) throw new Error('mismatched </' + tag + '>');
      continue;
    }
    const attrs = {};
    const ar = /([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
    let a;
    while ((a = ar.exec(attrText))) attrs[a[1]] = a[2] !== undefined ? a[2] : a[3];
    const node = { tag, attrs, children: [] };
    stack[stack.length - 1].children.push(node);
    if (!selfClose) stack.push(node);
  }
  if (stack.length !== 1) throw new Error('unclosed <' + stack[stack.length - 1].tag + '>');
  return root;
}

// ── path data: re-emitted with explicit separators ───────────────────────────────────────────

/**
 * SVG allows "a1 1 0 0118 0" (the two arc flags and the next number run together) and ".5.5".
 * Android's parser does not accept every such form, so the path is tokenised by the SVG grammar
 * and written back with a space between every argument -- identical geometry, unambiguous text.
 */
function normPath(d) {
  const ARGS = { M: 2, L: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, T: 2, A: 7, Z: 0 };
  const out = [];
  let i = 0;
  let cmd = null;
  const sep = () => { while (i < d.length && /[\s,]/.test(d[i])) i++; };
  const num = () => {
    sep();
    const m = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?/.exec(d.slice(i));
    if (!m) throw new Error('bad number in path at ' + i + ': ' + d.slice(i, i + 24));
    i += m[0].length;
    return fmt(parseFloat(m[0]));
  };
  const flag = () => {
    sep();
    const c = d[i];
    if (c !== '0' && c !== '1') throw new Error('bad arc flag at ' + i);
    i++;
    return c;
  };
  for (;;) {
    sep();
    if (i >= d.length) break;
    if (/[MmZzLlHhVvCcSsQqTtAa]/.test(d[i])) cmd = d[i++];
    else if (!cmd) throw new Error('path does not start with a command');
    const U = cmd.toUpperCase();
    if (U === 'Z') { out.push(cmd); cmd = null; continue; }
    const args = [];
    for (let k = 0; k < ARGS[U]; k++) args.push(U === 'A' && (k === 3 || k === 4) ? flag() : num());
    out.push(cmd + args.join(' '));
    if (U === 'M') cmd = cmd === 'M' ? 'L' : 'l';
  }
  return out.join(' ');
}

function fmt(n) {
  const r = Math.round(n * 10000) / 10000;
  return (Object.is(r, -0) ? 0 : r).toString();
}

function rectPath(a) {
  const x = +(a.x || 0), y = +(a.y || 0), w = +a.width, h = +a.height;
  let rx = a.rx !== undefined ? +a.rx : (a.ry !== undefined ? +a.ry : 0);
  let ry = a.ry !== undefined ? +a.ry : rx;
  rx = Math.min(rx, w / 2); ry = Math.min(ry, h / 2);
  if (!rx || !ry) return `M${fmt(x)} ${fmt(y)} H${fmt(x + w)} V${fmt(y + h)} H${fmt(x)} Z`;
  return [
    `M${fmt(x + rx)} ${fmt(y)}`, `H${fmt(x + w - rx)}`,
    `A${fmt(rx)} ${fmt(ry)} 0 0 1 ${fmt(x + w)} ${fmt(y + ry)}`, `V${fmt(y + h - ry)}`,
    `A${fmt(rx)} ${fmt(ry)} 0 0 1 ${fmt(x + w - rx)} ${fmt(y + h)}`, `H${fmt(x + rx)}`,
    `A${fmt(rx)} ${fmt(ry)} 0 0 1 ${fmt(x)} ${fmt(y + h - ry)}`, `V${fmt(y + ry)}`,
    `A${fmt(rx)} ${fmt(ry)} 0 0 1 ${fmt(x + rx)} ${fmt(y)}`, 'Z',
  ].join(' ');
}

function circlePath(a) {
  const cx = +a.cx, cy = +a.cy, r = +a.r;
  return `M${fmt(cx - r)} ${fmt(cy)} A${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(cx + r)} ${fmt(cy)} ` +
    `A${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(cx - r)} ${fmt(cy)} Z`;
}

// ── colours and transforms ────────────────────────────────────────────────────────────────────

function color(value, glyph) {
  if (value === undefined || value === 'none') return null;
  if (value === 'currentColor') return '#FFFFFFFF';
  if (/^url\(/.test(value)) throw new Error('gradient fills are not supported here: ' + value);
  let m = /^#([0-9a-f]{3})$/i.exec(value);
  if (m) value = '#' + m[1].split('').map((c) => c + c).join('');
  m = /^#([0-9a-f]{6})$/i.exec(value);
  if (m) return '#FF' + m[1].toUpperCase();
  const named = { white: '#FFFFFFFF', black: '#FF000000' };
  if (named[value]) return named[value];
  throw new Error('unsupported colour: ' + value + (glyph ? ' (glyph)' : ''));
}

function transforms(value) {
  if (!value) return [];
  const out = [];
  const re = /(translate|scale|rotate|matrix|skewX|skewY)\s*\(([^)]*)\)/g;
  let m;
  while ((m = re.exec(value))) {
    const nums = m[2].trim().split(/[\s,]+/).filter(Boolean).map(Number);
    if (m[1] === 'translate') out.push({ tx: nums[0] || 0, ty: nums[1] || 0 });
    else if (m[1] === 'scale') out.push({ sx: nums[0], sy: nums.length > 1 ? nums[1] : nums[0] });
    else if (m[1] === 'rotate' && nums.length === 1) out.push({ rot: nums[0] });
    else throw new Error('unsupported transform: ' + m[0]);
  }
  return out;
}

function groupOpen(t, pad) {
  const a = [];
  if (t.tx !== undefined) a.push(`android:translateX="${fmt(t.tx)}"`, `android:translateY="${fmt(t.ty)}"`);
  if (t.sx !== undefined) a.push(`android:scaleX="${fmt(t.sx)}"`, `android:scaleY="${fmt(t.sy)}"`);
  if (t.rot !== undefined) a.push(`android:rotation="${fmt(t.rot)}"`);
  return `${pad}<group ${a.join(' ')}>`;
}

// ── SVG -> VectorDrawable elements ────────────────────────────────────────────────────────────

const INHERITED = ['fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'fill-rule', 'opacity'];

function convert(node, inherited, lines, depth, glyph) {
  const pad = '    '.repeat(depth);
  const style = Object.assign({}, inherited);
  for (const k of INHERITED) {
    if (node.attrs[k] === undefined) continue;
    // Opacity multiplies down the tree; everything else is simply overridden.
    style[k] = k === 'opacity' ? String((+(style.opacity || 1)) * +node.attrs[k]) : node.attrs[k];
  }
  const tf = transforms(node.attrs.transform);
  tf.forEach((t, i) => lines.push(groupOpen(t, '    '.repeat(depth + i))));
  const inner = depth + tf.length;
  const ipad = '    '.repeat(inner);

  switch (node.tag) {
    case '#root':
    case 'g':
      node.children.forEach((c) => convert(c, style, lines, inner, glyph));
      break;
    case 'path':
    case 'rect':
    case 'circle': {
      const d = node.tag === 'path' ? normPath(node.attrs.d) :
        node.tag === 'rect' ? normPath(rectPath(node.attrs)) : normPath(circlePath(node.attrs));
      const a = [`android:pathData="${d}"`];
      const fill = color(style.fill === undefined ? '#000000' : style.fill, glyph);
      const stroke = color(style.stroke, glyph);
      const alpha = style.opacity !== undefined ? +style.opacity : 1;
      if (fill) {
        a.push(`android:fillColor="${fill}"`);
        if (alpha < 1) a.push(`android:fillAlpha="${fmt(alpha)}"`);
        if (style['fill-rule'] === 'evenodd') a.push('android:fillType="evenOdd"');
      }
      if (stroke) {
        a.push(`android:strokeColor="${stroke}"`, `android:strokeWidth="${fmt(+(style['stroke-width'] || 1))}"`);
        if (alpha < 1) a.push(`android:strokeAlpha="${fmt(alpha)}"`);
        if (style['stroke-linecap']) a.push(`android:strokeLineCap="${style['stroke-linecap']}"`);
        if (style['stroke-linejoin']) a.push(`android:strokeLineJoin="${style['stroke-linejoin']}"`);
      }
      if (!fill && !stroke) break;
      lines.push(`${ipad}<path\n${ipad}    ${a.join(`\n${ipad}    `)} />`);
      break;
    }
    case '#text':
      break;
    default:
      throw new Error('unsupported element <' + node.tag + '>');
  }
  for (let i = tf.length - 1; i >= 0; i--) lines.push('    '.repeat(depth + i) + '</group>');
}

function viewBox(vb) {
  const [x, y, w, h] = vb.trim().split(/[\s,]+/).map(Number);
  return { x, y, w, h };
}

// ── writers ───────────────────────────────────────────────────────────────────────────────────

function header(what) {
  return '<?xml version="1.0" encoding="utf-8"?>\n' +
    '<!--\n' +
    `    ${what}\n` +
    '    Generated by scripts/icons-from-windows.js from the Windows app\'s icon sprite\n' +
    '    (public/shell/icons.js), which is the approved set. Do not edit by hand: change the desktop\n' +
    '    icon and re-run the script, so the two apps can never drift apart.\n' +
    '-->\n';
}

function artDrawable(name, spec, sprite) {
  const art = sprite.ART[spec.id];
  if (!art) throw new Error('no artwork ' + spec.id + ' in icons.js');
  const vb = viewBox(art.viewBox);
  const scale = spec.scale || 1;
  // The desktop sizes the art as a square box inside the tile; a slightly non-square viewBox
  // (wireguard's is 300.004 x 300) is fitted by its larger side and centred, as SVG's default
  // preserveAspectRatio does.
  const box = TILE_SIZE * scale;
  const k = box / Math.max(vb.w, vb.h);
  const tx = TILE_MIN + (TILE_SIZE - vb.w * k) / 2 - vb.x * k;
  const ty = TILE_MIN + (TILE_SIZE - vb.h * k) / 2 - vb.y * k;

  const body = [];
  convert(parseXml(art.body), {}, body, 3, false);

  if (spec.kind === 'image') {
    // Its own ground, its own squircle: no tile behind it and no clip, only the viewBox fitted to
    // the 1024 box the other tiles use (centred by its larger side, as SVG does).
    const s = 1024 / Math.max(vb.w, vb.h);
    const ix = (1024 - vb.w * s) / 2 - vb.x * s;
    const iy = (1024 - vb.h * s) / 2 - vb.y * s;
    return header(`Home tile «${spec.app}» — desktop artwork ${spec.id}, drawn as is (it carries its own ground).`) +
      '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n' +
      '    android:width="108dp"\n    android:height="108dp"\n' +
      '    android:viewportWidth="1024"\n    android:viewportHeight="1024">\n' +
      '    <group>\n' +
      `        <group android:translateX="${fmt(ix)}" android:translateY="${fmt(iy)}" android:scaleX="${fmt(s)}" android:scaleY="${fmt(s)}">\n` +
      body.join('\n') + '\n' +
      '        </group>\n' +
      '    </group>\n' +
      '</vector>\n';
  }

  const ground = spec.kind === 'white'
    ? [
      `    <path android:pathData="${SQUIRCLE}">`,
      '        <aapt:attr name="android:fillColor">',
      `            <gradient android:type="linear" android:startX="512" android:startY="${TILE_MIN}" android:endX="512" android:endY="${fmt(TILE_MIN + TILE_SIZE)}">`,
      '                <item android:offset="0" android:color="#FFFFFFFF" />',
      '                <item android:offset="1" android:color="#FFECECF0" />',
      '            </gradient>',
      '        </aapt:attr>',
      '    </path>',
    ]
    : [`    <path android:pathData="${SQUIRCLE}" android:fillColor="${color(spec.bg)}" />`];

  return header(`Home tile «${spec.app}» — desktop artwork ${spec.id}, ${spec.kind} tile` +
      (spec.bg ? ` on ${spec.bg}` : '') + (spec.scale ? ` at ${Math.round(spec.scale * 100)}%` : '') + '.') +
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n' +
    (spec.kind === 'white' ? '    xmlns:aapt="http://schemas.android.com/aapt"\n' : '') +
    '    android:width="108dp"\n    android:height="108dp"\n' +
    '    android:viewportWidth="1024"\n    android:viewportHeight="1024">\n' +
    ground.join('\n') + '\n' +
    '    <group>\n' +
    `        <clip-path android:pathData="${SQUIRCLE}" />\n` +
    `        <group android:translateX="${fmt(tx)}" android:translateY="${fmt(ty)}" android:scaleX="${fmt(k)}" android:scaleY="${fmt(k)}">\n` +
    body.join('\n') + '\n' +
    '        </group>\n' +
    '    </group>\n' +
    '</vector>\n';
}

function glyphDrawable(name, id, sprite) {
  const body0 = sprite.S[id];
  if (!body0) throw new Error('no glyph ' + id + ' in icons.js');
  const vb = viewBox((sprite.VIEWBOX && sprite.VIEWBOX[id]) || '0 0 24 24');
  const body = [];
  convert(parseXml(body0), {}, body, 1, true);
  return header(`Glyph ${id}, white; the tile behind it is drawn by AppIcon in the destination's tint.`) +
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n' +
    '    android:width="24dp"\n    android:height="24dp"\n' +
    `    android:viewportWidth="${fmt(vb.w)}"\n    android:viewportHeight="${fmt(vb.h)}">\n` +
    (vb.x || vb.y ? `    <group android:translateX="${fmt(-vb.x)}" android:translateY="${fmt(-vb.y)}">\n` : '') +
    body.join('\n') + '\n' +
    (vb.x || vb.y ? '    </group>\n' : '') +
    '</vector>\n';
}

function main() {
  const check = process.argv.includes('--check');
  const sprite = loadSprite();
  const outputs = {};
  for (const [name, spec] of Object.entries(ART)) outputs[name] = artDrawable(name, spec, sprite);
  for (const [name, id] of Object.entries(GLYPHS)) outputs[name] = glyphDrawable(name, id, sprite);

  let stale = 0;
  for (const [name, text] of Object.entries(outputs)) {
    const file = path.join(OUT, name + '.xml');
    const current = fs.existsSync(file) ? fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n') : null;
    if (current === text) continue;
    if (check) { console.log('out of date: ' + name + '.xml'); stale++; continue; }
    fs.writeFileSync(file, text);
    console.log('wrote ' + path.relative(ROOT, file));
  }
  if (check && stale) process.exit(1);
  if (!check) console.log(Object.keys(outputs).length + ' drawables in sync with ' + path.relative(ROOT, WINDOWS_ICONS));
}

main();
