/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package server.buffs.buffclasses.nova;

import client.character.stat.MapleBuffStat;
import client.MapleJob;
import client.MonsterStatus;
import server.MapleStatEffect;
import server.MapleStatInfo;
import server.buffs.AbstractBuffClass;

/**
 *
 * @author Dismal
 */
public class KaiserBuff extends AbstractBuffClass {

    public KaiserBuff() {
        buffs = new int[]{60001216, // 洗牌交換： 防禦模式
            60001217, // 洗牌交換： 攻擊模式
            61101002, // 意志之劍
            61101004, // 怒火中燒
            61111002, // 地龍襲擊
            61111003, // 龍爆走
            61111004, // 加強戰力
            61111008, // 終極型態
            61120007, // 進階意志之劍
            61120008, // 進階終極形態
            61121009, // 堅韌護甲
            61121014, // 超新星勇士
            61121053, // 超.終極型態
            61121054, // 凱撒王權
    // 61121015, // 超新星勇士意志
    // 61100005, // 防禦模式
    // 61100008, // 攻擊模式
    // 61110005, // 二階防禦模式
    // 61110010, // 二階攻擊模式
    // 61120010, // 三階防禦模式
    // 61120013, // 三階攻擊模式
    };
    }

    @Override
    public boolean containsJob(int job) {
        return MapleJob.is凱撒(job);
    }

    @Override
    public void handleBuff(MapleStatEffect eff, int skill) {
        switch (skill) {
            case 61111003: // 龍爆走
                eff.statups.put(MapleBuffStat.AsrR, eff.info.get(MapleStatInfo.asrR));
                eff.statups.put(MapleBuffStat.TerR, eff.info.get(MapleStatInfo.terR));
                break;
            case 60001216: // 洗牌交換： 防禦模式
            case 60001217: // 洗牌交換： 攻擊模式
                eff.statups.put(MapleBuffStat.ReshuffleSwitch, 0);
            case 61101002: // 意志之劍
            case 61120007: // 進階意志之劍
                eff.statups.put(MapleBuffStat.StopForceAtomInfo, (int) eff.getLevel());
                break;
            case 61101004: // 怒火中燒
                eff.statups.put(MapleBuffStat.Booster, eff.info.get(MapleStatInfo.x));
                eff.statups.put(MapleBuffStat.IndiePAD, eff.info.get(MapleStatInfo.indiePad));
                break;
            case 61111002: // 地龍襲擊
                eff.statups.put(MapleBuffStat.SUMMON, 1);
                eff.monsterStatus.put(MonsterStatus.M_Stun, 1);
                break;
            case 61111004: // 加強戰力
                eff.statups.put(MapleBuffStat.IndieDamR, eff.info.get(MapleStatInfo.indieDamR));
                break;
            case 61111008: // 終極型態
            case 61120008: // 進階終極形態
            case 61121053: // 超.終極型態
                eff.statups.put(MapleBuffStat.IndieDamR, eff.info.get(MapleStatInfo.indieDamR));
                eff.statups.put(MapleBuffStat.IndieBooster, eff.info.get(MapleStatInfo.indieBooster));
                eff.statups.put(MapleBuffStat.Speed, eff.info.get(MapleStatInfo.speed));
                eff.statups.put(MapleBuffStat.Morph, eff.info.get(MapleStatInfo.morph));
                eff.statups.put(MapleBuffStat.Stance, eff.info.get(MapleStatInfo.prop));
                eff.statups.put(MapleBuffStat.CriticalBuff, eff.info.get(MapleStatInfo.cr));
                break;
            case 61121009: // 堅韌護甲
                eff.statups.put(MapleBuffStat.PartyBarrier, eff.info.get(MapleStatInfo.ignoreMobpdpR));
                break;
            case 61121014: // 超新星勇士
                eff.statups.put(MapleBuffStat.BasicStatUp, eff.info.get(MapleStatInfo.x));
                break;
            case 61121054: // 凱撒王權
                // eff.statups.put(MapleBuffStat.KAISER_MAJESTY3,
                // eff.info.get(MapleStatInfo.x));
                // eff.statups.put(MapleBuffStat.KAISER_MAJESTY4,
                // eff.info.get(MapleStatInfo.x));
                eff.statups.put(MapleBuffStat.IndiePAD, eff.info.get(MapleStatInfo.indiePad));
                eff.statups.put(MapleBuffStat.IndieBooster, eff.info.get(MapleStatInfo.indieBooster));
                break;
            default:
                System.out.println("未知的 凱撒(6100) Buff: " + skill);
                break;
        }
    }
}
