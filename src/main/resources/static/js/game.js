// Shared helpers for the board, host and player screens.
const Jeo = (() => {
  const esc = s => String(s ?? '').replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  const money = n => {
    const v = Number(n) || 0;
    return `<span class="money${v < 0 ? ' neg' : ''}">${v < 0 ? '-' : ''}$${Math.abs(v).toLocaleString()}</span>`;
  };

  const params = () => new URLSearchParams(location.search);

  let toastTimer;
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
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      ws = new WebSocket(`${proto}://${location.host}/ws?${query()}`);
      ws.onopen = () => { retry = 0; handlers.onStatus && handlers.onStatus(true); };
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
      ws.onclose = () => {
        handlers.onStatus && handlers.onStatus(false);
        if (!stopped) setTimeout(open, Math.min(10000, 500 * 2 ** retry++));
      };
    }

    function send(action, data = {}, quiet = false) {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action, ...data }));
        return true;
      }
      if (!quiet) toast('Reconnecting… try again in a moment');
      return false;
    }

    open();
    setInterval(() => send('ping', {}, true), 25000);
    return { send, reconnect: () => ws && ws.close() };
  }

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

  const playerUrl = code => `${location.origin}/play.html?game=${encodeURIComponent(code)}`;

  return { esc, money, params, toast, connect, teamById, teamLabel, playerUrl };
})();
