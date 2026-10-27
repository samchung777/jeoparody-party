// A simple finger/mouse drawing pad that exports a small PNG data URL.
// Used for team names (play.html when joining) and Final Jeopardy answers. Strokes are kept as lists of points
// so Undo can redraw without the last stroke. The result is sent to the server as a PNG data URL, which is why
// WebSocketConfig raises the message size limit and Game caps drawing size.
class DrawPad {
  constructor(container, { width = 480, height = 240, lineWidth = 7, color = '#000' } = {}) {
    this.container = container;
    this.lineWidth = lineWidth;
    this.color = color;
    container.classList.add('drawpad');
    container.innerHTML = `
      <canvas width="${width}" height="${height}"></canvas>
      <div class="tools">
        <button type="button" class="secondary small" data-act="undo">Undo</button>
        <button type="button" class="secondary small" data-act="clear">Clear</button>
      </div>`;
    this.canvas = container.querySelector('canvas');
    this.ctx = this.canvas.getContext('2d');
    this.strokes = [];
    this.current = null;
    container.querySelector('[data-act=clear]').addEventListener('click', () => this.clear());
    container.querySelector('[data-act=undo]').addEventListener('click', () => { this.strokes.pop(); this.redraw(); });

    // Converts a pointer position to canvas pixels (the canvas is scaled to fit the phone screen).
    const point = e => {
      const r = this.canvas.getBoundingClientRect();
      return [(e.clientX - r.left) * this.canvas.width / r.width, (e.clientY - r.top) * this.canvas.height / r.height];
    };
    // Pointer events cover mouse, touch and pen with one API. setPointerCapture keeps the stroke going if the
    // finger slides off the canvas; preventDefault stops the page scrolling while drawing.
    this.canvas.addEventListener('pointerdown', e => {
      e.preventDefault();
      this.canvas.setPointerCapture(e.pointerId);
      this.current = [point(e)];
      this.strokes.push(this.current);
      this.redraw();
    });
    this.canvas.addEventListener('pointermove', e => {
      if (!this.current) return;
      e.preventDefault();
      this.current.push(point(e));
      this.redraw();
    });
    const end = () => { this.current = null; };
    this.canvas.addEventListener('pointerup', end);
    this.canvas.addEventListener('pointercancel', end);
    this.redraw();
  }

  // Repaints white background and every stroke; a single tap draws a dot.
  redraw() {
    const { ctx, canvas } = this;
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = this.color;
    ctx.fillStyle = this.color;
    ctx.lineWidth = this.lineWidth;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    for (const stroke of this.strokes) {
      if (stroke.length === 1) {
        ctx.beginPath();
        ctx.arc(stroke[0][0], stroke[0][1], this.lineWidth / 2, 0, Math.PI * 2);
        ctx.fill();
        continue;
      }
      ctx.beginPath();
      ctx.moveTo(stroke[0][0], stroke[0][1]);
      for (const [x, y] of stroke.slice(1)) ctx.lineTo(x, y);
      ctx.stroke();
    }
  }

  clear() { this.strokes = []; this.redraw(); }
  isEmpty() { return this.strokes.length === 0; }
  // The drawing as a 'data:image/png;base64,...' string: what join and finalAnswer send.
  toDataURL() { return this.canvas.toDataURL('image/png'); }
}
