package client;

import client.inventory.Equip;
import constants.GameConstants;
import database.ManagerDatabasePool;
import server.MapleItemInformationProvider;
import server.quest.MapleQuest;
import tools.Pair;
import tools.Triple;
import tools.data.MaplePacketLittleEndianWriter;
import tools.packet.CField;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MonsterBook implements Serializable {

    private static final long serialVersionUID = 7179541993413738569L;
    private boolean changed = false;
    private int currentSet = -1;
    private int level = 0;
    private int setScore;
    private int finishedSets;
    private final Map<Integer, Integer> cards;
    private final List<Integer> cardItems = new ArrayList<>();
    private final Map<Integer, Pair<Integer, Boolean>> sets = new HashMap<>();

    public MonsterBook(Map<Integer, Integer> cards, MapleCharacter chr) {
        this.cards = cards;
        calculateItem();
        calculateScore();

        MapleQuestStatus stat = chr.getQuestNoAdd(MapleQuest.getInstance(122800));
        if ((stat != null) && (stat.getCustomData() != null)) {
            this.currentSet = Integer.parseInt(stat.getCustomData());
            if ((!this.sets.containsKey(this.currentSet))
                    || (!((Boolean) ((Pair) this.sets.get(this.currentSet)).right))) {
                this.currentSet = -1;
            }
        }
        applyBook(chr, true);
    }

    public void applyBook(MapleCharacter chr, boolean first_login) {
        // Equip item = (Equip)
        // chr.getInventory(MapleInventoryType.EQUIPPED).getItem((short) -55);
        // if (item == null) {
        // item = (Equip)
        // MapleItemInformationProvider.getInstance().getEquipById(1172000);
        // item.setPosition((short) -55);
        // }
        // modifyBook(item);
        // if (first_login) {
        // chr.getInventory(MapleInventoryType.EQUIPPED).addFromDB(item);
        // } else {
        // chr.forceReAddItem_Book(item, MapleInventoryType.EQUIPPED);
        // chr.equipChanged();
        // }
    }

    public byte calculateScore() {
        byte returnval = 0;
        sets.clear();
        int oldLevel = level, oldSetScore = setScore;
        setScore = 0;
        finishedSets = 0;
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        for (int i : cardItems) {
            // we need the card id but we store the mob id lol
            final Integer x = ii.getSetId(i);
            if (x != null && x > 0) {
                final Triple<Integer, List<Integer>, List<Integer>> set = ii.getMonsterBookInfo(x);
                if (set != null) {
                    if (!sets.containsKey(x)) {
                        Pair<Integer, Boolean> put = sets.put(x, new Pair<>(1, Boolean.FALSE));
                    } else {
                        sets.get(x).left++;
                    }
                    if (sets.get(x).left == set.mid.size()) {
                        sets.get(x).right = Boolean.TRUE;
                        setScore += set.left;
                        if (currentSet == -1) {
                            currentSet = x;
                            returnval = 2;
                        }
                        finishedSets++;
                    }
                }
            }
        }
        level = 10;
        for (byte i = 0; i < 10; i++) {
            if (GameConstants.getSetExpNeededForLevel(i) > setScore) {
                level = i;
                break;
            }
        }
        if (level > oldLevel) {
            returnval = 2;
        } else if (setScore > oldSetScore) {
            returnval = 1;
        }
        return returnval;
    }

    public void writeCharInfoPacket(MaplePacketLittleEndianWriter mplew) {
        List<Integer> cardSize = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            cardSize.add(0);
        }
        for (Iterator i$ = this.cardItems.iterator(); i$.hasNext();) {
            int x = ((Integer) i$.next());
            cardSize.set(0, ((Integer) cardSize.get(0)) + 1);
            cardSize.set(x / 1000 % 10 + 1, ((Integer) cardSize.get(x / 1000 % 10 + 1)) + 1);
        }
        for (Iterator i$ = cardSize.iterator(); i$.hasNext();) {
            int i = ((Integer) i$.next());
            mplew.writeInt(i);
        }
        mplew.writeInt(this.setScore);
        mplew.writeInt(this.currentSet);
        mplew.writeInt(this.finishedSets);
    }

    public void writeFinished(MaplePacketLittleEndianWriter mplew) {
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        mplew.write(1);
        mplew.writeShort(this.cardItems.size());
        List<Integer> mbList = new ArrayList<>(ii.getMonsterBookList());
        Collections.sort(mbList);
        int fullCards = mbList.size() / 8 + (mbList.size() % 8 > 0 ? 1 : 0);
        mplew.writeShort(fullCards);

        for (int i = 0; i < fullCards; i++) {
            int currentMask = 1;
            int maskToWrite = 0;
            for (int y = i * 8; (y < i * 8 + 8) && (mbList.size() > y); y++) {
                if (this.cardItems.contains(mbList.get(y))) {
                    maskToWrite |= currentMask;
                }
                currentMask *= 2;
            }
            mplew.write(maskToWrite);
        }

        int fullSize = this.cardItems.size() / 2 + (this.cardItems.size() % 2 > 0 ? 1 : 0);
        mplew.writeShort(fullSize);
        for (int i = 0; i < fullSize; i++) {
            mplew.write(i == this.cardItems.size() / 2 ? 1 : 17);
        }
    }

    public void writeUnfinished(MaplePacketLittleEndianWriter mplew) {
        mplew.write(0);
        mplew.writeShort(this.cardItems.size());
        for (Iterator i$ = this.cardItems.iterator(); i$.hasNext();) {
            int i = ((Integer) i$.next());
            mplew.writeShort(i % 10000);
            mplew.write(1);
        }
    }

    public void calculateItem() {
        this.cardItems.clear();
        for (Map.Entry s : this.cards.entrySet()) {
            addCardItem(((Integer) s.getKey()), ((Integer) s.getValue()));
        }
    }

    public void addCardItem(int key, int value) {
        if (value >= 2) {
            Integer x = MapleItemInformationProvider.getInstance().getItemIdByMob(key);
            if ((x != null) && (x > 0)) {
                this.cardItems.add(x);
            }
        }
    }

    public void modifyBook(Equip eq) {
        eq.setStr((short) this.level);
        eq.setDex((short) this.level);
        eq.setInt((short) this.level);
        eq.setLuk((short) this.level);
        eq.setPotential(0, 1, false);
        eq.setPotential(0, 2, false);
        eq.setPotential(0, 3, false);
        if (this.currentSet > -1) {
            Triple<Integer, List<Integer>, List<Integer>> set = MapleItemInformationProvider.getInstance().getMonsterBookInfo(this.currentSet);
            if (set != null) {
                for (int i = 0; i < ((List) set.right).size(); i++) {
                    eq.setPotential(((Integer) ((List) set.right).get(i)), i + 1, false);
                    eq.updateState(false);
                }
            } else {
                this.currentSet = -1;
            }
        }
    }

    public int getSetScore() {
        return this.setScore;
    }

    public int getLevel() {
        return this.level;
    }

    public int getSet() {
        return this.currentSet;
    }

    public boolean changeSet(int c) {
        if ((this.sets.containsKey(c)) && (((Boolean) ((Pair) this.sets.get(c)).right))) {
            this.currentSet = c;
            return true;
        }
        return false;
    }

    public void changed() {
        this.changed = true;
    }

    public Map<Integer, Integer> getCards() {
        return this.cards;
    }

    public final int getSeen() {
        return this.cards.size();
    }

    public final int getCaught() {
        int ret = 0;
        for (Iterator i$ = this.cards.values().iterator(); i$.hasNext();) {
            int i = ((Integer) i$.next());
            if (i >= 2) {
                ret++;
            }
        }
        return ret;
    }

    public final int getLevelByCard(int cardid) {
        return this.cards.get(cardid) == null ? 0 : (this.cards.get(cardid));
    }

    public static final MonsterBook loadCards(int charid, MapleCharacter chr) throws SQLException {
        Map<Integer, Integer> cards = new LinkedHashMap<>();
        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con
                    .prepareStatement("SELECT * FROM monsterbook WHERE charid = ? ORDER BY cardid ASC")) {
                ps.setInt(1, charid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        cards.put(rs.getInt("cardid"), rs.getInt("level"));
                    }
                }
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException se) {

        }
        return new MonsterBook(cards, chr);
    }

    public final void saveCards(int charid, Connection con) throws SQLException {
        if (!this.changed) {
            return;
        }
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM monsterbook WHERE charid = ?")) {
            ps.setInt(1, charid);
            ps.execute();
        } catch (SQLException ex) {
            Logger.getLogger(MonsterBook.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public final boolean monsterCaught(MapleClient c, int cardid, String cardname) {
        if ((!this.cards.containsKey(cardid)) || ((this.cards.get(cardid)) < 2)) {
            this.changed = true;
            // c.getPlayer().dropMessage(-6, new
            // StringBuilder().append(cardname).append("已成功登記至怪物卡冊.").toString());
            c.getSession().writeAndFlush(CField.EffectPacket.showMonsterBookEffect());
            this.cards.put(cardid, 2);
            // if (c.getPlayer().getQuestStatus(50195) != 1) {
            // MapleQuest.getInstance(50195).forceStart(c.getPlayer(), 9010000, "1");
            // }
            // if (c.getPlayer().getQuestStatus(50196) != 1) {
            // MapleQuest.getInstance(50196).forceStart(c.getPlayer(), 9010000, "1");
            // }
            // addCardItem(cardid, 2);
            // byte rr = calculateScore();
            // if (rr > 0) {
            // if (c.getPlayer().getQuestStatus(50197) != 1) {
            // MapleQuest.getInstance(50197).forceStart(c.getPlayer(), 9010000, "1");
            // }
            // c.getSession().writeAndFlush(CField.EffectPacket.showEffect(0x3D));
            // if (rr > 1) {
            // applyBook(c.getPlayer(), false);
            // }
            // }
            return true;
        }
        return false;
    }

    public boolean hasCard(int cardid) {
        return this.cardItems == null ? false : this.cardItems.contains(cardid);
    }

    public final void monsterSeen(MapleClient c, int cardid, String cardname) {
        if (this.cards.containsKey(cardid)) {
            return;
        }
        this.changed = true;
        // c.getPlayer().dropMessage(-6, new
        // StringBuilder().append(cardname).append("已成功登記至怪物卡冊.").toString());
        this.cards.put(cardid, 1);
        c.getSession().writeAndFlush(CField.EffectPacket.showMonsterBookEffect());
    }
}
