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

import client.*;
import client.MapleTrait.MapleTraitType;
import client.anticheat.CheatingOffense;
import client.character.stat.MapleDisease;
import client.familiar.FamiliarCard;
import client.inventory.*;
import client.inventory.Equip.ScrollResult;
import client.inventory.MaplePet.PetFlag;
import client.skill.Skill;
import client.skill.SkillEntry;
import client.skill.SkillFactory;
import client.skill.vcore.VCoreFactory;
import constants.GameConstants;
import constants.ItemConstants;
import database.ManagerDatabasePool;
import extensions.temporary.ScriptMessageType;
import extensions.temporary.UserEffectOpcode;
import handling.channel.ChannelServer;
import handling.world.MaplePartyCharacter;
import handling.world.World;
import scripting.NPCScriptManager;
import server.*;
import server.client.CoreDataEntry;
import server.events.MapleEvent;
import server.events.MapleEventType;
import server.life.MapleLifeFactory;
import server.life.MapleMonster;
import server.life.MapleMonsterInformationProvider;
import server.maps.*;
import server.quest.MapleQuest;
import server.shops.MapleShopFactory;
import server.stores.HiredMerchant;
import server.stores.IMaplePlayerShop;
import tools.FileoutputUtil;
import tools.Pair;
import tools.data.LittleEndianAccessor;
import tools.packet.*;
import tools.packet.CField.CScriptMan;
import tools.packet.CField.EffectPacket;
import tools.packet.CWvsContext.InfoPacket;
import tools.packet.CWvsContext.InventoryPacket;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InventoryHandler {

    public static final void ItemMove(final LittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer().hasBlockedInventory()) { // hack
            return;
        }
        c.getPlayer().setScrolledPosition((short) 0);
        c.getPlayer().updateTick(slea.readInt());
        final MapleInventoryType type = MapleInventoryType.getByType(slea.readByte());
        final short src = slea.readShort();
        final short dst = slea.readShort();
        final short quantity = slea.readShort();
        if (c.getPlayer().isShowInfo()) {
            c.getPlayer().showInfo("移動道具", false, type.name() + "::原位置:" + src + " 移動到:" + dst + " 數量:" + quantity);
        }

        if (src < 0 && dst > 0) {
            MapleInventoryManipulator.unequip(c, src, dst);
        } else if (dst < 0) {
            MapleInventoryManipulator.equip(c, src, dst);
        } else if (dst == 0) {
            MapleInventoryManipulator.drop(c, type, src, quantity);
        } else {
            MapleInventoryManipulator.move(c, type, src, dst);
        }

    }

    public static final void SwitchBag(final LittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer().hasBlockedInventory()) { // hack
            return;
        }
        c.getPlayer().setScrolledPosition((short) 0);
        c.getPlayer().updateTick(slea.readInt());
        final short src = (short) slea.readInt(); // 01 00
        final short dst = (short) slea.readInt(); // 00 00
        if (src < 100 || dst < 100) {
            return;
        }
        MapleInventoryManipulator.move(c, MapleInventoryType.ETC, src, dst);
    }

    public static final void MoveBag(final LittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer().hasBlockedInventory()) { // hack
            return;
        }
        c.getPlayer().setScrolledPosition((short) 0);
        c.getPlayer().updateTick(slea.readInt());
        final boolean srcFirst = slea.readInt() > 0;
        if (slea.readByte() != 4) { // must be etc
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        short dst = (short) slea.readInt();
        short src = slea.readShort(); // 00 00
        MapleInventoryManipulator.move(c, MapleInventoryType.ETC, srcFirst ? dst : src, srcFirst ? src : dst);
    }

    public static final void ItemSort(final LittleEndianAccessor slea, final MapleClient c) {
        c.getPlayer().updateTick(slea.readInt());
        c.getPlayer().setScrolledPosition((short) 0);
        final MapleInventoryType pInvType = MapleInventoryType.getByType(slea.readByte());
        if (pInvType == MapleInventoryType.UNDEFINED || c.getPlayer().hasBlockedInventory()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        final MapleInventory pInv = c.getPlayer().getInventory(pInvType); // Mode should correspond with
        // MapleInventoryType
        boolean sorted = false;

        while (!sorted) {
            final byte freeSlot = (byte) pInv.getNextFreeSlot();
            if (freeSlot != -1) {
                short itemSlot = -1;
                for (short i = (short) (freeSlot + 1); i <= pInv.getSlotLimit(); i++) {
                    if (pInv.getItem(i) != null) {
                        itemSlot = i;
                        break;
                    }
                }
                if (itemSlot > 0) {
                    MapleInventoryManipulator.move(c, pInvType, itemSlot, freeSlot);
                } else {
                    sorted = true;
                }
            } else {
                sorted = true;
            }
        }
        c.getSession().writeAndFlush(CWvsContext.finishedSort(pInvType.getType()));
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static final void ItemGather(final LittleEndianAccessor slea, final MapleClient c) {
        // [41 00] [E5 1D 55 00] [01]
        // [32 00] [01] [01] // Sent after

        c.getPlayer().updateTick(slea.readInt());
        c.getPlayer().setScrolledPosition((short) 0);
        if (c.getPlayer().hasBlockedInventory()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        final byte mode = slea.readByte();
        final MapleInventoryType invType = MapleInventoryType.getByType(mode);
        MapleInventory Inv = c.getPlayer().getInventory(invType);

        final List<Item> itemMap = new LinkedList<>();
        for (Item item : Inv.list()) {
            itemMap.add(item.copy()); // clone all items T___T.
        }
        for (Item itemStats : itemMap) {
            MapleInventoryManipulator.removeFromSlot(c, invType, itemStats.getPosition(), itemStats.getQuantity(), true,
                    false);
        }

        final List<Item> sortedItems = sortItems(itemMap);
        for (Item item : sortedItems) {
            MapleInventoryManipulator.addFromDrop(c, item);
        }
        c.getSession().writeAndFlush(CWvsContext.finishedGather(mode));
        c.getSession().writeAndFlush(CWvsContext.enableActions());
        itemMap.clear();
        sortedItems.clear();
    }

    private static List<Item> sortItems(final List<Item> passedMap) {
        final List<Integer> itemIds = new ArrayList<>(); // empty list.
        for (Item item : passedMap) {
            itemIds.add(item.getItemId()); // adds all item ids to the empty list to be sorted.
        }
        Collections.sort(itemIds); // sorts item ids

        final List<Item> sortedList = new LinkedList<>(); // ordered list pl0x <3.

        for (Integer val : itemIds) {
            for (Item item : passedMap) {
                if (val == item.getItemId()) { // Goes through every index and finds the first value that matches
                    sortedList.add(item);
                    passedMap.remove(item);
                    break;
                }
            }
        }
        return sortedList;
    }

    public static boolean UseRewardItem(final LittleEndianAccessor slea, final MapleClient c,
            final MapleCharacter chr) {
        // System.out.println("[Reward Item] " + slea.toString());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final boolean unseal = slea.readByte() > 0;
        return UseRewardItem(slot, itemId, unseal, c, chr);
    }

    public static boolean UseRewardItem(byte slot, int itemId, final boolean unseal, final MapleClient c,
            final MapleCharacter chr) {
        final Item toUse = c.getPlayer().getInventory(GameConstants.getInventoryType(itemId)).getItem(slot);
        c.getSession().writeAndFlush(CWvsContext.enableActions());
        if (toUse != null && toUse.getQuantity() >= 1 && toUse.getItemId() == itemId && !chr.hasBlockedInventory()) {
            if (chr.getInventory(MapleInventoryType.EQUIP).getNextFreeSlot() > -1
                    && chr.getInventory(MapleInventoryType.USE).getNextFreeSlot() > -1
                    && chr.getInventory(MapleInventoryType.SETUP).getNextFreeSlot() > -1
                    && chr.getInventory(MapleInventoryType.ETC).getNextFreeSlot() > -1) {
                final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
                final Pair<Integer, List<StructRewardItem>> rewards = ii.getRewardItem(itemId);

                if (rewards != null && rewards.getLeft() > 0) {
                    while (true) {
                        for (StructRewardItem reward : rewards.getRight()) {
                            if (reward.prob > 0 && Randomizer.nextInt(rewards.getLeft()) < reward.prob) { // Total prob
                                if (GameConstants.getInventoryType(reward.itemid) == MapleInventoryType.EQUIP) {
                                    final Item item = ii.getEquipById(reward.itemid);
                                    if (reward.period > 0) {
                                        item.setExpiration(System.currentTimeMillis() + (reward.period * 60 * 60 * 10));
                                    }
                                    item.setGMLog(
                                            "Reward item: " + itemId + " 時間:" + FileoutputUtil.CurrentReadable_Date());
                                    MapleInventoryManipulator.addbyItem(c, item);
                                } else {
                                    MapleInventoryManipulator.addById(c, reward.itemid, reward.quantity,
                                            "Reward item: " + itemId + " 時間:" + FileoutputUtil.CurrentReadable_Date());
                                }
                                MapleInventoryManipulator.removeById(c, GameConstants.getInventoryType(itemId), itemId,
                                        1, false, false);

                                c.getSession().writeAndFlush(
                                        EffectPacket.showRewardItemAnimation(reward.itemid, reward.effect));
                                chr.getMap().broadcastMessage(chr,
                                        EffectPacket.showRewardItemAnimation(reward.itemid, reward.effect, chr), false);
                                return true;
                            }
                        }
                    }
                } else {
                    if (itemId == 2028162) { // custom test
                        List<Integer> items;
                        Integer[] itemArray = {1002140, 1302000, 1302001, 1302002, 1302003, 1302004, 1302005, 1302006,
                            1302007};
                        items = Arrays.asList(itemArray);
                        if (unseal) {
                            MapleInventoryManipulator.removeById(c, GameConstants.getInventoryType(itemId), itemId, 1,
                                    false, false);
                            Item item = ii.getEquipById(items.get(Randomizer.nextInt(items.size())));
                            MapleInventoryManipulator.addbyItem(c, item);
                            c.getSession().writeAndFlush(EffectPacket.unsealBox(item.getItemId()));
                            c.getSession().writeAndFlush(EffectPacket.showRewardItemAnimation(2028162, "")); // sealed
                            // box
                        } else {
                            c.getSession().writeAndFlush(CField.sendSealedBox(slot, 2028162, items)); // sealed box
                        }
                    }
                    if (itemId >= 2028154 && itemId <= 2028156 || itemId >= 2028161 && itemId <= 2028165) {
                        // sealed box
                        List<Integer> items = GameConstants.getSealedBoxItems(itemId);
                        if (items.size() < 1) {
                            chr.dropMessage(6, "Failed to find rewards.");
                            return false;
                        }
                        if (unseal) {
                            MapleInventoryManipulator.removeById(c, GameConstants.getInventoryType(itemId), itemId, 1,
                                    false, false);
                            Item item = ii.getEquipById(items.get(Randomizer.nextInt(items.size())));
                            MapleInventoryManipulator.addbyItem(c, item);
                            c.getSession().writeAndFlush(EffectPacket.unsealBox(item.getItemId()));
                            c.getSession().writeAndFlush(EffectPacket.showRewardItemAnimation(itemId, ""));
                        } else {
                            c.getSession().writeAndFlush(CField.sendSealedBox(slot, itemId, items));
                        }
                        return true;
                    }
                    switch (itemId) {
                        default:
                            chr.dropMessage(6, "Unknown error.");
                            break;
                    }
                }
            } else {
                chr.dropMessage(6, "Insufficient inventory slot.");
            }
        }
        return false;
    }

    public static void UseExpItem(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        if (chr == null || !chr.isAlive() || chr.getMap() == null || chr.hasBlockedInventory() || chr.inPVP()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final Item toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);
        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (!MapleItemInformationProvider.getInstance().getEquipStats(itemId).containsKey("exp")) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        MapleItemInformationProvider.getInstance().getEquipStats(itemId).get("exp");
        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
    }

    public static final void UseItem(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        if (chr == null || !chr.isAlive() || chr.getMapId() == 749040100 || chr.getMap() == null
                || chr.hasDisease(MapleDisease.無法使用藥水) || chr.hasBlockedInventory() || chr.inPVP()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        final long time = System.currentTimeMillis();
        if (chr.getNextConsume() > time) {
            chr.dropMessage(5, "目前無法使用。");
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final Item toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (!FieldLimitType.PotionUse.check(chr.getMap().getFieldLimit())) { // cwk quick hack
            if (MapleItemInformationProvider.getInstance().getItemEffect(toUse.getItemId()).applyTo(chr)) {
                MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
                if (chr.getMap().getConsumeItemCoolTime() > 0) {
                    chr.setNextConsume(time + (chr.getMap().getConsumeItemCoolTime() * 1000));
                }
            }

        } else {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
        }
    }

    public static final void UseCosmetic(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        if (chr == null || !chr.isAlive() || chr.getMap() == null || chr.hasBlockedInventory() || chr.inPVP()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final Item toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);
        final int defaultGender = (itemId / 1000) % 10;

        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId || itemId / 10000 != 254 || (defaultGender != chr.getGender() && defaultGender <= 1) || (defaultGender >= 1 && ItemConstants.getItemGenderUnk(toUse.getItemId()) != chr.getGender())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (MapleItemInformationProvider.getInstance().getItemEffect(toUse.getItemId()).applyTo(chr)) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
        }
    }

    public static final void UseReturnScroll(final LittleEndianAccessor slea, final MapleClient c,
            final MapleCharacter chr) {
        if (!chr.isAlive() || chr.getMapId() == 749040100 || chr.hasBlockedInventory() || chr.isInBlockedMap()
                || chr.inPVP()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final Item toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (!FieldLimitType.PotionUse.check(chr.getMap().getFieldLimit())) {
            if (MapleItemInformationProvider.getInstance().getItemEffect(toUse.getItemId()).applyReturnScroll(chr)) {
                MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
            } else {
                c.getSession().writeAndFlush(CWvsContext.enableActions());
            }
        } else {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
        }
    }

    public static final void UseAlienSocket(final LittleEndianAccessor slea, final MapleClient c) {
        c.getPlayer().updateTick(slea.readInt());
        c.getPlayer().setScrolledPosition((short) 0);
        final Item alienSocket = c.getPlayer().getInventory(MapleInventoryType.USE).getItem((byte) slea.readShort());
        final int alienSocketId = slea.readInt();
        final Item toMount = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem((byte) slea.readShort());
        if (alienSocket == null || alienSocketId != alienSocket.getItemId() || toMount == null
                || c.getPlayer().hasBlockedInventory()) {
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            return;
        }
        // Can only use once-> 2nd and 3rd must use NPC.
        final Equip eqq = (Equip) toMount;
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(eqq.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (eqq.getSocketState() != 0) { // Used before
            c.getPlayer().dropMessage(1, "This item already has a socket.");
        } else {
            c.getSession().writeAndFlush(CCashShop.useAlienSocket(false));
            eqq.setSocket(0, 1); // First socket, GMS removed the other 2
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, alienSocket.getPosition(), (short) 1,
                    false);
            c.getPlayer().forceReAddItem(toMount);
        }
        c.getSession().writeAndFlush(CCashShop.useAlienSocket(true));
        // c.getPlayer().fakeRelog();
        // c.getPlayer().dropMessage(1, "Added 1 socket successfully to " + toMount);
    }

    public static final void UseNebulite(final LittleEndianAccessor slea, final MapleClient c) {
        c.getPlayer().updateTick(slea.readInt());
        c.getPlayer().setScrolledPosition((short) 0);
        final Item nebulite = c.getPlayer().getInventory(MapleInventoryType.SETUP).getItem((byte) slea.readShort());
        final int nebuliteId = slea.readInt();
        final Item toMount = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem((byte) slea.readShort());
        if (nebulite == null || nebuliteId != nebulite.getItemId() || toMount == null
                || c.getPlayer().hasBlockedInventory()) {
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            return;
        }
        final Equip eqq = (Equip) toMount;
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(eqq.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        boolean success = false;
        if (eqq.getSocket(1) == 0/* || eqq.getSocket(3) == 0 || eqq.getSocket(3) == 0 */) { // GMS removed 2nd and 3rd
            // sockets, we can put into
            // npc.
            final StructItemOption pot = ii.getSocketInfo(nebuliteId);
            if (pot != null && ItemConstants.方塊.optionTypeFits(pot.optionType, eqq.getItemId())) {
                // if (eqq.getSocket(1) == 0) { // priority comes first
                eqq.setSocket(pot.opID, 1);
                // }// else if (eqq.getSocket(3) == 0) {
                // eqq.setSocket(pot.opID, 2);
                // } else if (eqq.getSocket(3) == 0) {
                // eqq.setSocket(pot.opID, 3);
                // }
                MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.SETUP, nebulite.getPosition(), (short) 1,
                        false);
                c.getPlayer().forceReAddItem(toMount);
                success = true;
            }
        }
        c.getPlayer().getMap().broadcastMessage(CField.showNebuliteEffect(c.getPlayer().getId(), success));
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static final void UseNebuliteFusion(final LittleEndianAccessor slea, final MapleClient c) {
        c.getPlayer().updateTick(slea.readInt());
        c.getPlayer().setScrolledPosition((short) 0);
        final int nebuliteId1 = slea.readInt();
        final Item nebulite1 = c.getPlayer().getInventory(MapleInventoryType.SETUP).getItem((byte) slea.readShort());
        final int nebuliteId2 = slea.readInt();
        final Item nebulite2 = c.getPlayer().getInventory(MapleInventoryType.SETUP).getItem((byte) slea.readShort());
        final long mesos = slea.readInt();
        final int premiumQuantity = slea.readInt();
        if (nebulite1 == null || nebulite2 == null || nebuliteId1 != nebulite1.getItemId()
                || nebuliteId2 != nebulite2.getItemId() || (mesos == 0 && premiumQuantity == 0)
                || (mesos != 0 && premiumQuantity != 0) || mesos < 0 || premiumQuantity < 0
                || c.getPlayer().hasBlockedInventory()) {
            c.getPlayer().dropMessage(1, "Failed to fuse Nebulite.");
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            return;
        }
        final int grade1 = GameConstants.getNebuliteGrade(nebuliteId1);
        final int grade2 = GameConstants.getNebuliteGrade(nebuliteId2);
        final int highestRank = grade1 > grade2 ? grade1 : grade2;
        if (grade1 == -1 || grade2 == -1 || (highestRank == 3 && premiumQuantity != 2)
                || (highestRank == 2 && premiumQuantity != 1) || (highestRank == 1 && mesos != 5000)
                || (highestRank == 0 && mesos != 3000) || (mesos > 0 && c.getPlayer().getMeso() < mesos)
                || (premiumQuantity > 0 && c.getPlayer().getItemQuantity(4420000, false) < premiumQuantity)
                || grade1 >= 4 || grade2 >= 4
                || (c.getPlayer().getInventory(MapleInventoryType.SETUP).getNumFreeSlot() < 1)) { // 4000 + = S, 3000 +
            // = A, 2000 + = B,
            // 1000 + = C, else =
            // D
            c.getSession().writeAndFlush(CField.useNebuliteFusion(c.getPlayer().getId(), 0, false));
            return; // Most of them were done in client, so we just send the unsuccessfull packet,
            // as it is only here when they packet edit.
        }
        final int avg = (grade1 + grade2) / 2; // have to revise more about grades.
        final int rank = Randomizer.isSuccess(4)
                ? (Randomizer.isSuccess(70) ? (avg != 3 ? (avg + 1) : avg) : (avg != 0 ? (avg - 1) : 0))
                : avg;
        // 4 % chance to up/down 1 grade, (70% to up, 30% to down), cannot up to S
        // grade. =)
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final List<StructItemOption> pots = new LinkedList<>(ii.getAllSocketInfo(rank).values());
        int newId = 0;
        while (newId == 0) {
            StructItemOption pot = pots.get(Randomizer.nextInt(pots.size()));
            if (pot != null) {
                newId = pot.opID;
            }
        }
        if (mesos > 0) {
            c.getPlayer().gainMeso(-mesos, true);
        } else if (premiumQuantity > 0) {
            MapleInventoryManipulator.removeById(c, MapleInventoryType.ETC, 4420000, premiumQuantity, false, false);
        }
        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.SETUP, nebulite1.getPosition(), (short) 1,
                false);
        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.SETUP, nebulite2.getPosition(), (short) 1,
                false);
        MapleInventoryManipulator.addById(c, newId, (short) 1,
                "Fused from " + nebuliteId1 + " and " + nebuliteId2 + " on " + FileoutputUtil.CurrentReadable_Date());
        c.getSession().writeAndFlush(CField.useNebuliteFusion(c.getPlayer().getId(), newId, true));
    }

    public static void UseGoldenHammer(final LittleEndianAccessor slea, final MapleClient c) {
        // [21 D5 10 04] [16 00 00 00] [7B B0 25 00] [01 00 00 00] [03 00 00 00]
        c.getPlayer().updateTick(slea.readInt());
        byte slot = (byte) slea.readInt();
        int itemId = slea.readInt();
        slea.skip(4);
        byte equipslot = (byte) slea.readInt();
        Item toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(slot);
        Equip equip = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(equipslot);
        if (equip == null || toUse == null || toUse.getItemId() != itemId || toUse.getQuantity() < 1
                || c.getPlayer().hasBlockedInventory()
                || !(ItemConstants.類型.鐵鎚(toUse.getItemId()) && itemId != 2472000)) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(equip.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        if (ItemConstants.卷軸.canHammer(equip.getItemId()) && ii.getSlots(equip.getItemId()) > 0
                && equip.getViciousHammer() < 1) {
            Map<String, Integer> hammerStats = ii.getEquipStats(itemId);
            int success = hammerStats == null || !hammerStats.containsKey("success") ? 100
                    : (int) hammerStats.get("success");
            if (c.getPlayer().isShowInfo()) {
                c.getPlayer().dropMessage(-6, "黃金鐵鎚提煉 - 成功幾率: " + success + "%");
            }
            if (Randomizer.isSuccess(success)) {
                equip.setUpgradeSlots((byte) (equip.getUpgradeSlots() + 1));
                c.getSession().writeAndFlush(CCashShop.GoldenHammer((byte) 0, 0));
            } else {
                c.getSession().writeAndFlush(CCashShop.GoldenHammer((byte) 0, 1));
            }
            equip.setViciousHammer((byte) (equip.getViciousHammer() + 1));
            c.getPlayer().forceReAddItem(equip);
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, true);
        } else {
            c.getPlayer().dropMessage(5, "無法使用黃金鐵鎚提煉的道具。");
            c.getSession().writeAndFlush(CCashShop.GoldenHammer((byte) 0, 1));
        }
    }

    public static void UsePlatinumHammer(final LittleEndianAccessor slea, final MapleClient c) {
        // [80 67 93 04] [0B 00 00 00] [40 B8 25 00] [01 00 00 00] [0F 00 00 00]
        c.getPlayer().updateTick(slea.readInt());
        byte slot = (byte) slea.readInt();
        int itemId = slea.readInt();
        slea.skip(4);
        byte equipslot = (byte) slea.readInt();
        Item toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(slot);
        Equip equip = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(equipslot);
        if (equip == null || toUse == null || toUse.getItemId() != itemId || toUse.getQuantity() < 1
                || c.getPlayer().hasBlockedInventory() || (itemId != 2472000 && itemId != 2472001)) {
            c.getPlayer().dropMessage(1, "使用白金鎚子提煉時出錯。");
            c.getSession().writeAndFlush(CCashShop.PlatinumHammer(3));
            return;
        }
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(equip.getEnhanctBuff())) {
            c.getPlayer().dropMessage(1, "裝備痕跡無法使用白金鎚子提煉。");
            c.getSession().writeAndFlush(CCashShop.PlatinumHammer(3));
            return;
        }
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        if (ItemConstants.卷軸.canHammer(equip.getItemId()) && ii.getSlots(equip.getItemId()) > 0
                && equip.getPlatinumHammer() < 5) {
            int success;
            switch (equip.getPlatinumHammer()) {
                case 0:
                    success = 60;
                    break;
                case 1:
                    success = 45;
                    break;
                case 2:
                    success = 30;
                    break;
                case 3:
                    success = 15;
                    break;
                case 4:
                    success = 5;
                    break;
                default:
                    success = 0;
            }
            if (c.getPlayer().isShowInfo()) {
                c.getPlayer().dropMessage(-6,
                        "白金鎚子提煉(次數：" + (equip.getPlatinumHammer() + 1) + ") - 成功幾率: " + success + "%「伺服器管理員成功率100%」");
            }
            if (c.getPlayer().isAdmin()) {
                success = 100;
            }
            if (Randomizer.isSuccess(success)) {
                equip.setUpgradeSlots((byte) (equip.getUpgradeSlots() + 1));
                equip.setPlatinumHammer((byte) (equip.getPlatinumHammer() + 1));
                c.getSession().writeAndFlush(CCashShop.PlatinumHammer(2));
            } else {
                c.getSession().writeAndFlush(CCashShop.PlatinumHammer(3));
            }
            c.getPlayer().forceReAddItem(equip);
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, true);
        } else {
            c.getPlayer().dropMessage(5, "無法使用白金鎚子提煉的道具。");
            c.getSession().writeAndFlush(CCashShop.PlatinumHammer(3));
        }
    }

    public final static void UseSoulEnchanter(final LittleEndianAccessor slea, final MapleClient c,
            final MapleCharacter chr) {// 魂卷
        c.getPlayer().updateTick(slea.readInt());
        short useslot = slea.readShort();
        short slot = slea.readShort();
        Item equip;
        boolean eqed = false;
        if (slot == (short) -11) {
            eqed = true;
            equip = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -11);
        } else {
            eqed = false;
            equip = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(slot);
        }
        MapleInventory useInventory = c.getPlayer().getInventory(MapleInventoryType.USE);
        Item enchanter = useInventory.getItem(useslot);
        if (equip == null || enchanter == null) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }

        Equip nEquip = (Equip) equip;
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(nEquip.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        if (!ItemConstants.類型.靈魂卷軸_附魔器(enchanter.getItemId()) || nEquip.getSoulEnchanter() != 0
                || !ItemConstants.類型.武器(nEquip.getItemId())) {
            if (c.getPlayer().isShowErr()) {
                c.getPlayer().showMessage(11,
                        "砸卷錯誤:使用的捲軸不是靈魂捲軸-" + !ItemConstants.類型.靈魂卷軸_附魔器(enchanter.getItemId()) + " 裝備已經上了魂卷-"
                        + (nEquip.getSoulEnchanter() != 0) + " 使用捲軸的裝備不是武器-"
                        + (!ItemConstants.類型.武器(nEquip.getItemId())));
            }
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }

        int reqLevel = ii.getReqLevel(nEquip.getItemId());
        Pair<Integer, Integer> socketReqLevel = ii.getSocketReqLevel(enchanter.getItemId());
        if (reqLevel > socketReqLevel.getLeft() || reqLevel < socketReqLevel.getRight()
                || nEquip.getUpgradeSlots() > 0) {
            c.getPlayer().dropMessage(-1, "無法使用魂之珠的道具。");
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }

        Map<String, Integer> enchanterStats = ii.getEquipStats(enchanter.getItemId());
        int success = enchanterStats == null || !enchanterStats.containsKey("success") ? 100
                : (int) enchanterStats.get("success");
        if (c.getPlayer().isShowInfo()) {
            c.getPlayer().showMessage(11, "靈魂捲軸 - 成功幾率: " + success + "%");
        }
        if (Randomizer.isSuccess(success)) {
            nEquip.setSoulName((short) 1);
            nEquip.setSoulEnchanter((short) 3);
            chr.getMap().broadcastMessage(chr, CField.showEnchanterEffect(chr.getId(), (byte) 1), true);
        } else {
            chr.getMap().broadcastMessage(chr, CField.showEnchanterEffect(chr.getId(), (byte) 0), true);
        }
        MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, enchanter.getItemId(), (short) 1, true, false);
        c.getSession().writeAndFlush(InventoryPacket.scrolledItem(enchanter, nEquip, false));
        c.getPlayer().forceReAddItem(nEquip);

        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public final static void UseSoulScroll(final LittleEndianAccessor slea, final MapleClient c,
            final MapleCharacter chr) {// 魂珠
        c.getPlayer().updateTick(slea.readInt());
        short useslot = slea.readShort();
        short slot = slea.readShort();
        Item equip;
        if (slot == (short) -11) {
            equip = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -11);
        } else {
            equip = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(slot);
        }
        if (chr.isEquippedSoulWeapon()) {
            chr.changeSkillLevel(SkillFactory.getSkill(chr.getEquippedSoulSkill()), (byte) -1, (byte) 0);
        }
        MapleInventory useInventory = c.getPlayer().getInventory(MapleInventoryType.USE);
        Item soul = useInventory.getItem(useslot);
        if (equip == null || soul == null) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        Equip nEquip = (Equip) equip;
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(nEquip.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }

        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        int soulid = soul.getItemId();
        int skillid = ii.getSoulSkill(soulid);
        if (!ItemConstants.類型.靈魂寶珠(soulid) || skillid == 0 || nEquip.getSoulEnchanter() == 0
                || !ItemConstants.類型.武器(nEquip.getItemId())) {
            if (c.getPlayer().isShowErr()) {
                c.getPlayer().showMessage(11,
                        "砸卷錯誤:使用的捲軸不是靈魂寶珠-" + !ItemConstants.類型.靈魂寶珠(soulid) + " 寶珠未添加技能-" + (skillid == 0) + " 未上魂卷-"
                        + (nEquip.getSoulEnchanter() == 0) + " 使用捲軸的裝備不是武器-"
                        + (!ItemConstants.類型.武器(nEquip.getItemId())));
            }
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }

        ArrayList<Integer> tempOption = ii.getTempOption(soulid);
        int pot;
        if (tempOption.isEmpty()) {
            if (c.getPlayer().isShowErr()) {
                c.getPlayer().showMessage(11, "砸卷錯誤:找不到寶珠潛能");
            }
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        } else if (tempOption.size() == 1) {
            pot = tempOption.get(0);
        } else {
            pot = tempOption.get(Randomizer.nextInt(tempOption.size()));
        }

        nEquip.setSoulName((short) (soulid % 1000 + 1));
        nEquip.setSoulPotential((short) pot);
        nEquip.setSoulSkill(skillid);
        chr.changeSkillLevel(SkillFactory.getSkill(skillid), (byte) 1, (byte) 1);
        c.getSession().writeAndFlush(InventoryPacket.scrolledItem(soul, nEquip, false));
        chr.getMap().broadcastMessage(chr, CField.showSoulScrollEffect(chr.getId(), (byte) 1, false), true);
        MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, soulid, (short) 1, true, false);
        c.getPlayer().forceReAddItem(nEquip);

        c.getSession().writeAndFlush(CWvsContext.enableActions());

    }

    public static void UseMagnify(final LittleEndianAccessor slea, final MapleClient c) {
        c.getPlayer().updateTick(slea.readInt());
        c.getPlayer().setScrolledPosition((short) 0);
        final byte src = (byte) slea.readShort();
        final Item magnify = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(src);
        byte eqSlot = (byte) slea.readShort();
        boolean equipped = eqSlot < 0;
        final Item toReveal = c.getPlayer()
                .getInventory(equipped ? MapleInventoryType.EQUIPPED : MapleInventoryType.EQUIP).getItem(eqSlot);

        Equip nEquip = (Equip) toReveal;
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(nEquip.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }

        if (!magnifyEquip(c, magnify, toReveal, eqSlot, 0)) {
            c.getPlayer().dropMessage(5, "鑒定的道具不滿足要求。");
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
        }
    }

    public static boolean magnifyEquip(final MapleClient c, Item magnify, Item toReveal, boolean useMagnify,
            int cubeId) {
        return magnifyEquip(c, magnify, toReveal, (byte) 0, useMagnify, true, cubeId);
    }

    public static boolean magnifyEquip(final MapleClient c, Item magnify, Item toReveal, byte eqSlot, int cubeId) {
        return magnifyEquip(c, magnify, toReveal, eqSlot, true, true, cubeId);
    }

    public static boolean magnifyEquip(final MapleClient c, Item magnify, Item toReveal, byte eqSlot,
            boolean useMagnify, boolean show, int cubeId) {
        if (toReveal == null) {
            c.getPlayer().dropMessage(5, "現在還不能進行操作。");
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return false;
        }

        final byte src = (byte) (magnify == null ? 0x7F : magnify.getPosition());
        final Equip eqq = (Equip) toReveal;
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        int reqLevel = ii.getReqLevel(eqq.getItemId());
        final int insightLevel = c.getPlayer().getTrait(MapleTraitType.insight).getLevel();
        long price;
        if (!useMagnify || src != 0x7F || (insightLevel >= 30 && reqLevel <= 30)
                || (insightLevel >= 60 && reqLevel <= 70) || (insightLevel >= 90 && reqLevel <= 120)) {
            price = -1;
        } else {
            price = GameConstants.getMagnifyPrice(eqq.getItemId());
        }
        reqLevel /= 10;

        if (c.getPlayer().isShowInfo()) {
            c.getPlayer().showMessage(8, "鑒定裝備：放大鏡 - " + magnify + " | 鑒定道具 - " + toReveal + " | 消耗楓幣 - " + price
                    + " | 洞察力等級 - " + insightLevel + " | 非放大鏡鑒定 - " + !useMagnify);
        }

        if ((eqq.getState(false) < 17 && eqq.getState(false) > 0 || eqq.getState(true) < 17 && eqq.getState(true) > 0)
                && (((price != -1 && c.getPlayer().getMeso() >= price) || price == -1)
                || (magnify != null && (magnify.getItemId() == 2460005 || magnify.getItemId() == 2460004
                || magnify.getItemId() == 2460003 || (magnify.getItemId() == 2460002 && reqLevel <= 12)
                || (magnify.getItemId() == 2460001 && reqLevel <= 7)
                || (magnify.getItemId() == 2460000 && reqLevel <= 3))))) {
            final List<List<StructItemOption>> pots = new LinkedList<>(ii.getAllPotentialInfo().values());
            boolean isBonus = !(eqq.getState(false) < 17 && eqq.getState(false) > 0);

            int lockedLine = 0;
            int locked = Math.abs(eqq.getPotential(1, isBonus)) % 1000000;
            if (locked >= 100000) {
                lockedLine = locked / 100000;
                locked %= 100000;
            } else {
                locked = 0;
            }
            int lines = eqq.getPotential(2, isBonus) != 0 ? 3 : 2;

            // 鑒定潛能
            int new_state = eqq.getState(isBonus) + 16;
            if (new_state > 20 || new_state < 17) { // incase overflow
                new_state = 17;
            }
            int cubeType = Math.abs(eqq.getPotential(3, isBonus));
            eqq.setPotential(0, 3, isBonus);
            boolean twins = ItemConstants.方塊.CubeType.前兩條相同.check(cubeType);
            // 31001 = haste, 31002 = door, 31003 = se, 31004 = hb, 41005 = combat orders,
            // 41006 = advanced blessing, 41007 = speed infusion
            for (int i = 1; i <= lines; i++) { // minimum 2 lines, max 5
                if (i == lockedLine) {
                    eqq.setPotential(locked, lockedLine, isBonus);
                    continue;
                }
                while (true) {
                    if (reqLevel >= 20) {
                        reqLevel = 19;
                    }
                    StructItemOption pot = pots.get(Randomizer.nextInt(pots.size())).get(reqLevel);
                    if (pot != null && pot.reqLevel / 10 <= reqLevel
                            && ItemConstants.方塊.optionTypeFits(pot.optionType, eqq.getItemId())
                            && ItemConstants.方塊.potentialIDFits(pot.opID, new_state,
                                    ItemConstants.方塊.CubeType.對等.check(cubeType) ? 1 : i)
                            && ItemConstants.方塊.isAllowedPotentialStat(eqq, pot.opID, isBonus,
                                    ItemConstants.方塊.CubeType.點商光環.check(cubeType))
                            && (!ItemConstants.方塊.CubeType.去掉無用潛能.check(cubeType)
                            || (ItemConstants.方塊.CubeType.去掉無用潛能.check(cubeType)
                            && !ItemConstants.方塊.isUselessPotential(pot)))) { // optionType
                        if (i == 1 && twins) {
                            eqq.setPotential(pot.opID, 2, isBonus);
                        }
                        if (i != 2 || !twins) {
                            eqq.setPotential(pot.opID, i, isBonus);
                        }
                        break;
                    }
                }
            }
            eqq.updateState(isBonus);

            // 放大鏡處理
            if (useMagnify) {
                if (!isBonus && eqq.getState(true) < 17 && eqq.getState(true) > 0) {
                    return magnifyEquip(c, magnify, toReveal, eqSlot, useMagnify, show, cubeId);
                }
                c.getPlayer().getTrait(MapleTraitType.insight).addExp(
                        (src == 0x7F || magnify == null ? 10 : ((magnify.getItemId() + 2) - 2460000)) * 2,
                        c.getPlayer());

                if (price != -1) {
                    c.getPlayer().gainMeso(-price, false);
                }
            }

            if (show) {
                c.getPlayer().getMap()
                        .broadcastMessage(CField.showMagnifyingEffect(c.getPlayer().getId(), eqq.getPosition(), false));
            }

            if (src == 0x7F || magnify == null) {
                c.getPlayer().forceReAddItem(toReveal);
            } else {
                MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, magnify.getPosition(), (short) 1,
                        false);
                c.getSession().writeAndFlush(InventoryPacket.scrolledItem(magnify, toReveal, false));
            }

            if (cubeId > 0) {
                cubeMega(c, eqq, cubeId);
            }

            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return true;
        } else {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return false;
        }
    }

    public static void addToScrollLog(int accountID, int charID, int scrollID, int itemID, byte oldSlots, byte newSlots,
            byte viciousHammer, String result, boolean ws, boolean ls, int vega) {
        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con
                    .prepareStatement("INSERT INTO scroll_log VALUES(DEFAULT, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setInt(1, accountID);
                ps.setInt(2, charID);
                ps.setInt(3, scrollID);
                ps.setInt(4, itemID);
                ps.setByte(5, oldSlots);
                ps.setByte(6, newSlots);
                ps.setByte(7, viciousHammer);
                ps.setString(8, result);
                ps.setByte(9, (byte) (ws ? 1 : 0));
                ps.setByte(10, (byte) (ls ? 1 : 0));
                ps.setInt(11, vega);
                ps.execute();
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException e) {
            FileoutputUtil.outputFileError(FileoutputUtil.PacketEx_Log, e);
        }
    }

    public static boolean UseUpgradeScroll(final short slot, final short dst, final short ws, final MapleClient c,
            final MapleCharacter chr, final boolean legendarySpirit) {
        return UseUpgradeScroll(slot, dst, ws, c, chr, 0, legendarySpirit);
    }

    public static boolean UseUpgradeScroll(final short slot, final short dst, final short ws, final MapleClient c,
            final MapleCharacter chr, final int vegas, final boolean legendarySpirit) {
        return UseUpgradeScroll(slot, dst, ws, c, chr, vegas, legendarySpirit, false);
    }

    public static boolean UseUpgradeScroll(final short slot, final short dst, final short ws, final MapleClient c,
            final MapleCharacter chr, final int vegas, final boolean legendarySpirit, final boolean isCashItem) {
        boolean whiteScroll = false; // white scroll being used?祝福捲軸
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        chr.setScrolledPosition((short) 0);
        if ((ws & 2) == 2) {
            whiteScroll = true;
        }
        Equip toScroll = null;
        if (dst < 0) {
            toScroll = (Equip) chr.getInventory(MapleInventoryType.EQUIPPED).getItem(dst);
        } else /* if (legendarySpirit) */ {// may want to create a boolean for strengthen ui? lol
            toScroll = (Equip) chr.getInventory(MapleInventoryType.EQUIP).getItem(dst);
        }
        if (toScroll == null || c.getPlayer().hasBlockedInventory()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, 0));
            return false;
        }

        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(toScroll.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, 0));
            return false;
        }

        // 07 00 F5 FF 01 00 00
        final byte oldLevel = toScroll.getLevel(); // 07
        final byte oldEnhance = toScroll.getEnhance(); // 00
        final byte oldState = toScroll.getState(false); // F5
        final byte oldBonusState = toScroll.getState(true);
        final int oldFlag = toScroll.getFlag(); // FF 01
        final short oldEnhanctBuff = toScroll.getEnhanctBuff(); // FF 01
        final short oldSlots = toScroll.getUpgradeSlots(); // v146+

        Item scroll;
        if (isCashItem) {
            scroll = chr.getInventory(MapleInventoryType.CASH).getItem(slot);
        } else {
            scroll = chr.getInventory(MapleInventoryType.USE).getItem(slot);
        }
        if (scroll == null) {
            if (chr.isShowErr()) {
                chr.showInfo("砸卷錯誤", true, "捲軸道具為空");
            }
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            return false;
        }

        if (chr.isShowInfo()) {
            chr.showMessage(10, "砸卷捲軸：" + scroll + "，砸卷道具：" + toScroll);
        }

        final Map<String, Integer> stats = ii.getEquipStats(scroll.getItemId());
        final Map<String, Integer> eqstats = ii.getEquipStats(toScroll.getItemId());
        if (ItemConstants.isForGM(scroll.getItemId()) && !chr.isIntern()) {
            chr.dropMessage(1, "這個捲軸是運營員專用捲軸。");
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            return false;
        }

        // 判斷捲軸等級限制
        int limitedLv = stats == null || !stats.containsKey("limitedLv") ? 0 : stats.get("limitedLv");

        if (limitedLv > 0 && toScroll.getLevel() < limitedLv) {
            if (chr.isShowErr()) {
                chr.showInfo("砸卷錯誤", true, "裝等超過捲軸可砸的上限 - 裝等" + toScroll.getLevel() + "卷等" + limitedLv);
            }
            return false;
        }

        if (ItemConstants.類型.阿斯旺卷軸(scroll.getItemId())) {// 阿斯旺捲軸
            if (toScroll.getUpgradeSlots() < stats.get("tuc")) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "當前裝備可升級次數為：" + toScroll.getUpgradeSlots() + " 成功或失敗需減少："
                            + stats.get("tuc") + " 的升級次數，請檢查裝備是否符合升級條件");
                }
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        } else if (scroll.getItemId() == 2040727) {// 鞋子防滑卷軸10%
            if (ItemFlag.SPIKES.check(toScroll.getFlag())) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "已有效果" + ItemFlag.SPIKES.check(toScroll.getFlag()));
                }
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        } else if (scroll.getItemId() == 2041058) {// 披風防寒卷軸10%
            if (ItemFlag.COLD.check(toScroll.getFlag())) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "已有效果" + ItemFlag.COLD.check(toScroll.getFlag()));
                }
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        } else if (!ItemConstants.類型.特殊卷軸(scroll.getItemId()) && !ItemConstants.類型.白醫卷軸(scroll.getItemId())
                && !ItemConstants.類型.裝備強化卷軸(scroll.getItemId()) && !ItemConstants.類型.潛能卷軸(scroll.getItemId())
                && !ItemConstants.類型.附加潛能卷軸(scroll.getItemId()) && !ItemConstants.類型.回真卷軸(scroll.getItemId())) {
            if (toScroll.getUpgradeSlots() < 1) {
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "當前裝備可升級次數為0");
                }
                return false;
            }
        } else if (ItemConstants.類型.裝備強化卷軸(scroll.getItemId())) {// 強化捲軸
            if (toScroll.getUpgradeSlots() >= 1
                    || toScroll.getEnhance() >= ItemConstants.卷軸.getEnhanceTimes(toScroll.getItemId()) || vegas > 0
                    || ii.isCash(toScroll.getItemId())) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true,
                            "強化捲軸檢測 裝備是否有升級次數：" + (toScroll.getUpgradeSlots() >= 1) + " 裝備星級是否大於可升級的星數："
                            + (toScroll.getEnhance() >= ItemConstants.卷軸.getEnhanceTimes(toScroll.getItemId()))
                            + "vegas：" + (vegas > 0) + " 裝備是否是為點裝：" + ii.isCash(toScroll.getItemId()));
                }
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        } else if (ItemConstants.類型.潛能卷軸(scroll.getItemId())) {// 潛能捲軸
            final boolean isEpic = scroll.getItemId() / 100 == 20497 && scroll.getItemId() < 2049750
                    && scroll.getItemId() != 2049741;// 特殊捲
            final boolean isUnique = scroll.getItemId() / 100 == 20497
                    && ((scroll.getItemId() >= 2049750 && scroll.getItemId() < 2049780)
                    || scroll.getItemId() == 2049741);// 罕見卷
            final boolean isLegend = scroll.getItemId() / 100 == 20497 && scroll.getItemId() >= 2049780;// 傳說卷
            if ((!isEpic && !isUnique && !isLegend && toScroll.getState(false) >= 1)
                    || (isEpic && toScroll.getState(false) >= 18) || (isUnique && toScroll.getState(false) >= 19)
                    || (isLegend && toScroll.getState(false) >= 20)
                    || (toScroll.getLevel() == 0 && toScroll.getUpgradeSlots() == 0
                    && !ItemConstants.類型.副手(toScroll.getItemId()) && !ItemConstants.類型.能源(toScroll.getItemId())
                    && !ItemConstants.類型.特殊潛能道具(toScroll.getItemId())/* && !isEpic && !isUnique */)
                    || vegas > 0 || ii.isCash(toScroll.getItemId()) || ItemConstants.類型.無法潛能道具(toScroll.getItemId())) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true,
                            "isPotentialScroll" + (toScroll.getState(false) >= 1) + " "
                            + (toScroll.getLevel() == 0 && toScroll.getUpgradeSlots() == 0
                            && !ItemConstants.類型.副手(toScroll.getItemId())
                            && !ItemConstants.類型.能源(toScroll.getItemId())
                            && !ItemConstants.類型.特殊潛能道具(toScroll.getItemId()))
                            + "vegas" + (vegas > 0) + "裝備是否是為點裝" + ii.isCash(toScroll.getItemId()) + "特殊潛能道具"
                            + ItemConstants.類型.無法潛能道具(toScroll.getItemId()));
                }
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        } else if (ItemConstants.類型.附加潛能卷軸(scroll.getItemId())) {// 附加潛能捲軸
            if (toScroll.getState(true) >= 1 || toScroll.getState(false) == 0
                    || (toScroll.getLevel() == 0 && toScroll.getUpgradeSlots() == 0
                    && !ItemConstants.類型.副手(toScroll.getItemId()) && !ItemConstants.類型.能源(toScroll.getItemId())
                    && !ItemConstants.類型.特殊潛能道具(toScroll.getItemId()))
                    || vegas > 0 || ii.isCash(toScroll.getItemId()) || ItemConstants.類型.無法潛能道具(toScroll.getItemId())) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true,
                            "isBonusPotentialScroll " + (toScroll.getState(true) >= 1) + " "
                            + (toScroll.getLevel() == 0 && toScroll.getUpgradeSlots() == 0
                            && !ItemConstants.類型.副手(toScroll.getItemId())
                            && !ItemConstants.類型.能源(toScroll.getItemId())
                            && !ItemConstants.類型.特殊潛能道具(toScroll.getItemId()))
                            + "vegas" + (vegas > 0) + "裝備是否是為點裝" + ii.isCash(toScroll.getItemId()) + "特殊潛能道具"
                            + ItemConstants.類型.無法潛能道具(toScroll.getItemId()));
                }
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        } else if (ItemConstants.類型.特殊卷軸(scroll.getItemId())) {// 特殊捲軸
            boolean re = false;
            if (ii.isCash(toScroll.getItemId())) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "特殊捲軸 裝備是否是為點裝：" + ii.isCash(toScroll.getItemId()));
                }
                re = true;
            } else if (ItemConstants.類型.幸運日卷軸(scroll.getItemId()) && ItemFlag.LUCKY_DAY.check(toScroll.getFlag())) {// 幸運日
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "已有效果" + ItemFlag.LUCKY_DAY.check(toScroll.getFlag()));
                }
                re = true;
            } else if (ItemConstants.類型.安全卷軸(scroll.getItemId()) && ItemFlag.SLOTS_PROTECT.check(toScroll.getFlag())) {// 安全捲軸
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "已有效果" + ItemFlag.SLOTS_PROTECT.check(toScroll.getFlag()));
                }
                re = true;
            } else if (ItemConstants.類型.卷軸保護卡(scroll.getItemId())
                    && ItemFlag.SCROLL_PROTECT.check(toScroll.getFlag())) {// 捲軸保護卡
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "已有效果" + ItemFlag.SCROLL_PROTECT.check(toScroll.getFlag()));
                }
                re = true;
            }
            if (ItemConstants.類型.保護卷軸(scroll.getItemId())) {// 保護捲軸
                int maxEqp = stats != null && stats.containsKey("maxSuperiorEqp") ? stats.get("maxSuperiorEqp") : 12;
                if (ItemFlag.SHIELD_WARD.check(toScroll.getFlag()) || maxEqp <= toScroll.getEnhance() || (stats != null
                        && stats.containsKey("maxSuperiorEqp") && !ii.isSuperiorEquip(toScroll.getItemId()))) {
                    if (chr.isShowErr()) {
                        chr.showInfo("砸卷錯誤", true,
                                "已有保護效果" + ItemFlag.SHIELD_WARD.check(toScroll.getFlag()) + "武器強化次數超過或等於捲軸限制 "
                                + (maxEqp <= toScroll.getEnhance()) + " 只能砸極真道具而裝備不是極真道具 "
                                + ((stats != null && stats.containsKey("maxSuperiorEqp")
                                && !ii.isSuperiorEquip(toScroll.getItemId()))
                                || ii.isSuperiorEquip(toScroll.getItemId())));
                    }
                    re = true;
                }
            }

            switch (scroll.getItemId()) {
                case 2530003:// 寵物專用幸運日卷軸
                case 5068000:// 寵物專用幸運日卷軸
                case 2532001:// 寵物專用終極賽特拉捲軸
                case 5068100:// 寵物安全盾牌卷軸
                case 5068200:// 寵物卷軸保護卡
                    if (ItemConstants.類型.寵物裝備(toScroll.getItemId())) {
                        break;
                    } else {
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true, "這個道具無法砸寵物專屬道具");
                        }
                        re = true;
                    }
                default:
                    if (ItemConstants.類型.寵物裝備(toScroll.getItemId())) {
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true, "這個捲軸不能在寵物裝備上砸");
                        }
                        re = true;
                    }
            }

            if (re) {
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        } else if (ItemConstants.類型.白醫卷軸(scroll.getItemId())) {// 白衣捲軸
            if (stats == null || !stats.containsKey("recover") || !eqstats.containsKey("tuc") || toScroll.getLevel()
                    + toScroll.getUpgradeSlots() + stats.get("recover") > eqstats.get("tuc") + toScroll.getHammer()) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true,
                            "白衣捲軸 - 砸卷道具不存在卷軸升級次數 " + (stats != null ? !stats.containsKey("recover") : true) + " 回復次數超過"
                            + (toScroll.getLevel() + toScroll.getUpgradeSlots()
                            + stats.get("recover") > eqstats.get("tuc") + toScroll.getHammer()));
                }
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        }

        if (!ItemConstants.卷軸.canScroll(toScroll.getItemId()) && !ItemConstants.類型.混沌卷軸(toScroll.getItemId())) {
            if (chr.isShowErr()) {
                chr.showInfo("砸卷錯誤", true, "捲軸不能對裝備進行砸卷 " + !ItemConstants.卷軸.canScroll(toScroll.getItemId())
                        + " 不是混沌捲軸 " + !ItemConstants.類型.混沌卷軸(toScroll.getItemId()));
            }
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            return false;
        }

        if ((ItemConstants.類型.白醫卷軸(scroll.getItemId()) || ItemConstants.類型.普通升級卷軸(scroll.getItemId())
                || ItemConstants.類型.混沌卷軸(scroll.getItemId())) && (vegas > 0 || ii.isCash(toScroll.getItemId()))) {
            if (chr.isShowErr()) {
                chr.showInfo("砸卷錯誤", true,
                        "捲軸是白醫捲軸 " + ItemConstants.類型.白醫卷軸(scroll.getItemId()) + " 是普通捲軸 "
                        + ItemConstants.類型.普通升級卷軸(scroll.getItemId()) + " 是混沌捲軸 "
                        + ItemConstants.類型.混沌卷軸(scroll.getItemId()) + "vegas"
                        + (vegas > 0 || ii.isCash(toScroll.getItemId())));
            }
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            return false;
        }

        if (ItemConstants.類型.提升卷(scroll.getItemId()) && toScroll.getDurability() < 0) { // not a durability item
            if (chr.isShowErr()) {
                chr.showInfo("砸卷錯誤", true, "是提升卷 " + ItemConstants.類型.提升卷(scroll.getItemId()) + " getDurability "
                        + (toScroll.getDurability() < 0));
            }
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            return false;
        } else if ((!ItemConstants.類型.提升卷(scroll.getItemId()) && !ItemConstants.類型.潛能卷軸(scroll.getItemId())
                && !ItemConstants.類型.裝備強化卷軸(scroll.getItemId()) && !ItemConstants.類型.白醫卷軸(scroll.getItemId())
                && !ItemConstants.類型.特殊卷軸(scroll.getItemId()) && !ItemConstants.類型.混沌卷軸(scroll.getItemId()))
                && toScroll.getDurability() >= 0) {
            if (chr.isShowErr()) {
                chr.showInfo("砸卷錯誤", true, "!isTablet ----- 1");
            }
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return false;
        }

        if (scroll.getItemId() == 2049405 && !(toScroll.getItemId() / 10 == 112212 && toScroll.getItemId() % 10 >= 2
                && toScroll.getItemId() % 10 <= 6)) {
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            chr.dropMessage(1, "這個捲軸只能對真. 楓葉之心使用。");
            return false;
        }

        if ((scroll.getItemId() == 2049421 || scroll.getItemId() == 2048313)
                && !(toScroll.getItemId() >= 1122224 && toScroll.getItemId() <= 1122245)) {
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            chr.dropMessage(1, "這個捲軸只能心之項鍊使用。");
            return false;
        }

        if (scroll.getItemId() == 2049423 && !(toScroll.getItemId() / 100 == 10121 && toScroll.getItemId() % 100 >= 64
                && toScroll.getItemId() % 100 <= 74 && toScroll.getItemId() % 100 != 65
                && toScroll.getItemId() % 100 != 66)) {
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            chr.dropMessage(1, "這個捲軸只能鬼娃恰吉的傷口使用。");
            return false;
        }

        Item wscroll = null;

        // Anti cheat and validation
        List<Integer> scrollReqs = ii.getScrollReqs(scroll.getItemId());
        if (scrollReqs != null && scrollReqs.size() > 0 && !scrollReqs.contains(toScroll.getItemId())) {
            if (chr.isShowErr()) {
                chr.showInfo("砸卷錯誤", true, "特定捲軸只能對指定的道具進行砸卷");
            }
            c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            return false;
        }

        if (whiteScroll) {
            wscroll = chr.getInventory(MapleInventoryType.USE).findById(2340000);
            if (wscroll == null) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "使用祝福捲軸 但祝福捲軸為空");
                }
                whiteScroll = false;
            }
        }
        if (scroll.getItemId() == 2040727) {// 鞋子防滑卷軸10%
            if (!ItemConstants.類型.鞋子(toScroll.getItemId())) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "道具不是鞋子" + !ItemConstants.類型.鞋子(toScroll.getItemId()));
                }
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        } else if (scroll.getItemId() == 2041058) {// 披風防寒卷軸10%
            if (!ItemConstants.類型.披風(toScroll.getItemId())) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "道具不是披風" + !ItemConstants.類型.披風(toScroll.getItemId()));
                }
                c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        } else if (scroll.getItemId() == 2041200) {// 龍族水晶
            switch (toScroll.getItemId()) {
                case 1122000: // 闇黑龍王項鍊
                case 1122076: { // 混沌闇黑龍王項鍊
                    break;
                }
                default: {
                    if (chr.isShowErr()) {
                        chr.showInfo("砸卷錯誤", true, "道具不是闇黑龍王項鍊或混沌闇黑龍王項鍊");
                    }
                    c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                    return false;
                }
            }
        } else if (!ItemConstants.類型.心臟(toScroll.getItemId())
                && (ItemConstants.類型.提升卷(scroll.getItemId()) || ItemConstants.類型.普通升級卷軸(scroll.getItemId()))) {// 普通捲軸
            switch (scroll.getItemId() % 1000 / 100) {
                case 0: // 單手1h
                    if (ItemConstants.類型.雙手武器(toScroll.getItemId())
                            || !(ItemConstants.類型.武器(toScroll.getItemId()) || ItemConstants.類型.雙刀(toScroll.getItemId()))) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true,
                                    "是雙手武器" + ItemConstants.類型.雙手武器(toScroll.getItemId()) + " 不是武器"
                                    + !(ItemConstants.類型.武器(toScroll.getItemId())
                                    || ItemConstants.類型.雙刀(toScroll.getItemId())));
                        }
                        return false;
                    }
                    break;
                case 1: // 雙手2h
                    if (!ItemConstants.類型.雙手武器(toScroll.getItemId()) || !ItemConstants.類型.武器(toScroll.getItemId())) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true, "不是雙手武器" + !ItemConstants.類型.雙手武器(toScroll.getItemId()) + " 不是武器"
                                    + !ItemConstants.類型.武器(toScroll.getItemId()));
                        }
                        return false;
                    }
                    break;
                case 2: // 防具armor
                    if (ItemConstants.類型.飾品(toScroll.getItemId()) || ItemConstants.類型.武器(toScroll.getItemId())
                            || ItemConstants.類型.雙刀(toScroll.getItemId())) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true,
                                    "是飾品" + ItemConstants.類型.飾品(toScroll.getItemId()) + " 是武器"
                                    + (ItemConstants.類型.武器(toScroll.getItemId())
                                    || ItemConstants.類型.雙刀(toScroll.getItemId())));
                        }
                        return false;
                    }
                    break;
                case 3: // 飾品accessory
                    if (!ItemConstants.類型.飾品(toScroll.getItemId()) || ItemConstants.類型.武器(toScroll.getItemId())
                            || ItemConstants.類型.雙刀(toScroll.getItemId())) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true,
                                    "不是飾品" + !ItemConstants.類型.飾品(toScroll.getItemId()) + " 是武器"
                                    + (ItemConstants.類型.武器(toScroll.getItemId())
                                    || ItemConstants.類型.雙刀(toScroll.getItemId())));
                        }
                        return false;
                    }
                    break;
            }
        } else if (!ItemConstants.類型.心臟(toScroll.getItemId()) && (ItemConstants.類型.TMS特殊卷軸(scroll.getItemId()))) {
            switch (scroll.getItemId() % 10000 / 1000) {
                case 0:
                    chr.dropMessage(-9, "此捲軸未修復");
                    return false;
                case 2: // 雙手2h
                    if (!ItemConstants.類型.雙手武器(toScroll.getItemId()) || !ItemConstants.類型.武器(toScroll.getItemId())) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true, "不是雙手武器" + !ItemConstants.類型.雙手武器(toScroll.getItemId()) + " 不是武器"
                                    + !ItemConstants.類型.武器(toScroll.getItemId()));
                        }
                        return false;
                    }
                    break;
                case 3: // 單手1h
                    if (ItemConstants.類型.雙手武器(toScroll.getItemId())
                            || !(ItemConstants.類型.武器(toScroll.getItemId()) || ItemConstants.類型.雙刀(toScroll.getItemId()))) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true,
                                    "是雙手武器" + ItemConstants.類型.雙手武器(toScroll.getItemId()) + " 不是武器"
                                    + !(ItemConstants.類型.武器(toScroll.getItemId())
                                    || ItemConstants.類型.雙刀(toScroll.getItemId())));
                        }
                        return false;
                    }
                    break;
                case 5: // 飾品accessory
                    if (!ItemConstants.類型.飾品(toScroll.getItemId()) || ItemConstants.類型.武器(toScroll.getItemId())
                            || ItemConstants.類型.雙刀(toScroll.getItemId())) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true,
                                    "不是飾品" + !ItemConstants.類型.飾品(toScroll.getItemId()) + " 是武器"
                                    + (ItemConstants.類型.武器(toScroll.getItemId())
                                    || ItemConstants.類型.雙刀(toScroll.getItemId())));
                        }
                        return false;
                    }
                    break;
                case 6: // 防具armor
                    if (ItemConstants.類型.飾品(toScroll.getItemId()) || ItemConstants.類型.武器(toScroll.getItemId())
                            || ItemConstants.類型.雙刀(toScroll.getItemId())) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                        if (chr.isShowErr()) {
                            chr.showInfo("砸卷錯誤", true,
                                    "是飾品" + ItemConstants.類型.飾品(toScroll.getItemId()) + " 是武器"
                                    + (ItemConstants.類型.武器(toScroll.getItemId())
                                    || ItemConstants.類型.雙刀(toScroll.getItemId())));
                        }
                        return false;
                    }
                    break;
            }
        } else if (ItemConstants.類型.飾品卷軸(scroll.getItemId()) && !ItemConstants.類型.飾品(toScroll.getItemId())) {
            if (chr.isShowErr()) {
                chr.showInfo("砸卷錯誤", true, "捲軸為配飾捲軸 但砸卷的裝備不是配飾");
            }
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            return false;
        } else if (!ItemConstants.類型.混沌卷軸(scroll.getItemId())
                && // 混沌捲軸
                !ItemConstants.類型.白醫卷軸(scroll.getItemId())
                && // 白醫
                !ItemConstants.類型.裝備強化卷軸(scroll.getItemId())
                && // 強化
                !ItemConstants.類型.潛能卷軸(scroll.getItemId())
                && // 潛能
                !ItemConstants.類型.附加潛能卷軸(scroll.getItemId())
                && // 附加潛能
                !ItemConstants.類型.特殊卷軸(scroll.getItemId())
                && // 特殊捲軸
                !ItemConstants.類型.回真卷軸(scroll.getItemId())
                && // 還原捲軸
                !ii.canScroll(scroll.getItemId(), toScroll.getItemId())// 可以使用的捲軸
                ) {
            if (chr.isShowErr()) {
                chr.dropMessage(-9, "砸卷錯誤：砸卷的捲軸無法對裝備進行砸卷");
                chr.showInfo("砸卷錯誤", true, "砸卷的捲軸無法對裝備進行砸卷");
            }
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            return false;
        }
        if (scroll.getQuantity() <= 0) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
            if (chr.isShowErr()) {
                chr.showInfo("砸卷錯誤", true, "背包捲軸[" + ii.getName(scroll.getItemId()) + "]數量為 0");
            }
            return false;
        }

        if (legendarySpirit && vegas == 0) {
            if (chr.getSkillLevel(SkillFactory.getSkill(PlayerStats.getSkillByJob(1003, chr.getJob()))) <= 0) {
                if (chr.isShowErr()) {
                    chr.showInfo("砸卷錯誤", true, "檢測是否技能砸卷 角色沒有擁有技");
                }
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                c.getSession().writeAndFlush(CField.enchantResult(0, toScroll.getItemId()));
                return false;
            }
        }

        // Scroll Success/ Failure/ Curse
        Equip scrolled = (Equip) ii.scrollEquipWithId(toScroll, scroll, whiteScroll, chr, vegas);
        if (scrolled != null) {
            scrolled.setPosition(toScroll.getPosition());
        }
        ScrollResult scrollSuccess;
        if (scrolled == null) {
            if (ItemFlag.SHIELD_WARD.check(oldFlag) || EquipStat.EnhanctBuff.NO_DESTROY.check(oldEnhanctBuff)) {
                scrolled = toScroll;
                scrollSuccess = Equip.ScrollResult.FAIL;
                scrolled.removeFlag(ItemFlag.SHIELD_WARD.getValue());
                if (EquipStat.EnhanctBuff.NO_DESTROY.check(oldEnhanctBuff)) {
                    chr.showMessage(11, "因裝備效果所以道具並未破壞。");
                } else {
                    chr.showMessage(11, "因卷軸效果所以道具並未破壞。");
                }
            } else {
                scrollSuccess = Equip.ScrollResult.CURSE;
            }
        } else {
            if ((scroll.getItemId() / 100 == 20497 && scrolled.getState(false) == 1) || scrolled.getLevel() > oldLevel
                    || scrolled.getEnhance() > oldEnhance || scrolled.getState(false) != oldState
                    || scrolled.getState(true) != oldBonusState || scrolled.getFlag() > oldFlag) {
                scrollSuccess = Equip.ScrollResult.SUCCESS;
            } else if ((ItemConstants.類型.白醫卷軸(scroll.getItemId()) && scrolled.getUpgradeSlots() > oldSlots)) {
                scrollSuccess = Equip.ScrollResult.SUCCESS;
            } else if ((ItemConstants.類型.回真卷軸(scroll.getItemId()) && scrolled != toScroll)) {
                scrolled = toScroll;
                scrollSuccess = Equip.ScrollResult.SUCCESS;
            } else {
                scrollSuccess = Equip.ScrollResult.FAIL;
            }
            if (ItemFlag.SHIELD_WARD.check(oldFlag) && !ItemConstants.類型.特殊卷軸(scroll.getItemId())) {
                scrolled.removeFlag(ItemFlag.SHIELD_WARD.getValue());
            }

            if (chr.isIntern()) {
                if (chr.isShowInfo()) {
                    chr.dropMessage(6, "添加管理員砸卷道具記錄屬性。");
                }
                scrolled.addFlag(ItemFlag.CRAFTED.getValue());
                scrolled.setOwner(chr.getName());
            }
        }
        // Update
        if (ItemFlag.SCROLL_PROTECT.check(oldFlag)) {
            if (scrolled != null) {
                scrolled.removeFlag(ItemFlag.SCROLL_PROTECT.getValue());
            }
            if (scrollSuccess == Equip.ScrollResult.SUCCESS) {
                chr.getInventory(GameConstants.getInventoryType(scroll.getItemId())).removeItem(scroll.getPosition(),
                        (short) 1, false);
            } else {
                chr.showMessage(11, new StringBuilder().append("由於捲軸的效果，捲軸").append(ii.getName(scroll.getItemId()))
                        .append("沒有消失。").toString());
            }
        } else {
            chr.getInventory(GameConstants.getInventoryType(scroll.getItemId())).removeItem(scroll.getPosition(),
                    (short) 1, false);
        }
        if (whiteScroll) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, wscroll.getPosition(), (short) 1, false,
                    false);
        } else if (scrollSuccess == Equip.ScrollResult.FAIL && scrolled.getUpgradeSlots() < oldSlots
                && c.getPlayer().getInventory(MapleInventoryType.CASH).findById(5640000) != null) {
            chr.setScrolledPosition(scrolled.getPosition());
            if (vegas == 0) {
                c.getSession().writeAndFlush(CWvsContext.pamSongUI());
            }
        }

        if (scrollSuccess == Equip.ScrollResult.CURSE) {
            c.getSession().writeAndFlush(InventoryPacket.scrolledItem(scroll, toScroll, true));
            if (dst < 0) {
                chr.getInventory(MapleInventoryType.EQUIPPED).removeItem(toScroll.getPosition());
            } else {
                chr.getInventory(MapleInventoryType.EQUIP).removeItem(toScroll.getPosition());
            }
        } else if (vegas == 0) {
            if (ItemConstants.類型.回真卷軸(scroll.getItemId())) {
                c.getPlayer().forceReAddItem(scrolled);
            }
            c.getSession().writeAndFlush(InventoryPacket.scrolledItem(scroll, scrolled, false));
        }

        chr.getMap().broadcastMessage(chr, CField.getScrollEffect(c.getPlayer().getId(), scrollSuccess, legendarySpirit,
                whiteScroll, scroll.getItemId(), toScroll.getItemId()), vegas == 0);
        c.getSession()
                .writeAndFlush(CField.enchantResult(
                        scrollSuccess == ScrollResult.SUCCESS ? 1 : scrollSuccess == ScrollResult.CURSE ? 2 : 0,
                        toScroll.getItemId()));
        // addToScrollLog(chr.getAccountID(), chr.getId(), scroll.getItemId(), itemID,
        // oldSlots, (byte)(scrolled == null ? -1 : scrolled.getUpgradeSlots()), oldVH,
        // scrollSuccess.name(), whiteScroll, legendarySpirit, vegas);
        // equipped item was scrolled and changed
        if (dst < 0 && (scrollSuccess == Equip.ScrollResult.SUCCESS || scrollSuccess == Equip.ScrollResult.CURSE)
                && vegas == 0) {
            chr.equipChanged();
        }
        return true;
    }

    public static boolean UseSkillBook(final byte slot, final int itemId, final MapleClient c,
            final MapleCharacter chr) {
        final Item toUse = chr.getInventory(GameConstants.getInventoryType(itemId)).getItem(slot);

        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId || chr.hasBlockedInventory()) {
            return false;
        }
        final Map<String, Integer> skilldata = MapleItemInformationProvider.getInstance()
                .getEquipStats(toUse.getItemId());
        if (skilldata == null) { // Hacking or used an unknown item
            return false;
        }
        boolean canuse = false, success = false;
        int skill = 0, maxlevel = 0;

        final Integer SuccessRate = skilldata.get("success");
        final Integer ReqSkillLevel = skilldata.get("reqSkillLevel");
        final Integer MasterLevel = skilldata.get("masterLevel");

        byte i = 0;
        Integer CurrentLoopedSkillId;
        while (true) {
            CurrentLoopedSkillId = skilldata.get("skillid" + i);
            i++;
            if (CurrentLoopedSkillId == null || MasterLevel == null) {
                break; // End of data
            }
            final Skill CurrSkillData = SkillFactory.getSkill(CurrentLoopedSkillId);
            if (CurrSkillData != null && CurrSkillData.canBeLearnedBy(chr.getJob())
                    && (ReqSkillLevel == null || chr.getSkillLevel(CurrSkillData) >= ReqSkillLevel)
                    && chr.getMasterLevel(CurrSkillData) < MasterLevel) {
                canuse = true;
                if (SuccessRate == null || Randomizer.isSuccess(SuccessRate)) {
                    success = true;
                    chr.changeSingleSkillLevel(CurrSkillData, chr.getSkillLevel(CurrSkillData),
                            (byte) (int) MasterLevel);
                } else {
                    success = false;
                }
                MapleInventoryManipulator.removeFromSlot(c, GameConstants.getInventoryType(itemId), slot, (short) 1,
                        false);
                break;
            }
        }
        c.getPlayer().getMap().broadcastMessage(CWvsContext.useSkillBook(chr, skill, maxlevel, canuse, success));
        c.getSession().writeAndFlush(CWvsContext.enableActions());
        return canuse;
    }

    public static void UseExpPotion(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        int itemid = slea.readInt();
        final Item toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);
        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemid || chr.getLevel() >= 250
                || chr.hasBlockedInventory() || itemid / 10000 != 223) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        int job = chr.getJob();
        List<Integer> canList = ii.getCanAccountSharable(itemid);
        if ((canList.size() > 0 && !ii.getCanAccountSharable(itemid).contains(job))
                || ii.getCantAccountSharable(itemid).contains(job)) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }

        boolean first = false;
        boolean last = false;
        int[] limitLev = ii.getExpPotionLev(itemid);
        String info = chr.getOneInfo(GameConstants.EXP_POTION, String.valueOf(itemid));
        String[] expPot;
        if (info == null) {
            first = true;
            expPot = new String[]{"", ""};
        } else {
            expPot = info.split("#");
        }
        int potAllExp = 0;
        int expForLevel = 0;
        for (int i = limitLev[0]; i < limitLev[1]; i++) {
            potAllExp += GameConstants.getExpNeededForLevel(i);
            if (i <= chr.getLevel()) {
                expForLevel += GameConstants.getExpNeededForLevel(i);
            }
        }
        int lastLevel = expPot[0].isEmpty() ? 0 : Integer.parseInt(expPot[0]);
        int exp = expPot[1].isEmpty() ? potAllExp : Integer.parseInt(expPot[1]);
        int level = chr.getLevel();
        long needExp = chr.getNeededExp() - chr.getExp();
        long gain = 0;
        if (level < limitLev[0] || level >= limitLev[1] || lastLevel >= level || potAllExp < exp) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (exp >= needExp) {
            gain = needExp;
            exp -= needExp;
        } else {
            gain = exp;
            exp = 0;
        }
        if (level >= limitLev[1] - 1 || exp <= 0) {
            last = true;
        }
        chr.gainExp(gain, true, true, false);
        chr.dropMessage(5, String.valueOf(exp));
        c.getSession().writeAndFlush(CWvsContext.updateExpPotion(last ? 0 : 2, chr.getId(), itemid, first,
                chr.getLevel(), limitLev[1], (exp + expForLevel) - potAllExp));
        chr.updateOneInfo(GameConstants.EXP_POTION, String.valueOf(itemid),
                String.valueOf(level) + "#" + String.valueOf(exp));
        if (last) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
            chr.updateOneInfo(GameConstants.EXP_POTION, String.valueOf(itemid), null);
        }
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static void UseAbyssScroll(final LittleEndianAccessor slea, final MapleClient c) {// 世界之樹的祝福
        c.getPlayer().updateTick(slea.readInt());
        final byte scroll = (byte) slea.readShort();
        final byte equip = (byte) slea.readShort();
        slea.readByte(); // idk
        final Item toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(scroll);
        final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(equip);
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        if (toUse.getItemId() / 100 != 20485 && toUse.getItemId() / 100 != 20486
                || !ItemConstants.類型.裝備(item.getItemId()) || !ii.getEquipStats(toUse.getItemId()).containsKey("success")
                || !ii.getEquipStats(item.getItemId()).containsKey("reqLevel")) {
            System.out.println("error1 abyss scroll " + toUse.getItemId());
            c.getSession().writeAndFlush(CField.enchantResult(0, 0));
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (item == null) {
            c.getSession().writeAndFlush(CField.enchantResult(0, 0));
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        Equip nEquip = (Equip) item;
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(nEquip.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CField.enchantResult(0, 0));
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        final Integer success = ii.getEquipStats(toUse.getItemId()).get("success");
        if (success == null || Randomizer.isSuccess(success)) {
            final Equip eq = (Equip) item;
            if (toUse.getItemId() / 100 == 20485) {
                if (eq.getYggdrasilWisdom() > 0) {
                    System.out.println("error2 abyss scroll " + toUse.getItemId());
                    c.getSession().writeAndFlush(CField.enchantResult(0, item.getItemId()));
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }
                int minLevel = 0;
                int maxLevel = 0;
                if (eq.getItemId() >= 2048500 && eq.getItemId() < 2048504) {
                    minLevel = 120;
                    maxLevel = 200;
                } else if (eq.getItemId() >= 2048504 && eq.getItemId() < 2048508) {
                    minLevel = 70;
                    maxLevel = 120;
                }
                int level = ii.getEquipStats(eq.getItemId()).get("reqLevel");
                if (level < minLevel || level > maxLevel) {
                    System.out.println("error3 abyss scroll " + toUse.getItemId());
                    c.getSession().writeAndFlush(CField.enchantResult(0, item.getItemId()));
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }
                int stat = (eq.getItemId() % 10) + 1;
                if (stat > 4) {
                    stat -= 4;
                }
                eq.setYggdrasilWisdom((byte) stat);
                switch (stat) {
                    case 1:
                        eq.setStr((short) (eq.getStr(0) + 3));
                        break;
                    case 2:
                        eq.setDex((short) (eq.getDex(0) + 3));
                        break;
                    case 3:
                        eq.setInt((short) (eq.getInt(0) + 3));
                        break;
                    case 4:
                        eq.setLuk((short) (eq.getLuk(0) + 3));
                        break;
                    default:
                        break;
                }
            } else if (toUse.getItemId() / 100 == 20486) {
                eq.setFinalStrike(true);
            }
            c.getSession().writeAndFlush(CField.enchantResult(1, item.getItemId()));
        } else {
            c.getSession().writeAndFlush(CField.enchantResult(0, item.getItemId()));
        }
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static void UseCarvedSeal(final LittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer() == null || !c.getPlayer().isAlive() || c.getPlayer().getMap() == null
                || c.getPlayer().hasBlockedInventory() || c.getPlayer().inPVP()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        // slea: [90 64 C8 14] [04 00] [0F 00]
        c.getPlayer().updateTick(slea.readInt());
        final short seal = slea.readShort();
        final short equip = slea.readShort();
        final Item toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(seal);
        final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(equip);
        if (toUse == null || item == null || toUse.getItemId() / 100 != 20495 || item.getQuantity() != 1
                || MapleItemInformationProvider.getInventoryType(item.getItemId()) != MapleInventoryType.EQUIP
                || toUse.getQuantity() <= 0) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        Equip nEquip = (Equip) item;
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(nEquip.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            c.getSession().writeAndFlush(CField.enchantResult(0, item.getItemId()));
            return;
        }
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final int success = ii.getScrollSuccess(toUse.getItemId());
        if (success <= 0) {
            c.getPlayer().dropMessage(1, "捲軸道具：" + toUse + " 成功概率為：" + success + " 該捲軸可能還未修復");
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (c.getPlayer().isShowInfo()) {
            c.getPlayer().showMessage(11, "捲軸道具：" + toUse + " 成功概率為：" + success + "%");
        }
        if (Randomizer.isSuccess(success)) {
            final Equip eq = (Equip) item;
            if (eq.getState(false) < 17) {
                c.getSession().writeAndFlush(CField.enchantResult(0, eq.getItemId()));
                c.getPlayer().dropMessage(5, "這個道具的潛能無法被重置。");
                return;
            }
            if (eq.getPotential(3, false) != 0) {
                c.getSession().writeAndFlush(CField.enchantResult(0, eq.getItemId()));
                c.getPlayer().dropMessage(5, "在這道具無法使用。");
                return;
            }
            final List<List<StructItemOption>> pots = new LinkedList<>(ii.getAllPotentialInfo().values());
            final int reqLevel = ii.getReqLevel(eq.getItemId()) / 10;
            int new_state = eq.getState(false);
            if (new_state > 20 || new_state < 17) { // incase overflow
                new_state = 17;
            }
            // 31001 = haste, 31002 = door, 31003 = se, 31004 = hb, 41005 = combat orders,
            // 41006 = advanced blessing, 41007 = speed infusion
            boolean rewarded = false;
            while (!rewarded) {
                StructItemOption pot = pots.get(Randomizer.nextInt(pots.size())).get(reqLevel);
                if (pot != null && pot.reqLevel / 10 <= reqLevel
                        && ItemConstants.方塊.optionTypeFits(pot.optionType, eq.getItemId())
                        && ItemConstants.方塊.potentialIDFits(pot.opID, new_state, 1)) { // optionType
                    if (ItemConstants.方塊.isAllowedPotentialStat(eq, pot.opID, false, false)) {
                        eq.setPotential(pot.opID, 3, false);
                        rewarded = true;
                    }
                }
            }
            c.getPlayer().getMap()
                    .broadcastMessage(CField.showPotentialEx(c.getPlayer().getId(), true, toUse.getItemId()));
            c.getSession().writeAndFlush(InventoryPacket.scrolledItem(toUse, item, false));
            c.getPlayer().forceReAddItem_NoUpdate(item);
            c.getSession().writeAndFlush(CField.enchantResult(1, item.getItemId()));
        } else {
            c.getPlayer().getMap()
                    .broadcastMessage(CField.showPotentialEx(c.getPlayer().getId(), false, toUse.getItemId()));
            c.getSession().writeAndFlush(CField.enchantResult(0, item.getItemId()));
        }
        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, toUse.getPosition(), (short) 1, false);
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static void UseAdditionalItem(final LittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer() == null || !c.getPlayer().isAlive() || c.getPlayer().getMap() == null
                || c.getPlayer().hasBlockedInventory() || c.getPlayer().inPVP()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        final short seal = slea.readShort();
        final short equip = slea.readShort();
        final Item toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(seal);
        final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(equip);
        if (toUse == null || item == null
                || !(toUse.getItemId() / 1000 == 2048 && toUse.getItemId() % 1000 >= 200
                && toUse.getItemId() % 1000 <= 304)
                || item.getQuantity() != 1
                || MapleItemInformationProvider.getInventoryType(item.getItemId()) != MapleInventoryType.EQUIP
                || toUse.getQuantity() <= 0) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        Equip nEquip = (Equip) item;
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(nEquip.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final int success = ii.getScrollSuccess(toUse.getItemId());
        final boolean noCursed = ii.isNoCursedScroll(toUse.getItemId());
        if (success <= 0) {
            c.getPlayer().dropMessage(1, "卷軸道具：" + toUse + " 成功概率為：" + success + " 該卷軸可能還未修復");
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        if (c.getPlayer().isShowInfo()) {
            c.getPlayer().showMessage(11, "卷軸道具：" + toUse + " 成功概率為：" + success + "% 卷軸失敗是否不消失裝備：" + noCursed);
        }
        boolean suc = false;
        if (Randomizer.isSuccess(success)) {
            final Equip eq = (Equip) item;
            if (eq.getState(true) < 17) {
                c.getSession().writeAndFlush(CField.enchantResult(0, item.getItemId()));
                c.getPlayer().dropMessage(5, "這個道具的潛能無法被重置。");
                return;
            }
            if (eq.getPotential(3, true) != 0) {
                c.getSession().writeAndFlush(CField.enchantResult(0, item.getItemId()));
                c.getPlayer().dropMessage(5, "在這道具無法使用。");
                return;
            }
            final List<List<StructItemOption>> pots = new LinkedList<>(ii.getAllPotentialInfo().values());
            final int reqLevel = ii.getReqLevel(eq.getItemId()) / 10;
            int new_state = eq.getState(true);
            if (new_state > 20 || new_state < 17) { // incase overflow
                new_state = 17;
            }
            // 31001 = haste, 31002 = door, 31003 = se, 31004 = hb, 41005 = combat orders,
            // 41006 = advanced blessing, 41007 = speed infusion
            boolean rewarded = false;
            while (!rewarded) {
                StructItemOption pot = pots.get(Randomizer.nextInt(pots.size())).get(reqLevel);
                if (pot != null && pot.reqLevel / 10 <= reqLevel
                        && ItemConstants.方塊.optionTypeFits(pot.optionType, eq.getItemId())
                        && ItemConstants.方塊.potentialIDFits(pot.opID, new_state, 1)) { // optionType
                    if (ItemConstants.方塊.isAllowedPotentialStat(eq, pot.opID, true, false)) {
                        eq.setPotential(pot.opID, 3, true);
                        rewarded = true;
                    }
                }
            }
            suc = true;
        }

        boolean removeItem = false;
        if (!suc && !noCursed) {
            if (ItemFlag.SHIELD_WARD.check(item.getFlag())) {
                c.getPlayer().showMessage(11, "因卷軸效果所以道具並未破壞。");
            } else {
                removeItem = true;
                c.getPlayer().getInventory(MapleInventoryType.EQUIP).removeItem(item.getPosition());
            }
        }

        if (ItemFlag.SHIELD_WARD.check(item.getFlag())) {
            item.removeFlag(ItemFlag.SHIELD_WARD.getValue());
        }

        if (suc) {
            c.getSession().writeAndFlush(InventoryPacket.scrolledItem(toUse, item, false));
            c.getPlayer().forceReAddItem_NoUpdate(item);
        } else if (removeItem) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.EQUIP, item.getPosition(), (short) 1, false);
        }
        c.getPlayer().getMap().broadcastMessage(
                CField.showBonusPotentialEx(c.getPlayer().getId(), suc, toUse.getItemId(), removeItem));
        c.getSession().writeAndFlush(CField.enchantResult(suc ? 1 : 0, item.getItemId()));
        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, toUse.getPosition(), (short) 1, false);
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static void UseCube(LittleEndianAccessor slea, MapleClient c) {
        // [47 80 12 04] [0B 00] [03 00]
        c.getPlayer().updateTick(slea.readInt());
        final short slot = (short) slea.readShort();
        final Item toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(slot);
        final Equip eq = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(slea.readShort());
        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(eq.getEnhanctBuff())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        if (toUse.getItemId() / 10000 != 271 || eq == null
                || c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() < 1
                || (ii.getEquipStats(toUse.getItemId()) != null
                && ii.getEquipStats(toUse.getItemId()).containsKey("success"))) {
            c.getPlayer().getMap().broadcastMessage(
                    CField.showPotentialReset(c.getPlayer().getId(), false, toUse.getItemId(), false));
            c.getSession().writeAndFlush(CField.enchantResult(0, eq.getItemId()));
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        boolean used = c.getPlayer().useCube(toUse.getItemId(), eq);
        if (used) {
            MapleInventoryManipulator.removeFromSlot(c.getPlayer().getClient(), MapleInventoryType.USE, (short) slot,
                    (short) 1, false, true);
            c.getSession().writeAndFlush(InventoryPacket.scrolledItem(toUse, eq, false));
        }
        c.getSession().writeAndFlush(CField.enchantResult(used ? 1 : 0, eq.getItemId()));
        c.getSession().writeAndFlush(CWvsContext.enableActions());
        c.getPlayer().getMap().broadcastMessage(CField.showPotentialReset(c.getPlayer().getId(), used,
                toUse.getItemId(), toUse.getItemId() == 5062500 || toUse.getItemId() == 5062501));
    }

    public static void UseFlashCube(LittleEndianAccessor slea, MapleClient c) {
        int unk = slea.readInt();
        if (slea.available() == 0) {
            // 美容相簿-預覽
            c.getSession().writeAndFlush(CField.getSalonRespons(c.getPlayer()));
            return;
        }
        final byte type = slea.readByte();
        switch (type) {
            case 1: {// 放置楓方塊/選擇潛能
                if (c.getPlayer().getOneInfo(GameConstants.台方塊, "u") == null) {
                    int value = slea.readInt();
                    if (value > 100) {// 移除相簿
                        Map<Integer, List<Pair<Integer, Integer>>> salon = c.getPlayer().getSalon();
                        int base = value / 10000 * 10000;
                        int pos = value % 10000;
                        if (salon != null && salon.containsKey(base) && salon.get(base).size() > pos
                                && salon.get(base).get(pos).getLeft() != 0) {
                            salon.get(base).remove(pos);
                            salon.get(base).add(pos, new Pair<>(0, 0));
                            c.getSession().writeAndFlush(CField.getSalonRespons(c.getPlayer()));
                        }
                        return;
                    }
                    final short dst = (short) value; // 楓方塊
                    final Equip eq = (Equip) c.getPlayer()
                            .getInventory(dst > 0 ? MapleInventoryType.EQUIP : MapleInventoryType.EQUIPPED).getItem(dst);
                    if (EquipStat.EnhanctBuff.EQUIP_MARK.check(eq.getEnhanctBuff())) {
                        c.getSession().writeAndFlush(CField.getShimmerCubeRespons());
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                    if (c.getPlayer().getInventory(MapleInventoryType.SETUP).findById(3994895) == null || eq == null) {
                        c.getSession().writeAndFlush(CField.getShimmerCubeRespons());
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                    showMapleCubeCost(c, eq);
                    return;
                }

                int cubeId = Integer.parseInt(c.getPlayer().getOneInfo(GameConstants.台方塊, "u"));
                Equip eq = null;
                switch (cubeId) {
                    case 5062017: {// 閃耀方塊
                        int selected = slea.readShort();
                        if (selected == 1) {
                            eq = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP)
                                    .getItem(Short.valueOf(c.getPlayer().getOneInfo(GameConstants.台方塊, "p")));
                            if (eq.getItemId() != Integer.valueOf(c.getPlayer().getOneInfo(GameConstants.台方塊, "i"))) {
                                c.getSession().writeAndFlush(CWvsContext.enableActions());
                                return;
                            }
                            if (EquipStat.EnhanctBuff.EQUIP_MARK.check(eq.getEnhanctBuff())) {
                                c.getSession().writeAndFlush(CField.getFlashCubeRespons(cubeId, 1));
                                c.getSession().writeAndFlush(CWvsContext.enableActions());
                                return;
                            }
                            String[] s = c.getPlayer().getOneInfo(GameConstants.台方塊, "o").split(",");
                            eq.setPotential(Integer.valueOf(s[0]), 1, false);
                            eq.setPotential(Integer.valueOf(s[1]), 2, false);
                            if (s.length == 3) {
                                eq.setPotential(Integer.valueOf(s[2]), 3, false);
                            }
                            c.getPlayer().forceReAddItem(eq);
                        }
                        c.getSession().writeAndFlush(CField.getFlashCubeRespons(cubeId, 1));
                        break;
                    }
                    case 5062020: {// 閃炫方塊
                        int line = slea.readInt();
                        if (Integer.valueOf(c.getPlayer().getOneInfo(GameConstants.台方塊, "c")) == line) {
                            eq = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP)
                                    .getItem(Short.valueOf(c.getPlayer().getOneInfo(GameConstants.台方塊, "p")));
                            if (eq.getItemId() != Integer.valueOf(c.getPlayer().getOneInfo(GameConstants.台方塊, "i"))) {
                                c.getSession().writeAndFlush(CField.getShimmerCubeRespons());
                                c.getSession().writeAndFlush(CWvsContext.enableActions());
                                return;
                            }
                            if (EquipStat.EnhanctBuff.EQUIP_MARK.check(eq.getEnhanctBuff())) {
                                c.getSession().writeAndFlush(CField.getShimmerCubeRespons());
                                c.getSession().writeAndFlush(CWvsContext.enableActions());
                                return;
                            }
                            int pots[] = new int[line];
                            for (int i = 0; i < line; i++) {
                                pots[i] = slea.readInt();
                            }
                            boolean right = false;
                            for (int i = 0; i < pots.length; i++) {
                                String[] ss = c.getPlayer().getOneInfo(GameConstants.台方塊, "o").split(",");
                                for (String s : ss) {
                                    if (Integer.valueOf(s) == pots[i]) {
                                        if (i == pots.length - 1) {
                                            right = true;
                                        }
                                        break;
                                    }
                                }
                            }
                            if (!right) {
                                c.getSession().writeAndFlush(CField.getShimmerCubeRespons());
                                c.getSession().writeAndFlush(CWvsContext.enableActions());
                                return;
                            }
                            for (int i = 0; i < pots.length; i++) {
                                eq.setPotential(pots[i], i + 1, false);
                            }
                            c.getPlayer().forceReAddItem(eq);
                        }
                        c.getSession().writeAndFlush(CField.getShimmerCubeRespons());
                        break;
                    }
                    default: {
                        c.getPlayer().dropMessage(1, "此方塊未修復，請聯繫管理員。");
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                }
                c.getPlayer().clearInfoQuest(GameConstants.台方塊);
                if (eq != null) {
                    cubeMega(c, eq, cubeId);
                }
                break;
            }
            case 3: {// 洗方塊
                if (slea.available() == 8) {
                    int value = slea.readInt();
                    int value2 = slea.readInt();
                    if (value2 == 0) { // 楓方塊
                        final short dst = (short) value;
                        final Equip eq = (Equip) c.getPlayer()
                                .getInventory(dst > 0 ? MapleInventoryType.EQUIP : MapleInventoryType.EQUIPPED)
                                .getItem(dst);
                        if (EquipStat.EnhanctBuff.EQUIP_MARK.check(eq.getEnhanctBuff())) {
                            c.getSession().writeAndFlush(CWvsContext.enableActions());
                            return;
                        }
                        final Item cube = c.getPlayer().getInventory(MapleInventoryType.SETUP).findById(3994895);
                        if (cube == null || eq == null) {
                            c.getSession().writeAndFlush(CWvsContext.enableActions());
                            return;
                        }
                        if (useMapleCube(c, eq)) {
                            magnifyEquip(c, null, eq, (byte) eq.getPosition(), false, true, cube.getItemId());
                        } else {
                            c.getSession().writeAndFlush(CWvsContext.enableActions());
                        }
                    } else if (value < 10000) { // 美容相簿-擴充欄位
                        Map<Integer, List<Pair<Integer, Integer>>> salon = c.getPlayer().getSalon();
                        int base = value * 10000;
                        if (salon == null || !salon.containsKey(base)) {
                            return;
                        }
                        int cost = 0;
                        int baseSize = salon.get(base).size() - 2;
                        for (int i = 0; i < value2; i++) {
                            switch (i + baseSize) {
                                case -2:
                                case -1:
                                case 0:
                                    break;
                                case 1:
                                case 2:
                                    cost += 10;
                                    break;
                                case 3:
                                case 4:
                                case 5:
                                case 6:
                                case 7:
                                    cost += 20;
                                    break;
                                case 8:
                                case 9:
                                case 10:
                                case 11:
                                case 12:
                                    cost += 50;
                                    break;
                                default:
                                    cost += 100;
                            }
                        }
                        if (c.getPlayer().getCSPoints(2) < cost) {
                            return;
                        }
                        c.getPlayer().modifyCSPoints(2, -cost);
                        for (int i = 0; i < value2; i++) {
                            salon.get(base).add(new Pair<>(0, 0));
                        }
                        c.getSession().writeAndFlush(CField.getSalonRespons(c.getPlayer()));
                    } else { // 美容相簿-更變髮型
                        Map<Integer, List<Pair<Integer, Integer>>> salon = c.getPlayer().getSalon();
                        int base = value / 10000 * 10000;
                        int pos = value % 10000;
                        if (c.getPlayer().getMeso() < 100000) {
                            c.getPlayer().dropMessage(1, "楓幣不足。");
                            return;
                        }
                        if (salon == null || !salon.containsKey(base) || salon.get(base).size() < pos) {
                            return;
                        }
                        value = salon.get(base).get(pos).getLeft();
                        int chrValue;
                        if (base == 30000) {
                            chrValue = c.getPlayer().getHair();
                        } else {
                            chrValue = c.getPlayer().getFace();
                        }
                        if (!MapleItemInformationProvider.getInstance().itemExists(value)
                                || c.getPlayer().getGender() != salon.get(base).get(pos).getRight() || value == chrValue) {
                            return;
                        }
                        c.getPlayer().gainMeso(-100000, false);
                        if (base == 30000) {
                            c.getPlayer().setHair(value);
                            c.getPlayer().updateSingleStat(MapleStat.HAIR, value);
                        } else {
                            c.getPlayer().setFace(value);
                            c.getPlayer().updateSingleStat(MapleStat.FACE, value);
                        }
                        c.getPlayer().equipChanged();
                        c.getSession().writeAndFlush(CField.changeSalonRespons());
                    }
                    return;
                }

                final short slot = (short) slea.readShort();
                final short dst = slea.readShort();
                final Item toUse = c.getPlayer().getInventory(MapleInventoryType.CASH).getItem(slot);
                final Equip eq = (Equip) c.getPlayer()
                        .getInventory(dst > 0 ? MapleInventoryType.EQUIP : MapleInventoryType.EQUIPPED).getItem(dst);
                if (EquipStat.EnhanctBuff.EQUIP_MARK.check(eq.getEnhanctBuff())) {
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }
                if (toUse.getItemId() != 5062017 && toUse.getItemId() != 5062019 && toUse.getItemId() != 5062020
                        && toUse.getItemId() != 5062021 || eq == null) {// 閃耀方塊 閃耀鏡射方塊 閃炫方塊 新對等方塊
                    if (c.getPlayer().isShowErr()) {
                        c.getPlayer().showInfo("使用方塊", true, "此方塊未處理");
                    }
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }

                ArrayList<Integer> oldPots = new ArrayList<>();
                oldPots.add(eq.getPotential(1, false));
                oldPots.add(eq.getPotential(2, false));
                if (eq.getPotential(3, false) > 0) {
                    oldPots.add(eq.getPotential(3, false));
                }

                String pots = "";
                c.getPlayer().clearInfoQuest(GameConstants.台方塊);
                if (c.getPlayer().useCube(toUse.getItemId(), eq)) {
                    InventoryHandler.magnifyEquip(c, null, eq, false,
                            toUse.getItemId() == 5062020 || toUse.getItemId() == 5062017 ? 0 : toUse.getItemId());
                    switch (toUse.getItemId()) {
                        case 5062017:// 閃耀方塊
                            for (int i = 0; i < oldPots.size(); i++) {
                                pots += oldPots.get(i);
                                if (i < oldPots.size() - 1) {
                                    pots += ",";
                                }
                            }
                            c.getPlayer().forceReAddItem(eq);
                            c.getSession().writeAndFlush(CField.showFlashCubeEquip(eq));
                            break;
                        case 5062019:// 閃耀鏡射方塊
                        case 5062021:// 新對等方塊
                            MapleInventoryManipulator.removeFromSlot(c.getPlayer().getClient(), MapleInventoryType.CASH,
                                    (short) slot, (short) 1, false, true);
                            c.getSession().writeAndFlush(CField.getFlashCubeRespons(toUse.getItemId(), 0));
                            return;
                        case 5062020:// 閃炫方塊
                            ArrayList<Integer> newPots = new ArrayList<>();
                            newPots.add(eq.getPotential(1, false));
                            newPots.add(eq.getPotential(2, false));
                            if (eq.getPotential(3, false) > 0) {
                                newPots.add(eq.getPotential(3, false));
                            }
                            eq.renewPotential(0, ItemConstants.方塊.getCubeType(toUse.getItemId()), 0);
                            InventoryHandler.magnifyEquip(c, null, eq, false, 0);
                            newPots.add(eq.getPotential(1, false));
                            newPots.add(eq.getPotential(2, false));
                            if (eq.getPotential(3, false) > 0) {
                                newPots.add(eq.getPotential(3, false));
                            }
                            for (int i = 0; i < newPots.size(); i++) {
                                pots += newPots.get(i);
                                if (i < newPots.size() - 1) {
                                    pots += ",";
                                }
                            }
                            c.getSession().writeAndFlush(
                                    CField.getShimmerCubeRespons(eq.getPotential(3, false) > 0 ? 3 : 2, newPots));
                            break;
                        default:
                            c.getPlayer().dropMessage(1, "此方塊未修復，請聯繫管理員。");
                            c.getSession().writeAndFlush(CWvsContext.enableActions());
                            return;
                    }
                    c.getPlayer().updateOneInfo(GameConstants.台方塊, "c",
                            "" + (1 + (eq.getPotential(2, false) > 0 ? 1 : 0) + (eq.getPotential(3, false) > 0 ? 1 : 0)));
                    c.getPlayer().updateOneInfo(GameConstants.台方塊, "i", String.valueOf(eq.getItemId()));
                    c.getPlayer().updateOneInfo(GameConstants.台方塊, "o", pots);
                    c.getPlayer().updateOneInfo(GameConstants.台方塊, "p", String.valueOf(eq.getPosition()));
                    c.getPlayer().updateOneInfo(GameConstants.台方塊, "u", String.valueOf(toUse.getItemId()));
                    MapleInventoryManipulator.removeFromSlot(c.getPlayer().getClient(), MapleInventoryType.CASH,
                            (short) slot, (short) 1, false, true);
                }
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                break;
            }
            case 7: { // 美容相簿-儲存造型
                if (unk == 469471280) { // 刪除現金道具
                    String _2ndPassword = slea.readMapleAsciiString();
                    short invType = slea.readByte();
                    short slot = slea.readByte();
                    MapleInventoryType inv = MapleInventoryType.getByType((byte) invType);
                    if (!c.CheckSecondPassword(_2ndPassword)) {
                        c.getPlayer().dropMessage(1, "第二組密碼錯誤");
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                    if (inv == null) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                    final Item itemRemoved = c.getPlayer().getInventory(inv).getItem(slot);
                    final short quantity = itemRemoved.getQuantity();
                    MapleInventoryManipulator.removeFromSlot(c, inv, slot, quantity, false);
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }
                int value = slea.readInt();
                int base = value / 10000 * 10000;
                int pos = value % 10000;
                if (slea.readInt() != base / 10000 || (base != 20000 && base != 30000)) {
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }
                Map<Integer, List<Pair<Integer, Integer>>> salon = c.getPlayer().getSalon();
                int gender = slea.readShort();
                if (salon != null && salon.containsKey(base) && salon.get(base).size() > pos
                        && salon.get(base).get(pos).getLeft() == 0) {
                    for (Pair<Integer, Integer> p : salon.get(base)) {
                        if (p.getLeft() == (base == 30000 ? c.getPlayer().getHair() : c.getPlayer().getFace())
                                && p.getRight() == ((Integer) (int) c.getPlayer().getGender())) {
                            c.getPlayer().dropMessage(1, "已經存有相同的造型了");
                            return;
                        }
                    }
                    salon.get(base).remove(pos);
                    salon.get(base).add(pos, new Pair<>(base == 30000 ? c.getPlayer().getHair() : c.getPlayer().getFace(),
                            (int) c.getPlayer().getGender()));
                    c.getSession().writeAndFlush(CField.getSalonRespons(c.getPlayer()));
                }
                break;
            }
            case 0xF: {// 楓方塊-女神之力
                if (slea.available() == 16) {
                    final short dst = (short) slea.readLong();
                    final Equip eq = (Equip) c.getPlayer()
                            .getInventory(dst > 0 ? MapleInventoryType.EQUIP : MapleInventoryType.EQUIPPED).getItem(dst);
                    final int code = slea.readInt();
                    final short slot = (short) slea.readInt();
                    final Item toUse = c.getPlayer().getInventory(MapleInventoryType.ETC).getItem(slot);
                    if (c.getPlayer().getInventory(MapleInventoryType.SETUP).findById(3994895) == null || eq == null
                            || toUse == null) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                    if (EquipStat.EnhanctBuff.EQUIP_MARK.check(eq.getEnhanctBuff())) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                    int toLock = 0;
                    boolean free = false;
                    if (code >= 0 && code <= 2) {
                        toLock = code + 1;
                    } else if (code == 3) {
                        free = true;
                    }
                    if ((free && toUse.getItemId() != 4132000) || (toLock != 0 && toUse.getItemId() != 4132001)) {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                    if (useMapleCube(c, eq, toLock, free)) {
                        magnifyEquip(c, null, eq, (byte) eq.getPosition(), false, true, toUse.getItemId());
                        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.ETC, slot, (short) 1, false);
                    } else {
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                    }
                } else {
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                }
                break;
            }
        }
    }

    public static void cubeMega(MapleClient c, Equip eqq, int cubeId) {
        int cubeType = ItemConstants.方塊.getCubeType(cubeId);
        boolean bonus = ItemConstants.方塊.CubeType.附加潛能.check(cubeType);

        if (eqq.getState(bonus) < 20) {
            return;
        }

        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        StringBuilder msg = new StringBuilder();
        msg.append(c.getPlayer().getName()).append("使用").append(ii.getName(cubeId));
        String eqName = "[" + ii.getName(eqq.getItemId()) + "]";

        if (eqq.getOldState() < eqq.getState(bonus)) {
            msg.append("將").append(eqName).append("的");
            if (bonus) {
                msg.append("附加潛能");
            }
            msg.append("等級提升為傳說.");
        } else {
            int trueStat = 0;
            int superStat = 0;
            Map<Integer, List<StructItemOption>> pots = ii.getAllPotentialInfo();
            for (int i = 1; i <= 3; i++) {
                if (eqq.getPotential(i, bonus) >= 60000) {
                    superStat++;
                } else if (ItemConstants.類型.武器(eqq.getItemId()) || eqq.getPotential(i, bonus) >= 40000) {
                    boolean useless = false;
                    if (pots.get(eqq.getPotential(i, bonus)) != null) {
                        for (StructItemOption pot : pots.get(eqq.getPotential(i, bonus))) {
                            useless = ItemConstants.方塊.isUselessPotential(pot) || useless;
                        }
                    }
                    if (!useless) {
                        trueStat++;
                    }
                }
            }
            if (trueStat + superStat < 3 || (trueStat + superStat < 2 && ItemConstants.類型.武器(eqq.getItemId()))) {
                return;
            }
            msg.append("在").append(eqName).append("上設定了強大的");
            if (superStat > 0) {
                msg.append("尊貴");
            }
            if (bonus) {
                msg.append("附加");
            }
            msg.append("潛能.");
        }
        if (c.getPlayer().isIntern()) {
            msg.append("（管理員此訊息僅自己可見）");
            c.getSession().writeAndFlush(CWvsContext.cubeMega(msg.toString(), eqq));
        } else {
            World.Broadcast.broadcastSmega(CWvsContext.cubeMega(msg.toString(), eqq));
        }
    }

    public static boolean showMapleCubeCost(MapleClient c, Equip eq) {
        return mapleCubeAction(c, eq, false, 0, false);
    }

    public static boolean useMapleCube(MapleClient c, Equip eq) {
        return mapleCubeAction(c, eq, true, 0, false);
    }

    public static boolean useMapleCube(MapleClient c, Equip eq, int toLock, boolean free) {
        return mapleCubeAction(c, eq, true, toLock, false);
    }

    public static boolean mapleCubeAction(MapleClient c, Equip eq, boolean use, int toLock, boolean free) {
        String[] potStates = {"n", "r", "e", "u", "l"};// 無潛能,特殊,稀有,罕見,傳說
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        DateFormat fmt = new SimpleDateFormat("yyyyMMdd");
        Date dateQuest;
        Date dateNow = new Date();
        try {
            dateQuest = c.getPlayer().getOneInfo(GameConstants.楓方塊, "d") != null
                    ? fmt.parse(c.getPlayer().getOneInfo(GameConstants.楓方塊, "d"))
                    : new Date();
        } catch (ParseException ex) {
            dateQuest = new Date();
            Logger.getLogger(InventoryHandler.class.getName()).log(Level.SEVERE, null, ex);
        }
        if (Integer.parseInt(sdf.format(dateNow)) - Integer.parseInt(sdf.format(dateQuest)) > 0) {
            c.getPlayer().updateOneInfo(GameConstants.楓方塊, "d", sdf.format(dateNow));
            for (String s : potStates) {
                c.getPlayer().updateOneInfo(GameConstants.楓方塊, s, "0");
            }
        }

        Map<String, Integer> mapleCubePotTimes = new HashMap<>();
        for (String s : potStates) {
            mapleCubePotTimes.put(s, c.getPlayer().getOneInfo(GameConstants.楓方塊, s) == null ? 0
                    : Integer.parseInt(c.getPlayer().getOneInfo(GameConstants.楓方塊, s)));
        }

        int potentialState = eq.getState(false);
        if (potentialState >= 17) {
            potentialState -= 16;
        }

        if (eq.getLevel() == 0 && eq.getUpgradeSlots() == 0 && !ItemConstants.類型.副手(eq.getItemId())
                && !ItemConstants.類型.能源(eq.getItemId()) && !ItemConstants.類型.特殊潛能道具(eq.getItemId())
                || MapleItemInformationProvider.getInstance().isCash(eq.getItemId())
                || ItemConstants.類型.無法潛能道具(eq.getItemId())) {
            c.getPlayer().dropMessage(1, "在這道具無法使用。");
            c.getSession().writeAndFlush(CField.showMapleCubeCost(3, 0));
            return false;
        }

        if (ItemConstants.類型.特殊潛能道具(eq.getItemId()) && potentialState == 0) {
            c.getPlayer().dropMessage(1, "此道具只能透過專用潛能捲軸來進行潛能設定.請設定潛能後再使用.");
            c.getSession().writeAndFlush(CField.showMapleCubeCost(3, 0));
            return false;
        }

        String state = potStates[potentialState];
        if (use) {
            long price = ItemConstants.方塊.getMapleCubeCost(mapleCubePotTimes.get(state), potentialState);
            if (c.getPlayer().getMeso() < price && !free) {
                return false;
            }

            int lockPot = eq.getPotential(toLock, false);
            if (!checkPotentialLock(c.getPlayer(), eq, toLock, lockPot) && toLock > 0) {
                return false;
            }

            if (potentialState == 0) {
                eq.resetPotential(false);
                eq.setFlag(eq.getFlag() | ItemFlag.UNTRADABLE.getValue());
            } else if (!c.getPlayer().useCube(3994895, eq, toLock)) {
                return false;
            }

            eq.setFlag(eq.getFlag() | ItemFlag.MAPLE_CUBE.getValue());

            if (!free) {
                c.getPlayer().gainMeso(-price, false);
            }
            c.getPlayer().updateOneInfo(GameConstants.楓方塊, state, String.valueOf(mapleCubePotTimes.get(state) + 1));
            for (String s : potStates) {
                mapleCubePotTimes.put(s, c.getPlayer().getOneInfo(GameConstants.楓方塊, s) == null ? 0
                        : Integer.parseInt(c.getPlayer().getOneInfo(GameConstants.楓方塊, s)));
            }
            potentialState = eq.getState(false);
            if (potentialState >= 17) {
                potentialState -= 16;
            }
            state = potStates[potentialState];
        }
        c.getSession().writeAndFlush(CField.showMapleCubeCost(2,
                ItemConstants.方塊.getMapleCubeCost(mapleCubePotTimes.get(state), potentialState)));
        return true;
    }

    public static void UseCatchItem(final LittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        c.getPlayer().updateTick(slea.readInt());
        c.getPlayer().setScrolledPosition((short) 0);
        final byte slot = (byte) slea.readShort();
        final int itemid = slea.readInt();
        final MapleMonster mob = chr.getMap().getMonsterByOid(slea.readInt());
        final Item toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);
        final MapleMap map = chr.getMap();

        if (toUse != null && toUse.getQuantity() > 0 && toUse.getItemId() == itemid && mob != null
                && !chr.hasBlockedInventory() && itemid / 10000 == 227
                && MapleItemInformationProvider.getInstance().getCardMobId(itemid) == mob.getId()) {
            if (!MapleItemInformationProvider.getInstance().isMobHP(itemid) || mob.getHp() <= mob.getMobMaxHp() / 2) {
                map.broadcastMessage(MobPacket.catchMonster(mob.getObjectId(), itemid, (byte) 1));
                map.killMonster(mob, chr, true, false, (byte) 1);
                MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false, false);
                if (MapleItemInformationProvider.getInstance().getCreateId(itemid) > 0) {
                    MapleInventoryManipulator.addById(c, MapleItemInformationProvider.getInstance().getCreateId(itemid),
                            (short) 1, "用捕捉道具 " + itemid + " 捕捉, 時間：" + FileoutputUtil.CurrentReadable_Date());
                }
            } else {
                map.broadcastMessage(MobPacket.catchMonster(mob.getObjectId(), itemid, (byte) 0));
                c.getSession().writeAndFlush(CWvsContext.catchMob(mob.getId(), itemid, (byte) 0));
            }
        }
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static final void UseMountFood(final LittleEndianAccessor slea, final MapleClient c,
            final MapleCharacter chr) {
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemid = slea.readInt(); // 2260000 usually
        final Item toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);
        final MapleMount mount = chr.getMount();

        if (itemid / 10000 == 226 && toUse != null && toUse.getQuantity() > 0 && toUse.getItemId() == itemid
                && mount != null && !c.getPlayer().hasBlockedInventory()) {
            final int fatigue = mount.getFatigue();

            boolean levelup = false;
            mount.setFatigue((byte) -30);

            if (fatigue > 0) {
                mount.increaseExp();
                final int level = mount.getLevel();
                if (level < 30 && mount.getExp() >= GameConstants.getMountExpNeededForLevel(level + 1)) {
                    mount.setLevel((byte) (level + 1));
                    levelup = true;
                }
            }
            chr.getMap().broadcastMessage(CWvsContext.onSetTamingMobInfo(chr, levelup));
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
        }
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static void UseScriptedNPCItem(final LittleEndianAccessor slea, final MapleClient c,
            final MapleCharacter chr) {
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final MapleInventoryType invType = GameConstants.getInventoryType(itemId);
        final Item toUse = chr.getInventory(invType).getItem(slot);
        boolean used = false;
        long expiration_days = 0;
        int mountid = 0;
        int npc = ii.getNpc(itemId, 9010000);
        String script = "consume_" + itemId; // for now
        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId || chr.hasBlockedInventory()
                || chr.inPVP()) {
            return;
        }
        switch (toUse.getItemId()) {
            case 2435719:
            case 2435902: {
                MapleInventory mi = chr.getInventory(MapleInventoryType.USE);
                Item item = mi.getItem(slot);
                if (item != null) {
                    List<Integer> coreIds = new LinkedList<>();
                    if (true || Randomizer.isSuccess(GameConstants.OWN_JOB_V_SKILL_CHANCE)) {
                        coreIds.addAll(VCoreFactory.CoreData.getVCoresIdByJob(chr.getJob()));
                    } else if (Randomizer.isSuccess(GameConstants.BEGINNER_V_SKILL_CHANCE)) {
                        coreIds.addAll(VCoreFactory.CoreData.getVCoresIdByJob(0));
                    } else {
                        coreIds.addAll(VCoreFactory.CoreData.getAllVCoresId());
                    }
                    int randomPos = Randomizer.nextInt(coreIds.size());
                    CoreDataEntry vcore1 = VCoreFactory.CoreData.getCoreData(coreIds.get(randomPos));
                    final int[] skillIds = new int[]{0, 0, 0};
                    if (vcore1.getConnectSkill().size() > 0) {
                        skillIds[0] = vcore1.getConnectSkill().get(0);
                    }
                    switch (vcore1.getType()) {
                        case 0: {
                            // 技能核心
                            break;
                        }
                        case 1: {
                            // 強化核心
                            coreIds.remove((int) randomPos);
                            randomPos = Randomizer.nextInt(coreIds.size());
                            CoreDataEntry vcore2 = VCoreFactory.CoreData.getCoreData(coreIds.get(randomPos));
                            coreIds.remove((int) randomPos);
                            randomPos = Randomizer.nextInt(coreIds.size());
                            CoreDataEntry vcore3 = VCoreFactory.CoreData.getCoreData(coreIds.get(randomPos));
                            if (vcore2.getConnectSkill().size() > 0) {
                                skillIds[1] = vcore2.getConnectSkill().get(0);
                            }
                            if (vcore3.getConnectSkill().size() > 0) {
                                skillIds[2] = vcore3.getConnectSkill().get(0);
                            }
                            break;
                        }
                        case 2: {
                            // 特殊核心
                            break;
                        }
                    }
                    VMatrixRecord entry = new VMatrixRecord(vcore1.getCoreId(), skillIds[0], skillIds[1], skillIds[2],
                            vcore1.getMaxLevel());
                    chr.addVMatrixRecord(entry);
                    c.announce(CWvsContext.OnNodeStoneResult(vcore1.getCoreId(), entry.getSkillID1(), entry.getSkillID2(),
                            entry.getSkillID3()));
                    chr.removeItem(itemId, 1);
                    chr.dropMessage(6, "核心寶石產生耀眼的光芒，取得了一個V矩陣核心寶石。");
                    Map<MapleStat, Long> statMap = new HashMap<>();
                    statMap.put(MapleStat.SKIN, (long) 0);
                    c.announce(CWvsContext.updatePlayerStats(statMap, chr)); // SendCharacterStat(1, 0); ?
                    c.announce(CField.itemEffect(chr.getId(), itemId));
                    c.announce(CWvsContext.OnVMatrixUpdate(chr.getVMatrixRecords(), -1, 0));
                    used = true;
                }
                break;
            }
            case 2434009: { // Q彈皮卡啾寵物交換券
                Item item;
                int petItemID = 5000452;
                if (!c.getPlayer().canHold(petItemID)) {
                    c.getPlayer().dropMessage(1, "背包空間不足");
                    return;
                }
                item = new Item(itemId, (byte) 0, (short) 1, 0);
                MaplePet pet = MaplePet.createPet(petItemID);
                if (pet != null) {
                    item.setPet(pet);
                    MapleInventoryManipulator.addId(c, petItemID, (short) 1, null, pet, ii.getLife(petItemID),
                            MapleInventoryManipulator.DAY, null);
                }
                c.announce(CScriptMan.getNPCTalk(9010010, ScriptMessageType.SM_SAY, "獲得#e#z5000452##n。快點確認物品欄吧。", "00 00",
                        (byte) 3, 9010010));
                used = true;
                break;
            }
            case 2430007: { // 空白指南針
                final MapleInventory inventory = chr.getInventory(MapleInventoryType.SETUP);
                if (inventory.countById(3994102) >= 20 // Compass Letter "North"
                        && inventory.countById(3994103) >= 20 // Compass Letter "South"
                        && inventory.countById(3994104) >= 20 // Compass Letter "East"
                        && inventory.countById(3994105) >= 20) { // Compass Letter "West"
                    MapleInventoryManipulator.addById(c, 2430008, (short) 1, ""); // Gold Compass
                    MapleInventoryManipulator.removeById(c, MapleInventoryType.SETUP, 3994102, 20, false, false);
                    MapleInventoryManipulator.removeById(c, MapleInventoryType.SETUP, 3994103, 20, false, false);
                    MapleInventoryManipulator.removeById(c, MapleInventoryType.SETUP, 3994104, 20, false, false);
                    MapleInventoryManipulator.removeById(c, MapleInventoryType.SETUP, 3994105, 20, false, false);
                    used = true;
                }
                NPCScriptManager.getInstance().start(c, 2084001, null);
                break;
            }
            case 2430121: { // 定時炸彈
                c.getPlayer().getMap().spawnMonsterOnGroundBelow(MapleLifeFactory.getMonster(9300166),
                        c.getPlayer().getPosition());
                used = true;
                break;
            }
            case 2430112: // 奇幻方塊碎片
                if (c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() >= 1) {
                    if (c.getPlayer().getInventory(MapleInventoryType.USE).countById(2430112) >= 25) {
                        if (MapleInventoryManipulator.checkSpace(c, 2049400, 1, "") && MapleInventoryManipulator
                                .removeById(c, MapleInventoryType.USE, toUse.getItemId(), 25, true, false)) {
                            MapleInventoryManipulator.addById(c, 2049400, (short) 1,
                                    "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                        } else {
                            c.getPlayer().dropMessage(5, "Please make some space.");
                        }
                    } else if (c.getPlayer().getInventory(MapleInventoryType.USE).countById(2430112) >= 10) {
                        if (MapleInventoryManipulator.checkSpace(c, 2049400, 1, "") && MapleInventoryManipulator
                                .removeById(c, MapleInventoryType.USE, toUse.getItemId(), 10, true, false)) {
                            MapleInventoryManipulator.addById(c, 2049401, (short) 1,
                                    "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                        } else {
                            c.getPlayer().dropMessage(5, "Please make some space.");
                        }
                    } else {
                        c.getPlayer().dropMessage(5,
                                "There needs to be 10 Fragments for a Potential Scroll, 25 for Advanced Potential Scroll.");
                    }
                } else {
                    c.getPlayer().dropMessage(5, "Please make some space.");
                }
                break;
            case 2430481: // 傳說的奇幻方塊碎片
                if (c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() >= 1) {
                    if (c.getPlayer().getInventory(MapleInventoryType.USE).countById(2430481) >= 30) {
                        if (MapleInventoryManipulator.checkSpace(c, 2049701, 1, "") && MapleInventoryManipulator
                                .removeById(c, MapleInventoryType.USE, toUse.getItemId(), 30, true, false)) {
                            MapleInventoryManipulator.addById(c, 2049701, (short) 1,
                                    "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                        } else {
                            c.getPlayer().dropMessage(5, "Please make some space.");
                        }
                    } else if (c.getPlayer().getInventory(MapleInventoryType.USE).countById(2430481) >= 20) {
                        if (MapleInventoryManipulator.checkSpace(c, 2049300, 1, "") && MapleInventoryManipulator
                                .removeById(c, MapleInventoryType.USE, toUse.getItemId(), 20, true, false)) {
                            MapleInventoryManipulator.addById(c, 2049300, (short) 1,
                                    "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                        } else {
                            c.getPlayer().dropMessage(5, "Please make some space.");
                        }
                    } else {
                        c.getPlayer().dropMessage(5,
                                "There needs to be 20 Fragments for a Advanced Equip Enhancement Scroll, 30 for Epic Potential Scroll 80%.");
                    }
                } else {
                    c.getPlayer().dropMessage(5, "Please make some space.");
                }
                break;
            case 2430748: // 黃金楓葉
                if (c.getPlayer().getInventory(MapleInventoryType.ETC).getNumFreeSlot() >= 1) {
                    if (c.getPlayer().getInventory(MapleInventoryType.USE).countById(2430748) >= 20) {
                        if (MapleInventoryManipulator.checkSpace(c, 4420000, 1, "") && MapleInventoryManipulator
                                .removeById(c, MapleInventoryType.USE, toUse.getItemId(), 20, true, false)) {
                            MapleInventoryManipulator.addById(c, 4420000, (short) 1,
                                    "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                        } else {
                            c.getPlayer().dropMessage(5, "Please make some space.");
                        }
                    } else {
                        c.getPlayer().dropMessage(5, "There needs to be 20 Fragments for a Premium Fusion Ticket.");
                    }
                } else {
                    c.getPlayer().dropMessage(5, "Please make some space.");
                }
                break;
            case 2430692: // 光子盒子
                if (c.getPlayer().getInventory(MapleInventoryType.SETUP).getNumFreeSlot() >= 1) {
                    if (c.getPlayer().getInventory(MapleInventoryType.USE).countById(2430692) >= 1) {
                        final int rank = Randomizer.isSuccess(30) ? (Randomizer.isSuccess(4) ? 2 : 1) : 0;
                        final List<StructItemOption> pots = new LinkedList<>(ii.getAllSocketInfo(rank).values());
                        if (pots.isEmpty()) {
                            c.getPlayer().dropMessage(5, "這項道具目前不能使用");
                            break;
                        }
                        int newId = 0;
                        while (newId == 0) {
                            StructItemOption pot = pots.get(Randomizer.nextInt(pots.size()));
                            if (pot != null) {
                                newId = pot.opID;
                            }
                        }
                        if (MapleInventoryManipulator.checkSpace(c, newId, 1, "") && MapleInventoryManipulator.removeById(c,
                                MapleInventoryType.USE, toUse.getItemId(), 1, true, false)) {
                            MapleInventoryManipulator.addById(c, newId, (short) 1,
                                    "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                            c.getSession().writeAndFlush(InfoPacket.getShowItemGain(newId, (short) 1, true));
                        } else {
                            c.getPlayer().dropMessage(5, "Please make some space.");
                        }
                    } else {
                        c.getPlayer().dropMessage(5, "You do not have a Nebulite Box.");
                    }
                } else {
                    c.getPlayer().dropMessage(5, "Please make some space.");
                }
                break;
            case 5680019: { // starling hair
                // if (c.getPlayer().getGender() == 1) {
                int hair = 32150 + (c.getPlayer().getHair() % 10);
                c.getPlayer().setHair(hair);
                c.getPlayer().updateSingleStat(MapleStat.HAIR, hair);
                used = true;
                ;
                // }
                break;
            }
            case 5680020: {// starling hair
                // if (c.getPlayer().getGender() == 0) {
                int hair = 32160 + (c.getPlayer().getHair() % 10);
                c.getPlayer().setHair(hair);
                c.getPlayer().updateSingleStat(MapleStat.HAIR, hair);
                used = true;
                // }
                break;
            }
            case 3994225:
                c.getPlayer().dropMessage(5, "Please bring this item to the NPC.");
                break;
            case 2430212: // 提神飲料-薄荷思
                MapleQuestStatus marr = c.getPlayer().getQuestNAdd(MapleQuest.getInstance(GameConstants.ENERGY_DRINK));
                if (marr.getCustomData() == null) {
                    marr.setCustomData("0");
                }
                long lastTime = Long.parseLong(marr.getCustomData());
                if (lastTime + (600000) > System.currentTimeMillis()) {
                    c.getPlayer().dropMessage(5, "You can only use one energy drink per 10 minutes.");
                } else if (c.getPlayer().getFatigue() > 0) {
                    used = true;
                    c.getPlayer().setFatigue(c.getPlayer().getFatigue() - 5);
                }
                break;
            case 2430213: // 提神飲料-維他命C飲料
                marr = c.getPlayer().getQuestNAdd(MapleQuest.getInstance(GameConstants.ENERGY_DRINK));
                if (marr.getCustomData() == null) {
                    marr.setCustomData("0");
                }
                lastTime = Long.parseLong(marr.getCustomData());
                if (lastTime + (600000) > System.currentTimeMillis()) {
                    c.getPlayer().dropMessage(5, "You can only use one energy drink per 10 minutes.");
                } else if (c.getPlayer().getFatigue() > 0) {
                    used = true;
                    c.getPlayer().setFatigue(c.getPlayer().getFatigue() - 10);
                }
                break;
            case 2430220: // energy drink
            case 2430214: // energy drink
                if (c.getPlayer().getFatigue() > 0) {
                    used = true;
                    c.getPlayer().setFatigue(c.getPlayer().getFatigue() - 30);
                }
                break;
            case 2430227: // energy drink
                if (c.getPlayer().getFatigue() > 0) {
                    MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);
                    c.getPlayer().setFatigue(c.getPlayer().getFatigue() - 50);
                }
                break;
            case 2430231: // energy drink
                marr = c.getPlayer().getQuestNAdd(MapleQuest.getInstance(GameConstants.ENERGY_DRINK));
                if (marr.getCustomData() == null) {
                    marr.setCustomData("0");
                }
                lastTime = Long.parseLong(marr.getCustomData());
                if (lastTime + (600000) > System.currentTimeMillis()) {
                    c.getPlayer().dropMessage(5, "You can only use one energy drink per 10 minutes.");
                } else if (c.getPlayer().getFatigue() > 0) {
                    MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);
                    c.getPlayer().setFatigue(c.getPlayer().getFatigue() - 40);
                }
                break;
            case 2430144: // smb
                final int itemid = Randomizer.nextInt(373) + 2290000;
                if (MapleItemInformationProvider.getInstance().itemExists(itemid)
                        && !MapleItemInformationProvider.getInstance().getName(itemid).contains("Special")
                        && !MapleItemInformationProvider.getInstance().getName(itemid).contains("Event")) {
                    MapleInventoryManipulator.addById(c, itemid, (short) 1,
                            "Reward item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                    MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);
                }
                break;
            case 2430370:
                if (MapleInventoryManipulator.checkSpace(c, 2028062, (short) 1, "")) {
                    MapleInventoryManipulator.addById(c, 2028062, (short) 1,
                            "Reward item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                    MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);
                }
                break;
            case 2430158: // lion king
                if (c.getPlayer().getInventory(MapleInventoryType.ETC).getNumFreeSlot() >= 1) {
                    if (c.getPlayer().getInventory(MapleInventoryType.ETC).countById(4000630) >= 100) {
                        if (MapleInventoryManipulator.checkSpace(c, 4310010, 1, "") && MapleInventoryManipulator
                                .removeById(c, MapleInventoryType.USE, toUse.getItemId(), 1, true, false)) {
                            MapleInventoryManipulator.addById(c, 4310010, (short) 1,
                                    "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                        } else {
                            c.getPlayer().dropMessage(5, "Please make some space.");
                        }
                    } else if (c.getPlayer().getInventory(MapleInventoryType.ETC).countById(4000630) >= 50) {
                        if (MapleInventoryManipulator.checkSpace(c, 4310009, 1, "") && MapleInventoryManipulator
                                .removeById(c, MapleInventoryType.USE, toUse.getItemId(), 1, true, false)) {
                            MapleInventoryManipulator.addById(c, 4310009, (short) 1,
                                    "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                        } else {
                            c.getPlayer().dropMessage(5, "Please make some space.");
                        }
                    } else {
                        c.getPlayer().dropMessage(5,
                                "The corrupted power of the medal is too strong. To purify the medal, you need at least #r50#k #bPurification Totems#k.");
                    }
                } else {
                    c.getPlayer().dropMessage(5, "Please make some space.");
                }
                break;
            case 2430159:
                MapleQuest.getInstance(3182).forceComplete(c.getPlayer(), 2161004);
                used = true;
                break;
            case 2430200: // thunder stone
                if (c.getPlayer().getQuestStatus(31152) != 2) {
                    c.getPlayer().dropMessage(5, "You have no idea how to use it.");
                } else if (c.getPlayer().getInventory(MapleInventoryType.ETC).getNumFreeSlot() >= 1) {
                    if (c.getPlayer().getInventory(MapleInventoryType.ETC).countById(4000660) >= 1
                            && c.getPlayer().getInventory(MapleInventoryType.ETC).countById(4000661) >= 1
                            && c.getPlayer().getInventory(MapleInventoryType.ETC).countById(4000662) >= 1
                            && c.getPlayer().getInventory(MapleInventoryType.ETC).countById(4000663) >= 1) {
                        if (MapleInventoryManipulator.checkSpace(c, 4032923, 1, "")
                                && MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, toUse.getItemId(), 1,
                                        true, false)
                                && MapleInventoryManipulator.removeById(c, MapleInventoryType.ETC, 4000660, 1, true, false)
                                && MapleInventoryManipulator.removeById(c, MapleInventoryType.ETC, 4000661, 1, true, false)
                                && MapleInventoryManipulator.removeById(c, MapleInventoryType.ETC, 4000662, 1, true, false)
                                && MapleInventoryManipulator.removeById(c, MapleInventoryType.ETC, 4000663, 1, true,
                                        false)) {
                            MapleInventoryManipulator.addById(c, 4032923, (short) 1,
                                    "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                        } else {
                            c.getPlayer().dropMessage(5, "Please make some space.");
                        }
                    } else {
                        c.getPlayer().dropMessage(5, "There needs to be 1 of each Stone for a Dream Key.");
                    }
                } else {
                    c.getPlayer().dropMessage(5, "Please make some space.");
                }
                break;
            case 2430130:
                if (MapleJob.is末日反抗軍(c.getPlayer().getJob())) {
                    used = true;
                    c.getPlayer().gainExp(20000
                            + (c.getPlayer().getLevel() * 50 * c.getChannelServer().getExpRate(c.getPlayer().getWorld())),
                            true, true, false);
                } else {
                    c.getPlayer().dropMessage(5, "You may not use this item.");
                }
                break;
            case 2430131: // energy charge
                used = true;
                c.getPlayer().gainExp(
                        20000 + (c.getPlayer().getLevel() * 50 * c.getChannelServer().getExpRate(c.getPlayer().getWorld())),
                        true, true, false);
                break;
            case 2430132:
            case 2430134: // resistance box
                if (c.getPlayer().getInventory(MapleInventoryType.EQUIP).getNumFreeSlot() >= 1) {
                    switch (c.getPlayer().getJob()) {
                        case 3200:
                        case 3210:
                        case 3211:
                        case 3212:
                            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);
                            MapleInventoryManipulator.addById(c, 1382101, (short) 1,
                                    "Scripted item: " + itemId + " on " + FileoutputUtil.CurrentReadable_Date());
                            break;
                        case 3300:
                        case 3310:
                        case 3311:
                        case 3312:
                            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);
                            MapleInventoryManipulator.addById(c, 1462093, (short) 1,
                                    "Scripted item: " + itemId + " on " + FileoutputUtil.CurrentReadable_Date());
                            break;
                        case 3500:
                        case 3510:
                        case 3511:
                        case 3512:
                            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);
                            MapleInventoryManipulator.addById(c, 1492080, (short) 1,
                                    "Scripted item: " + itemId + " on " + FileoutputUtil.CurrentReadable_Date());
                            break;
                        default:
                            c.getPlayer().dropMessage(5, "You may not use this item.");
                            break;
                    }
                } else {
                    c.getPlayer().dropMessage(5, "Make some space.");
                }
                break;
            case 2430182:
                // TODO:Fix it
                break;
            case 2430218:
            case 2430230:
            case 2430473:
            case 2430479:
            case 2430632:
            case 2430697:
            case 2430979:
                used = true;
                c.getPlayer().gainExp(GameConstants.getExpNeededForLevel(c.getPlayer().getLevel()) - c.getPlayer().getExp(),
                        true, true, false);
                break;
            case 2430036: // croco 1 day
                mountid = 1027;
                expiration_days = 1;
                break;
            case 2430170: // croco 7 day
                mountid = 1027;
                expiration_days = 7;
                break;
            case 2430037: // black scooter 1 day
                mountid = 1028;
                expiration_days = 1;
                break;
            case 2430038: // pink scooter 1 day
                mountid = 1029;
                expiration_days = 1;
                break;
            case 2430039: // clouds 1 day
                mountid = 1030;
                expiration_days = 1;
                break;
            case 2430040: // balrog 1 day
                mountid = 1031;
                expiration_days = 1;
                break;
            case 2430223: // balrog 1 day
                mountid = 1031;
                expiration_days = 15;
                break;
            case 2430259: // balrog 1 day
                mountid = 1031;
                expiration_days = 3;
                break;
            case 2430242: // motorcycle
                mountid = 80001018;
                expiration_days = 10;
                break;
            case 2430243: // power suit
                mountid = 80001019;
                expiration_days = 10;
                break;
            case 2430261: // power suit
                mountid = 80001019;
                expiration_days = 3;
                break;
            case 2430249: // motorcycle
                mountid = 80001027;
                expiration_days = 3;
                break;
            case 2430225: // balrog 1 day
                mountid = 1031;
                expiration_days = 10;
                break;
            case 2430053: // croco 30 day
                mountid = 1027;
                expiration_days = 1;
                break;
            case 2430054: // black scooter 30 day
                mountid = 1028;
                expiration_days = 30;
                break;
            case 2430055: // pink scooter 30 day
                mountid = 1029;
                expiration_days = 30;
                break;
            case 2430257: // pink
                mountid = 1029;
                expiration_days = 7;
                break;
            case 2430056: // mist rog 30 day
                mountid = 1035;
                expiration_days = 30;
                break;
            case 2430057:
                mountid = 1033;
                expiration_days = 30;
                break;
            case 2430072: // ZD tiger 7 day
                mountid = 1034;
                expiration_days = 7;
                break;
            case 2430073: // lion 15 day
                mountid = 1036;
                expiration_days = 15;
                break;
            case 2430074: // unicorn 15 day
                mountid = 1037;
                expiration_days = 15;
                break;
            case 2430272: // low rider 15 day
                mountid = 1038;
                expiration_days = 3;
                break;
            case 2430275: // spiegelmann
                mountid = 80001033;
                expiration_days = 7;
                break;
            case 2430075: // low rider 15 day
                mountid = 1038;
                expiration_days = 15;
                break;
            case 2430076: // red truck 15 day
                mountid = 1039;
                expiration_days = 15;
                break;
            case 2430077: // gargoyle 15 day
                mountid = 1040;
                expiration_days = 15;
                break;
            case 2430080: // shinjo 20 day
                mountid = 1042;
                expiration_days = 20;
                break;
            case 2430082: // orange mush 7 day
                mountid = 1044;
                expiration_days = 7;
                break;
            case 2430260: // orange mush 7 day
                mountid = 1044;
                expiration_days = 3;
                break;
            case 2430091: // nightmare 10 day
                mountid = 1049;
                expiration_days = 10;
                break;
            case 2430092: // yeti 10 day
                mountid = 1050;
                expiration_days = 10;
                break;
            case 2430263: // yeti 10 day
                mountid = 1050;
                expiration_days = 3;
                break;
            case 2430093: // ostrich 10 day
                mountid = 1051;
                expiration_days = 10;
                break;
            case 2430101: // pink bear 10 day
                mountid = 1052;
                expiration_days = 10;
                break;
            case 2430102: // transformation robo 10 day
                mountid = 1053;
                expiration_days = 10;
                break;
            case 2430103: // chicken 30 day
                mountid = 1054;
                expiration_days = 30;
                break;
            case 2430266: // chicken 30 day
                mountid = 1054;
                expiration_days = 3;
                break;
            case 2430265: // chariot
                mountid = 1151;
                expiration_days = 3;
                break;
            case 2430258: // law officer
                mountid = 1115;
                expiration_days = 365;
                break;
            case 2430117: // lion 1 year
                mountid = 1036;
                expiration_days = 365;
                break;
            case 2430118: // red truck 1 year
                mountid = 1039;
                expiration_days = 365;
                break;
            case 2430119: // gargoyle 1 year
                mountid = 1040;
                expiration_days = 365;
                break;
            case 2430120: // unicorn 1 year
                mountid = 1037;
                expiration_days = 365;
                break;
            case 2430271: // owl 30 day
                mountid = 1069;
                expiration_days = 3;
                break;
            case 2430136: // owl 30 day
                mountid = 1069;
                expiration_days = 30;
                break;
            case 2430137: // owl 1 year
                mountid = 1069;
                expiration_days = 365;
                break;
            case 2430145: // mothership
                mountid = 1070;
                expiration_days = 30;
                break;
            case 2430146: // mothership
                mountid = 1070;
                expiration_days = 365;
                break;
            case 2430147: // mothership
                mountid = 1071;
                expiration_days = 30;
                break;
            case 2430148: // mothership
                mountid = 1071;
                expiration_days = 365;
                break;
            case 2430135: // os4
                mountid = 1065;
                expiration_days = 15;
                break;
            case 2430149: // leonardo 30 day
                mountid = 1072;
                expiration_days = 30;
                break;
            case 2430262: // leonardo 30 day
                mountid = 1072;
                expiration_days = 3;
                break;
            case 2430179: // witch 15 day
                mountid = 1081;
                expiration_days = 15;
                break;
            case 2430264: // witch 15 day
                mountid = 1081;
                expiration_days = 3;
                break;
            case 2430201: // giant bunny 60 day
                mountid = 1096;
                expiration_days = 60;
                break;
            case 2430228: // tiny bunny 60 day
                mountid = 1101;
                expiration_days = 60;
                break;
            case 2430276: // tiny bunny 60 day
                mountid = 1101;
                expiration_days = 15;
                break;
            case 2430277: // tiny bunny 60 day
                mountid = 1101;
                expiration_days = 365;
                break;
            case 2430283: // trojan
                mountid = 1025;
                expiration_days = 10;
                break;
            case 2430291: // hot air
                mountid = 1145;
                expiration_days = -1;
                break;
            case 2430293: // nadeshiko
                mountid = 1146;
                expiration_days = -1;
                break;
            case 2430295: // pegasus
                mountid = 1147;
                expiration_days = -1;
                break;
            case 2430297: // dragon
                mountid = 1148;
                expiration_days = -1;
                break;
            case 2430299: // broom
                mountid = 1149;
                expiration_days = -1;
                break;
            case 2430301: // cloud
                mountid = 1150;
                expiration_days = -1;
                break;
            case 2430303: // chariot
                mountid = 1151;
                expiration_days = -1;
                break;
            case 2430305: // nightmare
                mountid = 1152;
                expiration_days = -1;
                break;
            case 2430307: // rog
                mountid = 1153;
                expiration_days = -1;
                break;
            case 2430309: // mist rog
                mountid = 1154;
                expiration_days = -1;
                break;
            case 2430311: // owl
                mountid = 1156;
                expiration_days = -1;
                break;
            case 2430313: // helicopter
                mountid = 1156;
                expiration_days = -1;
                break;
            case 2430315: // pentacle
                mountid = 1118;
                expiration_days = -1;
                break;
            case 2430317: // frog
                mountid = 1121;
                expiration_days = -1;
                break;
            case 2430319: // turtle
                mountid = 1122;
                expiration_days = -1;
                break;
            case 2430321: // buffalo
                mountid = 1123;
                expiration_days = -1;
                break;
            case 2430323: // tank
                mountid = 1124;
                expiration_days = -1;
                break;
            case 2430325: // viking
                mountid = 1129;
                expiration_days = -1;
                break;
            case 2430327: // pachinko
                mountid = 1130;
                expiration_days = -1;
                break;
            case 2430329: // kurenai
                mountid = 1063;
                expiration_days = -1;
                break;
            case 2430331: // horse
                mountid = 1025;
                expiration_days = -1;
                break;
            case 2430333: // tiger
                mountid = 1034;
                expiration_days = -1;
                break;
            case 2430335: // hyena
                mountid = 1136;
                expiration_days = -1;
                break;
            case 2430337: // ostrich
                mountid = 1051;
                expiration_days = -1;
                break;
            case 2430339: // low rider
                mountid = 1138;
                expiration_days = -1;
                break;
            case 2430341: // napoleon
                mountid = 1139;
                expiration_days = -1;
                break;
            case 2430343: // croking
                mountid = 1027;
                expiration_days = -1;
                break;
            case 2430346: // lovely
                mountid = 1029;
                expiration_days = -1;
                break;
            case 2430348: // retro
                mountid = 1028;
                expiration_days = -1;
                break;
            case 2430350: // f1
                mountid = 1033;
                expiration_days = -1;
                break;
            case 2430352: // power suit
                mountid = 1064;
                expiration_days = -1;
                break;
            case 2430354: // giant rabbit
                mountid = 1096;
                expiration_days = -1;
                break;
            case 2430356: // small rabit
                mountid = 1101;
                expiration_days = -1;
                break;
            case 2430358: // rabbit rickshaw
                mountid = 1102;
                expiration_days = -1;
                break;
            case 2430360: // chicken
                mountid = 1054;
                expiration_days = -1;
                break;
            case 2430362: // transformer
                mountid = 1053;
                expiration_days = -1;
                break;
            case 2430292: // hot air
                mountid = 1145;
                expiration_days = 90;
                break;
            case 2430294: // nadeshiko
                mountid = 1146;
                expiration_days = 90;
                break;
            case 2430296: // pegasus
                mountid = 1147;
                expiration_days = 90;
                break;
            case 2430298: // dragon
                mountid = 1148;
                expiration_days = 90;
                break;
            case 2430300: // broom
                mountid = 1149;
                expiration_days = 90;
                break;
            case 2430302: // cloud
                mountid = 1150;
                expiration_days = 90;
                break;
            case 2430304: // chariot
                mountid = 1151;
                expiration_days = 90;
                break;
            case 2430306: // nightmare
                mountid = 1152;
                expiration_days = 90;
                break;
            case 2430308: // rog
                mountid = 1153;
                expiration_days = 90;
                break;
            case 2430310: // mist rog
                mountid = 1154;
                expiration_days = 90;
                break;
            case 2430312: // owl
                mountid = 1156;
                expiration_days = 90;
                break;
            case 2430314: // helicopter
                mountid = 1156;
                expiration_days = 90;
                break;
            case 2430316: // pentacle
                mountid = 1118;
                expiration_days = 90;
                break;
            case 2430318: // frog
                mountid = 1121;
                expiration_days = 90;
                break;
            case 2430320: // turtle
                mountid = 1122;
                expiration_days = 90;
                break;
            case 2430322: // buffalo
                mountid = 1123;
                expiration_days = 90;
                break;
            case 2430326: // viking
                mountid = 1129;
                expiration_days = 90;
                break;
            case 2430328: // pachinko
                mountid = 1130;
                expiration_days = 90;
                break;
            case 2430330: // kurenai
                mountid = 1063;
                expiration_days = 90;
                break;
            case 2430332: // horse
                mountid = 1025;
                expiration_days = 90;
                break;
            case 2430334: // tiger
                mountid = 1034;
                expiration_days = 90;
                break;
            case 2430336: // hyena
                mountid = 1136;
                expiration_days = 90;
                break;
            case 2430338: // ostrich
                mountid = 1051;
                expiration_days = 90;
                break;
            case 2430340: // low rider
                mountid = 1138;
                expiration_days = 90;
                break;
            case 2430342: // napoleon
                mountid = 1139;
                expiration_days = 90;
                break;
            case 2430344: // croking
                mountid = 1027;
                expiration_days = 90;
                break;
            case 2430347: // lovely
                mountid = 1029;
                expiration_days = 90;
                break;
            case 2430349: // retro
                mountid = 1028;
                expiration_days = 90;
                break;
            case 2430351: // f1
                mountid = 1033;
                expiration_days = 90;
                break;
            case 2430353: // power suit
                mountid = 1064;
                expiration_days = 90;
                break;
            case 2430355: // giant rabbit
                mountid = 1096;
                expiration_days = 90;
                break;
            case 2430357: // small rabit
                mountid = 1101;
                expiration_days = 90;
                break;
            case 2430359: // rabbit rickshaw
                mountid = 1102;
                expiration_days = 90;
                break;
            case 2430361: // chicken
                mountid = 1054;
                expiration_days = 90;
                break;
            case 2430363: // transformer
                mountid = 1053;
                expiration_days = 90;
                break;
            case 2430324: // high way
                mountid = 1158;
                expiration_days = -1;
                break;
            case 2430345: // high way
                mountid = 1158;
                expiration_days = 90;
                break;
            case 2430367: // law off
                mountid = 1115;
                expiration_days = 3;
                break;
            case 2430365: // pony
                mountid = 1025;
                expiration_days = 365;
                break;
            case 2430366: // pony
                mountid = 1025;
                expiration_days = 15;
                break;
            case 2430369: // nightmare
                mountid = 1049;
                expiration_days = 10;
                break;
            case 2430392: // speedy
                mountid = 80001038;
                expiration_days = 90;
                break;
            case 2430476: // red truck? but name is pegasus?
                mountid = 1039;
                expiration_days = 15;
                break;
            case 2430477: // red truck? but name is pegasus?
                mountid = 1039;
                expiration_days = 365;
                break;
            case 2430232: // fortune
                mountid = 1106;
                expiration_days = 10;
                break;
            case 2430511: // spiegel
                mountid = 80001033;
                expiration_days = 15;
                break;
            case 2430512: // rspiegel
                mountid = 80001033;
                expiration_days = 365;
                break;
            case 2430536: // buddy buggy
                mountid = 80001114;
                expiration_days = 365;
                break;
            case 2430537: // buddy buggy
                mountid = 80001114;
                expiration_days = 15;
                break;
            case 2430229: // bunny rickshaw 60 day
                mountid = 1102;
                expiration_days = 60;
                break;
            case 2430199: // santa sled
                mountid = 1102;
                expiration_days = 60;
                break;
            case 2430206: // race
                mountid = 1089;
                expiration_days = 7;
                break;
            case 2430211: // race
                mountid = 80001009;
                expiration_days = 30;
                break;
            case 2430611: {
                NPCScriptManager.getInstance().start(c, 9010010, "consume_2430611");
                break;
            }
            case 2430690: {
                if (c.getPlayer().getInventory(MapleInventoryType.CASH).getNumFreeSlot() >= 1
                        && c.getPlayer().getInventory(MapleInventoryType.EQUIP).getNumFreeSlot() >= 1) {
                    if (Randomizer.isSuccess(30)) { // 30% for Hilla's Pet
                        if (MapleInventoryManipulator.checkSpace(c, 5000217, 1, "") && MapleInventoryManipulator
                                .removeById(c, MapleInventoryType.USE, toUse.getItemId(), 1, true, false)) {
                            MapleInventoryManipulator.addById(c, 5000217, (short) 1, "", MaplePet.createPet(5000217), 45,
                                    MapleInventoryManipulator.DAY,
                                    "從 " + toUse + " 的道具腳本中獲得, 時間:" + FileoutputUtil.CurrentReadable_Date());
                        } else {
                            c.getPlayer().dropMessage(0, "Please make more space");
                        }
                    } else // 70% for Hilla's Pet's earrings
                    if (MapleInventoryManipulator.checkSpace(c, 1802354, 1, "") && MapleInventoryManipulator.removeById(c,
                            MapleInventoryType.USE, toUse.getItemId(), 1, true, false)) {
                        MapleInventoryManipulator.addById(c, 1802354, (short) 1,
                                "Scripted item: " + toUse.getItemId() + " on " + FileoutputUtil.CurrentReadable_Date());
                    } else {
                        c.getPlayer().dropMessage(0, "Please make more space");
                    }
                } else {
                    c.getPlayer().dropMessage(0, "Please make more space");
                }
                break;
            }
            case 2431174: // 名譽勳章
            case 2432586: // 失去名譽的勳章
            case 2432602: // 單身部隊名譽的勳章
            case 2432970: // 特殊名譽勳章
            case 2433103: // 頭目名譽勳章
            case 2433457: // 夏季名譽的勳章
            case 2433803: // 單身VS情侶名譽勳章
            case 2433808: // 特殊名譽勳章
            case 2433840: // 綠野仙蹤名譽勳章
            case 2433926: // 平凡的名譽勳章
            case 2434021: // 名譽的勳章
            case 2434146: // 刨冰名譽勳章
            case 2434175: // 高級服務名譽的勳章
            case 2434283: // 頭目名譽勳章
            case 2434288: // 特殊名譽的勳章
            case 2434290: // 武公認證的名譽勳章
            case 2434381: // 疲勞打破名譽勳章
            case 2434502: // 烏勒斯名譽勳章
            case 2434637: // 下級獵人名譽的勳章
            case 2434638: // 中級獵人名譽的勳章
            case 2434639: // 上級獵人名譽的勳章
            case 2434693: { // 橘子樂園名譽的勳章
                int honor = 0;
                switch (toUse.getItemId()) {
                    case 2431174: // 名譽勳章
                        honor = Randomizer.nextInt(16) + 5;
                        break;
                    case 2432586: // 失去名譽的勳章
                    case 2434637: // 下級獵人名譽的勳章
                        honor = 500;
                        break;
                    case 2432602: // 單身部隊名譽的勳章
                    case 2433803: // 單身VS情侶名譽勳章
                    case 2434502: // 烏勒斯名譽勳章
                    case 2434638: // 中級獵人名譽的勳章
                        honor = 1000;
                        break;
                    case 2432970: // 特殊名譽勳章
                    case 2433103: // 頭目名譽勳章
                    case 2433457: // 夏季名譽的勳章
                    case 2433808: // 特殊名譽勳章
                    case 2433926: // 平凡的名譽勳章
                    case 2434146: // 刨冰名譽勳章
                    case 2434283: // 頭目名譽勳章
                    case 2434693: // 橘子樂園名譽的勳章
                        honor = honor = Randomizer.nextInt(101) + 100;
                        break;
                    case 2433840: // 綠野仙蹤名譽勳章
                        honor = 100;
                        break;
                    case 2434021: // 名譽的勳章
                    case 2434175: // 高級服務名譽的勳章
                    case 2434288: // 特殊名譽的勳章
                    case 2434290: // 武公認證的名譽勳章
                    case 2434381: // 疲勞打破名譽勳章
                        honor = 10000;
                        break;
                    case 2434639: // 上級獵人名譽的勳章
                        honor = 2000;
                        break;
                }
                if (honor == 0) {
                    c.getPlayer().dropMessage(5, "當前道具未處理。");
                    break;
                }
                if (MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false)) {
                    chr.gainHonour(honor);
                } else {
                    c.getPlayer().dropMessage(5, "出現未知錯誤。");
                }
                break;
            }
            case 2434287: { // 武公的名譽值保證書
                if (chr.getHonourExp() < 10000) {
                    c.getPlayer().dropMessage(5, "名譽不足。");
                    break;
                }
                if (MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false)) {
                    chr.gainHonour(-10000);
                    MapleInventoryManipulator.addById(c, 2434290, (short) 1,
                            "由武公的名譽值保證書儲存, 時間:" + FileoutputUtil.CurrentReadable_Date());
                } else {
                    c.getPlayer().dropMessage(5, "出現未知錯誤。");
                }
                break;
            }
            default:
                if (ItemConstants.傷害字型.isDamageSkin(toUse.getItemId())) {
                    if (!MapleJob.is神之子(chr.getJob())) {
                        int sitemid = toUse.getItemId();
                        int skinnum = ItemConstants.傷害字型.getDamageSkinNumberByItem(sitemid);
                        if (skinnum == -1) {
                            chr.dropMessage(-9, "出現未知錯誤");
                            break;
                        }
                        c.getPlayer().setDamageSkin(skinnum);
                        chr.dropMessage(-9, "傷害字型已更變。");
                        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);
                        byte[] packet = CField.updateDamageSkin(chr.getId(), skinnum);
                        if (chr.isHidden()) {
                            chr.getMap().broadcastGMMessage(chr, packet, false);
                        } else {
                            chr.getMap().broadcastMessage(chr, packet, false);
                        }
                        List<WeakReference<MapleCharacter>> clones = chr.getClones();
                        for (WeakReference<MapleCharacter> cln : clones) {
                            if (cln.get() != null) {
                                final MapleCharacter clone = cln.get();
                                byte[] packetClone = CField.updateDamageSkin(clone.getId(), skinnum);
                                if (!clone.isHidden()) {
                                    clone.getMap().broadcastMessage(clone, packetClone, true);
                                } else {
                                    clone.getMap().broadcastGMMessage(clone, packetClone, true);
                                }
                            }
                        }
                    } else {
                        chr.dropMessage(-9, "神之子無法套用傷害字型。");
                    }
                    break;
                }
                NPCScriptManager.getInstance().startItemScript(c, toUse.getItemId(), slot, npc, script); // maple admin as
                // default npc
                break;
        }
        if (mountid > 0) {
            mountid = PlayerStats.getSkillByJob(mountid, c.getPlayer().getJob());
            final int fk = GameConstants.getMountItem(mountid, c.getPlayer());
            if (fk > 0 && mountid < 80001000) {
                for (int i = 80001001; i < 80001999; i++) {
                    final Skill skill = SkillFactory.getSkill(i);
                    if (skill != null && GameConstants.getMountItem(skill.getId(), c.getPlayer()) == fk) {
                        mountid = i;
                        break;
                    }
                }
            }
            if (c.getPlayer().getSkillLevel(mountid) > 0) {
                c.getPlayer().dropMessage(5, "你已經學習過此技能了");
            } else if (SkillFactory.getSkill(mountid) == null
                    || GameConstants.getMountItem(mountid, c.getPlayer()) == 0) {
                c.getPlayer().dropMessage(5, "目前這個技能無法學習.");
            } else if (expiration_days > 0) {
                used = true;
                Skill mountSkill = SkillFactory.getSkill(mountid);
                c.getPlayer().changeSingleSkillLevel(SkillFactory.getSkill(mountid), (byte) 1, (byte) 1,
                        System.currentTimeMillis() + expiration_days * 24 * 60 * 60 * 1000);
                c.getPlayer().dropMessage(5, "已學習" + mountSkill.getName());
            }
            c.announce(CWvsContext.enableActions());
        }
        if (used) {
            MapleInventoryManipulator.removeFromSlot(c, invType, slot, (byte) 1, false);
        }
    }

    public static void ResetCoreAura(int slot, MapleClient c, MapleCharacter chr) {
        Item starDust = chr.getInventory(MapleInventoryType.USE).getItem((byte) slot);
        if ((starDust == null) || (c.getPlayer().hasBlockedInventory())) {
            c.getSession().writeAndFlush(CWvsContext.InventoryPacket.getInventoryFull());
        }
    }

    public static final void UseSummonBag(final LittleEndianAccessor slea, final MapleClient c,
            final MapleCharacter chr) {
        if (!chr.isAlive() || chr.hasBlockedInventory() || chr.inPVP()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final Item toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse != null && toUse.getQuantity() >= 1 && toUse.getItemId() == itemId
                && (c.getPlayer().getMapId() < 910000000 || c.getPlayer().getMapId() > 910000022)) {
            final Map<String, Integer> toSpawn = MapleItemInformationProvider.getInstance().getEquipStats(itemId);

            if (toSpawn == null) {
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                return;
            }
            MapleMonster ht = null;
            int type = 0;
            for (Entry<String, Integer> i : toSpawn.entrySet()) {
                if (i.getKey().startsWith("mob") && Randomizer.nextInt(99) <= i.getValue()) {
                    ht = MapleLifeFactory.getMonster(Integer.parseInt(i.getKey().substring(3)));
                    chr.getMap().spawnMonster_sSack(ht, chr.getPosition(), type);
                }
            }
            if (ht == null) {
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                return;
            }

            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
        }
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static final void UseTreasureChest(final LittleEndianAccessor slea, final MapleClient c,
            final MapleCharacter chr) {
        final short slot = slea.readShort();
        final int itemid = slea.readInt();

        final Item toUse = chr.getInventory(MapleInventoryType.ETC).getItem((byte) slot);
        if (toUse == null || toUse.getQuantity() <= 0 || toUse.getItemId() != itemid || chr.hasBlockedInventory()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        int reward;
        int keyIDforRemoval = 0;
        String box;

        switch (toUse.getItemId()) {
            case 4280000: // Gold box
                reward = RandomRewards.getGoldBoxReward();
                keyIDforRemoval = 5490000;
                box = "Gold";
                break;
            case 4280001: // Silver box
                reward = RandomRewards.getSilverBoxReward();
                keyIDforRemoval = 5490001;
                box = "Silver";
                break;
            default: // Up to no good
                return;
        }

        // Get the quantity
        int amount = 1;
        switch (reward) {
            case 2000004:
                amount = 200; // Elixir
                break;
            case 2000005:
                amount = 100; // Power Elixir
                break;
        }
        if (chr.getInventory(MapleInventoryType.CASH).countById(keyIDforRemoval) > 0) {
            final Item item = MapleInventoryManipulator.addbyId_Gachapon(c, reward, (short) amount);

            if (item == null) {
                chr.dropMessage(5,
                        "Please check your item inventory and see if you have a Master Key, or if the inventory is full.");
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                return;
            }
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.ETC, (byte) slot, (short) 1, true);
            MapleInventoryManipulator.removeById(c, MapleInventoryType.CASH, keyIDforRemoval, 1, true, false);
            c.getSession().writeAndFlush(InfoPacket.getShowItemGain(reward, (short) amount, true));

            if (ItemConstants.gachaponRareItem(item.getItemId()) > 0) {
                World.Broadcast.broadcastSmega(CWvsContext.getGachaponMega(c.getPlayer().getName(),
                        "[" + box + " Chest]", null, item, c.getChannel(), 2));
            }
        } else {
            chr.dropMessage(5,
                    "Please check your item inventory and see if you have a Master Key, or if the inventory is full.");
            c.getSession().writeAndFlush(CWvsContext.enableActions());
        }
    }

    public static final void UseCashItem(final LittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer() == null || c.getPlayer().getMap() == null || c.getPlayer().inPVP()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        c.getPlayer().setScrolledPosition((short) 0);
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();

        final Item toUse = c.getPlayer().getInventory(MapleInventoryType.CASH).getItem(slot);

        if (toUse == null || toUse.getItemId() != itemId || toUse.getQuantity() < 1
                || c.getPlayer().hasBlockedInventory()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }

        boolean used = false, cc = false;

        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        switch (itemId) {
            case 285400: {
                break;
            }
            case 5537000: // 萌寵抽卡
            case 5840004: {
                int freeSlot = c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot();
                if (freeSlot >= 3) {
                    UseFamiliarCardPack(c.getPlayer());
                    used = true;
                    // c.getPlayer().getRand
                } else {
                    c.getPlayer().dropMessage(1, "請確認消耗欄位有三格");
                }
                break;
            }
            case 5043001: // NPC Teleport Rock
            case 5043000: { // NPC Teleport Rock
                final short questid = slea.readShort();
                final int npcid = slea.readInt();
                final MapleQuest quest = MapleQuest.getInstance(questid);

                if (c.getPlayer().getQuest(quest).getStatus() == 1 && quest.canComplete(c.getPlayer(), npcid)) {
                    final int mapId = MapleLifeFactory.getNPCLocation(npcid);
                    if (mapId != -1) {
                        final MapleMap map = c.getChannelServer().getMapFactory().getMap(mapId);
                        if (map.containsNPC(npcid) && !FieldLimitType.VipRock.check(c.getPlayer().getMap().getFieldLimit())
                                && !FieldLimitType.VipRock.check(map.getFieldLimit()) && !c.getPlayer().isInBlockedMap()) {
                            c.getPlayer().changeMap(map, map.getPortal(0));
                        }
                        used = true;
                    } else {
                        c.getPlayer().dropMessage(1, "Unknown error has occurred.");
                    }
                }
                break;
            }
            case 5041001:
            case 5040004:
            case 5040003:
            case 5040002:
            case 2320000: // The Teleport Rock
            case 5041000: // VIP Teleport Rock
            case 5040000: // The Teleport Rock
            case 5040001: { // Teleport Coke
                used = UseTeleRock(slea, c, itemId);
                break;
            }
            case 5450005: {
                c.getPlayer().setConversation(4);
                c.getPlayer().getStorage().sendStorage(c, 1022005);
                break;

            }
            case 5050000: { // AP Reset
                Map<MapleStat, Long> statupdate = new EnumMap<>(MapleStat.class);
                final int apto = (int) slea.readLong();
                final int apfrom = (int) slea.readLong();

                if (apto == apfrom) {
                    break; // Hack
                }
                final int job = c.getPlayer().getJob();
                final PlayerStats playerst = c.getPlayer().getStat();
                used = true;

                switch (apto) { // AP to
                    case 0x40: // str
                        if (playerst.getStr() >= 999) {
                            used = false;
                        }
                        break;
                    case 0x80: // dex
                        if (playerst.getDex() >= 999) {
                            used = false;
                        }
                        break;
                    case 0x100: // int
                        if (playerst.getInt() >= 999) {
                            used = false;
                        }
                        break;
                    case 0x200: // luk
                        if (playerst.getLuk() >= 999) {
                            used = false;
                        }
                        break;
                    case 0x800: // hp
                        if (playerst.getMaxHp() >= 500000) {
                            used = false;
                        }
                        break;
                    case 0x2000: // mp
                        if (playerst.getMaxMp() >= 500000) {
                            used = false;
                        }
                        break;
                }
                switch (apfrom) { // AP to
                    case 0x40: // str
                        if (playerst.getStr() <= 4 || (c.getPlayer().getJob() % 1000 / 100 == 1 && playerst.getStr() <= 35)) {
                            used = false;
                        }
                        break;
                    case 0x80: // dex
                        if (playerst.getDex() <= 4 || (c.getPlayer().getJob() % 1000 / 100 == 3 && playerst.getDex() <= 25)
                                || (c.getPlayer().getJob() % 1000 / 100 == 4 && playerst.getDex() <= 25)
                                || (c.getPlayer().getJob() % 1000 / 100 == 5 && playerst.getDex() <= 20)) {
                            used = false;
                        }
                        break;
                    case 0x100: // int
                        if (playerst.getInt() <= 4 || (c.getPlayer().getJob() % 1000 / 100 == 2 && playerst.getInt() <= 20)) {
                            used = false;
                        }
                        break;
                    case 0x200: // luk
                        if (playerst.getLuk() <= 4) {
                            used = false;
                        }
                        break;
                    case 0x800: // hp
                        if (/* playerst.getMaxMp() < ((c.getPlayer().getLevel() * 14) + 134) || */c.getPlayer()
                                        .getHpApUsed() <= 0 || c.getPlayer().getHpApUsed() >= 10000) {
                            used = false;
                            c.getPlayer().dropMessage(1, "You need points in HP or MP in order to take points out.");
                        }
                        break;
                    case 0x2000: // mp
                        if (/* playerst.getMaxMp() < ((c.getPlayer().getLevel() * 14) + 134) || */c.getPlayer()
                                        .getMpApUsed() <= 0 || c.getPlayer().getMpApUsed() >= 10000) {
                            used = false;
                            c.getPlayer().dropMessage(1, "You need points in HP or MP in order to take points out.");
                        }
                        break;
                }
                if (used) {
                    switch (apto) { // AP to
                        case 0x40: { // str
                            final long toSet = playerst.getStr() + 1;
                            playerst.setStr((short) toSet, c.getPlayer());
                            statupdate.put(MapleStat.STR, toSet);
                            break;
                        }
                        case 0x80: { // dex
                            final long toSet = playerst.getDex() + 1;
                            playerst.setDex((short) toSet, c.getPlayer());
                            statupdate.put(MapleStat.DEX, toSet);
                            break;
                        }
                        case 0x100: { // int
                            final long toSet = playerst.getInt() + 1;
                            playerst.setInt((short) toSet, c.getPlayer());
                            statupdate.put(MapleStat.INT, toSet);
                            break;
                        }
                        case 0x200: { // luk
                            final long toSet = playerst.getLuk() + 1;
                            playerst.setLuk((short) toSet, c.getPlayer());
                            statupdate.put(MapleStat.LUK, toSet);
                            break;
                        }
                        case 0x800: // hp
                            int maxhp = playerst.getMaxHp();
                            maxhp += GameConstants.getHpApByJob((short) job);
                            c.getPlayer().setHpApUsed((short) (c.getPlayer().getHpApUsed() + 1));
                            playerst.setMaxHp(maxhp, c.getPlayer());
                            statupdate.put(MapleStat.MAXHP, (long) maxhp);
                            break;

                        case 0x2000: // mp
                            int maxmp = playerst.getMaxMp();
                            if (MapleJob.is惡魔(job) || MapleJob.is天使破壞者(job)) {
                                break;
                            }
                            maxmp += GameConstants.getMpApByJob((short) job);
                            maxmp = Math.min(500000, Math.abs(maxmp));
                            c.getPlayer().setMpApUsed((short) (c.getPlayer().getMpApUsed() + 1));
                            playerst.setMaxMp(maxmp, c.getPlayer());
                            statupdate.put(MapleStat.MAXMP, (long) maxmp);
                            break;
                    }
                    switch (apfrom) { // AP from
                        case 0x40: { // str
                            final long toSet = playerst.getStr() - 1;
                            playerst.setStr((short) toSet, c.getPlayer());
                            statupdate.put(MapleStat.STR, toSet);
                            break;
                        }
                        case 0x80: { // dex
                            final long toSet = playerst.getDex() - 1;
                            playerst.setDex((short) toSet, c.getPlayer());
                            statupdate.put(MapleStat.DEX, toSet);
                            break;
                        }
                        case 0x100: { // int
                            final long toSet = playerst.getInt() - 1;
                            playerst.setInt((short) toSet, c.getPlayer());
                            statupdate.put(MapleStat.INT, toSet);
                            break;
                        }
                        case 0x200: { // luk
                            final long toSet = playerst.getLuk() - 1;
                            playerst.setLuk((short) toSet, c.getPlayer());
                            statupdate.put(MapleStat.LUK, toSet);
                            break;
                        }
                        case 0x800: // HP
                            int maxhp = playerst.getMaxHp();
                            maxhp -= GameConstants.getHpApByJob((short) job);
                            c.getPlayer().setHpApUsed((short) (c.getPlayer().getHpApUsed() - 1));
                            playerst.setMaxHp(maxhp, c.getPlayer());
                            statupdate.put(MapleStat.MAXHP, (long) maxhp);
                            break;
                        case 0x2000: // MP
                            int maxmp = playerst.getMaxMp();
                            if (MapleJob.is惡魔(job) || MapleJob.is天使破壞者(job)) {
                                break;
                            }
                            maxmp -= GameConstants.getMpApByJob((short) job);
                            c.getPlayer().setMpApUsed((short) (c.getPlayer().getMpApUsed() - 1));
                            playerst.setMaxMp(maxmp, c.getPlayer());
                            statupdate.put(MapleStat.MAXMP, (long) maxmp);
                            break;
                    }
                    c.getSession().writeAndFlush(CWvsContext.updatePlayerStats(statupdate, true, c.getPlayer()));
                }
                break;
            }
            case 5051001: // 技能點數初始化卷軸
                if (MapleJob.is影武者(c.getPlayer().getJob())) {
                    int s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0;
                    final Map<Skill, SkillEntry> sa = new HashMap<>();

                    for (Entry<Skill, SkillEntry> ss : c.getPlayer().getSkills().entrySet()) {
                        if (!GameConstants.isHyperSkill(ss.getKey())) {
                            switch (ss.getKey().getId() / 10000) {
                                case 400:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s0 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                                case 430:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s1 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                                case 431:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s2 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                                case 432:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s3 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                                case 433:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s4 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                                case 434:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s5 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                            }
                        }
                    }
                    c.getPlayer().changeSkillsLevel(sa);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(0) + s0, 0);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(1) + s1, 1);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(2) + s2, 2);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(3) + s3, 3);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(4) + s4, 4);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(5) + s5, 5);
                    c.getPlayer().updateSingleStat(MapleStat.AVAILABLESP, 0);
                    // c.getPlayer().dropMessage(5, "[s0:" + s0 + "] [s1:" + s1 + "] [s2:" + s2 + "]
                    // [s3:" + s3 + "] [s4:" + s4 + "] [s5:" + s5 + "]");
                } else {
                    int s0 = 0, s1 = 0, s2 = 0, s3 = 0;
                    final Map<Skill, SkillEntry> sa = new HashMap<>();

                    for (Entry<Skill, SkillEntry> ss : c.getPlayer().getSkills().entrySet()) {
                        if (!GameConstants.isHyperSkill(ss.getKey())) {
                            switch (ss.getKey().getId() % 100) {
                                case 00:
                                case 01:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s0 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                                case 10:
                                case 20:
                                case 30:
                                case 70:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s1 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                                case 11:
                                case 21:
                                case 31:
                                case 71:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s2 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                                case 12:
                                case 22:
                                case 32:
                                case 72:
                                    sa.put(ss.getKey(), new SkillEntry((byte) 0, c.getPlayer().getMasterLevel(ss.getKey()),
                                            SkillFactory.getDefaultSExpiry(ss.getKey())));
                                    s3 += c.getPlayer().getSkillLevel(ss.getKey());
                                    break;
                            }
                        }
                    }
                    c.getPlayer().changeSkillsLevel(sa);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(0) + s0, 0);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(1) + s1, 1);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(2) + s2, 2);
                    c.getPlayer().setRemainingSp(c.getPlayer().getRemainingSp(3) + s3, 3);
                    c.getPlayer().updateSingleStat(MapleStat.AVAILABLESP, 0);
                    // c.getPlayer().dropMessage(5, "[s0:" + s0 + "] [s1:" + s1 + "] [s2:" + s2 + "]
                    // [s3:" + s3 + "]");
                }
                c.getPlayer().dropMessage(5, "技能點數以初始化完成。");
                used = true;
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                break;
            case 5080000: // 風箏
            case 5080001: // 氫氣球
            case 5080002: // 畢業祝賀橫幅
            case 5080003: // 入學祝賀帷幕
                MapleKite kite = new MapleKite(itemId, c.getPlayer(), slea.readMapleAsciiString(),
                        c.getPlayer().getPosition());
                c.getPlayer().getMap().spawnKite(c, kite);
                used = true;
                break;
            // case 5220083: {//starter pack
            // used = true;
            // for (Entry<Integer, StructFamiliar> f :
            // MapleItemInformationProvider.getInstance().getFamiliars().entrySet()) {
            // if (f.getValue().getMonsterCardId() == 2870055 ||
            // f.getValue().getMonsterCardId() == 2871002 || f.getValue().getMonsterCardId()
            // == 2870235 || f.getValue().getMonsterCardId() == 2870019) {
            // MonsterFamiliar mf = c.getPlayer().getFamiliars().get(f.getKey());
            // if (mf != null) {
            // if (mf.getVitality() >= 3) {
            // mf.setExpiry(Math.min(System.currentTimeMillis() + 90 * 24 * 60 * 60000L,
            // mf.getExpiry() + 30 * 24 * 60 * 60000L));
            // } else {
            // mf.setVitality(mf.getVitality() + 1);
            // mf.setExpiry(mf.getExpiry() + 30 * 24 * 60 * 60000L);
            // }
            // } else {
            // mf = new MonsterFamiliar(c.getPlayer().getId(), f.getKey(),
            // System.currentTimeMillis() + 30 * 24 * 60 * 60000L);
            // c.getPlayer().getFamiliars().put(f.getKey(), mf);
            // }
            // c.getSession().writeAndFlush(CField.registerFamiliar(mf));
            // }
            // }
            // break;
            // }
            case 5220084: {// booster pack
                if (c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() < 3) {
                    c.getPlayer().dropMessage(5, "Make 3 USE space.");
                    break;
                }
                used = true;
                int[] familiars = new int[3];
                while (true) {
                    for (int i = 0; i < familiars.length; i++) {
                        if (familiars[i] > 0) {
                            continue;
                        }
                        for (Map.Entry<Integer, StructFamiliar> f : MapleItemInformationProvider.getInstance()
                                .getFamiliars().entrySet()) {
                            if (Randomizer.nextInt(500) == 0 && ((i < 2 && f.getValue().getGrade() == 0
                                    || (i == 2 && f.getValue().getGrade() != 0)))) {
                                MapleInventoryManipulator.addById(c, f.getValue().getMonsterCardId(), (short) 1,
                                        "Booster Pack");
                                // c.getSession().writeAndFlush(CField.getBoosterFamiliar(c.getPlayer().getId(),
                                // f.getKey(), 0));
                                familiars[i] = f.getValue().getMonsterCardId();
                                break;
                            }
                        }
                    }
                    if (familiars[0] > 0 && familiars[1] > 0 && familiars[2] > 0) {
                        break;
                    }
                }
                c.getSession().writeAndFlush(CCashShop.getBoosterPack(familiars[0], familiars[1], familiars[2]));
                c.getSession().writeAndFlush(CCashShop.getBoosterPackClick());
                c.getSession().writeAndFlush(CCashShop.getBoosterPackReveal());
                break;
            }
            case 5050001: // SP Reset (1st job)
            case 5050002: // SP Reset (2nd job)
            case 5050003: // SP Reset (3rd job)
            case 5050004: // SP Reset (4th job)
            case 5050005: // evan sp resets
            case 5050006:
            case 5050007:
            case 5050008:
            case 5050009: {
                if (itemId >= 5050005 && !MapleJob.is龍魔導士(c.getPlayer().getJob())) {
                    c.getPlayer().dropMessage(1, "This reset is only for Evans.");
                    break;
                } // well i dont really care other than this o.o
                if (itemId < 5050005 && MapleJob.is龍魔導士(c.getPlayer().getJob())) {
                    c.getPlayer().dropMessage(1, "This reset is only for non-Evans.");
                    break;
                } // well i dont really care other than this o.o
                int skill1 = slea.readInt();
                int skill2 = slea.readInt();
                for (int i : GameConstants.blockedSkills) {
                    if (skill1 == i) {
                        c.getPlayer().dropMessage(1, "You may not add this skill.");
                        return;
                    }
                }

                Skill skillSPTo = SkillFactory.getSkill(skill1);
                Skill skillSPFrom = SkillFactory.getSkill(skill2);

                if (skillSPTo.isBeginnerSkill() || skillSPFrom.isBeginnerSkill()) {
                    c.getPlayer().dropMessage(1, "You may not add beginner skills.");
                    break;
                }
                if (GameConstants.getSkillBookBySkill(skill1) != GameConstants.getSkillBookBySkill(skill2)) { // resistance
                    // evan
                    c.getPlayer().dropMessage(1, "You may not add different job skills.");
                    break;
                }
                // if (MapleJob.getNumber(skill1 / 10000) > MapleJob.getNumber(skill2 / 10000))
                // { //putting 3rd job skillpoints into 4th job for example
                // c.getPlayer().dropMessage(1, "You may not add skillpoints to a higher job.");
                // break;
                // }
                if ((c.getPlayer().getSkillLevel(skillSPTo) + 1 <= skillSPTo.getMaxLevel())
                        && c.getPlayer().getSkillLevel(skillSPFrom) > 0
                        && skillSPTo.canBeLearnedBy(c.getPlayer().getJob())) {
                    if (skillSPTo.isFourthJob()
                            && (c.getPlayer().getSkillLevel(skillSPTo) + 1 > c.getPlayer().getMasterLevel(skillSPTo))) {
                        c.getPlayer().dropMessage(1, "You will exceed the master level.");
                        break;
                    }
                    if (itemId >= 5050005) {
                        if (GameConstants.getSkillBookBySkill(skill1) != (itemId - 5050005) * 2
                                && GameConstants.getSkillBookBySkill(skill1) != (itemId - 5050005) * 2 + 1) {
                            c.getPlayer().dropMessage(1, "You may not add this job SP using this reset.");
                            break;
                        }
                    } else {
                        int theJob = MapleJob.getJobGrade(skill2 / 10000);
                        switch (skill2 / 10000) {
                            case 430:
                                theJob = 1;
                                break;
                            case 432:
                            case 431:
                                theJob = 2;
                                break;
                            case 433:
                                theJob = 3;
                                break;
                            case 434:
                                theJob = 4;
                                break;
                        }
                        if (theJob != itemId - 5050000) { // you may only subtract from the skill if the ID matches Sp reset
                            c.getPlayer().dropMessage(1,
                                    "You may not subtract from this skill. Use the appropriate SP reset.");
                            break;
                        }
                    }
                    final Map<Skill, SkillEntry> sa = new HashMap<>();
                    sa.put(skillSPFrom, new SkillEntry((byte) (c.getPlayer().getSkillLevel(skillSPFrom) - 1),
                            c.getPlayer().getMasterLevel(skillSPFrom), SkillFactory.getDefaultSExpiry(skillSPFrom)));
                    sa.put(skillSPTo, new SkillEntry((byte) (c.getPlayer().getSkillLevel(skillSPTo) + 1),
                            c.getPlayer().getMasterLevel(skillSPTo), SkillFactory.getDefaultSExpiry(skillSPTo)));
                    c.getPlayer().changeSkillsLevel(sa);
                    used = true;
                }
                break;
            }
            case 5500000: { // Magic Hourglass 1 day
                final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
                final int days = 1;
                if (item != null && !ItemConstants.類型.飾品(item.getItemId()) && item.getExpiration() > -1
                        && !ii.isCash(item.getItemId()) && System.currentTimeMillis()
                        + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
                    boolean change = true;
                    for (String z : GameConstants.RESERVED) {
                        if (c.getPlayer().getName().contains(z) || item.getOwner().contains(z)) {
                            change = false;
                        }
                    }
                    if (change) {
                        item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                        c.getPlayer().forceReAddItem(item);
                        used = true;
                    } else {
                        c.getPlayer().dropMessage(1, "It may not be used on this item.");
                    }
                }
                break;
            }
            case 5500001: { // Magic Hourglass 7 day
                final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
                final int days = 7;
                if (item != null && !ItemConstants.類型.飾品(item.getItemId()) && item.getExpiration() > -1
                        && !ii.isCash(item.getItemId()) && System.currentTimeMillis()
                        + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
                    boolean change = true;
                    for (String z : GameConstants.RESERVED) {
                        if (c.getPlayer().getName().contains(z) || item.getOwner().contains(z)) {
                            change = false;
                        }
                    }
                    if (change) {
                        item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                        c.getPlayer().forceReAddItem(item);
                        used = true;
                    } else {
                        c.getPlayer().dropMessage(1, "It may not be used on this item.");
                    }
                }
                break;
            }
            case 5500002: { // Magic Hourglass 20 day
                final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
                final int days = 20;
                if (item != null && !ItemConstants.類型.飾品(item.getItemId()) && item.getExpiration() > -1
                        && !ii.isCash(item.getItemId()) && System.currentTimeMillis()
                        + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
                    boolean change = true;
                    for (String z : GameConstants.RESERVED) {
                        if (c.getPlayer().getName().contains(z) || item.getOwner().contains(z)) {
                            change = false;
                        }
                    }
                    if (change) {
                        item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                        c.getPlayer().forceReAddItem(item);
                        used = true;
                    } else {
                        c.getPlayer().dropMessage(1, "It may not be used on this item.");
                    }
                }
                break;
            }
            case 5500005: { // Magic Hourglass 50 day
                final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
                final int days = 50;
                if (item != null && !ItemConstants.類型.飾品(item.getItemId()) && item.getExpiration() > -1
                        && !ii.isCash(item.getItemId()) && System.currentTimeMillis()
                        + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
                    boolean change = true;
                    for (String z : GameConstants.RESERVED) {
                        if (c.getPlayer().getName().contains(z) || item.getOwner().contains(z)) {
                            change = false;
                        }
                    }
                    if (change) {
                        item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                        c.getPlayer().forceReAddItem(item);
                        used = true;
                    } else {
                        c.getPlayer().dropMessage(1, "It may not be used on this item.");
                    }
                }
                break;
            }
            case 5500006: { // Magic Hourglass 99 day
                final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
                final int days = 99;
                if (item != null && !ItemConstants.類型.飾品(item.getItemId()) && item.getExpiration() > -1
                        && !ii.isCash(item.getItemId()) && System.currentTimeMillis()
                        + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
                    boolean change = true;
                    for (String z : GameConstants.RESERVED) {
                        if (c.getPlayer().getName().contains(z) || item.getOwner().contains(z)) {
                            change = false;
                        }
                    }
                    if (change) {
                        item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                        c.getPlayer().forceReAddItem(item);
                        used = true;
                    } else {
                        c.getPlayer().dropMessage(1, "It may not be used on this item.");
                    }
                }
                break;
            }
            case 5060000: { // Item Tag
                final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());

                if (item != null && item.getOwner().equals("")) {
                    boolean change = true;
                    for (String z : GameConstants.RESERVED) {
                        if (c.getPlayer().getName().contains(z)) {
                            change = false;
                        }
                    }
                    if (change) {
                        item.setOwner(c.getPlayer().getName());
                        c.getPlayer().forceReAddItem(item);
                        used = true;
                    }
                }
                break;
            }
            case 5680015: {
                if (c.getPlayer().getFatigue() > 0) {
                    c.getPlayer().setFatigue(0);
                    used = true;
                }
                break;
            }
            case 5534000: { // tims lab
                final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem((byte) slea.readInt());
                if (item != null) {
                    final Equip eq = (Equip) item;
                    if (eq.getState(false) == 0) {
                        eq.resetPotential(false);
                        c.getPlayer().getMap()
                                .broadcastMessage(CField.showPotentialReset(c.getPlayer().getId(), true, itemId, false));
                        c.getSession().writeAndFlush(InventoryPacket.scrolledItem(toUse, item, false));
                        c.getPlayer().forceReAddItem_NoUpdate(item);
                        c.getSession().writeAndFlush(CField.enchantResult(1, item.getItemId()));
                        used = true;
                    } else {
                        c.getPlayer().dropMessage(5, "This item's Potential cannot be reset.");
                    }
                } else {
                    c.getPlayer().getMap()
                            .broadcastMessage(CField.showPotentialReset(c.getPlayer().getId(), false, itemId, false));
                    c.getSession().writeAndFlush(CField.enchantResult(0, 0));
                }
                break;
            }
            case 5062000:// 奇幻方塊miracle cube
            case 5062001:// 超級奇幻方塊premium cube
            case 5062002:// 傳說方塊
            case 5062004:// 星星方塊
            case 5062005:// 驚奇方塊enlightening cube
            case 5062006:// 白金奇幻方塊
            case 5062008:// 鏡射方塊
            case 5062009:// 紅色方塊
            case 5062010:// 黑色方塊
            case 5062013:// 太陽方塊
            case 5062017:// 閃耀方塊
            case 5062019:// 閃耀鏡射方塊
            case 5062020:// 閃炫方塊
            case 5062090:// 記憶方塊
            case 5062100:// 楓葉奇幻方塊
            case 5062102:// [6週年]奇幻方塊
            case 5062103:// 奇異奇幻方塊
            case 5062500: // 大師附加奇幻方塊
            case 5062501: { // [MS特價] 大師附加奇幻方塊
                final Equip eq = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP)
                        .getItem((short) (byte) slea.readInt());
                if (c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() < 1) {
                    c.getPlayer().dropMessage(6, "消耗欄空間不足。");
                    c.getSession().writeAndFlush(CField.enchantResult(0, 0));
                    break;
                }
                if (eq != null) {
                    boolean potLock = c.getPlayer().getInventory(MapleInventoryType.CASH).findById(5067000) != null;
                    int line = potLock && slea.available() > 0 ? slea.readInt() : 0;
                    int toLock = potLock && slea.available() > 0 ? slea.readUShort() : 0;
                    if ((line != 0 && ItemConstants.方塊.canLockCube(itemId)) || line == 0) {
                        if (checkPotentialLock(c.getPlayer(), eq, line, toLock)) {
                            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.CASH,
                                    c.getPlayer().getInventory(MapleInventoryType.CASH).findById(5067000).getPosition(),
                                    (short) 1, false);
                            used = c.getPlayer().useCube(itemId, eq, line);
                        } else {
                            used = c.getPlayer().useCube(itemId, eq);
                        }
                    }
                }
                if (used) {
                    c.getSession().writeAndFlush(InventoryPacket.scrolledItem(toUse, eq, false));
                }
                c.getSession().writeAndFlush(CField.enchantResult(used ? 1 : 0, eq == null ? 0 : eq.getItemId()));
                c.getPlayer().getMap().broadcastMessage(CField.showPotentialReset(c.getPlayer().getId(), used, itemId,
                        itemId == 5062500 || itemId == 5062501));
                break;
            }
            case 5062300: { // TMS無white awakening stamp
                final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem((byte) slea.readInt());
                if (item != null) {
                    final Equip eq = (Equip) item;
                    if (eq.getState(false) < 17) {
                        c.getPlayer().dropMessage(5, "This item's Potential cannot be reset.");
                        return;
                    }
                    if (eq.getPotential(3, false) != 0) {
                        c.getPlayer().dropMessage(5, "Cannot be used on this item.");
                        return;
                    }
                    final List<List<StructItemOption>> pots = new LinkedList<>(ii.getAllPotentialInfo().values());
                    final int reqLevel = ii.getReqLevel(eq.getItemId()) / 10;
                    int new_state = Math.abs(eq.getPotential(1, false));
                    if (new_state > 20 || new_state < 17) { // incase overflow
                        new_state = 17;
                    }
                    boolean rewarded = false;
                    while (!rewarded) {
                        StructItemOption pot = pots.get(Randomizer.nextInt(pots.size())).get(reqLevel);
                        if (pot != null && pot.reqLevel / 10 <= reqLevel
                                && ItemConstants.方塊.optionTypeFits(pot.optionType, eq.getItemId())
                                && ItemConstants.方塊.potentialIDFits(pot.opID, new_state, 1)) { // optionType
                            eq.setPotential(pot.opID, 3, false);
                            rewarded = true;
                        }
                    }
                    c.getSession().writeAndFlush(InventoryPacket.scrolledItem(toUse, item, false));
                    c.getPlayer().forceReAddItem_NoUpdate(item);
                    used = true;
                }
                break;
            }
            case 5062400: // 神秘鐵砧
            case 5062401:
            case 5062402: // 勳章的神秘鐵鉆
            case 5062403: // [MS特價] 神秘鐵砧
            case 5062404: { // 時裝神秘鐵砧
                short appearance = (short) slea.readInt();
                short function = (short) slea.readInt();
                Equip appear = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(appearance);
                Equip equip = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(function);
                if (!ItemConstants.類型.裝備(appear.getItemId()) || !ItemConstants.類型.裝備(equip.getItemId())
                        || appear.getItemId() / 10000 != equip.getItemId() / 10000) {
                    if (c.getPlayer().isShowErr()) {
                        c.getPlayer().showInfo("影像合成", false,
                                "外型道具不是裝備:" + !ItemConstants.類型.裝備(appear.getItemId()) + " 功能道具不是裝備:"
                                + !ItemConstants.類型.裝備(equip.getItemId()) + " 外型道具和功能道具不是同一類型:"
                                + (appear.getItemId() / 10000 != equip.getItemId() / 10000));
                    }
                    c.getSession().writeAndFlush(CWvsContext.getFusionAnvil(false, itemId, 2028093));
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }
                if (ItemConstants.類型.勳章(equip.getItemId())) {
                    if (itemId != 5062402) {
                        if (c.getPlayer().isShowErr()) {
                            c.getPlayer().showInfo("影像合成", false, "影像合成的勳章使用的道具不是 勳章的神秘鐵鉆");
                        }
                        c.getSession().writeAndFlush(CWvsContext.getFusionAnvil(false, itemId, 2028093));
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                } else if (ItemConstants.類型.防具(equip.getItemId()) || ItemConstants.類型.臉飾(equip.getItemId())
                        || ItemConstants.類型.眼飾(equip.getItemId()) || ItemConstants.類型.耳環(equip.getItemId())
                        || ItemConstants.類型.盾牌(equip.getItemId())) {
                    if (itemId == 5062404) {
                        if (!ii.isCash(equip.getItemId())) {
                            if (c.getPlayer().isShowErr()) {
                                c.getPlayer().showInfo("影像合成", false, "影像合成的現金道具使用的不是 時裝神秘鐵砧");
                            }
                            c.getSession().writeAndFlush(CWvsContext.getFusionAnvil(false, itemId, 2028093));
                            c.getSession().writeAndFlush(CWvsContext.enableActions());
                            return;
                        }
                    } else if (ii.isCash(equip.getItemId())) {
                        if (c.getPlayer().isShowErr()) {
                            c.getPlayer().showInfo("影像合成", false, "使用勳章的神秘鐵鉆的道具不非現金道具");
                        }
                        c.getSession().writeAndFlush(CWvsContext.getFusionAnvil(false, itemId, 2028093));
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                } else {
                    if (c.getPlayer().isShowErr()) {
                        c.getPlayer().showInfo("影像合成", false, "此道具類型無法使用影像合成");
                    }
                    c.getSession().writeAndFlush(CWvsContext.getFusionAnvil(false, itemId, 2028093));
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }

                int fusionAnvil = appear.getFusionAnvil();
                fusionAnvil = fusionAnvil > 0 ? fusionAnvil : appear.getItemId();
                if (equip.getFusionAnvil() != 0 && fusionAnvil == equip.getFusionAnvil()) {
                    c.getPlayer().dropMessage(5, "此道具已經是這個外型了。");
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }
                equip.setFusionAnvil(fusionAnvil);
                c.getPlayer().forceReAddItem_NoUpdate(equip);
                c.getPlayer().gainItem(2028093, 1);
                c.getSession().writeAndFlush(CWvsContext.InventoryPacket.updateSpecialItemUse((Item) equip));
                c.getSession().writeAndFlush(CWvsContext.getFusionAnvil(true, itemId, 2028093));
                used = true;
                break;
            }
            case 5750000: { // alien cube
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(1, "You may not use this until level 10.");
                } else {
                    final Item item = c.getPlayer().getInventory(MapleInventoryType.SETUP).getItem((byte) slea.readInt());
                    if (item != null && c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() >= 1
                            && c.getPlayer().getInventory(MapleInventoryType.SETUP).getNumFreeSlot() >= 1) {
                        final int grade = GameConstants.getNebuliteGrade(item.getItemId());
                        if (grade != -1 && grade < 4) {
                            final int rank = Randomizer.isSuccess(7)
                                    ? (Randomizer.isSuccess(2) ? (grade + 1) : (grade != 3 ? (grade + 1) : grade))
                                    : grade;
                            final List<StructItemOption> pots = new LinkedList<>(ii.getAllSocketInfo(rank).values());
                            int newId = 0;
                            while (newId == 0) {
                                StructItemOption pot = pots.get(Randomizer.nextInt(pots.size()));
                                if (pot != null) {
                                    newId = pot.opID;
                                }
                            }
                            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.SETUP, item.getPosition(),
                                    (short) 1, false);
                            MapleInventoryManipulator.addById(c, newId, (short) 1,
                                    "Upgraded from alien cube on " + FileoutputUtil.CurrentReadable_Date());
                            MapleInventoryManipulator.addById(c, 2430691, (short) 1,
                                    "Alien Cube" + " on " + FileoutputUtil.CurrentReadable_Date());
                            used = true;
                        } else {
                            c.getPlayer().dropMessage(1, "Grade S Nebulite cannot be added.");
                        }
                    } else {
                        c.getPlayer().dropMessage(5, "You do not have sufficient inventory slot.");
                    }
                }
                break;
            }
            case 5750001: { // socket diffuser
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(1, "You may not use this until level 10.");
                } else {
                    final Item item = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem((byte) slea.readInt());
                    if (item != null) {
                        final Equip eq = (Equip) item;
                        if (eq.getSocket(1) > 0) { // first slot only.
                            eq.setSocket(0, 1);
                            c.getSession().writeAndFlush(InventoryPacket.scrolledItem(toUse, item, false));
                            c.getPlayer().forceReAddItem_NoUpdate(item);
                            used = true;
                        } else {
                            c.getPlayer().dropMessage(5, "This item do not have a socket.");
                        }
                    } else {
                        c.getPlayer().dropMessage(5, "This item's nebulite cannot be removed.");
                    }
                }
                break;
            }
            case 5521000: { // 與自己分享名牌Karma
                final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
                final Item item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());

                if (item != null && ii.isShareTagEnabled(item.getItemId()) && ItemConstants.類型.裝備(item.getItemId())) {
                    if (ItemFlag.KARMA_ACC.check(item.getFlag()) && ItemFlag.KARMA.check(item.getFlag())) {
                        c.getPlayer().dropMessage(1, "該道具已是可交易狀態！");
                        break;
                    }
                    int flag = item.getFlag();
                    flag |= ItemFlag.KARMA_ACC.getValue();
                    flag |= ItemFlag.KARMA.getValue();
                    if (ItemFlag.MAPLE_CUBE.check(flag)) {
                        flag -= ItemFlag.MAPLE_CUBE.getValue();
                    }
                    item.setFlag(flag);
                    c.getPlayer().forceReAddItem_NoUpdate(item);
                    c.getSession().writeAndFlush(InventoryPacket.updateSpecialItemUse(item));
                    used = true;
                }
                break;
            }
            case 5520000: // 神奇剪刀Karma
            case 5520001: // 白金神奇剪刀p.karma
            case 5520002: { // 時裝神奇剪刀
                final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
                final Item item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());

                if (item != null && ItemConstants.類型.裝備(item.getItemId()) && ((Equip) item).getKarmaCount() != 0) {
                    if (ItemFlag.KARMA.check(item.getFlag())) {
                        c.getPlayer().dropMessage(1, "該道具已是可交易狀態！");
                        break;
                    }

                    boolean allowKarma = false;
                    switch (itemId) {
                        case 5520000:
                            allowKarma = ii.isKarmaEnabled(item.getItemId()) && !ItemFlag.MAPLE_CUBE.check(item.getFlag());
                            break;
                        case 5520001:
                            allowKarma = ii.isPKarmaEnabled(item.getItemId()) || ItemFlag.MAPLE_CUBE.check(item.getFlag());
                            break;
                        case 5520002:
                            allowKarma = ii.isCash(item.getItemId());
                            break;
                    }

                    Equip eq = (Equip) item;
                    if (allowKarma) {
                        int flag = item.getFlag();
                        flag = flag | ItemFlag.UNTRADABLE.getValue();
                        flag |= ItemFlag.KARMA.getValue();
                        if (ItemFlag.MAPLE_CUBE.check(flag)) {
                            flag -= ItemFlag.MAPLE_CUBE.getValue();
                        }
                        item.setFlag(flag);
                        if (eq.getKarmaCount() > 0) {
                            eq.setKarmaCount((byte) (eq.getKarmaCount() - 1));
                        }
                        c.getPlayer().forceReAddItem_NoUpdate(item);
                        c.getSession().writeAndFlush(InventoryPacket.updateSpecialItemUse(item));
                        used = true;
                    }
                }
                break;
            }
            case 5520003: { // 寵物白金剪刀
                c.getPlayer().dropMessage(1, "這個道具暫時無法使用。");
                // final MapleInventoryType type = MapleInventoryType.getByType((byte)
                // slea.readInt());
                // final Item item = c.getPlayer().getInventory(type).getItem((byte)
                // slea.readInt());
                //
                // if (item != null && ItemConstants.類型.寵物(item.getItemId())) {
                // if (ItemFlag.KARMA_EQ.check(item.getFlag()) ||
                // ItemFlag.KARMA_USE.check(item.getFlag())) {
                // c.getPlayer().dropMessage(1, "該道具已是可交易狀態！");
                // break;
                // }
                //
                // int flag = item.getFlag();
                // flag = flag | ItemFlag.UNTRADABLE.getValue();
                // if (type == MapleInventoryType.EQUIP) {
                // flag |= ItemFlag.KARMA_EQ.getValue();
                // } else {
                // flag |= ItemFlag.KARMA_USE.getValue();
                // }
                // if (ItemFlag.MAPLE_CUBE.check(flag)) {
                // flag -= ItemFlag.MAPLE_CUBE.getValue();
                // }
                // item.setFlag(flag);
                // c.getPlayer().forceReAddItem_NoUpdate(item);
                // c.getSession().writeAndFlush(InventoryPacket.updateSpecialItemUse(item,
                // type.getType(), item.getPosition(), true, c.getPlayer()));
                // used = true;
                // }
                break;
            }
            case 5570000: { // 黃金鐵鎚Vicious Hammer
                slea.readInt(); // Inventory type, Hammered eq is always EQ.
                final Equip item = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP)
                        .getItem((byte) slea.readInt());
                // another int here, D3 49 DC 00
                if (item != null) {
                    if (ItemConstants.卷軸.canHammer(item.getItemId())
                            && MapleItemInformationProvider.getInstance().getSlots(item.getItemId()) > 0
                            && item.getViciousHammer() < 1) {
                        item.setViciousHammer((byte) (item.getViciousHammer() + 1));
                        item.setUpgradeSlots((byte) (item.getUpgradeSlots() + 1));
                        c.getPlayer().forceReAddItem(item);
                        c.getSession().writeAndFlush(CCashShop.ViciousHammer(true, item.getViciousHammer()));
                        used = true;
                    } else {
                        c.getPlayer().dropMessage(5, "無法使用在這個道具上。");
                        c.getSession().writeAndFlush(CCashShop.ViciousHammer(true, (byte) 0));
                    }
                }
                break;
            }
            case 5610001:
            case 5610000: { // Vega 30
                slea.readInt(); // Inventory type, always eq
                final short dst = (short) slea.readInt();
                slea.readInt(); // Inventory type, always use
                final short src = (short) slea.readInt();
                used = UseUpgradeScroll(src, dst, (short) 2, c, c.getPlayer(), itemId, false, true); // cannot use ws with
                // vega but we dont
                // care
                cc = used;
                break;
            }
            case 5060001: // 封印之鎖 Sealing Lock
            case 5061000: // 封印之鎖 : 7日 Sealing Lock 7 days
            case 5061001: // 封印之鎖 : 30日 Sealing Lock 30 days
            case 5061002: // 封印之鎖 : 90日 Sealing Lock 90 days
            case 5061003: { // 封印之鎖 : 365日 Sealing Lock 365 days
                int days;
                switch (itemId) {
                    case 5061000:
                        days = 7;
                        break;
                    case 5061001:
                        days = 30;
                        break;
                    case 5061002:
                        days = 90;
                        break;
                    case 5061003:
                        days = 256;
                        break;
                    default:
                        days = 0;
                        break;
                }
                final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
                final Item item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());
                // another int here, lock = 5A E5 F2 0A, 7 day = D2 30 F3 0A
                if (item != null && !ii.isCash(itemId)) {
                    int flag = item.getFlag();
                    if (ItemFlag.LOCK.check(flag)) {
                        if (item.getExpiration() == -1) {
                            c.getPlayer().dropMessage(1, "該道具無法封印！");
                            break;
                        }

                        if (days > 0) {
                            item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000L));
                        } else {
                            item.setExpiration(-1);
                        }
                    } else if (item.getExpiration() != -1) {
                        c.getPlayer().dropMessage(1, "該道具無法封印！");
                        break;
                    } else {
                        flag |= ItemFlag.LOCK.getValue();
                        item.setFlag(flag);

                        if (days > 0) {
                            item.setExpiration(System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000L));
                        }
                    }

                    c.getPlayer().forceReAddItem_Flag(item);
                    used = true;
                } else {
                    c.getPlayer().dropMessage(1, "該道具無法封印！");
                }
                break;
            }
            case 5061100: { // 解除封印之鎖(1天)
                final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
                final Item item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());
                if (item != null) {
                    if (!ItemFlag.LOCK.check(item.getFlag())) {
                        c.getPlayer().dropMessage(1, "此道具並無封印!");
                    } else if (item.getExpiration() - System.currentTimeMillis() <= (24 * 60 * 60 * 1000L)) {
                        c.getPlayer().dropMessage(1, "已解除封印的道具就無法在解除.");
                    } else if (!c.CheckSecondPassword(slea.readMapleAsciiString())) {
                        c.getPlayer().dropMessage(1, "第2組密碼認證錯誤.");
                    } else {
                        item.setExpiration(System.currentTimeMillis()/* + (24 * 60 * 60 * 1000L) */);
                        c.getPlayer().forceReAddItem_Flag(item);
                        used = true;
                    }
                }
                break;
            }
            case 5063000: {
                final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
                final Item item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());
                // another int here, lock = 5A E5 F2 0A, 7 day = D2 30 F3 0A
                if (item != null && item.getType() == 1) { // equip
                    int flag = item.getFlag();
                    flag |= ItemFlag.LUCKY_DAY.getValue();
                    item.setFlag(flag);

                    c.getPlayer().forceReAddItem_Flag(item);
                    used = true;
                }
                break;
            }
            case 5064003:
            case 5064000: {
                short dst = slea.readShort();
                MapleInventoryType type;
                Item item;
                if (dst < 0) {
                    type = MapleInventoryType.EQUIPPED;
                    item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(dst);
                } else {
                    type = MapleInventoryType.EQUIP;
                    item = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem(dst);
                }
                if (item != null && item.getType() == 1) {
                    if (toUse.getItemId() == 5064003 && !ii.isSuperiorEquip(item.getItemId())) {
                        break;
                    }
                    if (toUse.getItemId() == 5064000 && ii.isSuperiorEquip(item.getItemId())) {
                        break;
                    }
                    int maxEnhance = itemId == 5064003 ? 7 : 12;
                    if (((Equip) item).getEnhance() >= maxEnhance) {
                        c.getPlayer().dropMessage(1, "該道具已無法繼續使用防爆捲軸效果。");
                        break;
                    }
                    int flag = item.getFlag();
                    if (!ItemFlag.SHIELD_WARD.check(flag)) {
                        flag = flag | ItemFlag.SHIELD_WARD.getValue();
                        item.setFlag(flag);
                        c.getPlayer().forceReAddItem_Flag(item);
                        c.getPlayer().getMap().broadcastMessage(c.getPlayer(),
                                InventoryPacket.scrolledItem(toUse, item, false), true);
                        c.getPlayer().getMap()
                                .broadcastMessage(c.getPlayer(), CField.getScrollEffect(c.getPlayer().getId(),
                                        Equip.ScrollResult.SUCCESS, false, false, toUse.getItemId(), item.getItemId()),
                                        true);
                        c.getSession().writeAndFlush(CField.enchantResult(1, item.getItemId()));
                        used = true;
                    } else {
                        c.getPlayer().dropMessage(1, "已經獲得了相同效果。");
                        break;
                    }
                } else {
                    c.getPlayer().dropMessage(1, "請將捲軸點在你需要保護的裝備上。");
                    break;
                }
                break;
            }

            case 5060003:
            case 5060004:
            case 5060005:
            case 5060006:
            case 5060007: {
                Item item = c.getPlayer().getInventory(MapleInventoryType.ETC)
                        .findById(itemId == 5060003 ? 4170023 : 4170024);
                if (item == null || item.getQuantity() <= 0) { // hacking{
                    return;
                }
                if (getIncubatedItems(c, itemId)) {
                    MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.ETC, item.getPosition(), (short) 1,
                            false);
                    used = true;
                }
                break;
            }
            case 5062800: { // 奇幻傳播者
                if (c.getPlayer().getInnerRank() > 0) {
                    c.getPlayer().renewInnerSkills(itemId);
                    used = true;
                } else {
                    c.getPlayer().dropMessage(1, "奇幻傳播者稀有等級以上才能使用。");
                }
                break;
            }
            case 5070000: { // Megaphone
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final String message = slea.readMapleAsciiString();

                    if (message.length() > 65) {
                        break;
                    }
                    final StringBuilder sb = new StringBuilder();
                    addMedalString(c.getPlayer(), sb);
                    sb.append(c.getPlayer().getName());
                    sb.append(" : ");
                    sb.append(message);

                    c.getPlayer().getMap().broadcastMessage(CWvsContext.broadcastMsg(2, sb.toString()));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5071000: { // Megaphone
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final String message = slea.readMapleAsciiString();

                    if (message.length() > 65) {
                        break;
                    }
                    final StringBuilder sb = new StringBuilder();
                    addMedalString(c.getPlayer(), sb);
                    sb.append(c.getPlayer().getName());
                    sb.append(" : ");
                    sb.append(message);

                    c.getChannelServer().broadcastSmegaPacket(CWvsContext.broadcastMsg(2, sb.toString()));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5077000: { // 3 line Megaphone
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final byte numLines = slea.readByte();
                    if (numLines > 3) {
                        return;
                    }
                    final List<String> messages = new LinkedList<>();
                    String message;
                    for (int i = 0; i < numLines; i++) {
                        message = slea.readMapleAsciiString();
                        if (message.length() > 65) {
                            break;
                        }
                        messages.add(c.getPlayer().getName() + " : " + message);
                    }
                    final boolean ear = slea.readByte() > 0;

                    World.Broadcast.broadcastSmega(CWvsContext.tripleSmega(messages, ear, c.getChannel()));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5079004: { // Heart Megaphone
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final String message = slea.readMapleAsciiString();

                    if (message.length() > 65) {
                        break;
                    }
                    World.Broadcast.broadcastSmega(CWvsContext.echoMegaphone(c.getPlayer().getName(), message));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5073000: { // 愛心喇叭
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final String message = slea.readMapleAsciiString();

                    if (message.length() > 65) {
                        break;
                    }
                    final StringBuilder sb = new StringBuilder();
                    addMedalString(c.getPlayer(), sb);
                    sb.append(c.getPlayer().getName());
                    sb.append(" : ");
                    sb.append(message);

                    final boolean ear = slea.readByte() != 0;
                    World.Broadcast.broadcastSmega(CWvsContext.broadcastMsg(28, c.getChannel(), sb.toString(), ear));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5074000: { // 骷髏喇叭
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final String message = slea.readMapleAsciiString();

                    if (message.length() > 65) {
                        break;
                    }
                    final StringBuilder sb = new StringBuilder();
                    addMedalString(c.getPlayer(), sb);
                    sb.append(c.getPlayer().getName());
                    sb.append(" : ");
                    sb.append(message);

                    final boolean ear = slea.readByte() != 0;

                    World.Broadcast.broadcastSmega(CWvsContext.broadcastMsg(29, c.getChannel(), sb.toString(), ear));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5072000: { // Super Megaphone
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final String message = slea.readMapleAsciiString();

                    if (message.length() > 65) {
                        break;
                    }
                    final StringBuilder sb = new StringBuilder();
                    addMedalString(c.getPlayer(), sb);
                    sb.append(c.getPlayer().getName());
                    sb.append(" : ");
                    sb.append(message);

                    final boolean ear = slea.readByte() != 0;

                    World.Broadcast.broadcastSmega(CWvsContext.broadcastMsg(3, c.getChannel(), sb.toString(), ear));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5076000: { // Item Megaphone
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final String message = slea.readMapleAsciiString();

                    if (message.length() > 65) {
                        break;
                    }
                    final StringBuilder sb = new StringBuilder();
                    addMedalString(c.getPlayer(), sb);
                    sb.append(c.getPlayer().getName());
                    sb.append(" : ");
                    sb.append(message);

                    final boolean ear = slea.readByte() > 0;

                    Item item = null;
                    if (slea.readByte() == 1) { // item
                        byte invType = (byte) slea.readInt();
                        byte pos = (byte) slea.readInt();
                        if (pos <= 0) {
                            invType = -1;
                        }
                        item = c.getPlayer().getInventory(MapleInventoryType.getByType(invType)).getItem(pos);
                    }
                    World.Broadcast.broadcastSmega(CWvsContext.itemMegaphone(sb.toString(), ear, c.getChannel(), item));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5079000: {
                break;
            }
            case 5079001: // 蛋糕喇叭
            case 5079002: { // 派餅喇叭
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final String message = slea.readMapleAsciiString();

                    if (message.length() > 65) {
                        break;
                    }
                    final StringBuilder sb = new StringBuilder();
                    addMedalString(c.getPlayer(), sb);
                    sb.append(c.getPlayer().getName());
                    sb.append(" : ");
                    sb.append(message);

                    final boolean ear = slea.readByte() != 0;

                    World.Broadcast
                            .broadcastSmega(CWvsContext.broadcastMsg(25 + itemId % 10, c.getChannel(), sb.toString(), ear));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5075000: // MapleTV Messenger
            case 5075001: // MapleTV Star Messenger
            case 5075002: { // MapleTV Heart Messenger
                c.getPlayer().dropMessage(5, "There are no MapleTVs to broadcast the message to.");
                break;
            }
            case 5075003:
            case 5075004:
            case 5075005: {
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canSmega()) {
                    c.getPlayer().dropMessage(5, "每15秒內只能使用一次廣播");
                    break;
                }
                int tvType = itemId % 10;
                if (tvType == 3) {
                    slea.readByte(); // who knows
                }
                boolean ear = tvType != 1 && tvType != 2 && slea.readByte() > 1; // for tvType 1/2, there is no byte.
                MapleCharacter victim = tvType == 1 || tvType == 4 ? null
                        : c.getChannelServer().getPlayerStorage().getCharacterByName(slea.readMapleAsciiString()); // for
                // tvType
                // 4,
                // there
                // is no
                // string.
                if (tvType == 0 || tvType == 3) { // doesn't allow two
                    victim = null;
                } else if (!c.getChannelServer().getMegaphoneMuteState()) {
                    List<String> lines = new LinkedList<String>();
                    for (int i = 0; i < 4; i++) {
                        lines.add(slea.readMapleAsciiString());
                    }
                    String mas = "";
                    for (int i = 0; i < lines.size(); i++) {
                        mas += lines.get(i);
                    }
                    World.Broadcast.broadcastMessage(CWvsContext.broadcastMsg(2, c.getChannel(),
                            "[玩家 " + c.getPlayer().getName() + "][頻道 " + c.getChannel() + "] : " + mas));
                }
                // 國服修改
                // } else if (victim == null) {
                // c.getPlayer().dropMessage(1, "That character is not in the channel.");
                // break;
                // }
                // String message = slea.readMapleAsciiString();
                // World.Broadcast.broadcastSmega(CWvsContext.broadcastMsg(3, c.getChannel(),
                // c.getPlayer().getName() + " : " + message, ear));
                used = true;
                break;
            }
            case 5090000: // 訊息
            case 5090100: { // 請柬-1
                final String sendTo = slea.readMapleAsciiString();
                final String msg = slea.readMapleAsciiString();
                if (MapleCharacterUtil.canCreateChar(sendTo, false)) { // 檢查角色名稱
                    c.getSession().writeAndFlush(CCashShop.OnMemoResult((byte) 6, (byte) 1)); // 請確認接收的角色名稱。
                } else {
                    int ch = World.Find.findChannel(sendTo);
                    if (ch <= 0) { // 離線狀態
                        c.getPlayer().sendNote(sendTo, msg);
                        c.getSession().writeAndFlush(CCashShop.OnMemoResult((byte) 5, (byte) 0)); // 訊息傳送成功！
                        used = true;
                    } else {
                        c.getSession().writeAndFlush(CCashShop.OnMemoResult((byte) 6, (byte) 0));
                    }
                }
                break;
            }
            case 5100000: { // 賀曲
                c.getPlayer().getMap().broadcastMessage(CField.musicChange("Jukebox/Congratulation"));
                used = true;
                break;
            }
            case 5152100:
            case 5152101:
            case 5152102:
            case 5152103:
            case 5152104:
            case 5152105:
            case 5152106:
            case 5152107: { // 日拋隱形眼鏡
                int color = itemId - 5152100;

                if (color >= 0 && c.getPlayer().changeFace(color)) {
                    used = true;
                } else {
                    c.getPlayer().dropMessage(1, "使用日拋隱形眼鏡出現錯誤。");
                }
                break;
            }
            case 5155000: { // 卡勒塔的珍珠(更變精靈耳朵)
                int elf = c.getPlayer().getElf();
                boolean isMercedes = MapleJob.is精靈遊俠(c.getPlayer().getJob());
                if ((elf == 0 && !isMercedes) || (elf == 1 && isMercedes)) {
                    c.getSession()
                            .writeAndFlush(EffectPacket.showWeirdEffect("Effect/BasicEff.img/JobChangedElf", 5155000));
                    c.getPlayer().getMap().broadcastMessage(c.getPlayer(),
                            EffectPacket.showWeirdEffect(c.getPlayer(), "Effect/BasicEff.img/JobChangedElf", 5155000),
                            false);
                } else {
                    c.getSession().writeAndFlush(EffectPacket.showWeirdEffect("Effect/BasicEff.img/JobChanged", 5155000));
                    c.getPlayer().getMap().broadcastMessage(c.getPlayer(),
                            EffectPacket.showWeirdEffect(c.getPlayer(), "Effect/BasicEff.img/JobChanged", 5155000), false);
                }
                c.getPlayer().setElf(elf == 0 ? 1 : 0);
                used = true;
                break;
            }
            case 5156000: { // 偉大的變身之藥
                if (c.getPlayer().getMarriageId() > 0) {
                    c.getPlayer().dropMessage(11, "已婚人士無法使用。");
                } else if (MapleJob.is米哈逸(c.getPlayer().getJob()) || MapleJob.is天使破壞者(c.getPlayer().getJob())
                        || MapleJob.is神之子(c.getPlayer().getJob())) {
                    c.getPlayer().dropMessage(11, "該職業群無法使用的道具。");
                } else {
                    Pair<Integer, Integer> ret = GameConstants.getDefaultFaceAndHair(c.getPlayer().getJob(), c.getPlayer().getGender());
                    Map<MapleStat, Long> statup = new EnumMap<>(MapleStat.class);
                    c.getPlayer().setGender(c.getPlayer().getGender() == 0 ? 1 : (byte) 0);
                    c.getPlayer().setFace(ret.getLeft());
                    c.getPlayer().setHair(ret.getRight());
                    statup.put(MapleStat.FACE, (long) c.getPlayer().getFace());
                    statup.put(MapleStat.HAIR, (long) c.getPlayer().getHair());
                    statup.put(MapleStat.GENDER, (long) c.getPlayer().getGender());
                    c.getSession().writeAndFlush(CWvsContext.updatePlayerStats(statup, c.getPlayer()));
                    c.getSession().writeAndFlush(
                            CField.EffectPacket.showCraftingEffect("Effect/BasicEff.img/TransGender", (byte) 1, 0, 0));
                    c.getPlayer().getMap().broadcastMessage(c.getPlayer(), CField.EffectPacket
                            .showCraftingEffect(c.getPlayer(), "Effect/BasicEff.img/TransGender", (byte) 1, 0, 0), false);
                    c.getPlayer().equipChanged();
                    used = true;
                }
                break;
            }
            case 5190000: // 撿道具技能
            case 5190001: // 自動服用HP藥水技能
            case 5190002: // 擴大移動範圍技能
            case 5190003: // 範圍自動撿起功能
            case 5190004: // 撿起無所有權道具&楓幣技能
            case 5190005: // 勿撿特定道具技能
            case 5190006: // 自動服用MP藥水技能
            case 5190007: // 寵物召喚
            case 5190008: // 自言自語
            case 5190009: // 寵物萬能療傷藥自動使用技能
            case 5190010: // 寵物自動加持技能
            case 5190011: // 寵物訓練技能
            case 5190012: // 寵物巨大技能
            case 5190013: { // 開寵物商店技能
                final int uniqueid = (int) slea.readLong();
                MaplePet pet = c.getPlayer().getSummonedPet(0);
                int slo = 0;

                if (pet == null) {
                    break;
                }
                if (pet.getUniqueId() != uniqueid) {
                    pet = c.getPlayer().getSummonedPet(1);
                    slo = 1;
                    if (pet != null) {
                        if (pet.getUniqueId() != uniqueid) {
                            pet = c.getPlayer().getSummonedPet(2);
                            slo = 2;
                            if (pet != null) {
                                if (pet.getUniqueId() != uniqueid) {
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                    } else {
                        break;
                    }
                }
                PetFlag zz = PetFlag.getByAddId(itemId);
                if (zz != null && !zz.check(pet.getFlags())) {
                    pet.setFlags(pet.getFlags() | zz.getValue());
                    c.getSession().writeAndFlush(PetPacket.updatePet(c.getPlayer().getInventory(MapleInventoryType.CASH)
                            .getItem((byte) pet.getInventoryPosition())));
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    c.getSession().writeAndFlush(CCashShop.changePetFlag(uniqueid, true, zz.getValue()));
                    used = true;
                }
                break;
            }
            case 5191000: // 取消撿道具功能
            case 5191001: // 取消自動服用藥水功能
            case 5191002: // 取消擴大移動範圍技能
            case 5191003: // 取消範圍自動撿起功能
            case 5191004: { // 取消撿起無所有權道具&楓幣功能
                final int uniqueid = (int) slea.readLong();
                MaplePet pet = c.getPlayer().getSummonedPet(0);
                int slo = 0;

                if (pet == null) {
                    break;
                }
                if (pet.getUniqueId() != uniqueid) {
                    pet = c.getPlayer().getSummonedPet(1);
                    slo = 1;
                    if (pet != null) {
                        if (pet.getUniqueId() != uniqueid) {
                            pet = c.getPlayer().getSummonedPet(2);
                            slo = 2;
                            if (pet != null) {
                                if (pet.getUniqueId() != uniqueid) {
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                    } else {
                        break;
                    }
                }
                PetFlag zz = PetFlag.getByDelId(itemId);
                if (zz != null && zz.check(pet.getFlags())) {
                    pet.setFlags(pet.getFlags() - zz.getValue());
                    c.getSession().writeAndFlush(PetPacket.updatePet(c.getPlayer().getInventory(MapleInventoryType.CASH)
                            .getItem((byte) pet.getInventoryPosition())));
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    c.getSession().writeAndFlush(CCashShop.changePetFlag(uniqueid, false, zz.getValue()));
                    used = true;
                }
                break;
            }
            case 5501001:
            case 5501002: { // expiry mount
                final Skill skil = SkillFactory.getSkill(slea.readInt());
                if (skil == null || skil.getId() / 10000 != 8000 || c.getPlayer().getSkillLevel(skil) <= 0
                        || !skil.isTimeLimited() || GameConstants.getMountItem(skil.getId(), c.getPlayer()) <= 0) {
                    break;
                }
                final long toAdd = (itemId == 5501001 ? 30 : 60) * 24 * 60 * 60 * 1000L;
                final long expire = c.getPlayer().getSkillExpiry(skil);
                if (expire < System.currentTimeMillis()
                        || expire + toAdd >= System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L)) {
                    break;
                }
                c.getPlayer().changeSingleSkillLevel(skil, c.getPlayer().getSkillLevel(skil),
                        c.getPlayer().getMasterLevel(skil), expire + toAdd);
                used = true;
                break;
            }
            case 5170000: { // Pet name change
                final int uniqueid = (int) slea.readLong();
                MaplePet pet = c.getPlayer().getSummonedPet(0);
                int slo = 0;

                if (pet == null) {
                    break;
                }
                if (pet.getUniqueId() != uniqueid) {
                    pet = c.getPlayer().getSummonedPet(1);
                    slo = 1;
                    if (pet != null) {
                        if (pet.getUniqueId() != uniqueid) {
                            pet = c.getPlayer().getSummonedPet(2);
                            slo = 2;
                            if (pet != null) {
                                if (pet.getUniqueId() != uniqueid) {
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                    } else {
                        break;
                    }
                }
                String nName = slea.readMapleAsciiString();
                for (String z : GameConstants.RESERVED) {
                    if (pet.getName().contains(z) || nName.contains(z)) {
                        break;
                    }
                }
                if (MapleCharacterUtil.canChangePetName(nName)) {
                    pet.setName(nName);
                    c.getSession().writeAndFlush(PetPacket.updatePet(c.getPlayer().getInventory(MapleInventoryType.CASH)
                            .getItem((byte) pet.getInventoryPosition())));
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    c.getPlayer().getMap().broadcastMessage(CCashShop.changePetName(c.getPlayer(), nName, slo));
                    used = true;
                }
                break;
            }
            case 5700000: {
                slea.skip(8);
                if (c.getPlayer().getAndroid() == null) {
                    break;
                }
                String nName = slea.readMapleAsciiString();
                for (String z : GameConstants.RESERVED) {
                    if (c.getPlayer().getAndroid().getName().contains(z) || nName.contains(z)) {
                        break;
                    }
                }
                if (MapleCharacterUtil.canChangePetName(nName)) {
                    c.getPlayer().getAndroid().setName(nName);
                    c.getPlayer().setAndroid(c.getPlayer().getAndroid()); // respawn it
                    used = true;
                }
                break;
            }
            case 5240000:
            case 5240001:
            case 5240002:
            case 5240003:
            case 5240004:
            case 5240005:
            case 5240006:
            case 5240007:
            case 5240008:
            case 5240009:
            case 5240010:
            case 5240011:
            case 5240012:
            case 5240013:
            case 5240014:
            case 5240015:
            case 5240016:
            case 5240017:
            case 5240018:
            case 5240019:
            case 5240020:
            case 5240021:
            case 5240022:
            case 5240023:
            case 5240024:
            case 5240025:
            case 5240026:
            case 5240027:
            case 5240029:
            case 5240030:
            case 5240031:
            case 5240032:
            case 5240033:
            case 5240034:
            case 5240035:
            case 5240036:
            case 5240037:
            case 5240038:
            case 5240039:
            case 5240040:
            case 5240028: { // Pet food
                MaplePet pet = c.getPlayer().getSummonedPet(0);

                if (pet == null) {
                    break;
                }
                if (!pet.canConsume(itemId)) {
                    pet = c.getPlayer().getSummonedPet(1);
                    if (pet != null) {
                        if (!pet.canConsume(itemId)) {
                            pet = c.getPlayer().getSummonedPet(2);
                            if (pet != null) {
                                if (!pet.canConsume(itemId)) {
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                    } else {
                        break;
                    }
                }
                final byte petindex = c.getPlayer().getPetIndex(pet);
                pet.setFullness(100);
                if (pet.getCloseness() < 30000) {
                    if (pet.getCloseness() + (100 * c.getChannelServer().getTraitRate()) > 30000) {
                        pet.setCloseness(30000);
                    } else {
                        pet.setCloseness(pet.getCloseness() + (100 * c.getChannelServer().getTraitRate()));
                    }
                    if (pet.getCloseness() >= GameConstants.getClosenessNeededForLevel(pet.getLevel() + 1)) {
                        pet.setLevel(pet.getLevel() + 1);
                        c.getSession().writeAndFlush(EffectPacket.showPetLevelUp(c.getPlayer().getPetIndex(pet)));
                        c.getPlayer().getMap().broadcastMessage(EffectPacket.showPetLevelUp(c.getPlayer(), petindex));
                    }
                }
                c.getSession().writeAndFlush(PetPacket.updatePet(
                        c.getPlayer().getInventory(MapleInventoryType.CASH).getItem(pet.getInventoryPosition())));
                c.getPlayer().getMap().broadcastMessage(c.getPlayer(),
                        PetPacket.commandResponse(c.getPlayer().getId(), (byte) 1, petindex, true, true), true);
                used = true;
                break;
            }
            case 5230001:
            case 5230000: {// owl of minerva
                final int itemSearch = slea.readInt();
                final List<HiredMerchant> hms = c.getChannelServer().searchMerchant(itemSearch);
                if (hms.size() > 0) {
                    c.getSession().writeAndFlush(CWvsContext.getOwlSearched(itemSearch, hms));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(1, "Unable to find the item.");
                }
                break;
            }
            case 5281001: // idk, but probably
            case 5280001: // Gas Skill
            case 5281000: { // Passed gas
                Rectangle bounds = new Rectangle((int) c.getPlayer().getPosition().getX(),
                        (int) c.getPlayer().getPosition().getY(), 1, 1);
                MapleAffectedArea mist = new MapleAffectedArea(bounds, c.getPlayer());
                c.getPlayer().getMap().spawnAffectedArea(mist, 10000, true);
                c.getSession().writeAndFlush(CWvsContext.enableActions());
                used = true;
                break;
            }
            case 5370001:
            case 5370000: { // Chalkboard
                for (MapleEventType t : MapleEventType.values()) {
                    final MapleEvent e = ChannelServer.getInstance(c.getChannel()).getEvent(t);
                    if (e.isRunning()) {
                        for (int i : e.getType().mapids) {
                            if (c.getPlayer().getMapId() == i) {
                                c.getPlayer().dropMessage(5, "You may not use that here.");
                                c.getSession().writeAndFlush(CWvsContext.enableActions());
                                return;
                            }
                        }
                    }
                }
                c.getPlayer().setChalkboard(slea.readMapleAsciiString());
                break;
            }
            case 5390000: // Diablo Messenger
            case 5390001: // Cloud 9 Messenger
            case 5390002: // Loveholic Messenger
            case 5390003: // New Year Messenger 1
            case 5390004: // New Year Messenger 2
            case 5390005: // Cute Tiger Messenger
            case 5390006: // Tiger Roar's Messenger
            case 5390007:
            case 5390008:
            case 5390009:
            case 5390018:
            case 5390019:
            case 5390023:
            case 5390024:
            case 5390025:
            case 5390026:
            case 5390027:
            case 5390028:
            case 5390029:
            case 5390031:
            case 5390033:
            case 5390034: {
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "Must be level 10 or higher.");
                    break;
                }
                if (c.getPlayer().getMapId() == GameConstants.JAIL) {
                    c.getPlayer().dropMessage(5, "Cannot be used here.");
                    break;
                }
                if (!c.getPlayer().getCheatTracker().canAvatarSmega()) {
                    c.getPlayer().dropMessage(5, "每5分鐘以內只能使用一次");
                    break;
                }
                if (!c.getChannelServer().getMegaphoneMuteState()) {
                    final List<String> lines = new LinkedList<>();
                    if (itemId == 5390009) { // friend finder megaphone
                        lines.add("I'm looking for ");
                        lines.add("friends! Send a ");
                        lines.add("Friend Request if ");
                        lines.add("you're intetested!");
                    } else {
                        for (int i = 0; i < 4; i++) {
                            final String text = slea.readMapleAsciiString();
                            if (text.length() > 55) {
                                continue;
                            }
                            lines.add(text);
                        }
                    }
                    final boolean ear = slea.readByte() != 0;
                    World.Broadcast
                            .broadcastSmega(CWvsContext.getAvatarMega(c.getPlayer(), c.getChannel(), itemId, lines, ear));
                    used = true;
                } else {
                    c.getPlayer().dropMessage(5, "The usage of Megaphone is currently disabled.");
                }
                break;
            }
            case 5452001:
            case 5450003:
            case 5450000: { // Mu Mu the Travelling Merchant
                for (int i : GameConstants.blockedMaps) {
                    if (c.getPlayer().getMapId() == i) {
                        c.getPlayer().dropMessage(5, "You may not use this command here.");
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                }
                if (c.getPlayer().getLevel() < 10) {
                    c.getPlayer().dropMessage(5, "You must be over level 10 to use this command.");
                } else if (c.getPlayer().hasBlockedInventory() || c.getPlayer().getMap().getSquadByMap() != null
                        || c.getPlayer().getEventInstance() != null || c.getPlayer().getMap().getEMByMap() != null
                        || c.getPlayer().getMapId() >= 990000000) {
                    c.getPlayer().dropMessage(5, "You may not use this command here.");
                } else if ((c.getPlayer().getMapId() >= 680000210 && c.getPlayer().getMapId() <= 680000502)
                        || (c.getPlayer().getMapId() / 1000 == 980000 && c.getPlayer().getMapId() != 980000000)
                        || (c.getPlayer().getMapId() / 100 == 1030008) || (c.getPlayer().getMapId() / 100 == 922010)
                        || (c.getPlayer().getMapId() / 10 == 13003000)) {
                    c.getPlayer().dropMessage(5, "You may not use this command here.");
                } else {
                    MapleShopFactory.getInstance().getShop(61).sendShop(c);
                }
                // used = true;
                break;
            }
            case 5300000:
            case 5300001:
            case 5300002: { // Cash morphs
                ii.getItemEffect(itemId).applyTo(c.getPlayer());
                used = true;
                break;
            }
            case 5781000: { // pet color dye
                slea.readInt();
                slea.readInt();
                int color = slea.readInt();

                break;
            }
            case 5064100: // 安全盾牌卷軸
            case 5064101: // 星光安全盾牌卷軸(105以下的裝備)
            case 5068100: { // 寵物安全盾牌卷軸
                UseUpgradeScroll(slot, slea.readShort(), (short) 0, c, c.getPlayer(), 0, false, true);
                break;
            }
            default:
                if (itemId / 10000 == 512) {
                    String msg = ii.getMsg(itemId);
                    final String ourMsg = slea.readMapleAsciiString();
                    if (!msg.contains("%s")) {
                        msg = ourMsg;
                    } else {
                        msg = msg.replaceFirst("%s", c.getPlayer().getName());
                        if (!msg.contains("%s")) {
                            msg = ii.getMsg(itemId).replaceFirst("%s", ourMsg);
                        } else {
                            try {
                                msg = msg.replaceFirst("%s", ourMsg);
                            } catch (Exception e) {
                                msg = ii.getMsg(itemId).replaceFirst("%s", ourMsg);
                            }
                        }
                    }
                    c.getPlayer().getMap().startMapEffect(msg, itemId);

                    final int buff = ii.getStateChangeItem(itemId);
                    if (buff != 0) {
                        for (MapleCharacter mChar : c.getPlayer().getMap().getCharactersThreadsafe()) {
                            ii.getItemEffect(buff).applyTo(mChar);
                        }
                    }
                    used = true;
                } else if (itemId / 10000 == 510) {
                    c.getPlayer().getMap().startJukebox(c.getPlayer().getName(), itemId);
                    used = true;
                } else if (itemId / 10000 == 520) {
                    final int mesars = MapleItemInformationProvider.getInstance().getMeso(itemId);
                    if (mesars > 0 && c.getPlayer().getMeso() < (Integer.MAX_VALUE - mesars)) {
                        used = true;
                        if (Math.random() > 0.1) {
                            final int gainmes = Randomizer.nextInt(mesars);
                            c.getPlayer().gainMeso(gainmes, false);
                            c.getSession().writeAndFlush(CCashShop.sendMesobagSuccess(gainmes));
                        } else {
                            c.getSession().writeAndFlush(CCashShop.sendMesobagFailed(false)); // not random
                        }
                    }
                } else if (itemId / 10000 == 562) {
                    if (UseSkillBook(slot, itemId, c, c.getPlayer())) {
                        c.getPlayer().gainSP(1);
                    } // this should handle removing
                } else if (itemId / 10000 == 553) {
                    UseRewardItem(slot, itemId, false, c, c.getPlayer());// this too
                } else if (itemId / 10000 != 519) {
                    System.out.println("Unhandled CS item : " + itemId);
                    System.out.println(slea.toString(true));
                }
                break;
        }

        if (used) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.CASH, slot, (short) 1, false, true);
        }

        c.getSession().writeAndFlush(CWvsContext.enableActions());
        if (cc) {
            if (!c.getPlayer().isAlive() || c.getPlayer().getEventInstance() != null
                    || FieldLimitType.ChannelSwitch.check(c.getPlayer().getMap().getFieldLimit())) {
                c.getPlayer().dropMessage(1, "Auto relog failed.");
                return;
            }
            c.getPlayer().dropMessage(5, "Auto relogging. Please wait.");
            c.getPlayer().fakeRelog();
            if (c.getPlayer().getScrolledPosition() != 0) {
                c.getSession().writeAndFlush(CWvsContext.pamSongUI());
            }
        }
    }

    public static final void Pickup_Player(final LittleEndianAccessor slea, MapleClient c) {
        if (c == null) {
            return;
        }
        final MapleCharacter chr = c.getPlayer();
        if (chr != null && !chr.hasBlockedInventory()) { // hack
            Pickup(slea, c, null);
        }
        c.getSession().writeAndFlush(InventoryPacket.getInventoryFull());
    }

    public static final void Pickup_Pet(final LittleEndianAccessor slea, final MapleClient c) {
        if (c == null) {
            return;
        }
        final MapleCharacter chr = c.getPlayer();
        if (chr != null) {
            final byte petz = (byte) slea.readInt();// c.getPlayer().getPetIndex((int)slea.readLong());
            final MaplePet pet = chr.getSummonedPet(petz);
            if (pet != null) {
                Pickup(slea, c, pet);
            }
        }
        c.getSession().writeAndFlush(InventoryPacket.updateInventoryFull());
    }

    public static final void Pickup(final LittleEndianAccessor slea, MapleClient c, MaplePet pet) {
        if (c == null) {
            return;
        }
        final MapleCharacter chr = c.getPlayer();
        if (chr == null || slea.available() < 13) {
            return;
        }
        chr.setScrolledPosition((short) 0);
        slea.skip(1); // [4] Zero, [4] Seems to be tickcount, [1] Always zero
        chr.updateTick(slea.readInt());
        final Point Client_Reportedpos = slea.readPos();
        final MapleMapObject ob = chr.getMap().getMapObject(slea.readInt(), MapleMapObjectType.ITEM);
        Pickup(ob, c, pet, Client_Reportedpos, true);
    }

    public static final void Pickup(MapleMapObject ob, MapleClient c, MaplePet pet, Point Client_Reportedpos,
            boolean showNullPickup) {
        final MapleCharacter chr = c.getPlayer();
        if (ob == null || chr == null) {
            return;
        }
        final MapleMapItem mapitem = (MapleMapItem) ob;
        final Lock lock = mapitem.getLock();
        lock.lock();
        try {
            if (mapitem.getItemId() == 4033270) {
                return;
            }
            if (mapitem.isPickedUp()) {
                return;
            }
            if (mapitem.getQuest() > 0 && chr.getQuestStatus(mapitem.getQuest()) != 1) {
                return;
            }
            if (pet != null && mapitem.getOwner() != chr.getId() && mapitem.isPlayerDrop()) {
                return;
            }
            if (mapitem.getOwner() != chr.getId() && ((!mapitem.isPlayerDrop() && mapitem.getDropType() == 0) || (mapitem.isPlayerDrop() && chr.getMap().getEverlast()))) {
                return;
            }
            if (!mapitem.isPlayerDrop() && mapitem.getDropType() == 1 && mapitem.getOwner() != chr.getId()
                    && (chr.getParty() == null || chr.getParty().getMemberById(mapitem.getOwner()) == null)) {
                return;
            }
            final double Distance = Client_Reportedpos.distanceSq(mapitem.getPosition());
            if (pet == null) {
                if (Distance > 5000 && (mapitem.getMeso() > 0 || mapitem.getItemId() != 4001025)) {
                    chr.getCheatTracker().registerOffense(CheatingOffense.ITEMVAC_CLIENT, String.valueOf(Distance));
                } else if (chr.getPosition().distanceSq(mapitem.getPosition()) > 640000.0) {
                    chr.getCheatTracker().registerOffense(CheatingOffense.ITEMVAC_SERVER);
                }
            } else if (Distance > 10000 && (mapitem.getMeso() > 0 || mapitem.getItemId() != 4001025)) {
                chr.getCheatTracker().registerOffense(CheatingOffense.PET_ITEMVAC_CLIENT, String.valueOf(Distance));
            } else if (pet.getPos().distanceSq(mapitem.getPosition()) > 640000.0) {
                chr.getCheatTracker().registerOffense(CheatingOffense.PET_ITEMVAC_SERVER);
            }
            if (mapitem.getMeso() > 0) {
                if (chr.getParty() != null && mapitem.getOwner() != chr.getId()) {
                    final List<MapleCharacter> toGive = new LinkedList<>();
                    final int splitMeso = mapitem.getMeso() * 40 / 100;
                    for (MaplePartyCharacter z : chr.getParty().getMembers()) {
                        MapleCharacter m = chr.getMap().getCharacterById(z.getId());
                        if (m != null && m.getId() != chr.getId()) {
                            toGive.add(m);
                        }
                    }
                    for (final MapleCharacter m : toGive) {
                        int mesos = splitMeso / toGive.size();
                        if (mapitem.getDropper() instanceof MapleMonster && m.getStat().incMesoProp > 0) {
                            mesos += Math.floor((m.getStat().incMesoProp * mesos) / 100.0f);
                        }
                        m.gainMeso(mesos, true);
                    }
                    int mesos = mapitem.getMeso() - splitMeso;
                    if (mapitem.getDropper() instanceof MapleMonster && chr.getStat().incMesoProp > 0) {
                        mesos += Math.floor((chr.getStat().incMesoProp * mesos) / 100.0f);
                    }
                    chr.gainMeso(mesos, true);
                } else {
                    int mesos = mapitem.getMeso();
                    if (mapitem.getDropper() instanceof MapleMonster && chr.getStat().incMesoProp > 0) {
                        mesos += Math.floor((chr.getStat().incMesoProp * mesos) / 100.0f);
                    }
                    chr.gainMeso(mesos, true);
                }
                removeItem(chr, mapitem, pet);
            } else if (MapleItemInformationProvider.getInstance().isPickupBlocked(mapitem.getItemId())) {
                c.getPlayer().dropMessage(5, "無法拾取道具。");
            } else if (c.getPlayer().inPVP() && Integer.parseInt(c.getPlayer().getEventInstance().getProperty("ice")) == c.getPlayer().getId()) {
                c.getSession().writeAndFlush(InventoryPacket.getShowInventoryFull());
            } else if (useItem(c, mapitem.getItemId())) {
                removeItem(c.getPlayer(), mapitem, pet);
                // another hack
                if (mapitem.getItemId() / 10000 == 291) {
                    c.getPlayer().getMap().broadcastMessage(CField.getCapturePosition(c.getPlayer().getMap()));
                    c.getPlayer().getMap().broadcastMessage(CField.resetCapture());
                }
            } else if (mapitem.getItemId() / 10000 != 291 && MapleInventoryManipulator.checkSpace(c, mapitem.getItemId(), mapitem.getItem().getQuantity(), mapitem.getItem().getOwner())) {
                if (mapitem.getItem().getQuantity() >= 50 && mapitem.getItemId() == 2340000) {
                    c.setMonitored(true); // hack check
                }
                if (!ItemConstants.類型.寵物(mapitem.getItemId())) {
                    MapleInventoryManipulator.addFromDrop(c, mapitem.getItem(), true, mapitem.getDropper() instanceof MapleMonster, pet != null);
                    removeItem(chr, mapitem, pet);
                } else {
                    MapleInventoryManipulator.addById(c, mapitem.getItemId(), (short) 1, "", MaplePet.createPet(mapitem.getItemId()), MapleItemInformationProvider.getInstance().getLife(mapitem.getItemId()), MapleInventoryManipulator.DAY, null);
                    removeItem(chr, mapitem, pet);
                }
            } else if (showNullPickup) {
                c.getSession().writeAndFlush(InventoryPacket.getShowInventoryFull());
            }
        } finally {
            lock.unlock();
        }
    }

    public static final boolean useItem(final MapleClient c, final int id) {
        if (GameConstants.isUse(id)) { // TO prevent caching of everything, waste of mem
            final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
            final MapleStatEffect eff = ii.getItemEffect(id);
            if (eff == null) {
                return false;
            }
            // must hack here for ctf
            if (id / 10000 == 291) {
                boolean area = false;
                for (Rectangle rect : c.getPlayer().getMap().getAreas()) {
                    if (rect.contains(c.getPlayer().getTruePosition())) {
                        area = true;
                        break;
                    }
                }
                if (!c.getPlayer().inPVP() || (c.getPlayer().getTeam() == (id - 2910000) && area)) {
                    return false; // dont apply the consume
                }
            }
            final int consumeval = eff.getConsumeOnPickup();
            final int runeval = eff.getRunOnPickup();

            if (consumeval > 0 || runeval > 0) {
                if (consumeval > 0) {
                    switch (id) {
                        case 2023484: // 連續擊殺模式 - 藍色
                        case 2023494: // 連續擊殺模式 - 紫色
                        case 2023495: // 連續擊殺模式 - 紅色
                            int addExp = 0;
                            c.getPlayer().gainExp(addExp, true, true, true);
                            c.getSession()
                                    .writeAndFlush(EffectPacket.showEffect(true, null,
                                            UserEffectOpcode.UserEffect_FieldExpItemConsumed, new int[]{addExp}, null,
                                            null, null));
                            break;
                    }
                    consumeItem(c, eff);
                    consumeItem(c, ii.getItemEffectEX(id));
                }
                if (runeval > 0) {
                    runItem(c, id);
                }
                c.getSession().writeAndFlush(InfoPacket.getShowItemGain(id, (byte) 1));
                return true;
            }
        }
        return false;
    }

    public static final void consumeItem(final MapleClient c, final MapleStatEffect eff) {
        if (eff == null) {
            return;
        }
        if (eff.getConsumeOnPickup() == 2 && c.getPlayer().getParty() != null && c.getPlayer().isAlive()) {
            for (final MaplePartyCharacter pc : c.getPlayer().getParty().getMembers()) {
                final MapleCharacter chr = c.getPlayer().getMap().getCharacterById(pc.getId());
                if (chr != null && chr.isAlive()) {
                    eff.applyTo(chr);
                }
            }
        } else if (c.getPlayer().isAlive()) {
            eff.applyTo(c.getPlayer());
        }
    }

    public static final void runItem(final MapleClient c, final int itemId) {
        switch (itemId) {
            case 2431174: // 名譽勳章
                c.getPlayer().gainHonour(Randomizer.nextInt(16) + 5);
                break;
            default:
                c.getPlayer().dropMessage(5, "當前道具未處理。");
                c.getPlayer().gainItem(itemId, 1);
                break;
        }
    }

    private static void removeItem(final MapleCharacter chr, final MapleMapItem mapitem, MaplePet pet) {
        mapitem.setPickedUp(true);
        chr.getMap().broadcastMessage(CField.removeItemFromMap(mapitem.getObjectId(), pet == null ? 2 : 5, chr.getId(),
                pet == null ? 0 : (pet.getSummonedValue() - 1)));
        chr.getMap().removeMapObject(mapitem);
        if (mapitem.isRandDrop()) {
            chr.getMap().spawnRandDrop();
        }
    }

    private static void addMedalString(final MapleCharacter c, final StringBuilder sb) {
        final Item medal = c.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -49);
        if (medal != null) { // Medal
            sb.append("<");
            sb.append(MapleItemInformationProvider.getInstance().getName(medal.getItemId()));
            sb.append("> ");
        }
    }

    private static boolean getIncubatedItems(MapleClient c, int itemId) {
        if (c.getPlayer().getInventory(MapleInventoryType.EQUIP).getNumFreeSlot() < 2
                || c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() < 2
                || c.getPlayer().getInventory(MapleInventoryType.SETUP).getNumFreeSlot() < 2) {
            c.getPlayer().dropMessage(5, "請確認您的道具欄位空間。");
            return false;
        }
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        int id1 = RandomRewards.getPeanutReward(), id2 = RandomRewards.getPeanutReward();
        while (!ii.itemExists(id1)) {
            id1 = RandomRewards.getPeanutReward();
        }
        while (!ii.itemExists(id2)) {
            id2 = RandomRewards.getPeanutReward();
        }
        c.getSession().writeAndFlush(CWvsContext.getPeanutResult(id1, (short) 1, id2, (short) 1, itemId));
        MapleInventoryManipulator.addById(c, id1, (short) 1,
                ii.getName(itemId) + " on " + FileoutputUtil.CurrentReadable_Date());
        MapleInventoryManipulator.addById(c, id2, (short) 1,
                ii.getName(itemId) + " on " + FileoutputUtil.CurrentReadable_Date());
        // c.getSession().writeAndFlush(NPCPacket.getNPCTalk(1090000, (byte) 0,
        // "黃金花生開出道具了。\r\n#i" + id1 + "##z" + id1 + "#\r\n#i" + id2 + "##z" + id2 + "#",
        // "00 00", (byte) 0));
        return true;
    }

    public static final void OwlMinerva(final LittleEndianAccessor slea, final MapleClient c) {
        final byte slot = (byte) slea.readShort();
        final int itemid = slea.readInt();
        final Item toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(slot);
        if (toUse != null && toUse.getQuantity() > 0 && toUse.getItemId() == itemid && itemid == 2310000
                && !c.getPlayer().hasBlockedInventory()) {
            final int itemSearch = slea.readInt();
            /*
             * //僱傭商店搜尋 final List<HiredMerchant> hms =
             * c.getChannelServer().searchMerchant(itemSearch); if (hms.size() > 0) {
             * c.getSession().writeAndFlush(CWvsContext.getOwlSearched(itemSearch, hms));
             * MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, itemid, 1,
             * true, false); } else { c.getPlayer().dropMessage(1, "找不到此道具。"); }
             */

            // 掉寶搜尋
            String drops = MapleMonsterInformationProvider.getInstance().getDrops(itemSearch);
            if (drops.length() > 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("檢索完成 #i").append(itemSearch).append(":# #e#z").append(itemSearch).append("##n 有以下怪物掉落：\r\n");
                sb.append("--------------------------------------\r\n");
                sb.append(drops);
                c.getSession().writeAndFlush(CScriptMan.getNPCTalk(9010000, ScriptMessageType.SM_SAY, sb.toString(),
                        "00 00", (byte) 0, 9010000));
                MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, itemid, 1, true, false);
            } else {
                c.getPlayer().dropMessage(1, "沒有怪物噴此道具。");
            }
        }
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static final void Owl(final LittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer().haveItem(5230000, 1, true, false) || c.getPlayer().haveItem(2310000, 1, true, false)) {
            if (c.getPlayer().getMapId() >= 910000000 && c.getPlayer().getMapId() <= 910000022) {
                c.getSession().writeAndFlush(CWvsContext.getOwlOpen());
            } else {
                c.getPlayer().dropMessage(5, "限自由市場內使用。");
                c.getSession().writeAndFlush(CWvsContext.enableActions());
            }
        }
    }

    public static final int OWL_ID = 2; // don't change. 0 = owner ID, 1 = store ID, 2 = object ID

    public static final void OwlWarp(final LittleEndianAccessor slea, final MapleClient c) {
        if (!c.getPlayer().isAlive()) {
            c.getSession().writeAndFlush(CWvsContext.getOwlMessage(4));
            return;
        } else if (c.getPlayer().getTrade() != null) {
            c.getSession().writeAndFlush(CWvsContext.getOwlMessage(7));
            return;
        }
        if (c.getPlayer().getMapId() >= 910000000 && c.getPlayer().getMapId() <= 910000022
                && !c.getPlayer().hasBlockedInventory()) {
            final int id = slea.readInt();
            final int map = slea.readInt();
            if (map >= 910000001 && map <= 910000022) {
                c.getSession().writeAndFlush(CWvsContext.getOwlMessage(0));
                final MapleMap mapp = c.getChannelServer().getMapFactory().getMap(map);
                c.getPlayer().changeMap(mapp, mapp.getPortal(0));
                HiredMerchant merchant = null;
                List<MapleMapObject> objects;
                switch (OWL_ID) {
                    case 0:
                        objects = mapp.getAllHiredMerchantsThreadsafe();
                        for (MapleMapObject ob : objects) {
                            if (ob instanceof IMaplePlayerShop) {
                                final IMaplePlayerShop ips = (IMaplePlayerShop) ob;
                                if (ips instanceof HiredMerchant) {
                                    final HiredMerchant merch = (HiredMerchant) ips;
                                    if (merch.getOwnerId() == id) {
                                        merchant = merch;
                                        break;
                                    }
                                }
                            }
                        }
                        break;
                    case 1:
                        objects = mapp.getAllHiredMerchantsThreadsafe();
                        for (MapleMapObject ob : objects) {
                            if (ob instanceof IMaplePlayerShop) {
                                final IMaplePlayerShop ips = (IMaplePlayerShop) ob;
                                if (ips instanceof HiredMerchant) {
                                    final HiredMerchant merch = (HiredMerchant) ips;
                                    if (merch.getStoreId() == id) {
                                        merchant = merch;
                                        break;
                                    }
                                }
                            }
                        }
                        break;
                    default:
                        final MapleMapObject ob = mapp.getMapObject(id, MapleMapObjectType.HIRED_MERCHANT);
                        if (ob instanceof IMaplePlayerShop) {
                            final IMaplePlayerShop ips = (IMaplePlayerShop) ob;
                            if (ips instanceof HiredMerchant) {
                                merchant = (HiredMerchant) ips;
                            }
                        }
                        break;
                }
                if (merchant != null) {
                    if (merchant.isOwner(c.getPlayer())) {
                        merchant.setOpen(false);
                        merchant.removeAllVisitors((byte) 16, (byte) 0);
                        c.getPlayer().setPlayerShop(merchant);
                        c.getSession().writeAndFlush(PlayerShopPacket.getHiredMerch(c.getPlayer(), merchant, false));
                    } else if (!merchant.isOpen() || !merchant.isAvailable()) {
                        c.getPlayer().dropMessage(1,
                                "The owner of the store is currently undergoing store maintenance. Please try again in a bit.");
                    } else if (merchant.getFreeSlot() == -1) {
                        c.getPlayer().dropMessage(1, "You can't enter the room due to full capacity.");
                    } else if (merchant.isInBlackList(c.getPlayer().getName())) {
                        c.getPlayer().dropMessage(1, "You may not enter this store.");
                    } else {
                        c.getPlayer().setPlayerShop(merchant);
                        merchant.addVisitor(c.getPlayer());
                        c.getSession().writeAndFlush(PlayerShopPacket.getHiredMerch(c.getPlayer(), merchant, false));
                    }
                } else {
                    c.getPlayer().dropMessage(1, "The room is already closed.");
                }
            } else {
                c.getSession().writeAndFlush(CWvsContext.getOwlMessage(23));
            }
        } else {
            c.getSession().writeAndFlush(CWvsContext.getOwlMessage(23));
        }
    }

    public static final void PamSong(LittleEndianAccessor slea, MapleClient c) {
        final Item pam = c.getPlayer().getInventory(MapleInventoryType.CASH).findById(5640000);
        if (slea.readByte() > 0 && c.getPlayer().getScrolledPosition() != 0 && pam != null && pam.getQuantity() > 0) {
            final MapleInventoryType inv = c.getPlayer().getScrolledPosition() < 0 ? MapleInventoryType.EQUIPPED
                    : MapleInventoryType.EQUIP;
            final Item item = c.getPlayer().getInventory(inv).getItem(c.getPlayer().getScrolledPosition());
            c.getPlayer().setScrolledPosition((short) 0);
            if (item != null) {
                final Equip eq = (Equip) item;
                eq.setUpgradeSlots((byte) (eq.getUpgradeSlots() + 1));
                c.getPlayer().forceReAddItem_Flag(eq);
                MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.CASH, pam.getPosition(), (short) 1, true,
                        false);
                c.getPlayer().getMap().broadcastMessage(CField.pamsSongEffect(c.getPlayer().getId()));
            }
        } else {
            c.getPlayer().setScrolledPosition((short) 0);
        }
    }

    public static final void TeleRock(LittleEndianAccessor slea, MapleClient c) {
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final Item toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId || itemId / 10000 != 232
                || c.getPlayer().hasBlockedInventory()) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return;
        }
        boolean used = UseTeleRock(slea, c, itemId);
        if (used) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
        }
        c.getSession().writeAndFlush(CWvsContext.enableActions());
    }

    public static final boolean UseTeleRock(LittleEndianAccessor slea, MapleClient c, int itemId) {
        boolean used = false;
        if (itemId == 5041001 || itemId == 5040004) {
            slea.readByte(); // useless
        }
        if (slea.readByte() == 0) { // Rocktype
            final MapleMap target = c.getChannelServer().getMapFactory().getMap(slea.readInt());
            if ((itemId == 5041000 && c.getPlayer().isRockMap(target.getId()))
                    || (itemId != 5041000 && c.getPlayer().isRegRockMap(target.getId()))
                    || ((itemId == 5040004 || itemId == 5041001) && (c.getPlayer().isHyperRockMap(target.getId())
                    || GameConstants.isHyperTeleMap(target.getId())))) {
                if (!FieldLimitType.VipRock.check(c.getPlayer().getMap().getFieldLimit())
                        && !FieldLimitType.VipRock.check(target.getFieldLimit()) && !c.getPlayer().isInBlockedMap()) { // Makes
                    // sure
                    // this
                    // map
                    // doesn't
                    // have
                    // a
                    // forced
                    // return
                    // map
                    c.getPlayer().changeMap(target, target.getPortal(0));
                    used = true;
                }
            }
        } else {
            final MapleCharacter victim = c.getChannelServer().getPlayerStorage()
                    .getCharacterByName(slea.readMapleAsciiString());
            if (victim != null && !victim.isIntern() && c.getPlayer().getEventInstance() == null
                    && victim.getEventInstance() == null) {
                if (!FieldLimitType.VipRock.check(c.getPlayer().getMap().getFieldLimit())
                        && !FieldLimitType.VipRock
                                .check(c.getChannelServer().getMapFactory().getMap(victim.getMapId()).getFieldLimit())
                        && !victim.isInBlockedMap() && !c.getPlayer().isInBlockedMap()) {
                    if (itemId == 5041000 || itemId == 5040004 || itemId == 5041001
                            || (victim.getMapId() / 100000000) == (c.getPlayer().getMapId() / 100000000)) { // Viprock
                        // or same
                        // continent
                        c.getPlayer().changeMap(victim.getMap(),
                                victim.getMap().findClosestPortal(victim.getTruePosition()));
                        used = true;
                    }
                }
            }
        }
        return used && itemId != 5041001 && itemId != 5040004;
    }

    public static boolean checkPotentialLock(MapleCharacter chr, Equip eq, int line, int potential) {
        if (line == 0 || potential == 0) {
            return false;
        }
        if (line < 0 || line > 3) {
            System.out.println("[作弊] " + MapleCharacterUtil.makeMapleReadable(chr.getName()) + " 嘗試鎖定不存在的潛能。");
            return false;
        }
        if (line == 1 && eq.getPotential(1, false) != potential || line == 2 && eq.getPotential(2, false) != potential
                || line == 3 && eq.getPotential(3, false) != potential) {
            System.out.println("[作弊] " + MapleCharacterUtil.makeMapleReadable(chr.getName()) + " 嘗試鎖定的潛能非裝備潛能。");
            return false;
        }
        return true;
    }

    public static void UseFamiliarCardPack(MapleCharacter player) {
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        List<Integer> cards = ii.getRandomFamiliarCard(3);
        HashMap<Integer, Integer> data = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            int grade = 0;
            // TODO: 這裡要重寫一下拿到卡的機率
            int rand = Randomizer.nextInt(1000);
            if (Randomizer.nextInt(1000) >= 50) {
                if (50 <= rand && rand < 300) {
                    grade = 2;
                } else if (300 <= rand && rand < 750) {
                    grade = 0;
                } else {
                    grade = 1;
                }
            } else {
                grade = 0;
            }
            if (player.isShowInfo()) {
                player.dropMessage(-5, "[萌獸系統] 取得 " + ii.getFamiliarID(cards.get(i)) + " 等級：" + grade);
            }
            data.put(ii.getFamiliarID(cards.get(i)), grade);
            Item nItem = new Item(cards.get(i), (byte) 0, (short) 1);
            nItem.setFamiliarCard(new FamiliarCard((byte) grade));
            nItem.setGMLog("萌獸卡包獲取");
            MapleInventoryManipulator.addbyItem(player.getClient(), nItem);
        }
        player.getClient().announce(FamiliarPacket.showFamiliarCard(player, data));
    }
}
