package io.github.doggylover314.hardcorechallenge.core;

/**
 * What one participant did during one run.
 */
public final class PlayerStats {
    private String name;
    private int mobsKilled;
    private double damageDealt;
    private double bossDamage;
    private int bossKills;
    private long distanceCm;
    private long timePlayedMillis;

    public PlayerStats(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public int mobsKilled() {
        return mobsKilled;
    }

    public double damageDealt() {
        return damageDealt;
    }

    public double bossDamage() {
        return bossDamage;
    }

    public int bossKills() {
        return bossKills;
    }

    public long distanceCm() {
        return distanceCm;
    }

    public long timePlayedMillis() {
        return timePlayedMillis;
    }

    public void addMobKill() {
        mobsKilled++;
    }

    public void addDamage(double amount, boolean toBoss) {
        if (amount <= 0) {
            return;
        }
        damageDealt += amount;
        if (toBoss) {
            bossDamage += amount;
        }
    }

    public void addBossKill() {
        bossKills++;
    }

    public void addDistance(long cm) {
        if (cm > 0) {
            distanceCm += cm;
        }
    }

    public void addTimePlayed(long millis) {
        if (millis > 0) {
            timePlayedMillis += millis;
        }
    }

    /** Used when loading saved runs. */
    public void restore(int mobsKilled, double damageDealt, double bossDamage, int bossKills, long distanceCm, long timePlayedMillis) {
        this.mobsKilled = mobsKilled;
        this.damageDealt = damageDealt;
        this.bossDamage = bossDamage;
        this.bossKills = bossKills;
        this.distanceCm = distanceCm;
        this.timePlayedMillis = timePlayedMillis;
    }
}
