package com.dino.holeshield;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Frame paced tween driver for the desktop UI.
 *
 * <p>A single 16 ms Swing timer (about 60 frames per second) drives every
 * running animation, so widgets share one clock and the timer stops as soon as
 * nothing is moving. Animations are short and repaint only the widget that
 * changed, which keeps the panels smooth without a render loop.</p>
 */
final class Motion {

    /** One frame at 60 fps. */
    static final int FRAME_MS = 16;

    interface Easing {
        double apply(double t);
    }

    static final Easing EASE_OUT = t -> 1 - Math.pow(1 - t, 3);
    static final Easing EASE_IN_OUT = t -> t < 0.5
            ? 4 * t * t * t
            : 1 - Math.pow(-2 * t + 2, 3) / 2;

    private static final List<Tween> ACTIVE = new ArrayList<>();
    private static Timer timer;

    private Motion() { }

    /** An animation handle; {@link #stop()} freezes it where it is. */
    static final class Tween {
        private final double from;
        private final double to;
        private final int duration;
        private final Easing easing;
        private final DoubleConsumer frame;
        private final long start = System.nanoTime();
        private boolean stopped;

        private Tween(double from, double to, int duration, Easing easing,
                      DoubleConsumer frame) {
            this.from = from;
            this.to = to;
            this.duration = duration;
            this.easing = easing;
            this.frame = frame;
        }

        void stop() {
            stopped = true;
        }

        private boolean step() {
            if (stopped) return false;
            double progress = (System.nanoTime() - start) / 1_000_000.0 / duration;
            if (progress >= 1) {
                frame.accept(to);
                return false;
            }
            frame.accept(from + (to - from) * easing.apply(progress));
            return true;
        }
    }

    /**
     * Animates a value from {@code from} to {@code to}. With a zero duration
     * or a negligible distance the target is applied immediately.
     */
    static Tween tween(double from, double to, int durationMs, Easing easing,
                       DoubleConsumer frame) {
        if (durationMs <= 0 || Math.abs(to - from) < 0.001) {
            frame.accept(to);
            return new Tween(to, to, 0, EASE_OUT, frame);
        }
        Tween tween = new Tween(from, to, durationMs, easing, frame);
        if (SwingUtilities.isEventDispatchThread()) {
            start(tween);
        } else {
            SwingUtilities.invokeLater(() -> start(tween));
        }
        return tween;
    }

    private static void start(Tween tween) {
        ACTIVE.add(tween);
        ensureTimer();
    }

    private static void ensureTimer() {
        if (timer == null) {
            timer = new Timer(FRAME_MS, e -> tick());
            timer.setCoalesce(true);
        }
        if (!timer.isRunning()) timer.start();
    }

    private static void tick() {
        if (ACTIVE.isEmpty()) {
            stopTimer();
            return;
        }
        List<Tween> frame = new ArrayList<>(ACTIVE);
        for (Tween tween : frame) {
            if (!tween.step()) ACTIVE.remove(tween);
        }
        if (ACTIVE.isEmpty()) stopTimer();
    }

    private static void stopTimer() {
        if (timer != null && timer.isRunning()) timer.stop();
    }
}
