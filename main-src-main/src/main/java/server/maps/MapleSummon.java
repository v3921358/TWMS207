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
package server.maps;

import java.awt.Point;

import client.MapleCharacter;
import client.MapleClient;
import client.skill.SkillFactory;
import client.anticheat.CheatingOffense;
import constants.SkillConstants;
import server.MapleStatEffect;
import tools.packet.CField.SummonPacket;

public class MapleSummon extends AnimatedMapleMapObject {

    private final int ownerid, skillLevel, ownerLevel, skill;
    private MapleMap map; // required for instanceMaps
    private short hp;
    private boolean changedMap = false;
    private SummonMovementType movementType;
    // Since player can have more than 1 summon [Pirate]
    // Let's put it here instead of cheat tracker
    private int lastSummonAttackTime;
    private byte Summon_tickResetCount;
    private long Server_ClientSummonTickDiff;
    private long lastPVPAttackTime;
    private long lastActiveAttackTime;
    private boolean isControl = false;
    private boolean isScream = false;
    private int SummonTime;
    private long SummonStratTime;
    private int linkmonid = 0;
    private int AttackSkill = 0, AttackSkillLv = 0;

    public MapleSummon(final MapleCharacter owner, final MapleStatEffect skill, final Point pos,
            final SummonMovementType movementType) {
        this(owner, skill.getSourceId(), skill.getLevel(), pos, movementType);
    }

    public MapleSummon(final MapleCharacter owner, final int sourceid, final int level, final Point pos,
            final SummonMovementType movementType) {
        super();
        this.ownerid = owner.getId();
        this.ownerLevel = owner.getLevel();
        this.skill = sourceid;
        this.map = owner.getMap();
        this.skillLevel = level;
        this.movementType = movementType;
        this.SummonStratTime = System.currentTimeMillis();
        this.lastActiveAttackTime = 0;

        MapleFootholdTree fhTree = map.getFootholds();
        if (pos.x < fhTree.getMinDropX()) {
            pos.x = fhTree.getMinDropX();
        } else if (pos.x > fhTree.getMaxDropX()) {
            pos.x = fhTree.getMaxDropX();
        }
        boolean fly = movementType == SummonMovementType.??????????????????????????? || movementType == SummonMovementType.??????????????????????????????
                || movementType == SummonMovementType.????????????;
        if (!fly) {
            MapleFoothold fh = fhTree.findNearFloor(pos);
            if (fh != null) {
                pos.y = MapleFootholdTree.calcY(fh, pos);
            }
        }

        if (movementType == SummonMovementType.????????????) {
            MapleFoothold fh = map.getFootholds().findBelow(pos, false);
            setFh(fh == null ? 0 : fh.getId());
        }

        setPosition(pos);
        setStance(owner.getStance());

        if (!isPuppet()) { // Safe up 12 bytes of data, since puppet doesn't attack.
            lastSummonAttackTime = 0;
            Summon_tickResetCount = 0;
            Server_ClientSummonTickDiff = 0;
            lastPVPAttackTime = 0;
        }
    }

    @Override
    public final void sendSpawnData(final MapleClient client) {
    }

    @Override
    public final void sendDestroyData(final MapleClient client) {
        client.getSession().writeAndFlush(SummonPacket.removeSummon(this, false));
    }

    public final void updateMap(final MapleMap map) {
        this.map = map;
    }

    public int getSummonTime() {
        return SummonTime;
    }

    public int SummonTime(int bufftime) {
        SummonTime = bufftime - (int) (System.currentTimeMillis() - SummonStratTime);
        return SummonTime;
    }

    public final MapleCharacter getOwner() {
        return map.getCharacterById(ownerid);
    }

    public final int getOwnerId() {
        return ownerid;
    }

    public boolean setControl(boolean ss) {
        return this.isControl = ss;
    }

    public void setAttackSkill(int AttackSkill) {
        this.AttackSkill = AttackSkill;
    }

    public int getAttackSkill() {
        return this.AttackSkill;
    }

    public void setAttackSkillLv(int AttackSkillLv) {
        this.AttackSkillLv = AttackSkillLv;
    }

    public int getAttackSkillLv() {
        return this.AttackSkillLv;
    }

    public void setLinkmonid(int ss) {
        this.linkmonid = ss;
    }

    public int getLinkmonid() {
        return this.linkmonid;
    }

    public boolean getControl() {
        return isControl;
    }

    public boolean setScream(boolean ss) {
        return isScream = ss;
    }

    public boolean getScream() {
        return isScream;
    }

    public final int getOwnerLevel() {
        return ownerLevel;
    }

    public final int getSkill() {
        return skill;
    }

    public final short getHP() {
        return hp;
    }

    public final void addHP(final short delta) {
        this.hp += delta;
    }

    public boolean isMultiAttack() {// TODO ?????????::????????????????????????
        switch (skill) {
            case 2111010: // ???????????????
            case 42100010: // ???????????? - ?????????
                return false;
            case 61111002: // ????????????
            case 35111002: // ??????
            case 35121003: // ???????????? : ?????????
            case 35111001:
            case 35111009:
            case 42111003: // ????????????
            case 131002015: // ?????????
                return true;
            default:
                if (skill != 33101008 && skill < 35000000) {
                    return true;
                }
                return false;
        }
    }

    public boolean isMultiSummon() { // TODO ?????????::????????????????????????
        switch (skill) {
            case 42100010: // ???????????? - ?????????
            case 35111002: // ??????
            case 2111010: // ???????????????
            case 131002015: // ?????????
                return true;
            default:
                return false;
        }
    }

    public boolean isSummon() {
        return (isAngel()) || (SkillFactory.getSkill(skill).isSummonSkill());
    }

    public final boolean isPuppet() {
        switch (skill) {
            case 3111002:
            case 3211002:
            case 3120012:
            case 3220012:
            case 13111004:
            case 4341006:
            case 33111003:
                return true;
        }
        return isAngel();
    }

    public final boolean isAngel() {
        return SkillConstants.isAngel(skill);
    }

    public final boolean isGaviota() {
        return skill == 5211002;
    }

    public final boolean isBeholder() {
        return skill == 1321007;
    }

    public final int getSkillLevel() {
        return skillLevel;
    }

    public final SummonMovementType getMovementType() {
        return movementType;
    }

    public final int getAttackType() {// TODO ?????????::???????????????????????????
        if (isAngel()) {
            return 2;
        }
        switch (skill) {
            case 13111024: // ????????????
            case 13120007: // ??????????????????
            case 35111002: // ??????
            case 35111005:
            case 35121010:
            case 14111024: // ????????????
            case 14000027: // ????????????
                return 0;
            case 3221014: // ????????????
            case 4111007: // ????????????
            case 4211007: // ????????????
            case 36121013: // ?????????????????????
            case 12111022: // ??????
            case 42111003: // ????????????
                return 1;
            case 1321007:
            case 1301013: // ?????????
            case 36121014: // ?????????????????????
                return 2; // buffs and stuff
            case 23111008: // ????????????
            case 23111009: // ????????????
            case 23111010: // ????????????
            case 35111001: // satellite.
            case 35111009:
            case 35111010:
            case 36121002: // ?????????????????????
                return 3; // attacks what you attack
            case 35121009: // ??????????????? : RM1
                return 5; // sub summons
            case 32001014:
            case 32100010:
            case 32110017:
            case 32120019:
            case 35121003: // ???????????? : ?????????
                return 6; // charge
            case 14111010: // ?????????
                return 7;
            case 5201012: // ????????????
            case 5201013: // ???????????????
            case 5201014: // ????????????
            case 5210015: // ????????????
            case 5210016: // ???????????????
            case 5210017: // ????????????
            case 5210018: // ???????????????
                return 9;
            case 33001007: // ???????????????
            case 33001008: // ???????????????
            case 33001009: // ???????????????
            case 33001010: // ???????????????
            case 33001011: // ???????????????
            case 33001012: // ???????????????
            case 33001013: // ???????????????
            case 33001014: // ???????????????
            case 33001015: // ???????????????
                return 10;
            case 42100010: // ???????????? - ?????????
                return 11;
        }
        return 1;
    }

    public byte getRemoveStatus() {
        if (isAngel()) {
            return 10;
        }
        switch (skill) {
            case 5321003:
            case 33101008:
            case 35111002:
            case 35111005:
            case 35111011:
            case 35121009:
            case 35121010:
            case 35121011:
                return 5;
            case 23111008:
            case 23111009:
            case 23111010:
            case 35111001:
            case 35111009:
            case 35111010:
            case 35121003:
                return 10;
        }
        return 0;
    }

    @Override
    public final MapleMapObjectType getType() {
        return MapleMapObjectType.SUMMON;
    }

    public final void CheckSummonAttackFrequency(final MapleCharacter chr, final int tickcount) {
        final int tickdifference = (tickcount - lastSummonAttackTime);
        if (tickdifference < SkillFactory.getSummonData(skill).delay) {
            chr.getCheatTracker().registerOffense(CheatingOffense.FAST_SUMMON_ATTACK);
        }
        final long STime_TC = System.currentTimeMillis() - tickcount;
        final long S_C_Difference = Server_ClientSummonTickDiff - STime_TC;
        if (S_C_Difference > 500) {
            chr.getCheatTracker().registerOffense(CheatingOffense.FAST_SUMMON_ATTACK);
        }
        Summon_tickResetCount++;
        if (Summon_tickResetCount > 4) {
            Summon_tickResetCount = 0;
            Server_ClientSummonTickDiff = STime_TC;
        }
        lastSummonAttackTime = tickcount;
    }

    public final void CheckPVPSummonAttackFrequency(final MapleCharacter chr) {
        final long tickdifference = (System.currentTimeMillis() - lastPVPAttackTime);
        if (tickdifference < SkillFactory.getSummonData(skill).delay) {
            chr.getCheatTracker().registerOffense(CheatingOffense.FAST_SUMMON_ATTACK);
        }
        lastPVPAttackTime = System.currentTimeMillis();
    }

    public final boolean isChangedMap() {
        return changedMap;
    }

    public final void setChangedMap(boolean cm) {
        this.changedMap = cm;
    }

    public final boolean isSpecialSummon() {
        boolean result;
        if (skill > 131003017) { // ???????????????
            if (skill == 400011005 || skill == 400031007) { // ???????????? || ????????????
                return true;
            }
            result = skill == 400041028;
            // LABEL_13:
            if (!result) {
                // ????????????
                return skill - 400031007 <= 2 && skill - 400031007 >= 0;
            }
            return true;
        }
        if (skill == 131003017) { // ???????????????
            return true;
        }
        if (skill > 131003017) { // ???????????????
            result = skill == 131002017; // ???????????????
            // goto LABEL_13;
            if (!result) {
                // ????????????
                return skill - 400031007 <= 2 && skill - 400031007 >= 0;
            }
            return true;
        }
        // ??????????????? || ???????????? || ??????
        if (skill == 131001017 || skill == 14111024 || skill > 14121053 && skill <= 14121056) {
            return true;
        }
        // ????????????
        return skill - 400031007 <= 2 && skill - 400031007 >= 0;
    }

    public long getLastActiveAttackTime() {
        return lastActiveAttackTime;
    }

    public void updateLastActiveAttackTime() {
        this.lastActiveAttackTime = System.currentTimeMillis();
    }

}
