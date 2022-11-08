package server.buffs.buffclasses.cygnus;

import client.character.stat.MapleBuffStat;
import client.MapleJob;
import client.MonsterStatus;
import server.MapleStatEffect;
import server.MapleStatInfo;
import server.buffs.AbstractBuffClass;

public class DawnWarriorBuff extends AbstractBuffClass {

    public DawnWarriorBuff() {
        buffs = new int[]{11101002, // 終極攻擊
            11001021, // 光之劍
            11001022, // 元素： 靈魂
            11101023, // 堅定信念
            11101022, // 沉月
            // 11101003, // 憤怒
            11101024, // 光速反應
            // 11101001, // Booster
            // 11111007, // Radiant Charge
            11111022, // 旭日
            11111023, // 真實之眼
            11111024, // 靈魂守護者
            11121012, // 雙重力量（旭日）
            11121011, // 雙重力量（沉月）
            11121005, // 雙重力量
            11121006, // 靈魂誓約
            // 11121000, // Call of Cygnus
            11121053,};
    }

    @Override
    public boolean containsJob(int job) {
        return MapleJob.is聖魂劍士(job);
    }

    @Override
    public void handleBuff(MapleStatEffect eff, int skill) {
        switch (skill) {
            case 11101002: // 終極攻擊
                eff.statups.put(MapleBuffStat.SoulMasterFinal, eff.info.get(MapleStatInfo.x));
                break;
            case 11101024: // 光速反應
                eff.statups.put(MapleBuffStat.Booster, eff.info.get(MapleStatInfo.x));
                break;
            case 11001021: // 光之劍
                eff.statups.put(MapleBuffStat.ACCR, eff.info.get(MapleStatInfo.x));
                eff.statups.put(MapleBuffStat.LightOfSpirit, (int) eff.getLevel());
                eff.statups.put(MapleBuffStat.IndiePAD, eff.info.get(MapleStatInfo.indiePad));
                break;
            case 11001022: // 元素： 靈魂
                eff.statups.put(MapleBuffStat.ElementSoul, eff.info.get(MapleStatInfo.prop));
                eff.monsterStatus.put(MonsterStatus.M_Stun, 1);
                break;
            case 11101023: // 堅定信念
                eff.statups.put(MapleBuffStat.IndiePAD, eff.info.get(MapleStatInfo.indiePad));
                break;
            case 11101022: // 沉月
                eff.statups.put(MapleBuffStat.PoseType, 1);
                eff.statups.put(MapleBuffStat.BuckShot, eff.info.get(MapleStatInfo.x));
                eff.statups.put(MapleBuffStat.IndieCr, eff.info.get(MapleStatInfo.indieCr));
                break;
            case 11111022: // 旭日
                eff.statups.put(MapleBuffStat.PoseType, 2);
                eff.statups.put(MapleBuffStat.IndieDamR, eff.info.get(MapleStatInfo.indieDamR));
                eff.statups.put(MapleBuffStat.IndieBooster, eff.info.get(MapleStatInfo.indieBooster));
                break;
            case 11111023:
                eff.statups.put(MapleBuffStat.IndiePAD, eff.info.get(MapleStatInfo.indiePad));
                break;
            case 11111024: // 靈魂守護者
                eff.statups.put(MapleBuffStat.IndieMHP, eff.info.get(MapleStatInfo.indieMhp));
                eff.statups.put(MapleBuffStat.IndiePDD, eff.info.get(MapleStatInfo.indiePdd));
                break;
            case 11121005: // 雙重力量
                eff.statups.put(MapleBuffStat.PoseType, 1); // should be level but smd
                break;
            case 11121011: // 雙重力量（沉月）
                eff.statups.put(MapleBuffStat.GlimmeringTime, 11121011);
                eff.statups.put(MapleBuffStat.IndieCr, eff.info.get(MapleStatInfo.indieCr));
                eff.statups.put(MapleBuffStat.BuckShot, eff.info.get(MapleStatInfo.x));
                break;
            case 11121012: // 雙重力量（旭日）
                eff.statups.put(MapleBuffStat.GlimmeringTime, 11121012);
                eff.statups.put(MapleBuffStat.IndieDamR, eff.info.get(MapleStatInfo.indieDamR));
                eff.statups.put(MapleBuffStat.IndieBooster, eff.info.get(MapleStatInfo.indieBooster));
                break;
            case 11121006: // 靈魂誓約
                eff.statups.put(MapleBuffStat.IndieCr, eff.info.get(MapleStatInfo.indieCr));
                eff.statups.put(MapleBuffStat.IndieAllStat, eff.info.get(MapleStatInfo.indieAllStat));
                eff.statups.put(MapleBuffStat.Stance, eff.info.get(MapleStatInfo.prop));
                break;
            case 11121053:
                eff.statups.put(MapleBuffStat.IndieDamR, eff.info.get(MapleStatInfo.indieDamR));
                eff.statups.put(MapleBuffStat.IncMaxDamage, eff.info.get(MapleStatInfo.indieMaxDamageOver));
                break;
            default:
                System.out.println("Unhandled 聖魂劍士 Buff: " + skill);
                break;
        }
    }
}
