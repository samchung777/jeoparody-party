// Shared helpers for the board, host and player screens.
// Loaded by host.html, board.html and play.html. Plain JavaScript, no framework or build step: the server just
// serves these files from src/main/resources/static, so there is nothing extra to build in CI.
// Jeo is a module pattern (an immediately-invoked function) that exposes a few helpers without globals clutter.
const Jeo = (() => {
  // Escapes text before putting it into HTML. Team names and answers come from players, so this prevents
  // HTML/script injection (XSS) on every screen.
  const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // Formats a score like $1,200 (negative scores styled red by game.css).
  const money = n => {
    const v = Number(n) || 0;
    return `<span class="money${v < 0 ? ' neg' : ''}">${v < 0 ? '-' : ''}$${Math.abs(v).toLocaleString()}</span>`;
  };

  // Reads ?game=...&key=... from the page URL (room code and host key live in the links).
  const params = () => new URLSearchParams(location.search);

  let toastTimer;
  // Small pop-up message at the bottom of the screen; server errors ('Can't do that during board') show here.
  function toast(message) {
    let el = document.querySelector('.toast');
    if (!el) {
      el = document.createElement('div');
      el.className = 'toast';
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.add('hidden'), 3500);
  }

  /**
   * Opens the game WebSocket and keeps it open (reconnects with back-off, pings to stay alive).
   * query: function returning the query string, so it can change (e.g. after a player joins).
   */
  function connect(query, handlers) {
    let ws;
    let retry = 0;
    let stopped = false;

    function open() {
      // wss:// on the live https:// site, ws:// on http://localhost. Same host as the page, so the server's
      // same-origin check passes (behind Cloud Run that needs the forwarded-headers setting).
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      ws = new WebSocket(`${proto}://${location.host}/ws?${query()}`);
      ws.onopen = () => { retry = 0; handlers.onStatus && handlers.onStatus(true); };
      // Every server message is JSON with a type: 'state' (redraw the screen), 'error' (toast; fatal ones stop
      // reconnecting, e.g. a bad host link), or something page-specific (joined, searchResults, ...).
      ws.onmessage = ev => {
        let msg;
        try { msg = JSON.parse(ev.data); } catch { return; }
        if (msg.type === 'state') {
          handlers.onState(msg.state);
        } else if (msg.type === 'error') {
          toast(msg.message);
          if (msg.fatal) {
            stopped = true;
            handlers.onFatal && handlers.onFatal(msg.message);
          }
        } else if (handlers.onMessage) {
          handlers.onMessage(msg);
        }
      };
      // Lost connection (phone sleep, Wi-Fi switch, deploy): reconnect with exponential back-off, 0.5 s doubling to 10 s.
      ws.onclose = () => {
        handlers.onStatus && handlers.onStatus(false);
        if (!stopped) setTimeout(open, Math.min(10000, 500 * 2 ** retry++));
      };
    }

    // Sends {action, ...data} to GameSocketHandler. Returns false (and tells the user) if the socket isn't open.
    function send(action, data = {}, quiet = false) {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action, ...data }));
        return true;
      }
      if (!quiet) toast('Reconnecting… try again in a moment');
      return false;
    }

    open();
    // Keep-alive: a quiet ping every 25 s stops idle connections being closed by proxies.
    setInterval(() => send('ping', {}, true), 25000);
    return { send, reconnect: () => ws && ws.close() };
  }

  // Finds a team in the latest state by id.
  const teamById = (state, id) => (state.teams || []).find(t => t.id === id);

  /** A team's drawn name as an image, or its typed name. */
  function teamLabel(team, { big = false } = {}) {
    if (!team) return '';
    if (team.drawing) {
      return `<img class="team-name-img" src="${esc(team.drawing)}" alt="${esc(team.name)}" title="${esc(team.name)}"
                   style="${big ? '' : 'max-height:60px;object-fit:contain'}">`;
    }
    return `<span class="title-font">${esc(team.name)}</span>`;
  }

  // The join link shown on the host screen and TV board.
  const playerUrl = code => `${location.origin}/play.html?game=${encodeURIComponent(code)}`;

  return { esc, money, params, toast, connect, teamById, teamLabel, playerUrl };
})();
