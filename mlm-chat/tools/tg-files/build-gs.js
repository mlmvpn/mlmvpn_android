// Builds mlm-chat/relay/tgfiles/TgFiles.gs, the one file the fetcher's owner pastes into a Google
// Apps Script project: tgcore.js, aes.js and gas-main.js together. Edit those, then run
//   node tools/tg-files/build-gs.js        (from mlm-chat)
"use strict";
const fs = require("fs");
const path = require("path");

const here = __dirname;
const out = path.join(here, "..", "..", "relay", "tgfiles", "TgFiles.gs");
const strip = (s) => s.replace(/\r/g, "").replace(/\nif \(typeof module !== "undefined"\) \{[\s\S]*?\n\}\n*$/, "\n");
const head = `// Gereh: the Telegram file fetcher (T71). Google Apps Script, in its owner's own Google account.
// Paste this whole file into Code.gs of a new project, then:
//  1. Project Settings > Script Properties > Add script property: TG_SESSION, and as its value the
//     session (on the computer: node tgfiles.js copy, then paste). It acts as the fetcher's
//     Telegram account: show it to nobody.
//  2. Deploy > New deployment > Web app; Execute as: Me; Who has access: Anyone > Deploy; allow
//     "connect to an external service". The Deployment ID goes into the app.
// Built by mlm-chat/tools/tg-files/build-gs.js from tgcore.js, aes.js and gas-main.js: edit those.
`;
const body = ["tgcore.js", "aes.js", "gas-main.js"].map((f) => strip(fs.readFileSync(path.join(here, f), "utf8"))).join("\n");
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, head + "\n" + body);
console.log("wrote " + out + " (" + Buffer.byteLength(head + body) + " bytes)");
