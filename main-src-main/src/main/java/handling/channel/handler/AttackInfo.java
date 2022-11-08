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

import java.util.List;
import java.awt.Point;

import client.skill.Skill;
import constants.GameConstants;
import client.MapleCharacter;
import client.MapleJob;
import client.skill.SkillFactory;
import server.MapleStatEffect;
import tools.AttackPair;
import tools.HexTool;

public class AttackInfo {

    public int skill, charge, lastAttackTickCount, display, stance;
    public List<AttackPair> allDamage;
    public Point position, skillposition;
    public byte hits, targets, tbyte, speed, cashSlot, AOE, starSlot, animation, direction, zeroUnk, level;
    public boolean real = true;
    public boolean move = false;
    public boolean isMeleeAttack = false;
    public boolean isShootAttack = false;
    public boolean isMagicAttack = false;
    public boolean isBodyAttack = false;
    public boolean isDotAttack = false;

    public final MapleStatEffect getAttackEffect(final MapleCharacter chr, int skillLevel, final Skill theSkill) {
        if (GameConstants.isMulungSkill(skill) || GameConstants.isPyramidSkill(skill)
                || GameConstants.isInflationSkill(skill)) {
            skillLevel = 1;
        } else if (skillLevel <= 0) {
            if (chr.isShowErr()) {
                chr.showInfo("AttackEffect", true, "技能等級<=0");
            }
            return null;
        }
        int dd = ((display & 0x8000) != 0 ? (display - 0x8000) : display);
        if (GameConstants.isLinkedAttackSkill(skill)) {
            final Skill skillLink = SkillFactory.getSkill(skill);
            if (1 == 1) { // is bugged after red
                return skillLink.getEffect(skillLevel);
            }

            if (dd > SkillFactory.Delay.magic6.i && dd != SkillFactory.Delay.shot.i
                    && dd != SkillFactory.Delay.fist.i) {
                if (skillLink.getAnimation() == -1 || Math.abs(skillLink.getAnimation() - dd) > 0x10) {
                    chr.dropMessage(-1, "Animation: " + skillLink.getAnimation() + " | "
                            + HexTool.getOpcodeToString(skillLink.getAnimation()));
                    if (skillLink.getAnimation() == -1) {
                        chr.dropMessage(5,
                                "Please report this: animation for skill " + skillLink.getId() + " doesn't exist");
                    } else {
                        // AutobanManager.getInstance().autoban(chr.getClient(), "No delay hack, SkillID
                        // : " + skillLink.getId() + ", animation: " + dd + ", expected: " +
                        // skillLink.getAnimation());
                    }
                    if (theSkill.getId() == 24121003) {
                        return skillLink.getEffect(skillLevel);
                    }
                    if (MapleJob.is神之子(theSkill.getId() / 10000)) {
                        return skillLink.getEffect(skillLevel); // idk wat 2 do w/ dis
                    }
                    if (MapleJob.is幻獸師(theSkill.getId() / 11000)) {
                        return skillLink.getEffect(skillLevel);
                    }
                    return null;
                }
            }
            return skillLink.getEffect(skillLevel);
        }
        return theSkill.getEffect(skillLevel);
    }
}
