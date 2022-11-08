/*
 This file is part of the ZeroFusion MapleStory Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc> 
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>
 ZeroFusion organized by "RMZero213" <RMZero213@hotmail.com>

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
package tools.wztosql;

import client.inventory.MapleInventoryType;
import constants.GameConstants;
import database.ManagerDatabasePool;
import provider.*;
import server.swing.Progressbar;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;

public class DumpItems {

    private final MapleDataProvider item, character, string = MapleDataProviderFactory.getDataProvider("String");
    private final MapleData cashStringData = Objects.requireNonNull(string).getData("Cash.img");
    private final MapleData consumeStringData = string.getData("Consume.img");
    private final MapleData eqpStringData = string.getData("Eqp.img");
    private final MapleData etcStringData = string.getData("Etc.img");
    private final MapleData insStringData = string.getData("Ins.img");
    private final MapleData petStringData = string.getData("Pet.img");
    private final Set<Integer> doneIds = new LinkedHashSet<>();
    private boolean hadError = false;
    protected boolean update;
    protected int id = 0;
    private final Connection con = ManagerDatabasePool.getConnection();

    private DumpItems(boolean update) throws SQLException {
        this.update = update;
        this.item = MapleDataProviderFactory.getDataProvider("Item");
        this.character = MapleDataProviderFactory.getDataProvider("Character");
        if (item == null || string == null || character == null) {
            hadError = true;
        }
    }

    private boolean isHadError() {
        return hadError;
    }

    private void dumpItems() throws Exception {
        if (!hadError) {
            PreparedStatement psa = con.prepareStatement(
                    "INSERT INTO wz_itemadddata(itemid, `key`, `subKey`, `value`) VALUES (?, ?, ?, ?)");
            PreparedStatement psr = con.prepareStatement(
                    "INSERT INTO wz_itemrewarddata(itemid, item, prob, quantity, period, worldMsg, effect) VALUES (?, ?, ?, ?, ?, ?, ?)");
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO wz_itemdata(itemid, name, msg, `desc`, slotMax, price, unitPrice, wholePrice, stateChange, flags, karma, meso, monsterBook, itemMakeLevel, questId, scrollReqs, consumeItem, totalprob, incSkill, replaceId, replaceMsg, `create`, afterImage) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            PreparedStatement pse = con.prepareStatement(
                    "INSERT INTO wz_itemequipdata(itemid, itemLevel, `key`, `value`) VALUES (?, ?, ?, ?)");
            try {
                dumpItems(psa, psr, ps, pse);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(id + " quest.");
                hadError = true;
            } finally {
                psa.executeBatch();
                psa.close();
                psr.executeBatch();
                psr.close();
                pse.executeBatch();
                pse.close();
                ps.executeBatch();
                ps.close();
            }
        }
    }

    public void delete(String sql) throws Exception {
        Progressbar.addValue();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private boolean doesExist(String sql) throws Exception {
        boolean ret;
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            ret = rs.next();
        }
        return ret;
    }

    private void dumpItems(MapleDataProvider d, PreparedStatement psa, PreparedStatement psr, PreparedStatement ps,
            PreparedStatement pse, boolean charz) throws Exception {
        for (MapleDataDirectoryEntry topDir : d.getRoot().getSubdirectories()) { // requirements first
            if (!topDir.getName().equalsIgnoreCase("Special") && !topDir.getName().equalsIgnoreCase("Afterimage")) {
                for (MapleDataFileEntry ifile : topDir.getFiles()) {
                    if (Progressbar.getValue() < (!charz ? 50 : 90)) {
                        Progressbar.addValue();
                    }
                    final MapleData iz = d.getData(topDir.getName() + "/" + ifile.getName());
                    if (charz || topDir.getName().equalsIgnoreCase("Pet")) {
                        dumpItem(psa, psr, ps, pse, iz);
                    } else {
                        for (MapleData itemData : iz.getChildren()) {
                            dumpItem(psa, psr, ps, pse, itemData);
                        }
                    }
                }
            }
        }
    }

    private void dumpItem(PreparedStatement psa, PreparedStatement psr, PreparedStatement ps, PreparedStatement pse,
            MapleData iz) throws Exception {
        try {
            if (iz.getName().endsWith(".img")) {
                this.id = Integer.parseInt(iz.getName().substring(0, iz.getName().length() - 4));
            } else {
                this.id = Integer.parseInt(iz.getName());
            }
        } catch (NumberFormatException nfe) { // not we need
            return;
        }
        if (doneIds.contains(id) || GameConstants.getInventoryType(id) == MapleInventoryType.UNDEFINED) {
            return;
        }
        doneIds.add(id);
        if (update && doesExist("SELECT * FROM wz_itemdata WHERE itemid = " + id)) {
            return;
        }
        ps.setInt(1, id);
        final MapleData stringData = getStringData(id);
        if (stringData == null) {
            ps.setString(2, "");
            ps.setString(3, "");
            ps.setString(4, "");
        } else {
            ps.setString(2, MapleDataTool.getString("name", stringData, ""));
            ps.setString(3, MapleDataTool.getString("msg", stringData, ""));
            ps.setString(4, MapleDataTool.getString("desc", stringData, ""));
        }
        Progressbar.setText("轉存道具[" + (stringData == null ? "None" : MapleDataTool.getString("name", stringData, ""))
                + "(" + id + ")]...");
        short ret;
        final MapleData smEntry = iz.getChildByPath("info/slotMax");
        if (smEntry == null) {
            if (GameConstants.getInventoryType(id) == MapleInventoryType.EQUIP) {
                ret = 1;
            } else {
                ret = 100;
            }
        } else {
            ret = (short) MapleDataTool.getIntConvert(smEntry, -1);
        }
        ps.setInt(5, ret);
        double pEntry;
        MapleData pData = iz.getChildByPath("info/price");
        MapleData aData = iz.getChildByPath("info/autoPrice");
        boolean autoPrice = MapleDataTool.getIntConvert(aData, 0) > 0;
        if (pData == null && !autoPrice) {
            pEntry = -1.0;
        } else if (autoPrice) {
            MapleData lvData = iz.getChildByPath("info/lv");
            pEntry = MapleDataTool.getIntConvert(lvData, 0) * 2;
        } else {
            pEntry = MapleDataTool.getIntConvert(pData, -1);
        }
        ps.setString(6, String.valueOf(pEntry));
        ps.setString(7, String.valueOf(MapleDataTool.getDoubleConvert("info/unitPrice", iz, 0.0)));
        ps.setInt(8, MapleDataTool.getIntConvert("info/price", iz, -1));
        ps.setInt(9, MapleDataTool.getIntConvert("info/stateChangeItem", iz, 0));
        int flags = MapleDataTool.getIntConvert("info/bagType", iz, 0);
        if (MapleDataTool.getIntConvert("info/notSale", iz, 0) > 0) {
            flags |= 0x10;
        }
        if (MapleDataTool.getIntConvert("info/expireOnLogout", iz, 0) > 0) {
            flags |= 0x20;
        }
        if (MapleDataTool.getIntConvert("info/pickUpBlock", iz, 0) > 0) {
            flags |= 0x40;
        }
        if (MapleDataTool.getIntConvert("info/only", iz, 0) > 0) {
            flags |= 0x80;
        }
        if (MapleDataTool.getIntConvert("info/accountSharable", iz, 0) > 0) {
            flags |= 0x100;
        }
        if (MapleDataTool.getIntConvert("info/quest", iz, 0) > 0) {
            flags |= 0x200;
        }
        if (id != 4310008 && MapleDataTool.getIntConvert("info/tradeBlock", iz, 0) > 0) {
            flags |= 0x400;
        }
        if (MapleDataTool.getIntConvert("info/accountShareTag", iz, 0) > 0) {
            flags |= 0x800;
        }
        if (MapleDataTool.getIntConvert("info/mobHP", iz, 0) > 0
                && MapleDataTool.getIntConvert("info/mobHP", iz, 0) < 100) {
            flags |= 0x1000;
        }
        if (MapleDataTool.getIntConvert("info/sharableOnce", iz, 0) > 0) {
            flags |= 0x2000;
        }
        ps.setInt(10, flags);
        ps.setInt(11, MapleDataTool.getIntConvert("info/tradeAvailable", iz, 0));
        ps.setInt(12, MapleDataTool.getIntConvert("info/meso", iz, 0));
        ps.setInt(13, MapleDataTool.getIntConvert("info/mob", iz, 0));
        ps.setInt(14, MapleDataTool.getIntConvert("info/lv", iz, 0));
        ps.setInt(15, MapleDataTool.getIntConvert("info/questId", iz, 0));
        int totalprob = 0;
        StringBuilder scrollReqs = new StringBuilder(), consumeItem = new StringBuilder(),
                incSkill = new StringBuilder();
        MapleData dat = iz.getChildByPath("req");
        if (dat != null) {
            for (MapleData req : dat.getChildren()) {
                if (scrollReqs.length() > 0) {
                    scrollReqs.append(",");
                }
                scrollReqs.append(MapleDataTool.getIntConvert(req, 0));
            }
        }
        dat = iz.getChildByPath("consumeItem");
        if (dat != null) {
            for (MapleData req : dat.getChildren()) {
                if (consumeItem.length() > 0) {
                    consumeItem.append(",");
                }
                consumeItem.append(MapleDataTool.getIntConvert(req, 0));
            }
        }
        ps.setString(16, scrollReqs.toString());
        ps.setString(17, consumeItem.toString());
        Map<Integer, Map<String, Integer>> equipStats = new HashMap<>();
        equipStats.put(-1, new HashMap<>());
        dat = iz.getChildByPath("mob");
        if (dat != null) {
            for (MapleData child : dat.getChildren()) {
                equipStats.get(-1).put("mob" + MapleDataTool.getIntConvert("id", child, 0),
                        MapleDataTool.getIntConvert("prob", child, 0));
            }
        }
        dat = iz.getChildByPath("info/level/case");
        if (dat != null) {
            for (MapleData info : dat.getChildren()) {
                for (MapleData data : info.getChildren()) {
                    String[] skillString = { "Skill", "EquipmentSkill" };
                    for (String s : skillString) {
                        if (data.getName().length() == 1 && data.getChildByPath(s) != null) {
                            for (MapleData skil : data.getChildByPath(s).getChildren()) {
                                int incSkillz = MapleDataTool.getIntConvert("id", skil, 0);
                                if (incSkillz != 0) {
                                    if (incSkill.length() > 0) {
                                        incSkill.append(",");
                                    }
                                    incSkill.append(incSkillz);
                                }
                            }
                        }
                    }
                }
            }
        }
        dat = iz.getChildByPath("info/level");
        if (dat != null && MapleDataTool.getIntConvert("fixLevel", dat, 0) != 0) {
            if (MapleDataTool.getIntConvert("fixLevel", dat, 0) != 0) {
                equipStats.put(-1, new HashMap<>());
                equipStats.get(-1).put("fixLevel", MapleDataTool.getIntConvert("fixLevel", dat));
            }
        }
        dat = iz.getChildByPath("info/level/info");
        if (dat != null) {
            for (MapleData info : dat.getChildren()) {
                if (MapleDataTool.getIntConvert("exp", info, 0) == 0) {
                    continue;
                }
                final int lv = Integer.parseInt(info.getName());
                if (equipStats.containsKey(lv) || equipStats.get(lv) == null) {
                    equipStats.put(lv, new HashMap<>());
                }
                for (MapleData data : info.getChildren()) {
                    if (data.getName().length() > 3) {
                        equipStats.get(lv).put(data.getName().substring(3), MapleDataTool.getIntConvert(data, 0));
                    }
                }
            }
        }
        dat = iz.getChildByPath("info");
        if (dat != null) {
            ps.setString(23, MapleDataTool.getString("afterImage", dat, ""));
            final Map<String, Integer> rett = equipStats.get(-1);
            for (final MapleData data : dat.getChildren()) {
                if (data.getName().startsWith("inc")) {
                    int gg = (int) MapleDataTool.getLongConvert(data, 0);
                    if (gg != 0) {
                        rett.put(data.getName().substring(3), gg);
                    }
                }
            }
            // save sql, only do the ones that exist
            for (String stat : GameConstants.stats) {
                final MapleData d = dat.getChildByPath(stat);
                if (stat.equals("canLevel")) {
                    if (dat.getChildByPath("level") != null) {
                        rett.put(stat, 1);
                    }
                } else if (d != null) {

                    if (stat.equals("skill")) {
                        for (int i = 0; i < d.getChildren().size(); i++) { // List of allowed skillIds
                            rett.put("skillid" + i, MapleDataTool.getIntConvert(Integer.toString(i), d, 0));
                        }
                    } else {
                        final int dd = MapleDataTool.getIntConvert(d, 0);
                        if (dd != 0) {
                            rett.put(stat, dd);
                        }
                    }
                }
            }
        } else {
            ps.setString(23, "");
        }
        pse.setInt(1, id);
        for (Entry<Integer, Map<String, Integer>> stats : equipStats.entrySet()) {
            pse.setInt(2, stats.getKey());
            for (Entry<String, Integer> stat : stats.getValue().entrySet()) {
                pse.setString(3, stat.getKey());
                pse.setInt(4, stat.getValue());
                pse.addBatch();
            }
        }
        dat = iz.getChildByPath("info/addition");
        if (dat != null) {
            psa.setInt(1, id);
            for (MapleData d : dat.getChildren()) {
                switch (d.getName()) {
                case "statinc":
                case "critical":
                case "skill":
                case "mobdie":
                case "hpmpchange":
                case "elemboost":
                case "elemBoost":
                case "mobcategory":
                case "boss":
                    for (MapleData subKey : d.getChildren()) {
                        if (subKey.getName().equals("con")) {
                            for (MapleData conK : subKey.getChildren()) {
                                switch (conK.getName()) {
                                case "job":
                                    StringBuilder sbbb = new StringBuilder();
                                    if (conK.getData() == null) { // a loop
                                        for (MapleData ids : conK.getChildren()) {
                                            sbbb.append(ids.getData().toString());
                                            sbbb.append(",");
                                        }
                                        sbbb.deleteCharAt(sbbb.length() - 1);
                                    } else {
                                        sbbb.append(conK.getData().toString());
                                    }
                                    psa.setString(2, d.getName().equals("elemBoost") ? "elemboost" : d.getName());
                                    psa.setString(3, "con:job");
                                    psa.setString(4, sbbb.toString());
                                    psa.addBatch();
                                    break;
                                case "weekDay":
                                    // 01142367
                                    continue;
                                default:
                                    psa.setString(2, d.getName().equals("elemBoost") ? "elemboost" : d.getName());
                                    psa.setString(3, "con:" + conK.getName());
                                    psa.setString(4, conK.getData().toString());
                                    psa.addBatch();
                                    break;
                                }
                            }
                        } else {
                            psa.setString(2, d.getName().equals("elemBoost") ? "elemboost" : d.getName());
                            psa.setString(3, subKey.getName());
                            psa.setString(4, subKey.getData().toString());
                            psa.addBatch();
                        }
                    }
                    break;
                default:
                    System.out.println("UNKNOWN EQ ADDITION : " + d.getName() + " from " + id);
                    break;
                }
            }
        }
        dat = iz.getChildByPath("reward");
        if (dat != null) {
            psr.setInt(1, id);
            for (MapleData reward : dat.getChildren()) {
                psr.setInt(2, MapleDataTool.getIntConvert("item", reward, 0));
                psr.setInt(3, MapleDataTool.getIntConvert("prob", reward, 0));
                psr.setInt(4, MapleDataTool.getIntConvert("count", reward, 0));
                psr.setInt(5, MapleDataTool.getIntConvert("period", reward, 0));
                psr.setString(6, MapleDataTool.getString("worldMsg", reward, ""));
                psr.setString(7, MapleDataTool.getString("Effect", reward, ""));
                psr.addBatch();
                totalprob += MapleDataTool.getIntConvert("prob", reward, 0);
            }
        }
        ps.setInt(18, totalprob);
        ps.setString(19, incSkill.toString());
        dat = iz.getChildByPath("replace");
        if (dat != null) {
            ps.setInt(20, MapleDataTool.getInt("itemid", dat, 0));
            ps.setString(21, MapleDataTool.getString("msg", dat, ""));
        } else {
            ps.setInt(20, 0);
            ps.setString(21, "");
        }
        ps.setInt(22, MapleDataTool.getInt("info/create", iz, 0));
        ps.addBatch();
    }
    // kinda inefficient

    private void dumpItems(PreparedStatement psa, PreparedStatement psr, PreparedStatement ps, PreparedStatement pse)
            throws Exception {
        if (!update) {
            Progressbar.setText("清理數據庫...");
            delete("DELETE FROM wz_itemdata");
            delete("DELETE FROM wz_itemequipdata");
            delete("DELETE FROM wz_itemadddata");
            delete("DELETE FROM wz_itemrewarddata");
            System.out.println("清空\"wz_itemdata\"庫成功");
        }
        System.out.println("正在添加進\"wz_itemdata\"庫.....");
        dumpItems(item, psa, psr, ps, pse, false);
        dumpItems(character, psa, psr, ps, pse, true);
        System.out.println("完成\"wz_itemdata\"庫的轉存, 轉存還在進行請勿關閉...");
    }

    private int currentId() {
        return id;
    }

    public static void start(String[] args) {
        Progressbar.setValue(0);
        Progressbar.setText("轉存道具...");
        boolean hadError;
        boolean update = false;
        long startTime = System.currentTimeMillis();
        for (String file : args) {
            if (file.equalsIgnoreCase("-update")) {
                update = true;
            }
        }
        int currentQuest = 0;
        try {
            final DumpItems dq = new DumpItems(update);
            System.out.println("轉存道具");
            dq.dumpItems();
            hadError = dq.isHadError();
            currentQuest = dq.currentId();
        } catch (Exception e) {
            hadError = true;
            e.printStackTrace();
            System.out.println(currentQuest + " quest.");
        }
        long endTime = System.currentTimeMillis();
        double elapsedSeconds = (endTime - startTime) / 1000.0;
        int elapsedSecs = (((int) elapsedSeconds) % 60);
        int elapsedMinutes = (int) (elapsedSeconds / 60.0);

        String withErrors = "";
        if (hadError) {
            withErrors = "(有錯誤)";
        }
        Progressbar.setValue(100);
        System.out.println("完成" + withErrors + "，耗時：" + elapsedMinutes + "分" + elapsedSecs + "秒");
    }

    private MapleData getStringData(final int itemId) {
        String cat = null;
        MapleData data;

        if (itemId >= 5010000) {
            data = cashStringData;
        } else if (itemId >= 2000000 && itemId < 3000000) {
            data = consumeStringData;
        } else if ((itemId >= 1010000 && itemId < 1040000) || (itemId >= 1122000 && itemId < 1123000)
                || (itemId >= 1132000 && itemId < 1210000)) {
            data = eqpStringData;
            cat = "Eqp/Accessory";
        } else if (itemId >= 1662000 && itemId < 1680000) {
            data = eqpStringData;
            cat = "Eqp/Android";
        } else if (itemId >= 1680000 && itemId < 1690000) {
            data = eqpStringData;
            cat = "Eqp/Bits";
        } else if (itemId >= 1000000 && itemId < 1010000) {
            data = eqpStringData;
            cat = "Eqp/Cap";
        } else if (itemId >= 1100000 && itemId < 1103000) {
            data = eqpStringData;
            cat = "Eqp/Cape";
        } else if (itemId >= 1040000 && itemId < 1050000) {
            data = eqpStringData;
            cat = "Eqp/Coat";
        } else if (itemId >= 1920000 && itemId < 2000000) {
            data = eqpStringData;
            cat = "Eqp/Dragon";
        } else if (itemId >= 20000 && itemId < 25000) {
            data = eqpStringData;
            cat = "Eqp/Face";
        } else if (itemId >= 1080000 && itemId < 1090000) {
            data = eqpStringData;
            cat = "Eqp/Glove";
        } else if (itemId >= 30000 && itemId < 50000) {
            data = eqpStringData;
            cat = "Eqp/Hair";
        } else if (itemId >= 1050000 && itemId < 1060000) {
            data = eqpStringData;
            cat = "Eqp/Longcoat";
        } else if (itemId >= 1610000 && itemId < 1660000) {
            data = eqpStringData;
            cat = "Eqp/Mechanic";
        } else if (itemId >= 1842000 && itemId < 1893000) {
            data = eqpStringData;
            cat = "Eqp/MonsterBattle";
        } else if (itemId >= 1060000 && itemId < 1070000) {
            data = eqpStringData;
            cat = "Eqp/Pants";
        } else if (itemId >= 1802000 && itemId < 1820000) {
            data = eqpStringData;
            cat = "Eqp/PetEquip";
        } else if (itemId >= 1112000 && itemId < 1130000) {
            data = eqpStringData;
            cat = "Eqp/Ring";
        } else if (itemId >= 1092000 && itemId < 1100000) {
            data = eqpStringData;
            cat = "Eqp/Shield";
        } else if (itemId >= 1070000 && itemId < 1080000) {
            data = eqpStringData;
            cat = "Eqp/Shoes";
        } else if (itemId >= 1603000 && itemId < 1604000) {
            data = eqpStringData;
            cat = "Eqp/Skillskin";
        } else if (itemId >= 1900000 && itemId < 2000000) {
            data = eqpStringData;
            cat = "Eqp/Taming";
        } else if (itemId >= 1210000 && itemId < 1800000) {
            data = eqpStringData;
            cat = "Eqp/Weapon";
        } else if (itemId >= 1172000 && itemId < 1180000) {
            data = eqpStringData;
            cat = "Eqp/MonsterBook";
        } else if (itemId >= 4000000 && itemId < 5000000) {
            data = etcStringData;
            cat = "Etc";
        } else if (itemId >= 3000000 && itemId < 4000000) {
            data = insStringData;
        } else if (itemId >= 5000000 && itemId < 5010000) {
            data = petStringData;
        } else {
            return null;
        }
        if (cat == null) {
            return data.getChildByPath(String.valueOf(itemId));
        } else {
            return data.getChildByPath(cat + "/" + itemId);
        }
    }
}
