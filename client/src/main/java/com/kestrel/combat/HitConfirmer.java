package com.kestrel.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Decides whether a hurt event the client sees for an entity was caused by
 * <em>your</em> attack. Pure (no Minecraft types), unit-tested.
 *
 * <p>Minecraft 1.8.9 never tells the client who damaged whom. What it does
 * see: the attack you send ({@code AttackEntityEvent}) and, for every damage
 * the server applies, a "hurt" status for that entity. A hurt counts as your
 * hit only if it arrives within {@link #WINDOW_MS} after one of your attacks on
 * that same entity, and each attack confirms at most one hurt, so a miss (no
 * hurt), another player's hit long after yours, fall damage etc. never count.
 *
 * @param <S> what is remembered about the attack (e.g. a damage snapshot)
 */
public final class HitConfirmer<S> {
    /** Longest round trip accepted between your attack and the server's hurt status. */
    public static final long WINDOW_MS = 800;

    private static final class Pending<S> {
        final long atMs;
        final S snapshot;

        Pending(long atMs, S snapshot) {
            this.atMs = atMs;
            this.snapshot = snapshot;
        }
    }

    private final Map<Integer, Pending<S>> pending = new HashMap<Integer, Pending<S>>();

    /** You attacked entity {@code id}. A newer attack replaces an unconfirmed older one. */
    public void attack(int id, long nowMs, S snapshot) {
        pending.put(id, new Pending<S>(nowMs, snapshot));
    }

    /**
     * Entity {@code id} was hurt. Returns the attack's snapshot if this hurt is
     * yours (and consumes the attack), otherwise {@code null}.
     */
    public S hurt(int id, long nowMs) {
        Pending<S> p = pending.get(id);
        if (p == null) return null;
        long dt = nowMs - p.atMs;
        if (dt < 0 || dt > WINDOW_MS) {
            pending.remove(id);
            return null;
        }
        pending.remove(id);
        return p.snapshot;
    }

    /** Drop attacks older than the window (call now and then). */
    public void expire(long nowMs) {
        Iterator<Pending<S>> it = pending.values().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().atMs > WINDOW_MS) it.remove();
        }
    }

    public void clear() {
        pending.clear();
    }

    int size() {
        return pending.size();
    }
}
