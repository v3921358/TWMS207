/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc> 
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package handling.channel.handler;

import client.MapleCharacter;
import client.MapleClient;
import client.MapleJob;
import client.MapleStat;
import client.PlayerStats;
import client.skill.Skill;
import client.skill.SkillEntry;
import client.skill.SkillFactory;
import constants.GameConstants;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import server.Randomizer;
import tools.Pair;
import tools.data.LittleEndianAccessor;
import tools.packet.CWvsContext;

public class StatsHandling {

    public static final short statLimit = 32767;
    public static final int hpMpLimit = 500000;

    public static void DistributeAP(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        Map<MapleStat, Long> statupdate = new EnumMap<>(MapleStat.class);
        c.getSession().writeAndFlush(CWvsContext.updatePlayerStats(statupdate, true, chr));
        chr.updateTick(slea.readInt());
        final int statmask = slea.readInt();
        final PlayerStats stat = chr.getStat();
        final int job = chr.getJob();
        if (chr.getRemainingAp() > 0) {
            switch (statmask) {
                case 0x40: // Str
                    if (stat.getStr() >= statLimit) {
                        return;
                    }
                    stat.setStr((short) (stat.getStr() + 1), chr);
                    statupdate.put(MapleStat.STR, (long) stat.getStr());
                    break;
                case 0x80: // Dex
                    if (stat.getDex() >= statLimit) {
                        return;
                    }
                    stat.setDex((short) (stat.getDex() + 1), chr);
                    statupdate.put(MapleStat.DEX, (long) stat.getDex());
                    break;
                case 0x100: // Int
                    if (stat.getInt() >= statLimit) {
                        return;
                    }
                    stat.setInt((short) (stat.getInt() + 1), chr);
                    statupdate.put(MapleStat.INT, (long) stat.getInt());
                    break;
                case 0x200: // Luk
                    if (stat.getLuk() >= statLimit) {
                        return;
                    }
                    stat.setLuk((short) (stat.getLuk() + 1), chr);
                    statupdate.put(MapleStat.LUK, (long) stat.getLuk());
                    break;
                case 0x800: // HP
                    int maxhp = stat.getMaxHp();
                    if (chr.getHpApUsed() >= 10000 || maxhp >= 500000) {
                        return;
                    }
                    if (MapleJob.isBeginner(job)) { // Beginner
                        maxhp += Randomizer.rand(8, 12);
                    } else if (MapleJob.is惡魔復仇者(job)) {
                        maxhp += 30;
                    } else if ((job >= 100 && job <= 132) || (job >= 3200 && job <= 3212) || (job >= 1100 && job <= 1112)
                            || (job >= 3100 && job <= 3112)) { // Warrior
                        maxhp += Randomizer.rand(36, 42);
                    } else if ((job >= 200 && job <= 232) || (MapleJob.is龍魔導士(job)) || (job >= 2700 && job <= 2712)) { // Magician
                        maxhp += Randomizer.rand(10, 20);
                    } else if ((job >= 300 && job <= 322) || (job >= 400 && job <= 434) || (job >= 1300 && job <= 1312)
                            || (job >= 1400 && job <= 1412) || (job >= 3300 && job <= 3312)
                            || (job >= 2300 && job <= 2312)) { // Bowman
                        maxhp += Randomizer.rand(16, 20);
                    } else if ((job >= 510 && job <= 512) || (job >= 1510 && job <= 1512)) {
                        maxhp += Randomizer.rand(28, 32);
                    } else if ((job >= 500 && job <= 532) || MapleJob.is蒼龍俠客(job) || (job >= 3500 && job <= 3512)
                            || job == 1500) { // Pirate
                        maxhp += Randomizer.rand(18, 22);
                    } else if (job >= 1200 && job <= 1212) { // Flame Wizard
                        maxhp += Randomizer.rand(15, 21);
                    } else if (job >= 2000 && job <= 2112) { // Aran
                        maxhp += Randomizer.rand(38, 42);
                    } else if (job >= 10100 && job <= 10112) {
                        maxhp += Randomizer.rand(48, 52);
                    } else { // GameMaster
                        maxhp += Randomizer.rand(18, 26);
                    }
                    maxhp = Math.min(500000, Math.abs(maxhp));
                    chr.setHpApUsed((short) (chr.getHpApUsed() + 1));
                    stat.setMaxHp(maxhp, chr);
                    statupdate.put(MapleStat.MAXHP, (long) maxhp);
                    break;
                case 0x2000: // MP
                    int maxmp = stat.getMaxMp();
                    if (chr.getMpApUsed() >= 10000 || stat.getMaxMp() >= 500000) {
                        return;
                    }
                    if (MapleJob.isBeginner(job)) { // Beginner
                        maxmp += Randomizer.rand(6, 8);
                    } else if (MapleJob.isNotMpJob(job)) {
                        return;
                    } else if ((job >= 200 && job <= 232) || (MapleJob.is龍魔導士(job)) || (job >= 3200 && job <= 3212)
                            || (job >= 1200 && job <= 1212)) { // Magician
                        maxmp += Randomizer.rand(38, 40);
                    } else if ((job >= 300 && job <= 322) || (job >= 400 && job <= 434) || (job >= 500 && job <= 532)
                            || (job >= 3200 && job <= 3212) || (job >= 3500 && job <= 3512) || (job >= 1300 && job <= 1312)
                            || (job >= 1400 && job <= 1412) || (job >= 1500 && job <= 1512)
                            || (job >= 2300 && job <= 2312)) { // Bowman
                        maxmp += Randomizer.rand(10, 12);
                    } else if ((job >= 100 && job <= 132) || (job >= 1100 && job <= 1112) || (job >= 2000 && job <= 2112)) { // Soul
                        // Master
                        maxmp += Randomizer.rand(6, 9);
                    } else { // GameMaster
                        maxmp += Randomizer.rand(6, 12);
                    }
                    maxmp = Math.min(500000, Math.abs(maxmp));
                    chr.setMpApUsed((short) (chr.getMpApUsed() + 1));
                    stat.setMaxMp(maxmp, chr);
                    statupdate.put(MapleStat.MAXMP, (long) maxmp);
                    break;
                default:
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
            }

            chr.setRemainingAp((short) (chr.getRemainingAp() - 1));
            statupdate.put(MapleStat.AVAILABLEAP, (long) chr.getRemainingAp());
            c.getSession().writeAndFlush(CWvsContext.updatePlayerStats(statupdate, true, chr));
        }
        System.out.println(slea.toString());
    }

    public static void DistributeSP(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        c.getPlayer().updateTick(slea.readInt());
        final int skillid = slea.readInt();
        // final int amount = slea.readInt();//v148
        int amount = slea.readInt();
        amount = amount == 0 ? 1 : amount;

        if (chr.isShowInfo()) {
            chr.dropMessage(5, "開始加技能點 - 技能ID: " + skillid + " 等級: " + amount);
        }
        // null
        boolean isBeginnerSkill = false;
        final int remainingSp;
        if (MapleJob.isBeginner(skillid / 10000) && (skillid % 10000 == 1000
                || // 嫩寶丟擲術
                skillid % 10000 == 1001
                || // 治癒
                skillid % 10000 == 1002
                || // 疾風之步
                skillid % 10000 == 2
                || // 能效
                skillid == 140000291 // 第六感知
                )) {
            final boolean resistance = skillid / 10000 == 3000 || skillid / 10000 == 3001;
            final int snailsLevel = chr.getSkillLevel(SkillFactory.getSkill(((skillid / 10000) * 10000) + 1000));
            final int recoveryLevel = chr.getSkillLevel(SkillFactory.getSkill(((skillid / 10000) * 10000) + 1001));
            final int nimbleFeetLevel = chr
                    .getSkillLevel(SkillFactory.getSkill(((skillid / 10000) * 10000) + (resistance ? 2 : 1002)));
            final int 第六感知Level = chr.getSkillLevel(SkillFactory.getSkill(140000291));
            remainingSp = Math.min((chr.getLevel() - 1), resistance ? 9 : 6) - snailsLevel - recoveryLevel
                    - nimbleFeetLevel - 第六感知Level;
            isBeginnerSkill = true;
        } else if (MapleJob.isBeginner(skillid / 10000)) {
            if (chr.isShowErr()) {
                chr.dropMessage(5, "加技能點錯誤 - 1");
            }
            return;
        } else {
            remainingSp = chr.getRemainingSp(GameConstants.getSkillBookBySkill(skillid));
        }
        Map<Skill, SkillEntry> skills = chr.getSkills();
        Skill skill = SkillFactory.getSkill(skillid);
        if (skill.getRequiredSkills() != null) {
            for (Pair<String, Integer> ski : skill.getRequiredSkills()) {
                if (ski.left.equals("level")) {
                    if (chr.getLevel() < ski.right) {
                        return;
                    }
                } else if (ski.left.equals("reqAmount")) {
                    int reqAmount = 0;
                    for (Map.Entry<Skill, SkillEntry> skil : skills.entrySet()) {
                        if (skil.getKey().getId() / 10000 != skill.getId() / 10000) {
                            continue;
                        }
                        reqAmount += skil.getValue().skillLevel;
                    }
                    if (reqAmount < ski.right) {
                        return;
                    }
                } else {
                    int left = Integer.parseInt(ski.left);
                    if (chr.getSkillLevel(SkillFactory.getSkill(left)) < ski.right) {
                        // AutobanManager.getInstance().addPoints(c, 1000, 0, "Trying to learn a skill
                        // without the required skill (" + skillid + ")");
                        return;
                    }
                }
            }
        } else {
            if (chr.isShowErr()) {
                chr.dropMessage(5, "加技能點錯誤 - 1");
            }
        }
        final int maxlevel = skill.isFourthJob() ? chr.getMasterLevel(skill) : skill.getMaxLevel();
        final int curLevel = chr.getSkillLevel(skill);

        if (skill.isInvisible() && chr.getSkillLevel(skill) == 0) {
            if ((skill.isFourthJob() && chr.getMasterLevel(skill) == 0) || (!skill.isFourthJob() && maxlevel < 10
                    && !MapleJob.is影武者(chr.getJob()) && !isBeginnerSkill && chr.getMasterLevel(skill) <= 0)) {
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                // AutobanManager.getInstance().addPoints(c, 1000, 0, "Illegal distribution of
                // SP to invisible skills (" + skillid + ")");
                return;
            }
        }
        for (int i : GameConstants.blockedSkills) {
            if (skill.getId() == i) {
                if (chr.isIntern()) {
                    if (chr.isShowInfo()) {
                        chr.dropMessage(1, "個技能普通玩家無法加技能點.");
                    }
                    break;
                } else {
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    chr.dropMessage(1, "這個技能暫時無法使用.");
                    return;
                }
            }
        }
        if ((remainingSp >= amount && curLevel + amount <= maxlevel) && skill.canBeLearnedBy(chr.getJob())) {
            if (!isBeginnerSkill) {
                final int skillbook = GameConstants.getSkillBookBySkill(skillid);
                chr.setRemainingSp(chr.getRemainingSp(skillbook) - amount, skillbook);
            }
            chr.updateSingleStat(MapleStat.AVAILABLESP, 0); // we don't care the value here
            chr.changeSingleSkillLevel(skill, (byte) (curLevel + amount), chr.getMasterLevel(skill));
            // } else if (!skill.canBeLearnedBy(chr.getJob())) {
            // AutobanManager.getInstance().addPoints(c, 1000, 0, "Trying to learn a skill
            // for a different job (" + skillid + ")");
        } else {
            System.out.println("Skill errors!!");
            System.out.println("isbeginner " + isBeginnerSkill);
            System.out.println("canlearn " + skill.canBeLearnedBy(chr.getJob()));
            System.out.println("remainingsp " + remainingSp);
            System.out.println("amount " + amount);
            System.out.println("curlvl " + curLevel);
            System.out.println("maxlvl " + maxlevel);
            c.getSession().writeAndFlush(CWvsContext.enableActions());
        }
    }

    public static final void AutoAssignAP(LittleEndianAccessor slea, MapleClient c, MapleCharacter chr) {
        chr.updateTick(slea.readInt());
        int autoSpSize = slea.readInt();
        if (slea.available() < autoSpSize * 12) {
            return;
        }
        int PrimaryStat = (int) slea.readLong();
        int amount = slea.readInt();
        int SecondaryStat = autoSpSize > 1 ? (int) slea.readLong() : 0;
        int amount2 = autoSpSize > 1 ? slea.readInt() : 0;
        if ((amount < 0) || (amount2 < 0)) {
            return;
        }

        PlayerStats playerst = chr.getStat();
        boolean usedAp1 = true;
        boolean usedAp2 = true;
        Map<MapleStat, Long> statupdate = new EnumMap<>(MapleStat.class);
        c.getSession().writeAndFlush(CWvsContext.updatePlayerStats(statupdate, true, chr));

        if (chr.getRemainingAp() >= amount + amount2) {
            switch (PrimaryStat) {
                case 0x40:
                    if (playerst.getStr() + amount > statLimit) {
                        return;
                    }
                    playerst.setStr((short) (playerst.getStr() + amount), chr);
                    statupdate.put(MapleStat.STR, (long) playerst.getStr());
                    break;
                case 0x80:
                    if (playerst.getDex() + amount > statLimit) {
                        return;
                    }
                    playerst.setDex((short) (playerst.getDex() + amount), chr);
                    statupdate.put(MapleStat.DEX, (long) playerst.getDex());
                    break;
                case 0x100:
                    if (playerst.getInt() + amount > statLimit) {
                        return;
                    }
                    playerst.setInt((short) (playerst.getInt() + amount), chr);
                    statupdate.put(MapleStat.INT, (long) playerst.getInt());
                    break;
                case 0x200:
                    if (playerst.getLuk() + amount > statLimit) {
                        return;
                    }
                    playerst.setLuk((short) (playerst.getLuk() + amount), chr);
                    statupdate.put(MapleStat.LUK, (long) playerst.getLuk());
                    break;
                case 0x800:
                    int maxhp = playerst.getMaxHp();
                    if ((chr.getHpApUsed() >= 10000) || (maxhp >= 500000) || (!MapleJob.is惡魔復仇者(chr.getJob()))) {
                        return;
                    }
                    maxhp += 30 * amount;
                    maxhp = Math.min(500000, Math.abs(maxhp));
                    chr.setHpApUsed((short) (chr.getHpApUsed() + amount));
                    playerst.setMaxHp(maxhp, chr);
                    statupdate.put(MapleStat.MAXHP, (long) maxhp);
                    break;
                default:
                    usedAp1 = false;
            }
            switch (SecondaryStat) {
                case 0x40:
                    if (playerst.getStr() + amount2 > statLimit) {
                        return;
                    }
                    playerst.setStr((short) (playerst.getStr() + amount2), chr);
                    statupdate.put(MapleStat.STR, (long) playerst.getStr());
                    break;
                case 0x80:
                    if (playerst.getDex() + amount2 > statLimit) {
                        return;
                    }
                    playerst.setDex((short) (playerst.getDex() + amount2), chr);
                    statupdate.put(MapleStat.DEX, (long) playerst.getDex());
                    break;
                case 0x100:
                    if (playerst.getInt() + amount2 > statLimit) {
                        return;
                    }
                    playerst.setInt((short) (playerst.getInt() + amount2), chr);
                    statupdate.put(MapleStat.INT, (long) playerst.getInt());
                    break;
                case 0x200:
                    if (playerst.getLuk() + amount2 > statLimit) {
                        return;
                    }
                    playerst.setLuk((short) (playerst.getLuk() + amount2), chr);
                    statupdate.put(MapleStat.LUK, (long) playerst.getLuk());
                    break;
                default:
                    usedAp2 = false;
            }

            if ((!usedAp1 || !usedAp2) && chr.isShowInfo()) {
                chr.dropMessage(5, "自動分配能力點 - 主能力: " + usedAp1 + " 副能力: " + usedAp2);
            }
            chr.setRemainingAp((short) (chr.getRemainingAp() - ((usedAp1 ? amount : 0) + (usedAp2 ? amount2 : 0))));
            statupdate.put(MapleStat.AVAILABLEAP, (long) chr.getRemainingAp());
            c.getSession().writeAndFlush(CWvsContext.updatePlayerStats(statupdate, true, chr));
        }
    }

    public static void DistributeHyper(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        chr.updateTick(slea.readInt());
        int skillid = slea.readInt();
        final Skill skill = SkillFactory.getSkill(skillid);

        final int remainingSp = chr.getRemainingHSp(skill.getHyper() - 1);

        final int maxlevel = skill.getMaxLevel();
        final int curLevel = chr.getSkillLevel(skill);

        if (skill.isInvisible() && chr.getSkillLevel(skill) == 0) {
            if (maxlevel <= 0) {
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                // AutobanManager.getInstance().addPoints(c, 1000, 0, "Illegal distribution of
                // SP to invisible skills (" + skillid + ")");
                return;
            }
        }

        if (chr.isShowInfo()) {
            chr.dropMessage(5, "開始加超級技能 - 技能ID：" + skillid + " - " + skill.getName());
        }

        for (int i : GameConstants.blockedSkills) {
            if (skill.getId() == i) {
                if (chr.isIntern()) {
                    if (chr.isShowInfo()) {
                        chr.dropMessage(1, "個技能普通玩家無法加技能點.");
                    }
                    break;
                } else {
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    chr.dropMessage(1, "這個技能暫時無法使用.");
                    return;
                }
            }
        }

        if (remainingSp >= 1 && chr.getLevel() >= skill.getReqLevel() && skill.canBeLearnedBy(chr.getJob())
                && curLevel < maxlevel) {
            chr.setRemainingHSp(skill.getHyper() - 1, remainingSp - 1);
            chr.changeSingleSkillLevel(skill, (byte) 1, (byte) 1, -1L, true);
            c.getSession().writeAndFlush(CWvsContext.enableActions());
        } else {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
        }
    }

    public static void DistributeHyperStat(final LittleEndianAccessor slea, final MapleClient c,
            final MapleCharacter chr) {
        chr.updateTick(slea.readInt());
        int skillid = slea.readInt();
        final Skill skill = SkillFactory.getSkill(skillid);

        if (!skill.isHyperStat()) {
            return;
        }

        final int remainingHAp = chr.getRemainingHAp();

        final int maxlevel = skill.getMaxLevel();
        final int curLevel = chr.getSkillLevel(skill);

        if (skill.isInvisible() && chr.getSkillLevel(skill) == 0) {
            if (maxlevel <= 0) {
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                // AutobanManager.getInstance().addPoints(c, 1000, 0, "Illegal distribution of
                // SP to invisible skills (" + skillid + ")");
                return;
            }
        }

        if (chr.isShowInfo()) {
            chr.dropMessage(5, "開始加極限屬性 - 技能ID：" + skillid + " - " + skill.getName());
        }

        for (int i : GameConstants.blockedSkills) {
            if (skill.getId() == i) {
                if (chr.isIntern()) {
                    if (chr.isShowInfo()) {
                        chr.dropMessage(1, "個技能普通玩家無法加技能點.");
                    }
                    break;
                } else {
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    chr.dropMessage(1, "這個技能暫時無法使用.");
                    return;
                }
            }
        }

        if (remainingHAp >= GameConstants.getHyperStatReqAp(curLevel + 1) && chr.getLevel() >= skill.getReqLevel()
                && curLevel < maxlevel) {
            chr.changeSingleSkillLevel(skill, (byte) chr.getSkillLevel(skill) + 1, (byte) skill.getMaxLevel(), -1L,
                    true);
            c.getSession().writeAndFlush(CWvsContext.enableActions());
        } else {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
        }
    }

    public static void ResetHyper(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        chr.updateTick(slea.readInt());
        short times = slea.readShort();
        if (times < 1 || times > 3) {
            times = 3;
        }
        long price = 10000L * (long) Math.pow(10, times);
        if (chr.getMeso() < price) {
            chr.dropMessage(1, "你的楓幣不足.");
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        int spp = 0;
        int sap = 0;
        HashMap<Skill, SkillEntry> sa = new HashMap<>();
        for (Skill skil : SkillFactory.getAllSkills()) {
            if (skil.isHyper()) {
                sa.put(skil, new SkillEntry(0, (byte) 1, -1));
                if (skil.getHyper() == 1) {
                    spp++;
                } else if (skil.getHyper() == 2) {
                    sap++;
                }
            }
        }
        chr.gainMeso(-price, false);
        chr.changeSkillsLevel(sa, true);
        chr.gainHSP(0, spp);
        chr.gainHSP(1, sap);
    }
}
