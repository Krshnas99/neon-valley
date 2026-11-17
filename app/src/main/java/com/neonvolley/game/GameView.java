package com.neonvolley.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * Neon Volley: drag to aim, release to fire a volley of balls at numbered bricks.
 * Every round the bricks move down one row. Collect green rings for extra balls.
 * The game ends when a brick reaches the floor.
 */
public class GameView extends View {
    private static final int BG = 0xFF0A0E1F, FIELD = 0xFF111836, INK = 0xFF0A0E1F, WHITE = 0xFFFFFFFF,
            CYAN = 0xFF4DE8FF, GOLD = 0xFFFFD54A, RED = 0xFFFF4D6D, GREEN = 0xFF5CFF9D, DIM = 0xFF8A93B8;
    private static final int COLS = 7, MAXB = 300, MAXQ = 220, MAXBEAM = 8;
    private static final int EMPTY = 0, BRICK = 1, PLUS = 2, LASER_H = 3, LASER_V = 4;
    private static final int TITLE = 0, AIM = 1, FLY = 2, SHIFT = 3, OVER = 4;
    private static final int WAIT = 0, FLYING = 1, SLIDE = 2, HOME = 3;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final SharedPreferences prefs;
    private final Sfx sfx = new Sfx();

    // layout
    private float w, h, u, cell, fx, fy, floorY, rad, speed;
    private int rows;
    // board
    private int[][] kind, hp;
    private float[][] flash;
    private boolean[][] used;
    // game
    private int state = TITLE, level = 1, balls = 1, best, pending;
    private float launchFrac = 0.5f, nextFrac = 0.5f;
    private boolean landedAny, boost, newBest;
    private int launched;
    private float shotTimer, flyClock, shiftT = 1, overT, clock, shake, ballPop;
    private float dirX, dirY;
    private long last, lastBuzz;
    // balls
    private final float[] bx = new float[MAXB], by = new float[MAXB], bvx = new float[MAXB], bvy = new float[MAXB];
    private final int[] bs = new int[MAXB], bl = new int[MAXB];
    // aiming
    private boolean aiming, aimOk;
    private float ax0, ay0, aimX, aimY;
    // laser beams (visual only)
    private final int[] beamKind = new int[MAXBEAM], beamIdx = new int[MAXBEAM];
    private final float[] beamLife = new float[MAXBEAM];
    // particles
    private final float[] qx = new float[MAXQ], qy = new float[MAXQ], qvx = new float[MAXQ],
            qvy = new float[MAXQ], ql = new float[MAXQ], qm = new float[MAXQ];
    private final int[] qc = new int[MAXQ];

    public GameView(Context c) {
        super(c);
        prefs = c.getSharedPreferences("neonvolley", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        sfx.muted = prefs.getBoolean("muted", false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        setKeepScreenOn(true);
    }

    public void release() { sfx.release(); }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        if (nw <= 0 || nh <= 0) return;
        w = nw;
        h = nh;
        u = w / 100f;
        float hud = 21 * u, bottom = 17 * u, avail = Math.max(h - hud - bottom, h * 0.5f);
        cell = Math.min(w / COLS, avail / 9f);
        int newRows = Math.min(10, (int) (avail / cell));
        fx = (w - cell * COLS) / 2f;
        fy = hud + Math.max(0, avail - newRows * cell) * 0.4f; // centre the board on tall screens
        floorY = fy + newRows * cell;
        rad = cell * 0.105f;
        speed = cell * 12.5f;
        if (kind == null || newRows != rows) {
            rows = newRows;
            kind = new int[rows][COLS];
            hp = new int[rows][COLS];
            flash = new float[rows][COLS];
            used = new boolean[rows][COLS];
            if (load()) {
                state = AIM;
            } else {
                newGame();
                state = TITLE;
            }
        } else if (state == FLY) {
            endTurn(); // screen changed mid-flight: finish the turn cleanly
        }
    }

    private float lx() { return fx + launchFrac * COLS * cell; }
    private float nx() { return fx + nextFrac * COLS * cell; }
    private float cellX(int c) { return fx + (c + 0.5f) * cell; }
    private float cellY(int r) { return fy + (r + 0.5f) * cell; }

    // ------------------------------------------------------------------ game flow

    private void newGame() {
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < COLS; c++) { kind[r][c] = EMPTY; hp[r][c] = 0; flash[r][c] = 0; used[r][c] = false; }
        level = 1;
        balls = 1;
        pending = 0;
        launchFrac = nextFrac = 0.5f;
        newBest = false;
        aiming = false;
        spawnRow();
        shiftT = 0;
        state = SHIFT;
        save();
    }

    /** New bricks always appear in row 1; row 0 stays free so balls can get behind them. */
    private void spawnRow() {
        int[] cols = new int[COLS];
        for (int i = 0; i < COLS; i++) cols[i] = i;
        for (int i = COLS - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1), t = cols[i];
            cols[i] = cols[j];
            cols[j] = t;
        }
        float p = Math.min(0.30f + level * 0.008f, 0.58f);
        int placed = 0;
        kind[1][cols[0]] = PLUS;
        for (int i = 1; i < COLS; i++) {
            int c = cols[i];
            if (rnd.nextFloat() < p || (i == COLS - 1 && placed == 0)) {
                kind[1][c] = BRICK;
                hp[1][c] = (level >= 3 && rnd.nextFloat() < 0.15f) ? level * 2 : level;
                placed++;
            }
        }
        if (level >= 4 && rnd.nextFloat() < 0.28f) {
            for (int i = 1; i < COLS; i++) {
                if (kind[1][cols[i]] == EMPTY) {
                    kind[1][cols[i]] = rnd.nextBoolean() ? LASER_H : LASER_V;
                    break;
                }
            }
        }
    }

    private void startShot() {
        for (int i = 0; i < balls; i++) { bs[i] = WAIT; bl[i] = -1; }
        dirX = aimX;
        dirY = aimY;
        launched = 0;
        shotTimer = 0;
        flyClock = 0;
        boost = false;
        landedAny = false;
        nextFrac = launchFrac;
        state = FLY;
    }

    private void endTurn() {
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < COLS; c++)
                if (used[r][c]) { used[r][c] = false; kind[r][c] = EMPTY; }
        if (pending > 0) ballPop = 1;
        balls = Math.min(MAXB, balls + pending);
        pending = 0;
        launchFrac = nextFrac;
        // everything moves down one row
        boolean dead = false;
        for (int r = rows - 1; r >= 1; r--)
            for (int c = 0; c < COLS; c++) {
                kind[r][c] = kind[r - 1][c];
                hp[r][c] = hp[r - 1][c];
                flash[r][c] = 0;
            }
        for (int c = 0; c < COLS; c++) {
            kind[0][c] = EMPTY;
            hp[0][c] = 0;
            if (kind[rows - 1][c] == BRICK) dead = true;
            else kind[rows - 1][c] = EMPTY; // pickups vanish at the floor
        }
        shiftT = 0;
        if (dead) {
            state = OVER;
            overT = 0;
            shake = 1;
            sfx.play(Sfx.OVER, 1f);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            prefs.edit().remove("save").apply();
            return;
        }
        level++;
        if (level > best) {
            best = level;
            newBest = true;
            prefs.edit().putInt("best", best).apply();
        }
        spawnRow();
        state = SHIFT;
        save();
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        sb.append(level).append(';').append(balls).append(';').append(launchFrac).append(';').append(rows).append(';');
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < COLS; c++) sb.append(kind[r][c] * 100000 + hp[r][c]).append(',');
        prefs.edit().putString("save", sb.toString()).apply();
    }

    private boolean load() {
        try {
            String s = prefs.getString("save", null);
            if (s == null) return false;
            String[] p = s.split(";");
            if (p.length != 5 || Integer.parseInt(p[3]) != rows) return false;
            String[] cells = p[4].split(",");
            if (cells.length < rows * COLS) return false;
            int lv = Integer.parseInt(p[0]), bn = Integer.parseInt(p[1]);
            float lf = Float.parseFloat(p[2]);
            if (lv < 1 || bn < 1 || bn > MAXB || !(lf >= 0 && lf <= 1)) return false;
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < COLS; c++) {
                    int v = Integer.parseInt(cells[r * COLS + c]);
                    int k = v / 100000;
                    if (k < EMPTY || k > LASER_V) return false;
                    kind[r][c] = k;
                    hp[r][c] = v % 100000;
                }
            level = lv;
            balls = bn;
            launchFrac = nextFrac = lf;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ simulation

    private void update(float dt) {
        clock += dt;
        if (shake > 0) shake = Math.max(0, shake - dt * 3f);
        if (ballPop > 0) ballPop = Math.max(0, ballPop - dt * 3f);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < COLS; c++)
                if (flash[r][c] > 0) flash[r][c] = Math.max(0, flash[r][c] - dt * 7f);
        for (int i = 0; i < MAXBEAM; i++) if (beamLife[i] > 0) beamLife[i] -= dt * 4f;
        for (int i = 0; i < MAXQ; i++) {
            if (ql[i] <= 0) continue;
            ql[i] -= dt;
            qx[i] += qvx[i] * dt;
            qy[i] += qvy[i] * dt;
            qvy[i] += cell * 9f * dt;
        }
        if (shiftT < 1) shiftT = Math.min(1, shiftT + dt * 4.5f);
        if (state == SHIFT && shiftT >= 1) state = AIM;
        else if (state == FLY) fly(dt);
        else if (state == OVER) overT += dt;
    }

    private float timeScale() {
        if (boost) return 3f;
        float k = (flyClock - 4f) / 4f;
        return 1f + 2f * Math.max(0, Math.min(1, k));
    }

    private void fly(float dt) {
        flyClock += dt;
        float sdt = dt * timeScale();
        shotTimer -= sdt;
        while (launched < balls && shotTimer <= 0) {
            int i = launched++;
            bx[i] = lx();
            by[i] = floorY - rad;
            bvx[i] = dirX * speed;
            bvy[i] = dirY * speed;
            bs[i] = FLYING;
            shotTimer += 0.065f;
            sfx.play(Sfx.SHOT, 1f);
        }
        int steps = Math.max(1, (int) Math.ceil(speed * 1.6f * sdt / (rad * 0.8f)));
        float hstep = sdt / steps;
        boolean gravity = flyClock > 25f; // safety net: nothing can bounce forever
        for (int s = 0; s < steps; s++)
            for (int i = 0; i < launched; i++)
                if (bs[i] == FLYING) {
                    if (gravity) bvy[i] = Math.min(bvy[i] + speed * 1.5f * hstep, speed * 1.5f);
                    stepBall(i, hstep);
                }
        boolean done = launched == balls;
        float target = nx(), slide = cell * 14f * sdt;
        for (int i = 0; i < launched; i++) {
            if (bs[i] == SLIDE) {
                float d = target - bx[i];
                if (Math.abs(d) <= slide) { bx[i] = target; bs[i] = HOME; }
                else bx[i] += d > 0 ? slide : -slide;
            }
            if (bs[i] != HOME) done = false;
        }
        if (done) endTurn();
    }

    private void stepBall(int i, float t) {
        float x = bx[i] + bvx[i] * t, y = by[i] + bvy[i] * t, vx = bvx[i], vy = bvy[i];
        float left = fx + rad, right = fx + COLS * cell - rad, top = fy + rad;
        boolean bounced = false;
        if (x < left) { x = left; vx = Math.abs(vx); bounced = true; }
        else if (x > right) { x = right; vx = -Math.abs(vx); bounced = true; }
        if (y < top) { y = top; vy = Math.abs(vy); bounced = true; }
        if (y >= floorY - rad && vy > 0) {
            bx[i] = Math.max(left, Math.min(right, x));
            by[i] = floorY - rad;
            if (!landedAny) {
                landedAny = true;
                nextFrac = (bx[i] - fx) / (COLS * cell);
                bs[i] = HOME;
            } else {
                bs[i] = SLIDE;
            }
            return;
        }
        int c0 = clampI((int) Math.floor((x - rad - fx) / cell), COLS - 1);
        int c1 = clampI((int) Math.floor((x + rad - fx) / cell), COLS - 1);
        int r0 = clampI((int) Math.floor((y - rad - fy) / cell), rows - 1);
        int r1 = clampI((int) Math.floor((y + rad - fy) / cell), rows - 1);
        boolean onLaser = false;
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                int k = kind[r][c];
                if (k == EMPTY) continue;
                float mx = cellX(c), my = cellY(r);
                if (k == BRICK) {
                    float half = cell * 0.46f;
                    float px = Math.max(mx - half, Math.min(mx + half, x));
                    float py = Math.max(my - half, Math.min(my + half, y));
                    float dx = x - px, dy = y - py, d2 = dx * dx + dy * dy;
                    if (d2 >= rad * rad) continue;
                    float nx, ny;
                    if (d2 > 1e-6f) {
                        float d = (float) Math.sqrt(d2);
                        nx = dx / d;
                        ny = dy / d;
                    } else { // centre is inside the brick: leave through the nearest face
                        float ox = half - Math.abs(x - mx), oy = half - Math.abs(y - my);
                        if (ox < oy) { nx = x < mx ? -1 : 1; ny = 0; px = mx + nx * half; py = y; }
                        else { nx = 0; ny = y < my ? -1 : 1; px = x; py = my + ny * half; }
                    }
                    x = px + nx * rad;
                    y = py + ny * rad;
                    float dot = vx * nx + vy * ny;
                    if (dot < 0) { vx -= 2 * dot * nx; vy -= 2 * dot * ny; }
                    bounced = true;
                    hitBrick(r, c);
                } else {
                    float dx = x - mx, dy = y - my, reach = rad + cell * 0.2f;
                    if (dx * dx + dy * dy >= reach * reach) continue;
                    if (k == PLUS) {
                        kind[r][c] = EMPTY;
                        pending++;
                        burst(mx, my, GREEN, 10, 5f);
                        sfx.play(Sfx.PICK, 1f);
                    } else {
                        onLaser = true;
                        int id = r * COLS + c;
                        if (bl[i] != id) { bl[i] = id; fireLaser(r, c, k); }
                    }
                }
            }
        }
        if (!onLaser) bl[i] = -1;
        if (bounced) { // never let a ball travel almost flat: it would take forever to come down
            float minV = speed * 0.12f;
            if (Math.abs(vy) < minV) {
                vy = vy < 0 ? -minV : minV;
                float s = (float) Math.sqrt(speed * speed - vy * vy);
                vx = vx < 0 ? -s : s;
            }
        }
        bx[i] = x;
        by[i] = y;
        bvx[i] = vx;
        bvy[i] = vy;
    }

    private static int clampI(int v, int max) { return v < 0 ? 0 : (v > max ? max : v); }

    private void hitBrick(int r, int c) {
        if (kind[r][c] != BRICK) return;
        flash[r][c] = 1;
        if (--hp[r][c] > 0) {
            sfx.play(Sfx.HIT, 0.85f + 0.5f * (rows - r) / rows);
            return;
        }
        kind[r][c] = EMPTY;
        hp[r][c] = 0;
        burst(cellX(c), cellY(r), brickColor(1), 12, 7f);
        shake = Math.max(shake, 0.35f);
        sfx.play(Sfx.BREAK, 0.9f + rnd.nextFloat() * 0.3f);
        long now = System.nanoTime();
        if (now - lastBuzz > 120000000L) {
            lastBuzz = now;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    private void fireLaser(int r, int c, int k) {
        used[r][c] = true;
        flash[r][c] = 1;
        for (int i = 0; i < MAXBEAM; i++) {
            if (beamLife[i] > 0) continue;
            beamKind[i] = k;
            beamIdx[i] = k == LASER_H ? r : c;
            beamLife[i] = 1;
            break;
        }
        if (k == LASER_H) for (int cc = 0; cc < COLS; cc++) hitBrick(r, cc);
        else for (int rr = 0; rr < rows; rr++) hitBrick(rr, c);
        sfx.play(Sfx.LASER, 1f);
    }

    private void burst(float x, float y, int color, int n, float power) {
        for (int i = 0; i < MAXQ && n > 0; i++) {
            if (ql[i] > 0) continue;
            double a = rnd.nextFloat() * 2 * Math.PI;
            float s = (0.3f + rnd.nextFloat()) * power * cell;
            qx[i] = x;
            qy[i] = y;
            qvx[i] = (float) Math.cos(a) * s;
            qvy[i] = (float) Math.sin(a) * s - cell * 2f;
            qm[i] = ql[i] = 0.35f + rnd.nextFloat() * 0.4f;
            qc[i] = color;
            n--;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (kind == null) return true;
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.abs(x - w / 2f) < 8 * u && y < 20 * u) { // sound button
                    sfx.muted = !sfx.muted;
                    prefs.edit().putBoolean("muted", sfx.muted).apply();
                    sfx.play(Sfx.PICK, 1f);
                } else if (state == TITLE) {
                    state = AIM;
                } else if (state == OVER) {
                    if (overT > 0.8f) newGame();
                } else if (state == FLY) {
                    boost = true; // tap during a volley to fast-forward
                } else {
                    aiming = true;
                    aimOk = false;
                    ax0 = x;
                    ay0 = y;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (aiming) {
                    // drag in either direction along the line you want: the shot always goes upward
                    float vx = ax0 - x, vy = ay0 - y;
                    float len = (float) Math.sqrt(vx * vx + vy * vy);
                    if (len < 5 * u) {
                        aimOk = false;
                    } else {
                        if (vy > 0) { vx = -vx; vy = -vy; }
                        vx /= len;
                        vy /= len;
                        if (vy > -0.12f) { // keep at least ~7 degrees above the floor
                            vy = -0.12f;
                            vx = (vx < 0 ? -1 : 1) * (float) Math.sqrt(1 - 0.12f * 0.12f);
                        }
                        aimX = vx;
                        aimY = vy;
                        aimOk = true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (aiming && aimOk && (state == AIM || state == SHIFT)) {
                    shiftT = 1;
                    startShot();
                }
                aiming = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                aiming = false;
                break;
            default:
                break;
        }
        return true;
    }

    // ------------------------------------------------------------------ drawing

    private static int alpha(int color, float a) {
        if (a < 0) a = 0;
        if (a > 1) a = 1;
        return (color & 0x00FFFFFF) | ((int) (a * 255) << 24);
    }

    private static int hsv(float hue, float s, float v) {
        hue = ((hue % 360f) + 360f) % 360f;
        float c = v * s, hp = hue / 60f, x = c * (1 - Math.abs(hp % 2 - 1)), m = v - c, r, g, b;
        switch ((int) hp) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return 0xFF000000 | ((int) ((r + m) * 255) << 16) | ((int) ((g + m) * 255) << 8) | (int) ((b + m) * 255);
    }

    /** Colour shifts with remaining hits, so a brick visibly changes as it is worn down. */
    private static int brickColor(int hits) { return hsv(172 + hits * 13, 0.60f, 1f); }

    private void label(Canvas c, String s, float x, float yMid, float size, int color, Paint.Align align) {
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setColor(color);
        c.drawText(s, x, yMid - (text.ascent() + text.descent()) / 2f, text);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0 : (now - last) / 1e9f;
        last = now;
        if (dt > 0.033f) dt = 0.033f; // also swallows the gap after the app was in the background
        c.drawColor(BG);
        if (kind == null) { postInvalidateOnAnimation(); return; }
        update(dt);

        c.save();
        if (shake > 0)
            c.translate((rnd.nextFloat() - 0.5f) * shake * 3 * u, (rnd.nextFloat() - 0.5f) * shake * 3 * u);
        drawBoard(c);
        if (aiming && aimOk && state != FLY) drawAim(c);
        drawBalls(c);
        for (int i = 0; i < MAXQ; i++) {
            if (ql[i] <= 0) continue;
            float f = ql[i] / qm[i], s = cell * 0.07f * f + cell * 0.02f;
            fill.setColor(alpha(qc[i], f));
            c.drawRect(qx[i] - s, qy[i] - s, qx[i] + s, qy[i] + s, fill);
        }
        c.restore();

        drawHud(c);
        if (state == TITLE) drawTitle(c);
        else if (state == OVER) drawOver(c);
        postInvalidateOnAnimation();
    }

    private void drawBoard(Canvas c) {
        float fw = COLS * cell, round = cell * 0.14f;
        fill.setColor(FIELD);
        c.drawRoundRect(fx, fy, fx + fw, floorY, round, round, fill);
        fill.setColor(alpha(WHITE, 0.07f));
        for (int r = 1; r < rows; r++)
            for (int col = 1; col < COLS; col++) c.drawCircle(fx + col * cell, fy + r * cell, cell * 0.018f, fill);

        // danger: bricks one step away from the floor
        boolean danger = false;
        for (int col = 0; col < COLS; col++) if (kind[rows - 2][col] == BRICK) danger = true;
        float pulse = 0.5f + 0.5f * (float) Math.sin(clock * 7f);
        if (danger && state != OVER) {
            fill.setColor(alpha(RED, 0.06f + 0.10f * pulse));
            c.drawRoundRect(fx, floorY - cell, fx + fw, floorY, round, round, fill);
        }
        stroke.setStrokeWidth(cell * 0.03f);
        stroke.setColor(danger ? alpha(RED, 0.5f + 0.5f * pulse) : alpha(DIM, 0.5f));
        c.drawLine(fx + round, floorY, fx + fw - round, floorY, stroke);

        float ease = 1 - (1 - shiftT) * (1 - shiftT);
        float oy = -cell * (1 - ease);
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < COLS; col++) {
                int k = kind[r][col];
                if (k == EMPTY) continue;
                float a = (r == 1 && shiftT < 1) ? ease : 1f; // the new row fades in
                float mx = cellX(col), my = cellY(r) + oy, fl = flash[r][col];
                if (k == BRICK) {
                    float half = cell * 0.45f * (1 + 0.07f * fl);
                    fill.setColor(alpha(brickColor(hp[r][col]), a));
                    c.drawRoundRect(mx - half, my - half, mx + half, my + half, round, round, fill);
                    if (fl > 0) {
                        fill.setColor(alpha(WHITE, fl * 0.65f));
                        c.drawRoundRect(mx - half, my - half, mx + half, my + half, round, round, fill);
                    }
                    int v = hp[r][col];
                    float size = cell * (v < 100 ? 0.40f : (v < 1000 ? 0.31f : 0.24f));
                    label(c, String.valueOf(v), mx, my, size, alpha(INK, a), Paint.Align.CENTER);
                } else if (k == PLUS) {
                    float rr = cell * (0.17f + 0.015f * (float) Math.sin(clock * 5f + col));
                    stroke.setStrokeWidth(cell * 0.05f);
                    stroke.setColor(alpha(GREEN, a));
                    c.drawCircle(mx, my, rr, stroke);
                    fill.setColor(alpha(WHITE, a));
                    c.drawCircle(mx, my, rad * 0.8f, fill);
                } else {
                    float rr = cell * 0.19f, arm = cell * 0.34f;
                    int col2 = used[r][col] ? alpha(GOLD, 0.45f) : alpha(GOLD, a);
                    stroke.setStrokeWidth(cell * 0.05f);
                    stroke.setColor(col2);
                    if (k == LASER_H) c.drawLine(mx - arm, my, mx + arm, my, stroke);
                    else c.drawLine(mx, my - arm, mx, my + arm, stroke);
                    fill.setColor(FIELD);
                    c.drawCircle(mx, my, rr, fill);
                    c.drawCircle(mx, my, rr, stroke);
                    fill.setColor(col2);
                    c.drawCircle(mx, my, rr * (0.35f + 0.5f * fl), fill);
                }
            }
        }
        for (int i = 0; i < MAXBEAM; i++) {
            if (beamLife[i] <= 0) continue;
            stroke.setColor(alpha(GOLD, beamLife[i] * 0.8f));
            stroke.setStrokeWidth(cell * 0.16f * beamLife[i]);
            if (beamKind[i] == LASER_H) c.drawLine(fx + round, cellY(beamIdx[i]), fx + fw - round, cellY(beamIdx[i]), stroke);
            else c.drawLine(cellX(beamIdx[i]), fy + round, cellX(beamIdx[i]), floorY - round, stroke);
        }
    }

    private void drawAim(Canvas c) {
        float x = lx(), y = floorY - rad, dx = aimX, dy = aimY, step = rad * 1.2f;
        float left = fx + rad, right = fx + COLS * cell - rad, top = fy + rad;
        int n = (int) (cell * 15f / step), bounces = 0;
        for (int i = 1; i <= n; i++) {
            x += dx * step;
            y += dy * step;
            if (x < left) { x = left; dx = -dx; bounces++; }
            else if (x > right) { x = right; dx = -dx; bounces++; }
            if (y < top) { y = top; dy = -dy; bounces++; }
            if (bounces > 2 || (dy > 0 && y > floorY - rad)) break;
            int cc = (int) ((x - fx) / cell), rr = (int) ((y - fy) / cell);
            boolean hit = rr >= 0 && rr < rows && cc >= 0 && cc < COLS && kind[rr][cc] == BRICK;
            if (hit) {
                stroke.setStrokeWidth(cell * 0.03f);
                stroke.setColor(WHITE);
                c.drawCircle(x, y, rad, stroke);
                break;
            }
            if (i % 2 == 0) {
                fill.setColor(alpha(WHITE, 0.85f * (1 - i / (float) n)));
                c.drawCircle(x, y, rad * 0.42f, fill);
            }
        }
    }

    private void ball(Canvas c, float x, float y) {
        fill.setColor(alpha(CYAN, 0.22f));
        c.drawCircle(x, y, rad * 1.9f, fill);
        fill.setColor(WHITE);
        c.drawCircle(x, y, rad, fill);
    }

    private void drawBalls(Canvas c) {
        float y = floorY - rad, labelY = floorY + 5.5f * u;
        if (state == FLY) {
            boolean home = false;
            for (int i = 0; i < launched; i++) {
                if (bs[i] == HOME) home = true;
                else ball(c, bx[i], by[i]);
            }
            if (home) ball(c, nx(), y);
            int left = balls - launched;
            if (left > 0) {
                ball(c, lx(), y);
                label(c, "x" + left, lx(), labelY, 4.2f * u, DIM, Paint.Align.CENTER);
            }
            if (pending > 0)
                label(c, "+" + pending, landedAny ? nx() : lx(), labelY + (left > 0 && !landedAny ? 5 * u : 0),
                        4.6f * u, GREEN, Paint.Align.CENTER);
            if (timeScale() > 1.01f)
                label(c, ">>", w / 2f, floorY + 12.5f * u, 5 * u, alpha(GOLD, 0.6f + 0.4f * (float) Math.sin(clock * 10f)),
                        Paint.Align.CENTER);
            else if (flyClock > 1.5f)
                label(c, "tap to speed up", w / 2f, floorY + 12.5f * u, 3.6f * u, alpha(DIM, 0.8f), Paint.Align.CENTER);
        } else if (state != OVER) {
            ball(c, lx(), y);
            float lxc = Math.max(8 * u, Math.min(w - 8 * u, lx()));
            label(c, "x" + balls, lxc, labelY, (4.2f + 2f * ballPop) * u, ballPop > 0 ? GREEN : DIM, Paint.Align.CENTER);
            if (level <= 2 && state != TITLE && !aiming)
                label(c, "Drag to aim, release to fire", w / 2f, floorY + 12.5f * u, 4.2f * u,
                        alpha(WHITE, 0.55f + 0.35f * (float) Math.sin(clock * 4f)), Paint.Align.CENTER);
        }
    }

    private void drawHud(Canvas c) {
        label(c, "ROUND", 6 * u, 6 * u, 3.6f * u, DIM, Paint.Align.LEFT);
        label(c, String.valueOf(level), 6 * u, 13.5f * u, 10 * u, WHITE, Paint.Align.LEFT);
        label(c, "BEST", w - 6 * u, 6 * u, 3.6f * u, DIM, Paint.Align.RIGHT);
        label(c, String.valueOf(Math.max(best, level)), w - 6 * u, 13.5f * u, 10 * u, newBest ? GOLD : WHITE, Paint.Align.RIGHT);
        // sound toggle: three bars, struck through when muted
        float mx = w / 2f, my = 11 * u;
        stroke.setStrokeWidth(1.3f * u);
        stroke.setColor(sfx.muted ? alpha(DIM, 0.5f) : CYAN);
        float[] hgt = {1.6f, 3.2f, 2.2f};
        for (int i = 0; i < 3; i++) {
            float bh = sfx.muted ? 0.6f * u : hgt[i] * u * (0.8f + 0.2f * (float) Math.sin(clock * 6f + i * 2));
            c.drawLine(mx + (i - 1) * 2.6f * u, my - bh, mx + (i - 1) * 2.6f * u, my + bh, stroke);
        }
        if (sfx.muted) {
            stroke.setColor(RED);
            stroke.setStrokeWidth(0.8f * u);
            c.drawLine(mx - 4.5f * u, my + 3.2f * u, mx + 4.5f * u, my - 3.2f * u, stroke);
        }
    }

    private void drawTitle(Canvas c) {
        fill.setColor(alpha(BG, 0.90f));
        c.drawRect(0, 0, w, h, fill);
        float y = h * 0.34f;
        label(c, "NEON", w / 2f, y, 17 * u, CYAN, Paint.Align.CENTER);
        label(c, "VOLLEY", w / 2f, y + 17 * u, 17 * u, GOLD, Paint.Align.CENTER);
        label(c, "Drag to aim. Release to fire.", w / 2f, y + 34 * u, 4.4f * u, WHITE, Paint.Align.CENTER);
        label(c, "Break the bricks before they reach the floor.", w / 2f, y + 41 * u, 3.8f * u, DIM, Paint.Align.CENTER);
        label(c, "Green rings give you extra balls.", w / 2f, y + 47 * u, 3.8f * u, DIM, Paint.Align.CENTER);
        label(c, "TAP TO PLAY", w / 2f, y + 64 * u, 6 * u, alpha(GREEN, 0.55f + 0.45f * (float) Math.sin(clock * 4f)),
                Paint.Align.CENTER);
        if (best > 1) label(c, "BEST ROUND " + best, w / 2f, y + 74 * u, 4 * u, DIM, Paint.Align.CENTER);
    }

    private void drawOver(Canvas c) {
        float a = Math.min(1, overT * 2.5f);
        fill.setColor(alpha(BG, 0.93f * a));
        c.drawRect(0, 0, w, h, fill);
        float y = h * 0.36f;
        label(c, "GAME OVER", w / 2f, y, 12 * u, alpha(RED, a), Paint.Align.CENTER);
        label(c, "ROUND", w / 2f, y + 15 * u, 4.2f * u, alpha(DIM, a), Paint.Align.CENTER);
        label(c, String.valueOf(level), w / 2f, y + 28 * u, 20 * u, alpha(WHITE, a), Paint.Align.CENTER);
        label(c, newBest ? "NEW BEST!" : "BEST " + best, w / 2f, y + 42 * u, 4.6f * u,
                alpha(newBest ? GOLD : DIM, a), Paint.Align.CENTER);
        if (overT > 0.8f)
            label(c, "TAP TO PLAY AGAIN", w / 2f, y + 58 * u, 6 * u,
                    alpha(GREEN, 0.55f + 0.45f * (float) Math.sin(clock * 4f)), Paint.Align.CENTER);
    }
}
