package com.kestrel.combat;

/**
 * Opponent health for one target: the server's value when it can be trusted,
 * otherwise an estimate from your confirmed hits. Pure, unit-tested.
 *
 * <p>Many servers send real player health; some hide it (always full, or a
 * fixed value such as 1). The server's value is treated as trustworthy only
 * once it has changed while tracked; a value that merely starts below full is
 * not enough, since a fixed fake value would look the same. It is proven
 * untrustworthy when the target took two hurts and the value never moved.
 * Until then it is shown as reported (marked unverified).
 *
 * <p>An estimate is only needed while the server's value is unverified, so it
 * always starts from the configured starting assumption, never from an
 * unverified server value.
 */
public final class HealthEstimator {
    public enum Source {
        /** The server's health value (it has shown real changes). */
        CONFIRMED,
        /** Not proven either way yet and no hits of yours: the server's value as reported. */
        REPORTED,
        /** Estimated from your confirmed hits (marked with "~"). */
        ESTIMATED,
        /** Nothing trustworthy to show. */
        UNAVAILABLE
    }

    private final float startingAssumption;
    private boolean observed;
    private float lastServer;
    private float lastAbsorption;
    private boolean changed;
    private int hurts;
    private int ownHits;
    private float damageTaken;
    private float startHealth;
    private float startAbsorption;

    /** @param startingAssumption assumed health when the server's value can't be trusted */
    public HealthEstimator(float startingAssumption) {
        this.startingAssumption = startingAssumption;
    }

    /** The server's current health/absorption for the target (call every tick). */
    public void observe(float serverHealth, float maxHealth, float serverAbsorption) {
        if (Float.isNaN(serverHealth) || Float.isInfinite(serverHealth)) {
            return;
        }
        if (!observed) {
            observed = true;
            lastServer = serverHealth;
            lastAbsorption = serverAbsorption;
            return;
        }
        if (serverHealth != lastServer || serverAbsorption != lastAbsorption) changed = true;
        lastServer = serverHealth;
        lastAbsorption = serverAbsorption;
    }

    /** The target took damage from anything (its hurt status arrived). */
    public void hurt() {
        hurts++;
    }

    /** One of your hits was confirmed, with its expected damage after defences. */
    public void confirmedHit(float expectedDamage) {
        if (ownHits == 0) {
            startHealth = startingAssumption;
            startAbsorption = observed ? Math.max(0f, lastAbsorption) : 0f;
        }
        ownHits++;
        damageTaken += Math.max(0f, expectedDamage);
    }

    public boolean trusted() {
        return changed;
    }

    public boolean provenUntrusted() {
        return !changed && hurts >= 2;
    }

    public Source source() {
        if (changed) return Source.CONFIRMED;
        if (ownHits > 0) return Source.ESTIMATED;
        if (provenUntrusted()) return Source.UNAVAILABLE;
        return observed ? Source.REPORTED : Source.UNAVAILABLE;
    }

    /** Health to show for {@link #source()} (absorption not included). */
    public float health() {
        switch (source()) {
            case CONFIRMED:
            case REPORTED:
                return Math.max(0f, lastServer);
            case ESTIMATED: {
                // Absorption from a server shown to fake values isn't trusted either.
                float abs = provenUntrusted() ? 0f : startAbsorption;
                return Math.max(0f, startHealth - Math.max(0f, damageTaken - abs));
            }
            default:
                return 0f;
        }
    }

    /** Absorption hearts to show (server value, or what the estimate says is left). */
    public float absorption() {
        switch (source()) {
            case CONFIRMED:
            case REPORTED:
                return Math.max(0f, lastAbsorption);
            case ESTIMATED:
                return provenUntrusted() ? 0f : Math.max(0f, startAbsorption - damageTaken);
            default:
                return 0f;
        }
    }
}
