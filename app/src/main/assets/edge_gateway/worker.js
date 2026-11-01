// MLMVPN — Edge Gateway Worker. Written for this app; shares no code with the panel Workers.
//
// What it is: a RELAY and nothing else. The app opens a WebSocket here, sends a VLESS request
// header (user id, destination host and port) and then the stream; the Worker dials that
// destination with connect() and copies bytes both ways. There is no panel, no subscription, no
// user database and no API token in it — the app builds its own configs and keeps its own state.
//
// Configuration comes from the Worker's bindings (see config.js); the code carries no secret and
// is identical on every account. Anything that is not an authorised tunnel gets a small JSON
// document, which is also what a health check reads.
//
// Limits it does not hide:
//  - TCP, plus UDP/53 carried as DNS-over-HTTPS; every other UDP is refused (a Worker has no UDP);
//  - connect() cannot reach Cloudflare-hosted destinations — that is what FALLBACK is for;
//  - every tunnel costs a Worker request from the account's quota.

import { connect } from 'cloudflare:sockets';
import { createGateway } from './gateway.js';

const gateway = createGateway({ connect });

export default {
  fetch(request, env) {
    return gateway.fetch(request, env);
  },
};
