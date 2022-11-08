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
package server.life;

import constants.GameConstants;

public class ChangeableStats extends OverrideMonsterStats {

    public int watk, matk, acc, eva, PDRate, MDRate, pushed, speed, level;

    public ChangeableStats(MapleMonsterStats stats) {
        hp = stats.getHp();
        exp = stats.getExp();
        mp = stats.getMp();
        finalmaxHP = stats.getFinalMaxHP();
        watk = stats.getPhysicalAttack();
        matk = stats.getMagicAttack();
        acc = stats.getAcc();
        eva = stats.getEva();
        PDRate = stats.getPDRate();
        MDRate = stats.getMDRate();
        pushed = stats.getPushed();
        speed = 0;
        level = stats.getLevel();
    }

    public ChangeableStats(MapleMonsterStats stats, OverrideMonsterStats ostats) {
        hp = ostats.getHp();
        exp = ostats.getExp();
        mp = ostats.getMp();
        finalmaxHP = ostats.getFinalMaxHP();
        watk = stats.getPhysicalAttack();
        matk = stats.getMagicAttack();
        acc = stats.getAcc();
        eva = stats.getEva();
        PDRate = stats.getPDRate();
        MDRate = stats.getMDRate();
        pushed = stats.getPushed();
        speed = 0;
        level = stats.getLevel();
    }

    public ChangeableStats(MapleMonsterStats stats, int newLevel, boolean pqMob) { // here we go i think
        final double mod = (double) newLevel / (double) stats.getLevel();
        final double hpRatio = (double) stats.getHp() / (double) stats.getExp();
        final double pqMod = (pqMob ? 1.5 : 1.0); // god damn
        long tempValue = Math
                .round((!stats.isBoss() ? GameConstants.getMonsterHP(newLevel) : (stats.getHp() * mod)) * pqMod); // right
        // here
        // lol
        hp = (int) Math.min(Integer.MAX_VALUE, tempValue < 0 ? Long.MAX_VALUE : tempValue);
        if (finalmaxHP > 0) {
            finalmaxHP = Math.round(stats.getFinalMaxHP() * mod * pqMod);
        } else if (tempValue > Integer.MAX_VALUE) {
            finalmaxHP = tempValue;
        }
        if (finalmaxHP < 0) {
            finalmaxHP = Long.MAX_VALUE;
        }
        tempValue = Math
                .round((!stats.isBoss() ? (GameConstants.getMonsterHP(newLevel) / hpRatio) : (stats.getExp())) * pqMod);
        exp = (int) Math.min(Integer.MAX_VALUE, tempValue);
        tempValue = Math.round(stats.getMp() * mod * pqMod);
        mp = (int) Math.min(Integer.MAX_VALUE, tempValue);
        tempValue = Math.round(stats.getPhysicalAttack() * mod);
        watk = (int) Math.min(Integer.MAX_VALUE, tempValue);
        tempValue = Math.round(stats.getMagicAttack() * mod);
        matk = (int) Math.min(Integer.MAX_VALUE, tempValue);
        acc = Math.round(stats.getAcc() + Math.max(0, newLevel - stats.getLevel()) * 2);
        eva = Math.round(stats.getEva() + Math.max(0, newLevel - stats.getLevel()));
        tempValue = Math.round(stats.getPDRate() * mod);
        PDRate = Math.min(stats.isBoss() ? 30 : 20, (int) Math.min(Integer.MAX_VALUE, tempValue));
        tempValue = Math.round(stats.getMDRate() * mod);
        MDRate = Math.min(stats.isBoss() ? 30 : 20, (int) Math.min(Integer.MAX_VALUE, tempValue));
        tempValue = Math.round(stats.getPushed() * mod);
        pushed = (int) Math.min(Integer.MAX_VALUE, tempValue);
        speed = 0;
        level = newLevel;
    }
}
